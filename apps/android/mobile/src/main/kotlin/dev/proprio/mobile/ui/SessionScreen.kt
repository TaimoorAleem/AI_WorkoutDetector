package dev.proprio.mobile.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.proprio.mobile.SessionCoordinator
import dev.proprio.mobile.SessionUiState
import dev.proprio.mobile.SetConfig
import dev.proprio.mobile.domain.CATEGORIES
import dev.proprio.mobile.domain.EXERCISES
import dev.proprio.protocol.MarkKind
import dev.proprio.protocol.SessionKind
import dev.proprio.protocol.WatchStatus

/** Callbacks of the session screen; bound to [SessionCoordinator] by the stateful overload. */
class SessionActions(
    val startSession: (SessionKind) -> Unit,
    val endSession: () -> Unit,
    val startSet: () -> Unit,
    val startOther: () -> Unit,
    val stopSegment: () -> Unit,
    val setConfig: ((SetConfig) -> SetConfig) -> Unit,
    val confirmReps: (reps: Int, rpe: Int?, note: String) -> Unit,
    val dismissPrompt: () -> Unit,
    val dismissMessage: () -> Unit,
    val refresh: () -> Unit,
)

@Composable
fun SessionScreen(coordinator: SessionCoordinator, modifier: Modifier = Modifier) {
    val state by coordinator.state.collectAsState()
    val actions = remember(coordinator) {
        SessionActions(
            coordinator::startSession, coordinator::endSession, coordinator::startSet, coordinator::startOther,
            coordinator::stopSegment, coordinator::setConfig, coordinator::confirmReps, coordinator::dismissPrompt,
            coordinator::dismissMessage, coordinator::refreshConnection,
        )
    }
    SessionContent(state, actions, modifier)
}

@Composable
fun SessionContent(state: SessionUiState, actions: SessionActions, modifier: Modifier = Modifier) {
    val watch = state.watch
    val recording = watch?.state == WatchStatus.State.RECORDING
    val gym = recording && watch.sessionKind != SessionKind.CALIBRATION

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusCard(state, actions.refresh)

        state.message?.let {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f).testTag("message"), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = actions.dismissMessage) { Text("OK") }
                }
            }
        }

        if (!recording) {
            Button(
                onClick = { actions.startSession(SessionKind.GYM) },
                enabled = state.connected,
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) { Text("Start gym session") }
            OutlinedButton(
                onClick = { actions.startSession(SessionKind.CALIBRATION) },
                enabled = state.connected,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start calibration") }
        } else {
            if (gym) {
                SetConfigCard(state.config, actions.setConfig, enabled = state.open == null)
                SetButtons(state, actions)
            } else {
                Text("Calibration: follow the prompts on the watch.", style = MaterialTheme.typography.bodyLarge)
            }
            OutlinedButton(onClick = actions.endSession, modifier = Modifier.fillMaxWidth()) { Text("End session") }
        }
    }

    state.repPrompt?.let { prompt ->
        RepPickerDialog(
            title = listOfNotNull(prompt.exercise, prompt.loadKg?.let { "${formatKg(it)} kg" }).joinToString(" ")
                .ifEmpty { "Set finished" },
            onSave = actions.confirmReps,
            onDismiss = actions.dismissPrompt,
        )
    }
}

@Composable
private fun StatusCard(state: SessionUiState, refresh: () -> Unit) {
    val watch = state.watch
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    if (state.connected) "Watch connected" else "Watch not connected",
                    Modifier.weight(1f).testTag("connection"),
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = refresh) { Text("Refresh") }
            }
            if (!state.connected) {
                Text("Open Proprio on the watch and keep Bluetooth on.", style = MaterialTheme.typography.bodySmall)
            }
            when (watch?.state) {
                WatchStatus.State.RECORDING -> {
                    Text("Recording ${formatElapsed(watch.elapsedS)}", Modifier.testTag("recording"))
                    Text("acc ${formatHz(watch.accRateHz)}  gyro ${formatHz(watch.gyrRateHz)}", Modifier.testTag("rates"))
                    val open = state.open
                    val openText = when {
                        watch.calibrationStep != null -> "Calibration step: ${watch.calibrationStep}"
                        open != null -> "Open segment: ${open.kind.name.lowercase()} #${open.segmentId}"
                        watch.openSegmentId != null -> "Open segment #${watch.openSegmentId}"
                        else -> "No open segment"
                    }
                    Text(openText, Modifier.testTag("open_segment"))
                }
                WatchStatus.State.TRANSFERRING -> Text("Transferring session to the phone...")
                else -> Text("Idle")
            }
        }
    }
}

