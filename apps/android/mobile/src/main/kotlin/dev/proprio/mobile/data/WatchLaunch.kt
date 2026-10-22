package dev.proprio.mobile.data

import android.net.Uri
import dev.proprio.protocol.SessionKind

/**
 * Data URI of the "start session" request opened on the watch via RemoteActivityHelper. The watch parses the same
 * format (wear `StartRequest`): `proprio://session/start?kind=GYM&participant=P01`.
 */
object WatchLaunch {
    fun uriString(kind: SessionKind, participantId: String?): String = buildString {
        append("proprio://session/start?kind=").append(kind.name)
        if (!participantId.isNullOrBlank()) append("&participant=").append(java.net.URLEncoder.encode(participantId, "UTF-8"))
    }

    fun uri(kind: SessionKind, participantId: String?): Uri = Uri.parse(uriString(kind, participantId))
}
