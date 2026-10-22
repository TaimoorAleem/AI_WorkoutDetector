package dev.proprio.wear

import dev.proprio.wear.sensor.SelectionReason
import dev.proprio.wear.sensor.SensorCandidate
import dev.proprio.wear.sensor.SensorSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensorSelectorTest {
    private fun pick(list: List<SensorCandidate>, default: SensorCandidate?) =
        SensorSelector.select(list, default) { it }

    // Galaxy Watch5 Pro (SM-R920) gyroscope-type sensors, from dumpsys sensorservice.
    private val lsm = SensorCandidate("LSM6DSO Gyroscope", "STMicroelectronics")
    private val corrected = SensorCandidate("Corrected Gyroscope Sensor", "AOSP")

    @Test
    fun watch5ProPicksHardwareGyroEvenWhenVirtualIsTheDefaultOrListedFirst() {
        for (list in listOf(listOf(corrected, lsm), listOf(lsm, corrected))) {
            val sel = pick(list, default = corrected)!!
            assertEquals(lsm, sel.sensor)
            assertEquals(SelectionReason.HARDWARE, sel.reason)
        }
    }

    @Test
    fun watch5ProAccelerometerIsTheLsm() {
        val acc = SensorCandidate("LSM6DSO Accelerometer", "STMicroelectronics")
        assertEquals(acc, pick(listOf(acc), acc)!!.sensor)
    }

    @Test
    fun correctedNameIsRejectedEvenWithOtherVendor() {
        val c = SensorCandidate("Corrected Gyroscope", "Samsung")
        assertEquals(SelectionReason.DEFAULT_FALLBACK, pick(listOf(c), default = c)!!.reason)
    }

    @Test
    fun fallsBackToDefaultWhenNoHardwareSensorMatches() {
        val sel = pick(listOf(corrected), default = corrected)!!
        assertEquals(corrected, sel.sensor)
        assertEquals(SelectionReason.DEFAULT_FALLBACK, sel.reason)
    }

    @Test
    fun nonWakeUpHardwareSensorIsPreferred() {
        val wake = SensorCandidate("X Gyro Wake-up", "STM", isWakeUp = true)
        val plain = SensorCandidate("X Gyro", "STM")
        assertEquals(plain, pick(listOf(wake, plain), null)!!.sensor)
        assertEquals(wake, pick(listOf(wake), null)!!.sensor)
    }

    @Test
    fun noSensorsAndNoDefaultIsNull() {
        assertNull(pick(emptyList(), null))
    }
}
