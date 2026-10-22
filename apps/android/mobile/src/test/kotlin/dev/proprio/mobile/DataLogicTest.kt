package dev.proprio.mobile

import dev.proprio.mobile.TestSupport.SID
import dev.proprio.mobile.TestSupport.sec
import dev.proprio.mobile.data.ExportBuilder
import dev.proprio.mobile.data.FileSettingsStore
import dev.proprio.mobile.data.LabelHeader
import dev.proprio.mobile.data.SessionImporter
import dev.proprio.mobile.data.SessionStore
import dev.proprio.mobile.domain.LabelEditor
import dev.proprio.mobile.domain.PlotData
import dev.proprio.mobile.domain.SegmentEdit
import dev.proprio.protocol.Consent
import dev.proprio.protocol.FILE_LABELS
import dev.proprio.protocol.Labels
import dev.proprio.protocol.SegmentKind
import dev.proprio.protocol.SegmentLabel
import dev.proprio.protocol.SegmentResolver
import dev.proprio.protocol.SessionReader
import dev.proprio.protocol.SessionZip
import dev.proprio.protocol.toJson
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataLogicTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = SessionStore(File(tmp.root, "sessions"), header = { LabelHeader("P01", Consent(true, "2026-01-01T00:00:00Z")) })

    private fun labels(vararg segs: SegmentLabel) =
        Labels(sessionId = SID, participantId = "P01", consent = Consent(true, "2026-01-01T00:00:00Z"), segments = segs.toList())

    // ---- ack logic ----

    @Test
    fun importComputesSha256AndAcksOk() {
        val store = store()
        val zip = TestSupport.buildZip(tmp.newFolder())
        val expected = MessageDigest.getInstance("SHA-256").digest(zip.readBytes()).joinToString("") { "%02x".format(it) }
        val ack = SessionImporter(store).import(SID, zip)
        assertTrue(ack.ok)
        assertEquals(SID, ack.sessionId)
        assertEquals(expected, ack.sha256)
        assertTrue(store.hasZip(SID))
        assertNotNull(store.readLabels(SID)) // labels.json exists next to the zip
        assertEquals(1, store.list().size)
    }

    @Test
    fun corruptZipIsNackedAndNotStored() {
        val store = store()
        val bad = File(tmp.newFolder(), "bad.zip").apply { writeBytes(ByteArray(500) { (it * 7).toByte() }) }
        val expected = MessageDigest.getInstance("SHA-256").digest(bad.readBytes()).joinToString("") { "%02x".format(it) }
        val ack = SessionImporter(store).import(SID, bad)
        assertFalse(ack.ok)
        assertEquals(expected, ack.sha256)
        assertFalse(store.hasZip(SID))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun zipForAnotherSessionIsNacked() {
        val store = store()
        val zip = TestSupport.buildZip(tmp.newFolder())
        val ack = SessionImporter(store).import("99999999-2222-4333-8444-555555555555", zip)
        assertFalse(ack.ok)
    }

    @Test
    fun incompleteSessionIsStoredAndListedAsIncomplete() {
        val store = store()
        val zip = TestSupport.buildZip(tmp.newFolder(), complete = false)
        assertTrue(SessionImporter(store).import(SID, zip).ok)
        val item = store.list().single()
        assertFalse(item.summary.complete)
        assertFalse(item.labeled)
        assertEquals(60.0, item.summary.durationS, 0.1)
    }

    // ---- labels.json is written before / after the zip ----

    @Test
    fun labelsWrittenBeforeZipSurviveImport() {
        val store = store()
        store.updateLabels(SID) { LabelEditor.put(it, SegmentLabel(1, SegmentKind.SET, "bench", 60.0, "heavy", 5)) }
        assertFalse(store.hasZip(SID))
        SessionImporter(store).import(SID, TestSupport.buildZip(tmp.newFolder()))
        val l = store.readLabels(SID)!!
        assertEquals(5, LabelEditor.find(l, 1)!!.repsUser)
        assertEquals(5, store.load(SID).resolvedSegments().first { it.segmentId == 1 }.repsUser)
    }

    // ---- review overrides ----

    @Test
    fun nudgeStoresOverridesAndNullWhenBackToWatchTime() {
        val mark = TestSupport.defaultMarks[0]
        val l0 = labels(SegmentLabel(1, SegmentKind.SET, "bench"))
        val l1 = LabelEditor.nudge(l0, mark, SegmentKind.SET, +LabelEditor.NUDGE_NS, 0)
        assertEquals(mark.tStartNs + 500_000_000L, LabelEditor.find(l1, 1)!!.tStartNs)
        assertNull(LabelEditor.find(l1, 1)!!.tEndNs)
        val l2 = LabelEditor.nudge(l1, mark, SegmentKind.SET, 0, -LabelEditor.NUDGE_NS)
        assertEquals(mark.tEndNs - 500_000_000L, LabelEditor.find(l2, 1)!!.tEndNs)
        val l3 = LabelEditor.nudge(l2, mark, SegmentKind.SET, -LabelEditor.NUDGE_NS, 0)
        assertNull(LabelEditor.find(l3, 1)!!.tStartNs) // back to the watch's time
    }

    @Test
    fun nudgeRefusesToMakeSegmentTooShort() {
        val mark = TestSupport.defaultMarks[0]
        var l = labels(SegmentLabel(1, SegmentKind.SET))
        repeat(25) { l = LabelEditor.nudge(l, mark, SegmentKind.SET, +LabelEditor.NUDGE_NS, 0) }
        val s = LabelEditor.find(l, 1)!!
        val len = (s.tEndNs ?: mark.tEndNs) - (s.tStartNs ?: mark.tStartNs)
        assertEquals(LabelEditor.MIN_LENGTH_NS, len)
    }

    @Test
    fun editClearsSetOnlyFieldsForNonSetAndKeepsOverrides() {
        val l0 = labels(SegmentLabel(1, SegmentKind.SET, "bench", 60.0, "heavy", 5, 8, tStartNs = 123L))
        val l1 = LabelEditor.applyEdit(l0, 1, SegmentEdit(SegmentKind.OTHER, note = "phone call"))
        val s = LabelEditor.find(l1, 1)!!
        assertEquals(SegmentKind.OTHER, s.kind)
        assertNull(s.exercise)
        assertNull(s.repsUser)
        assertEquals("phone call", s.note)
        assertEquals(123L, s.tStartNs)
    }

    // ---- implicit rest preview = SegmentResolver ----

    @Test
    fun resolvedPreviewEqualsSegmentResolverWithImplicitRest() {
        val store = store()
        SessionImporter(store).import(SID, TestSupport.buildZip(tmp.newFolder()))
        store.updateLabels(SID) {
            var l = LabelEditor.put(it, SegmentLabel(1, SegmentKind.SET, "bench", 60.0, "heavy", 5))
            l = LabelEditor.put(l, SegmentLabel(2, SegmentKind.SET, "bench", 60.0, "heavy", 5))
            LabelEditor.put(l, SegmentLabel(3, SegmentKind.OTHER))
        }
        val session = store.load(SID)
        val preview = session.resolvedSegments()
        assertEquals(SegmentResolver.resolve(session.meta.kind, session.segments, session.labels), preview)
        val rests = preview.filter { it.implicit }
        // gap 20-40 s minus other (25-30 s) = pieces 20-25 and 30-40, each trimmed 2 s at both edges
        assertEquals(listOf(sec(22.0) to sec(23.0), sec(32.0) to sec(38.0)), rests.map { it.tStartNs to it.tEndNs })
        assertEquals(listOf(-1, -2), rests.map { it.segmentId })
    }

    @Test
    fun reviewViewModelWritesOverridesAndRecomputesRest() {
        val store = store()
        SessionImporter(store).import(SID, TestSupport.buildZip(tmp.newFolder()))
        store.updateLabels(SID) {
            LabelEditor.put(LabelEditor.put(it, SegmentLabel(1, SegmentKind.SET, "bench")), SegmentLabel(2, SegmentKind.SET, "bench"))
        }
        val exporter = ExportBuilder(store, File(tmp.root, "exports"))
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val vm = ReviewViewModel(SID, store, exporter, io = Dispatchers.Unconfined)
            assertFalse(vm.state.value.loading)
            vm.nudge(1, 0, +LabelEditor.NUDGE_NS) // set 1 now ends at 20.5 s
            val onDisk = store.readLabels(SID)!!
            assertEquals(sec(20.5), LabelEditor.find(onDisk, 1)!!.tEndNs)
            assertNull(LabelEditor.find(onDisk, 1)!!.tStartNs)
            val rest = vm.state.value.segments.filter { it.implicit && it.segmentId == -1 }.single()
            assertEquals(sec(22.5), rest.tStartNs)
            vm.applyEdit(1, SegmentEdit(SegmentKind.SET, "squat", 100.0, "medium", 8, 9, "grindy"))
            val s = LabelEditor.find(store.readLabels(SID)!!, 1)!!
            assertEquals("squat", s.exercise)
            assertEquals(8, s.repsUser)
            assertEquals(sec(20.5), s.tEndNs) // override survives an edit
            vm.applyEdit(-1, SegmentEdit(SegmentKind.SET)) // implicit rest is not editable
            assertNull(LabelEditor.find(store.readLabels(SID)!!, -1))
        } finally {
            Dispatchers.resetMain()
        }
        // the watch's raw files are untouched
        val original = SessionZip.read(store.zipFile(SID))
        assertFalse(original.containsKey(FILE_LABELS))
    }

    // ---- export ----

    @Test
    fun exportReadsBackAndLabelsValidateAgainstSchema() {
        val store = store()
        val zip = TestSupport.buildZip(tmp.newFolder())
        val originalEntries = SessionZip.read(zip)
        SessionImporter(store).import(SID, zip)
        store.updateLabels(SID) {
            LabelEditor.put(it, SegmentLabel(1, SegmentKind.SET, "ohp", 40.0, "medium", 10, 7, "ok", tStartNs = sec(10.5)))
        }
        val out = ExportBuilder(store, File(tmp.root, "exports")).build(SID)
        assertEquals("proprio-session-$SID.zip", out.name)

        val session = SessionReader.readZip(out)
        assertEquals(SID, session.meta.sessionId)
        assertEquals(3000, session.acc.size)
        val seg = session.resolvedSegments().first { it.segmentId == 1 }
        assertEquals(sec(10.5), seg.tStartNs)
        assertEquals(10, seg.repsUser)

        val entries = SessionZip.read(out)
        assertEquals(emptyList<String>(), TestSupport.labelErrors(entries.getValue(FILE_LABELS).decodeToString()))
        // watch files are byte-identical to what was received
        for ((name, bytes) in originalEntries) assertTrue(name, bytes.contentEquals(entries.getValue(name)))
        // no PII-looking keys: only the pseudonymous participant id
        assertTrue(entries.getValue(FILE_LABELS).decodeToString().contains("\"participant_id\": \"P01\""))
    }

    @Test
    fun emptyLabelsFileIsAlsoSchemaValid() {
        assertEquals(emptyList<String>(), TestSupport.labelErrors(labels().toJson()))
    }

    // ---- plot downsampling and settings ----

    @Test
    fun plotDataDownsamplesToBucketsWithMagnitudes() {
        val session = SessionReader.readZip(TestSupport.buildZip(tmp.newFolder()))
        val plot = PlotData.build(session.acc, session.gyr, buckets = 100)
        assertEquals(100, plot.buckets)
        assertEquals(session.acc.tNs.first(), plot.tStartNs)
        assertTrue(plot.acc.all { it in 9.0f..11.0f }) // ~|(sin, 9.81, 0.5)|
        assertEquals(0.5f, plot.timeToFraction(plot.fractionToTime(0.5f)), 1e-3f)
    }

    @Test
    fun settingsPersistToFile() {
        val f = File(tmp.root, "settings.json")
        val a = FileSettingsStore(f)
        a.update { it.copy(participantId = "P07", trainingConsent = true, consentAtUtc = "2026-02-02T00:00:00Z", leftWristAcknowledged = true) }
        val b = FileSettingsStore(f).settings.value
        assertEquals("P07", b.participantId)
        assertTrue(b.firstRunComplete)
        assertEquals("2026-02-02T00:00:00Z", b.consentAtUtc)
    }
}
