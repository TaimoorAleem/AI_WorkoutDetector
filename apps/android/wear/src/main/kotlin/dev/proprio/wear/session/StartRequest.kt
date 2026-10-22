package dev.proprio.wear.session

import dev.proprio.protocol.SegmentSource
import dev.proprio.protocol.SessionKind

/**
 * "Start a session" request carried by the intent the phone launches on the watch (RemoteActivityHelper), because
 * a foreground service may only be started while the app is visible. Encoded as the data URI
 * `proprio://session/start?kind=GYM&participant=P01`; the phone builds the same string (mobile `WatchLaunch`).
 */
data class StartRequest(val kind: SessionKind, val participantId: String?) {
    companion object {
        const val SCHEME = "proprio"
        const val HOST = "session"
        const val PATH = "/start"

        /** Parses a data URI string; null when it is not a start request. Unknown/missing kind means GYM. */
        fun parse(uri: String?): StartRequest? {
            if (uri == null) return null
            val base = "$SCHEME://$HOST$PATH"
            if (uri != base && !uri.startsWith("$base?")) return null
            val q = uri.substringAfter('?', "").split('&').filter { it.contains('=') }
                .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            val kind = SessionKind.entries.firstOrNull { it.name.equals(q["kind"], ignoreCase = true) } ?: SessionKind.GYM
            return StartRequest(kind, q["participant"]?.ifBlank { null })
        }
    }
}

/** Handles a [StartRequest] intent delivered to the foreground activity; starts with `source = phone`. */
class StartRequestHandler(private val controller: RecordingController, private val log: (String) -> Unit = {}) {
    /** True when [uri] was a start request and a session was started (false if one was already running). */
    suspend fun handle(uri: String?): Boolean {
        val req = StartRequest.parse(uri) ?: return false
        log("remote start request: ${req.kind}")
        return controller.startSession(req.kind, SegmentSource.PHONE)
    }
}
