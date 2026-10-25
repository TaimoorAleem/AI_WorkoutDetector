package dev.proprio.backend

import dev.proprio.api.models.CreateSessionRequest
import dev.proprio.api.models.CreateSessionRequestDevice
import dev.proprio.api.models.Session
import dev.proprio.api.models.SessionKind as ApiKind
import dev.proprio.api.models.SessionStatus
import dev.proprio.api.models.UploadTicket
import dev.proprio.protocol.FILE_META
import dev.proprio.protocol.Meta
import dev.proprio.protocol.ProtocolJson
import dev.proprio.protocol.SessionKind
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

/** Where the upload core finds a session's files. The zip is read-only: it is uploaded byte for byte. */
interface SessionFiles {
    fun zip(sessionId: String): File?
    fun labelsText(sessionId: String): String?
}

sealed interface UploadOutcome {
    /** Zip uploaded (now or earlier) and labels in sync. */
    data object Done : UploadOutcome

    /** Transient failure (network, 5xx, rate limit, expired URL): try again later with backoff. */
    data class Retry(val reason: String) : UploadOutcome

    /** Will not succeed by retrying (validation, foreign session id, missing file). */
    data class Failed(val reason: String) : UploadOutcome

    /** No usable login; resume after the user signs in. */
    data object AuthRequired : UploadOutcome
}

/**
 * Uploads one session: create (idempotent on the watch's session UUID) -> PUT the untouched zip to the presigned
 * URL -> complete -> PUT labels. Every step is safe to repeat, so a crash or retry at any point just continues:
 * - a 409 on create means the session already left CREATED (an earlier attempt got further): ask the server and skip ahead;
 * - once `complete` is accepted, [UploadState.remoteUploaded] is persisted and the zip is never sent again;
 * - labels are re-sent only when their hash differs from the last stored one, using the server's ETag as If-Match.
 */
