package dev.proprio.mobile

import dev.proprio.backend.BackendService
import dev.proprio.backend.MemorySecretStore
import dev.proprio.backend.MemoryUploadStateStore
import dev.proprio.backend.UploadCore
import dev.proprio.backend.UploadOutcome
import dev.proprio.backend.UploadPhase
import dev.proprio.api.models.TokenPair
import dev.proprio.mobile.TestSupport.SID
import dev.proprio.mobile.data.FakeWatchLink
import dev.proprio.mobile.data.LabelHeader
import dev.proprio.mobile.data.MemorySettingsStore
import dev.proprio.mobile.data.SessionImporter
import dev.proprio.mobile.data.SessionStore
import dev.proprio.mobile.data.StoreSessionFiles
import dev.proprio.mobile.data.UserSettings
import dev.proprio.protocol.Consent
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** From a zip arriving over the (fake) watch link to the bytes on the (fake) backend. */
class UploadIntegrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @After fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun json(code: Int, body: String, vararg h: Pair<String, String>) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).apply { h.forEach { addHeader(it.first, it.second) } }.build()

    @Test
    fun receivedSession_triggersAnUpload_ofTheStoredZipByteForByte_withTheLabelsDocument() = runBlocking {
        val link = FakeWatchLink { SID }
        val store = SessionStore(File(tmp.root, "sessions"), header = { LabelHeader("P01", Consent(true, "2026-01-01T00:00:00Z")) })
        val changed = mutableListOf<String>()
        val coordinator = SessionCoordinator(
            scope, link, store, SessionImporter(store),
            MemorySettingsStore(UserSettings("P01", true, "2026-01-01T00:00:00Z", true)),
            io = Dispatchers.Unconfined, onSessionChanged = { changed += it },
        )
        coordinator.startSession(dev.proprio.protocol.SessionKind.GYM)
        coordinator.startSet()
        coordinator.stopSegment()
        coordinator.confirmReps(5, null, "")
        assertTrue("no zip yet, but the hook fires and the app decides", changed.all { it == SID })

        changed.clear()
        val received = TestSupport.buildZip(tmp.newFolder())
        val original = received.readBytes()
        link.emitZip(SID, received)
        assertEquals(listOf(SID), changed)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.url.encodedPath
                return when ("${request.method} $path") {
                    "POST /v1/sessions" -> json(
                        201,
                        """{"session":{"sessionId":"$SID","kind":"gym","status":"CREATED","startedAt":"2026-01-01T10:00:00Z","createdAt":"2026-01-01T10:00:00Z"},
                           "uploadUrl":"${server.url("/bucket/x.zip")}","uploadMethod":"PUT","uploadHeaders":{"Content-Type":"application/zip"},"expiresAt":"2026-01-01T10:15:00Z"}""",
                    )
                    "PUT /bucket/x.zip" -> MockResponse.Builder().code(200).build()
                    "POST /v1/sessions/$SID/complete" ->
                        json(202, """{"sessionId":"$SID","kind":"gym","status":"UPLOADED","startedAt":"2026-01-01T10:00:00Z","createdAt":"2026-01-01T10:00:00Z"}""")
                    "GET /v1/sessions/$SID/labels" -> MockResponse.Builder().code(404).build()
                    "PUT /v1/sessions/$SID/labels" -> json(200, request.body!!.utf8(), "ETag" to "\"1\"")
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
        val backend = BackendService(MemorySecretStore(), { server.url("/").toString() }, allowCleartext = true)
        backend.auth.signIn("a@b.c", TokenPair("acc", "ref", 900, TokenPair.TokenType.Bearer))
        val states = MemoryUploadStateStore()

        val outcome = UploadCore({ backend.client() }, states, StoreSessionFiles(store)).upload(SID)

        assertEquals(UploadOutcome.Done, outcome)
        val put = requests.first { it.method == "PUT" && it.url.encodedPath == "/bucket/x.zip" }
        assertArrayEquals("the raw zip must be uploaded untouched", original, put.body!!.toByteArray())
        assertArrayEquals(original, store.zipFile(SID).readBytes())
        val labelsPut = requests.last { it.method == "PUT" && it.url.encodedPath.endsWith("/labels") }
        // The labels sent are the phone's labels.json, which carries the user's rep count for set 1.
        assertTrue(labelsPut.body!!.utf8().contains("\"reps_user\""))
        assertEquals(UploadPhase.UPLOADED, states.get(SID)!!.phase)
    }
}
