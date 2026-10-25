package dev.proprio.backend

import dev.proprio.api.models.TokenPair
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface AuthState {
    data object SignedOut : AuthState
    data class SignedIn(val email: String?) : AuthState
}

/** Result of one attempt to exchange the refresh token. */
sealed interface RefreshOutcome {
    data class Success(val tokens: TokenPair) : RefreshOutcome

    /** The server refused the token (expired, revoked or reuse detected): the session is over. */
    data object Rejected : RefreshOutcome

    /** Server unreachable or failing: keep the session, fail only this request. */
    data object Unavailable : RefreshOutcome
}

/**
 * Token custody. The short-lived access token lives only in memory; the rotating refresh token goes to the
 * [SecretStore]. Refreshes are serialised on [lock] so concurrent 401s trigger exactly one rotation (a second
 * use of a rotated token would make the server revoke the whole family). Tokens are never logged.
 */
class AuthManager(private val secrets: SecretStore) {
    private val lock = Any()

    @Volatile var accessToken: String? = null
        private set

    private val _state = MutableStateFlow<AuthState>(
        if (secrets.get(REFRESH) != null) AuthState.SignedIn(secrets.get(EMAIL)) else AuthState.SignedOut,
    )
    val state: StateFlow<AuthState> = _state.asStateFlow()

    val signedIn: Boolean get() = _state.value is AuthState.SignedIn
    val refreshToken: String? get() = secrets.get(REFRESH)

    fun signIn(email: String?, tokens: TokenPair) = synchronized(lock) {
        store(tokens)
        if (email != null) secrets.put(EMAIL, email)
        _state.value = AuthState.SignedIn(email ?: secrets.get(EMAIL))
    }

    fun signOut() = synchronized(lock) {
        accessToken = null
        secrets.remove(REFRESH)
        secrets.remove(EMAIL)
        _state.value = AuthState.SignedOut
    }

    /**
     * Called by the authenticator after a 401 for a request that carried [staleToken] (null if it carried none).
     * Returns the access token to retry with, or null to give up. If another thread already refreshed while this
     * one waited for the lock, the new token is reused without touching the server.
     */
    fun tokenAfterUnauthorized(staleToken: String?, refresh: (refreshToken: String) -> RefreshOutcome): String? =
        synchronized(lock) {
            accessToken?.takeIf { it != staleToken }?.let { return it }
            val current = secrets.get(REFRESH) ?: return null
            when (val outcome = refresh(current)) {
                is RefreshOutcome.Success -> {
                    store(outcome.tokens)
                    outcome.tokens.accessToken
                }
                RefreshOutcome.Rejected -> {
                    signOut()
                    null
                }
                RefreshOutcome.Unavailable -> throw IOException("Token refresh unavailable")
            }
        }

    private fun store(tokens: TokenPair) {
        // Persist the rotated refresh token before exposing the new access token.
        secrets.put(REFRESH, tokens.refreshToken)
        accessToken = tokens.accessToken
    }

    private companion object {
        const val REFRESH = "refresh_token"
        const val EMAIL = "email"
    }
}