@Composable
private fun SetConfigCard(config: SetConfig, update: ((SetConfig) -> SetConfig) -> Unit, enabled: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Next set", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EXERCISES.forEach { ex ->
                    FilterChip(
                        selected = config.exercise == ex,
                        onClick = { update { it.copy(exercise = ex) } },
                        label = { Text(ex) },
                        enabled = enabled,
                        modifier = Modifier.testTag("exercise_$ex"),
                    )
                }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { update { it.copy(loadKg = ((it.loadKg ?: 0.0) - 2.5).coerceAtLeast(0.0)) } }, enabled = enabled) { Text("-2.5") }
                OutlinedTextField(
                    value = config.loadKg?.let { formatKg(it) }.orEmpty(),
                    onValueChange = { text -> update { it.copy(loadKg = text.replace(',', '.').toDoubleOrNull()) } },
                    label = { Text("Load (kg)") },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).testTag("load"),
                )
                OutlinedButton(onClick = { update { it.copy(loadKg = (it.loadKg ?: 0.0) + 2.5) } }, enabled = enabled) { Text("+2.5") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CATEGORIES.forEach { c ->
                    FilterChip(
                        selected = config.category == c,
                        onClick = { update { it.copy(category = c) } },
                        label = { Text(c) },
                        enabled = enabled,
                        modifier = Modifier.testTag("category_$c"),
                    )
                }
            }
        }
    }
}

@Composable
private fun SetButtons(state: SessionUiState, actions: SessionActions) {
    val open = state.open
    when (open?.kind) {
        null -> {
            Button(onClick = actions.startSet, modifier = Modifier.fillMaxWidth().height(96.dp)) {
                Text("Start set", style = MaterialTheme.typography.headlineSmall)
            }
            OutlinedButton(onClick = actions.startOther, modifier = Modifier.fillMaxWidth()) { Text("Start other") }
        }
        MarkKind.SET -> Button(onClick = actions.stopSegment, modifier = Modifier.fillMaxWidth().height(96.dp)) {
            Text("Stop set", style = MaterialTheme.typography.headlineSmall)
        }
        MarkKind.OTHER -> Button(onClick = actions.stopSegment, modifier = Modifier.fillMaxWidth().height(96.dp)) {
            Text("Stop other", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

/** Rep count (required), RPE and note (optional) for the set that just ended. Starts empty. */
@Composable
fun RepPickerDialog(title: String, onSave: (Int, Int?, String) -> Unit, onDismiss: () -> Unit) {
    var reps by remember { mutableStateOf<Int?>(null) }
    var rpe by remember { mutableStateOf<Int?>(null) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How many reps? $title") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Reps: ${reps ?: "-"}", Modifier.testTag("reps_value"), style = MaterialTheme.typography.titleLarge)
                (1..20).chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { n ->
                            FilterChip(
                                selected = reps == n,
                                onClick = { reps = n },
                                label = { Text("$n") },
                                modifier = Modifier.testTag("rep_$n"),
                            )
                        }
                    }
                }
                Text("RPE (optional)")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (5..10).forEach { n ->
                        FilterChip(
                            selected = rpe == n,
                            onClick = { rpe = if (rpe == n) null else n },
                            label = { Text("$n") },
                            modifier = Modifier.testTag("rpe_$n"),
                        )
                    }
                }
                OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.testTag("note"))
            }
        },
        confirmButton = {
            TextButton(onClick = { reps?.let { onSave(it, rpe, note.trim()) } }, enabled = reps != null, modifier = Modifier.testTag("save_reps")) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Skip") } },
    )
}

internal fun formatElapsed(s: Double?): String {
    val total = (s ?: 0.0).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

internal fun formatHz(hz: Double?): String = if (hz == null) "- Hz" else "%.1f Hz".format(hz)

internal fun formatKg(kg: Double): String = if (kg % 1.0 == 0.0) kg.toInt().toString() else kg.toString()
