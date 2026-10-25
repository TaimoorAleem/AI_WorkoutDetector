package dev.proprio.mobile.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import dev.proprio.backend.SessionFiles
import dev.proprio.backend.UploadCore
import dev.proprio.backend.UploadOutcome
import dev.proprio.backend.UploadPhase
import dev.proprio.backend.UploadStateStore
import java.io.File
import java.util.concurrent.TimeUnit

/** Gives the upload core the session files held by [SessionStore] (the zip is read, never written). */
class StoreSessionFiles(private val store: SessionStore) : SessionFiles {
    override fun zip(sessionId: String): File? = store.zipFile(sessionId).takeIf { it.isFile }

    override fun labelsText(sessionId: String): String? =
        File(store.dir(sessionId), dev.proprio.protocol.FILE_LABELS).takeIf { it.isFile }?.readText(Charsets.UTF_8)
}

/** What the worker calls; separated from [UploadCore] so the worker is testable without a network. */
interface UploadRunner {
    suspend fun upload(sessionId: String): UploadOutcome

    /** WorkManager stopped retrying: surface the last error as a failed upload the user can retry by hand. */
    fun giveUp(sessionId: String, reason: String)
}

class CoreUploadRunner(private val core: UploadCore, private val states: UploadStateStore) : UploadRunner {
    override suspend fun upload(sessionId: String) = core.upload(sessionId)

    override fun giveUp(sessionId: String, reason: String) {
        states.update(sessionId) { if (it.remoteUploaded) it else it.copy(phase = UploadPhase.FAILED, error = reason) }
    }
}

/** Queues upload work for a session; the real one is backed by WorkManager. */
fun interface UploadScheduler {
    fun enqueue(sessionId: String)
}

/**
 * Persistent, network-constrained, exponentially backed-off upload job per session. New requests are appended to
 * any job still running for the same session, so labels edited mid-upload are picked up by a follow-up run, and a
 * job that already failed is replaced (manual retry). A run with nothing left to send makes no network call.
 */
class WorkUploadScheduler(private val workManager: WorkManager) : UploadScheduler {
    override fun enqueue(sessionId: String) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(Data.Builder().putString(UploadWorker.KEY_SESSION_ID, sessionId).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork("upload-$sessionId", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

class UploadWorker(
    context: Context,
    params: WorkerParameters,
    private val runner: UploadRunner,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_SESSION_ID) ?: return Result.failure()
        return when (val outcome = runner.upload(id)) {
            UploadOutcome.Done -> Result.success()
            is UploadOutcome.Retry -> if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
                runner.giveUp(id, outcome.reason)
                Result.failure()
            } else {
                Result.retry()
            }
            // Nothing a retry can fix; the state already carries the reason. Signing in re-queues pending sessions.
            is UploadOutcome.Failed, UploadOutcome.AuthRequired -> Result.failure()
        }
    }

    companion object {
        const val KEY_SESSION_ID = "session_id"
        const val MAX_ATTEMPTS = 6
    }
}

class UploadWorkerFactory(private val runner: () -> UploadRunner) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
        if (workerClassName == UploadWorker::class.java.name) UploadWorker(appContext, workerParameters, runner()) else null
}
