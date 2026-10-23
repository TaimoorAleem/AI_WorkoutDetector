package dev.proprio.wear

import android.content.Context
import android.hardware.SensorManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.proprio.protocol.SessionKind
import dev.proprio.protocol.SessionReader
import dev.proprio.wear.sensor.AndroidSensorSource
import dev.proprio.wear.sensor.SensorRecorder
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for a real watch or emulator: record 3 s of real sensor data with the real
 * [AndroidSensorSource] and check the session reads back. Needs no UI interaction.
 */
@RunWith(AndroidJUnit4::class)
class RecordingSmokeTest {
    @Test
    fun recordsThreeSecondsOfRealSensorData() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rec = SensorRecorder(AndroidSensorSource(sm), SystemDeviceClock)
        val dir = File(ctx.cacheDir, "smoke-${System.nanoTime()}")
        rec.start(this, dir, "smoke", SessionKind.GYM, AppInfoForTest.app(ctx), dev.proprio.wear.platform.AppGraph.deviceInfo())
        Thread.sleep(3_000)
        val snap = rec.snapshot()
        val meta = rec.stop()
        val read = SessionReader.readDir(dir)
        assertTrue(meta.complete)
        val gyrName = meta.sensors.getValue("gyr").name
        android.util.Log.i("ProprioSmoke", "gyr=$gyrName acc=${meta.sensors.getValue("acc").name} rate=${snap.accRateHz}/${snap.gyrRateHz}")
        assertTrue("virtual gyro chosen: $gyrName", !gyrName.contains("Corrected"))
        assertTrue("acc rows ${read.acc.size}", read.acc.size > 30)
        assertTrue("gyr rows ${read.gyr.size}", read.gyr.size > 30)
        assertTrue("acc rate ${snap.accRateHz}", snap.accRateHz > 10)
        dir.deleteRecursively()
    }
}

private object AppInfoForTest {
    fun app(ctx: Context) = dev.proprio.wear.platform.AppGraph.appInfo(ctx)
}
