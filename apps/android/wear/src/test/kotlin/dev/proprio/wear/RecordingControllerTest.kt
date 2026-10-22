package dev.proprio.wear

import dev.proprio.protocol.DataLayer
import dev.proprio.protocol.MarkKind
import dev.proprio.protocol.SegmentEvent
import dev.proprio.protocol.SegmentSource
import dev.proprio.protocol.SegmentStartCommand
import dev.proprio.protocol.SessionAck
import dev.proprio.protocol.SessionKind
import dev.proprio.protocol.SessionReader
import dev.proprio.protocol.SessionStartCommand
import dev.proprio.protocol.WatchStatus
import dev.proprio.wear.sensor.SensorRecorder
import dev.proprio.wear.session.CommandHandler
import dev.proprio.wear.session.RecordingController
import dev.proprio.wear.transfer.AckResult
import dev.proprio.wear.transfer.TransferManager
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingControllerTest {
    private class Harness(val t: TestScope) {
        val root: File = createTempDirectory("ctl").toFile()
        val clock = FakeClock(scheduler = t.testScheduler)
        val source = FakeSensorSource()
        val phone = FakePhone()
        val haptics = FakeHaptics()
        val host = FakeHost()
        val sender = FakeSender()
        val nodes = FakeNodes()
        val transfer = TransferManager(File(root, "sessions"), File(root, "outbox"), sender, nodes)
        var ids = 0
        val logs = mutableListOf<String>()
        val controller = RecordingController(
            recorder = SensorRecorder(source, clock, StandardTestDispatcher(t.testScheduler)),
            clock = clock, transfer = transfer, phone = phone, haptics = haptics, host = host,
            app = testApp, device = testDevice, scope = t.backgroundScope,
            newSessionId = { "sess-${++ids}" }, log = { logs += it },
        )
        val commands = CommandHandler(controller)

        fun status() = phone.paths(DataLayer.PATH_STATUS).map { DataLayer.decode<WatchStatus>(it) }
        fun events() = phone.paths(DataLayer.PATH_SEGMENT_EVENT).map { DataLayer.decode<SegmentEvent>(it) }
    }

    private fun TestScope.harness() = Harness(this)

    private fun Harness.feed(n: Int) {
        for (i in 0 until n) { source.acc(clock.elapsedRealtimeNs() + i); source.gyr(clock.elapsedRealtimeNs() + i) }
    }

    @Test
    fun gymSessionProducesAValidZipWithSegmentsAndMessages() = runTest {
        val h = harness()
        assertTrue(h.controller.startSession(SessionKind.GYM))
        assertEquals(1, h.host.enters)
        advanceTimeBy(1_000)
        h.feed(10)

        assertTrue(h.controller.startSegment(MarkKind.SET, SegmentSource.WATCH))
        assertEquals(1, h.controller.state.value.openSegmentId)
        advanceTimeBy(2_000)
        h.feed(10)
        assertTrue(h.controller.stopSegment(SegmentSource.WATCH))
        assertNull(h.controller.state.value.openSegmentId)
        advanceTimeBy(500)
        assertTrue(h.controller.startSegment(MarkKind.OTHER, SegmentSource.PHONE))
        advanceTimeBy(500)
        assertTrue(h.controller.endSession(SegmentSource.WATCH)) // closes the open "other"
        runCurrent()

        assertEquals(listOf("start", "stop", "start", "stop"), h.haptics.events)
        assertEquals(1, h.host.exits)
        val ev = h.events()
        assertEquals(
            listOf(
                Triple(1, SegmentEvent.Event.STARTED, "watch"),
                Triple(1, SegmentEvent.Event.STOPPED, "watch"),
                Triple(2, SegmentEvent.Event.STARTED, "phone"),
                Triple(2, SegmentEvent.Event.STOPPED, "phone"),
            ),
            ev.map { Triple(it.segmentId, it.event, it.source) },
        )
        assertEquals(MarkKind.SET, ev[0].kind)
        assertEquals("sess-1", ev[0].sessionId)

        val zip = h.transfer.zipFile("sess-1")
        val rec = SessionReader.readZip(zip)
        assertTrue(rec.meta.complete)
        assertEquals(20, rec.acc.size)
        assertEquals(listOf(1, 2), rec.segments.map { it.segmentId })
        assertEquals(listOf(SegmentSource.WATCH, SegmentSource.PHONE), rec.segments.map { it.source })
        assertTrue(rec.segments.all { it.tEndNs > it.tStartNs })
        assertEquals(emptyList<String>(), Schemas.metaErrors(String(dev.proprio.protocol.SessionZip.read(zip).getValue("meta.json"))))
        // the transfer was attempted
        assertEquals(DataLayer.channelPath("sess-1"), h.sender.sends.single().second)
        assertEquals(1, h.transfer.pendingCount.value) // kept until acked
    }

    @Test
    fun startingASegmentWhileOneIsOpenAutoClosesIt() = runTest {
        val h = harness()
        h.controller.startSession(SessionKind.GYM)
        h.controller.startSegment(MarkKind.SET, SegmentSource.WATCH)
        advanceTimeBy(1000)
        h.controller.startSegment(MarkKind.SET, SegmentSource.PHONE)
        runCurrent()
        assertEquals(2, h.controller.state.value.openSegmentId)
        assertEquals(
            listOf(1 to SegmentEvent.Event.STARTED, 1 to SegmentEvent.Event.STOPPED, 2 to SegmentEvent.Event.STARTED),
            h.events().map { it.segmentId to it.event },
        )
        assertTrue(h.logs.any { it.contains("auto-closing") })
        h.controller.endSession()
    }

    @Test
    fun segmentCommandsWithoutASessionAreIgnored() = runTest {
        val h = harness()
        assertFalse(h.controller.startSegment(MarkKind.SET, SegmentSource.WATCH))
        assertFalse(h.controller.stopSegment(SegmentSource.WATCH))
        assertFalse(h.controller.endSession())
        assertTrue(h.events().isEmpty())
    }

    @Test
    fun secondSessionStartIsIgnored() = runTest {
        val h = harness()
        assertTrue(h.controller.startSession(SessionKind.GYM))
        assertFalse(h.controller.startSession(SessionKind.GYM))
        assertEquals(1, h.host.enters)
        h.controller.endSession()
    }

    @Test
    fun foregroundRefusalAbortsTheStart() = runTest {
        val h = harness()
        h.host.allow = false
        assertFalse(h.controller.startSession(SessionKind.GYM))
        assertFalse(h.controller.state.value.recording)
        assertNotNull(h.controller.state.value.message)
        assertEquals(0, h.source.stopCount)
    }

    @Test
    fun statusIsSentOnChangeAndEveryFiveSecondsWhileRecording() = runTest {
        val h = harness()
        h.controller.startSession(SessionKind.GYM)
        runCurrent()
        val afterStart = h.status().size
        assertTrue(afterStart >= 1)
        assertEquals(WatchStatus.State.RECORDING, h.status().last().state)
        assertEquals("sess-1", h.status().last().sessionId)

        advanceTimeBy(4_900); runCurrent()
        assertEquals(afterStart, h.status().size)
        advanceTimeBy(200); runCurrent() // 5.1 s
        assertEquals(afterStart + 1, h.status().size)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(afterStart + 2, h.status().size)
        assertTrue(h.status().last().elapsedS!! >= 10.0)

        h.controller.startSegment(MarkKind.SET, SegmentSource.WATCH)
        runCurrent()
        assertEquals(1, h.status().last().openSegmentId)
        h.controller.endSession()
        runCurrent()
        assertEquals(WatchStatus.State.IDLE, h.status().last().state.let { if (it == WatchStatus.State.TRANSFERRING) WatchStatus.State.IDLE else it })
        assertNull(h.status().last().sessionId)
    }

    @Test
    fun calibrationRunsAllStepsAsAutoSegmentsAndFinishesTheSession() = runTest {
        val h = harness()
        assertTrue(h.controller.startSession(SessionKind.CALIBRATION, SegmentSource.PHONE))
        // manual segment control is disabled during calibration
        assertFalse(h.controller.startSegment(MarkKind.SET, SegmentSource.WATCH))
        assertFalse(h.controller.stopSegment(SegmentSource.WATCH))

        advanceTimeBy(6_000) // lead-in done, step 1 open
        assertEquals("table_flat_face_up", h.controller.state.value.calibration!!.stepId)
        assertEquals(1, h.controller.state.value.openSegmentId)
        advanceTimeBy(275_000 - 6_000 + 2_000)
        runCurrent()

        assertFalse(h.controller.state.value.recording)
        val rec = SessionReader.readZip(h.transfer.zipFile("sess-1"))
        assertEquals(SessionKind.CALIBRATION, rec.meta.kind)
        assertEquals(CAL_IDS, rec.segments.map { it.step })
        assertTrue(rec.segments.all { it.source == SegmentSource.AUTO })
        assertEquals((1..10).toList(), rec.segments.map { it.segmentId })
        // durations in the sample clock match the spec (tick granularity 100 ms)
        val durS = rec.segments.map { (it.tEndNs - it.tStartNs) / 1e9 }
        assertEquals(120.0, durS[0], 0.15)
        durS.subList(1, 7).forEach { assertEquals(10.0, it, 0.15) }
        durS.subList(7, 10).forEach { assertEquals(15.0, it, 0.15) }
        // 5 s unsegmented transitions between steps
        for (i in 0 until 9) {
            assertEquals(5.0, (rec.segments[i + 1].tStartNs - rec.segments[i].tEndNs) / 1e9, 0.25)
        }
        assertTrue(h.haptics.events.count { it == "cue" } == 10)
        assertEquals(1, h.host.exits)
    }

    @Test
    fun cancelledCalibrationStillYieldsReadableSession() = runTest {
        val h = harness()
        h.controller.startSession(SessionKind.CALIBRATION)
        advanceTimeBy(20_000)
        h.controller.endSession()
        runCurrent()
        val rec = SessionReader.readZip(h.transfer.zipFile("sess-1"))
        assertEquals(listOf("table_flat_face_up"), rec.segments.map { it.step })
    }

    // ---- commands from the phone -------------------------------------------------------------

    @Test
    fun everyPhoneCommandPathIsHandledWithSourcePhone() = runTest {
        val h = harness()
        assertTrue(h.commands.handle(DataLayer.PATH_SESSION_START, DataLayer.encode(SessionStartCommand(SessionKind.GYM, "P01"))))
        assertTrue(h.controller.state.value.recording)
        assertEquals(SessionKind.GYM, h.controller.state.value.kind)

        assertTrue(h.commands.handle(DataLayer.PATH_SEGMENT_START, DataLayer.encode(SegmentStartCommand(MarkKind.SET))))
        advanceTimeBy(1_000)
        assertTrue(h.commands.handle(DataLayer.PATH_SEGMENT_STOP, ByteArray(0)))
        assertTrue(h.commands.handle(DataLayer.PATH_SEGMENT_START, DataLayer.encode(SegmentStartCommand(MarkKind.OTHER))))
        advanceTimeBy(1_000)
        assertTrue(h.commands.handle(DataLayer.PATH_SESSION_END, ByteArray(0)))
        runCurrent()
        assertFalse(h.controller.state.value.recording)

        val rec = SessionReader.readZip(h.transfer.zipFile("sess-1"))
        assertEquals(listOf(SegmentSource.PHONE, SegmentSource.PHONE), rec.segments.map { it.source })
        assertEquals(setOf("phone"), h.events().map { it.source }.toSet())
        assertEquals(listOf(MarkKind.SET, MarkKind.SET, MarkKind.OTHER, MarkKind.OTHER), h.events().map { it.kind })
    }

    @Test
    fun phoneCanStartCalibrationAndAckDeletesTheSession() = runTest {
        val h = harness()
        assertTrue(h.commands.handle(DataLayer.PATH_SESSION_START, DataLayer.encode(SessionStartCommand(SessionKind.CALIBRATION))))
        assertEquals(SessionKind.CALIBRATION, h.controller.state.value.kind)
        h.commands.handle(DataLayer.PATH_SESSION_END, ByteArray(0))
        runCurrent()
        val zip = h.transfer.zipFile("sess-1")
        val sha = TransferManager.sha256(zip)

        // wrong hash first: kept, then a resend happens
        val sendsBefore = h.sender.sends.size
        assertTrue(h.commands.handle(DataLayer.PATH_SESSION_ACK, DataLayer.encode(SessionAck("sess-1", "bad", ok = true))))
        assertTrue(zip.exists())
        advanceTimeBy(2_500); runCurrent()
        assertTrue(h.sender.sends.size > sendsBefore)

        assertTrue(h.commands.handle(DataLayer.PATH_SESSION_ACK, DataLayer.encode(SessionAck("sess-1", sha, ok = true))))
        assertFalse(zip.exists())
        assertEquals(0, h.controller.state.value.pendingTransfers)
    }

    @Test
    fun malformedOrUnknownCommandsAreRejectedWithoutSideEffects() = runTest {
        val h = harness()
        assertFalse(h.commands.handle(DataLayer.PATH_SEGMENT_START, "not json".toByteArray()))
        assertFalse(h.commands.handle(DataLayer.PATH_SESSION_ACK, ByteArray(0)))
        assertFalse(h.commands.handle("/proprio/v1/unknown", ByteArray(0)))
        assertFalse(h.controller.state.value.recording)
        // a bare session/start with no payload defaults to a gym session
        assertTrue(h.commands.handle(DataLayer.PATH_SESSION_START, ByteArray(0)))
        assertEquals(SessionKind.GYM, h.controller.state.value.kind)
        h.controller.endSession()
    }

    @Test
    fun appStartRecoversInterruptedSessionAndRetriesTransfers() = runTest {
        val h = harness()
        h.controller.startSession(SessionKind.GYM)
        h.feed(5)
        h.controller.stopSegment(SegmentSource.WATCH)
        // simulate a process kill: a brand-new controller over the same directories
        val h2 = harness()
        val sessions = File(h.root, "sessions")
        File(sessions, "sess-1").copyRecursively(File(h2.root, "sessions/sess-1"))
        h2.controller.resumeTransfers()
        advanceTimeBy(1); runCurrent()
        assertFalse(SessionReader.readZip(h2.transfer.zipFile("sess-1")).meta.complete)
        assertEquals(1, h2.sender.sends.size)
        assertEquals(AckResult.DELETED, h2.transfer.onAck(SessionAck("sess-1", TransferManager.sha256(h2.transfer.zipFile("sess-1")), true)))
        h.controller.endSession()
    }

    companion object {
        val CAL_IDS = listOf(
            "table_flat_face_up", "face_up", "face_down", "buttons_up", "buttons_down",
            "twelve_up", "twelve_down", "rot_x", "rot_y", "rot_z",
        )
    }

    @Test
    fun watchTapsWithinOneSecondOfTheLastAcceptedTapAreIgnored() = runTest {
        val h = harness()
        assertTrue(h.controller.startSession(SessionKind.GYM))
        advanceTimeBy(1_000)
        assertTrue(h.controller.startSegment(MarkKind.SET, SegmentSource.WATCH))
        advanceTimeBy(700)
        assertFalse(h.controller.stopSegment(SegmentSource.WATCH)) // 0.7 s: accidental double press
        assertEquals(1, h.controller.state.value.openSegmentId)
        advanceTimeBy(299)
        assertFalse(h.controller.stopSegment(SegmentSource.WATCH)) // 0.999 s after the accepted start; ignored taps don't extend the window
        advanceTimeBy(1)
        assertTrue(h.controller.stopSegment(SegmentSource.WATCH)) // exactly 1.0 s: accepted
        assertNull(h.controller.state.value.openSegmentId)
        runCurrent()
        assertEquals(listOf(SegmentEvent.Event.STARTED, SegmentEvent.Event.STOPPED), h.events().map { it.event })
    }

    @Test
    fun phoneCommandsAreNotDebounced() = runTest {
        val h = harness()
        assertTrue(h.controller.startSession(SessionKind.GYM))
        assertTrue(h.controller.startSegment(MarkKind.SET, SegmentSource.WATCH))
        advanceTimeBy(100)
        assertTrue(h.controller.stopSegment(SegmentSource.PHONE))
        advanceTimeBy(100)
        assertTrue(h.controller.startSegment(MarkKind.OTHER, SegmentSource.PHONE))
        advanceTimeBy(100)
        assertTrue(h.controller.stopSegment(SegmentSource.PHONE))
    }

    @Test
    fun startRequestIntentStartsASessionWithPhoneSource() = runTest {
        val h = harness()
        val handler = dev.proprio.wear.session.StartRequestHandler(h.controller)
        assertFalse(handler.handle("https://example.com/x"))
        assertFalse(h.controller.state.value.recording)
        assertTrue(handler.handle("proprio://session/start?kind=CALIBRATION&participant=P01"))
        assertTrue(h.controller.state.value.recording)
        assertEquals(SessionKind.CALIBRATION, h.controller.state.value.kind)
        assertEquals(1, h.host.enters)
        assertFalse(handler.handle("proprio://session/start?kind=GYM")) // already running: ignored
    }

    @Test
    fun startRequestParsing() {
        val r = dev.proprio.wear.session.StartRequest.parse("proprio://session/start?kind=gym&participant=P%2F01")!!
        assertEquals(SessionKind.GYM, r.kind)
        assertEquals("P/01", r.participantId)
        assertEquals(SessionKind.GYM, dev.proprio.wear.session.StartRequest.parse("proprio://session/start")!!.kind)
        assertNull(dev.proprio.wear.session.StartRequest.parse("proprio://session/other"))
        assertNull(dev.proprio.wear.session.StartRequest.parse(null))
    }

    @Test
    fun recordingStateSelectsTheBlackPalette() {
        val rec = dev.proprio.wear.ui.watchPalette(dev.proprio.wear.session.UiState(recording = true))
        assertEquals(androidx.compose.ui.graphics.Color(0xFF000000), rec.background)
        // Readable text while recording (dim text at minimum brightness was barely legible on the device)
        assertTrue(rec.content.red > 0.8f && rec.content.green > 0.8f && rec.content.blue > 0.8f)
        val idle = dev.proprio.wear.ui.watchPalette(dev.proprio.wear.session.UiState())
        assertEquals(androidx.compose.ui.graphics.Color(0xFF000000), idle.background)
    }
}
