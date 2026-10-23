package dev.proprio.protocol

/**
 * A segment after label overrides and implicit-rest computation.
 *
 * [kind] is null for a watch mark that has no label entry (watch-only export of a gym session).
 * Implicit rest segments have [implicit] = true, a negative [segmentId] (-1, -2, ... in time order)
 * and no [source].
 */
data class ResolvedSegment(
    val segmentId: Int,
    val kind: SegmentKind?,
    val tStartNs: Long,
    val tEndNs: Long,
    val source: SegmentSource?,
    val step: String?,
    val exercise: String? = null,
    val loadKg: Double? = null,
    val category: String? = null,
    val repsUser: Int? = null,
    val rpe: Int? = null,
    val note: String = "",
    val implicit: Boolean = false,
)

object SegmentResolver {
    /** Implicit rest is trimmed this much at each edge to avoid transition motion (spec 3.2). */
    const val REST_TRIM_NS = 2_000_000_000L

    /**
     * Applies overrides from [labels] to the watch [marks] and adds implicit rest.
     *
     * Rest: every gap between two consecutive `set` segments (ordered by effective start) that is not
     * covered by another segment becomes a `rest` segment, trimmed [REST_TRIM_NS] at each edge. A gap
     * partly covered by e.g. an `other` segment yields one rest piece per uncovered part. Pieces whose
     * trimmed length is not positive are dropped. Calibration sessions with no labels default to kind
     * `calibration`.
     */
    fun resolve(sessionKind: SessionKind, marks: List<SegmentMark>, labels: Labels?): List<ResolvedSegment> {
        val byId = labels?.segments?.associateBy { it.segmentId }.orEmpty()
        val explicit = marks.map { m ->
            val l = byId[m.segmentId]
            ResolvedSegment(
                segmentId = m.segmentId,
                kind = l?.kind ?: if (sessionKind == SessionKind.CALIBRATION) SegmentKind.CALIBRATION else null,
                tStartNs = l?.tStartNs ?: m.tStartNs,
                tEndNs = l?.tEndNs ?: m.tEndNs,
                source = m.source,
                step = m.step,
                exercise = l?.exercise,
                loadKg = l?.loadKg,
                category = l?.category,
                repsUser = l?.repsUser,
                rpe = l?.rpe,
                note = l?.note.orEmpty(),
            )
        }.sortedWith(compareBy({ it.tStartNs }, { it.segmentId }))

        val sets = explicit.filter { it.kind == SegmentKind.SET }
        val rests = ArrayList<ResolvedSegment>()
        for (i in 0 until sets.size - 1) {
            val a = sets[i]
            val b = sets[i + 1]
            if (b.tStartNs <= a.tEndNs) continue
            var pieces = listOf(a.tEndNs to b.tStartNs)
            for (o in explicit) {
                if (o === a || o === b || o.tEndNs <= o.tStartNs) continue
                pieces = pieces.flatMap { subtract(it, o.tStartNs to o.tEndNs) }
            }
            for ((s, e) in pieces) {
                val ts = s + REST_TRIM_NS
                val te = e - REST_TRIM_NS
                if (te > ts) {
                    rests += ResolvedSegment(
                        segmentId = 0, kind = SegmentKind.REST, tStartNs = ts, tEndNs = te,
                        source = null, step = null, implicit = true,
                    )
                }
            }
        }
        val numbered = rests.sortedBy { it.tStartNs }.mapIndexed { i, r -> r.copy(segmentId = -(i + 1)) }
        return (explicit + numbered).sortedWith(compareBy({ it.tStartNs }, { it.segmentId }))
    }

    /** [piece] minus [cut] (half-open intervals); zero, one or two remaining intervals. */
    private fun subtract(piece: Pair<Long, Long>, cut: Pair<Long, Long>): List<Pair<Long, Long>> {
        val (s, e) = piece
        val (cs, ce) = cut
        if (ce <= s || cs >= e) return listOf(piece)
        val out = ArrayList<Pair<Long, Long>>(2)
        if (cs > s) out += s to cs
        if (ce < e) out += ce to e
        return out
    }
}
