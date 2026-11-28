package com.finance.tracker.backend

import com.finance.tracker.business.contracts.CASH_IMPORT_RECEIPT_PAGE_PATH
import com.finance.tracker.business.contracts.ExactJsonElementSerializer
import com.finance.tracker.business.contracts.LEDGER_SNAPSHOT_PAGE_PATH
import com.finance.tracker.business.contracts.READ_PAGE_BUNDLE_HEADER
import com.finance.tracker.business.contracts.READ_PAGE_BUNDLE_VERSION
import com.finance.tracker.business.contracts.READ_PAGE_CHUNKS_HEADER
import com.finance.tracker.business.contracts.READ_PAGE_CHUNK_MAX_ITEMS
import com.finance.tracker.business.contracts.ReadPageChunk
import com.finance.tracker.business.contracts.ReadPageFragment
import com.finance.tracker.business.contracts.ReadPagePosition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseHeaderValue
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.suppressCompression
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

internal const val snapshotReadPagePath = LEDGER_SNAPSHOT_PAGE_PATH
internal const val importReceiptReadPagePath = CASH_IMPORT_RECEIPT_PAGE_PATH
internal const val readPageRowWindow = 1024
internal const val snapshotReadPageTargetBytes = 4 * 1024 * 1024
internal const val importReceiptReadPageTargetBytes = 768 * 1024
private const val pageEnvelopeReserveBytes = 2048

internal val compatibleReadPageJson = Json { encodeDefaults = true; explicitNulls = true }

internal class ReadPageFailure(val code: String = "read_page_invalid", val status: HttpStatusCode = HttpStatusCode.BadRequest) :
    RuntimeException(code)

internal fun readPageChanged(): Nothing = throw ReadPageFailure("read_page_changed", HttpStatusCode.Conflict)

internal data class ReadPageRequest(val workspace: String, val pin: String?, val position: ReadPagePosition,
    val bundled: Boolean = false)

internal data class ReadPageWindow(val position: ReadPagePosition, val targetBytes: Int, val rows: Int)

internal fun ApplicationCall.readPageRequest(pinName: String, sections: List<String>): ReadPageRequest {
    val query = request.queryParameters
    if (query.names().any { it !in setOf("workspace_id", pinName, "section", "offset", "fragment_offset") } ||
        query.names().any { query.getAll(it)?.size != 1 }) throw ReadPageFailure()
    fun number(name: String): Int? = query[name]?.let { raw ->
        raw.toIntOrNull()?.takeIf { it >= 0 && it.toString() == raw } ?: throw ReadPageFailure()
    }
    val workspace = query["workspace_id"]?.takeIf { it.isNotEmpty() } ?: throw ReadPageFailure()
    val pin = query[pinName]?.takeIf { it.isNotEmpty() } ?: if (pinName in query.names()) throw ReadPageFailure() else null
    val section = query["section"]
    val offset = number("offset")
    val fragment = number("fragment_offset")
    val position = if (section == null && offset == null && fragment == null && pin == null) {
        ReadPagePosition(sections.first(), 0)
    } else {
        if (pin == null || section !in sections || offset == null) throw ReadPageFailure()
        ReadPagePosition(checkNotNull(section), offset, fragment ?: 0)
    }
    return ReadPageRequest(workspace, pin, position,
        request.headers[READ_PAGE_BUNDLE_HEADER] == READ_PAGE_BUNDLE_VERSION)
}

internal fun ReadPageRequest.readChunks(targetBytes: Int, read: (ReadPageWindow) -> EncodedReadPageChunk): List<EncodedReadPageChunk> {
    if (!bundled) return listOf(read(ReadPageWindow(position, targetBytes, readPageRowWindow)))
    val chunks = mutableListOf<EncodedReadPageChunk>()
    var remaining = targetBytes
    var position = position
    while (true) {
        val chunk = read(ReadPageWindow(position, remaining, READ_PAGE_CHUNK_MAX_ITEMS))
        chunks += chunk
        val next = chunk.next ?: break
        if (next.section == position.section) break
        remaining -= chunk.encodedSize() + 1
        if (remaining < 2 * pageEnvelopeReserveBytes) break
        position = next
    }
    return chunks
}

internal fun ReadPageRequest.chunkPositions(chunks: List<EncodedReadPageChunk>): String? = if (bundled) {
    chunks.joinToString(",") { chunk ->
        val (section, offset, fragmentOffset) = chunk.position
        val base64 = chunk.fragment?.base64
        val fragmentBytes = base64?.let { it.length / 4 * 3 - (it.length - it.trimEnd('=').length) } ?: 0
        "$section:$offset:$fragmentOffset:${chunk.total}:${chunk.items.size}:${chunk.fragment?.totalBytes ?: 0}:$fragmentBytes"
    }
} else null

internal data class EncodedReadPage(val body: ByteArray, val gzip: Boolean, val chunkPositions: String? = null)

@Serializable
internal data class EncodedReadPageChunk(
    val position: ReadPagePosition,
    val total: Int,
    val items: List<JsonElement>,
    val fragment: ReadPageFragment?,
    val next: ReadPagePosition?,
    @Transient val itemBytes: Int = 0,
) {
    fun headerChunk() = ReadPageChunk(position, total, emptyList(), fragment, next)
    fun encodedSize(): Int = compatibleReadPageJson.encodeToString(headerChunk()).encodeToByteArray().size + itemBytes
}

