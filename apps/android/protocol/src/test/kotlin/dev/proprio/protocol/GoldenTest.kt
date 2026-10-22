package dev.proprio.protocol

import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Reads the committed golden files back and checks them against `expected.json`. */
class GoldenTest {
    @TempDir
    lateinit var tmp: File

    private val golden = TestSupport.goldenDir

    @Test
    fun committedExpectedJsonMatchesWhatTheReaderComputes() {
        val committed = File(golden, GoldenGenerator.EXPECTED).readText().replace("\r\n", "\n")
        assertEquals(committed, GoldenGenerator.expectedJson(golden))
    }

    @Test
    fun generatorIsDeterministicAndMatchesCommittedContent() {
        GoldenGenerator.generateZips(tmp)
        for (name in listOf(GoldenGenerator.GYM_ZIP, GoldenGenerator.CALIBRATION_ZIP)) {
            val fresh = SessionZip.read(File(tmp, name))
            val committed = SessionZip.read(File(golden, name))
            assertEquals(committed.keys, fresh.keys, name)
            for (k in committed.keys) {
                assertTrue(committed.getValue(k).contentEquals(fresh.getValue(k)), "$name/$k differs; run :protocol:generateGolden")
            }
        }
    }

    @Test
    fun gymGoldenHasTheDesignedDefects() {
        val s = SessionReader.readZip(File(golden, GoldenGenerator.GYM_ZIP))
        assertTrue(s.meta.complete)
        assertEquals(GoldenGenerator.GYM_SESSION_ID, s.meta.sessionId)
        assertEquals(1, s.report.accDuplicates)
        assertEquals(1, s.report.gyrDuplicates)
        assertEquals(1, s.report.accOutOfOrder)
        assertEquals(1, s.report.gyrOutOfOrder)
        val st = RecordingStats.compute(s)
        assertEquals(1, st.acc.gapsOver100Ms)
        assertEquals(1, st.gyr.gapsOver100Ms)
        assertEquals(50.0, st.acc.rateHz, 0.5)
        assertEquals(12.0, st.clockDriftMs!!, 1e-9)
        assertTrue(st.accFracAbove1p9g > 0.0)
        assertTrue(st.accGyrStartOffsetMs in 0.0..14.0)

        val seg = s.resolvedSegments()
        fun sec(x: Long) = (x - GoldenGenerator.ELAPSED0_NS) / 1e9
        assertEquals(
            listOf(
                "set" to (10.0 to 40.0), "rest" to (42.0 to 53.0), "set" to (55.0 to 85.0),
                "rest" to (87.0 to 98.0), "other" to (100.0 to 115.0), "rest" to (117.0 to 128.0),
                "set" to (130.0 to 160.0), "rest" to (162.0 to 175.5), "set" to (177.5 to 205.0),
            ),
            seg.map { it.kind!!.name.lowercase() to (sec(it.tStartNs) to sec(it.tEndNs)) },
        )
        assertEquals(listOf("bench", "squat", "dead", "ohp"), seg.filter { it.kind == SegmentKind.SET }.map { it.exercise })
    }

    @Test
    fun calibrationGoldenHasAutoSegmentsWithSteps() {
        val s = SessionReader.readZip(File(golden, GoldenGenerator.CALIBRATION_ZIP))
        assertEquals(SessionKind.CALIBRATION, s.meta.kind)
        assertEquals(10, s.segments.size)
        assertTrue(s.segments.all { it.source == SegmentSource.AUTO && it.step != null })
        assertEquals("table_flat_face_up", s.segments.first().step)
        assertEquals("rot_z", s.segments.last().step)
        assertTrue(s.resolvedSegments().none { it.implicit })
        assertEquals(1, s.report.accDuplicates)
        assertTrue(Files.size(File(golden, GoldenGenerator.CALIBRATION_ZIP).toPath()) > 0)
    }
}
