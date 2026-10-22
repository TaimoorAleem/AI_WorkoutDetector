package dev.proprio.wear

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.wear.ongoing.OngoingActivity
import dev.proprio.protocol.SegmentSource
import dev.proprio.protocol.SessionKind
import dev.proprio.wear.platform.RecordingService
import dev.proprio.wear.sensor.SensorRecorder
import dev.proprio.wear.session.ExerciseHost
import dev.proprio.wear.session.RecordingController
import dev.proprio.wear.session.Strategy
import dev.proprio.wear.session.StrategyHolder
import dev.proprio.wear.transfer.TransferManager
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class StrategySelectionTest {
    @Test
    fun parseIsCaseInsensitiveAndRejectsUnknown() {
        assertEquals(Strategy.ALL, Strategy.parse("ALL"))
        assertEquals(Strategy.NONE, Strategy.parse(" none "))
        assertNull(Strategy.parse("bogus"))
        assertNull(Strategy.parse(null))
    }

    @Test
    fun eachStrategyEnablesExactlyItsMechanisms() {
        fun flags(s: Strategy) = Triple(s.ongoing, s.ambient, s.exercise)
        assertEquals(Triple(false, false, false), flags(Strategy.NONE))
        assertEquals(Triple(true, false, false), flags(Strategy.ONGOING))
        assertEquals(Triple(false, true, false), flags(Strategy.AMBIENT))
        assertEquals(Triple(false, false, true), flags(Strategy.EXERCISE))
        assertEquals(Triple(true, true, true), flags(Strategy.ALL))
        assertEquals(Strategy.ALL, Strategy.DEFAULT)
        assertEquals(Strategy.ALL, StrategyHolder().current)
    }

    @Test
    fun holderPublishesChanges() {
        val h = StrategyHolder()
        h.current = Strategy.NONE
        assertEquals(Strategy.NONE, h.changes.value)
    }
}

/** Records calls so tests can check the exercise life cycle. */
private class FakeExercise(var startResult: Boolean = true, var startThrows: Boolean = false) : ExerciseHost {
    val calls = mutableListOf<String>()
    override suspend fun start(): Boolean {
        calls += "start"
        if (startThrows) throw IllegalStateException("boom")
        return startResult
    }

    override suspend fun stop() {
        calls += "stop"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ExerciseLifecycleTest {
    private class H(t: TestScope, strategy: Strategy, val exercise: FakeExercise) {
        val root: File = createTempDirectory("ex").toFile()
        val host = FakeHost()
        val phone = FakePhone()
        val clock = FakeClock(scheduler = t.testScheduler)
        val transfer = TransferManager(File(root, "sessions"), File(root, "outbox"), FakeSender(), FakeNodes())
        val controller = RecordingController(
            recorder = SensorRecorder(FakeSensorSource(), clock, StandardTestDispatcher(t.testScheduler)),
            clock = clock, transfer = transfer, phone = phone, haptics = FakeHaptics(), host = host,
            app = testApp, device = testDevice, scope = t.backgroundScope,
            newSessionId = { "sess-1" },
            exercise = exercise, strategy = { strategy },
        )
    }

    @Test
    fun exerciseStartsAfterForegroundAndStopsAtSessionEnd() = runTest {
        val ex = FakeExercise()
        val h = H(this, Strategy.EXERCISE, ex)
        assertTrue(h.controller.startSession(SessionKind.GYM))
        assertEquals(listOf("start"), ex.calls)
        advanceTimeBy(1_000)
        assertTrue(h.controller.endSession(SegmentSource.WATCH))
        assertEquals(listOf("start", "stop"), ex.calls)
        assertEquals(1, h.host.exits)
    }

    @Test
    fun allStrategyAlsoRunsTheExercise() = runTest {
        val ex = FakeExercise()
        val h = H(this, Strategy.ALL, ex)
        h.controller.startSession(SessionKind.GYM)
        h.controller.endSession(SegmentSource.WATCH)
        assertEquals(listOf("start", "stop"), ex.calls)
    }

    @Test
    fun strategiesWithoutExerciseNeverTouchHealthServices() = runTest {
        for (s in listOf(Strategy.NONE, Strategy.ONGOING, Strategy.AMBIENT)) {
            val ex = FakeExercise()
            val h = H(this, s, ex)
            h.controller.startSession(SessionKind.GYM)
            h.controller.endSession(SegmentSource.WATCH)
            assertEquals("strategy $s", emptyList<String>(), ex.calls)
        }
    }

    @Test
    fun deniedOrFailingExerciseDoesNotStopTheRecording() = runTest {
        for (ex in listOf(FakeExercise(startResult = false), FakeExercise(startThrows = true))) {
            val h = H(this, Strategy.ALL, ex)
            assertTrue(h.controller.startSession(SessionKind.GYM))
            assertTrue(h.controller.state.value.recording)
            h.controller.endSession(SegmentSource.WATCH)
            // a start that did not take effect is not stopped again at the end
            assertEquals(listOf("start"), ex.calls)
        }
    }

    @Test
    fun appStartEndsAnOrphanedExerciseButNotWhileRecording() = runTest {
        val ex = FakeExercise()
        val h = H(this, Strategy.EXERCISE, ex)
        h.controller.resumeTransfers() // crash recovery: nothing recording
        assertEquals(listOf("stop"), ex.calls)

        h.controller.startSession(SessionKind.GYM)
        h.controller.resumeTransfers() // e.g. capability change mid-session
        assertEquals(listOf("stop", "start"), ex.calls)
        h.controller.endSession(SegmentSource.WATCH)
        assertEquals(listOf("stop", "start", "stop"), ex.calls)
    }

    @Test
    fun failedStartDoesNotLeaveAnExercise() = runTest {
        val ex = FakeExercise()
        val h = H(this, Strategy.EXERCISE, ex)
        h.host.allow = false
        assertFalse(h.controller.startSession(SessionKind.GYM))
        assertEquals(emptyList<String>(), ex.calls)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OngoingActivityTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    private fun post(ongoing: Boolean): OngoingActivity? {
        RecordingService.ensureChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.notify(RecordingService.NOTIFICATION_ID, RecordingService.buildNotification(ctx, "Recording 0:05", ongoing))
        return OngoingActivity.recoverOngoingActivity(ctx)
    }

    @Test
    fun notificationCarriesTheOngoingActivityWithWorkoutCategoryAndTouchIntent() {
        val oa = post(ongoing = true)
        assertNotNull(oa)
        assertEquals(RecordingService.NOTIFICATION_ID, oa!!.notificationId)
        assertEquals(androidx.core.app.NotificationCompat.CATEGORY_WORKOUT, oa.category)
        assertNotNull(oa.touchIntent)
    }

    @Test
    fun withoutTheStrategyThereIsNoOngoingActivity() {
        assertNull(post(ongoing = false))
    }
}
