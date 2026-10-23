package dev.proprio.mobile

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import dev.proprio.protocol.AppInfo
import dev.proprio.protocol.ClockAnchor
import dev.proprio.protocol.DeviceInfo
import dev.proprio.protocol.Meta
import dev.proprio.protocol.SegmentMark
import dev.proprio.protocol.SegmentSource
import dev.proprio.protocol.SensorInfo
import dev.proprio.protocol.SessionKind
import dev.proprio.protocol.SessionWriter
import dev.proprio.protocol.SessionZip
import java.io.File
import kotlin.math.sin

object TestSupport {
    const val SID = "11111111-2222-4333-8444-555555555555"
    const val T0 = 1_000_000_000_000L
    fun sec(s: Double): Long = T0 + (s * 1e9).toLong()

    private val mapper = ObjectMapper()
    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    private val protocolDir = File(System.getProperty("proprio.protocol.dir") ?: error("proprio.protocol.dir not set"))

    val labelsSchema: JsonSchema by lazy {
        File(protocolDir, "schema/labels.schema.json").inputStream().use { factory.getSchema(it) }
    }

    /** Schema violations of [json] against protocol/schema/labels.schema.json (empty = valid). */
    fun labelErrors(json: String): List<String> = labelsSchema.validate(mapper.readTree(json)).map { it.message }

    /** Two sets (10-20 s, 40-50 s) plus an `other` mark (25-30 s) in a 60 s, 50 Hz recording. */
    val defaultMarks = listOf(
        SegmentMark(1, sec(10.0), sec(20.0), SegmentSource.PHONE),
        SegmentMark(2, sec(40.0), sec(50.0), SegmentSource.PHONE),
        SegmentMark(3, sec(25.0), sec(30.0), SegmentSource.WATCH),
    )

    fun meta(sessionId: String = SID, kind: SessionKind = SessionKind.GYM): Meta {
        val sensor = SensorInfo(1, "acc", "v", 1, 0.01, 78.0, 2000, 0, 0, 0, 20000, 0, false)
        return Meta(
            sessionId = sessionId, kind = kind, complete = false,
            app = AppInfo("dev.proprio.wear", "0.1.0", 1),
            device = DeviceInfo("m", "model", "p", 34, "14", "fp"),
            clockAnchorStart = ClockAnchor(T0, 1_700_000_000_000_000_000L),
            sensors = mapOf("acc" to sensor, "gyr" to sensor.copy(type = 4, name = "gyr")),
            startedAtUtc = "2026-01-01T10:00:00Z",
        )
    }

    /** Builds a Raw Format v1 session zip (a watch export, no labels.json) in [dir]; returns the zip file. */
    fun buildZip(
        dir: File,
        sessionId: String = SID,
        marks: List<SegmentMark> = defaultMarks,
        complete: Boolean = true,
        kind: SessionKind = SessionKind.GYM,
    ): File {
        val raw = File(dir, "raw-$sessionId").apply { mkdirs() }
        val w = SessionWriter(raw, meta(sessionId, kind = kind))
        for (i in 0 until 3000) {
            val t = T0 + i * 20_000_000L
            val phase = i * 0.1
            w.writeAcc(t, sin(phase).toFloat(), 9.81f, 0.5f)
            w.writeGyr(t, 0.1f, sin(phase).toFloat(), 0f)
        }
        marks.forEach(w::addSegment)
        if (complete) {
            w.finish(ClockAnchor(T0 + 60_000_000_000L, 1_700_000_060_000_000_000L), "2026-01-01T10:01:00Z")
        } else {
            w.close()
        }
        val zip = File(dir, SessionZip.fileName(sessionId))
        SessionZip.pack(raw, zip)
        return zip
    }
}
