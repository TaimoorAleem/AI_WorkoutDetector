package dev.proprio.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.proprio.mobile.data.RecordingItem
import dev.proprio.protocol.SessionKind

@Composable
fun RecordingsScreen(
    items: List<RecordingItem>,
    onOpen: (String) -> Unit,
    onExport: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        Column(modifier.fillMaxSize().padding(16.dp)) {
            Text("No recordings yet", style = MaterialTheme.typography.titleMedium)
            Text("Finish a session on the watch and it will appear here after the transfer.")
        }
        return
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items, key = { it.summary.sessionId }) { item ->
            val s = item.summary
            Card(Modifier.fillMaxWidth().clickable { onOpen(s.sessionId) }.testTag("recording_${s.sessionId}")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "${s.startedAtUtc.take(16).replace('T', ' ')} UTC  -  ${if (s.kind == SessionKind.GYM) "Gym" else "Calibration"}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text("Duration ${formatElapsed(s.durationS)}  -  ${item.setCount} ${if (s.kind == SessionKind.GYM) "sets" else "steps"}")
                    Row {
                        Text(
                            listOf(
                                if (s.complete) "complete" else "incomplete",
                                if (item.labeled) "labeled" else "unlabeled",
                            ).joinToString(", "),
                            Modifier.weight(1f).testTag("status_${s.sessionId}"),
                        )
                        TextButton(onClick = { onExport(s.sessionId) }) { Text("Export") }
                    }
                }
            }
        }
    }
}
