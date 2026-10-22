package dev.proprio.protocol

import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Deterministic generator for the cross-language golden files in `protocol/golden/`.
 *
 * Run explicitly with `./gradlew :protocol:generateGolden`; it is not part of `test`. Everything is
 * driven by fixed seeds and a fixed clock anchor, and trig uses [StrictMath] so the bytes do not
 * depend on the JVM.
 */
object GoldenGenerator {
    const val GYM_ZIP = "session_gym_v1.zip"
    const val CALIBRATION_ZIP = "session_calibration_v1.zip"
    const val EXPECTED = "expected.json"

    const val GYM_SESSION_ID = "3f1c2a52-8d0e-4b6a-9a57-2f6d0c1e7b01"
    const val CALIBRATION_SESSION_ID = "b7d4e9a0-5c33-4f1e-8a2b-6e1d9c4f0a02"

    /** Fixed anchor: sensor clock 1000 s after boot is 2025-06-15T15:06:40Z. */
    const val ELAPSED0_NS = 1_000_000_000_000L
    const val UNIX0_NS = 1_750_000_000_000_000_000L
    private const val SEC = 1_000_000_000L
    private const val PERIOD_NS = 20_000_000L

    private fun at(seconds: Double): Long = ELAPSED0_NS + (seconds * SEC).toLong()

    private class Plan(
        val sessionId: String,
        val kind: SessionKind,
        val seed: Int,
        val durationS: Double,
        val marks: List<SegmentMark>,
        val labels: Labels,
        val driftNs: Long,
        val gyrOffsetNs: Long,
        /** (sensor-row index at which to cut a >100 ms gap, rows removed) */
        val accGap: Int,
        val gyrGap: Int,
        val accDupAfter: Int,
        val gyrDupAfter: Int,
        val accSwapAt: Int,
        val gyrSwapAt: Int,
    )

    private fun meta(plan: Plan) = Meta(
        sessionId = plan.sessionId,
        kind = plan.kind,
        complete = false,
        app = AppInfo("dev.proprio.wear", "0.1.0", 1),
        device = DeviceInfo(
            manufacturer = "proprio-golden",
            model = "synthetic-watch",
            product = "synthetic",
            sdkInt = 34,
            release = "14",
            fingerprint = "proprio-golden/synthetic/v1",
        ),
        clockAnchorStart = ClockAnchor(ELAPSED0_NS, UNIX0_NS),
        sensors = mapOf(
            "acc" to sensorInfo(1, "Synthetic Accelerometer", 0.0023, 78.4532),
            "gyr" to sensorInfo(4, "Synthetic Gyroscope", 0.0011, 34.9066),
        ),
        startedAtUtc = "2025-06-15T15:06:40Z",
    )

    private fun sensorInfo(type: Int, name: String, resolution: Double, range: Double) = SensorInfo(
        type = type, name = name, vendor = "proprio", version = 1, resolution = resolution,
        maximumRange = range, minDelayUs = 2000, maxDelayUs = 500000, fifoReserved = 0, fifoMax = 0,
        requestedPeriodUs = 20000, maxReportLatencyUs = 0, isWakeUp = false,
    )

    private fun gymPlan(): Plan {
        val marks = listOf(
            SegmentMark(1, at(10.0), at(40.0), SegmentSource.WATCH),
            SegmentMark(2, at(55.0), at(85.0), SegmentSource.PHONE),
            SegmentMark(3, at(100.0), at(115.0), SegmentSource.PHONE),
            SegmentMark(4, at(130.0), at(160.0), SegmentSource.WATCH),
            SegmentMark(5, at(175.0), at(205.0), SegmentSource.PHONE),
        )
        val labels = Labels(
            sessionId = GYM_SESSION_ID,
            participantId = "P01",
            consent = Consent(true, "2025-06-15T15:05:00Z"),
            segments = listOf(
                SegmentLabel(1, SegmentKind.SET, "bench", 60.0, "heavy", 5, 8),
                SegmentLabel(2, SegmentKind.SET, "squat", 80.0, "medium", 8, 7, "felt easy"),
                SegmentLabel(3, SegmentKind.OTHER, note = "walking"),
                SegmentLabel(4, SegmentKind.SET, "dead", 100.0, "heavy", 3, 9),
                // Boundary override from the review screen: the set really started at 177.5 s.
                SegmentLabel(5, SegmentKind.SET, "ohp", 40.0, "medium", 10, tStartNs = at(177.5)),
            ),
            app = LabelsApp("dev.proprio.mobile", "0.1.0"),
        )
        return Plan(
            GYM_SESSION_ID, SessionKind.GYM, seed = 1234, durationS = 225.0, marks = marks, labels = labels,
            driftNs = 12_000_000L, gyrOffsetNs = 7_000_000L,
            accGap = 4500, gyrGap = 4560, accDupAfter = 8000, gyrDupAfter = 6000, accSwapAt = 2000, gyrSwapAt = 9000,
        )
    }

