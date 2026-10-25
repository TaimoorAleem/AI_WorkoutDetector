package dev.proprio.backend

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the UI shows per session. */
@Serializable
enum class UploadPhase { PENDING, UPLOADING, UPLOADED, ANALYZED, FAILED }

/**
 * Persisted per-session upload progress (`upload.json` next to the session's zip), so it survives process death
 * and a finished upload is never repeated.
 */
@Serializable
data class UploadState(
    val phase: UploadPhase = UploadPhase.PENDING,
    /** The raw zip is on the server and `complete` was accepted: never upload it again. */
    val remoteUploaded: Boolean = false,
    /** SHA-256 of the labels.json last stored on the server; a different hash means labels need re-sending. */
    val labelsHash: String? = null,
    val attempts: Int = 0,
    /** Last upload error, or the server's failure reason when analysis failed. */
    val error: String? = null,
    val labelsError: String? = null,
    val updatedAtMs: Long = 0,
) {
    /** The upload worked but the server's analysis did not. */
    val analysisFailed: Boolean get() = phase == UploadPhase.FAILED && remoteUploaded
}

interface UploadStateStore {
    val changes: StateFlow<Int>
    fun get(sessionId: String): UploadState?
    fun update(sessionId: String, transform: (UploadState) -> UploadState): UploadState
}

class FileUploadStateStore(private val dirFor: (String) -> File) : UploadStateStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val _changes = MutableStateFlow(0)
    override val changes: StateFlow<Int> = _changes.asStateFlow()

    private fun file(id: String) = File(dirFor(id), "upload.json")

    override fun get(sessionId: String): UploadState? = try {
        file(sessionId).takeIf { it.isFile }?.let { json.decodeFromString(UploadState.serializer(), it.readText()) }
    } catch (e: Exception) {
        null
    }

    @Synchronized
    override fun update(sessionId: String, transform: (UploadState) -> UploadState): UploadState {
        val next = transform(get(sessionId) ?: UploadState())
        val target = file(sessionId)
        target.parentFile!!.mkdirs()
        val tmp = File(target.parentFile!!, target.name + ".tmp")
        tmp.writeText(json.encodeToString(UploadState.serializer(), next))
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        _changes.value++
        return next
    }
}

class MemoryUploadStateStore : UploadStateStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, UploadState>()
    private val _changes = MutableStateFlow(0)
    override val changes: StateFlow<Int> = _changes.asStateFlow()
    override fun get(sessionId: String) = map[sessionId]

    @Synchronized
    override fun update(sessionId: String, transform: (UploadState) -> UploadState): UploadState {
        val next = transform(map[sessionId] ?: UploadState())
        map[sessionId] = next
        _changes.value++
        return next
    }
}
