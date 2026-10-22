package dev.proprio.mobile

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import dev.proprio.mobile.TestSupport.SID
import dev.proprio.mobile.data.FakeWatchLink
import dev.proprio.mobile.data.LabelHeader
import dev.proprio.mobile.data.MemorySettingsStore
import dev.proprio.mobile.data.SessionImporter
import dev.proprio.mobile.data.SessionStore
import dev.proprio.mobile.data.UserSettings
import dev.proprio.mobile.domain.LabelEditor
import dev.proprio.mobile.ui.ProprioTheme
import dev.proprio.mobile.ui.RecordingsScreen
import dev.proprio.mobile.ui.ReviewScreen
import dev.proprio.mobile.ui.SessionScreen
import dev.proprio.mobile.ui.SettingsScreen
import dev.proprio.protocol.Consent
import dev.proprio.protocol.FILE_LABELS
import dev.proprio.protocol.SegmentKind
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Compose UI tests on Robolectric (no emulator, no watch). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComposeUiTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @After fun tearDown() = scope.cancel()

    @Test
    fun labelingFlow_startSet_stop_repPicker_labelStored_zipArrives_labelsJsonValid() {
        val link = FakeWatchLink { SID }
        val store = SessionStore(File(tmp.root, "sessions"), header = { LabelHeader("P01", Consent(true, "2026-01-01T00:00:00Z")) })
        val coordinator = SessionCoordinator(
            scope, link, store, SessionImporter(store),
            MemorySettingsStore(UserSettings("P01", true, "2026-01-01T00:00:00Z", true)),
            io = Dispatchers.Unconfined,
        )
        compose.setContent { ProprioTheme { SessionScreen(coordinator) } }

        compose.onNodeWithTag("connection").assertExists()
        compose.onNodeWithText("Watch connected").assertExists()
        compose.onNodeWithText("Start gym session").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("recording").assertExists()

        compose.onNodeWithTag("exercise_squat").performClick()
        compose.onNodeWithTag("category_medium").performClick()
        compose.onNodeWithTag("load").performTextReplacement("100")
        compose.onNodeWithText("Start set").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Stop set").assertExists()
        compose.onNodeWithTag("open_segment").assertExists()

        compose.onNodeWithText("Stop set").performScrollTo().performClick()
        compose.waitForIdle()
        // rep picker is up and requires a rep count
        compose.onNodeWithTag("save_reps").assertIsNotEnabled()
        compose.onNodeWithTag("rep_5").performClick()
        compose.onNodeWithTag("rpe_8").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.onNodeWithTag("note").performTextReplacement("clean")
        compose.onNodeWithTag("save_reps").assertIsEnabled().performClick()
        compose.waitForIdle()

        val label = LabelEditor.find(store.readLabels(SID)!!, 1)!!
        assertEquals(SegmentKind.SET, label.kind)
        assertEquals("squat", label.exercise)
        assertEquals(100.0, label.loadKg!!, 0.0)
        assertEquals("medium", label.category)
        assertEquals(5, label.repsUser)
        assertEquals(8, label.rpe)
        assertEquals("clean", label.note)

        // the session zip arrives afterwards
        runBlocking { link.emitZip(SID, TestSupport.buildZip(tmp.newFolder())) }
        assertTrue(link.acks.single().ok)
        val json = File(store.dir(SID), FILE_LABELS).readText()
        assertEquals(emptyList<String>(), TestSupport.labelErrors(json))
        assertEquals(5, store.load(SID).resolvedSegments().first { it.segmentId == 1 }.repsUser)
    }

    @Test
    fun disconnectedWatchDisablesStart() {
        val link = FakeWatchLink { SID }.also { it.setConnected(false) }
        val store = SessionStore(File(tmp.root, "sessions"), header = { LabelHeader("P01", Consent(false, "x")) })
        val coordinator = SessionCoordinator(
            scope, link, store, SessionImporter(store), MemorySettingsStore(UserSettings("P01")), io = Dispatchers.Unconfined,
        )
        compose.setContent { ProprioTheme { SessionScreen(coordinator) } }
        compose.onNodeWithText("Watch not connected").assertExists()
        compose.onNodeWithText("Start gym session").assertIsNotEnabled()
    }

    @Test
    fun firstRunSettingsRequireIdAndLeftWristAck() {
        var saved: Triple<String, Boolean, Boolean>? = null
        compose.setContent {
            ProprioTheme { SettingsScreen(UserSettings(), firstRun = true, onSave = { a, b, c -> saved = Triple(a, b, c) }) }
        }
        compose.onNodeWithTag("wrist_reminder").assertExists()
        compose.onNodeWithTag("save_settings").assertIsNotEnabled()
        compose.onNodeWithTag("participant_id").performTextReplacement("P01")
        compose.onNodeWithTag("save_settings").assertIsNotEnabled() // wrist not acknowledged yet
        compose.onNodeWithTag("wrist_ack").performClick()
        compose.onNodeWithTag("consent").performClick()
        compose.onNodeWithTag("save_settings").assertIsEnabled().performClick()
        assertEquals(Triple("P01", true, true), saved)
    }

    @Test
    fun participantIdRejectsPii() {
        compose.setContent { ProprioTheme { SettingsScreen(UserSettings(), firstRun = true, onSave = { _, _, _ -> }) } }
        compose.onNodeWithTag("wrist_ack").performClick()
        compose.onNodeWithTag("participant_id").performTextReplacement("jane@example.com")
        compose.onNodeWithTag("save_settings").assertIsNotEnabled()
    }

    @Test
    fun recordingsListShowsStatusDateDurationAndSets() {
        val store = SessionStore(File(tmp.root, "sessions"), header = { LabelHeader("P01", Consent(true, "x")) })
        SessionImporter(store).import(SID, TestSupport.buildZip(tmp.newFolder(), complete = false))
        var opened: String? = null
        compose.setContent {
            ProprioTheme { RecordingsScreen(store.list(), onOpen = { opened = it }, onExport = {}) }
        }
        compose.onNodeWithText("incomplete, unlabeled").assertExists()
        compose.onNodeWithText("2026-01-01 10:00 UTC", substring = true).assertExists()
        compose.onNodeWithText("Duration 1:00", substring = true).assertExists()
        compose.onNodeWithTag("recording_$SID").performClick()
        assertEquals(SID, opened)
    }

    @Test
    fun reviewScreen_selectAndNudgeWritesOverride() {
        val store = SessionStore(File(tmp.root, "sessions"), header = { LabelHeader("P01", Consent(true, "x")) })
        SessionImporter(store).import(SID, TestSupport.buildZip(tmp.newFolder()))
        store.updateLabels(SID) { LabelEditor.put(LabelEditor.put(it, dev.proprio.protocol.SegmentLabel(1, SegmentKind.SET, "bench")), dev.proprio.protocol.SegmentLabel(2, SegmentKind.SET, "bench")) }
        Dispatchers.setMain(Dispatchers.Unconfined)
        val vm = ReviewViewModel(SID, store, dev.proprio.mobile.data.ExportBuilder(store, File(tmp.root, "exp")), io = Dispatchers.Unconfined)
        try {
            compose.setContent {
                val state = vm.state.collectAsState().value
                ProprioTheme { ReviewScreen(state, {}, vm::select, vm::applyEdit, vm::nudge, {}) }
            }
            compose.onNodeWithTag("segment_1").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("end_plus").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(TestSupport.sec(20.5), LabelEditor.find(store.readLabels(SID)!!, 1)!!.tEndNs)
            compose.onNodeWithTag("segment_-1").performScrollTo().assertExists() // implicit rest preview row
        } finally {
            Dispatchers.resetMain()
        }
    }
}
