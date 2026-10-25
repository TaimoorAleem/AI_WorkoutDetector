package dev.proprio.mobile.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.proprio.backend.BaseUrl
import dev.proprio.mobile.AppContainer
import dev.proprio.mobile.AuthViewModel
import dev.proprio.mobile.BuildConfig
import dev.proprio.mobile.HistoryViewModel
import dev.proprio.mobile.SessionBackendViewModel
import dev.proprio.mobile.RecordingsViewModel
import dev.proprio.mobile.ReviewViewModel
import dev.proprio.mobile.SettingsViewModel
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun ProprioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(), content = content)
}

private enum class Tab(val label: String) { SESSION("Session"), RECORDINGS("Recordings"), HISTORY("History"), SETTINGS("Settings") }

@Composable
fun AppRoot(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsVm: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel(container.settings) } })
    val settings by settingsVm.settings.collectAsState()

    var tab by rememberSaveable { mutableStateOf(Tab.SESSION) }
    var reviewId by rememberSaveable { mutableStateOf<String?>(null) }
    var showAuth by rememberSaveable { mutableStateOf(false) }
    val authVm: AuthViewModel = viewModel(factory = viewModelFactory { initializer { AuthViewModel(container.backend) } })
    val authState by authVm.auth.collectAsState()
    val authUi by authVm.state.collectAsState()
    val serverUrl = settings.serverUrl.ifBlank { AppContainer.defaultBaseUrl() }
    val saveSettings: (String, Boolean, Boolean) -> Unit = { id, consent, wrist ->
        val consentChanged = consent != settings.trainingConsent
        settingsVm.save(id, consent, wrist)
        if (consentChanged) scope.launch { container.backend.setConsent(consent) }
    }

    val share: (String) -> Unit = { id ->
        scope.launch {
            runCatching { container.exporter.build(id) }.onSuccess { shareZip(context, it) }
        }
    }

    if (!settings.firstRunComplete) {
        SettingsScreen(settings, firstRun = true, onSave = saveSettings, modifier = Modifier)
        return
    }

    if (showAuth) {
        BackHandler { showAuth = false }
        AuthScreen(
            state = authUi,
            serverUrl = serverUrl,
            initialParticipantId = settings.participantId,
            initialConsent = settings.trainingConsent,
            onLogin = { e, p -> authVm.login(e, p) { showAuth = false } },
            onRegister = { e, p, pid, c -> authVm.register(e, p, pid, c) { showAuth = false } },
            onCancel = { authVm.clearError(); showAuth = false },
        )
        return
    }

    val review = reviewId
    if (review != null) {
        BackHandler { reviewId = null }
        val vm: ReviewViewModel = viewModel(
            key = "review-$review",
            factory = viewModelFactory { initializer { ReviewViewModel(review, container.store, container.exporter, onLabelsChanged = { container.queueUpload(review) }) } },
        )
        val state by vm.state.collectAsState()
        ReviewScreen(
            state = state,
            onBack = { reviewId = null },
            onSelect = vm::select,
            onEdit = vm::applyEdit,
            onNudge = vm::nudge,
            onExport = { scope.launch { runCatching { vm.export() }.onSuccess { shareZip(context, it) } } },
            extra = {
                val backendVm: SessionBackendViewModel = viewModel(
                    key = "backend-$review",
                    factory = viewModelFactory {
                        initializer { SessionBackendViewModel(review, container.analysis, container.uploadStates, container.backend.auth.state) }
                    },
                )
                val backendState by backendVm.state.collectAsState()
                BackendPanel(
                    state = backendState,
                    onSignIn = { showAuth = true },
                    onUpload = { container.uploadNow(review) },
                    onRefresh = backendVm::refresh,
                )
            },
        )
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = {}, label = { Text(t.label) })
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (tab) {
            Tab.SESSION -> SessionScreen(container.coordinator, m)
            Tab.RECORDINGS -> {
                val vm: RecordingsViewModel = viewModel(factory = viewModelFactory { initializer { RecordingsViewModel(container.store) } })
                val items by vm.items.collectAsState()
                val tick by container.uploadStates.changes.collectAsState()
                val uploads = remember(items, tick) {
                    items.mapNotNull { i -> container.uploadStates.get(i.summary.sessionId)?.let { i.summary.sessionId to it } }.toMap()
                }
                RecordingsScreen(items, onOpen = { reviewId = it }, onExport = share, modifier = m, uploads = uploads)
            }
            Tab.HISTORY -> {
                val vm: HistoryViewModel = viewModel(
                    factory = viewModelFactory { initializer { HistoryViewModel(container.history, container.backend.auth.state) } },
                )
                val state by vm.state.collectAsState()
                HistoryScreen(state, onRefresh = vm::refresh, onSignIn = { showAuth = true }, modifier = m)
            }
            Tab.SETTINGS -> SettingsScreen(settings, firstRun = false, onSave = saveSettings, modifier = m, extra = {
                AccountSection(
                    auth = authState,
                    serverUrl = settings.serverUrl,
                    defaultServerUrl = AppContainer.defaultBaseUrl(),
                    urlProblem = { BaseUrl.problem(it, allowCleartext = BuildConfig.DEBUG) },
                    onSaveServerUrl = settingsVm::saveServerUrl,
                    onSignIn = { showAuth = true },
                    onSignOut = authVm::logout,
                )
            })
        }
    }
}

/** Opens the share sheet for an export zip via the app's FileProvider. */
fun shareZip(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Export ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
