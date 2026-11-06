package com.finance.tracker.data

import com.finance.tracker.core.TokenStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException

internal val apiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

internal object NoRequestBody

/** An immutable request identity; an explicit null must not pick up a later login. */
internal class ApiCredentialSnapshot(val token: String?, val apiOrigin: String)

class FinanceApiClient(
    internal val baseUrl: () -> String,
    internal val tokenStore: TokenStore,
    engine: HttpClientEngine? = null,
    internal val allowInsecureHttp: Boolean = false,
) {
    internal val credentialMutex = Mutex()
    private val transportFailures = PlatformTransportFailureTracker()
    internal fun isTrackedTransportFailure(failure: Throwable): Boolean = transportFailures.matches(failure)
    internal val httpClient = engine?.let { injectedEngine ->
        HttpClient(injectedEngine) {
            expectSuccess = false
            install(ContentNegotiation) { json(apiJson) }
            install(SSE)
        }
    } ?: HttpClient {
        transportFailures.configure(this)
        expectSuccess = false
        install(ContentNegotiation) { json(apiJson) }
        install(SSE)
    }

    fun close() = httpClient.close()
}

internal suspend inline fun <reified T> FinanceApiClient.request(
    method: HttpMethod,
    path: String,
    body: Any? = NoRequestBody,
    extraHeaders: Map<String, String> = emptyMap(),
    bodyContentType: ContentType? = null,
    clearUnauthorizedToken: Boolean = true,
    credentials: ApiCredentialSnapshot? = null,
    preserveHttpRejectionOnBodyFailure: Boolean = false,
): T {
    val origin = normalizeApiOrigin(credentials?.apiOrigin ?: baseUrl(), allowInsecureHttp)
    val token = if (credentials == null) readToken() else credentials.token
    val statement = httpClient.prepareRequest(origin + path) {
        this.method = method
        if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
        extraHeaders.forEach { (name, value) -> header(name, value) }
        if (body !== NoRequestBody) {
            val requestContentType = bodyContentType ?: ContentType.Application.Json
            contentType(requestContentType)
            if (body is JsonElement && requestContentType == ContentType.Application.Json) {
                setBody(TextContent(body.toString(), ContentType.Application.Json))
            } else {
                setBody(body)
            }
        }
    }
    var rejectedStatus: Int? = null
    val readResponse: suspend (HttpResponse) -> T = readResponse@{ response ->
        if (preserveHttpRejectionOnBodyFailure && response.status.value !in 200..299) rejectedStatus = response.status.value
        val responseText = response.bodyAsText()
        if (response.status.value !in 200..299) {
            val code = apiErrorCode(response.status.value, responseText) ?: "request_failed"
            val receiptId = runCatching {
                ((apiJson.parseToJsonElement(responseText) as? JsonObject)?.get("receipt_id") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.takeIf { it.matches(Regex("[A-Za-z0-9_:.~-]{1,36}")) }
            }.getOrNull()
            val commitState = runCatching {
                ((apiJson.parseToJsonElement(responseText) as? JsonObject)?.get("commit_state") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.takeIf { it in setOf("unknown", "not_committed") }
            }.getOrNull()
            val failure = ApiFailure(code, response.status.value, receiptId = receiptId, commitState = commitState,
                diagnostic = apiErrorDiagnostic(responseText))
            rejectHttpFailure(failure, token, clearUnauthorizedToken)
        }
        if (response.status == HttpStatusCode.NoContent || responseText.isBlank()) {
            @Suppress("UNCHECKED_CAST")
            return@readResponse EmptyResultDto() as T
        }
        try {
            apiJson.decodeFromString<T>(responseText)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            throw ApiFailure("request_failed", response.status.value, cause = failure)
        }
    }
    return try {
        if (preserveHttpRejectionOnBodyFailure) statement.execute(readResponse)
        else readResponse(statement.execute()) // Preserve ordinary reads/writes' saved-response and unknown-result semantics.
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        if (preserveHttpRejectionOnBodyFailure) currentCoroutineContext().ensureActive()
        if (failure is ApiFailure) throw failure
        val trackedTransportFailure = isTrackedTransportFailure(failure)
        if (preserveHttpRejectionOnBodyFailure) {
            val status = rejectedStatus
            if (status != null && (failure is IOException || failure is HttpRequestTimeoutException || trackedTransportFailure)) {
                rejectHttpFailure(ApiFailure(apiErrorCode(status, "") ?: "request_failed", status, cause = failure),
                    token, clearUnauthorizedToken)
            }
        }
        if (trackedTransportFailure) {
            currentCoroutineContext().ensureActive()
            throw IOException("network_unavailable", failure)
        }
        throw failure
    }
}

/** A known HTTP rejection remains authoritative even if its diagnostic body could not be read. */
internal suspend fun FinanceApiClient.rejectHttpFailure(failure: ApiFailure, token: String?, clearUnauthorizedToken: Boolean): Nothing {
    if (failure.status == HttpStatusCode.Unauthorized.value && clearUnauthorizedToken) {
        try { clearTokenIfCurrent(token) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (cleanup: Throwable) { cleanup.addSuppressed(failure); throw cleanup }
    }
    throw failure
}

internal suspend fun FinanceApiClient.captureCredentials(): ApiCredentialSnapshot {
    val origin = normalizeApiOrigin(baseUrl(), allowInsecureHttp)
    return ApiCredentialSnapshot(readToken(), origin)
}

internal suspend fun FinanceApiClient.readToken(): String? = credentialMutex.withLock { readTokenLocked() }

/** Only called while holding this client's credentialMutex. */
internal suspend fun FinanceApiClient.readTokenLocked(): String? {
    return try { tokenStore.get()?.takeIf(String::isNotEmpty) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Throwable) { throw ApiFailure("token_store_unavailable", 0, cause = failure) }
}

internal suspend fun FinanceApiClient.writeToken(value: String) = credentialMutex.withLock { writeTokenLocked(value) }

/** Only called while holding this client's credentialMutex. */
internal suspend fun FinanceApiClient.writeTokenLocked(value: String) {
    try { tokenStore.set(value) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Throwable) { throw ApiFailure("token_store_unavailable", 0, cause = failure) }
}

internal suspend fun FinanceApiClient.clearTokenIfCurrent(expected: String?) {
    if (expected == null) return
    credentialMutex.withLock {
        try { if (tokenStore.get() == expected) tokenStore.clear() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) { throw ApiFailure("token_store_unavailable", 0, cause = failure) }
    }
}
