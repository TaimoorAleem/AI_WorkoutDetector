package dev.proprio.mobile.data

import dev.proprio.protocol.MarkKind
import dev.proprio.protocol.SegmentEvent
import dev.proprio.protocol.SessionAck
import dev.proprio.protocol.SessionKind
import dev.proprio.protocol.WatchStatus
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A session zip fully received from the watch into a temporary [file]; ownership passes to the consumer. */
class IncomingZip(val sessionId: String, val file: File)

/**
 * The phone's view of the watch (Wearable Data Layer, contract in [dev.proprio.protocol.DataLayer]).
 * Everything the UI and labeling logic need sits behind this interface so tests can use [FakeWatchLink].
 */
interface WatchLink {
    /** True while a node advertising `proprio_wear` is reachable. */
    val connected: StateFlow<Boolean>

    /** Latest status pushed by the watch, or null if none was received yet. */
    val status: StateFlow<WatchStatus?>

    /** Segment start/stop events; the only source of segment ids on the phone. */
    val segmentEvents: Flow<SegmentEvent>

    /** Session zips received over the channel. */
    val incomingZips: Flow<IncomingZip>

    /** Re-checks reachable nodes. */
    suspend fun refresh()

    /** Each command returns false when no watch is reachable or the send failed. */
    suspend fun startSession(kind: SessionKind, participantId: String?): Boolean
    suspend fun endSession(): Boolean
    suspend fun startSegment(kind: MarkKind): Boolean
    suspend fun stopSegment(): Boolean

    suspend fun sendAck(ack: SessionAck): Boolean
}

/**
 * In-memory [WatchLink] that behaves like a cooperative watch: commands produce the same
 * [SegmentEvent]s and [WatchStatus] updates the real watch would send. Used by tests.
 */
class FakeWatchLink(private val sessionIdFactory: () -> String = { "00000000-0000-4000-8000-000000000001" }) : WatchLink {
    private val _connected = MutableStateFlow(true)
    private val _status = MutableStateFlow<WatchStatus?>(WatchStatus(WatchStatus.State.IDLE))
    private val _events = MutableSharedFlow<SegmentEvent>(extraBufferCapacity = 64)
    private val _zips = MutableSharedFlow<IncomingZip>(extraBufferCapacity = 16)

    override val connected: StateFlow<Boolean> = _connected.asStateFlow()
    override val status: StateFlow<WatchStatus?> = _status.asStateFlow()
    override val segmentEvents: Flow<SegmentEvent> = _events
    override val incomingZips: Flow<IncomingZip> = _zips

    /** Commands received, in order, as human-readable strings (e.g. "segment/start:SET"). */
    val commands = mutableListOf<String>()
    val acks = mutableListOf<SessionAck>()

    var sessionId: String? = null
        private set
    private var nextSegmentId = 1
    private var openSegment: Pair<Int, MarkKind>? = null

    fun setConnected(value: Boolean) {
        _connected.value = value
    }

    suspend fun emitZip(sessionId: String, file: File) = _zips.emit(IncomingZip(sessionId, file))

    suspend fun emitEvent(event: SegmentEvent) = _events.emit(event)

    override suspend fun refresh() = Unit

    override suspend fun startSession(kind: SessionKind, participantId: String?): Boolean {
        commands += "session/start:$kind"
        if (!_connected.value) return false
        sessionId = sessionIdFactory()
        nextSegmentId = 1
        _status.value = WatchStatus(WatchStatus.State.RECORDING, sessionId, kind, null, 0.0, 50.0, 50.0)
        return true
    }

    override suspend fun endSession(): Boolean {
        commands += "session/end"
        if (!_connected.value) return false
        _status.value = WatchStatus(WatchStatus.State.TRANSFERRING, sessionId)
        sessionId = null
        openSegment = null
        return true
    }

    override suspend fun startSegment(kind: MarkKind): Boolean {
        commands += "segment/start:$kind"
        val sid = sessionId
        if (!_connected.value || sid == null || openSegment != null) return false
        val id = nextSegmentId++
        openSegment = id to kind
        _status.value = _status.value?.copy(openSegmentId = id)
        _events.emit(SegmentEvent(sid, id, SegmentEvent.Event.STARTED, kind, "phone"))
        return true
    }

    override suspend fun stopSegment(): Boolean {
        commands += "segment/stop"
        val sid = sessionId
        val open = openSegment
        if (!_connected.value || sid == null || open == null) return false
        openSegment = null
        _status.value = _status.value?.copy(openSegmentId = null)
        _events.emit(SegmentEvent(sid, open.first, SegmentEvent.Event.STOPPED, open.second, "phone"))
        return true
    }

    override suspend fun sendAck(ack: SessionAck): Boolean {
        acks += ack
        return true
    }
}
