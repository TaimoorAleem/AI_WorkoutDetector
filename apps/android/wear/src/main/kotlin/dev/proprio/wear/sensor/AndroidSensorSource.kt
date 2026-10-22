package dev.proprio.wear.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import dev.proprio.protocol.SensorInfo

/** Real [SensorSource]: events are delivered on a dedicated [HandlerThread], never the main thread. */
class AndroidSensorSource(private val sensorManager: SensorManager) : SensorSource {
    private var thread: HandlerThread? = null
    private var listener: SensorEventListener? = null

    override fun describe(type: Int): SensorInfo? =
        sensorManager.getDefaultSensor(type)?.let { toInfo(it) }

    override fun start(periodUs: Int, maxReportLatencyUs: Int, sink: SensorSink): Boolean {
        stop()
        val acc = sensorManager.getDefaultSensor(TYPE_ACC) ?: return false
        val gyr = sensorManager.getDefaultSensor(TYPE_GYR) ?: return false
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
