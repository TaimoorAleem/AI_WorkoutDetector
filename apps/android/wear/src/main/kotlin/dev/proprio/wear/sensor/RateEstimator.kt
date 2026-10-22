package dev.proprio.wear.sensor

/**
 * Rolling rate estimate over the last [window] event timestamps: (n-1) / (t_last - t_first).
 * Written from the sensor thread only; [rateHz] may be read from any thread.
 */
class RateEstimator(window: Int = 100) {
    private val ring = LongArray(window)
    private var count = 0
    private var head = 0

    @Volatile
    var rateHz: Double = 0.0
        private set

    fun add(tNs: Long) {
        ring[head] = tNs
        head = (head + 1) % ring.size
        if (count < ring.size) count++
        if (count >= 2) {
            val newest = ring[(head - 1 + ring.size) % ring.size]
            val oldest = ring[(head - count + ring.size) % ring.size]
            val span = newest - oldest
            if (span > 0) rateHz = (count - 1) * 1e9 / span
        }
    }
}
