package com.finance.tracker.backend

import java.io.InputStream
import java.time.Instant

internal class ParsedObjectProviderError(val providerCode: String) : RuntimeException("object_provider_error")
internal data class ParsedObjectResponse(val body: InputStream, val etag: String?, val contentLength: Long = 0)
internal data class ParsedObjectHead(val metadata: Map<String, String> = emptyMap(), val lastModified: Instant? = null)
internal data class ParsedObjectPage(val keys: List<String>, val truncated: Boolean, val nextCursor: String? = null)

internal interface ParsedObjectClient {
    fun get(bucket: String, key: String): ParsedObjectResponse
    fun put(bucket: String, key: String, content: ByteArray, ifMatch: String?, ifNoneMatch: String?,
        contentType: String, metadata: Map<String, String>)
    fun delete(bucket: String, key: String)
    fun list(bucket: String, prefix: String, limit: Int, cursor: String?): ParsedObjectPage
    fun head(bucket: String, key: String): ParsedObjectHead
}

internal class R2ParsedImportStore(private val client: ParsedObjectClient, private val bucket: String,
    prefix: String = "cash-import", ttlSeconds: Int = 1800, clock: () -> Instant = Instant::now,
    codec: ParsedImportCodec = ParsedImportCodec()) : ParsedImportStore(ttlSeconds, clock, codec) {
    private val prefix = prefix.trim('/')
    init { require(bucket.isNotEmpty() && this.prefix.matches(Regex("[A-Za-z0-9_/-]+")) && ".." !in this.prefix) {
        "import_session_storage_config_invalid"
    } }
    private fun key(id: String) = "$prefix/parsed/${tokenDigest(id)}/session"
    private fun missing(error: Exception) = error is ParsedObjectProviderError && error.providerCode in setOf("404", "NoSuchKey", "NotFound")
    override fun readRaw(id: String): ParsedImportObject {
        var body: InputStream? = null
        try {
            val response = client.get(bucket, key(id))
            body = response.body
            if (response.contentLength > codec.maxBytes || response.etag.isNullOrEmpty()) throw ParsedImportStorageUnavailable()
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(65536)
            while (true) {
                val count = body.read(buffer, 0, minOf(buffer.size, codec.maxBytes + 1 - output.size()))
                if (count < 0) break
                if (count == 0) throw ParsedImportStorageUnavailable()
                output.write(buffer, 0, count)
                if (output.size() > codec.maxBytes) throw ParsedImportStorageUnavailable()
            }
            return ParsedImportObject(output.toByteArray(), response.etag)
        } catch (error: Exception) {
            if (missing(error)) throw ParsedImportNotFound()
            throw ParsedImportStorageUnavailable()
        } finally {
            try { body?.close() } catch (_: Exception) { throw ParsedImportStorageUnavailable() }
        }
    }
    override fun writeRaw(id: String, content: ByteArray, expected: String?, expiresAt: Instant) {
        try { client.put(bucket, key(id), content, expected, if (expected == null) "*" else null, "application/json",
            mapOf("expires-at" to ParsedImportCodec.timeText(expiresAt))) }
        catch (error: Exception) {
            if (error is ParsedObjectProviderError && error.providerCode in setOf("412", "PreconditionFailed", "ConditionalRequestConflict"))
                throw ParsedImportRevisionConflict()
            throw ParsedImportStorageUnavailable()
        }
    }
    override fun deleteRaw(id: String) {
        try { client.delete(bucket, key(id)) } catch (_: Exception) { throw ParsedImportStorageUnavailable() }
    }
    override fun sweepExpired(cursor: String?, limit: Int, shouldStop: () -> Boolean): ParsedImportSweep {
        sweepArgs(cursor, limit)
        var scanned = 0; var deleted = 0
        try {
            val page = client.list(bucket, "$prefix/parsed/", limit, cursor)
            val next = if (page.truncated) page.nextCursor else null
            if (page.keys.size > limit || page.truncated && (next.isNullOrEmpty() || next == cursor))
                throw ParsedImportStorageUnavailable()
            for (key in page.keys) {
                if (shouldStop()) return ParsedImportSweep(scanned, deleted, cursor)
                scanned++
                if (!key.matches(Regex(Regex.escape(prefix) + "/parsed/[0-9a-f]{64}/session"))) continue
                val head = try { client.head(bucket, key) } catch (error: Exception) {
                    if (missing(error)) continue else throw error
                }
                var expiry = head.metadata["expires-at"]?.let {
                    try { pythonCashTimestamp(it, preserveZSeparator = true) }
                    catch (_: InvalidCashValue) { null }
                    catch (_: ArithmeticException) { null }
                }
                head.lastModified?.plusSeconds(1800)?.let { fallback -> expiry = expiry?.let { minOf(it, fallback) } ?: fallback }
                val deadline = expiry ?: throw ParsedImportStorageUnavailable()
                if (clock() >= deadline && !shouldStop()) { client.delete(bucket, key); deleted++ }
            }
            return ParsedImportSweep(scanned, deleted, next)
        } catch (_: Exception) { throw ParsedImportStorageUnavailable() }
    }
}
