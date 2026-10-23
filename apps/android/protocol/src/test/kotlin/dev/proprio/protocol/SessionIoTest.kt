package dev.proprio.protocol

import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SessionIoTest {
    @TempDir
    lateinit var tmp: File

    private fun writer(dir: File, clock: () -> Long = { 0L }) =
        SessionWriter(dir, TestSupport.sampleMeta(complete = false), monotonicMs = clock)

    @Test
    fun roundTripPreservesFloat32AndSegments() {
        val dir = File(tmp, "s")
        val rng = Random(7)
        val values = FloatArray(300) { (rng.nextDouble() * 40 - 20).toFloat() * if (it % 7 == 0) 1e-6f else 1f }
        writer(dir).use { w ->
            for (i in 0 until 100) {
                w.writeAcc(1000L + i, values[3 * i], values[3 * i + 1], values[3 * i + 2])
                w.writeGyr(1500L + i, values[3 * i + 2], values[3 * i + 1], values[3 * i])
            }
            w.addSegment(SegmentMark(1, 1010, 1050, SegmentSource.WATCH))
            w.addSegment(SegmentMark(2, 1060, 1090, SegmentSource.AUTO, "rot_x"))
            val m = w.finish(ClockAnchor(5000, 6_000_100), "2025-01-01T00:01:00Z")
            assertTrue(m.complete)
            assertEquals(SampleCounts(100, 100), m.sampleCounts)
        }
        val s = SessionReader.readDir(dir)
        assertTrue(s.meta.complete)
        assertEquals(100, s.acc.size)
        assertArrayEquals(values, s.acc.xyz) // bit-exact float32 round trip
        assertEquals(1000L, s.acc.tNs[0])
        assertEquals(listOf(null, "rot_x"), s.segments.map { it.step })
        assertEquals(SegmentSource.AUTO, s.segments[1].source)
        assertEquals(3.9961, s.meta.clockDriftMs()!!, 1e-9)
    }

    @Test
    fun readerSortsAndDeduplicatesKeepingFirst() {
        val dir = File(tmp, "s")
        writer(dir).use { w ->
            w.writeAcc(30, 3f, 0f, 0f)
            w.writeAcc(10, 1f, 0f, 0f)
            w.writeAcc(20, 2f, 0f, 0f)
            w.writeAcc(20, 99f, 0f, 0f) // duplicate timestamp
            w.writeAcc(40, 4f, 0f, 0f)
        }
        val s = SessionReader.readDir(dir)
        assertArrayEquals(longArrayOf(10, 20, 30, 40), s.acc.tNs)
        assertEquals(listOf(1f, 2f, 3f, 4f), (0 until 4).map { s.acc.xyz[3 * it] })
        assertEquals(1, s.report.accDuplicates)
        assertEquals(1, s.report.accOutOfOrder)
        assertEquals(5, s.report.accRows)
        assertFalse(s.meta.complete)
        assertNull(s.meta.clockAnchorEnd)
        assertNull(s.meta.clockDriftMs())
    }

    @Test
    fun truncatedMidRowRecoversAllCompleteRows() {
        val dir = File(tmp, "s")
        writer(dir).use { w ->
            for (i in 0 until 50) w.writeAcc(100L + i, 1.5f + i, 2f, 3f)
            w.flush()
        }
        val f = File(dir, FILE_ACC)
        val bytes = f.readBytes()
        // Cut in the middle of the last row (drop its newline and the last 3 chars).
        f.writeBytes(bytes.copyOf(bytes.size - 4))
        val s = SessionReader.readDir(dir)
        assertEquals(49, s.acc.size)
        assertEquals(1, s.report.droppedTruncatedRows)
        assertEquals(148L, s.acc.tNs.last())
        assertFalse(s.meta.complete) // never finished: crash-safe meta
    }

    @Test
    fun truncatedHeaderAndEmptyFilesAreTolerated() {
        val dir = File(tmp, "s")
        writer(dir).close()
        File(dir, FILE_ACC).writeText("t_ns,x")
        File(dir, FILE_GYR).writeText("")
        val s = SessionReader.readDir(dir)
        assertEquals(0, s.acc.size)
        assertEquals(0, s.gyr.size)
    }

    @Test
    fun dataReachesDiskWithinFlushInterval() {
        val dir = File(tmp, "s")
        var now = 0L
        val w = writer(dir) { now }
        val file = File(dir, FILE_ACC)
        val headerLen = file.length()
        w.writeAcc(1, 1f, 2f, 3f)
        assertEquals(headerLen, file.length(), "still buffered before the interval")
        now = 1_999
        w.writeAcc(2, 1f, 2f, 3f)
        assertEquals(headerLen, file.length())
        now = 2_000
        w.writeAcc(3, 1f, 2f, 3f)
        assertTrue(file.length() > headerLen, "flushed once 2 s have elapsed")
        w.close()
    }

    @Test
    fun segmentMarksAreFlushedImmediatelyAndMetaStartsIncomplete() {
        val dir = File(tmp, "s")
        val w = writer(dir)
        w.addSegment(SegmentMark(1, 1, 2, SegmentSource.PHONE))
        assertTrue(File(dir, FILE_SEGMENTS).readText().endsWith("1,1,2,phone,\n"))
        assertTrue(TestSupport.tree(File(dir, FILE_META).readText()).get("complete").isBoolean)
        assertFalse(TestSupport.tree(File(dir, FILE_META).readText()).get("complete").asBoolean())
        w.close()
    }

    @Test
    fun rejectsNonFiniteSamples() {
        writer(File(tmp, "s")).use { w ->
            assertThrows(IllegalArgumentException::class.java) { w.writeAcc(1, Float.NaN, 0f, 0f) }
        }
    }

    @Test
    fun zipPackUnpackAndWithLabels() {
        val dir = File(tmp, "s")
        writer(dir).use { w ->
            w.writeAcc(1, 1f, 2f, 3f)
            w.finish(ClockAnchor(5000, 6_000_100), "2025-01-01T00:01:00Z")
        }
        val zip = File(tmp, "a.zip")
        SessionZip.pack(dir, zip)
        assertEquals(listOf(FILE_META, FILE_ACC, FILE_GYR, FILE_SEGMENTS), SessionZip.read(zip).keys.toList())
        assertNull(SessionReader.readZip(zip).labels)

        val labeled = File(tmp, "b.zip")
        SessionZip.withLabels(zip, TestSupport.sampleLabels(), labeled)
        val s = SessionReader.readZip(labeled)
        assertNotNull(s.labels)
        assertEquals("P01", s.labels!!.participantId)
        assertArrayEquals(SessionZip.read(zip)[FILE_ACC], SessionZip.read(labeled)[FILE_ACC])

        val out = File(tmp, "out")
        SessionZip.unpack(labeled, out)
        assertTrue(File(out, FILE_LABELS).isFile)
        // Packing is deterministic.
        val again = File(tmp, "c.zip")
        SessionZip.withLabels(zip, TestSupport.sampleLabels(), again)
        assertArrayEquals(Files.readAllBytes(labeled.toPath()), Files.readAllBytes(again.toPath()))
    }

    @Test
    fun rejectsUnsafeEntriesWrongVersionAndMismatchedLabels() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionZip.write(mapOf("../evil.txt" to ByteArray(1)), File(tmp, "x.zip"))
        }
        val dir = File(tmp, "s")
        writer(dir).close()
        val bad = File(dir, FILE_META).readText().replace("\"raw_format\": 1", "\"raw_format\": 2")
        File(dir, FILE_META).writeText(bad)
        assertThrows(UnsupportedFormatException::class.java) { SessionReader.readDir(dir) }

        val dir2 = File(tmp, "s2")
        writer(dir2).close()
        File(dir2, FILE_LABELS).writeText(TestSupport.sampleLabels("other-session").toJson())
        assertThrows(IllegalArgumentException::class.java) { SessionReader.readDir(dir2) }
    }
}
