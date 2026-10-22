package dev.proprio.wear.transfer

import dev.proprio.protocol.DataLayer
import dev.proprio.protocol.SessionAck
import dev.proprio.protocol.SessionZip
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Sends a file to a node over the Data Layer channel at [path]. Throws on failure. */
fun interface ChannelSender {
    suspend fun send(nodeId: String, path: String, file: File)
}

/** Nodes advertising [DataLayer.CAPABILITY_MOBILE]. */
fun interface NodeFinder {
    suspend fun mobileNodeIds(): List<String>
}

enum class AckResult {
    /** ok=true and the hash matched: the zip and raw directory were deleted. */
    DELETED,

    /** The phone reported a failure or the hash differs: everything is kept for a retry. */
    KEPT_RETRY,

    /** No pending zip for that session (already deleted, or never ours). */
    UNKNOWN,
}

/**
 * File-based outbox: a session is pending while `outbox/proprio-session-<id>.zip` exists. The zip is
 * deleted only on a [SessionAck] with `ok=true` and a matching SHA-256 of the zip. Because state is
 * the file system, it survives process death and can be driven from any entry point (app start,
 * capability change, ack received while the UI is closed).
 */
class TransferManager(
    private val sessionsDir: File,
    private val outboxDir: File,
    private val sender: ChannelSender,
    private val nodes: NodeFinder,
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending

    /**
     * When true, an acked zip is renamed to `<name>.zip.kept` instead of deleted. Only the debug-only test
     * receiver sets it; production code never does.
     */
    @Volatile var keepAckedZips = false

    init {
        refresh()
    }

    fun sessionDir(sessionId: String) = File(sessionsDir, sessionId)
    fun zipFile(sessionId: String) = File(outboxDir, SessionZip.fileName(sessionId))

    fun pendingSessionIds(): List<String> =
        outboxDir.listFiles { f -> f.isFile && f.name.startsWith("proprio-session-") && f.name.endsWith(".zip") }
            .orEmpty()
            .map { it.name.removePrefix("proprio-session-").removeSuffix(".zip") }
            .sorted()

    fun refresh() {
        _pendingCount.value = pendingSessionIds().size
    }

    /** Zips the (finished or crashed) session directory into the outbox. Idempotent. */
    fun prepare(sessionId: String): File {
        val zip = zipFile(sessionId)
        if (!zip.exists()) {
            outboxDir.mkdirs()
            val tmp = File(outboxDir, zip.name + ".tmp")
            SessionZip.pack(sessionDir(sessionId), tmp)
            check(tmp.renameTo(zip)) { "cannot move $tmp" }
        }
        refresh()
        return zip
    }

    /**
     * Zips every session directory that has no zip yet. Called on app start, when by definition no
     * recording is active in this process: such directories are sessions cut short by a crash, a
     * kill or a dead battery (meta.json is `complete=false`, the CSVs are readable).
     */
    fun recoverOrphans(): List<String> {
        val dirs = sessionsDir.listFiles { f -> f.isDirectory && File(f, "meta.json").isFile }.orEmpty()
        val recovered = dirs.map { it.name }.filter { !zipFile(it).exists() }
        for (id in recovered) {
            log("recovering interrupted session $id")
            prepare(id)
        }
        refresh()
        return recovered
    }

    /** Sends every pending zip to the first phone node. Returns the ids whose send completed. */
    suspend fun sendPending(): List<String> = mutex.withLock {
        val ids = pendingSessionIds()
        if (ids.isEmpty()) return emptyList()
        val node = runCatching { nodes.mobileNodeIds() }.getOrElse { log("node lookup failed: $it"); emptyList() }
            .firstOrNull()
        if (node == null) {
            log("no phone node with capability ${DataLayer.CAPABILITY_MOBILE}; ${ids.size} transfer(s) stay pending")
            return emptyList()
        }
        val sent = mutableListOf<String>()
        _sending.value = true
        try {
            for (id in ids) {
                try {
                    sender.send(node, DataLayer.channelPath(id), zipFile(id))
                    sent += id
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    log("send of $id failed: $e")
                }
            }
        } finally {
            _sending.value = false
        }
        sent
    }

    /** Applies a phone ack. Keeps everything unless `ok` and the SHA-256 of our zip matches. */
    fun onAck(ack: SessionAck): AckResult {
        val zip = zipFile(ack.sessionId)
        if (!zip.exists()) return AckResult.UNKNOWN
        val mine = sha256(zip)
        if (!ack.ok || !mine.equals(ack.sha256, ignoreCase = true)) {
            log("ack for ${ack.sessionId} rejected (ok=${ack.ok}, hash match=${mine.equals(ack.sha256, true)}); keeping zip")
            return AckResult.KEPT_RETRY
        }
        if (keepAckedZips) {
            // Device tests only: the zip stays in the outbox (no longer pending) so it can be pulled with adb.
            check(zip.renameTo(File(outboxDir, zip.name + KEPT_SUFFIX))) { "cannot keep $zip" }
        } else {
            zip.delete()
        }
        sessionDir(ack.sessionId).deleteRecursively()
        refresh()
        return AckResult.DELETED
    }

    companion object {
        const val KEPT_SUFFIX = ".kept"

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