    private fun calibrationPlan(): Plan {
        val marks = ArrayList<SegmentMark>()
        val segs = ArrayList<SegmentLabel>()
        var cursor = 10.0
        var id = 1
        fun step(name: String, seconds: Double) {
            marks += SegmentMark(id, at(cursor), at(cursor + seconds), SegmentSource.AUTO, name)
            segs += SegmentLabel(id, SegmentKind.CALIBRATION, note = name)
            id++
            cursor += seconds + 5.0 // 5 s unsegmented transition
        }
        step("table_flat_face_up", 120.0)
        for (pose in listOf("face_up", "face_down", "buttons_up", "buttons_down", "twelve_up", "twelve_down")) {
            step("pose_$pose", 10.0)
        }
        for (axis in listOf("rot_x", "rot_y", "rot_z")) step(axis, 15.0)
        val labels = Labels(
            sessionId = CALIBRATION_SESSION_ID,
            participantId = "P01",
            consent = Consent(true, "2025-06-15T15:05:00Z"),
            segments = segs,
            app = LabelsApp("dev.proprio.mobile", "0.1.0"),
        )
        return Plan(
            CALIBRATION_SESSION_ID, SessionKind.CALIBRATION, seed = 4321, durationS = cursor + 5.0,
            marks = marks, labels = labels, driftNs = 31_000_000L, gyrOffsetNs = 11_000_000L,
            accGap = 2000, gyrGap = 2100, accDupAfter = 5000, gyrDupAfter = 7000, accSwapAt = 3000, gyrSwapAt = 1000,
        )
    }

    private class Row(val t: Long, val x: Float, val y: Float, val z: Float)

    /** Synthetic motion: gravity plus per-segment oscillation; see [signal]. */
    private fun rows(plan: Plan, gyro: Boolean, rng: Random, offsetNs: Long, gapAt: Int, dupAfter: Int, swapAt: Int): List<Row> {
        val n = ((plan.durationS - 0.1) / 0.02).toInt()
        val list = ArrayList<Row>(n + 2)
        for (i in 0 until n) {
            val jitter = (rng.nextInt(-3_000_000, 3_000_001)).toLong()
            val t = ELAPSED0_NS + 50_000_000L + offsetNs + i * PERIOD_NS + jitter
            val s = (t - ELAPSED0_NS) / 1e9
            val v = signal(plan, gyro, s, rng)
            list += Row(t, v[0], v[1], v[2])
        }
        // >100 ms gap: remove 7 consecutive samples (interval of 8 periods = 160 ms).
        repeat(7) { list.removeAt(gapAt) }
        // Out-of-order arrival.
        val a = list[swapAt]
        list[swapAt] = list[swapAt + 1]
        list[swapAt + 1] = a
        // Exact duplicate timestamp with a different payload: the reader must keep the first.
        val d = list[dupAfter]
        list.add(dupAfter + 1, Row(d.t, d.x + 1f, d.y, d.z))
        return list
    }

