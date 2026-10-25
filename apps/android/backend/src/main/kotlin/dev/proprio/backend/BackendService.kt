package dev.proprio.backend

import dev.proprio.api.models.ConsentUpdate
import dev.proprio.api.models.LoginRequest
import dev.proprio.api.models.RefreshRequest
import dev.proprio.api.models.RegisterRequest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

object BaseUrl {
    /** Null if [url] is usable; otherwise why not. Release builds ([allowCleartext] false) require https. */
    fun problem(url: String, allowCleartext: Boolean): String? {
        val parsed = url.trim().toHttpUrlOrNull() ?: return "Enter a full URL such as https://api.example.com"
        if (!parsed.isHttps && !allowCleartext) return "Use an https:// address."
        return null
    }

    fun normalize(url: String) = url.trim().trimEnd('/') + "/"
}

sealed interface AuthResult {
    data object Success : AuthResult
    data class Failure(val message: String) : AuthResult
}

/**
 * Entry point to the backend for the app: owns the [AuthManager], rebuilds the [BackendClient] when the base URL
 * setting changes, and wraps login, registration and logout in user-presentable results.
 */
class BackendService(
    secrets: SecretStore,
    private val baseUrl: () -> String,
    private val allowCleartext: Boolean,
    private val httpBase: OkHttpClient = OkHttpClient(),
) {
    val auth = AuthManager(secrets)

    private var cached: BackendClient? = null

    /** The client for the current base URL setting. Throws [IllegalStateException] when that URL is invalid. */
    @Synchronized
    fun client(): BackendClient {
        val url = baseUrl()
        BaseUrl.problem(url, allowCleartext)?.let { throw IllegalStateException("Server address: $it") }
        val normalized = BaseUrl.normalize(url)
        cached?.takeIf { it.baseUrl == normalized }?.let { return it }
        return BackendClient(normalized, auth, httpBase).also { cached = it }
    }

    suspend fun login(email: String, password: String): AuthResult = authenticate(email.trim()) {
        it.plain.auth.login(LoginRequest(email.trim(), password))
    }

    /** Registers, then records the user's training-use consent on the account (accounts start with no consent). */
    suspend fun register(email: String, password: String, participantId: String?, trainingConsent: Boolean): AuthResult {
        val result = authenticate(email.trim()) {
            it.plain.auth.register(RegisterRequest(email.trim(), password, participantId?.takeIf { p -> p.isNotBlank() }))
        }
        if (result is AuthResult.Success && trainingConsent) setConsent(true)
        return result
    }

    /** Best-effort: the local setting stays authoritative and is pushed again on the next change. */
    suspend fun setConsent(trainingUse: Boolean): Boolean {
        if (!auth.signedIn) return false
        val c = clientOrNull() ?: return false
        return apiCall { c.api.account.setConsent(ConsentUpdate(trainingUse)) } is ApiResult.Ok
    }

    /** Revokes the refresh token family server-side if reachable, and always signs out locally. */
    suspend fun logout() {
        val refresh = auth.refreshToken
        val c = clientOrNull()
        if (refresh != null && c != null) apiCall { c.api.auth.logout(RefreshRequest(refresh)) }
        auth.signOut()
    }

    private fun clientOrNull() = try {
        client()
    } catch (e: IllegalStateException) {
        null
    }

    private suspend fun authenticate(
        email: String,
        call: suspend (BackendClient) -> retrofit2.Response<dev.proprio.api.models.TokenPair>,
    ): AuthResult {
        val c = try {
            client()
        } catch (e: IllegalStateException) {
            return AuthResult.Failure(e.message ?: "Invalid server address.")
        }
        return when (val r = apiCall { call(c) }) {
            is ApiResult.Ok -> {
                auth.signIn(email, r.value)
                AuthResult.Success
            }
            is ApiResult.HttpError -> AuthResult.Failure(
                when (r.code) {
                    401 -> "Wrong email or password."
                    409 -> "An account with this email already exists."
                    429 -> "Too many attempts. Try again in a minute."
                    400 -> r.problem?.errors?.joinToString("; ") { "${it.field ?: ""} ${it.message ?: ""}".trim() }
                        ?.takeIf { it.isNotBlank() } ?: r.message
                    else -> "Server error (${r.code})."
                },
            )
            is ApiResult.NetworkError -> AuthResult.Failure("Could not reach the server. Check the address and your connection.")
        }
    }
}
