package dev.proprio.backend

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UploadCoreTest {
    private val zip = goldenZip()
    private val zipBytes = zip.readBytes()
    private val labels = """{"labels_format":1,"session_id":"$GOLDEN_ID","segments":[]}"""

    private val files = object : SessionFiles {
        var labelsText: String? = labels
        override fun zip(sessionId: String): File? = zip.takeIf { sessionId == GOLDEN_ID }
        override fun labelsText(sessionId: String) = labelsText
    }

    /** A configurable fake core-api + object store. Every knob can be changed between attempts. */
    private inner class Fake {
        var create: (RecordedRequest) -> MockResponse = { r -> ticket(r) }
        var put: () -> MockResponse = { status(200) }
        var complete: MockResponse = jsonResponse(202, sessionJson("UPLOADED"))
        var getSession: MockResponse = jsonResponse(200, sessionJson("UPLOADED"))
        var getLabels: MockResponse = status(404)
        var putLabels: () -> MockResponse = { jsonResponse(200, labels, "ETag" to "\"1\"") }
        lateinit var s: ScriptedServer

        fun ticket(r: RecordedRequest) = jsonResponse(
            201,
            """{"session":${sessionJson("CREATED")},"uploadUrl":"${s.baseUrl}bucket/raw.zip?X-Amz-Signature=abc","uploadMethod":"PUT",
               "uploadHeaders":{"Content-Type":"application/zip"},"expiresAt":"2026-01-01T10:15:00Z"}""",
        )

        fun handle(r: RecordedRequest): MockResponse = when (r.line()) {
            "POST /v1/sessions" -> create(r)
            "PUT /bucket/raw.zip" -> put()
            "POST /v1/sessions/$GOLDEN_ID/complete" -> complete
            "GET /v1/sessions/$GOLDEN_ID" -> getSession
            "GET /v1/sessions/$GOLDEN_ID/labels" -> getLabels
            "PUT /v1/sessions/$GOLDEN_ID/labels" -> putLabels()
            else -> status(404)
        }
    }

    private fun withCore(block: suspend (Fake, ScriptedServer, UploadCore, UploadStateStore) -> Unit) = runBlocking {
        val fake = Fake()
        ScriptedServer(fake::handle).use { s ->
            fake.s = s
            val svc = BackendService(MemorySecretStore(), { s.baseUrl }, allowCleartext = true)
            svc.auth.signIn("a@b.c", tokens("acc", "ref"))
            val states = MemoryUploadStateStore()
            block(fake, s, UploadCore({ svc.client() }, states, files), states)
        }
    }

    @Test
    fun happyPath_createsPutsTheUntouchedZip_completes_thenSendsLabels() = withCore { _, s, core, states ->
        assertEquals(UploadOutcome.Done, core.upload(GOLDEN_ID))

        assertEquals(
            listOf(
                "POST /v1/sessions", "PUT /bucket/raw.zip", "POST /v1/sessions/$GOLDEN_ID/complete",
                "GET /v1/sessions/$GOLDEN_ID/labels", "PUT /v1/sessions/$GOLDEN_ID/labels",
            ),
            s.lines(),
        )
        val create = Json.parseToJsonElement(s.seen[0].body!!.utf8()).jsonObject
        assertEquals(GOLDEN_ID, create.getValue("sessionId").jsonPrimitive.content)
        assertEquals("gym", create.getValue("kind").jsonPrimitive.content)
        assertEquals(UploadCore.sha256(zip), create.getValue("sha256").jsonPrimitive.content)
        assertEquals(zipBytes.size.toString(), create.getValue("sizeBytes").jsonPrimitive.content)

        val put = s.seen[1]
        assertArrayEquals(zipBytes, put.body!!.toByteArray())
        assertEquals("application/zip", put.headers["Content-Type"])
        assertNull(put.headers["Authorization"], "the bearer token must not reach object storage")
        assertEquals("Bearer acc", s.seen[0].headers["Authorization"])
        assertNull(s.seen[4].headers["If-Match"])

        val st = states.get(GOLDEN_ID)!!
        assertEquals(UploadPhase.UPLOADED, st.phase)
        assertTrue(st.remoteUploaded)
        assertEquals(UploadCore.sha256(labels.toByteArray()), st.labelsHash)
    }

    @Test
    fun completedSession_isNeverUploadedAgain_andUnchangedLabelsAreNotResent() = withCore { _, s, core, _ ->
        core.upload(GOLDEN_ID)
        s.seen.clear()

        assertEquals(UploadOutcome.Done, core.upload(GOLDEN_ID))

        assertTrue(s.seen.isEmpty(), s.lines().toString())
    }

    @Test
    fun editedLabels_areResentWithTheServersETag_withoutTouchingTheZip() = withCore { fake, s, core, _ ->
        core.upload(GOLDEN_ID)
        s.seen.clear()
        files.labelsText = labels.replace("[]", """[{"segment_id":1}]""")
        fake.getLabels = jsonResponse(200, labels, "ETag" to "\"4\"")

        assertEquals(UploadOutcome.Done, core.upload(GOLDEN_ID))

        assertEquals(listOf("GET /v1/sessions/$GOLDEN_ID/labels", "PUT /v1/sessions/$GOLDEN_ID/labels"), s.lines())
        assertEquals("\"4\"", s.seen[1].headers["If-Match"])
    }

    @Test
    fun labelsPreconditionFailed_rereadsTheETagAndRetries() = withCore { fake, s, core, states ->
        val puts = AtomicInteger()
        fake.getLabels = jsonResponse(200, labels, "ETag" to "\"1\"")
        fake.putLabels = { if (puts.incrementAndGet() == 1) status(412) else jsonResponse(200, labels, "ETag" to "\"3\"") }

        assertEquals(UploadOutcome.Done, core.upload(GOLDEN_ID))

        assertEquals(2, s.count("PUT /v1/sessions/$GOLDEN_ID/labels"))
        assertEquals(2, s.count("GET /v1/sessions/$GOLDEN_ID/labels"))
        assertTrue(states.get(GOLDEN_ID)!!.labelsHash != null)
    }

    @Test
    fun create409_whenSessionAlreadyUploaded_skipsPutAndComplete() = withCore { fake, s, core, states ->
        fake.create = { status(409) }
        fake.getSession = jsonResponse(200, sessionJson("ANALYZED"))

        assertEquals(UploadOutcome.Done, core.upload(GOLDEN_ID))

        assertEquals(0, s.count("PUT /bucket/raw.zip"))
        assertEquals(0, s.count("POST /v1/sessions/$GOLDEN_ID/complete"))
        val st = states.get(GOLDEN_ID)!!
        assertTrue(st.remoteUploaded)
        assertEquals(UploadPhase.ANALYZED, st.phase)
    }

    @Test
    fun create409_forAnotherAccountsSession_failsPermanently() = withCore { fake, _, core, states ->
        fake.create = { status(409) }
        fake.getSession = status(404)

        val o = core.upload(GOLDEN_ID)

        assertTrue(o is UploadOutcome.Failed, o.toString())
        assertEquals(UploadPhase.FAILED, states.get(GOLDEN_ID)!!.phase)
    }

    @Test
    fun complete409_isTreatedAsAlreadyDone() = withCore { fake, _, core, states ->
        fake.complete = status(409)
        fake.getSession = jsonResponse(200, sessionJson("ANALYZING"))

        assertEquals(UploadOutcome.Done, core.upload(GOLDEN_ID))

        assertEquals(UploadPhase.UPLOADED, states.get(GOLDEN_ID)!!.phase)
    }

    @Test
    fun presignFailure_5xx_isRetriedLater_andRecovers() = withCore { fake, s, core, states ->
        fake.create = { status(503) }

        val first = core.upload(GOLDEN_ID)

        assertTrue(first is UploadOutcome.Retry)
        assertEquals(0, s.count("PUT /bucket/raw.zip"))
        assertEquals(UploadPhase.PENDING, states.get(GOLDEN_ID)!!.phase)
        assertEquals(1, states.get(GOLDEN_ID)!!.attempts)

        fake.create = { r -> fake.ticket(r) }
        assertEquals(UploadOutcome.Done, core.upload(GOLDEN_ID))
        assertEquals(UploadPhase.UPLOADED, states.get(GOLDEN_ID)!!.phase)
        assertNull(states.get(GOLDEN_ID)!!.error)
    }

    @Test
    fun presignRejected_400_failsPermanently_withTheServersMessage() = withCore { fake, _, core, states ->
        fake.create = {
            MockResponse.Builder().code(400).addHeader("Content-Type", "application/problem+json")
                .body("""{"type":"about:blank","title":"Bad Request","status":400,"detail":"Upload too large."}""").build()
        }

        val o = core.upload(GOLDEN_ID)

        assertEquals(UploadOutcome.Failed("Upload too large."), o)
        assertEquals(UploadPhase.FAILED, states.get(GOLDEN_ID)!!.phase)
    }

    @Test
    fun storageError_retries_andTheNextAttemptUploadsAgain() = withCore { fake, s, core, states ->
        fake.put = { status(403) } // e.g. expired signature

        assertTrue(core.upload(GOLDEN_ID) is UploadOutcome.Retry)
        assertEquals(0, s.count("POST /v1/sessions/$GOLDEN_ID/complete"))
        assertTrue(!states.get(GOLDEN_ID)!!.remoteUploaded)

        fake.put = { status(200) }
        assertEquals(UploadOutcome.Done, core.upload(GOLDEN_ID))
        assertEquals(2, s.count("POST /v1/sessions"))
        assertEquals(2, s.count("PUT /bucket/raw.zip"))
    }

    @Test
    fun completeUnprocessable_retriesTheWholeUpload() = withCore { fake, s, core, _ ->
        fake.complete = status(422)

        assertTrue(core.upload(GOLDEN_ID) is UploadOutcome.Retry)

        fake.complete = jsonResponse(202, sessionJson("UPLOADED"))
        assertEquals(UploadOutcome.Done, core.upload(GOLDEN_ID))
        assertEquals(2, s.count("PUT /bucket/raw.zip"))
    }

    @Test
    fun serverRejectingLabels_doesNotFailTheUpload() = withCore { fake, _, core, states ->
        fake.putLabels = { status(400) }

        assertEquals(UploadOutcome.Done, core.upload(GOLDEN_ID))

        val st = states.get(GOLDEN_ID)!!
        assertTrue(st.remoteUploaded)
        assertTrue(st.labelsError != null)
        assertNull(st.labelsHash)
    }

    @Test
    fun notSignedIn_needsAuth_andSendsNothing() = runBlocking {
        ScriptedServer { status(500) }.use { s ->
            val svc = BackendService(MemorySecretStore(), { s.baseUrl }, allowCleartext = true)
            val states = MemoryUploadStateStore()

            assertEquals(UploadOutcome.AuthRequired, UploadCore({ svc.client() }, states, files).upload(GOLDEN_ID))

            assertTrue(s.seen.isEmpty())
            assertEquals(UploadPhase.PENDING, states.get(GOLDEN_ID)!!.phase)
        }
    }

    @Test
    fun missingZip_failsWithoutNetwork() = runBlocking {
        ScriptedServer { status(500) }.use { s ->
            val svc = BackendService(MemorySecretStore(), { s.baseUrl }, allowCleartext = true)
            svc.auth.signIn("a@b.c", tokens("acc", "ref"))
            val none = object : SessionFiles {
                override fun zip(sessionId: String): File? = null
                override fun labelsText(sessionId: String): String? = null
            }

            assertTrue(UploadCore({ svc.client() }, MemoryUploadStateStore(), none).upload(GOLDEN_ID) is UploadOutcome.Failed)
            assertTrue(s.seen.isEmpty())
        }
    }
}
