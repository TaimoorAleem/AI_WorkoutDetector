package dev.proprio.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SegmentResolverTest {
    private fun s(x: Double) = (x * 1e9).toLong()

    private fun mark(id: Int, a: Double, b: Double, step: String? = null) =
        SegmentMark(id, s(a), s(b), SegmentSource.WATCH, step)

    private fun label(id: Int, kind: SegmentKind, start: Double? = null, end: Double? = null) =
        SegmentLabel(id, kind, tStartNs = start?.let(::s), tEndNs = end?.let(::s))

    private fun labels(vararg l: SegmentLabel) =
        Labels(sessionId = "x", participantId = "P", consent = Consent(true, "t"), segments = l.toList())

    @Test
    fun implicitRestBetweenSetsIsTrimmedTwoSecondsPerEdge() {
        val r = SegmentResolver.resolve(
            SessionKind.GYM,
            listOf(mark(1, 0.0, 10.0), mark(2, 20.0, 30.0)),
            labels(label(1, SegmentKind.SET), label(2, SegmentKind.SET)),
        )
        assertEquals(listOf(1, -1, 2), r.map { it.segmentId })
        val rest = r[1]
        assertEquals(SegmentKind.REST, rest.kind)
        assertEquals(s(12.0) to s(18.0), rest.tStartNs to rest.tEndNs)
        assertEquals(true, rest.implicit)
        assertNull(rest.source)
    }

    @Test
    fun gapShorterThanTwiceTheTrimProducesNoRest() {
        val r = SegmentResolver.resolve(
            SessionKind.GYM,
            listOf(mark(1, 0.0, 10.0), mark(2, 13.0, 20.0)),
            labels(label(1, SegmentKind.SET), label(2, SegmentKind.SET)),
        )
        assertEquals(listOf(1, 2), r.map { it.segmentId })
    }

    @Test
    fun otherSegmentSplitsTheGapIntoTwoRests() {
        val r = SegmentResolver.resolve(
            SessionKind.GYM,
            listOf(mark(1, 0.0, 10.0), mark(2, 20.0, 30.0), mark(3, 50.0, 60.0), mark(4, 40.0, 45.0)),
            labels(
                label(1, SegmentKind.SET), label(2, SegmentKind.SET), label(3, SegmentKind.SET),
                label(4, SegmentKind.OTHER),
            ),
        )
        val rests = r.filter { it.implicit }.map { (it.tStartNs / 1e9) to (it.tEndNs / 1e9) }
        // 10-20 -> 12-18; 30-50 minus other 40-45 -> 30-40, 45-50 -> 32-38, 47-48.
        assertEquals(listOf(12.0 to 18.0, 32.0 to 38.0, 47.0 to 48.0), rests)
        assertEquals(listOf(-1, -2, -3), r.filter { it.implicit }.map { it.segmentId })
    }

    @Test
    fun overridesMoveBoundariesAndRestFollows() {
        val r = SegmentResolver.resolve(
            SessionKind.GYM,
            listOf(mark(1, 0.0, 10.0), mark(2, 20.0, 30.0)),
            labels(label(1, SegmentKind.SET), label(2, SegmentKind.SET, start = 25.0)),
        )
        val set2 = r.first { it.segmentId == 2 }
        assertEquals(s(25.0), set2.tStartNs)
        assertEquals(s(30.0), set2.tEndNs) // null end override keeps the watch value
        val rest = r.first { it.implicit }
        assertEquals(s(12.0) to s(23.0), rest.tStartNs to rest.tEndNs)
    }

    @Test
    fun calibrationSegmentsDefaultToCalibrationKindWithoutLabels() {
        val r = SegmentResolver.resolve(SessionKind.CALIBRATION, listOf(mark(1, 0.0, 5.0, "rot_x")), null)
        assertEquals(SegmentKind.CALIBRATION, r.single().kind)
        assertEquals("rot_x", r.single().step)
        val g = SegmentResolver.resolve(SessionKind.GYM, listOf(mark(1, 0.0, 5.0)), null)
        assertNull(g.single().kind)
    }
}