    private fun signal(plan: Plan, gyro: Boolean, s: Double, rng: Random): FloatArray {
        val noise = { sd: Double -> (rng.nextDouble() - 0.5) * 2.0 * sd }
        var gx = 0.0
        var gy = 9.7
        var gz = 1.2
        var rx = 0.0
        var ry = 0.0
        var rz = 0.0
        val mark = plan.marks.firstOrNull { s >= (it.tStartNs - ELAPSED0_NS) / 1e9 && s < (it.tEndNs - ELAPSED0_NS) / 1e9 }
        if (plan.kind == SessionKind.GYM) {
            val label = mark?.let { m -> plan.labels.segments.first { it.segmentId == m.segmentId } }
            when (label?.kind) {
                SegmentKind.SET -> {
                    val heavy = label.category == "heavy"
                    val amp = if (heavy) 11.0 else 6.0 // heavy sets exceed 1.9 g on y
                    val w = 2.0 * Math.PI * (if (heavy) 0.5 else 0.8) * s
                    gy += amp * StrictMath.sin(w)
                    gz += 0.4 * amp * StrictMath.cos(w)
                    rx = 2.5 * StrictMath.cos(w)
                    ry = 0.8 * StrictMath.sin(w)
                }
                SegmentKind.OTHER -> {
                    val w = 2.0 * Math.PI * 1.7 * s
                    gy += 2.0 * StrictMath.sin(w)
                    gx += 1.5 * StrictMath.cos(w)
                    rz = 0.9 * StrictMath.sin(w)
                }
                else -> {
                    gy += 0.15 * StrictMath.sin(2.0 * Math.PI * 0.2 * s)
                }
            }
        } else {
            // Calibration: static poses flip gravity between axes; rotations sweep a slow gyro rate.
            val step = mark?.step.orEmpty()
            when {
                step.startsWith("pose_") -> {
                    val g = 9.80665
                    val v = when (step.removePrefix("pose_")) {
                        "face_up" -> doubleArrayOf(0.0, 0.0, g)
                        "face_down" -> doubleArrayOf(0.0, 0.0, -g)
                        "buttons_up" -> doubleArrayOf(g, 0.0, 0.0)
                        "buttons_down" -> doubleArrayOf(-g, 0.0, 0.0)
                        "twelve_up" -> doubleArrayOf(0.0, g, 0.0)
                        else -> doubleArrayOf(0.0, -g, 0.0)
                    }
                    gx = v[0]; gy = v[1]; gz = v[2]
                }
                step == "table_flat_face_up" -> { gx = 0.0; gy = 0.0; gz = 9.80665 }
                step.startsWith("rot_") -> {
                    val w = 2.0 * Math.PI * 0.2 * s // 5 s per rotation
                    val rate = 0.31 * StrictMath.sin(w) // ~90 degrees per half... slow, signed
                    gx = 0.0; gy = 9.80665 * StrictMath.cos(0.3 * s); gz = 9.80665 * StrictMath.sin(0.3 * s)
                    when (step.removePrefix("rot_")) {
                        "x" -> rx = rate
                        "y" -> ry = rate
                        else -> rz = rate
                    }
                }
                else -> { gx = 0.0; gy = 0.0; gz = 9.80665 }
            }
        }
        return if (gyro) {
            floatArrayOf((rx + noise(0.01)).toFloat(), (ry + noise(0.01)).toFloat(), (rz + noise(0.01)).toFloat())
        } else {
            floatArrayOf((gx + noise(0.05)).toFloat(), (gy + noise(0.05)).toFloat(), (gz + noise(0.05)).toFloat())
        }
    }

    private fun build(plan: Plan, zip: File) {
        val work = Files.createTempDirectory("proprio-golden").toFile()
        try {
            var fakeMs = 0L
            val dir = File(work, "s")
            // Fake clock advances 1 ms per call so the 2 s flush path is exercised deterministically.
            SessionWriter(dir, meta(plan), monotonicMs = { fakeMs++ }).use { w ->
                val acc = rows(plan, false, Random(plan.seed), 0L, plan.accGap, plan.accDupAfter, plan.accSwapAt)
                val gyr = rows(plan, true, Random(plan.seed + 1), plan.gyrOffsetNs, plan.gyrGap, plan.gyrDupAfter, plan.gyrSwapAt)
                for (r in acc) w.writeAcc(r.t, r.x, r.y, r.z)
                for (r in gyr) w.writeGyr(r.t, r.x, r.y, r.z)
                for (m in plan.marks) w.addSegment(m)
                val endElapsed = ELAPSED0_NS + (plan.durationS * SEC).toLong()
                w.finish(ClockAnchor(endElapsed, UNIX0_NS + endElapsed - ELAPSED0_NS + plan.driftNs), "2025-06-15T15:10:40Z")
            }
            val base = File(work, "base.zip")
            SessionZip.pack(dir, base)
            SessionZip.withLabels(base, plan.labels, zip)
        } finally {
            work.deleteRecursively()
        }
    }

