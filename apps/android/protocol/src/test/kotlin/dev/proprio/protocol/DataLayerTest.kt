package dev.proprio.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DataLayerTest {
    @Test
    fun pathsAreVersionedAndDistinct() {
        val paths = listOf(
            DataLayer.PATH_SESSION_START,
            DataLayer.PATH_SESSION_END,
            DataLayer.PATH_SEGMENT_START,
            DataLayer.PATH_SEGMENT_STOP,
            DataLayer.PATH_SESSION_ACK,
            DataLayer.PATH_STATUS,
            DataLayer.PATH_SEGMENT_EVENT,
        )
        assertEquals(paths.size, paths.toSet().size)
        assertTrue(paths.all { it.startsWith("/proprio/v1/") })
    }

    @Test
    fun channelPathRoundTripsSessionId() {
        val path = DataLayer.channelPath("abc-123")
        assertEquals("abc-123", DataLayer.sessionIdFromChannelPath(path))
        assertNull(DataLayer.sessionIdFromChannelPath(DataLayer.PATH_STATUS))
        assertNull(DataLayer.sessionIdFromChannelPath(DataLayer.channelPath("")))
    }

    @Test
    fun payloadsRoundTripWithSnakeCaseKeys() {
        val start = SessionStartCommand(SessionKind.CALIBRATION, participantId = "P01")
        val bytes = DataLayer.encode(start)
        assertTrue(bytes.decodeToString().contains("\"session_kind\": \"calibration\""))
        assertEquals(start, DataLayer.decode<SessionStartCommand>(bytes))

        val event = SegmentEvent("s1", 3, SegmentEvent.Event.STARTED, MarkKind.SET, source = "phone")
        assertEquals(event, DataLayer.decode<SegmentEvent>(DataLayer.encode(event)))

        val status = WatchStatus(WatchStatus.State.RECORDING, sessionId = "s1", openSegmentId = 3, accRateHz = 49.9)
        assertEquals(status, DataLayer.decode<WatchStatus>(DataLayer.encode(status)))

        val ack = SessionAck("s1", "deadbeef", ok = true)
        assertEquals(ack, DataLayer.decode<SessionAck>(DataLayer.encode(ack)))
        assertEquals(
            SegmentStartCommand(MarkKind.OTHER),
            DataLayer.decode<SegmentStartCommand>(DataLayer.encode(SegmentStartCommand(MarkKind.OTHER))),
        )
    }
}
