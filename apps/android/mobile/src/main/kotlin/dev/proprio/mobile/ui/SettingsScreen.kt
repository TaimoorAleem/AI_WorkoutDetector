package dev.proprio.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.proprio.mobile.data.ParticipantId
import dev.proprio.mobile.data.UserSettings

/** First-run setup and later edits: pseudonymous participant id, training consent, left-wrist reminder. */
@Composable
fun SettingsScreen(
    settings: UserSettings,
    firstRun: Boolean,
    onSave: (participantId: String, trainingConsent: Boolean, leftWrist: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** Extra cards under the form (account and server address; not shown on first run). */
    extra: @Composable () -> Unit = {},
) {
    var id by remember(settings) { mutableStateOf(settings.participantId) }
    var consent by remember(settings) { mutableStateOf(settings.trainingConsent) }
    var wrist by remember(settings) { mutableStateOf(settings.leftWristAcknowledged) }
    val valid = ParticipantId.isValid(id.trim())

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(if (firstRun) "Welcome to Proprio" else "Settings", style = MaterialTheme.typography.headlineSmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Wear the watch on your LEFT wrist.", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("wrist_reminder"))
                Text("The dataset and the models assume left-wrist data.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(wrist, { wrist = it }, modifier = Modifier.testTag("wrist_ack"))
                    Text("I will wear it on my left wrist")
                }
            }
        }

        OutlinedTextField(
            value = id,
            onValueChange = { id = it },
            label = { Text("Participant id (e.g. P01)") },
            supportingText = { Text("Pseudonymous. Do not enter your name or email.") },
            isError = id.isNotEmpty() && !valid,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("participant_id"),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Allow my recordings to be used for model training")
                settings.consentAtUtc?.let { Text("Recorded $it", style = MaterialTheme.typography.bodySmall) }
            }
            Switch(consent, { consent = it }, modifier = Modifier.testTag("consent"))
        }

        Button(
            onClick = { onSave(id.trim(), consent, wrist) },
            enabled = valid && wrist,
            modifier = Modifier.fillMaxWidth().testTag("save_settings"),
        ) { Text(if (firstRun) "Continue" else "Save") }
        extra()
    }
}
