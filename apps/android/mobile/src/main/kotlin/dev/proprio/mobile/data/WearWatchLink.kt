package dev.proprio.mobile.data

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import dev.proprio.protocol.DataLayer
import dev.proprio.protocol.MarkKind
import dev.proprio.protocol.SegmentEvent
import dev.proprio.protocol.SegmentStartCommand
import dev.proprio.protocol.SessionAck
import dev.proprio.protocol.SessionKind
import dev.proprio.protocol.SessionStartCommand
import dev.proprio.protocol.WatchStatus
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * [WatchLink] over the Wearable Data Layer: [CapabilityClient] finds the watch (`proprio_wear`),
 * [com.google.android.gms.wearable.MessageClient] carries commands/status/events and
 * [ChannelClient] carries session zips.
 *
 * Inbound traffic is delivered by [PhoneListenerService] (so it works while the UI is closed) through
 * [onMessage] and [onChannelOpened]. All Play Services calls are wrapped: on a phone without a paired
 * watch (or without Play Services) the link simply reports "not connected".
 */
class WearWatchLink(private val context: Context) : WatchLink {
    private val capabilityClient: CapabilityClient by lazy { Wearable.getCapabilityClient(context) }
    private val messageClient by lazy { Wearable.getMessageClient(context) }
    private val channelClient: ChannelClient by lazy { Wearable.getChannelClient(context) }

    private val _connected = MutableStateFlow(false)
    private val _status = MutableStateFlow<WatchStatus?>(null)
    private val _events = MutableSharedFlow<SegmentEvent>(extraBufferCapacity = 64)
    private val _zips = MutableSharedFlow<IncomingZip>(extraBufferCapacity = 16)

    @Volatile private var nodeId: String? = null
    @Volatile private var listening = false

    override val connected: StateFlow<Boolean> = _connected.asStateFlow()
    override val status: StateFlow<WatchStatus?> = _status.asStateFlow()
    override val segmentEvents: Flow<SegmentEvent> = _events
    override val incomingZips: Flow<IncomingZip> = _zips

    override suspend fun refresh() {
        try {
            val info = capabilityClient.getCapability(DataLayer.CAPABILITY_WEAR, CapabilityClient.FILTER_REACHABLE).await()
            updateNodes(info.nodes)
            if (!listening) {
                capabilityClient.addListener({ updateNodes(it.nodes) }, DataLayer.CAPABILITY_WEAR).await()
                listening = true
            }
        } catch (e: Exception) {
            nodeId = null
            _connected.value = false
        }
    }

    private fun updateNodes(nodes: Set<Node>) {
        val best = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
        nodeId = best?.id
        _connected.value = best != null
    }

    override suspend fun startSession(kind: SessionKind, participantId: String?): Boolean =
        send(DataLayer.PATH_SESSION_START, DataLayer.encode(SessionStartCommand(kind, participantId)))

    override suspend fun endSession(): Boolean = send(DataLayer.PATH_SESSION_END, ByteArray(0))

    override suspend fun startSegment(kind: MarkKind): Boolean =
        send(DataLayer.PATH_SEGMENT_START, DataLayer.encode(SegmentStartCommand(kind)))

    override suspend fun stopSegment(): Boolean = send(DataLayer.PATH_SEGMENT_STOP, ByteArray(0))

    override suspend fun sendAck(ack: SessionAck): Boolean = send(DataLayer.PATH_SESSION_ACK, DataLayer.encode(ack))

    private suspend fun send(path: String, payload: ByteArray): Boolean {
        if (nodeId == null) refresh()
        val target = nodeId ?: return false
        return try {
            messageClient.sendMessage(target, path, payload).await()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Called by [PhoneListenerService] for every message under `/proprio/v1/`. */
    suspend fun onMessage(path: String, data: ByteArray) {
        _connected.value = true // any message proves the watch is reachable
        try {
            when (path) {
                DataLayer.PATH_STATUS -> _status.value = DataLayer.decode<WatchStatus>(data)
                DataLayer.PATH_SEGMENT_EVENT -> _events.emit(DataLayer.decode<SegmentEvent>(data))
            }
        } catch (e: Exception) {
            // Malformed payload from the watch: drop it; the next status message supersedes it.
        }
    }

    /** Called by [PhoneListenerService] when the watch opens a channel; copies the zip to a temp file. */
    suspend fun onChannelOpened(channel: ChannelClient.Channel) {
        val sessionId = DataLayer.sessionIdFromChannelPath(channel.path) ?: return
        val tmp = File(context.cacheDir, "incoming-${UUID.randomUUID()}.zip")
        try {
            withContext(Dispatchers.IO) {
                channelClient.getInputStream(channel).await().use { input ->
                    tmp.outputStream().buffered().use { out -> input.copyTo(out) }
                }
            }
            _zips.emit(IncomingZip(sessionId, tmp))
            nodeId = nodeId ?: channel.nodeId
        } catch (e: Exception) {
            // Transfer interrupted: the watch retries until it gets an ack.
            tmp.delete()
        } finally {
            try {
                channelClient.close(channel).await()
            } catch (e: Exception) {
                // already closed
            }
        }
    }
}
