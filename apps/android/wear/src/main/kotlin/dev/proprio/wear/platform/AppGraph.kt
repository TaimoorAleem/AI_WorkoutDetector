package dev.proprio.wear.platform

import android.content.Context
import android.content.Intent
import android.hardware.SensorManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.content.ContextCompat
import dev.proprio.protocol.AppInfo
import dev.proprio.protocol.DeviceInfo
import dev.proprio.wear.SystemDeviceClock
import dev.proprio.wear.sensor.AndroidSensorSource
import dev.proprio.wear.sensor.SensorRecorder
import dev.proprio.wear.session.CommandHandler
import dev.proprio.wear.session.ForegroundHost
import dev.proprio.wear.session.Haptics
import dev.proprio.wear.session.RecordingController
import dev.proprio.wear.transfer.TransferManager
import dev.proprio.wear.transfer.WearTransport
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private const val TAG = "Proprio"

fun appLog(msg: String) {
    // Log.* is a no-op stub-free call on device; under plain JVM tests this graph is not constructed.
    Log.i(TAG, msg)
}

/** Wiring for the real app. Built once in [dev.proprio.wear.ProprioApp]. */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transport = WearTransport(appContext)
    val transfer = TransferManager(
        sessionsDir = File(appContext.filesDir, "sessions"),
        outboxDir = File(appContext.filesDir, "outbox"),
        sender = transport,
        nodes = transport,
        log = ::appLog,
    )
    val controller = RecordingController(
        recorder = SensorRecorder(
            AndroidSensorSource(appContext.getSystemService(SensorManager::class.java), ::appLog),
            SystemDeviceClock,
            log = ::appLog,
        ),
        clock = SystemDeviceClock,
        transfer = transfer,
        phone = transport,
        haptics = VibratorHaptics(appContext),
        host = ServiceHost(appContext),
        app = appInfo(appContext),
        device = deviceInfo(),
        scope = scope,
        log = ::appLog,
    )
    val commands = CommandHandler(controller, ::appLog)

    companion object {
        fun appInfo(context: Context): AppInfo {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            return AppInfo(context.packageName, pi.versionName ?: "unknown", pi.longVersionCode.toInt())
        }

        fun deviceInfo() = DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            product = Build.PRODUCT,
            sdkInt = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE,
            fingerprint = Build.FINGERPRINT,
        )
    }
}

class ServiceHost(private val context: Context) : ForegroundHost {
    override fun enterForeground(): Boolean = try {
        ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException when the app is in the background (API 31+).
        appLog("cannot start foreground service: $e")
        false
    }

    override fun exitForeground() {
        context.stopService(Intent(context, RecordingService::class.java))
    }
}

class VibratorHaptics(context: Context) : Haptics {
    private val vibrator: Vibrator? = context.getSystemService(Vibrator::class.java)

    private fun play(pattern: LongArray) {
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    override fun startBuzz() = play(longArrayOf(0, 120))
    override fun stopBuzz() = play(longArrayOf(0, 80, 80, 80))
    override fun cue() = play(longArrayOf(0, 200, 100, 200, 100, 200))
}
