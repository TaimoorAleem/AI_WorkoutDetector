package dev.proprio.wear.sensor

/** The fields of a sensor that matter for choosing between several of the same type. */
data class SensorCandidate(val name: String, val vendor: String, val isWakeUp: Boolean = false)

enum class SelectionReason { HARDWARE, DEFAULT_FALLBACK }

data class Selection<T>(val sensor: T, val reason: SelectionReason)

/**
 * Deterministic sensor choice. Among sensors of one type, prefers real hardware sensors:
 * vendor not "AOSP" and name not containing "Corrected" (software virtual sensors such as
 * "Corrected Gyroscope Sensor"). Non-wake-up sensors win over wake-up ones; otherwise list order.
 * Falls back to [default] only when no hardware sensor matches.
 */
object SensorSelector {
    fun isHardware(c: SensorCandidate) =
        !c.vendor.equals("AOSP", ignoreCase = true) && !c.name.contains("Corrected", ignoreCase = true)

    fun <T> select(list: List<T>, default: T?, describe: (T) -> SensorCandidate): Selection<T>? {
        val hw = list.filter { isHardware(describe(it)) }
        val pick = hw.firstOrNull { !describe(it).isWakeUp } ?: hw.firstOrNull()
        if (pick != null) return Selection(pick, SelectionReason.HARDWARE)
        return default?.let { Selection(it, SelectionReason.DEFAULT_FALLBACK) }
    }
}
