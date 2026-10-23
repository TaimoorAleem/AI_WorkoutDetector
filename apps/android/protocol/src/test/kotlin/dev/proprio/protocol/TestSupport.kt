package dev.proprio.protocol

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import java.io.File

object TestSupport {
    /** Repo-root `protocol/` directory (set by the Gradle test task). */
    val protocolDir: File = File(System.getProperty("proprio.protocol.dir") ?: error("proprio.protocol.dir not set"))
    val goldenDir: File = File(protocolDir, "golden")

    private val mapper = ObjectMapper()
    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)

    private fun schema(name: String): JsonSchema =
        File(protocolDir, "schema/$name").inputStream().use { factory.getSchema(it) }

    val metaSchema: JsonSchema by lazy { schema("meta.schema.json") }
    val labelsSchema: JsonSchema by lazy { schema("labels.schema.json") }

    fun errors(schema: JsonSchema, json: String): List<String> =
        schema.validate(mapper.readTree(json)).map { it.message }

    fun tree(json: String): JsonNode = mapper.readTree(json)

    fun sampleMeta(complete: Boolean = true, kind: SessionKind = SessionKind.GYM): Meta {
        val sensor = SensorInfo(1, "acc", "v", 1, 0.01, 78.0, 2000, 0, 0, 0, 20000, 0, false)
        return Meta(
            sessionId = "11111111-2222-4333-8444-555555555555",
            kind = kind,
            complete = complete,
            app = AppInfo("dev.proprio.wear", "0.1.0", 1),
            device = DeviceInfo("m", "model", "p", 34, "14", "fp"),
            clockAnchorStart = ClockAnchor(1_000L, 2_000_000L),
            clockAnchorEnd = if (complete) ClockAnchor(5_000L, 6_000_100L) else null,
            sensors = mapOf("acc" to sensor, "gyr" to sensor.copy(type = 4, name = "gyr")),
            sampleCounts = if (complete) SampleCounts(1, 1) else null,
            startedAtUtc = "2025-01-01T00:00:00Z",
            endedAtUtc = if (complete) "2025-01-01T00:01:00Z" else null,
        )
    }

    fun sampleLabels(sessionId: String = sampleMeta().sessionId) = Labels(
        sessionId = sessionId,
        participantId = "P01",
        consent = Consent(true, "2025-01-01T00:00:00Z"),
        segments = listOf(
            SegmentLabel(1, SegmentKind.SET, "bench", 60.0, "heavy", 5, 8),
            SegmentLabel(2, SegmentKind.OTHER, tStartNs = 5L, tEndNs = null),
        ),
        app = LabelsApp("dev.proprio.mobile", "0.1.0"),
    )
}
