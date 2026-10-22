package dev.proprio.protocol

import kotlin.math.abs
import kotlin.math.floor

data class SensorStats(
    /** Unique samples after de-duplication. */
    val n: Int,
    val durationS: Double,
    val rateHz: Double,
    val dtP50Ms: Double,
    val dtP95Ms: Double,
    val dtP99Ms: Double,
    val dtMaxMs: Double,
    val gapsOver100Ms: Int,
    val duplicates: Int,
)

data class RecordingStats(
    val acc: SensorStats,
    val gyr: SensorStats,
    /** First gyro timestamp minus first acc timestamp, ms. */
    val accGyrStartOffsetMs: Double,
    /** End-anchor drift, ms; null for an incomplete session. */
    val clockDriftMs: Double?,
    /** Fraction of acc samples with any |axis| above 1.9 g (PROJECT.md 6.2 clip question). */
    val accFracAbove1p9g: Double,
) {
    companion object {
        const val GRAVITY = 9.80665
        const val CLIP_G = 1.9

        fun compute(s: RecordedSession): RecordingStats {
            val acc = sensorStats(s.acc, s.report.accDuplicates)
            val gyr = sensorStats(s.gyr, s.report.gyrDuplicates)
            val offset = if (s.acc.size > 0 && s.gyr.size > 0) (s.gyr.tNs[0] - s.acc.tNs[0]) / 1e6 else 0.0
            val thr = CLIP_G * GRAVITY
            var over = 0
            for (i in 0 until s.acc.size) {
                val x = s.acc.xyz
                if (abs(x[3 * i].toDouble()) > thr || abs(x[3 * i + 1].toDouble()) > thr || abs(x[3 * i + 2].toDouble()) > thr) over++
            }
            return RecordingStats(
                acc, gyr, offset, s.meta.clockDriftMs(),
                if (s.acc.size > 0) over.toDouble() / s.acc.size else 0.0,
            )
        }

        private fun sensorStats(t: SensorTrack, duplicates: Int): SensorStats {
            val n = t.size
            if (n < 2) return SensorStats(n, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, duplicates)
            val dt = DoubleArray(n - 1) { (t.tNs[it + 1] - t.tNs[it]) / 1e6 }
            val sorted = dt.sortedArray()
            val durationS = (t.tNs[n - 1] - t.tNs[0]) / 1e9
            return SensorStats(
                n = n,
                durationS = durationS,
                rateHz = (n - 1) / durationS,
                dtP50Ms = percentile(sorted, 50.0),
                dtP95Ms = percentile(sorted, 95.0),
                dtP99Ms = percentile(sorted, 99.0),
                dtMaxMs = sorted.last(),
                gapsOver100Ms = dt.count { it > 100.0 },
                duplicates = duplicates,
            )
        }

        /** Linear-interpolation percentile (numpy default). [sorted] must be ascending. */
        fun percentile(sorted: DoubleArray, q: Double): Double {
            val pos = (sorted.size - 1) * q / 100.0
            val lo = floor(pos).toInt()
            val hi = minOf(lo + 1, sorted.size - 1)
            return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
        }
    }
}
