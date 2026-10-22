package dev.proprio.mobile.data

import dev.proprio.protocol.Consent
import dev.proprio.protocol.Labels
import dev.proprio.protocol.LabelsApp
import dev.proprio.protocol.ProtocolJson
import dev.proprio.protocol.RecordedSession
import dev.proprio.protocol.SegmentKind
import dev.proprio.protocol.SessionKind
import dev.proprio.protocol.SessionReader
import dev.proprio.protocol.SessionZip
import dev.proprio.protocol.FILE_LABELS
import dev.proprio.protocol.toJson
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/** Participant and consent written into every new `labels.json`. */
data class LabelHeader(val participantId: String, val consent: Consent)

/** Facts about a received session, computed once on import. */
@Serializable
data class SessionSummary(
    val sessionId: String,
    val kind: SessionKind,
    val complete: Boolean,
    val startedAtUtc: String,
    val durationS: Double,
    val markCount: Int,
)

/** One row of the recordings list. */
data class RecordingItem(
    val summary: SessionSummary,
    val setCount: Int,
    val labeled: Boolean,
)

/**
 * App-private session storage. Layout per session under [root]/`<session_id>/`:
 * - `session.zip`: the watch's file, byte-identical to what was received, never modified.
 * - `labels.json`: the phone's labels (valid Raw Format `labels.json` at all times). It exists as soon as
 *   the first segment event arrives, i.e. possibly *before* `session.zip`; export merges the two.
 * - `summary.json`: derived facts used by the recordings list.
 */
class SessionStore(
    private val root: File,
    private val header: () -> LabelHeader,
    private val appVersion: String = "0.1.0",
) {
    private val _changes = MutableStateFlow(0)

    /** Increments whenever labels or a zip are written, so list screens can refresh. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    fun dir(sessionId: String): File {
        require(sessionId.isNotEmpty() && sessionId.all { it.isLetterOrDigit() || it == '-' }) { "bad session id" }
        return File(root, sessionId)
    }

    fun zipFile(sessionId: String) = File(dir(sessionId), ZIP)
    fun hasZip(sessionId: String) = zipFile(sessionId).isFile

    fun readLabels(sessionId: String): Labels? {
        val f = File(dir(sessionId), FILE_LABELS)
        if (!f.isFile) return null
        return try {
            ProtocolJson.decodeFromString(Labels.serializer(), f.readText())
        } catch (e: Exception) {
            null
        }
    }

    /** Reads (creating if absent), transforms and atomically rewrites the session's labels. */
    @Synchronized
    fun updateLabels(sessionId: String, transform: (Labels) -> Labels): Labels {
        val current = readLabels(sessionId) ?: newLabels(sessionId)
        val next = transform(current).copy(sessionId = sessionId)
        writeAtomic(File(dir(sessionId), FILE_LABELS), next.toJson())
        _changes.value++
        return next
    }

    /** Makes sure a `labels.json` exists. */
    fun ensureLabels(sessionId: String): Labels = updateLabels(sessionId) { it }

    private fun newLabels(sessionId: String): Labels {
        val h = header()
        return Labels(
            sessionId = sessionId,
            participantId = h.participantId,
            consent = h.consent,
            segments = emptyList(),
            app = LabelsApp("dev.proprio.mobile", appVersion),
        )
    }

    /** Moves a verified zip into place and records its summary. */
    @Synchronized
    fun saveZip(sessionId: String, verified: File, session: RecordedSession) {
        val dst = zipFile(sessionId)
        dst.parentFile!!.mkdirs()
        Files.move(verified.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
        val s = session.acc
        val durationS = if (s.size > 1) (s.tNs.last() - s.tNs.first()) / 1e9 else 0.0
        val summary = SessionSummary(
            sessionId = sessionId,
            kind = session.meta.kind,
            complete = session.meta.complete,
            startedAtUtc = session.meta.startedAtUtc,
            durationS = durationS,
            markCount = session.segments.size,
        )
        writeAtomic(File(dir(sessionId), SUMMARY), ProtocolJson.encodeToString(SessionSummary.serializer(), summary))
        _changes.value++
    }

    fun summary(sessionId: String): SessionSummary? = try {
        File(dir(sessionId), SUMMARY).takeIf { it.isFile }
            ?.let { ProtocolJson.decodeFromString(SessionSummary.serializer(), it.readText()) }
    } catch (e: Exception) {
        null
    }

    /** Received sessions (those with a zip), newest first. */
    fun list(): List<RecordingItem> {
        val dirs = root.listFiles { f -> f.isDirectory }.orEmpty()
        return dirs.mapNotNull { d ->
            if (!File(d, ZIP).isFile) return@mapNotNull null
            val summary = summary(d.name) ?: return@mapNotNull null
            val sets = readLabels(d.name)?.segments.orEmpty().filter { it.kind == SegmentKind.SET }
            val labeled = if (summary.kind == SessionKind.CALIBRATION) true
            else sets.isNotEmpty() && sets.all { it.exercise != null && it.repsUser != null }
            RecordingItem(summary, if (summary.kind == SessionKind.CALIBRATION) summary.markCount else sets.size, labeled)
        }.sortedByDescending { it.summary.startedAtUtc }
    }

    /** Loads the zip with the phone's current `labels.json` overlaid (what the review screen shows). */
    fun load(sessionId: String): RecordedSession {
        val entries = SessionZip.read(zipFile(sessionId))
        readLabels(sessionId)?.let { entries[FILE_LABELS] = it.toJson().toByteArray(Charsets.UTF_8) }
        return SessionReader.fromEntries(entries)
    }

    private fun writeAtomic(target: File, text: String) {
        target.parentFile!!.mkdirs()
        val tmp = File(target.parentFile!!, target.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        const val ZIP = "session.zip"
        const val SUMMARY = "summary.json"
    }
}
