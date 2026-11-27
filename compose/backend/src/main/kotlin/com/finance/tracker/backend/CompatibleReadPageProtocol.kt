package com.finance.tracker.backend

import com.finance.tracker.business.contracts.CASH_IMPORT_RECEIPT_PAGE_PATH
import com.finance.tracker.business.contracts.LEDGER_SNAPSHOT_PAGE_PATH
import com.finance.tracker.business.contracts.ReadPageChunk
import com.finance.tracker.business.contracts.ReadPageFragment
import com.finance.tracker.business.contracts.ReadPagePosition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.suppressCompression
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

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

internal data class ReadPageRequest(val workspace: String, val pin: String?, val position: ReadPagePosition)

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
    return ReadPageRequest(workspace, pin, position)
}

internal suspend fun ApplicationCall.respondReadPage(body: ByteArray, limit: Int) {
    check(body.size in 1..limit)
    suppressCompression()
    response.header(HttpHeaders.CacheControl, "no-store, no-transform")
    respondBytes(body, ContentType.Application.Json)
}

internal fun boundedReadPageChunk(
    position: ReadPagePosition,
    sections: List<String>,
    total: Int,
    candidates: Sequence<JsonElement>,
    targetBytes: Int,
): ReadPageChunk {
    if (position.offset > total || position.offset == total && (total != 0 || position.fragmentOffset != 0))
        throw ReadPageFailure()
    val sectionIndex = sections.indexOf(position.section)
    if (sectionIndex < 0) throw ReadPageFailure()
    fun next(offset: Int): ReadPagePosition? =
        if (offset < total) ReadPagePosition(position.section, offset)
        else sections.getOrNull(sectionIndex + 1)?.let { ReadPagePosition(it, 0) }
    val items = mutableListOf<JsonElement>()
    var remaining = targetBytes - pageEnvelopeReserveBytes
    for (row in candidates) {
        val encoded = row.toString()
        val bytes = encoded.encodeToByteArray()
        if (items.isEmpty() && (position.fragmentOffset > 0 || bytes.size + 1 > remaining)) {
            if (position.fragmentOffset >= bytes.size) throw ReadPageFailure()
            val size = minOf(bytes.size - position.fragmentOffset, remaining / 4 * 3)
            val end = position.fragmentOffset + size
            val fragment = ReadPageFragment(bytes.size,
                Base64.getEncoder().encodeToString(bytes.copyOfRange(position.fragmentOffset, end)))
            return ReadPageChunk(position, total, emptyList(), fragment,
                if (end < bytes.size) position.copy(fragmentOffset = end) else next(position.offset + 1))
        }
        if (bytes.size + 1 > remaining || items.size == readPageRowWindow) break
        items += row
        remaining -= bytes.size + 1
    }
    check(items.isNotEmpty() || position.offset == total)
    check(items.size <= total - position.offset)
    return ReadPageChunk(position, total, items, null, next(position.offset + items.size))
}