    /** Writes both zips into [outDir] (or any dir). */
    fun generateZips(outDir: File) {
        outDir.mkdirs()
        build(gymPlan(), File(outDir, GYM_ZIP))
        build(calibrationPlan(), File(outDir, CALIBRATION_ZIP))
    }

    /** Expected values for one session, computed by the Kotlin reader (cross-checked in tests). */
    fun expectedFor(zip: File): JsonObject {
        val s = SessionReader.readZip(zip)
        val resolved = s.resolvedSegments()
        val stats = RecordingStats.compute(s)
        fun num(d: Double?): JsonElement = if (d == null) JsonNull else JsonPrimitive(d)
        fun sensor(st: SensorStats) = buildJsonObject {
            put("n", st.n); put("duration_s", st.durationS); put("rate_hz", st.rateHz)
            put("dt_p50_ms", st.dtP50Ms); put("dt_p95_ms", st.dtP95Ms); put("dt_p99_ms", st.dtP99Ms)
            put("dt_max_ms", st.dtMaxMs); put("gaps_gt_100ms", st.gapsOver100Ms); put("duplicates", st.duplicates)
        }
        return buildJsonObject {
            put("session_id", s.meta.sessionId)
            put("kind", s.meta.kind.name.lowercase())
            put("complete", s.meta.complete)
            put("participant_id", s.labels?.participantId)
            put("sample_counts", buildJsonObject {
                put("acc", s.meta.sampleCounts!!.acc); put("gyr", s.meta.sampleCounts!!.gyr)
            })
            put("rows", buildJsonObject { put("acc", s.report.accRows); put("gyr", s.report.gyrRows) })
            put("segments", JsonArray(resolved.map { r ->
                val ai = s.acc.indexRange(r.tStartNs, r.tEndNs)
                val gi = s.gyr.indexRange(r.tStartNs, r.tEndNs)
                buildJsonObject {
                    put("segment_id", r.segmentId)
                    put("kind", r.kind?.name?.lowercase())
                    put("t_start_ns", r.tStartNs); put("t_end_ns", r.tEndNs)
                    put("t_start_unix_ns", s.meta.elapsedToUnixNs(r.tStartNs))
                    put("t_end_unix_ns", s.meta.elapsedToUnixNs(r.tEndNs))
                    put("source", r.source?.csvName())
                    put("step", r.step)
                    put("exercise", r.exercise)
                    put("load_kg", r.loadKg)
                    put("category", r.category)
                    put("reps_user", r.repsUser)
                    put("rpe", r.rpe)
                    put("implicit", r.implicit)
                    put("acc_n", ai.last - ai.first + 1)
                    put("gyr_n", gi.last - gi.first + 1)
                }
            }))
            put("stats", buildJsonObject {
                put("acc", sensor(stats.acc)); put("gyr", sensor(stats.gyr))
                put("acc_gyr_start_offset_ms", num(stats.accGyrStartOffsetMs))
                put("clock_drift_ms", num(stats.clockDriftMs))
                put("acc_frac_above_1p9g", stats.accFracAbove1p9g)
            })
        }
    }

    fun expectedJson(dir: File): String {
        val root = buildJsonObject {
            put("generator", "dev.proprio.protocol.GoldenGenerator")
            put("raw_format", RAW_FORMAT_VERSION)
            put("gym", expectedFor(File(dir, GYM_ZIP)))
            put("calibration", expectedFor(File(dir, CALIBRATION_ZIP)))
        }
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root) + "\n"
    }
}

fun main(args: Array<String>) {
    val out = File(args.getOrNull(0) ?: error("usage: GoldenGenerator <output dir>"))
    GoldenGenerator.generateZips(out)
    File(out, GoldenGenerator.EXPECTED).writeText(GoldenGenerator.expectedJson(out))
    println("wrote golden files to ${out.absolutePath}")
}
