package dev.proprio.wear.session

/** One calibration step (spec section 5). */
data class CalibrationStep(val id: String, val durationMs: Long, val title: String, val instruction: String)

sealed interface CalibrationEvent {
    /** Transition (or lead-in) before [next] begins; not segmented. */
    data class TransitionStarted(val next: CalibrationStep, val durationMs: Long) : CalibrationEvent

    /** Open an auto segment with `step = step.id`. */
    data class StepStarted(val step: CalibrationStep) : CalibrationEvent

    /** Close the auto segment of [step]. */
    data class StepEnded(val step: CalibrationStep) : CalibrationEvent
    data object Finished : CalibrationEvent
}

/** Where the sequence is right now, for the UI. */
data class CalibrationProgress(
    val stepIndex: Int,
    val stepCount: Int,
    val step: CalibrationStep,
    val inTransition: Boolean,
    val remainingMs: Long,
)

/**
 * Time-driven sequencer: `lead-in(5 s) -> step 1 -> 5 s transition -> step 2 -> ... -> last step -> done`.
 * Transitions (and the initial lead-in, so the user can put the watch down) are not segmented.
 *
 * It is a pure function of [clockMs]: call [poll] regularly; it returns the events whose boundaries
 * have passed since the previous poll, in order. With an injected clock the timing is unit-testable.
 */
class CalibrationSequencer(
    private val clockMs: () -> Long,
    val steps: List<CalibrationStep> = DEFAULT_STEPS,
    private val transitionMs: Long = TRANSITION_MS,
) {
    private var startMs = 0L
    private var started = false
    private var nextBoundary = 0
    private val boundaries: List<Pair<Long, (Int) -> CalibrationEvent>>

    init {
        // (offsetMs, event) in order
        val list = mutableListOf<Pair<Long, (Int) -> CalibrationEvent>>()
        var t = 0L
        steps.forEachIndexed { i, step ->
            list += t to { _ -> CalibrationEvent.TransitionStarted(step, transitionMs) }
            t += transitionMs
            list += t to { _ -> CalibrationEvent.StepStarted(step) }
            t += step.durationMs
            list += t to { _ -> CalibrationEvent.StepEnded(step) }
        }
        list += t to { _ -> CalibrationEvent.Finished }
        boundaries = list
    }

    val totalMs: Long get() = boundaries.last().first
    val isFinished: Boolean get() = started && nextBoundary >= boundaries.size

    fun start() {
        startMs = clockMs()
        started = true
        nextBoundary = 0
    }

    fun poll(): List<CalibrationEvent> {
        if (!started) return emptyList()
        val elapsed = clockMs() - startMs
        val out = mutableListOf<CalibrationEvent>()
        while (nextBoundary < boundaries.size && boundaries[nextBoundary].first <= elapsed) {
            out += boundaries[nextBoundary].second(nextBoundary)
            nextBoundary++
        }
        return out
    }

    fun progress(): CalibrationProgress? {
        if (!started || isFinished) return null
        val elapsed = clockMs() - startMs
        var t = 0L
        steps.forEachIndexed { i, step ->
            val transEnd = t + transitionMs
            if (elapsed < transEnd) return CalibrationProgress(i, steps.size, step, true, transEnd - elapsed)
            val stepEnd = transEnd + step.durationMs
            if (elapsed < stepEnd) return CalibrationProgress(i, steps.size, step, false, stepEnd - elapsed)
            t = stepEnd
        }
        return null
    }

    companion object {
        const val TRANSITION_MS = 5_000L

        private fun pose(id: String, title: String, text: String) = CalibrationStep(id, 10_000, title, text)

        private fun rot(id: String, axis: String) = CalibrationStep(
            id, 15_000, "Rotate about $axis",
            "Rotate slowly 90 degrees about the $axis axis, hold, return. Three times.",
        )

        val DEFAULT_STEPS: List<CalibrationStep> = listOf(
            CalibrationStep("table_flat_face_up", 120_000, "Table, face up", "Lay the watch flat on a table, screen up. Do not touch it."),
            pose("face_up", "Face up", "Hold the watch still, screen facing up."),
            pose("face_down", "Face down", "Hold the watch still, screen facing down."),
            pose("buttons_up", "Buttons up", "Hold still, crown/buttons side pointing up."),
            pose("buttons_down", "Buttons down", "Hold still, crown/buttons side pointing down."),
            pose("twelve_up", "12 o'clock up", "Hold still, top of the watch face pointing up."),
            pose("twelve_down", "12 o'clock down", "Hold still, top of the watch face pointing down."),
            rot("rot_x", "X"),
            rot("rot_y", "Y"),
            rot("rot_z", "Z"),
        )
    }
}
