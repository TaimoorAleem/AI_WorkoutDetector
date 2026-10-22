package dev.proprio.wear.session

import dev.proprio.protocol.DataLayer
import dev.proprio.protocol.SegmentSource
import dev.proprio.protocol.SegmentStartCommand
import dev.proprio.protocol.SessionAck
import dev.proprio.protocol.SessionKind
import dev.proprio.protocol.SessionStartCommand

/** Maps every phone -> watch Data Layer path onto the controller; commands use `source = phone`. */
class CommandHandler(
    private val controller: RecordingController,
    private val log: (String) -> Unit = {},
) {
    /** Returns true when [path] is one of ours and its payload parsed, false otherwise. */
    suspend fun handle(path: String, data: ByteArray): Boolean {
        try {
            when (path) {
                DataLayer.PATH_SESSION_START -> {
                    val kind = if (data.isEmpty()) {
                        SessionKind.GYM
                    } else {
                        DataLayer.decode<SessionStartCommand>(data).sessionKind
                    }
                    controller.startSession(kind, SegmentSource.PHONE)
                }
                DataLayer.PATH_SESSION_END -> controller.endSession(SegmentSource.PHONE)
                DataLayer.PATH_SEGMENT_START -> {
                    val cmd = DataLayer.decode<SegmentStartCommand>(data)
                    controller.startSegment(cmd.kind, SegmentSource.PHONE)
                }
                DataLayer.PATH_SEGMENT_STOP -> controller.stopSegment(SegmentSource.PHONE)
                DataLayer.PATH_SESSION_ACK -> controller.handleAck(DataLayer.decode<SessionAck>(data))
                else -> return false
            }
            return true
        } catch (e: kotlinx.serialization.SerializationException) {
            log("bad payload on $path: $e")
            return false
        } catch (e: IllegalArgumentException) {
            log("bad payload on $path: $e")
            return false
        }
    }
}
