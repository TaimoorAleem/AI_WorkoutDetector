package dev.proprio.mobile.data

import dev.proprio.protocol.Consent
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** First-run settings. Pseudonymous participant id only: no names, emails or other PII. */
@Serializable
data class UserSettings(
    val participantId: String = "",
    val trainingConsent: Boolean = false,
    val consentAtUtc: String? = null,
    val leftWristAcknowledged: Boolean = false,
) {
    val firstRunComplete: Boolean get() = participantId.isNotBlank() && leftWristAcknowledged

    fun consent(now: Instant): Consent = Consent(trainingConsent, consentAtUtc ?: now.toString())
}

object ParticipantId {
    private val VALID = Regex("[A-Za-z0-9_-]{1,32}")
    fun isValid(id: String) = VALID.matches(id)
}

interface SettingsStore {
    val settings: StateFlow<UserSettings>
    fun update(transform: (UserSettings) -> UserSettings)
}

/** Settings kept in one small JSON file in app-private storage. */
class FileSettingsStore(private val file: File) : SettingsStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val state = MutableStateFlow(load())
    override val settings: StateFlow<UserSettings> = state.asStateFlow()

    private fun load(): UserSettings = try {
        if (file.isFile) json.decodeFromString(UserSettings.serializer(), file.readText()) else UserSettings()
    } catch (e: Exception) {
        UserSettings()
    }

    @Synchronized
    override fun update(transform: (UserSettings) -> UserSettings) {
        val next = transform(state.value)
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.absolutePath + ".tmp")
        tmp.writeText(json.encodeToString(UserSettings.serializer(), next))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        state.value = next
    }
}

/** In-memory store for tests. */
class MemorySettingsStore(initial: UserSettings = UserSettings()) : SettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<UserSettings> = state.asStateFlow()
    override fun update(transform: (UserSettings) -> UserSettings) {
        state.value = transform(state.value)
    }
}
