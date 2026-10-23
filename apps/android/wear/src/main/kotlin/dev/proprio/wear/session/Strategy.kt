package dev.proprio.wear.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Anti-freeze strategy against Samsung's Freecess (D-023): which mechanisms keep the recorder process
 * unfrozen while the screen is off. [ALL] is the default for normal use; the others exist so the device test
 * matrix can measure each mechanism alone.
 */
enum class Strategy(
    val id: String,
    val ongoing: Boolean,
    val ambient: Boolean,
    val exercise: Boolean,
    val screenOn: Boolean = false,
) {
    NONE("none", ongoing = false, ambient = false, exercise = false),
    ONGOING("ongoing", ongoing = true, ambient = false, exercise = false),
    AMBIENT("ambient", ongoing = false, ambient = true, exercise = false),
    EXERCISE("exercise", ongoing = false, ambient = false, exercise = true),
    SCREEN_ON("screen_on", ongoing = false, ambient = false, exercise = false, screenOn = true),
    ALL("all", ongoing = true, ambient = true, exercise = true, screenOn = true),
    ;

    companion object {
        val DEFAULT = ALL

        /** Parses a strategy id (case-insensitive); null or unknown ids give null. */
        fun parse(id: String?): Strategy? = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
    }
}

/** Whether the recording activity should hold the screen on (dimmed to minimum) right now. */
fun keepScreenOn(strategy: Strategy, recording: Boolean): Boolean = strategy.screenOn && recording

/** Process-wide, in-memory current [Strategy]. The debug receiver is the only thing that changes it. */
class StrategyHolder(initial: Strategy = Strategy.DEFAULT) {
    private val flow = MutableStateFlow(initial)
    val changes: StateFlow<Strategy> = flow
    var current: Strategy
        get() = flow.value
        set(value) {
            flow.value = value
        }
}

/** A Health Services exercise kept open for the duration of a session so the OS treats the app as a workout. */
interface ExerciseHost {
    /** Starts the exercise; false when unavailable or denied (the recording carries on without it). */
    suspend fun start(): Boolean

    /** Ends the exercise. Idempotent: also used on app start to end one orphaned by a crash. */
    suspend fun stop()
}

object NoExercise : ExerciseHost {
    override suspend fun start() = false
    override suspend fun stop() {}
}
