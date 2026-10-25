package dev.proprio.api

import dev.proprio.api.apis.AccountApi
import dev.proprio.api.apis.AnalysisApi
import dev.proprio.api.apis.AuthApi
import dev.proprio.api.apis.HistoryApi
import dev.proprio.api.apis.LabelsApi
import dev.proprio.api.apis.OpsApi
import dev.proprio.api.apis.SessionsApi
import dev.proprio.api.infrastructure.ApiClient
import dev.proprio.api.models.CreateSessionRequest
import dev.proprio.api.models.CreateSessionRequestDevice
import dev.proprio.api.models.Exercise
import dev.proprio.api.models.LoginRequest
import dev.proprio.api.models.SessionKind
import dev.proprio.api.models.SessionStatus
import java.io.File
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/**
 * The client is generated from contracts/openapi/core-api.yaml at build time. These tests prove the generated
 * code covers the whole contract and really speaks the contract's JSON over HTTP.
 */
class ClientContractTest {
    private val server = MockWebServer()

    @BeforeEach fun start() = server.start()

    @AfterEach fun stop() = server.close()

    private fun <T> service(type: Class<T>): T = ApiClient(baseUrl = server.url("/").toString(), callFactory = OkHttpClient()).createService(type)

    private fun json(code: Int, body: String) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun everyContractOperationHasAGeneratedMethod_andNothingElse() {
        val spec = File(System.getProperty("proprio.contract")).reader().use { Yaml().load<Map<String, Any?>>(it) }

        @Suppress("UNCHECKED_CAST")
        val paths = spec["paths"] as Map<String, Map<String, Any?>>
        val operationIds = paths.values.flatMap { item ->
            item.filterKeys { it in setOf("get", "put", "post", "delete", "patch") }
                .values.map { (it as Map<*, *>)["operationId"] as String }
        }.toSet()
        assertTrue(operationIds.size >= 17, "contract should declare its operations: $operationIds")

        val generated = listOf(AuthApi::class, AccountApi::class, SessionsApi::class, LabelsApi::class, AnalysisApi::class, HistoryApi::class, OpsApi::class)
            .flatMap { it.java.declaredMethods.map { m -> m.name } }
            .filterNot { it.endsWith("\$default") }
            .toSet()
        assertEquals(operationIds, generated)
    }

    @Test
    fun createSession_serialisesContractJson_andParsesTheTicket() = runBlocking {
        val id = UUID.fromString("3f1c2a52-8d0e-4b6a-9a57-2f6d0c1e7b01")
        server.enqueue(
            json(
                201,
                """{"session":{"sessionId":"$id","kind":"gym","status":"CREATED","startedAt":"2026-01-01T10:00:00Z","createdAt":"2026-01-01T10:00:01Z","deviceModel":"SM-R920"},
                   "uploadUrl":"http://minio:9000/proprio/raw/u/$id.zip?X-Amz-Signature=abc","uploadMethod":"PUT",
                   "uploadHeaders":{"Content-Type":"application/zip"},"expiresAt":"2026-01-01T10:15:00Z","somethingNew":1}""",
            ),
        )
        val request = CreateSessionRequest(
            sessionId = id, kind = SessionKind.gym, sha256 = "a".repeat(64), sizeBytes = 1234,
            rawFormat = CreateSessionRequest.RawFormat._1, startedAt = OffsetDateTime.parse("2026-01-01T10:00:00Z"),
            device = CreateSessionRequestDevice("Samsung", "SM-R920", 34),
        )

        val response = service(SessionsApi::class.java).createSession(request)

        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/v1/sessions", sent.url.encodedPath)
        val body = Json.parseToJsonElement(sent.body!!.utf8()).jsonObject
        assertEquals(id.toString(), body.getValue("sessionId").jsonPrimitive.content)
        assertEquals("gym", body.getValue("kind").jsonPrimitive.content)
        assertEquals("1", body.getValue("rawFormat").jsonPrimitive.content)
        assertEquals("Samsung", body.getValue("device").jsonObject.getValue("manufacturer").jsonPrimitive.content)

        assertEquals(201, response.code())
        val ticket = response.body()!!
        assertEquals(SessionStatus.CREATED, ticket.session.status)
        assertEquals("application/zip", ticket.uploadHeaders["Content-Type"])
        assertTrue(ticket.uploadUrl.toString().contains("X-Amz-Signature=abc"))
    }

    @Test
    fun login_sendsCredentials_andParsesTokenPair() = runBlocking {
        server.enqueue(json(200, """{"accessToken":"a","refreshToken":"r","expiresIn":900,"tokenType":"Bearer"}"""))

        val tokens = service(AuthApi::class.java).login(LoginRequest("me@example.com", "pw")).body()!!

        assertEquals("r", tokens.refreshToken)
        assertEquals(900, tokens.expiresIn)
        val sent = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("me@example.com", sent.getValue("email").jsonPrimitive.content)
    }

    @Test
    fun labels_roundTripFreeFormDocument_withETagAndIfMatch() = runBlocking {
        val doc = """{"labels_format":1,"session_id":"x","segments":[{"segment_id":1,"kind":"set","reps_user":5}],"extra":{"a":[1,2]}}"""
        server.enqueue(MockResponse.Builder().code(200).addHeader("ETag", "\"3\"").addHeader("Content-Type", "application/json").body(doc).build())
        val api = service(LabelsApi::class.java)
        val id = UUID.randomUUID()

        val got = api.getLabels(id)
        assertEquals("\"3\"", got.headers()["ETag"])
        val parsed = got.body()!!
        assertEquals("1", parsed.getValue("labels_format").jsonPrimitive.content)

        server.takeRequest()
        server.enqueue(json(200, doc))
        api.putLabels(id, parsed + ("note" to JsonPrimitive("n")), "\"3\"")
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("\"3\"", put.headers["If-Match"])
        assertEquals("n", Json.parseToJsonElement(put.body!!.utf8()).jsonObject.getValue("note").jsonPrimitive.content)
    }

    @Test
    fun analysis_parsesSegmentsWithEnumsAndNumbers() = runBlocking {
        val id = UUID.randomUUID()
        server.enqueue(
            json(
                200,
                """{"sessionId":"$id","status":"ANALYZED","model":{"name":"m","version":"1"},"profileId":"p","transformId":"t",
                   "segments":[{"segmentId":1,"kind":"set","startS":10.0,"endS":20.5,"predictedExercise":"squat","predictedKind":"set",
                   "confidence":0.8,"repCount":5,"userExercise":"bench","userReps":6,"loadKg":60.5}]}""",
            ),
        )

        val analysis = service(AnalysisApi::class.java).getAnalysis(id).body()!!

        val seg = analysis.segments.single()
        assertEquals(Exercise.squat, seg.predictedExercise)
        assertEquals(Exercise.bench, seg.userExercise)
        assertEquals(5, seg.repCount)
        assertEquals(60.5, seg.loadKg!!.toDouble(), 1e-9)
    }
}
