package dev.proprio.mobile

import android.app.Application
import android.content.Context
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import androidx.work.Configuration
import androidx.work.WorkManager
import dev.proprio.backend.AnalysisRepository
import dev.proprio.backend.AuthState
import dev.proprio.backend.BackendService
import dev.proprio.backend.FileUploadStateStore
import dev.proprio.backend.HistoryRepository
import dev.proprio.backend.UploadCore
import dev.proprio.mobile.data.CoreUploadRunner
import dev.proprio.mobile.data.ExportBuilder
import dev.proprio.mobile.data.FileSettingsStore
import dev.proprio.mobile.data.LabelHeader
import dev.proprio.mobile.data.SessionImporter
import dev.proprio.mobile.data.SessionStore
import dev.proprio.mobile.data.SettingsStore
import dev.proprio.mobile.data.StoreSessionFiles
import dev.proprio.mobile.data.TinkSecretStore
import dev.proprio.mobile.data.UploadScheduler
import dev.proprio.mobile.data.UploadWorkerFactory
import dev.proprio.mobile.data.WearWatchLink
import dev.proprio.mobile.data.WorkUploadScheduler
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Hand-wired dependency graph; created lazily on first use by the UI or the listener service. */
class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings: SettingsStore = FileSettingsStore(File(context.filesDir, "settings.json"))
    val link = WearWatchLink(context)
    val store = SessionStore(File(context.filesDir, "sessions"), header = {
        val s = settings.settings.value
        LabelHeader(s.participantId.ifBlank { "unassigned" }, s.consent(Instant.now()))
    })
    val importer = SessionImporter(store)
    val exporter = ExportBuilder(store, File(context.cacheDir, "exports"))

    // Backend: the refresh token sits in the Keystore-backed vault, the base URL comes from Settings.
    val backend = BackendService(
        secrets = TinkSecretStore(context),
        baseUrl = { settings.settings.value.serverUrl.ifBlank { defaultBaseUrl() } },
        allowCleartext = BuildConfig.DEBUG,
    )
    val uploadStates = FileUploadStateStore { store.dir(it) }
    private val uploadCore = UploadCore({ backend.client() }, uploadStates, StoreSessionFiles(store))
    val uploadRunner = CoreUploadRunner(uploadCore, uploadStates)
    val analysis = AnalysisRepository({ backend.client() }, uploadStates)
    val history = HistoryRepository({ backend.client() })
    val scheduler: UploadScheduler by lazy { WorkUploadScheduler(WorkManager.getInstance(context)) }

    /** Queues the upload job for a received session; does nothing while signed out (sign-in queues everything). */
    fun queueUpload(sessionId: String) {
        if (backend.auth.signedIn && store.hasZip(sessionId)) scheduler.enqueue(sessionId)
    }

    /** Manual upload from the session detail. */
    fun uploadNow(sessionId: String) {
        uploadStates.update(sessionId) { it.copy(attempts = 0, error = null) }
        queueUpload(sessionId)
    }

    private fun queueAll() = store.list().forEach { queueUpload(it.summary.sessionId) }

    val coordinator = SessionCoordinator(scope, link, store, importer, settings, onSessionChanged = ::queueUpload)

    init {
        // Signing in (and every app start while signed in) re-queues sessions that are not fully uploaded;
        // finished ones cost no network call.
        scope.launch { backend.auth.state.collect { if (it is AuthState.SignedIn) queueAll() } }
    }

    companion object {
        fun defaultBaseUrl() =
            if (BuildConfig.DEBUG) BuildConfig.DEFAULT_API_BASE_URL_DEBUG else BuildConfig.DEFAULT_API_BASE_URL_RELEASE
    }
}

class ProprioApplication : Application(), Configuration.Provider {
    val container: AppContainer by lazy { AppContainer(applicationContext) }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(UploadWorkerFactory { container.uploadRunner }).build()
}

val Context.container: AppContainer get() = (applicationContext as ProprioApplication).container

/** Receives watch traffic even when the UI is closed. Needs the `proprio_mobile` capability and path filters. */
class PhoneListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (!event.path.startsWith("/proprio/")) return
        val c = container
        c.scope.launch { c.link.onMessage(event.path, event.data) }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        val c = container
        c.scope.launch { c.link.onChannelOpened(channel) }
    }
}
