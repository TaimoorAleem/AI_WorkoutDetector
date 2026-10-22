package dev.proprio.mobile

import android.app.Application
import android.content.Context
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dev.proprio.mobile.data.ExportBuilder
import dev.proprio.mobile.data.FileSettingsStore
import dev.proprio.mobile.data.LabelHeader
import dev.proprio.mobile.data.SessionImporter
import dev.proprio.mobile.data.SessionStore
import dev.proprio.mobile.data.SettingsStore
import dev.proprio.mobile.data.WearWatchLink
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
    val coordinator = SessionCoordinator(scope, link, store, importer, settings)
}

class ProprioApplication : Application() {
    val container: AppContainer by lazy { AppContainer(applicationContext) }
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
