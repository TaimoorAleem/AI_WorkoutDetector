package dev.proprio.wear.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import dev.proprio.protocol.SensorInfo

/** Real [SensorSource]: events are delivered on a dedicated [HandlerThread], never the main thread. */
class AndroidSensorSource(
    private val sensorManager: SensorManager,
    private val log: (String) -> Unit = {},
) : SensorSource {
    private var thread: HandlerThread? = null
    private var listener: SensorEventListener? = null

    /** Explicit, deterministic choice (see [SensorSelector]); logged with the reason. */
    private fun choose(type: Int): Selection<Sensor>? {
        val sel = SensorSelector.select(
            sensorManager.getSensorList(type), sensorManager.getDefaultSensor(type),
        ) { SensorCandidate((it.name as String?).orEmpty(), (it.vendor as String?).orEmpty(), it.isWakeUpSensor) }
        sel?.let { log("sensor type $type -> '${it.sensor.name}' (${it.sensor.vendor}), selection=${it.reason.name.lowercase()}") }
        return sel
    }

    override fun describe(type: Int): SensorInfo? = choose(type)?.let { sel ->
        val info = toInfo(sel.sensor)
        // Meta has no selection field; the sensor name identifies the choice, a fallback is flagged in it.
        if (sel.reason == SelectionReason.DEFAULT_FALLBACK) info.copy(name = info.name + " [default_fallback]") else info
    }

    override fun start(periodUs: Int, maxReportLatencyUs: Int, sink: SensorSink): Boolean {
        stop()
        val acc = choose(TYPE_ACC)?.sensor ?: return false
        val gyr = choose(TYPE_GYR)?.sensor ?: return false
        val t = HandlerThread("proprio-sensors", android.os.Process.THREAD_PRIORITY_URGENT_AUDIO).also { it.start() }
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val v = event.values
                if (v.size >= 3) sink.onSample(event.sensor.type, event.timestamp, v[0], v[1], v[2])
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        thread = t
        listener = l
        val handler = Handler(t.looper)
        val okAcc = sensorManager.registerListener(l, acc, periodUs, maxReportLatencyUs, handler)
        val okGyr = sensorManager.registerListener(l, gyr, periodUs, maxReportLatencyUs, handler)
        if (!(okAcc && okGyr)) {
            stop()
            return false
        }
        return true
    }

    override fun stop() {
        listener?.let { sensorManager.unregisterListener(it) }
        listener = null
        thread?.quitSafely()
        thread?.join(1_000)
        thread = null
    }

    companion object {
        fun toInfo(s: Sensor) = SensorInfo(
            type = s.type,
            name = s.name,
            vendor = s.vendor,
            version = s.version,
            resolution = s.resolution.toDouble(),
            maximumRange = s.maximumRange.toDouble(),
            minDelayUs = s.minDelay,
            maxDelayUs = s.maxDelay,
            fifoReserved = s.fifoReservedEventCount,
            fifoMax = s.fifoMaxEventCount,
            requestedPeriodUs = 0,
            maxReportLatencyUs = 0,
            isWakeUp = s.isWakeUpSensor,
        )
    }
}
