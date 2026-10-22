package dev.proprio.wear.sensor

import dev.proprio.protocol.AppInfo
import dev.proprio.protocol.DeviceInfo
import dev.proprio.protocol.Meta
import dev.proprio.protocol.SegmentMark
import dev.proprio.protocol.SessionKind
import dev.proprio.protocol.SessionWriter
import dev.proprio.wear.DeviceClock
import dev.proprio.wear.captureAnchor
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Live numbers for the UI and for `WatchStatus`. */
data class RecorderSnapshot(
    val accRateHz: Double = 0.0,
    val gyrRateHz: Double = 0.0,
    val accWritten: Long = 0,
    val gyrWritten: Long = 0,
    /** Events discarded because the queue was full (see queue-overflow policy on [SensorRecorder]). */
    val droppedEvents: Long = 0,
    /** Events skipped because a value was NaN/Infinite (the CSV writer refuses them). */
    val rejectedEvents: Long = 0,
    /** Set when the writer coroutine hit an I/O error; recording no longer persists samples. */
    val writeError: String? = null,
)

/**
 * Captures raw accelerometer and gyroscope events into a Raw Format v1 session.
 *
 * - Both sensors are requested at [PERIOD_US] with `maxReportLatencyUs = 0` through [SensorSource],
 *   which delivers on its own sensor thread.
 * - The sensor thread only updates a rate estimate and offers the event to a bounded [Channel]; a
 *   writer coroutine on [dispatcher] drains it into [SessionWriter]. `event.timestamp` is written
 *   exactly as delivered: no resampling, no unit conversion, no reordering or de-duplication.
 *
 * **Queue-overflow policy: drop newest, count it.** The sensor thread must never block (it would
 * stall the sensor HAL callback), so when the queue is full the incoming event is discarded and
 * [RecorderSnapshot.droppedEvents] is incremented (logged at session end). The default capacity of
 * [DEFAULT_QUEUE_CAPACITY] events is about 10 minutes at 2 x 50 Hz, so overflow only happens if
 * disk I/O stalls for minutes. The Raw Format has no field for the count, so it is reported via
 * the snapshot and the log only; dropped events show up as gaps in the data.
 */
class SensorRecorder(
    private val source: SensorSource,
    private val clock: DeviceClock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val flushIntervalMs: Long = SessionWriter.DEFAULT_FLUSH_INTERVAL_MS,
    private val log: (String) -> Unit = {},
) {
    private class Sample(val type: Int, val tNs: Long, val x: Float, val y: Float, val z: Float)

    private val accRate = RateEstimator()
    private val gyrRate = RateEstimator()
    private val dropped = AtomicLong()
    private val rejected = AtomicLong()
    @Volatile private var accWritten = 0L
    @Volatile private var gyrWritten = 0L
    @Volatile private var writeError: String? = null

    private var writer: SessionWriter? = null
    private var channel: Channel<Sample>? = null
    private var drainJob: Job? = null

    val isRecording: Boolean get() = writer != null

    fun snapshot() = RecorderSnapshot(
        accRateHz = accRate.rateHz,
        gyrRateHz = gyrRate.rateHz,
        accWritten = accWritten,
        gyrWritten = gyrWritten,
        droppedEvents = dropped.get(),
        rejectedEvents = rejected.get(),
        writeError = writeError,
    )

    /**
     * Opens the session in [dir] and starts capturing. Order (spec 3.1): describe sensors, capture the
     * start anchor, create the writer, then register the listeners, so the anchor precedes every event.
     * @throws IllegalStateException if already recording, a sensor is missing, or registration fails.
     */
    fun start(
        scope: CoroutineScope,
        dir: File,
        sessionId: String,
        kind: SessionKind,
        app: AppInfo,
        device: DeviceInfo,
    ) {
        check(writer == null) { "already recording" }
        val acc = source.describe(TYPE_ACC) ?: error("no accelerometer")
        val gyr = source.describe(TYPE_GYR) ?: error("no gyroscope")
        val sensors = mapOf(
            "acc" to acc.copy(requestedPeriodUs = PERIOD_US, maxReportLatencyUs = MAX_REPORT_LATENCY_US),
            "gyr" to gyr.copy(requestedPeriodUs = PERIOD_US, maxReportLatencyUs = MAX_REPORT_LATENCY_US),
        )
        val anchor = clock.captureAnchor()
        val meta = Meta(
            sessionId = sessionId,
            kind = kind,
            complete = false,
            app = app,
            device = device,
            clockAnchorStart = anchor,
            sensors = sensors,
            startedAtUtc = Instant.ofEpochMilli(anchor.unixTimeNs / 1_000_000L).toString(),
        )
        val w = SessionWriter(dir, meta, flushIntervalMs)
        val ch = Channel<Sample>(queueCapacity)
        dropped.set(0); rejected.set(0); accWritten = 0; gyrWritten = 0; writeError = null
        writer = w
        channel = ch
        drainJob = scope.launch(dispatcher) { drain(w, ch) }
        val ok = source.start(PERIOD_US, MAX_REPORT_LATENCY_US) { type, tNs, x, y, z ->
            when (type) {
                TYPE_ACC -> accRate.add(tNs)
                TYPE_GYR -> gyrRate.add(tNs)
            }
            if (ch.trySend(Sample(type, tNs, x, y, z)).isFailure) dropped.incrementAndGet()
        }
        if (!ok) {
            abort()
            error("sensor registration failed")
        }
    }

    private suspend fun drain(w: SessionWriter, ch: Channel<Sample>) {
        for (s in ch) {
            if (writeError != null) continue
            try {
                when (s.type) {
                    TYPE_ACC -> { w.writeAcc(s.tNs, s.x, s.y, s.z); accWritten++ }
                    TYPE_GYR -> { w.writeGyr(s.tNs, s.x, s.y, s.z); gyrWritten++ }
                }
            } catch (e: IllegalArgumentException) {
                rejected.incrementAndGet() // non-finite value
            } catch (e: java.io.IOException) {
                writeError = e.message ?: "I/O error"
                log("writer I/O error: $writeError")
            }
        }
    }

    /** Adds a completed segment mark to `segments.csv` (flushed immediately). */
    fun addSegment(mark: SegmentMark) {
        writer?.addSegment(mark)
    }

    /**
     * Unregisters the sensors, captures the end anchor, drains the queue and finalizes `meta.json`
     * (`complete = true`). Returns the final meta.
     */
    suspend fun stop(): Meta {
        val w = checkNotNull(writer) { "not recording" }
        source.stop()
        val endAnchor = clock.captureAnchor()
        channel?.close()
        drainJob?.join()
        val meta = w.finish(endAnchor, Instant.ofEpochMilli(endAnchor.unixTimeNs / 1_000_000L).toString())
        log("recording stopped: ${snapshot()}")
        reset()
        return meta
    }

    /** Stops capture without finalizing; `meta.json` stays `complete = false` and files stay readable. */
    fun abort() {
        runCatching { source.stop() }
        channel?.close()
        writer?.close()
        drainJob?.cancel()
        reset()
    }

    private fun reset() {
        writer = null
        channel = null
        drainJob = null
    }

    companion object {
        const val PERIOD_US = 20_000
        const val MAX_REPORT_LATENCY_US = 0
        const val DEFAULT_QUEUE_CAPACITY = 60_000
    }
}
