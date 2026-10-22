package dev.proprio.wear.session

import dev.proprio.protocol.AppInfo
import dev.proprio.protocol.DataLayer
import dev.proprio.protocol.DeviceInfo
import dev.proprio.protocol.MarkKind
import dev.proprio.protocol.SegmentEvent
import dev.proprio.protocol.SegmentSource
import dev.proprio.protocol.SessionAck
import dev.proprio.protocol.SessionKind
import dev.proprio.protocol.WatchStatus
import dev.proprio.wear.DeviceClock
import dev.proprio.wear.sensor.SensorRecorder
import dev.proprio.wear.transfer.AckResult
import dev.proprio.wear.transfer.TransferManager
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Sends messages to the paired phone (MessageClient). Failures are swallowed by the caller. */
fun interface PhoneLink {
    suspend fun sendMessage(path: String, payload: ByteArray)
}

interface Haptics {
    /** Short buzz: segment start. */
    fun startBuzz()

    /** Double buzz: segment stop. */
    fun stopBuzz()

    /** Calibration step cue. */
    fun cue()
}

/** Keeps the process alive while recording (foreground service). */
interface ForegroundHost {
    /** Starts the foreground service; false when the OS refuses (e.g. app in background on API 31+). */
    fun enterForeground(): Boolean
    fun exitForeground()
}

data class CalibrationUi(
    val stepId: String,
    val title: String,
    val instruction: String,
    val inTransition: Boolean,
    val remainingS: Int,
    val index: Int,
    val count: Int,
)

data class UiState(
    val recording: Boolean = false,
    val sessionId: String? = null,
    val kind: SessionKind? = null,
    val openSegmentId: Int? = null,
    val openSegmentKind: MarkKind? = null,
    val elapsedS: Double = 0.0,
    val accRateHz: Double = 0.0,
    val gyrRateHz: Double = 0.0,
    val calibration: CalibrationUi? = null,
    val pendingTransfers: Int = 0,
    val transferring: Boolean = false,
    val message: String? = null,
)

/**
 * Single owner of the session life cycle. The UI, the foreground service and the phone command
 * handler all call the same suspend functions; only [SegmentSource] differs. Operations are
 * serialized by [mutex]. Timestamps come from [clock] (sensor clock).
 */
