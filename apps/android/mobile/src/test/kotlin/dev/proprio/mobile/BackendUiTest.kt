package dev.proprio.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.proprio.api.models.LoggedSet
import dev.proprio.api.models.Exercise
import dev.proprio.backend.AnalysisView
import dev.proprio.backend.AuthState
import dev.proprio.backend.RemoteSession
import dev.proprio.backend.SegmentRow
import dev.proprio.backend.UploadPhase
import dev.proprio.backend.UploadState
import dev.proprio.api.models.SessionStatus
import dev.proprio.mobile.ui.AccountSection
import dev.proprio.mobile.ui.AuthScreen
import dev.proprio.mobile.ui.BackendPanel
import dev.proprio.mobile.ui.HistoryScreen
import dev.proprio.mobile.ui.ProprioTheme
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Compose UI tests (Robolectric) for the sign-in, analysis, history and account screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackendUiTest {
    @get:Rule val compose = createComposeRule()

    private fun row(id: Int, user: String?, predicted: String?, conf: Double?, predReps: Int?, userReps: Int?) =
        SegmentRow(id, "set", id * 10.0, id * 10.0 + 8, user, predicted, conf, predReps, userReps, 60.0)

    private val analyzed = SessionBackendState(
        signedIn = true,
        upload = UploadState(UploadPhase.ANALYZED, remoteUploaded = true),
        remote = RemoteSession(
            SessionStatus.ANALYZED, null,
            AnalysisView(
                SessionStatus.ANALYZED, "proprio-classifier 3",
                listOf(row(1, "bench", "bench", 0.9, 5, 5), row(2, "squat", "dead", 0.75, 5, 8)),
            ),
        ),
    )

    @Test
    fun analysis_showsLabelVsPrediction_confidence_andRepDifference() {
        compose.setContent { ProprioTheme { BackendPanel(analyzed, onSignIn = {}, onUpload = {}, onRefresh = {}) } }

        compose.onNodeWithTag("backend_status").assertTextEquals("Analyzed")
        compose.onNodeWithTag("predicted_1").assertTextEquals("Bench (90%)")
        compose.onNodeWithTag("predicted_2").assertTextEquals("Deadlift (75%)")
        compose.onNodeWithText("Model: proprio-classifier 3").assertExists()
        compose.onNodeWithText("Squat").assertExists() // the user's own label for set 2
        compose.onNodeWithText("8 reps, 60 kg").assertExists() // logged
        compose.onNodeWithText("5 reps (-3)").assertExists() // predicted, off by three
        compose.onNodeWithTag("backend_refresh").assertIsEnabled()
    }

    @Test
    fun failedAnalysis_showsTheErrorCode() {
        val failed = SessionBackendState(
            signedIn = true,
            upload = UploadState(UploadPhase.FAILED, remoteUploaded = true, error = "unsupported_device"),
            remote = RemoteSession(SessionStatus.FAILED, "unsupported_device", null),
        )
        compose.setContent { ProprioTheme { BackendPanel(failed, onSignIn = {}, onUpload = {}, onRefresh = {}) } }

        compose.onNodeWithTag("backend_status").assertTextEquals("Analysis failed: unsupported_device")
        compose.onNodeWithTag("analysis_error").assertTextEquals("Error code: unsupported_device")
    }

    @Test
    fun uploadStates_driveTheStatusLineAndTheButton() {
        var uploads = 0
        var state by androidx.compose.runtime.mutableStateOf(SessionBackendState(signedIn = true, upload = null))
        compose.setContent { ProprioTheme { BackendPanel(state, onSignIn = {}, onUpload = { uploads++ }, onRefresh = {}) } }

        compose.onNodeWithTag("backend_status").assertTextEquals("Not uploaded yet")
        compose.onNodeWithTag("upload_now").performClick()
        assertEquals(1, uploads)

        state = state.copy(upload = UploadState(UploadPhase.UPLOADING))
        compose.onNodeWithTag("backend_status").assertTextEquals("Uploading...")
        compose.onNodeWithTag("upload_now").assertIsNotEnabled()

        state = state.copy(upload = UploadState(UploadPhase.PENDING, error = "Storage answered HTTP 503"))
        compose.onNodeWithTag("backend_status").assertTextContains("Upload pending", substring = true)

        state = state.copy(upload = UploadState(UploadPhase.FAILED, error = "Upload too large."))
        compose.onNodeWithTag("backend_status").assertTextEquals("Upload failed: Upload too large.")
        compose.onNodeWithText("Retry upload").assertExists()

        state = state.copy(upload = UploadState(UploadPhase.UPLOADED, remoteUploaded = true))
        compose.onNodeWithTag("backend_status").assertTextEquals("Uploaded, analysis in progress")
    }

    @Test
    fun signedOut_offersSignIn() {
        var signIns = 0
        compose.setContent { ProprioTheme { BackendPanel(SessionBackendState(), onSignIn = { signIns++ }, onUpload = {}, onRefresh = {}) } }

        compose.onNodeWithTag("backend_status").assertTextEquals("Not signed in")
        compose.onNodeWithTag("backend_sign_in").performClick()
        assertEquals(1, signIns)
    }

    @Test
    fun login_submitsTrimmedEmailAndPassword_andShowsServerErrors() {
        val calls = mutableListOf<Pair<String, String>>()
        var state by androidx.compose.runtime.mutableStateOf(AuthUiState())
        compose.setContent {
            ProprioTheme {
                AuthScreen(state, "http://10.0.2.2:8080", "P01", false, { e, p -> calls += e to p }, { _, _, _, _ -> }, {})
            }
        }
        compose.onNodeWithTag("auth_submit").assertIsNotEnabled()

        compose.onNodeWithTag("auth_email").performTextInput(" me@example.com ")
        compose.onNodeWithTag("auth_password").performTextInput("secret")
        compose.onNodeWithTag("auth_submit").assertIsEnabled().performClick()

        assertEquals(listOf("me@example.com" to "secret"), calls)
        compose.onNodeWithTag("auth_server").assertTextContains("http://10.0.2.2:8080", substring = true)
        state = AuthUiState(error = "Wrong email or password.")
        compose.onNodeWithTag("auth_error").assertTextEquals("Wrong email or password.")
    }

    @Test
    fun register_needsTwelveCharacterPassword_andPassesParticipantAndConsent() {
        var registered: List<Any>? = null
        compose.setContent {
            ProprioTheme {
                AuthScreen(AuthUiState(), "https://api.example.com", "P01", false, { _, _ -> }, { e, p, pid, c -> registered = listOf(e, p, pid, c) }, {})
            }
        }
        compose.onNodeWithTag("auth_toggle").performClick()
        compose.onNodeWithTag("auth_email").performTextInput("me@example.com")
        compose.onNodeWithTag("auth_password").performTextInput("short-pw")
        compose.onNodeWithTag("auth_submit").assertIsNotEnabled()

        compose.onNodeWithTag("auth_password").performTextInput("-long-enough")
        compose.onNodeWithTag("auth_consent").performScrollTo().performClick()
        compose.onNodeWithTag("auth_submit").performScrollTo().assertIsEnabled().performClick()

        assertEquals(listOf("me@example.com", "short-pw-long-enough", "P01", true), registered)
    }

    @Test
    fun history_listsSetsWithSource_andPromptsSignInWhenSignedOut() {
        val id = UUID.randomUUID()
        val set = LoggedSet(id, 2, OffsetDateTime.parse("2026-01-02T10:30:00Z"), Exercise.dead, LoggedSet.Source.model, 3, BigDecimal("100"), null)
        var state by androidx.compose.runtime.mutableStateOf(HistoryUiState(signedIn = true, sets = listOf(set)))
        compose.setContent { ProprioTheme { HistoryScreen(state, onRefresh = {}, onSignIn = {}) } }

        compose.onNodeWithTag("history_${id}_2").assertExists()
        compose.onNodeWithText("Deadlift").assertExists()
        compose.onNodeWithText("2026-01-02 10:30  -  3 reps  -  100 kg  -  detected by the model").assertExists()

        state = HistoryUiState(signedIn = false)
        compose.onNodeWithTag("history_sign_in").assertExists()
    }

    @Test
    fun account_validatesServerAddress_andSignsOut() {
        var saved: String? = null
        var out = 0
        var auth by androidx.compose.runtime.mutableStateOf<AuthState>(AuthState.SignedIn("me@example.com"))
        compose.setContent {
            ProprioTheme {
                AccountSection(
                    auth, serverUrl = "", defaultServerUrl = "https://api.example.com",
                    urlProblem = { if (it.startsWith("https://")) null else "Use an https:// address." },
                    onSaveServerUrl = { saved = it }, onSignIn = {}, onSignOut = { out++ },
                )
            }
        }
        compose.onNodeWithTag("account_status").assertTextEquals("Signed in as me@example.com")
        compose.onNodeWithTag("server_url").performTextInput("http://192.168.1.20:8080")
        compose.onNodeWithText("Use an https:// address.").assertExists()
        compose.onNodeWithTag("save_server").assertIsNotEnabled()

        compose.onNodeWithTag("sign_out").performClick()
        assertEquals(1, out)
        auth = AuthState.SignedOut
        compose.onNodeWithTag("sign_in").assertExists()
        assertTrue(saved == null)
    }
}