internal inline fun <reified T> encodedReadPageBytes(header: T, chunks: List<EncodedReadPageChunk>): ByteArray {
    val fields = compatibleReadPageJson.encodeToJsonElement(header).jsonObject.toMutableMap()
    val encoded = chunks.map { compatibleReadPageJson.encodeToJsonElement(EncodedReadPageChunk.serializer(), it) }
    fields["chunk"] = encoded.first()
    if (encoded.size > 1) fields["more_chunks"] = JsonArray(encoded.drop(1))
    return SnapshotReadTrace.stage("page_envelope_utf8") {
        compatibleReadPageJson.encodeToString(JsonObject(fields)).encodeToByteArray()
    }
}

internal class ReadPageEncoding(private val gzipQuality: Double, private val identityQuality: Double) {
    fun encode(body: ByteArray, limit: Int, chunkPositions: String? = null): EncodedReadPage {
        check(body.size in 1..limit)
        if (gzipQuality > 0 && gzipQuality >= identityQuality &&
            (gzipQuality > identityQuality || body.size >= 1024)) {
            val compressed = SnapshotReadTrace.stage("page_gzip") { gzipReadPage(body) }
            if (compressed.size <= limit && (gzipQuality > identityQuality || compressed.size < body.size))
                return EncodedReadPage(compressed, gzip = true, chunkPositions)
        }
        if (identityQuality == 0.0) throw ReadPageFailure("read_encoding_not_acceptable", HttpStatusCode.NotAcceptable)
        return EncodedReadPage(body, gzip = false, chunkPositions)
    }
}

internal fun ApplicationCall.readPageEncoding(): ReadPageEncoding {
    val preferences = request.headers.getAll(HttpHeaders.AcceptEncoding).orEmpty().flatMap(::parseHeaderValue)
    fun quality(name: String): Double? = preferences.filter { it.value.equals(name, ignoreCase = true) }.maxOfOrNull { it.quality }
    val wildcard = quality("*")
    val identity = quality("identity") ?: if (wildcard == 0.0) 0.0 else 1.0
    val gzip = quality("gzip") ?: wildcard ?: 0.0
    if (gzip == 0.0 && identity == 0.0) throw ReadPageFailure("read_encoding_not_acceptable", HttpStatusCode.NotAcceptable)
    return ReadPageEncoding(gzip, identity)
}

internal suspend fun ApplicationCall.respondReadPage(page: EncodedReadPage) {
    suppressCompression()
    response.header(HttpHeaders.CacheControl, "no-store, no-transform")
    response.header(HttpHeaders.Vary, "${HttpHeaders.AcceptEncoding}, $READ_PAGE_BUNDLE_HEADER")
    page.chunkPositions?.let { response.header(READ_PAGE_CHUNKS_HEADER, it) }
    if (page.gzip) response.header(HttpHeaders.ContentEncoding, "gzip")
    respondBytes(page.body, ContentType.Application.Json)
}

private fun gzipReadPage(body: ByteArray): ByteArray {
    val buffer = ByteArrayOutputStream(minOf(body.size, 64 * 1024))
    GZIPOutputStream(buffer).use { it.write(body) }
    return buffer.toByteArray()
}

@OptIn(ExperimentalSerializationApi::class)
internal fun boundedReadPageChunk(
    position: ReadPagePosition,
    sections: List<String>,
    total: Int,
    candidates: Sequence<JsonElement>,
    targetBytes: Int,
    rowWindow: Int = readPageRowWindow,
): EncodedReadPageChunk {
    if (position.offset > total || position.offset == total && (total != 0 || position.fragmentOffset != 0))
        throw ReadPageFailure()
    val sectionIndex = sections.indexOf(position.section)
    if (sectionIndex < 0) throw ReadPageFailure()
    fun next(offset: Int): ReadPagePosition? =
        if (offset < total) ReadPagePosition(position.section, offset)
        else sections.getOrNull(sectionIndex + 1)?.let { ReadPagePosition(it, 0) }
    val items = mutableListOf<JsonElement>()
    var itemBytes = 0
    var remaining = targetBytes - pageEnvelopeReserveBytes
    for (row in candidates) {
        val (encoded, size) = SnapshotReadTrace.stage("row_json_and_size") {
            val text = compatibleReadPageJson.encodeToString(ExactJsonElementSerializer, row)
            text to text.encodeToByteArray().size
        }
        if (items.isEmpty() && (position.fragmentOffset > 0 || size + 1 > remaining)) {
            // A continuation can refer to bytes emitted by the previous server version.
            val bytes = row.toString().encodeToByteArray()
            if (position.fragmentOffset >= bytes.size) throw ReadPageFailure()
            val end = position.fragmentOffset + minOf(bytes.size - position.fragmentOffset, remaining / 4 * 3)
            val fragment = ReadPageFragment(bytes.size,
                Base64.getEncoder().encodeToString(bytes.copyOfRange(position.fragmentOffset, end)))
            return EncodedReadPageChunk(position, total, emptyList(), fragment,
                if (end < bytes.size) position.copy(fragmentOffset = end) else next(position.offset + 1))
        }
        if (size + 1 > remaining || items.size == rowWindow) break
        // Keep JSON emitted by the normal row serializer so the cursor's object graph can be reclaimed.
        if (items.isNotEmpty()) itemBytes++
        items += if (row == JsonNull) JsonNull else JsonUnquotedLiteral(encoded)
        itemBytes += size
        remaining -= size + 1
    }
    check(items.isNotEmpty() || position.offset == total)
    check(items.size <= total - position.offset)
    return EncodedReadPageChunk(position, total, items, null, next(position.offset + items.size), itemBytes)
}
