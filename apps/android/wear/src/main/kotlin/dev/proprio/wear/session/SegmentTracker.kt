package dev.proprio.wear.session

import dev.proprio.protocol.MarkKind
import dev.proprio.protocol.SegmentMark
import dev.proprio.protocol.SegmentSource

/** A segment that is currently open (not yet in `segments.csv`; marks are written on close). */
data class OpenSegment(
    val id: Int,
    /** Null for automatic calibration segments. */
    val kind: MarkKind?,
    val source: SegmentSource,
    val step: String?,
    val tStartNs: Long,
)

data class ClosedSegment(val open: OpenSegment, val mark: SegmentMark)

/** What a [SegmentTracker.start] call did: an auto-closed predecessor (if any) and the new segment. */
data class StartResult(val autoClosed: ClosedSegment?, val opened: OpenSegment)

/**
 * Segment state machine for one session: none open -> open -> closed, ids 1, 2, 3, ... strictly
 * increasing. Starting while one is open closes the open one first (logged, reported to the caller).
 * Timestamps come from [nowNs], the sensor clock (elapsedRealtimeNanos).
 */
class SegmentTracker(
    private val nowNs: () -> Long,
    private val writeMark: (SegmentMark) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private var nextId = 1
    var open: OpenSegment? = null
        private set

    fun start(kind: MarkKind?, source: SegmentSource, step: String? = null): StartResult {
        val closed = open?.let { prev ->
            log("segment ${prev.id} still open when starting a new one; auto-closing it")
            close()
        }
        val seg = OpenSegment(nextId++, kind, source, step, nowNs())
        open = seg
        return StartResult(closed, seg)
    }

    /** Closes the open segment; returns null (and does nothing) when none is open. */
    fun stop(): ClosedSegment? = open?.let { close() }

    private fun close(): ClosedSegment {
        val seg = checkNotNull(open)
        val mark = SegmentMark(seg.id, seg.tStartNs, nowNs(), seg.source, seg.step)
        writeMark(mark)
        open = null
        return ClosedSegment(seg, mark)
    }
}
