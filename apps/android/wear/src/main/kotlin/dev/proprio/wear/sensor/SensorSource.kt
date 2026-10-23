package dev.proprio.wear.sensor

import dev.proprio.protocol.SensorInfo

const val TYPE_ACC = 1 // android.hardware.Sensor.TYPE_ACCELEROMETER
const val TYPE_GYR = 4 // android.hardware.Sensor.TYPE_GYROSCOPE

/** Receives raw events; called on the source's sensor thread, must not block. */
fun interface SensorSink {
    fun onSample(type: Int, tNs: Long, x: Float, y: Float, z: Float)
}

/** Thin seam over `SensorManager` so JVM tests can inject arbitrary event streams. */
interface SensorSource {
    /**
     * Static description of the default sensor of [type] ([TYPE_ACC] or [TYPE_GYR]), or null when
     * absent. `requestedPeriodUs` and `maxReportLatencyUs` are 0; the recorder fills them in.
     */
    fun describe(type: Int): SensorInfo?

    /**
     * Registers both sensors at [periodUs] with [maxReportLatencyUs] and delivers events to [sink] on a
     * dedicated thread owned by the source. Returns false when any registration failed.
     */
    fun start(periodUs: Int, maxReportLatencyUs: Int, sink: SensorSink): Boolean

    /** Unregisters and stops the sensor thread. After return no more events are delivered. */
    fun stop()
}
