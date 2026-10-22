package dev.proprio.wear

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import dev.proprio.protocol.SegmentMark
import dev.proprio.protocol.SegmentSource
import dev.proprio.protocol.SessionKind
import dev.proprio.protocol.SessionReader
import dev.proprio.wear.sensor.SensorRecorder
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

object Schemas {
    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    private val dir = File(System.getProperty("proprio.protocol.dir") ?: error("proprio.protocol.dir not set"))
    private val meta = File(dir, "schema/meta.schema.json").inputStream().use { factory.getSchema(it) }
    fun metaErrors(json: String): List<String> = meta.validate(ObjectMapper().readTree(json)).map { it.message }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SensorRecorderTest {
    private fun tmp(): File = createTempDirectory("rec").toFile()

    private fun TestScope.recorder(source: FakeSensorSource, log: CallLog? = null, cap: Int = 1000) =
        SensorRecorder(
            source,
            FakeClock(log),
            StandardTestDispatcher(testScheduler),
            queueCapacity = cap,
        )

    @Test
    fun jitterDuplicatesOutOfOrderAndGapsAreWrittenRaw() = runTest {
        val src = FakeSensorSource()
        val rec = recorder(src)
        val dir = tmp()
        rec.start(this, dir, "sid-1", SessionKind.GYM, testApp, testDevice)
        // jitter, an exact duplicate, an out-of-order event, then a 1 s gap
        val ts = listOf(0L, 19_000_000L, 41_500_000L, 41_500_000L, 30_000_000L, 1_100_000_000L)
        ts.forEachIndexed { i, t -> src.acc(t, x = i.toFloat()) }
        src.gyr(5_000_000L, 1f, 2f, 3f)
        val meta = rec.stop()

        // exactly as delivered, in arrival order: no sort, no dedup, no resampling
        assertEquals(
            listOf("t_ns,x,y,z") + ts.mapIndexed { i, t -> "$t,${i.toFloat()},0.0,9.81" },
            dir.lines("acc.csv"),
        )
        assertEquals(listOf("t_ns,x,y,z", "5000000,1.0,2.0,3.0"), dir.lines("gyr.csv"))
        assertTrue(meta.complete)
        assertEquals(6L, meta.sampleCounts!!.acc)
        assertEquals(1L, meta.sampleCounts!!.gyr)

        val read = SessionReader.readDir(dir)
        assertEquals(1, read.report.accDuplicates)
        assertTrue(read.report.accOutOfOrder >= 1)
        assertEquals(0, rec.snapshot().droppedEvents)
    }

    @Test
    fun registersAtTwentyMillisWithZeroLatencyAndRecordsSensorInfo() = runTest {
        val src = FakeSensorSource()
        val rec = recorder(src)
        val dir = tmp()
        rec.start(this, dir, "sid-2", SessionKind.GYM, testApp, testDevice)
        assertEquals(20_000, src.startedPeriodUs)
        assertEquals(0, src.startedLatencyUs)
        val meta = rec.stop()
        val acc = meta.sensors.getValue("acc")
        assertEquals(src.accInfo.copy(requestedPeriodUs = 20_000, maxReportLatencyUs = 0), acc)
        assertEquals("fake acc", acc.name)
        assertEquals(10, acc.fifoReserved)
        assertEquals(3000, acc.fifoMax)
        assertTrue(meta.sensors.getValue("gyr").isWakeUp)
        assertEquals(testDevice, meta.device)
        assertEquals("left", meta.wrist)
        assertEquals(1, src.stopCount)
    }

    @Test
    fun anchorsAreCapturedBeforeRegistrationAndAfterUnregistration() = runTest {
        val log = CallLog()
        val src = FakeSensorSource(log)
        val rec = recorder(src, log)
        val dir = tmp()
        rec.start(this, dir, "sid-3", SessionKind.GYM, testApp, testDevice)
        rec.stop()
        val e = log.entries
        // adjacent statements, elapsed first, then unix
        val firstElapsed = e.indexOf("elapsed")
        assertEquals("unix", e[firstElapsed + 1])
        assertTrue("start anchor before register", firstElapsed < e.indexOf("register"))
        val unreg = e.indexOf("unregister")
        val lastElapsed = e.lastIndexOf("elapsed")
        assertTrue("end anchor after unregister", lastElapsed > unreg)
        assertEquals("unix", e[lastElapsed + 1])
        assertEquals(2, e.count { it == "elapsed" })
    }

    @Test
    fun metaValidatesAgainstSchemaAndRoundTrips() = runTest {
        val src = FakeSensorSource()
        val rec = recorder(src)
        val dir = tmp()
        rec.start(this, dir, "sid-4", SessionKind.CALIBRATION, testApp, testDevice)
        // a crashed session first: meta.json is complete=false and valid
        assertEquals(emptyList<String>(), Schemas.metaErrors(File(dir, "meta.json").readText()))
        assertFalse(SessionReader.readDir(dir).meta.complete)
        for (i in 0 until 100) {
            src.acc(i * 20_000_000L)
            src.gyr(i * 20_000_000L + 1_000)
        }
        rec.addSegment(SegmentMark(1, 0L, 1_000_000_000L, SegmentSource.AUTO, "face_up"))
        rec.stop()
        assertEquals(emptyList<String>(), Schemas.metaErrors(File(dir, "meta.json").readText()))
        val read = SessionReader.readDir(dir)
        assertTrue(read.meta.complete)
        assertEquals(100, read.acc.size)
        assertEquals(100, read.gyr.size)
        assertEquals(1, read.segments.size)
        assertEquals("face_up", read.segments[0].step)
        assertEquals(SessionKind.CALIBRATION, read.meta.kind)
        assertEquals(read.meta.clockAnchorStart.unixTimeNs, 1_700_000_000_000L * 1_000_000L)
    }

    @Test
    fun queueOverflowDropsNewestAndCounts() = runTest {
        val src = FakeSensorSource()
        val rec = recorder(src, cap = 4) // writer does not run until stop() (StandardTestDispatcher)
        val dir = tmp()
        rec.start(this, dir, "sid-5", SessionKind.GYM, testApp, testDevice)
        for (i in 0 until 10) src.acc(i * 20_000_000L)
        assertEquals(6, rec.snapshot().droppedEvents)
        val meta = rec.stop()
        assertEquals(4L, meta.sampleCounts!!.acc)
        assertEquals(
            listOf(0L, 20_000_000L, 40_000_000L, 60_000_000L), // oldest kept, newest dropped
            SessionReader.readDir(dir).acc.tNs.toList(),
        )
        assertEquals(6, rec.snapshot().droppedEvents)
    }

    @Test
    fun nonFiniteValuesAreRejectedNotFatal() = runTest {
        val src = FakeSensorSource()
        val rec = recorder(src)
        val dir = tmp()
        rec.start(this, dir, "sid-6", SessionKind.GYM, testApp, testDevice)
        src.acc(1L, x = Float.NaN)
        src.acc(2L)
        val meta = rec.stop()
        assertEquals(1L, meta.sampleCounts!!.acc)
    }

    @Test
    fun liveRateEstimateTracksEventTimestamps() = runTest {
        val src = FakeSensorSource()
        val rec = recorder(src)
        rec.start(this, tmp(), "sid-7", SessionKind.GYM, testApp, testDevice)
        for (i in 0 until 200) src.acc(i * 20_000_000L) // 50 Hz
        for (i in 0 until 200) src.gyr(i * 10_000_000L) // 100 Hz
        val snap = rec.snapshot()
        assertEquals(50.0, snap.accRateHz, 0.01)
        assertEquals(100.0, snap.gyrRateHz, 0.01)
        rec.stop()
    }

    @Test
    fun failedRegistrationAbortsAndThrows() = runTest {
        val src = FakeSensorSource(startOk = false)
        val rec = recorder(src)
        val dir = tmp()
        val r = runCatching { rec.start(this, dir, "sid-8", SessionKind.GYM, testApp, testDevice) }
        assertTrue(r.isFailure)
        assertFalse(rec.isRecording)
        // header-only session still readable, complete=false
        assertFalse(SessionReader.readDir(dir).meta.complete)
    }
}
