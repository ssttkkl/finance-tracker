package com.finance.tracker.cli

import com.finance.tracker.business.contracts.CashImportArchivePage
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.SerialName
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.EncodeDefault
import com.finance.tracker.business.domain.page
import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.business.contracts.CashMutationResultDto
import com.finance.tracker.business.domain.CashImportResultContent
import com.finance.tracker.business.domain.resultItems
import com.finance.tracker.client.ClientOperationFailure
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

private const val archiveReceiptUnits = 64 * 1024
private const val archivePageSize = 200

internal data class ImportResultRange(val kind: String, val offset: Int, val limit: Int)

@Serializable
private data class ArchivedImportOutput(
    val stamp: String,
    val fingerprint: String,
    val generation: String,
    val receiptBlocks: Int,
    val rows: Int,
    val relations: Int,
    val text: String,
)

internal fun writeArchivedImportResult(
    database: dynamic, batchSlot: String, stamp: String, fingerprint: String, archive: CashImportResultContent,
) {
    val root = archiveOutputSlot(batchSlot)
    val generation = newReference()
    val blocks = "$root.$generation"
    val receipt = archive.displayReceipt()
    val receiptJson = archivedReceiptJson(receipt)
    var position = 0
    var receiptBlocks = 0
    while (position < receiptJson.length) {
        var end = position + minOf(archiveReceiptUnits, receiptJson.length - position)
        if (end < receiptJson.length && receiptJson[end - 1].isHighSurrogate()) end--
        database.write("$blocks.receipt.$receiptBlocks", receiptJson.substring(position, end))
        position = end
        receiptBlocks++
    }
    val rows = writeResultItems(database, blocks, "row", archive.resultItems("row"))
    val relations = writeResultItems(database, blocks, "relation", archive.resultItems("relation"))
    val index = ArchivedImportOutput(stamp, fingerprint, generation, receiptBlocks, rows, relations, importResultText(archive.batchId, receipt, complete = true))
    database.write(root, Json.encodeToString(index))
}

private fun writeResultItems(database: dynamic, blocks: String, kind: String, items: List<CashMutationResultDto>): Int {
    var position = 0
    while (position < items.size) {
        val end = position + minOf(archivePageSize, items.size - position)
        database.write("$blocks.$kind.${position / archivePageSize}", Json.encodeToString(items.subList(position, end)))
        position = end
    }
    return items.size
}

internal fun readArchivedImportResult(
    database: dynamic, batchSlot: String, batchId: String, request: () -> ImportResultRange,
): TerminalResult? {
    val root = archiveOutputSlot(batchSlot)
    val index = readArchiveState {
        val text = database.read(root) as String? ?: return null
        val value = Json.decodeFromString<ArchivedImportOutput>(text)
        val stamp = database.stateStamp(batchSlot) as String? ?: return null
        if (stamp != value.stamp) return null
        check(database.stateFingerprint(batchSlot) == value.fingerprint)
        check(value.receiptBlocks > 0 && value.rows >= 0 && value.relations >= 0)
        value
    }

    val (kind, offset, limit) = request()
    validateImportResultRange(kind, offset, limit)
    val total = if (kind == "row") index.rows else index.relations
    if (offset > total) throw CliFailure("invalid_parameter", "--offset")
    return readArchiveState {
        val blocks = "$root.${index.generation}"
        val end = offset + minOf(limit, total - offset)
        val items = readResultItems(database, blocks, kind, offset, end, total)
        val page = CashImportArchivePage(items, end.takeIf { it < total }?.toString())
        // Authenticate every part of this output in the current transaction before returning it to the terminal.
        val parts = buildList {
            add("{\"batch_id\":${JsonPrimitive(batchId)},\"receipt\":")
            for (block in 0 until index.receiptBlocks) {
                add(checkNotNull(database.read("$blocks.receipt.$block") as String?))
            }
            add(",\"items_complete\":true,\"page\":${outputJson.encodeToString(page)}}")
        }
        TerminalResult(index.text, parts)
    }
}

private fun readResultItems(
    database: dynamic, blocks: String, kind: String, offset: Int, end: Int, total: Int,
): List<CashMutationResultDto> = buildList {
    var position = offset
    while (position < end) {
        val chunk = position / archivePageSize
        val start = chunk * archivePageSize
        val text = checkNotNull(database.read("$blocks.$kind.$chunk") as String?)
        val items = Json.decodeFromString<List<CashMutationResultDto>>(text)
        check(items.size == minOf(archivePageSize, total - start))
        val stop = minOf(end, start + items.size)
        addAll(items.subList(position - start, stop - start))
        position = stop
    }
}

private fun archiveOutputSlot(batchSlot: String): String = "$batchSlot.file.archive-output-v1"

private inline fun <T> readArchiveState(read: () -> T): T = try {
    read()
} catch (failure: Throwable) {
    throw ClientOperationFailure("local_state_unreadable", cause = failure)
}

internal fun validateImportResultRange(kind: String, offset: Int, limit: Int) {
    if (kind != "row" && kind != "relation" || offset < 0 || limit !in 1..200) throw CliFailure("invalid_parameter")
}

internal fun importResult(result: CashImportResultContent, kind: String = "row", offset: Int = 0, limit: Int = 50): TerminalResult {
    validateImportResultRange(kind, offset, limit)
    val page = if (result.hasServerItems) try {
        result.page(kind, offset, limit)
    } catch (_: IllegalArgumentException) {
        throw CliFailure("invalid_parameter", "--offset")
    } else null
    val receipt = result.displayReceipt()
    val output = ImportResultOutput(result.batchId, receipt, result.hasServerItems, page)
    return TerminalResult(importResultText(result.batchId, receipt, result.hasServerItems), output::encode)
}

internal fun importResultText(batchId: String, receipt: CashMutationReceiptDto, complete: Boolean): String = buildString {
    appendLine("Batch $batchId  Receipt ${receipt.receiptId}")
    for ((key, count) in receipt.summary) appendLine("$key  $count")
    if (!complete) appendLine("Saved. Use ft import resume $batchId to finish retrieving results.")
}.trimEnd()

private fun CashMutationReceiptDto.needsExactResultIdentifiers(): Boolean =
    projectionVersion != null || accountRevision != null || account != null ||
        summary.keys.any { it in exactReadIdentifierFields } || legacyByAccount.keys.any { it in exactReadIdentifierFields }

private fun CashMutationReceiptDto.receiptWithExactIdentifiers() =
    exactReadIdentifiers(outputJson.encodeToJsonElement(CashMutationReceiptDto.serializer(), this))

internal fun archivedReceiptJson(receipt: CashMutationReceiptDto): String =
    if (receipt.needsExactResultIdentifiers()) receipt.receiptWithExactIdentifiers().toString()
    else outputJson.encodeToString(CashMutationReceiptDto.serializer(), receipt)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class ImportResultOutput(
    @SerialName("batch_id") val batchId: String,
    val receipt: CashMutationReceiptDto,
    @SerialName("items_complete") val itemsComplete: Boolean,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val page: CashImportArchivePage? = null,
) {
    fun encode(): String {
        if (!receipt.needsExactResultIdentifiers())
            return outputJson.encodeToString(this)
        // Permitted historical receipt metadata still uses the same exact numeric identifier conversion.
        return buildJsonObject {
            put("batch_id", batchId)
            put("receipt", receipt.receiptWithExactIdentifiers())
            put("items_complete", itemsComplete)
            page?.let { put("page", outputJson.encodeToJsonElement(CashImportArchivePage.serializer(), it)) }
        }.toString()
    }
}
