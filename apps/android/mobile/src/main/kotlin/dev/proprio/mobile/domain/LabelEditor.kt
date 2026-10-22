package dev.proprio.mobile.domain

import dev.proprio.protocol.Labels
import dev.proprio.protocol.SegmentKind
import dev.proprio.protocol.SegmentLabel
import dev.proprio.protocol.SegmentMark

/** User-editable fields of one segment. */
data class SegmentEdit(
    val kind: SegmentKind,
    val exercise: String? = null,
    val loadKg: Double? = null,
    val category: String? = null,
    val repsUser: Int? = null,
    val rpe: Int? = null,
    val note: String = "",
)

val EXERCISES = listOf("bench", "squat", "dead", "ohp", "row")
val CATEGORIES = listOf("heavy", "medium")

/**
 * Pure label operations. The watch's marks are never touched: edits only produce a new [Labels], whose
 * `t_start_ns`/`t_end_ns` are overrides (null = use the watch's times, spec section 3.2).
 */
object LabelEditor {
    /** Step of the review screen's start/end nudge buttons. */
    const val NUDGE_NS = 500_000_000L

    /** Smallest segment length a nudge may produce. */
    const val MIN_LENGTH_NS = 500_000_000L

    fun find(labels: Labels, segmentId: Int): SegmentLabel? = labels.segments.firstOrNull { it.segmentId == segmentId }

    /** Replaces the entry for [label]'s segment id (or appends it); entries stay ordered by id. */
    fun put(labels: Labels, label: SegmentLabel): Labels {
        val rest = labels.segments.filter { it.segmentId != label.segmentId }
        return labels.copy(segments = (rest + label).sortedBy { it.segmentId })
    }

    /** Applies [transform] to the entry for [segmentId], creating a bare one of [defaultKind] if missing. */
    fun update(labels: Labels, segmentId: Int, defaultKind: SegmentKind, transform: (SegmentLabel) -> SegmentLabel): Labels {
        val current = find(labels, segmentId) ?: SegmentLabel(segmentId, defaultKind)
        return put(labels, transform(current))
    }

    /** Applies a review-screen edit, keeping any time overrides. Exercise/category/load only apply to sets. */
    fun applyEdit(labels: Labels, segmentId: Int, edit: SegmentEdit): Labels =
        update(labels, segmentId, edit.kind) {
            val isSet = edit.kind == SegmentKind.SET
            it.copy(
                kind = edit.kind,
                exercise = edit.exercise.takeIf { isSet },
                loadKg = edit.loadKg.takeIf { isSet },
                category = edit.category.takeIf { isSet },
                repsUser = edit.repsUser.takeIf { isSet },
                rpe = edit.rpe.takeIf { isSet },
                note = edit.note,
            )
        }

    /**
     * Moves the effective start/end of [mark] by [dStartNs]/[dEndNs]. The result is stored as an override,
     * or as null when it equals the watch's time again. Returns [labels] unchanged when the segment would
     * become shorter than [MIN_LENGTH_NS].
     */
    fun nudge(labels: Labels, mark: SegmentMark, defaultKind: SegmentKind, dStartNs: Long, dEndNs: Long): Labels {
        val cur = find(labels, mark.segmentId)
        val start = (cur?.tStartNs ?: mark.tStartNs) + dStartNs
        val end = (cur?.tEndNs ?: mark.tEndNs) + dEndNs
        if (end - start < MIN_LENGTH_NS) return labels
        return update(labels, mark.segmentId, defaultKind) {
            it.copy(
                tStartNs = start.takeIf { s -> s != mark.tStartNs },
                tEndNs = end.takeIf { e -> e != mark.tEndNs },
            )
        }
    }
}
