package dev.proprio.wear.platform

import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dev.proprio.protocol.DataLayer
import dev.proprio.wear.ProprioApp
import kotlinx.coroutines.launch

/**
 * Receives phone -> watch messages (and phone capability changes) even when the UI is closed. The
 * actual work is in [dev.proprio.wear.session.CommandHandler], which is unit-tested on the JVM.
 */
class CommandListenerService : WearableListenerService() {
    private val graph get() = (application as ProprioApp).graph

    override fun onMessageReceived(event: MessageEvent) {
        val g = graph
        g.scope.launch { g.commands.handle(event.path, event.data) }
    }

    override fun onCapabilityChanged(info: CapabilityInfo) {
        if (info.name == DataLayer.CAPABILITY_MOBILE && info.nodes.isNotEmpty()) {
            val g = graph
            g.scope.launch { g.controller.resumeTransfers() }
        }
    }
}
