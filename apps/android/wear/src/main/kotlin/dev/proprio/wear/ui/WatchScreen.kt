package dev.proprio.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.LocalContentColor
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

/** Ambient (low-power) face: black background, dim text, only what is needed to see that a session records. */
@Composable
private fun AmbientScreen(state: UiState) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (state.recording) formatElapsed(state.elapsedS) else "Proprio",
                color = Color.LightGray,
                style = MaterialTheme.typography.titleLarge,
            )
            if (state.recording) Text("recording", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Screen colors. While recording the screen stays on (D-024): pure black background (AMOLED pixels off), readable text. */
data class WatchPalette(val background: Color, val content: Color, val button: Color)

val RECORDING_PALETTE = WatchPalette(Color(0xFF000000), Color(0xFFE0E0E0), Color(0xFF303030))
val IDLE_PALETTE = WatchPalette(Color(0xFF000000), Color(0xFFE0E0E0), Color(0xFF303030))

fun watchPalette(state: UiState): WatchPalette = if (state.recording) RECORDING_PALETTE else IDLE_PALETTE

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
    ambient: Boolean = false,
) {
    if (ambient) {
        AmbientScreen(state)
        return
    }
    val palette = watchPalette(state)
    Box(Modifier.fillMaxSize().background(palette.background)) {
    CompositionLocalProvider(LocalContentColor provides palette.content) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!state.recording) {
            item { Text("Proprio", style = MaterialTheme.typography.titleMedium) }
            state.message?.let { m -> item { Text(m, textAlign = TextAlign.Center) } }
            item { Button(colors = ButtonDefaults.buttonColors(containerColor = palette.button, contentColor = palette.content), onClick = onStartSession) { Text("Start session") } }
            item { Button(colors = ButtonDefaults.buttonColors(containerColor = palette.button, contentColor = palette.content), onClick = onStartCalibration) { Text("Calibration") } }
        } else {
            item {
                Text(formatElapsed(state.elapsedS), style = MaterialTheme.typography.titleLarge)
            }
            item {
                Text(
                    "%.0f Hz".format(minOf(state.accRateHz, state.gyrRateHz)),
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
                item { Button(colors = ButtonDefaults.buttonColors(containerColor = palette.button, contentColor = palette.content), onClick = onEndSession) { Text("Cancel") } }
            } else {
                val open = state.openSegmentId
                if (open == null) {
                    item { Button(colors = ButtonDefaults.buttonColors(containerColor = palette.button, contentColor = palette.content), onClick = onStartSet) { Text("Start set") } }
                    item { Button(colors = ButtonDefaults.buttonColors(containerColor = palette.button, contentColor = palette.content), onClick = onStartOther) { Text("Start other") } }
                } else {
                    val what = if (state.openSegmentKind == MarkKind.OTHER) "other" else "set"
                    item { Text("Segment #$open ($what)") }
                    item { Button(colors = ButtonDefaults.buttonColors(containerColor = palette.button, contentColor = palette.content), onClick = onStopSegment) { Text("Stop segment") } }
                }
                item { Button(colors = ButtonDefaults.buttonColors(containerColor = palette.button, contentColor = palette.content), onClick = onEndSession) { Text("End session") } }
            }
        }
        if (state.pendingTransfers > 0 || state.transferring) {
            item {
                Button(colors = ButtonDefaults.buttonColors(containerColor = palette.button, contentColor = palette.content), onClick = onRetryTransfers) {
                    Text(
                        if (state.transferring) "Sending..." else "${state.pendingTransfers} pending - retry",
                    )
                }
            }
        }
    }
    }
    }
}
