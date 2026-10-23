package dev.proprio.mobile.domain

import dev.proprio.protocol.SensorTrack
import kotlin.math.sqrt

/**
 * Downsampled |acc| (m/s^2) and |gyro| (rad/s) over the session for the review plot.
 * Each of the [acc]/[gyr] entries is the mean magnitude of one equal-width time bucket
 * over [tStartNs, tEndNs]; empty buckets (recording gaps) are NaN.
 */
class PlotData(val tStartNs: Long, val tEndNs: Long, val acc: FloatArray, val gyr: FloatArray) {
    val buckets: Int get() = acc.size

    fun timeToFraction(tNs: Long): Float =
        if (tEndNs <= tStartNs) 0f else ((tNs - tStartNs).toDouble() / (tEndNs - tStartNs)).toFloat()

    fun fractionToTime(f: Float): Long = tStartNs + ((tEndNs - tStartNs) * f.toDouble()).toLong()

    companion object {
        fun build(acc: SensorTrack, gyr: SensorTrack, buckets: Int = 600): PlotData {
            val starts = listOfNotNull(acc.tNs.firstOrNull(), gyr.tNs.firstOrNull())
            val ends = listOfNotNull(acc.tNs.lastOrNull(), gyr.tNs.lastOrNull())
            if (starts.isEmpty()) return PlotData(0, 0, FloatArray(0), FloatArray(0))
            val t0 = starts.min()
            val t1 = ends.max()
            return PlotData(t0, t1, bucketMeans(acc, t0, t1, buckets), bucketMeans(gyr, t0, t1, buckets))
        }

        private fun bucketMeans(track: SensorTrack, t0: Long, t1: Long, buckets: Int): FloatArray {
            val sum = DoubleArray(buckets)
            val n = IntArray(buckets)
            val span = (t1 - t0).coerceAtLeast(1L).toDouble()
            for (i in 0 until track.size) {
                val b = (((track.tNs[i] - t0) / span) * buckets).toInt().coerceIn(0, buckets - 1)
                val x = track.xyz[3 * i].toDouble()
                val y = track.xyz[3 * i + 1].toDouble()
                val z = track.xyz[3 * i + 2].toDouble()
                sum[b] += sqrt(x * x + y * y + z * z)
                n[b]++
            }
            return FloatArray(buckets) { if (n[it] == 0) Float.NaN else (sum[it] / n[it]).toFloat() }
        }
    }
}