class RecordingController(
    private val recorder: SensorRecorder,
    private val clock: DeviceClock,
    private val transfer: TransferManager,
    private val phone: PhoneLink,
    private val haptics: Haptics,
    private val host: ForegroundHost,
    private val app: AppInfo,
    private val device: DeviceInfo,
    private val scope: CoroutineScope,
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
    private val log: (String) -> Unit = {},
    private val tickMs: Long = 100,
    private val statusIntervalMs: Long = STATUS_INTERVAL_MS,
    private val retryBackoffMs: List<Long> = listOf(15_000, 30_000, 60_000, 120_000, 300_000),
    private val calibrationSteps: List<CalibrationStep> = CalibrationSequencer.DEFAULT_STEPS,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var sessionId: String? = null
    private var kind: SessionKind? = null
    private var sessionStartNs = 0L
    private var tracker: SegmentTracker? = null
    private var sequencer: CalibrationSequencer? = null
    private var lastStatusMs = 0L
    private var retryJob: Job? = null

    private val recording get() = sessionId != null

    // ---- commands -------------------------------------------------------------------------

    suspend fun startSession(kind: SessionKind, source: SegmentSource = SegmentSource.WATCH): Boolean = mutex.withLock {
        if (recording) {
            log("startSession ignored: session ${sessionId} already running")
            return false
        }
        if (!host.enterForeground()) {
            fail("Cannot start: open the watch app first")
            return false
        }
        val id = newSessionId()
        try {
            recorder.start(scope, transfer.sessionDir(id), id, kind, app, device)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            host.exitForeground()
            fail("Cannot start: ${e.message}")
            return false
        }
        sessionId = id
        this.kind = kind
        sessionStartNs = clock.elapsedRealtimeNs()
        tracker = SegmentTracker({ clock.elapsedRealtimeNs() }, recorder::addSegment, log)
        sequencer = if (kind == SessionKind.CALIBRATION) {
            CalibrationSequencer({ clock.elapsedRealtimeNs() / 1_000_000L }, calibrationSteps).also { it.start() }
        } else {
            null
        }
        lastStatusMs = nowMs()
        log("session $id started (${source.name.lowercase()}, ${kind.name.lowercase()})")
        publish(message = null)
        scope.launch { tickLoop(id) }
        true
    }

    suspend fun endSession(source: SegmentSource = SegmentSource.WATCH): Boolean = mutex.withLock { endLocked(source) }

    suspend fun startSegment(kind: MarkKind, source: SegmentSource): Boolean = mutex.withLock {
        if (!recording) {
            log("startSegment ignored: no session")
            return false
        }
        if (this.kind == SessionKind.CALIBRATION) {
            log("startSegment ignored: calibration segments are automatic")
            return false
        }
        openSegment(kind, source, null)
        true
    }

    suspend fun stopSegment(source: SegmentSource): Boolean = mutex.withLock {
        if (!recording || kind == SessionKind.CALIBRATION) return false
        closeSegment() != null
    }

    suspend fun handleAck(ack: SessionAck): AckResult {
        val result = transfer.onAck(ack)
        if (result == AckResult.KEPT_RETRY) scheduleRetry(listOf(2_000L) + retryBackoffMs)
        publish()
        return result
    }

    /** App start / capability change: zip crash leftovers and resend everything pending. */
    suspend fun resumeTransfers() {
        mutex.withLock { if (!recording) transfer.recoverOrphans() }
        publish()
        if (transfer.pendingSessionIds().isNotEmpty()) scheduleRetry(listOf(0L) + retryBackoffMs)
    }

    // ---- internals (call under mutex) ---------------------------------------------------------

    private fun openSegment(kind: MarkKind?, source: SegmentSource, step: String?) {
        val r = checkNotNull(tracker).start(kind, source, step)
        r.autoClosed?.let { c ->
            sendSegmentEvent(c.open, SegmentEvent.Event.STOPPED)
        }
        sendSegmentEvent(r.opened, SegmentEvent.Event.STARTED)
        haptics.startBuzz()
        publish()
    }

    private fun closeSegment(): ClosedSegment? {
        val c = checkNotNull(tracker).stop() ?: return null
        sendSegmentEvent(c.open, SegmentEvent.Event.STOPPED)
        haptics.stopBuzz()
        publish()
        return c
    }

    private suspend fun endLocked(source: SegmentSource): Boolean {
        val id = sessionId ?: return false
        closeSegment()
        sessionId = null
        tracker = null
        sequencer = null
        val id0 = id
        try {
            recorder.stop()
            transfer.prepare(id0)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            log("error finalizing $id0: $e")
            recorder.abort()
            runCatching { transfer.prepare(id0) } // the files are readable even if incomplete
        }
        kind = null
        host.exitForeground()
        log("session $id0 ended (${source.name.lowercase()})")
        publish()
        scheduleRetry(listOf(0L) + retryBackoffMs)
        return true
    }

    private fun scheduleRetry(delays: List<Long>) {
        retryJob?.cancel()
        retryJob = scope.launch {
            for (d in delays) {
                if (d > 0) delay(d)
                if (transfer.pendingSessionIds().isEmpty()) break
                publish()
                transfer.sendPending()
                publish()
                if (transfer.pendingSessionIds().isEmpty()) break
            }
        }
    }

    private suspend fun tickLoop(id: String) {
        while (true) {
            delay(tickMs)
            val keepGoing = mutex.withLock {
                if (sessionId != id) return@withLock false
                runCalibration()
                if (sessionId != id) return@withLock false // calibration finished the session
                refreshUi()
                val now = nowMs()
                if (now - lastStatusMs >= statusIntervalMs) {
                    lastStatusMs = now
                    sendStatus()
                }
                refreshUi()
                true
            }
            if (!keepGoing) return
        }
    }

    private suspend fun runCalibration() {
        val seq = sequencer ?: return
        for (e in seq.poll()) {
            when (e) {
                is CalibrationEvent.TransitionStarted -> haptics.cue()
                is CalibrationEvent.StepStarted -> openSegment(null, SegmentSource.AUTO, e.step.id)
                is CalibrationEvent.StepEnded -> closeSegment()
                CalibrationEvent.Finished -> {
                    endLocked(SegmentSource.AUTO)
                    return
                }
            }
        }
    }

    // ---- status / UI --------------------------------------------------------------------------

    private fun nowMs() = clock.elapsedRealtimeNs() / 1_000_000L

    private fun fail(msg: String) {
        log(msg)
        publish(message = msg)
    }

    private fun refreshUi() {
        val snap = recorder.snapshot()
        val open = tracker?.open
        val prog = sequencer?.progress()
        _state.update {
            it.copy(
                recording = recording,
                sessionId = sessionId,
                kind = kind,
                openSegmentId = open?.id,
                openSegmentKind = open?.kind,
                elapsedS = if (recording) (clock.elapsedRealtimeNs() - sessionStartNs) / 1e9 else 0.0,
                accRateHz = if (recording) snap.accRateHz else 0.0,
                gyrRateHz = if (recording) snap.gyrRateHz else 0.0,
                calibration = prog?.let { p ->
                    CalibrationUi(
                        p.step.id, p.step.title, p.step.instruction, p.inTransition,
                        ((p.remainingMs + 999) / 1000).toInt(), p.stepIndex + 1, p.stepCount,
                    )
                },
                pendingTransfers = transfer.pendingCount.value,
                transferring = transfer.sending.value,
            )
        }
    }

    /** Refreshes the UI state and sends a `WatchStatus` to the phone (on every change). */
    private fun publish(message: String? = _state.value.message) {
        refreshUi()
        _state.update { it.copy(message = message) }
        sendStatus()
    }

    private fun sendStatus() {
        val s = _state.value
        val status = WatchStatus(
            state = when {
                s.recording -> WatchStatus.State.RECORDING
                s.transferring -> WatchStatus.State.TRANSFERRING
                else -> WatchStatus.State.IDLE
            },
            sessionId = s.sessionId,
            sessionKind = s.kind,
            openSegmentId = s.openSegmentId,
            elapsedS = if (s.recording) s.elapsedS else null,
            accRateHz = if (s.recording) s.accRateHz else null,
            gyrRateHz = if (s.recording) s.gyrRateHz else null,
            calibrationStep = s.calibration?.takeIf { !it.inTransition }?.stepId,
        )
        send(DataLayer.PATH_STATUS, DataLayer.encode(status))
    }

    private fun sendSegmentEvent(seg: OpenSegment, event: SegmentEvent.Event) {
        val e = SegmentEvent(
            sessionId = checkNotNull(sessionId),
            segmentId = seg.id,
            event = event,
            kind = seg.kind,
            source = when (seg.source) {
                SegmentSource.WATCH -> "watch"
                SegmentSource.PHONE -> "phone"
                SegmentSource.AUTO -> "auto"
            },
        )
        send(DataLayer.PATH_SEGMENT_EVENT, DataLayer.encode(e))
    }

    private fun send(path: String, payload: ByteArray) {
        scope.launch {
            try {
                phone.sendMessage(path, payload)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log("message to phone failed ($path): $e")
            }
        }
    }

    companion object {
        const val STATUS_INTERVAL_MS = 5_000L
    }
}
