package dev.proprio.mobile.data

import dev.proprio.protocol.SessionZip
import java.io.File

/** Builds `proprio-session-<id>.zip`: the watch's files byte-identical plus the phone's `labels.json`. */
class ExportBuilder(private val store: SessionStore, private val exportDir: File) {
    fun build(sessionId: String): File {
        check(store.hasZip(sessionId)) { "session $sessionId has not been received" }
        val labels = store.ensureLabels(sessionId)
        exportDir.mkdirs()
        val dst = File(exportDir, SessionZip.fileName(sessionId))
        SessionZip.withLabels(store.zipFile(sessionId), labels, dst)
        return dst
    }
}
