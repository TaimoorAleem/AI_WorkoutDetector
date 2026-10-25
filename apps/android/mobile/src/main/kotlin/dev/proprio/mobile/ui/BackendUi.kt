package dev.proprio.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.proprio.api.models.LoggedSet
import dev.proprio.backend.AnalysisView
import dev.proprio.backend.AuthState
import dev.proprio.backend.SegmentRow
import dev.proprio.backend.UploadPhase
import dev.proprio.backend.UploadState
import dev.proprio.mobile.AuthUiState
import dev.proprio.mobile.HistoryUiState
import dev.proprio.mobile.SessionBackendState
import dev.proprio.mobile.data.ParticipantId
import kotlin.math.roundToInt

/** One-line upload/analysis status for a recording. */
fun uploadStatusText(upload: UploadState?, signedIn: Boolean): String = when {
    upload == null || (upload.phase == UploadPhase.PENDING && upload.error == null) ->
        if (signedIn) "Not uploaded yet" else "Not signed in"
    else -> when (upload.phase) {
        UploadPhase.PENDING -> "Upload pending: ${upload.error}"
        UploadPhase.UPLOADING -> "Uploading..."
        UploadPhase.UPLOADED -> "Uploaded, analysis in progress"
        UploadPhase.ANALYZED -> "Analyzed"
        UploadPhase.FAILED -> if (upload.analysisFailed) "Analysis failed: ${upload.error}" else "Upload failed: ${upload.error}"
    }
}

/** Short label for the recordings list. */
fun uploadChip(upload: UploadState?): String? = when (upload?.phase) {
    null -> null
    UploadPhase.PENDING -> "upload pending"
    UploadPhase.UPLOADING -> "uploading"
    UploadPhase.UPLOADED -> "uploaded"
    UploadPhase.ANALYZED -> "analyzed"
    UploadPhase.FAILED -> if (upload.analysisFailed) "analysis failed" else "upload failed"
}

/** Backend card of the session detail: upload status, manual upload, and the analysis when ready. */
@Composable
fun BackendPanel(
    state: SessionBackendState,
    onSignIn: () -> Unit,
    onUpload: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Analysis", style = MaterialTheme.typography.titleMedium)
            Text(uploadStatusText(state.upload, state.signedIn), Modifier.testTag("backend_status"))
            state.upload?.labelsError?.let {
                Text("Labels not accepted by the server: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            val remote = state.remote
            if (remote?.failureReason != null) {
                Text("Error code: ${remote.failureReason}", Modifier.testTag("analysis_error"), color = MaterialTheme.colorScheme.error)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.signedIn) {
                    Button(onClick = onSignIn, Modifier.testTag("backend_sign_in")) { Text("Sign in") }
                } else {
                    val uploaded = state.upload?.remoteUploaded == true
                    if (!uploaded) {
                        Button(
                            onClick = onUpload,
                            enabled = state.upload?.phase != UploadPhase.UPLOADING,
                            modifier = Modifier.testTag("upload_now"),
                        ) { Text(if (state.upload?.phase == UploadPhase.FAILED) "Retry upload" else "Upload now") }
                    } else {
                        OutlinedButton(onClick = onRefresh, enabled = !state.refreshing, modifier = Modifier.testTag("backend_refresh")) {
                            Text(if (state.refreshing) "Refreshing..." else "Refresh")
                        }
                    }
                }
            }

            remote?.analysis?.let { AnalysisTable(it) }
        }
    }
}

@Composable
fun AnalysisTable(view: AnalysisView, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        view.model?.let { Text("Model: $it", style = MaterialTheme.typography.bodySmall) }
        if (view.rows.isEmpty()) Text("No sets found in this recording.")
        view.rows.forEach { SegmentRowView(it) }
    }
}

