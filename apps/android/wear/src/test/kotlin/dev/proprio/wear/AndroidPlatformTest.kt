package dev.proprio.wear

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import dev.proprio.wear.platform.RecordingService
import dev.proprio.wear.sensor.AndroidSensorSource
import dev.proprio.wear.sensor.TYPE_ACC
import dev.proprio.wear.sensor.TYPE_GYR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidSensorSourceTest {
    private fun manager(): SensorManager {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    @Test
    fun describeMapsEverySensorField() {
        val sm = manager()
        val acc = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER)
        val s = shadowOf(acc)
        s.setName("LSM6DSO Accelerometer")
        s.setMaximumRange(78.4f)
        s.setMinDelay(2500)
        s.setWakeUpFlag(false)
        for ((f, v) in mapOf<String, Any>(
            "mVendor" to "STMicro", "mVersion" to 2, "mResolution" to 0.0024f,
            "mMaxDelay" to 500_000, "mFifoReservedEventCount" to 100, "mFifoMaxEventCount" to 3000,
        )) {
            Sensor::class.java.getDeclaredField(f).apply { isAccessible = true }.set(acc, v)
        }
        shadowOf(sm).addSensor(acc)

        val info = AndroidSensorSource(sm).describe(TYPE_ACC)!!
        assertEquals(1, info.type)
        assertEquals("LSM6DSO Accelerometer", info.name)
        assertEquals("STMicro", info.vendor)
        assertEquals(2, info.version)
        assertEquals(0.0024, info.resolution, 1e-6)
        assertEquals(78.4, info.maximumRange, 1e-4)
        assertEquals(2500, info.minDelayUs)
        assertEquals(500_000, info.maxDelayUs)
        assertEquals(100, info.fifoReserved)
        assertEquals(3000, info.fifoMax)
        assertEquals(false, info.isWakeUp)
        assertNull(AndroidSensorSource(sm).describe(TYPE_GYR)) // not installed in this fake device
    }

    @Test
    fun startRegistersBothSensorsAndStopUnregisters() {
        val sm = manager()
        shadowOf(sm).addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
        shadowOf(sm).addSensor(ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE))
        val src = AndroidSensorSource(sm)
        assertTrue(src.start(20_000, 0) { _, _, _, _, _ -> })
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        assertNotNull(acc)
        src.stop()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingServiceTest {
    @Test
    fun serviceGoesForegroundWithHealthTypeOngoingNotificationAndWakeLock() {
        val controller = Robolectric.buildService(RecordingService::class.java).create()
        val service = controller.get()
        val shadow = shadowOf(service)
        val n = shadow.lastForegroundNotification
        assertNotNull(n)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val info = ctx.packageManager.getServiceInfo(android.content.ComponentName(ctx, RecordingService::class.java), 0)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH, info.foregroundServiceType)
        assertNotNull(ctx.getSystemService(NotificationManager::class.java).getNotificationChannel(RecordingService.CHANNEL_ID))
        assertTrue(org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock().isHeld)
        controller.destroy()
        assertTrue(!org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock().isHeld)
    }
}
