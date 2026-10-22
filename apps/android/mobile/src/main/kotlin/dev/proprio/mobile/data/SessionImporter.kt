package dev.proprio.mobile.data

import dev.proprio.protocol.SessionAck
import dev.proprio.protocol.SessionReader
import java.io.File
import java.security.MessageDigest

/**
 * Handles a zip received from the watch: hash it, check that it unzips and parses, store it and
 * produce the [SessionAck]. The watch deletes its copy only on `ok = true` with a matching hash.
 */
class SessionImporter(private val store: SessionStore) {
    fun import(sessionId: String, received: File): SessionAck {
        val sha = sha256(received)
        val ok = try {
            val session = SessionReader.readZip(received)
            require(session.meta.sessionId == sessionId) { "zip is for session ${session.meta.sessionId}" }
            store.saveZip(sessionId, received, session)
            // Labels collected before the zip arrived are already in labels.json; make sure one exists.
            store.ensureLabels(sessionId)
            true
        } catch (e: Exception) {
            false
        }
        if (!ok) received.delete()
        return SessionAck(sessionId, sha, ok)
    }

    companion object {
        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
