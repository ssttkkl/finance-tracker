package com.finance.tracker.data

import com.finance.tracker.core.TokenStore
import com.finance.tracker.domain.FileSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
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

internal val apiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

internal object NoRequestBody

class FinanceApiClient(
    internal val baseUrl: () -> String,
    internal val tokenStore: TokenStore,
    engine: HttpClientEngine? = null,
    internal val allowInsecureHttp: Boolean = false,
) {
    internal val httpClient = engine?.let { injectedEngine ->
        HttpClient(injectedEngine) {
            expectSuccess = false
            install(ContentNegotiation) { json(apiJson) }
            install(SSE)
        }
    } ?: HttpClient {
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
): T {
    val origin = normalizeApiOrigin(baseUrl(), allowInsecureHttp)
    val token = readToken()
    val response = httpClient.request(origin + path) {
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
    val responseText = response.bodyAsText()
    if (response.status.value !in 200..299) {
        val code = apiErrorCode(response.status.value, responseText) ?: "request_failed"
        if (response.status == HttpStatusCode.Unauthorized) clearToken()
        val importToken = runCatching {
            (apiJson.parseToJsonElement(responseText) as? JsonObject)?.get("import_token")?.let { (it as? JsonPrimitive)?.content }
        }.getOrNull()
        throw ApiFailure(code, response.status.value, importToken)
    }
    if (response.status == HttpStatusCode.NoContent || responseText.isBlank()) {
        @Suppress("UNCHECKED_CAST")
        return EmptyResultDto() as T
    }
    return try {
        apiJson.decodeFromString<T>(responseText)
    } catch (_: Throwable) {
        throw ApiFailure("request_failed", response.status.value)
    }
}

internal suspend inline fun <reified T> FinanceApiClient.importJsonRequest(
    path: String,
    options: ImportRequestOptions,
    payload: Any? = importPayload(options),
): T {
    val headers = buildMap {
        if (options.password != null) put("X-FT-Statement-Password", options.password)
        if (options.passwords.isNotEmpty()) put("X-FT-Statement-Passwords", apiJson.encodeToString(options.passwords))
        if (options.idempotencyKey != null) put("Idempotency-Key", options.idempotencyKey)
    }
    return request(HttpMethod.Post, path, payload, extraHeaders = headers)
}

internal suspend inline fun <reified T> FinanceApiClient.importRawRequest(
    path: String,
    file: FileSource,
    options: ImportRequestOptions,
): T {
    val query = appendQuery(path, listOf(
        "source" to options.source,
        "filename" to file.name,
        "currency" to options.currency,
        "preview_digest" to options.previewDigest,
        "preview_relation_digest" to options.previewRelationDigest,
        "preview_channel" to options.previewChannel,
        "relations" to options.relations?.let(apiJson::encodeToString),
        "mapping" to options.mapping?.let(apiJson::encodeToString),
    ))
    val headers = buildMap {
        if (options.password != null) put("X-FT-Statement-Password", options.password)
        if (options.passwords.isNotEmpty()) put("X-FT-Statement-Passwords", apiJson.encodeToString(options.passwords))
        put(HttpHeaders.ContentType, "application/octet-stream")
        if (options.idempotencyKey != null) put("Idempotency-Key", options.idempotencyKey)
    }
    return request(HttpMethod.Post, query, file.read(), headers, ContentType.Application.OctetStream)
}

internal suspend fun FinanceApiClient.readToken(): String? = try {
    tokenStore.get()?.takeIf(String::isNotEmpty)
} catch (_: Throwable) {
    throw ApiFailure("token_store_unavailable", 0)
}

internal suspend fun FinanceApiClient.clearToken() {
    try {
        tokenStore.clear()
    } catch (_: Throwable) {
        throw ApiFailure("token_store_unavailable", 0)
    }
}
