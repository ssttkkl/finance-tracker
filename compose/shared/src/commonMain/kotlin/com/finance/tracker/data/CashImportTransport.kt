package com.finance.tracker.data

import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** Successful server capability; failures are never cached because an old or transient gateway may recover. */
internal data class CashImportTransportCapabilities(val commandVersions: Set<Int>, val requestEncodings: Set<String>,
    val responseEncodings: Set<String>) {
    val supportsCompactCommand: Boolean get() = 2 in commandVersions
    val supportsGzipRequest: Boolean get() = "gzip" in requestEncodings
}

internal object CashImportTransportCache {
    private val mutex = Mutex()
    private val successful = mutableMapOf<String, CashImportTransportCapabilities>()

    fun key(origin: String, token: String?): String = origin + "\u0000" + (token ?: "")

    suspend fun cached(key: String): CashImportTransportCapabilities? = mutex.withLock { successful[key] }

    suspend fun probe(key: String, action: suspend () -> CashImportTransportCapabilities?): CashImportTransportCapabilities? {
        cached(key)?.let { return it }
        val result = try { action() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Throwable) { null }
        if (result != null) mutex.withLock { successful[key] = result }
        return result
    }

    suspend fun invalidate(origin: String, token: String?) {
        mutex.withLock { successful.remove(key(origin, token)) }
    }

    internal suspend fun resetForTest() = mutex.withLock { successful.clear() }
}

internal fun parseCashImportTransportCapabilities(value: JsonObject): CashImportTransportCapabilities? {
    fun strings(name: String, numbers: Boolean = false): Set<String>? {
        val array = value[name] as? JsonArray ?: return null
        val items = array.map {
            val primitive = it as? JsonPrimitive ?: return null
            if (numbers) {
                if (primitive.isString) return null
                primitive.intOrNull?.toString() ?: return null
            } else {
                if (!primitive.isString) return null
                primitive.content
            }
        }
        return items.toSet()
    }
    val versions = strings("command_versions", numbers = true)?.mapNotNull(String::toIntOrNull)?.toSet() ?: return null
    val requests = strings("request_encodings") ?: return null
    val responses = strings("response_encodings") ?: return null
    if (versions.isEmpty() || requests.none { it in setOf("identity", "gzip") }) return null
    return CashImportTransportCapabilities(versions, requests, responses)
}

internal suspend fun FinanceApiClient.cashImportWriteSetCapabilities(workspaceId: String,
    credentials: ApiCredentialSnapshot): CashImportTransportCapabilities? {
    require(workspaceId.isNotEmpty())
    val path = appendQuery("/api/v1/cash-import/write-set", listOf("workspace_id" to workspaceId))
    val root: JsonObject = request(HttpMethod.Options, path, clearUnauthorizedToken = false, credentials = credentials,
        requireResponseBody = true, decodeResponse = { apiJson.parseToJsonElement(it).jsonObject })
    return parseCashImportTransportCapabilities(root)
}

internal suspend fun FinanceApiClient.cashImportTransportCapabilities(workspaceId: String,
    credentials: ApiCredentialSnapshot): CashImportTransportCapabilities? {
    val key = CashImportTransportCache.key(credentials.apiOrigin, credentials.token)
    return CashImportTransportCache.probe(key) { cashImportWriteSetCapabilities(workspaceId, credentials) }
}

/** Marker that the platform can produce gzip bytes locally; browsers ask their worker instead. */
internal sealed interface CashImportRequestEncoding {
    data class Native(val gzip: ByteArray) : CashImportRequestEncoding
    data object Browser : CashImportRequestEncoding
    data object Identity : CashImportRequestEncoding
}

internal expect fun encodeCashImportRequest(bytes: ByteArray): CashImportRequestEncoding