@Composable
private fun SegmentRowView(row: SegmentRow) {
    val bad = MaterialTheme.colorScheme.error
    val title = if (row.segmentId < 0) "Rest" else "${row.kind.replaceFirstChar { it.uppercase() }} ${row.segmentId}"
    Column(Modifier.fillMaxWidth().testTag("analysis_row_${row.segmentId}")) {
        Text("$title, ${row.startS.roundToInt()}-${row.endS.roundToInt()} s", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text("You", style = MaterialTheme.typography.labelSmall)
                Text(row.userExercise?.let(::exerciseName) ?: "not labeled")
                Text("${row.userReps ?: "-"} reps" + (row.loadKg?.let { ", ${formatKg(it)} kg" } ?: ""))
            }
            Column(Modifier.weight(1f)) {
                Text("Model", style = MaterialTheme.typography.labelSmall)
                Text(
                    (row.predictedExercise?.let(::exerciseName) ?: "no lift") + (row.confidence?.let { " (${(it * 100).roundToInt()}%)" } ?: ""),
                    color = if (row.exerciseMatches == false) bad else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("predicted_${row.segmentId}"),
                )
                val delta = row.repDelta
                Text(
                    "${row.predictedReps ?: "-"} reps" + (delta?.takeIf { it != 0 }?.let { " (${if (it > 0) "+" else ""}$it)" } ?: ""),
                    color = if (delta != null && delta != 0) bad else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private fun exerciseName(e: String) = when (e) {
    "dead" -> "Deadlift"
    "ohp" -> "Overhead press"
    else -> e.replaceFirstChar { it.uppercase() }
}

/** Logged sets across sessions, newest first. */
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onRefresh: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("History", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            if (state.signedIn) TextButton(onClick = onRefresh, modifier = Modifier.testTag("history_refresh")) { Text("Refresh") }
        }
        when {
            !state.signedIn -> {
                Text("Sign in to see the sets logged from your analysed workouts.")
                Button(onClick = onSignIn, Modifier.testTag("history_sign_in")) { Text("Sign in") }
            }
            state.loading -> Text("Loading...")
            state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error)
            state.sets.isEmpty() -> Text("No sets in the last 90 days. Upload a recording to see it here.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.sets, key = { "${it.sessionId}-${it.segmentId}" }) { LoggedSetRow(it) }
            }
        }
    }
}

@Composable
private fun LoggedSetRow(set: LoggedSet) {
    Card(Modifier.fillMaxWidth().testTag("history_${set.sessionId}_${set.segmentId}")) {
        Column(Modifier.padding(12.dp)) {
            Text(exerciseName(set.exercise.value), style = MaterialTheme.typography.titleMedium)
            Text(
                listOfNotNull(
                    set.date.toString().take(16).replace('T', ' '),
                    set.reps?.let { "$it reps" },
                    set.loadKg?.let { "${formatKg(it.toDouble())} kg" },
                    if (set.source == LoggedSet.Source.user) "logged by you" else "detected by the model",
                ).joinToString("  -  "),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Sign in / create account. Registration requires a 12+ character password (the server enforces the same). */
@Composable
fun AuthScreen(
    state: AuthUiState,
    serverUrl: String,
    initialParticipantId: String,
    initialConsent: Boolean,
    onLogin: (email: String, password: String) -> Unit,
    onRegister: (email: String, password: String, participantId: String, trainingConsent: Boolean) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var register by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var participant by remember { mutableStateOf(initialParticipantId) }
    var consent by remember { mutableStateOf(initialConsent) }

    val emailOk = email.trim().let { "@" in it && "." in it.substringAfter("@") }
    val passwordOk = if (register) password.length in 12..128 else password.isNotEmpty()
    val participantOk = participant.isBlank() || ParticipantId.isValid(participant.trim())

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("auth_cancel")) { Text("Back") }
            Text(if (register) "Create account" else "Sign in", style = MaterialTheme.typography.titleLarge)
        }
        Text("Server: $serverUrl", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("auth_server"))

        OutlinedTextField(
            value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth().testTag("auth_email"),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = { if (register) Text("At least 12 characters") },
            isError = register && password.isNotEmpty() && !passwordOk,
            modifier = Modifier.fillMaxWidth().testTag("auth_password"),
        )
        if (register) {
            OutlinedTextField(
                value = participant, onValueChange = { participant = it }, label = { Text("Participant id (optional)") }, singleLine = true,
                supportingText = { Text("Pseudonymous, e.g. P01. Do not enter your name.") },
                isError = !participantOk,
                modifier = Modifier.fillMaxWidth().testTag("auth_participant"),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Allow my recordings to be used for model training", Modifier.weight(1f))
                Switch(consent, { consent = it }, modifier = Modifier.testTag("auth_consent"))
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("auth_error")) }
        Button(
            onClick = {
                if (register) onRegister(email.trim(), password, participant.trim(), consent) else onLogin(email.trim(), password)
            },
            enabled = emailOk && passwordOk && participantOk && !state.busy,
            modifier = Modifier.fillMaxWidth().testTag("auth_submit"),
        ) { Text(if (state.busy) "Please wait..." else if (register) "Create account" else "Sign in") }
        TextButton(onClick = { register = !register }, modifier = Modifier.testTag("auth_toggle")) {
            Text(if (register) "I already have an account" else "Create an account")
        }
    }
}

/** Server address and account block, shown at the bottom of Settings. */
@Composable
fun AccountSection(
    auth: AuthState,
    serverUrl: String,
    defaultServerUrl: String,
    urlProblem: (String) -> String?,
    onSaveServerUrl: (String) -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by remember(serverUrl) { mutableStateOf(serverUrl) }
    val shown = url.ifBlank { defaultServerUrl }
    val problem = urlProblem(shown)
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Account", style = MaterialTheme.typography.titleMedium)
            when (auth) {
                is AuthState.SignedIn -> {
                    Text("Signed in" + (auth.email?.let { " as $it" } ?: ""), Modifier.testTag("account_status"))
                    OutlinedButton(onClick = onSignOut, Modifier.testTag("sign_out")) { Text("Sign out") }
                }
                AuthState.SignedOut -> {
                    Text("Not signed in. Recordings stay on this phone until you sign in.", Modifier.testTag("account_status"))
                    Button(onClick = onSignIn, Modifier.testTag("sign_in")) { Text("Sign in or create account") }
                }
            }
            OutlinedTextField(
                value = url, onValueChange = { url = it }, label = { Text("Server address") }, singleLine = true,
                placeholder = { Text(defaultServerUrl) },
                supportingText = { Text(problem ?: "Blank uses $defaultServerUrl") },
                isError = problem != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().testTag("server_url"),
            )
            OutlinedButton(
                onClick = { onSaveServerUrl(url.trim()) },
                enabled = problem == null && url.trim() != serverUrl,
                modifier = Modifier.testTag("save_server"),
            ) { Text("Save address") }
        }
    }
}
