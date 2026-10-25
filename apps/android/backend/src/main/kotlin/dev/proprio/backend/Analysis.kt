package dev.proprio.backend

import dev.proprio.api.models.Analysis
import dev.proprio.api.models.LoggedSet
import dev.proprio.api.models.Session
import dev.proprio.api.models.SessionStatus
import java.time.LocalDate
import java.util.UUID

/** One analysed segment, ready to display: what the user logged next to what the model said. */
data class SegmentRow(
    val segmentId: Int,
    val kind: String,
    val startS: Double,
    val endS: Double,
    val userExercise: String?,
    val predictedExercise: String?,
    /** Winning class vote share, 0..1. */
    val confidence: Double?,
    val predictedReps: Int?,
    val userReps: Int?,
    val loadKg: Double?,
) {
    /** Null when either side is missing. */
    val exerciseMatches: Boolean? get() = if (userExercise != null && predictedExercise != null) userExercise == predictedExercise else null

    /** Predicted minus logged reps; null when either side is missing. */
    val repDelta: Int? get() = if (predictedReps != null && userReps != null) predictedReps - userReps else null
}

data class AnalysisView(
    val status: SessionStatus,
    val model: String?,
    val rows: List<SegmentRow>,
) {
    companion object {
        /**
         * Maps the API analysis to display rows. Lifts (user kind `set`, or predicted `set`) always show;
         * plain rest and `other` segments the model also considers non-lifts are dropped as noise.
         */
        fun from(a: Analysis): AnalysisView {
            val rows = a.segments
                .filter { it.kind.value == "set" || it.kind.value == "calibration" || it.predictedKind?.value == "set" }
                .map {
                    SegmentRow(
                        segmentId = it.segmentId,
                        kind = it.kind.value,
                        startS = it.startS.toDouble(),
                        endS = it.endS.toDouble(),
                        userExercise = it.userExercise?.value,
                        predictedExercise = it.predictedExercise?.value,
                        confidence = it.confidence?.toDouble(),
                        predictedReps = it.repCount,
                        userReps = it.userReps,
                        loadKg = it.loadKg?.toDouble(),
                    )
                }
                .sortedBy { it.startS }
            val model = a.model?.let { m -> listOfNotNull(m.name, m.version).joinToString(" ") }?.takeIf { it.isNotBlank() }
            return AnalysisView(a.status, model, rows)
        }
    }
}

/** Server-side state of one session as the UI needs it. */
data class RemoteSession(val status: SessionStatus, val failureReason: String?, val analysis: AnalysisView?) {
    /** Still worth polling. */
    val pending: Boolean get() = status == SessionStatus.CREATED || status == SessionStatus.UPLOADED || status == SessionStatus.ANALYZING
}

class AnalysisRepository(
    private val client: () -> BackendClient,
    private val states: UploadStateStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    sealed interface Result {
        data class Ok(val remote: RemoteSession) : Result
        data object NotSignedIn : Result
        data object NotFound : Result
        data class Error(val message: String) : Result
    }

    /** Fetches the session (and its analysis once ANALYZED) and mirrors the status into the local upload state. */
    suspend fun refresh(sessionId: String): Result {
        val c = try {
            client()
        } catch (e: IllegalStateException) {
            return Result.Error(e.message ?: "Invalid server address.")
        }
        if (!c.auth.signedIn) return Result.NotSignedIn
        val id = UUID.fromString(sessionId)
        val session: Session = when (val s = apiCall { c.api.sessions.getSession(id) }) {
            is ApiResult.Ok -> s.value
            is ApiResult.HttpError -> return when (s.code) {
                401 -> Result.NotSignedIn
                404 -> Result.NotFound
                else -> Result.Error(s.message)
            }
            is ApiResult.NetworkError -> return Result.Error("Could not reach the server.")
        }
        var view: AnalysisView? = null
        if (session.status == SessionStatus.ANALYZED) {
            when (val a = apiCall { c.api.analysis.getAnalysis(id) }) {
                is ApiResult.Ok -> view = AnalysisView.from(a.value)
                is ApiResult.HttpError -> return Result.Error(a.message)
                is ApiResult.NetworkError -> return Result.Error("Could not reach the server.")
            }
        }
        mirror(sessionId, session)
        return Result.Ok(RemoteSession(session.status, session.failureReason, view))
    }

    /**
     * Polls [refresh] with growing delays (2 s, 3 s, 4.5 s ... capped at 30 s) until the analysis is no longer
     * pending, an error occurs, or [maxWaitMs] of waiting has passed. Every result goes to [onResult].
     */
    suspend fun awaitSettled(
        sessionId: String,
        maxWaitMs: Long = 10 * 60_000L,
        sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
        onResult: (Result) -> Unit,
    ) {
        var waited = 0L
        var attempt = 0
        while (true) {
            val r = refresh(sessionId)
            onResult(r)
            // A transient error keeps polling; signed-out and unknown-session answers will not change by waiting.
            val pending = (r is Result.Ok && r.remote.pending) || r is Result.Error
            if (!pending || waited >= maxWaitMs) return
            val d = nextDelayMs(attempt++)
            sleep(d)
            waited += d
        }
    }

    private fun mirror(id: String, s: Session) {
        if (s.status == SessionStatus.CREATED) return
        states.update(id) {
            val base = it.copy(remoteUploaded = true, updatedAtMs = nowMs())
            when (s.status) {
                SessionStatus.ANALYZED -> base.copy(phase = UploadPhase.ANALYZED, error = null)
                SessionStatus.FAILED -> base.copy(phase = UploadPhase.FAILED, error = s.failureReason ?: "analysis_failed")
                else -> base.copy(phase = UploadPhase.UPLOADED, error = null)
            }
        }
    }
}

/** Delay before poll number [attempt] (0-based): 2 s growing by 1.5x up to 30 s. */
fun nextDelayMs(attempt: Int): Long = minOf(30_000.0, 2_000.0 * Math.pow(1.5, attempt.toDouble())).toLong()

class HistoryRepository(private val client: () -> BackendClient) {
    sealed interface Result {
        data class Ok(val sets: List<LoggedSet>) : Result
        data object NotSignedIn : Result
        data class Error(val message: String) : Result
    }

    /** Logged sets from the last [days] days, newest first. */
    suspend fun load(today: LocalDate = LocalDate.now(), days: Long = 90): Result {
        val c = try {
            client()
        } catch (e: IllegalStateException) {
            return Result.Error(e.message ?: "Invalid server address.")
        }
        if (!c.auth.signedIn) return Result.NotSignedIn
        return when (val r = apiCall { c.api.history.listWorkouts(today.minusDays(days), today.plusDays(1)) }) {
            is ApiResult.Ok -> Result.Ok(r.value.sortedByDescending { it.date })
            is ApiResult.HttpError -> if (r.code == 401) Result.NotSignedIn else Result.Error(r.message)
            is ApiResult.NetworkError -> Result.Error("Could not reach the server.")
        }
    }
}
