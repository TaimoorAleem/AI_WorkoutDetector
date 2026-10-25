package dev.proprio.backend

import dev.proprio.api.apis.AccountApi
import dev.proprio.api.apis.AnalysisApi
import dev.proprio.api.apis.AuthApi
import dev.proprio.api.apis.HistoryApi
import dev.proprio.api.apis.LabelsApi
import dev.proprio.api.apis.SessionsApi
import dev.proprio.api.infrastructure.ApiClient
import dev.proprio.api.models.RefreshRequest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/** Every generated API interface over one call factory. */
class ApiSet(baseUrl: String, callFactory: Call.Factory) {
    private val client = ApiClient(baseUrl = baseUrl, callFactory = callFactory)
    val auth: AuthApi = client.createService(AuthApi::class.java)
    val sessions: SessionsApi = client.createService(SessionsApi::class.java)
    val labels: LabelsApi = client.createService(LabelsApi::class.java)
    val analysis: AnalysisApi = client.createService(AnalysisApi::class.java)
    val history: HistoryApi = client.createService(HistoryApi::class.java)
    val account: AccountApi = client.createService(AccountApi::class.java)
}

private val PUBLIC_PATHS = setOf("/v1/auth/login", "/v1/auth/register", "/v1/auth/refresh")

private fun Request.isPublic() = url.encodedPath in PUBLIC_PATHS

/** Adds `Authorization: Bearer <access>` to every non-public request when a token is held. */
class BearerInterceptor(private val auth: AuthManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val token = auth.accessToken
        if (token == null || req.isPublic()) return chain.proceed(req)
        return chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
    }
}

/**
 * Refreshes once on a 401 and retries the request with the new token. [refresh] is a blocking exchange of the
 * refresh token (see [BackendClient]). Gives up (returns null, so the caller sees the 401) when the retried
 * request is rejected again, when the refresh is refused (the user is then signed out) and for public auth calls,
 * whose 401 means bad credentials rather than an expired token.
 */
class TokenAuthenticator(
    private val auth: AuthManager,
    private val refresh: (refreshToken: String) -> RefreshOutcome,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.isPublic()) return null
        if (response.priorResponse != null) return null // already retried once
        val stale = response.request.header("Authorization")?.removePrefix("Bearer ")
        val token = auth.tokenAfterUnauthorized(stale, refresh) ?: return null
        return response.request.newBuilder().header("Authorization", "Bearer $token").build()
    }
}

/**
 * The backend connection for one base URL: a plain [ApiSet] (login, register, refresh; no auth) and an
 * authenticated one (bearer + refresh-on-401). [http] is the auth-free client for presigned object-store URLs,
 * which must never receive the bearer token.
 */
class BackendClient(
    val baseUrl: String,
    val auth: AuthManager,
    base: OkHttpClient = OkHttpClient(),
) {
    val http: OkHttpClient = base.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    // Own dispatcher: the authenticator blocks an authed-client thread while the refresh runs, so sharing the
    // dispatcher could exhaust its per-host slots and deadlock.
    val plain = ApiSet(baseUrl, http.newBuilder().dispatcher(okhttp3.Dispatcher()).build())

    val api = ApiSet(
        baseUrl,
        http.newBuilder()
            .addInterceptor(BearerInterceptor(auth))
            .authenticator(TokenAuthenticator(auth, ::exchangeRefreshToken))
            .build(),
    )

    // Runs on an OkHttp thread of the authenticated client; the call itself uses the plain client's own dispatcher.
    private fun exchangeRefreshToken(refreshToken: String): RefreshOutcome = runBlocking {
        when (val r = apiCall { plain.auth.refresh(RefreshRequest(refreshToken)) }) {
            is ApiResult.Ok -> RefreshOutcome.Success(r.value)
            is ApiResult.HttpError -> if (r.code in 400..499) RefreshOutcome.Rejected else RefreshOutcome.Unavailable
            is ApiResult.NetworkError -> RefreshOutcome.Unavailable
        }
    }
}
