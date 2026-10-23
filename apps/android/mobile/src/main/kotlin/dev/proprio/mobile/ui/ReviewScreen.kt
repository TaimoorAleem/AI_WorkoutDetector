package dev.proprio.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.proprio.mobile.ReviewUiState
import dev.proprio.mobile.domain.CATEGORIES
import dev.proprio.mobile.domain.EXERCISES
import dev.proprio.mobile.domain.LabelEditor
import dev.proprio.mobile.domain.PlotData
import dev.proprio.mobile.domain.SegmentEdit
import dev.proprio.protocol.ResolvedSegment
import dev.proprio.protocol.SegmentKind
import androidx.compose.foundation.gestures.detectTapGestures

private val ACC_COLOR = Color(0xFF1E6FD9)
private val UNCONFIRMED_COLOR = Color(0xFFE65100)
private val GYR_COLOR = Color(0xFFD9822B)

fun overlayColor(kind: SegmentKind?): Color = when (kind) {
    SegmentKind.SET -> Color(0xFF2E9E5B)
    SegmentKind.REST -> Color(0xFF8A8F98)
    SegmentKind.OTHER -> Color(0xFF9B59B6)
    SegmentKind.CALIBRATION -> Color(0xFFE0B000)
    null -> Color(0xFFD64545)
}

/** The segment under time [tNs]: the shortest explicit one, else an implicit rest. */
fun segmentAt(segments: List<ResolvedSegment>, tNs: Long): ResolvedSegment? =
    segments.filter { tNs in it.tStartNs..it.tEndNs }
        .minWithOrNull(compareBy({ it.implicit }, { it.tEndNs - it.tStartNs }))

@Composable
fun ReviewScreen(
    state: ReviewUiState,
    onBack: () -> Unit,
    onSelect: (Int?) -> Unit,
    onEdit: (Int, SegmentEdit) -> Unit,
    onNudge: (segmentId: Int, dStartNs: Long, dEndNs: Long) -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Review", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            Button(onClick = onExport, enabled = !state.loading && state.error == null, modifier = Modifier.testTag("export")) { Text("Export") }
        }
        when {
            state.loading -> Text("Loading...")
            state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error)
            else -> {
                if (!state.complete) Text("Incomplete recording (watch stopped unexpectedly).", color = MaterialTheme.colorScheme.error)
                val plot = state.plot!!
                SessionPlot(plot, state.segments, state.selectedId) { t -> onSelect(segmentAt(state.segments, t)?.segmentId) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("|acc|", color = ACC_COLOR)
                    Text("|gyro|", color = GYR_COLOR)
                    Text("tap the plot or a row to select; orange bar = unconfirmed", style = MaterialTheme.typography.bodySmall)
                }
                state.segments.forEach { seg ->
                    SegmentRow(seg, plot, seg.segmentId == state.selectedId) { onSelect(seg.segmentId) }
                }
                val selected = state.segments.firstOrNull { it.segmentId == state.selectedId }
                if (selected != null) SegmentEditor(selected, plot, onEdit, onNudge)
            }
        }
    }
}

@Composable
private fun SessionPlot(plot: PlotData, segments: List<ResolvedSegment>, selectedId: Int?, onTapTime: (Long) -> Unit) {
    Canvas(
        Modifier.fillMaxWidth().height(180.dp).testTag("plot").pointerInput(plot) {
            detectTapGestures { o -> onTapTime(plot.fractionToTime(o.x / size.width)) }
        },
    ) {
        for (seg in segments) {
            val x0 = plot.timeToFraction(seg.tStartNs).coerceIn(0f, 1f) * size.width
            val x1 = plot.timeToFraction(seg.tEndNs).coerceIn(0f, 1f) * size.width
            val c = overlayColor(seg.kind)
            drawRect(c.copy(alpha = if (seg.implicit) 0.12f else 0.25f), Offset(x0, 0f), androidx.compose.ui.geometry.Size(x1 - x0, size.height))
            if (LabelEditor.isUnconfirmed(seg)) {
                // orange bar along the top edge of the overlay marks an unconfirmed set
                drawRect(UNCONFIRMED_COLOR, Offset(x0, 0f), androidx.compose.ui.geometry.Size(x1 - x0, 8f))
            }
            if (seg.segmentId == selectedId) {
                drawRect(c, Offset(x0, 0f), androidx.compose.ui.geometry.Size(x1 - x0, size.height), style = Stroke(3f))
            }
        }
        drawSeries(plot.acc, ACC_COLOR)
        drawSeries(plot.gyr, GYR_COLOR)
    }
}

private fun DrawScope.drawSeries(values: FloatArray, color: Color) {
    val max = values.filter { !it.isNaN() }.maxOrNull() ?: return
    if (max <= 0f) return
    val path = Path()
    var pen = false
    val n = values.size
    for (i in 0 until n) {
        val v = values[i]
        if (v.isNaN()) { pen = false; continue }
        val x = if (n <= 1) 0f else i.toFloat() / (n - 1) * size.width
        val y = size.height * (1f - v / max * 0.95f)
        if (pen) path.lineTo(x, y) else { path.moveTo(x, y); pen = true }
    }
    drawPath(path, color, style = Stroke(2f))
}

