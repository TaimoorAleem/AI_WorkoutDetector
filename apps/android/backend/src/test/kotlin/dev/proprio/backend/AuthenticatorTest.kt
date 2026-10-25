package dev.proprio.backend

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val USER =
    """{"id":"$GOLDEN_ID","email":"a@b.c","participantId":"P01","createdAt":"2026-01-01T10:00:00Z","consent":{"trainingUse":false,"recordedAt":"2026-01-01T10:00:00Z"}}"""

class AuthenticatorTest {
    private val secrets = MemorySecretStore()

    private fun service(s: ScriptedServer) = BackendService(secrets, { s.baseUrl }, allowCleartext = true).also {
        it.auth.signIn("a@b.c", tokens("old-access", "old-refresh"))
    }

    /** Resource server that accepts only `new-access`; refresh accepts only the current refresh token (reuse = 401). */
    private class Backend(val refreshStatus: Int = 200) {
        val refreshCalls = AtomicInteger()
        private var currentRefresh = "old-refresh"
        val handler: (RecordedRequest) -> MockResponse = { r ->
            when (r.url.encodedPath) {
                "/v1/me" -> if (r.headers["Authorization"] == "Bearer new-access") jsonResponse(200, USER) else status(401)
                "/v1/auth/refresh" -> synchronized(this) {
                    refreshCalls.incrementAndGet()
                    val sent = r.body!!.utf8()
                    if (refreshStatus != 200) {
                        status(refreshStatus)
                    } else if (!sent.contains("\"$currentRefresh\"")) {
                        status(401) // reuse of a rotated token
                    } else {
                        currentRefresh = "new-refresh"
                        jsonResponse(200, tokenJson("new-access", "new-refresh"))
                    }
                }
                else -> status(404)
            }
        }
    }

    @Test
    fun expiredAccessToken_isRefreshedOnce_requestRetried_andRefreshTokenRotated() = runBlocking {
        val b = Backend()
        ScriptedServer(b.handler).use { s ->
            val svc = service(s)

            val r = apiCall { svc.client().api.account.getMe() }

            assertTrue(r is ApiResult.Ok)
            assertEquals(1, b.refreshCalls.get())
            assertEquals(listOf("GET /v1/me", "POST /v1/auth/refresh", "GET /v1/me"), s.lines())
            assertEquals("new-refresh", secrets.get("refresh_token"))
            assertEquals("new-access", svc.auth.accessToken)
            assertTrue(svc.auth.signedIn)
        }
    }

    @Test
    fun concurrentUnauthorizedCalls_shareASingleRefresh() = runBlocking {
        val b = Backend()
        ScriptedServer(b.handler).use { s ->
            val svc = service(s)
            val api = svc.client().api.account

            val results = (1..8).map { async(Dispatchers.IO) { apiCall { api.getMe() } } }.awaitAll()

            assertTrue(results.all { it is ApiResult.Ok }, results.toString())
            // A second rotation with the old token would have been answered 401 (reuse detection) above.
            assertEquals(1, b.refreshCalls.get())
        }
    }

    @Test
    fun refreshRejected_signsOut_andClearsStoredToken() = runBlocking {
        val b = Backend(refreshStatus = 401)
        ScriptedServer(b.handler).use { s ->
            val svc = service(s)

            val r = apiCall { svc.client().api.account.getMe() }

            assertEquals(401, (r as ApiResult.HttpError).code)
            assertFalse(svc.auth.signedIn)
            assertNull(secrets.get("refresh_token"))
            assertNull(svc.auth.accessToken)
            assertEquals(1, b.refreshCalls.get())
        }
    }

    @Test
    fun refreshServerDown_failsTheRequest_butKeepsTheSession() = runBlocking {
        val b = Backend(refreshStatus = 503)
        ScriptedServer(b.handler).use { s ->
            val svc = service(s)

            val r = apiCall { svc.client().api.account.getMe() }

            assertTrue(r is ApiResult.NetworkError && r.cause is IOException, r.toString())
            assertTrue(svc.auth.signedIn)
            assertEquals("old-refresh", secrets.get("refresh_token"))
        }
    }

    @Test
    fun stillUnauthorizedAfterRefresh_givesUp_withoutLooping() = runBlocking {
        val refreshes = AtomicInteger()
        ScriptedServer { r ->
            if (r.url.encodedPath == "/v1/auth/refresh") {
                refreshes.incrementAndGet()
                jsonResponse(200, tokenJson("another", "r2"))
            } else {
                status(401)
            }
        }.use { s ->
            val svc = service(s)

            val r = apiCall { svc.client().api.account.getMe() }

            assertEquals(401, (r as ApiResult.HttpError).code)
            assertEquals(1, refreshes.get())
        }
    }

    @Test
    fun wrongPasswordOnLogin_doesNotTriggerARefresh() = runBlocking {
        ScriptedServer { status(401) }.use { s ->
            val svc = BackendService(secrets, { s.baseUrl }, allowCleartext = true)

            val r = svc.login("a@b.c", "bad")

            assertEquals(AuthResult.Failure("Wrong email or password."), r)
            assertEquals(listOf("POST /v1/auth/login"), s.lines())
        }
    }

    @Test
    fun login_storesTokens_logoutRevokesAndClears() = runBlocking {
        ScriptedServer { r ->
            when (r.url.encodedPath) {
                "/v1/auth/login" -> jsonResponse(200, tokenJson("acc", "ref"))
                "/v1/auth/logout" -> {
                    assertEquals("Bearer acc", r.headers["Authorization"])
                    status(204)
                }
                else -> status(404)
            }
        }.use { s ->
            val svc = BackendService(secrets, { s.baseUrl }, allowCleartext = true)
            assertEquals(AuthResult.Success, svc.login(" a@b.c ", "pw"))
            assertEquals(AuthState.SignedIn("a@b.c"), svc.auth.state.value)
            assertEquals("ref", secrets.get("refresh_token"))

            svc.logout()

            assertEquals(AuthState.SignedOut, svc.auth.state.value)
            assertNull(secrets.get("refresh_token"))
            assertEquals(1, s.count("POST /v1/auth/logout"))
        }
    }

    @Test
    fun register_recordsConsentAfterwards() = runBlocking {
        ScriptedServer { r ->
            when (r.url.encodedPath) {
                "/v1/auth/register" -> jsonResponse(201, tokenJson("acc", "ref"))
                "/v1/me/consent" -> jsonResponse(200, """{"trainingUse":true,"recordedAt":"2026-01-01T10:00:00Z"}""")
                else -> status(404)
            }
        }.use { s ->
            val svc = BackendService(secrets, { s.baseUrl }, allowCleartext = true)

            assertEquals(AuthResult.Success, svc.register("a@b.c", "long-enough-pw", "P01", trainingConsent = true))

            assertEquals(listOf("POST /v1/auth/register", "PUT /v1/me/consent"), s.lines())
            assertTrue(s.seen[1].body!!.utf8().contains("true"))
        }
    }

    @Test
    fun releaseStyleBaseUrl_refusesCleartext() {
        assertEquals("Use an https:// address.", BaseUrl.problem("http://192.168.1.20:8080", allowCleartext = false))
        assertNull(BaseUrl.problem("https://api.example.com", allowCleartext = false))
        assertNull(BaseUrl.problem("http://192.168.1.20:8080", allowCleartext = true))
        val svc = BackendService(MemorySecretStore(), { "http://x.test" }, allowCleartext = false)
        assertThrows(IllegalStateException::class.java) { svc.client() }
    }
}
