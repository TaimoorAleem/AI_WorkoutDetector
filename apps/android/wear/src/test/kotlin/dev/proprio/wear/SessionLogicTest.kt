package dev.proprio.wear

import dev.proprio.protocol.MarkKind
import dev.proprio.protocol.SegmentMark
import dev.proprio.protocol.SegmentSource
import dev.proprio.wear.session.CalibrationEvent
import dev.proprio.wear.session.CalibrationSequencer
import dev.proprio.wear.session.SegmentTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentTrackerTest {
    private var now = 0L
    private val marks = mutableListOf<SegmentMark>()
    private val logs = mutableListOf<String>()
    private val tracker = SegmentTracker({ now }, { marks += it }, { logs += it })

    @Test
    fun idsIncreaseAndMarksAreWrittenOnClose() {
        now = 100
        val a = tracker.start(MarkKind.SET, SegmentSource.WATCH).opened
        assertEquals(1, a.id)
        assertTrue(marks.isEmpty())
        now = 250
        val closed = tracker.stop()!!
        assertEquals(SegmentMark(1, 100, 250, SegmentSource.WATCH, null), closed.mark)
        assertEquals(listOf(closed.mark), marks)
        now = 300
        assertEquals(2, tracker.start(MarkKind.OTHER, SegmentSource.PHONE).opened.id)
        assertNotNull(tracker.open)
    }

    @Test
    fun startWhileOpenAutoClosesAndLogs() {
        now = 10
        tracker.start(MarkKind.SET, SegmentSource.WATCH)
        now = 50
        val r = tracker.start(MarkKind.SET, SegmentSource.PHONE)
        assertEquals(1, r.autoClosed!!.open.id)
        assertEquals(SegmentMark(1, 10, 50, SegmentSource.WATCH, null), marks.single())
        assertEquals(2, r.opened.id)
        assertEquals(50, r.opened.tStartNs)
        assertEquals(1, logs.size)
        assertTrue(logs[0].contains("auto-closing"))
    }

    @Test
    fun stopWithNothingOpenIsNoOp() {
        assertNull(tracker.stop())
        assertTrue(marks.isEmpty())
    }

    @Test
    fun stepIsCarriedIntoTheMark() {
        tracker.start(null, SegmentSource.AUTO, "rot_x")
        now = 5
        assertEquals("rot_x", tracker.stop()!!.mark.step)
    }
}

class CalibrationSequencerTest {
    private var ms = 0L

    @Test
    fun stepsAndDurationsMatchTheSpec() {
        val steps = CalibrationSequencer.DEFAULT_STEPS
        assertEquals(
            listOf(
                "table_flat_face_up", "face_up", "face_down", "buttons_up", "buttons_down",
                "twelve_up", "twelve_down", "rot_x", "rot_y", "rot_z",
            ),
            steps.map { it.id },
        )
        assertEquals(120_000L, steps[0].durationMs)
        assertEquals(List(6) { 10_000L }, steps.subList(1, 7).map { it.durationMs })
        assertEquals(List(3) { 15_000L }, steps.subList(7, 10).map { it.durationMs }) // 3 rotations x ~5 s
        // lead-in + 10 steps with a 5 s transition before each = 10*5 + 120 + 60 + 45
        assertEquals(275_000L, CalibrationSequencer({ ms }).totalMs)
    }

    @Test
    fun eventsFireAtTheExactBoundaries() {
        val seq = CalibrationSequencer({ ms })
        seq.start()
        val first = seq.steps[0]
        val second = seq.steps[1]

        assertEquals(listOf(CalibrationEvent.TransitionStarted(first, 5_000)), seq.poll())
        ms = 4_999
        assertEquals(emptyList<CalibrationEvent>(), seq.poll())
        ms = 5_000
        assertEquals(listOf<CalibrationEvent>(CalibrationEvent.StepStarted(first)), seq.poll())
        ms = 124_999
        assertEquals(emptyList<CalibrationEvent>(), seq.poll())
        ms = 125_000 // 5 + 120 s: step ends and, unsegmented, the 5 s transition begins
        assertEquals(
            listOf(CalibrationEvent.StepEnded(first), CalibrationEvent.TransitionStarted(second, 5_000)),
            seq.poll(),
        )
        ms = 130_000
        assertEquals(listOf<CalibrationEvent>(CalibrationEvent.StepStarted(second)), seq.poll())
        ms = 140_000
        assertEquals(CalibrationEvent.StepEnded(second), seq.poll().first())
    }

    @Test
    fun fullRunProducesEveryStepOnceInOrderThenFinishes() {
        val seq = CalibrationSequencer({ ms })
        seq.start()
        val started = mutableListOf<String>()
        val durations = mutableMapOf<String, Long>()
        var startedAt = 0L
        var finished = false
        while (ms <= seq.totalMs + 1000 && !finished) {
            for (e in seq.poll()) when (e) {
                is CalibrationEvent.StepStarted -> { started += e.step.id; startedAt = ms }
                is CalibrationEvent.StepEnded -> durations[e.step.id] = ms - startedAt
                CalibrationEvent.Finished -> finished = true
                else -> Unit
            }
            ms += 100
        }
        assertTrue(finished)
        assertEquals(CalibrationSequencer.DEFAULT_STEPS.map { it.id }, started)
        assertEquals(CalibrationSequencer.DEFAULT_STEPS.associate { it.id to it.durationMs }, durations)
        assertTrue(seq.isFinished)
        assertEquals(emptyList<CalibrationEvent>(), seq.poll())
    }

    @Test
    fun lateSingleCallDeliversAllMissedEventsInOrder() {
        val seq = CalibrationSequencer({ ms })
        seq.start()
        ms = seq.totalMs
        val events = seq.poll()
        assertEquals(CalibrationEvent.Finished, events.last())
        assertEquals(10, events.count { it is CalibrationEvent.StepStarted })
    }

    @Test
    fun progressReportsTransitionAndStepCountdown() {
        val seq = CalibrationSequencer({ ms })
        seq.start()
        ms = 2_000
        var p = seq.progress()!!
        assertTrue(p.inTransition)
        assertEquals(3_000, p.remainingMs)
        ms = 10_000
        p = seq.progress()!!
        assertEquals(false, p.inTransition)
        assertEquals("table_flat_face_up", p.step.id)
        assertEquals(115_000, p.remainingMs)
        assertEquals(10, p.stepCount)
    }
}
