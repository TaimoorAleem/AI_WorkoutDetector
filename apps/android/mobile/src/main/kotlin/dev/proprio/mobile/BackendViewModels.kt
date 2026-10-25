package dev.proprio.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.proprio.api.models.LoggedSet
import dev.proprio.backend.AnalysisRepository
import dev.proprio.backend.AuthResult
import dev.proprio.backend.AuthState
import dev.proprio.backend.BackendService
import dev.proprio.backend.HistoryRepository
import dev.proprio.backend.RemoteSession
import dev.proprio.backend.UploadState
import dev.proprio.backend.UploadStateStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(val busy: Boolean = false, val error: String? = null)

class AuthViewModel(private val backend: BackendService) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()
    val auth: StateFlow<AuthState> = backend.auth.state

    fun login(email: String, password: String, onSuccess: () -> Unit) = run(onSuccess) { backend.login(email, password) }

    fun register(email: String, password: String, participantId: String, trainingConsent: Boolean, onSuccess: () -> Unit) =
        run(onSuccess) { backend.register(email, password, participantId, trainingConsent) }

    fun logout() {
        viewModelScope.launch { backend.logout() }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun run(onSuccess: () -> Unit, call: suspend () -> AuthResult) {
        if (_state.value.busy) return
        _state.value = AuthUiState(busy = true)
        viewModelScope.launch {
            val result = try {
                call()
            } catch (e: Exception) {
                AuthResult.Failure("Sign-in failed: ${e.message ?: e.javaClass.simpleName}")
            }
            when (result) {
                AuthResult.Success -> {
                    _state.value = AuthUiState()
                    onSuccess()
                }
                is AuthResult.Failure -> _state.value = AuthUiState(error = result.message)
            }
        }
    }
}

data class SessionBackendState(
    val signedIn: Boolean = false,
    val upload: UploadState? = null,
    val remote: RemoteSession? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
)

/**
 * Backend status of one recording: the persisted upload state plus the server's session/analysis. Polls with backoff
 * while the server still works on it (uploaded, queued or analysing); a manual refresh fetches once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionBackendViewModel(
    private val sessionId: String,
    private val analysis: AnalysisRepository,
    private val states: UploadStateStore,
    private val auth: StateFlow<AuthState>,
) : ViewModel() {
    private val remote = MutableStateFlow<RemoteSession?>(null)
    private val refreshing = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    val state: StateFlow<SessionBackendState> = combine(auth, states.changes, remote, refreshing, error) { a, _, r, busy, e ->
        SessionBackendState(a is AuthState.SignedIn, states.get(sessionId), r, busy, e)
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionBackendState(auth.value is AuthState.SignedIn, states.get(sessionId)),
    )

    init {
        // Poll only while signed in, after the raw upload is on the server, and until the analysis settled.
        viewModelScope.launch {
            combine(auth, states.changes, remote) { a, _, r ->
                a is AuthState.SignedIn && states.get(sessionId)?.remoteUploaded == true && (r == null || r.pending)
            }.distinctUntilChanged().collectLatest { shouldPoll ->
                if (shouldPoll) analysis.awaitSettled(sessionId, onResult = ::apply)
            }
        }
    }

    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            apply(analysis.refresh(sessionId))
            refreshing.value = false
        }
    }

    private fun apply(r: AnalysisRepository.Result) {
        when (r) {
            is AnalysisRepository.Result.Ok -> {
                remote.value = r.remote
                error.value = null
            }
            AnalysisRepository.Result.NotSignedIn -> error.value = "Sign in to see the analysis."
            AnalysisRepository.Result.NotFound -> error.value = "The server does not know this recording yet."
            is AnalysisRepository.Result.Error -> error.value = r.message
        }
    }
}

data class HistoryUiState(
    val loading: Boolean = false,
    val signedIn: Boolean = false,
    val sets: List<LoggedSet> = emptyList(),
    val error: String? = null,
)

class HistoryViewModel(private val history: HistoryRepository, auth: StateFlow<AuthState>) : ViewModel() {
    private val _state = MutableStateFlow(HistoryUiState(signedIn = auth.value is AuthState.SignedIn))
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { auth.map { it is AuthState.SignedIn }.distinctUntilChanged().collectLatest { load(it) } }
    }

    fun refresh() {
        viewModelScope.launch { load(_state.value.signedIn) }
    }

    private suspend fun load(signedIn: Boolean) {
        if (!signedIn) {
            _state.value = HistoryUiState(signedIn = false)
            return
        }
        _state.update { it.copy(loading = true, signedIn = true, error = null) }
        _state.value = when (val r = history.load()) {
            is HistoryRepository.Result.Ok -> HistoryUiState(signedIn = true, sets = r.sets)
            HistoryRepository.Result.NotSignedIn -> HistoryUiState(signedIn = false)
            is HistoryRepository.Result.Error -> HistoryUiState(signedIn = true, error = r.message)
        }
    }
}
