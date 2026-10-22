package dev.proprio.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.proprio.protocol.MarkKind
import dev.proprio.protocol.SessionKind
import dev.proprio.wear.session.UiState

fun formatElapsed(s: Double): String {
    val t = s.toInt()
    return "%d:%02d".format(t / 60, t % 60)
}

@Composable
fun WatchScreen(
    state: UiState,
    onStartSession: () -> Unit,
    onEndSession: () -> Unit,
    onStartSet: () -> Unit,
    onStartOther: () -> Unit,
    onStopSegment: () -> Unit,
    onStartCalibration: () -> Unit,
    onRetryTransfers: () -> Unit,
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!state.recording) {
            item { Text("Proprio", style = MaterialTheme.typography.titleMedium) }
            state.message?.let { m -> item { Text(m, textAlign = TextAlign.Center) } }
            item { Button(onClick = onStartSession) { Text("Start session") } }
            item { Button(onClick = onStartCalibration) { Text("Calibration") } }
        } else {
            item {
                Text(formatElapsed(state.elapsedS), style = MaterialTheme.typography.titleLarge)
            }
            item {
                Text(
                    "acc %.1f Hz  gyr %.1f Hz".format(state.accRateHz, state.gyrRateHz),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            val cal = state.calibration
            if (state.kind == SessionKind.CALIBRATION) {
                if (cal != null) {
                    item {
                        Text(
                            (if (cal.inTransition) "Next " else "") + "${cal.index}/${cal.count}: ${cal.title}",
                            textAlign = TextAlign.Center,
                        )
                    }
                    item { Text(cal.instruction, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp)) }
                    item { Text("${cal.remainingS} s", style = MaterialTheme.typography.titleMedium) }
                }
                item { Button(onClick = onEndSession) { Text("Cancel") } }
            } else {
                val open = state.openSegmentId
                if (open == null) {
                    item { Button(onClick = onStartSet) { Text("Start set") } }
                    item { Button(onClick = onStartOther) { Text("Start other") } }
                } else {
                    val what = if (state.openSegmentKind == MarkKind.OTHER) "other" else "set"
                    item { Text("Segment #$open ($what)") }
                    item { Button(onClick = onStopSegment) { Text("Stop segment") } }
                }
                item { Button(onClick = onEndSession) { Text("End session") } }
            }
        }
        if (state.pendingTransfers > 0 || state.transferring) {
            item {
                Button(onClick = onRetryTransfers) {
                    Text(
                        if (state.transferring) "Sending..." else "${state.pendingTransfers} pending - retry",
                    )
                }
            }
        }
    }
}
