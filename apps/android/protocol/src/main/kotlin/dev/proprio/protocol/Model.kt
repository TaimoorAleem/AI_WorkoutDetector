package dev.proprio.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/** Raw Format v1 data model (docs/specs/phase2-recorder.md section 3). */
const val RAW_FORMAT_VERSION = 1
const val LABELS_FORMAT_VERSION = 1

const val FILE_META = "meta.json"
const val FILE_ACC = "acc.csv"
const val FILE_GYR = "gyr.csv"
const val FILE_SEGMENTS = "segments.csv"
const val FILE_LABELS = "labels.json"

/** JSON configuration shared by every reader and writer: snake_case keys, nulls omitted. */
@OptIn(ExperimentalSerializationApi::class)
val ProtocolJson: Json = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
    prettyPrint = true
}

@Serializable
enum class SessionKind {
    @SerialName("gym") GYM,
    @SerialName("calibration") CALIBRATION,
}

@Serializable
data class AppInfo(
    @SerialName("package") val packageName: String,
    val versionName: String,
    val versionCode: Int,
)

@Serializable
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val product: String,
    val sdkInt: Int,
    val release: String,
    val fingerprint: String,
)

/** `elapsedRealtimeNanos` and Unix time captured in adjacent statements. */
@Serializable
data class ClockAnchor(
    val elapsedRealtimeNs: Long,
    val unixTimeNs: Long,
)

@Serializable
data class SensorInfo(
    val type: Int,
    val name: String,
    val vendor: String,
    val version: Int,
    val resolution: Double,
    val maximumRange: Double,
    val minDelayUs: Int,
    val maxDelayUs: Int,
    val fifoReserved: Int,
    val fifoMax: Int,
    val requestedPeriodUs: Int,
    val maxReportLatencyUs: Int,
    val isWakeUp: Boolean,
)

@Serializable
data class SampleCounts(val acc: Long, val gyr: Long)

/** `meta.json`, written by the watch. The `ended_*` fields are absent when `complete` is false. */
@Serializable
data class Meta(
    val rawFormat: Int = RAW_FORMAT_VERSION,
    val sessionId: String,
    val kind: SessionKind,
    val complete: Boolean,
    val app: AppInfo,
    val device: DeviceInfo,
    val wrist: String = "left",
    val clockAnchorStart: ClockAnchor,
    val clockAnchorEnd: ClockAnchor? = null,
    val sensors: Map<String, SensorInfo>,
    val sampleCounts: SampleCounts? = null,
    val startedAtUtc: String,
    val endedAtUtc: String? = null,
) {
    /** Converts a sensor timestamp to Unix ns with the start anchor (spec section 3.1). */
    fun elapsedToUnixNs(tNs: Long): Long =
        tNs - clockAnchorStart.elapsedRealtimeNs + clockAnchorStart.unixTimeNs

    /** End-anchor drift in ms: (unix delta) - (elapsed delta). Null when there is no end anchor. */
    fun clockDriftMs(): Double? {
        val end = clockAnchorEnd ?: return null
        val unixDelta = end.unixTimeNs - clockAnchorStart.unixTimeNs
        val elapsedDelta = end.elapsedRealtimeNs - clockAnchorStart.elapsedRealtimeNs
        return (unixDelta - elapsedDelta) / 1e6
    }
}

@Serializable
enum class SegmentSource {
    @SerialName("watch") WATCH,
    @SerialName("phone") PHONE,
    @SerialName("auto") AUTO,
}

/** One row of `segments.csv`: a mark created on the watch, in the sample clock. */
data class SegmentMark(
    val segmentId: Int,
    val tStartNs: Long,
    val tEndNs: Long,
    val source: SegmentSource,
    val step: String? = null,
)

@Serializable
enum class SegmentKind {
    @SerialName("set") SET,
    @SerialName("rest") REST,
    @SerialName("other") OTHER,
    @SerialName("calibration") CALIBRATION,
}

@Serializable
data class Consent(val trainingUse: Boolean, val recordedAtUtc: String)

@Serializable
data class LabelsApp(
    @SerialName("package") val packageName: String,
    val versionName: String,
)

/** One entry of `labels.json` `segments`. `tStartNs`/`tEndNs` are overrides (null = use the watch's). */
@Serializable
data class SegmentLabel(
    val segmentId: Int,
    val kind: SegmentKind,
    val exercise: String? = null,
    val loadKg: Double? = null,
    val category: String? = null,
    val repsUser: Int? = null,
    val rpe: Int? = null,
    val note: String = "",
    val tStartNs: Long? = null,
    val tEndNs: Long? = null,
)

/** `labels.json`, written by the phone. */
@Serializable
data class Labels(
    val labelsFormat: Int = LABELS_FORMAT_VERSION,
    val sessionId: String,
    val participantId: String,
    val consent: Consent,
    val segments: List<SegmentLabel>,
    val app: LabelsApp? = null,
)

fun Meta.toJson(): String = ProtocolJson.encodeToString(Meta.serializer(), this) + "\n"

fun Labels.toJson(): String = ProtocolJson.encodeToString(Labels.serializer(), this) + "\n"
