package dev.proprio.wear

import android.os.SystemClock
import dev.proprio.protocol.ClockAnchor

/** Time source for the recorder; injectable so tests control both clocks. */
interface DeviceClock {
    /** Same clock as `SensorEvent.timestamp` (elapsedRealtimeNanos). */
    fun elapsedRealtimeNs(): Long

    /** Wall-clock milliseconds since the Unix epoch. */
    fun unixTimeMs(): Long
}

object SystemDeviceClock : DeviceClock {
    override fun elapsedRealtimeNs(): Long = SystemClock.elapsedRealtimeNanos()
    override fun unixTimeMs(): Long = System.currentTimeMillis()
}

/** Spec 3.1: the two clocks are read in adjacent statements, elapsed first. */
fun DeviceClock.captureAnchor(): ClockAnchor {
    val elapsed = elapsedRealtimeNs()
    val unixMs = unixTimeMs()
    return ClockAnchor(elapsedRealtimeNs = elapsed, unixTimeNs = unixMs * 1_000_000L)
}
