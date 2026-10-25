package dev.proprio.backend

import dev.proprio.api.infrastructure.Serializer
import dev.proprio.api.models.Problem
import kotlinx.coroutines.CancellationException
import okhttp3.Headers
import retrofit2.Response

/** Outcome of one generated-client call, with HTTP failures and transport failures kept apart. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T, val code: Int, val headers: Headers) : ApiResult<T>

    /** The server answered with a non-2xx status; [problem] is its RFC 9457 body when it parsed. */
    data class HttpError(val code: Int, val problem: Problem?, val headers: Headers) : ApiResult<Nothing> {
        val message: String get() = problem?.detail ?: problem?.title ?: "HTTP $code"
    }

    /** No usable answer: I/O failure, timeout or an unparseable body. Always worth retrying. */
    data class NetworkError(val cause: Exception) : ApiResult<Nothing>
}

/** Runs a generated-client call and folds every outcome into an [ApiResult]; never throws (except on cancellation). */
suspend fun <T> apiCall(block: suspend () -> Response<T>): ApiResult<T> = try {
    val r = block()
    if (r.isSuccessful) {
        @Suppress("UNCHECKED_CAST")
        ApiResult.Ok(r.body() as T, r.code(), r.headers())
    } else {
        ApiResult.HttpError(r.code(), parseProblem(r.errorBody()?.string()), r.headers())
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    ApiResult.NetworkError(e)
}

private fun parseProblem(body: String?): Problem? = try {
    body?.takeIf { it.isNotBlank() }?.let { Serializer.kotlinxSerializationJson.decodeFromString(Problem.serializer(), it) }
} catch (e: Exception) {
    null
}