private fun secs(plot: PlotData, tNs: Long) = "%.1f s".format((tNs - plot.tStartNs) / 1e9)

@Composable
private fun SegmentRow(seg: ResolvedSegment, plot: PlotData, selected: Boolean, onClick: () -> Unit) {
    val title = buildString {
        append(seg.kind?.name?.lowercase() ?: "unlabeled")
        if (seg.implicit) append(" (auto)") else append(" #${seg.segmentId}")
        seg.exercise?.let { append("  $it") }
        seg.loadKg?.let { append("  ${formatKg(it)} kg") }
        seg.repsUser?.let { append("  x$it") }
    }
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("segment_${seg.segmentId}"),
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, overlayColor(seg.kind)) else null,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text("${secs(plot, seg.tStartNs)} to ${secs(plot, seg.tEndNs)}", style = MaterialTheme.typography.bodySmall)
            if (seg.note == LabelEditor.DISCARDED_NOTE) {
                Text("discarded", Modifier.testTag("discarded_${seg.segmentId}"), style = MaterialTheme.typography.labelMedium)
            }
            if (LabelEditor.isUnconfirmed(seg)) {
                AssistChip(
                    onClick = onClick,
                    label = { Text("unconfirmed") },
                    colors = AssistChipDefaults.assistChipColors(labelColor = UNCONFIRMED_COLOR),
                    border = AssistChipDefaults.assistChipBorder(true, borderColor = UNCONFIRMED_COLOR),
                    modifier = Modifier.testTag("unconfirmed_${seg.segmentId}"),
                )
            }
        }
    }
}

@Composable
private fun SegmentEditor(
    seg: ResolvedSegment,
    plot: PlotData,
    onEdit: (Int, SegmentEdit) -> Unit,
    onNudge: (Int, Long, Long) -> Unit,
) {
    if (seg.implicit) {
        Text("Automatic rest between sets (trimmed 2 s at each edge). Edit the neighbouring sets to change it.")
        return
    }
    var kind by remember(seg.segmentId) { mutableStateOf(seg.kind ?: SegmentKind.SET) }
    var exercise by remember(seg.segmentId) { mutableStateOf(seg.exercise) }
    var load by remember(seg.segmentId) { mutableStateOf(seg.loadKg?.let { formatKg(it) }.orEmpty()) }
    var category by remember(seg.segmentId) { mutableStateOf(seg.category) }
    var reps by remember(seg.segmentId) { mutableStateOf(seg.repsUser?.toString().orEmpty()) }
    var rpe by remember(seg.segmentId) { mutableStateOf(seg.rpe?.toString().orEmpty()) }
    var note by remember(seg.segmentId) { mutableStateOf(seg.note) }
    val step = LabelEditor.NUDGE_NS

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Segment #${seg.segmentId}: ${secs(plot, seg.tStartNs)} to ${secs(plot, seg.tEndNs)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("editor_title"))
            Text("Start")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onNudge(seg.segmentId, -step, 0) }, modifier = Modifier.testTag("start_minus")) { Text("-0.5 s") }
                OutlinedButton(onClick = { onNudge(seg.segmentId, step, 0) }, modifier = Modifier.testTag("start_plus")) { Text("+0.5 s") }
            }
            Text("End")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onNudge(seg.segmentId, 0, -step) }, modifier = Modifier.testTag("end_minus")) { Text("-0.5 s") }
                OutlinedButton(onClick = { onNudge(seg.segmentId, 0, step) }, modifier = Modifier.testTag("end_plus")) { Text("+0.5 s") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentKind.entries.forEach { k ->
                    FilterChip(kind == k, { kind = k }, { Text(k.name.lowercase()) }, modifier = Modifier.testTag("kind_${k.name.lowercase()}"))
                }
            }
            if (kind == SegmentKind.SET) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EXERCISES.forEach { ex ->
                        FilterChip(exercise == ex, { exercise = ex }, { Text(ex) }, modifier = Modifier.testTag("edit_exercise_$ex"))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CATEGORIES.forEach { c ->
                        FilterChip(category == c, { category = c }, { Text(c) }, modifier = Modifier.testTag("edit_category_$c"))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(load, { load = it }, label = { Text("kg") }, singleLine = true, modifier = Modifier.weight(1f).testTag("edit_load"))
                    OutlinedTextField(reps, { reps = it.filter(Char::isDigit) }, label = { Text("reps") }, singleLine = true, modifier = Modifier.weight(1f).testTag("edit_reps"))
                    OutlinedTextField(rpe, { rpe = it.filter(Char::isDigit) }, label = { Text("RPE") }, singleLine = true, modifier = Modifier.weight(1f).testTag("edit_rpe"))
                }
            }
            OutlinedTextField(note, { note = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth().testTag("edit_note"))
            Button(
                onClick = {
                    onEdit(
                        seg.segmentId,
                        SegmentEdit(
                            kind = kind,
                            exercise = exercise,
                            loadKg = load.replace(',', '.').toDoubleOrNull(),
                            category = category,
                            repsUser = reps.toIntOrNull(),
                            rpe = rpe.toIntOrNull()?.coerceIn(0, 10),
                            note = note,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth().testTag("save_edit"),
            ) { Text("Save labels") }
        }
    }
}