class UploadCore(
    private val client: () -> BackendClient,
    private val states: UploadStateStore,
    private val files: SessionFiles,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun upload(sessionId: String): UploadOutcome {
        val c = client()
        if (!c.auth.signedIn) {
            record(sessionId, UploadOutcome.AuthRequired)
            return UploadOutcome.AuthRequired
        }
        val outcome = try {
            run(c, sessionId)
        } catch (e: java.util.zip.ZipException) {
            UploadOutcome.Failed("The recording file is damaged.")
        } catch (e: IOException) {
            UploadOutcome.Retry(e.message ?: "I/O error")
        } catch (e: kotlinx.serialization.SerializationException) {
            UploadOutcome.Failed("The recording metadata is unreadable.")
        } catch (e: IllegalArgumentException) {
            UploadOutcome.Failed(e.message ?: "Invalid recording.")
        }
        record(sessionId, outcome)
        return outcome
    }

    private suspend fun run(c: BackendClient, id: String): UploadOutcome {
        if (states.get(id)?.remoteUploaded != true) {
            val zip = files.zip(id) ?: return UploadOutcome.Failed("The recording file is missing.")
            states.update(id) { it.copy(phase = UploadPhase.UPLOADING, error = null, updatedAtMs = nowMs()) }
            uploadRaw(c, id, zip)?.let { return it }
        }
        return syncLabels(c, id)
    }

    /** Returns null when the raw zip is stored and completed on the server, else the outcome to report. */
    private suspend fun uploadRaw(c: BackendClient, id: String, zip: File): UploadOutcome? {
        val meta = readMeta(zip)
        val request = CreateSessionRequest(
            sessionId = UUID.fromString(id),
            kind = if (meta.kind == SessionKind.GYM) ApiKind.gym else ApiKind.calibration,
            sha256 = sha256(zip),
            sizeBytes = zip.length().toInt(),
            rawFormat = CreateSessionRequest.RawFormat._1,
            startedAt = OffsetDateTime.parse(meta.startedAtUtc),
            device = CreateSessionRequestDevice(meta.device.manufacturer, meta.device.model, meta.device.sdkInt),
            durationS = durationS(meta),
        )
        when (val created = apiCall { c.api.sessions.createSession(request) }) {
            is ApiResult.Ok -> {
                putObject(c, created.value, zip)?.let { return it }
            }
            is ApiResult.HttpError -> {
                if (created.code != 409) return classify(created)
                // Already registered. If it moved past CREATED an earlier attempt finished the upload.
                when (val existing = apiCall { c.api.sessions.getSession(UUID.fromString(id)) }) {
                    is ApiResult.Ok -> {
                        if (existing.value.status != SessionStatus.CREATED) {
                            markRemote(id, existing.value)
                            return null
                        }
                        return UploadOutcome.Retry("Session is still being created.")
                    }
                    is ApiResult.HttpError -> return if (existing.code == 404) {
                        UploadOutcome.Failed("This session id is already used by another account.")
                    } else {
                        classify(existing)
                    }
                    is ApiResult.NetworkError -> return retry(existing)
                }
            }
            is ApiResult.NetworkError -> return retry(created)
        }
        return when (val done = apiCall { c.api.sessions.completeUpload(UUID.fromString(id)) }) {
            is ApiResult.Ok -> {
                markRemote(id, done.value)
                null
            }
            is ApiResult.HttpError -> when (done.code) {
                // Already completed (or analysed) by an earlier attempt whose response was lost.
                409 -> when (val s = apiCall { c.api.sessions.getSession(UUID.fromString(id)) }) {
                    is ApiResult.Ok -> {
                        markRemote(id, s.value)
                        null
                    }
                    is ApiResult.HttpError -> classify(s)
                    is ApiResult.NetworkError -> retry(s)
                }
                // Object missing or size mismatch: the next attempt creates a fresh URL and uploads again.
                422 -> UploadOutcome.Retry(done.message)
                else -> classify(done)
            }
            is ApiResult.NetworkError -> retry(done)
        }
    }

    /** PUTs the zip to the presigned URL with exactly the headers the ticket demands; no bearer token. */
    private suspend fun putObject(c: BackendClient, ticket: UploadTicket, zip: File): UploadOutcome? {
        val type = (ticket.uploadHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value ?: "application/zip")
        val builder = Request.Builder().url(ticket.uploadUrl.toString()).put(zip.asRequestBody(type.toMediaType()))
        for ((k, v) in ticket.uploadHeaders) {
            if (k.equals("Content-Type", true) || k.equals("Content-Length", true) || k.equals("Host", true)) continue
            builder.header(k, v)
        }
        val code = try {
            withContext(Dispatchers.IO) { c.http.newCall(builder.build()).execute().use { it.code } }
        } catch (e: IOException) {
            return UploadOutcome.Retry(e.message ?: "Upload interrupted")
        }
        return when {
            code in 200..299 -> null
            // Expired or rejected signature: a retry starts with a new ticket.
            code == 400 || code == 403 || code == 408 || code == 429 || code >= 500 -> UploadOutcome.Retry("Storage answered HTTP $code")
            else -> UploadOutcome.Failed("Storage answered HTTP $code")
        }
    }

    private suspend fun syncLabels(c: BackendClient, id: String): UploadOutcome {
        val text = files.labelsText(id) ?: return UploadOutcome.Done
        val hash = sha256(text.toByteArray())
        if (states.get(id)?.labelsHash == hash) return UploadOutcome.Done
        val document = Json.parseToJsonElement(text).jsonObject
        val uuid = UUID.fromString(id)
        repeat(3) {
            val etag = when (val g = apiCall { c.api.labels.getLabels(uuid) }) {
                is ApiResult.Ok -> g.headers["ETag"]
                is ApiResult.HttpError -> if (g.code == 404) null else return classify(g)
                is ApiResult.NetworkError -> return retry(g)
            }
            when (val p = apiCall { c.api.labels.putLabels(uuid, document, etag) }) {
                is ApiResult.Ok -> {
                    states.update(id) { it.copy(labelsHash = hash, labelsError = null) }
                    return UploadOutcome.Done
                }
                is ApiResult.HttpError -> when (p.code) {
                    412 -> Unit // somebody changed them between our GET and PUT: re-read the ETag
                    400 -> {
                        // The server rejected this labels document; retrying the same bytes cannot help. The raw upload is fine.
                        states.update(id) { it.copy(labelsError = p.message) }
                        return UploadOutcome.Done
                    }
                    else -> return classify(p)
                }
                is ApiResult.NetworkError -> return retry(p)
            }
        }
        return UploadOutcome.Retry("Labels changed on the server repeatedly.")
    }

    private fun markRemote(id: String, s: Session) {
        states.update(id) {
            val base = it.copy(remoteUploaded = true, error = null, updatedAtMs = nowMs())
            when (s.status) {
                SessionStatus.ANALYZED -> base.copy(phase = UploadPhase.ANALYZED)
                SessionStatus.FAILED -> base.copy(phase = UploadPhase.FAILED, error = s.failureReason ?: "analysis_failed")
                else -> base.copy(phase = UploadPhase.UPLOADED)
            }
        }
    }

    private fun record(id: String, outcome: UploadOutcome) {
        states.update(id) {
            when (outcome) {
                UploadOutcome.Done -> it // markRemote already set the phase
                is UploadOutcome.Retry -> it.copy(
                    phase = if (it.remoteUploaded) it.phase else UploadPhase.PENDING,
                    attempts = it.attempts + 1, error = outcome.reason, updatedAtMs = nowMs(),
                )
                is UploadOutcome.Failed -> it.copy(
                    phase = if (it.remoteUploaded) it.phase else UploadPhase.FAILED,
                    attempts = it.attempts + 1, error = outcome.reason, updatedAtMs = nowMs(),
                )
                UploadOutcome.AuthRequired -> it.copy(
                    phase = if (it.remoteUploaded) it.phase else UploadPhase.PENDING,
                    error = "Sign in to upload.", updatedAtMs = nowMs(),
                )
            }
        }
    }

    private fun classify(e: ApiResult.HttpError): UploadOutcome = when {
        e.code == 401 -> UploadOutcome.AuthRequired
        e.code == 408 || e.code == 429 || e.code >= 500 -> UploadOutcome.Retry(e.message)
        else -> UploadOutcome.Failed(e.message)
    }

    private fun retry(e: ApiResult.NetworkError) = UploadOutcome.Retry(e.cause.message ?: e.cause.javaClass.simpleName)

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
            return md.digest().toHex()
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

        /** Reads only meta.json from the zip (the sensor CSVs can be tens of MB). */
        fun readMeta(zip: File): Meta = ZipFile(zip).use { zf ->
            val entry = zf.getEntry(FILE_META) ?: throw IllegalArgumentException("meta.json missing in ${zip.name}")
            ProtocolJson.decodeFromString(Meta.serializer(), zf.getInputStream(entry).readBytes().toString(Charsets.UTF_8))
        }

        private fun durationS(meta: Meta): BigDecimal? = meta.endedAtUtc?.let {
            val d = Duration.between(OffsetDateTime.parse(meta.startedAtUtc), OffsetDateTime.parse(it))
            BigDecimal.valueOf(d.toMillis(), 3).takeIf { v -> v.signum() >= 0 }
        }
    }
}
