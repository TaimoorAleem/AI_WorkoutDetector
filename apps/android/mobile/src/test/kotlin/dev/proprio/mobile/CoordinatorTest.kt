package dev.proprio.mobile

import dev.proprio.mobile.TestSupport.SID
import dev.proprio.mobile.data.FakeWatchLink
import dev.proprio.mobile.data.LabelHeader
import dev.proprio.mobile.data.MemorySettingsStore
import dev.proprio.mobile.data.SessionImporter
import dev.proprio.mobile.data.SessionStore
import dev.proprio.mobile.data.UserSettings
import dev.proprio.mobile.domain.LabelEditor
import dev.proprio.protocol.Consent
import dev.proprio.protocol.FILE_LABELS
import dev.proprio.protocol.SegmentEvent
import dev.proprio.protocol.SegmentKind
import dev.proprio.protocol.SessionKind
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Labeling flow against [FakeWatchLink] with a real on-disk [SessionStore]. */
class CoordinatorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private lateinit var link: FakeWatchLink
    private lateinit var store: SessionStore
    private lateinit var coordinator: SessionCoordinator

    @Before fun setUp() {
        link = FakeWatchLink { SID }
        store = SessionStore(File(tmp.root, "sessions"), header = { LabelHeader("P01", Consent(true, "2026-01-01T00:00:00Z")) })
        coordinator = SessionCoordinator(
            scope, link, store, SessionImporter(store),
            MemorySettingsStore(UserSettings("P01", true, "2026-01-01T00:00:00Z", true)),
            io = Dispatchers.Unconfined,
        )
    }

    @After fun tearDown() = scope.cancel()

    @Test
    fun startSetStopRepsLabelsTheWatchSegmentId() {
        coordinator.startSession(SessionKind.GYM)
        coordinator.setConfig { it.copy(exercise = "squat", loadKg = 100.0, category = "medium") }
        coordinator.startSet()
        assertEquals(1, coordinator.state.value.open?.segmentId)
        // label exists as soon as the watch confirmed the start, under the watch's id
        val started = LabelEditor.find(store.readLabels(SID)!!, 1)!!
        assertEquals(SegmentKind.SET, started.kind)
        assertEquals("squat", started.exercise)
        assertNull(started.repsUser)

        coordinator.stopSegment()
        val prompt = coordinator.state.value.repPrompt!!
        assertEquals(1, prompt.segmentId)
        assertNull(coordinator.state.value.open)

        coordinator.confirmReps(5, 8, "felt easy")
        assertNull(coordinator.state.value.repPrompt)
        val l = LabelEditor.find(store.readLabels(SID)!!, 1)!!
        assertEquals(5, l.repsUser)
        assertEquals(8, l.rpe)
        assertEquals("felt easy", l.note)
        assertEquals(listOf("session/start:GYM", "segment/start:SET", "segment/stop"), link.commands)
    }

    @Test
    fun configChangedAfterPressDoesNotLeakIntoTheLabel() {
        coordinator.startSession(SessionKind.GYM)
        coordinator.setConfig { it.copy(exercise = "dead") }
        coordinator.startSet()
        coordinator.setConfig { it.copy(exercise = "row") } // user fiddles during the set
        assertEquals("dead", LabelEditor.find(store.readLabels(SID)!!, 1)!!.exercise)
    }

    @Test
    fun otherSegmentIsLabeledOtherAndHasNoRepPrompt() {
        coordinator.startSession(SessionKind.GYM)
        coordinator.startOther()
        coordinator.stopSegment()
        assertEquals(SegmentKind.OTHER, LabelEditor.find(store.readLabels(SID)!!, 1)!!.kind)
        assertNull(coordinator.state.value.repPrompt)
    }

    @Test
    fun watchTapSegmentsAreLabeledFromTheEventToo() = runBlocking {
        link.emitEvent(SegmentEvent(SID, 7, SegmentEvent.Event.STARTED, null, "watch"))
        assertEquals(SegmentKind.SET, LabelEditor.find(store.readLabels(SID)!!, 7)!!.kind)
        link.emitEvent(SegmentEvent(SID, 7, SegmentEvent.Event.STOPPED, null, "watch"))
        assertEquals(7, coordinator.state.value.repPrompt!!.segmentId)
    }

    @Test
    fun watchStartedSetAsksToConfirmTheExercise_phoneStartedDoesNot() = runBlocking {
        coordinator.setConfig { it.copy(exercise = "ohp", loadKg = 40.0, category = "medium") }
        link.emitEvent(SegmentEvent(SID, 3, SegmentEvent.Event.STARTED, null, "watch"))
        link.emitEvent(SegmentEvent(SID, 3, SegmentEvent.Event.STOPPED, null, "watch"))
        val p = coordinator.state.value.repPrompt!!
        assertTrue(p.fromWatch)
        assertEquals("ohp", p.exercise)
        assertEquals(40.0, p.loadKg!!, 0.0)
        assertEquals("medium", p.category)

        // the user corrects the guess while confirming
        coordinator.confirmReps(8, null, "", SetConfig("row", 55.0, "heavy"))
        val l = LabelEditor.find(store.readLabels(SID)!!, 3)!!
        assertEquals("row", l.exercise)
        assertEquals(55.0, l.loadKg!!, 0.0)
        assertEquals("heavy", l.category)
        assertEquals(8, l.repsUser)

        link.emitEvent(SegmentEvent(SID, 4, SegmentEvent.Event.STARTED, null, "phone"))
        link.emitEvent(SegmentEvent(SID, 4, SegmentEvent.Event.STOPPED, null, "phone"))
        assertFalse(coordinator.state.value.repPrompt!!.fromWatch)
    }

    @Test
    fun calibrationAutoSegmentsAreNotLabeled() = runBlocking {
        link.emitEvent(SegmentEvent(SID, 1, SegmentEvent.Event.STARTED, null, "auto"))
        assertNull(store.readLabels(SID))
    }

    @Test
    fun labelsBeforeZipArrive_thenZipArrives_labelsJsonKeptAndValid() = runBlocking {
        coordinator.startSession(SessionKind.GYM)
        coordinator.startSet()
        coordinator.stopSegment()
        coordinator.confirmReps(6, null, "")
        assertFalse(store.hasZip(SID))

        val zip = TestSupport.buildZip(tmp.newFolder())
        link.emitZip(SID, zip)

        assertTrue(store.hasZip(SID))
        val ack = link.acks.single()
        assertTrue(ack.ok)
        val json = File(store.dir(SID), FILE_LABELS).readText()
        assertEquals(emptyList<String>(), TestSupport.labelErrors(json))
        assertEquals(6, store.load(SID).resolvedSegments().first { it.segmentId == 1 }.repsUser)
    }

    @Test
    fun zipBeforeLabels_labelsStillBindBySegmentId() = runBlocking {
        link.emitZip(SID, TestSupport.buildZip(tmp.newFolder()))
        assertTrue(link.acks.single().ok)
        // phone was offline during the set: events arrive afterwards
        link.emitEvent(SegmentEvent(SID, 1, SegmentEvent.Event.STARTED, dev.proprio.protocol.MarkKind.SET, "watch"))
        link.emitEvent(SegmentEvent(SID, 1, SegmentEvent.Event.STOPPED, dev.proprio.protocol.MarkKind.SET, "watch"))
        coordinator.confirmReps(4, null, "")
        assertEquals(4, store.load(SID).resolvedSegments().first { it.segmentId == 1 }.repsUser)
    }

    @Test
    fun corruptZipIsNackedAndUserIsTold() = runBlocking {
        val bad = File(tmp.newFolder(), "bad.zip").apply { writeText("not a zip") }
        link.emitZip(SID, bad)
        assertFalse(link.acks.single().ok)
        assertNotNull(coordinator.state.value.message)
        assertFalse(store.hasZip(SID))
    }

    @Test
    fun commandsFailGracefullyWhenWatchIsAway() {
        link.setConnected(false)
        coordinator.startSession(SessionKind.GYM)
        assertNotNull(coordinator.state.value.message)
        assertFalse(coordinator.state.value.connected)
    }
}
