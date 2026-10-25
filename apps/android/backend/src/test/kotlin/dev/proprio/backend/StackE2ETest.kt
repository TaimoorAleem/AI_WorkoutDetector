package dev.proprio.backend

import dev.proprio.api.models.SessionStatus
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Drives the phone's backend layer (auth, generated client, [UploadCore], [AnalysisRepository]) against the real
 * docker-compose stack. Skipped unless `PROPRIO_E2E_BASE_URL` is set, so it never runs in CI:
 *
 *     docker compose -f deploy/compose.yaml up --build -d --wait
 *     PROPRIO_E2E_BASE_URL=http://localhost:8080 ./gradlew :backend:test --tests '*StackE2ETest*'
 */
class StackE2ETest {
    private val baseUrl = System.getProperty("proprio.e2e.baseUrl").orEmpty()

    @Test
    fun registerUploadGoldenSession_untilAnalyzed_thenHistory() = runBlocking {
        assumeTrue(baseUrl.isNotBlank(), "PROPRIO_E2E_BASE_URL not set; stack E2E skipped")
        val zip = goldenZip()
        val labels = ZipFile(zip).use { z -> z.getInputStream(z.getEntry("labels.json")).readBytes().toString(Charsets.UTF_8) }
        val files = object : SessionFiles {
            override fun zip(sessionId: String): File = zip
            override fun labelsText(sessionId: String) = labels
        }
        val secrets = MemorySecretStore()
        val svc = BackendService(secrets, { baseUrl }, allowCleartext = true)
        val email = "phone-e2e-${System.nanoTime()}@example.com"

        assertEquals(AuthResult.Success, svc.register(email, "correct-horse-battery", "P01", trainingConsent = true))

        // An expired access token must be transparently refreshed (rotating the refresh token) mid-flow.
        val firstRefresh = svc.auth.refreshToken
        svc.auth.signIn(null, tokens("expired-access-token", firstRefresh!!))

        val states = MemoryUploadStateStore()
        val core = UploadCore({ svc.client() }, states, files)
        assertEquals(UploadOutcome.Done, core.upload(GOLDEN_ID))
        assertTrue(states.get(GOLDEN_ID)!!.remoteUploaded)
        assertTrue(svc.auth.refreshToken != firstRefresh, "refresh token should have rotated")
        println("E2E upload ok; phase=${states.get(GOLDEN_ID)!!.phase}")

        // Re-running is idempotent: no new request needed, state unchanged.
        assertEquals(UploadOutcome.Done, core.upload(GOLDEN_ID))

        val repo = AnalysisRepository({ svc.client() }, states)
        var remote: RemoteSession? = null
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            val r = repo.refresh(GOLDEN_ID)
            check(r is AnalysisRepository.Result.Ok) { "refresh failed: $r" }
            remote = r.remote
            println("E2E poll: ${remote.status}")
            if (!remote.pending) break
            delay(2_000)
        }
        assertEquals(SessionStatus.ANALYZED, remote?.status, "failure=${remote?.failureReason}")
        val rows = remote!!.analysis!!.rows
        rows.forEach { println("E2E segment: $it") }
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.any { it.userExercise != null && it.userReps != null }, "user labels should be merged into the analysis")
        assertEquals(UploadPhase.ANALYZED, states.get(GOLDEN_ID)!!.phase)

        val history = HistoryRepository({ svc.client() }).load(LocalDate.of(2025, 6, 16), days = 60)
        check(history is HistoryRepository.Result.Ok) { history.toString() }
        history.sets.forEach { println("E2E history: ${it.date} ${it.exercise} reps=${it.reps} source=${it.source}") }
        assertTrue(history.sets.isNotEmpty())

        svc.logout()
        assertTrue(!svc.auth.signedIn)
    }
}
