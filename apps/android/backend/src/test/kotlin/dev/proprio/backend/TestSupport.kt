package dev.proprio.backend

import dev.proprio.api.models.TokenPair
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

const val GOLDEN_ID = "3f1c2a52-8d0e-4b6a-9a57-2f6d0c1e7b01"

fun goldenZip(): File =
    File(System.getProperty("proprio.protocol.dir") ?: error("proprio.protocol.dir not set"), "golden/session_gym_v1.zip")

fun jsonResponse(code: Int, body: String, vararg headers: Pair<String, String>): MockResponse =
    MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).apply {
        headers.forEach { (k, v) -> addHeader(k, v) }
    }.build()

fun status(code: Int): MockResponse = MockResponse.Builder().code(code).build()

fun tokens(access: String, refresh: String) = TokenPair(access, refresh, 900, TokenPair.TokenType.Bearer)

fun tokenJson(access: String, refresh: String) =
    """{"accessToken":"$access","refreshToken":"$refresh","expiresIn":900,"tokenType":"Bearer"}"""

fun sessionJson(status: String, failure: String? = null, id: String = GOLDEN_ID) =
    """{"sessionId":"$id","kind":"gym","status":"$status","startedAt":"2026-01-01T10:00:00Z","createdAt":"2026-01-01T10:00:01Z"${failure?.let { ""","failureReason":"$it"""" } ?: ""}}"""

/** A MockWebServer whose answers come from [handler]; every request is kept in [seen] ("METHOD /path"). */
class ScriptedServer(private val handler: (RecordedRequest) -> MockResponse) : AutoCloseable {
    val server = MockWebServer()
    val seen = CopyOnWriteArrayList<RecordedRequest>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                return handler(request)
            }
        }
        server.start()
    }

    val baseUrl: String get() = server.url("/").toString()

    fun count(line: String) = seen.count { it.method + " " + it.url.encodedPath == line }

    fun lines() = seen.map { it.method + " " + it.url.encodedPath }

    override fun close() = server.close()
}

fun RecordedRequest.line() = method + " " + url.encodedPath
