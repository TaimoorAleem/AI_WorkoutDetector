package dev.proprio.wear

import dev.proprio.protocol.AppInfo
import dev.proprio.protocol.DeviceInfo
import dev.proprio.protocol.Meta
import dev.proprio.protocol.SensorInfo
import dev.proprio.wear.sensor.SensorSink
import dev.proprio.wear.sensor.SensorSource
import dev.proprio.wear.sensor.TYPE_ACC
import dev.proprio.wear.sensor.TYPE_GYR
import dev.proprio.wear.session.ForegroundHost
import dev.proprio.wear.session.Haptics
import dev.proprio.wear.session.PhoneLink
import dev.proprio.wear.transfer.ChannelSender
import dev.proprio.wear.transfer.NodeFinder
import java.io.File
import kotlinx.coroutines.test.TestCoroutineScheduler

/** Shared ordered log of "what happened", for order assertions. */
class CallLog {
    val entries = mutableListOf<String>()
    fun add(s: String) { entries += s }
}

class FakeClock(
    private val log: CallLog? = null,
    var elapsedNs: Long = 1_000_000_000L,
    var unixMs: Long = 1_700_000_000_000L,
    private val scheduler: TestCoroutineScheduler? = null,
) : DeviceClock {
    private val base = elapsedNs
    override fun elapsedRealtimeNs(): Long {
        log?.add("elapsed")
        return if (scheduler != null) base + scheduler.currentTime * 1_000_000L else elapsedNs
    }

    override fun unixTimeMs(): Long {
        log?.add("unix")
        return if (scheduler != null) unixMs + scheduler.currentTime else unixMs
    }
}

class FakeSensorSource(private val log: CallLog? = null, var startOk: Boolean = true) : SensorSource {
    var sink: SensorSink? = null
    var startedPeriodUs = -1
    var startedLatencyUs = -1
    var stopCount = 0

    val accInfo = SensorInfo(TYPE_ACC, "fake acc", "ACME", 3, 0.0023, 78.4, 5000, 500_000, 10, 3000, 0, 0, false)
    val gyrInfo = SensorInfo(TYPE_GYR, "fake gyr", "ACME", 2, 0.0012, 34.9, 5000, 500_000, 0, 1500, 0, 0, true)

    override fun describe(type: Int) = when (type) {
        TYPE_ACC -> accInfo
        TYPE_GYR -> gyrInfo
        else -> null
    }

    override fun start(periodUs: Int, maxReportLatencyUs: Int, sink: SensorSink): Boolean {
        log?.add("register")
        startedPeriodUs = periodUs
        startedLatencyUs = maxReportLatencyUs
        this.sink = sink
        return startOk
    }

    override fun stop() {
        log?.add("unregister")
        stopCount++
        sink = null
    }

    fun acc(tNs: Long, x: Float = 0f, y: Float = 0f, z: Float = 9.81f) = sink!!.onSample(TYPE_ACC, tNs, x, y, z)
    fun gyr(tNs: Long, x: Float = 0f, y: Float = 0f, z: Float = 0f) = sink!!.onSample(TYPE_GYR, tNs, x, y, z)
}

class FakeHaptics : Haptics {
    val events = mutableListOf<String>()
    override fun startBuzz() { events += "start" }
    override fun stopBuzz() { events += "stop" }
    override fun cue() { events += "cue" }
}

class FakeHost(var allow: Boolean = true) : ForegroundHost {
    var enters = 0
    var exits = 0
    override fun enterForeground(): Boolean { enters++; return allow }
    override fun exitForeground() { exits++ }
}

class FakePhone : PhoneLink {
    val sent = mutableListOf<Pair<String, ByteArray>>()
    override suspend fun sendMessage(path: String, payload: ByteArray) { sent += path to payload }
    fun paths(path: String) = sent.filter { it.first == path }.map { it.second }
}

class FakeSender : ChannelSender {
    val sends = mutableListOf<Triple<String, String, File>>()
    var fail = false
    override suspend fun send(nodeId: String, path: String, file: File) {
        if (fail) throw java.io.IOException("channel closed")
        sends += Triple(nodeId, path, file)
    }
}

class FakeNodes(var ids: List<String> = listOf("phone-1")) : NodeFinder {
    override suspend fun mobileNodeIds() = ids
}

val testApp = AppInfo("dev.proprio.wear", "0.1.0", 1)
val testDevice = DeviceInfo("Samsung", "SM-L300", "galaxywatch", 34, "14", "fp/1")

fun File.lines(name: String): List<String> = File(this, name).readText().split("\n").filter { it.isNotEmpty() }

fun Meta.hasSensorKeys() = sensors.keys == setOf("acc", "gyr")
