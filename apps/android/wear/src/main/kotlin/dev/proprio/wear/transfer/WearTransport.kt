package dev.proprio.wear.transfer

import android.content.Context
import com.google.android.gms.wearable.Wearable
import dev.proprio.protocol.DataLayer
import dev.proprio.wear.session.PhoneLink
import java.io.File
import kotlinx.coroutines.tasks.await

/** Real Data Layer implementations of the transfer and messaging seams. */
class WearTransport(context: Context) : ChannelSender, NodeFinder, PhoneLink {
    private val appContext = context.applicationContext
    private val channelClient by lazy { Wearable.getChannelClient(appContext) }
    private val messageClient by lazy { Wearable.getMessageClient(appContext) }
    private val capabilityClient by lazy { Wearable.getCapabilityClient(appContext) }

    override suspend fun mobileNodeIds(): List<String> =
        capabilityClient.getCapability(DataLayer.CAPABILITY_MOBILE, com.google.android.gms.wearable.CapabilityClient.FILTER_REACHABLE)
            .await().nodes.map { it.id }

    override suspend fun send(nodeId: String, path: String, file: File) {
        val channel = channelClient.openChannel(nodeId, path).await()
        try {
            channelClient.getOutputStream(channel).await().use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
        } finally {
            channelClient.close(channel).await()
        }
    }

    override suspend fun sendMessage(path: String, payload: ByteArray) {
        for (id in mobileNodeIds()) messageClient.sendMessage(id, path, payload).await()
    }
}
