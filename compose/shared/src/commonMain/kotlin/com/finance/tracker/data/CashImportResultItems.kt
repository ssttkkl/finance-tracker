package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.business.contracts.CashMutationResultDto
import com.finance.tracker.business.contracts.CashMutationResultPageDto
import io.ktor.http.HttpMethod
import kotlinx.serialization.SerializationException

/** Permanent operation outcomes only. Current server projection references are deliberately not retained. */
internal suspend fun FinanceApiClient.readCashImportResultItems(workspaceId: String, receipt: CashMutationReceiptDto,
    credentials: ApiCredentialSnapshot, checkActive: suspend () -> Unit): List<CashMutationResultDto> {
    validateCashWriteSetReceipt(receipt)
    val identities = checkNotNull(receipt.writeSetResult)
    val result = mutableListOf<CashMutationResultDto>()
    for ((kind, expected) in listOf("rows" to identities.transactions.size, "relations" to identities.relations.size)) {
        if (expected == 0) continue
        var after = "0"
        var received = 0
        while (true) {
            checkActive()
            val page: CashMutationResultPageDto = request(HttpMethod.Get,
                appendQuery("/api/v1/cash-mutations/receipts/${encodePathSegment(receipt.receiptId)}/$kind",
                    listOf("workspace_id" to workspaceId, "after" to after, "limit" to "200")),
                clearUnauthorizedToken = false, credentials = credentials,
                preserveHttpRejectionOnBodyFailure = true, requireResponseBody = true)
            checkActive()
            if (page.items.size > 200 || page.items.size > expected - received) invalidImportResultItems()
            var last = resultId(after, allowZero = true)
            for (item in page.items) {
                val next = resultId(item.resultId)
                if (next <= last || item.resultKind != (if (kind == "rows") "row" else "relation")) invalidImportResultItems()
                last = next
            }
            result += page.items
            received += page.items.size
            val next = page.nextAfter ?: break
            if (received == expected || page.items.isEmpty() || next != page.items.last().resultId || resultId(next) <= resultId(after, true)) invalidImportResultItems()
            after = next
        }
        if (received != expected) invalidImportResultItems()
    }
    validateImportResultItems(receipt, result)
    return result.toList()
}

/** The permanent service defines item_ref as an explicit original array position, never an opaque preview ref. */
internal fun validateImportResultItems(receipt: CashMutationReceiptDto, items: List<CashMutationResultDto>) {
    val actual = checkNotNull(receipt.writeSetResult)
    val rows = mutableSetOf<Int>()
    val relations = mutableSetOf<Int>()
    val ids = mutableSetOf<String>()
    for (item in items) {
        resultId(item.resultId)
        if (!ids.add(item.resultId)) invalidImportResultItems()
        val index = item.itemRef.substringAfter(':', "").toIntOrNull() ?: invalidImportResultItems()
        if (index < 0 || item.itemRef != "${item.resultKind}:$index" || item.accountId != null) invalidImportResultItems()
        when (item.resultKind) {
            "row" -> if (!rows.add(index) || index !in actual.transactions.indices ||
                item.transactionId != actual.transactions[index].id || item.relationId != null ||
                item.outcome !in setOf("created", "updated", "unchanged")) invalidImportResultItems()
            "relation" -> if (!relations.add(index) || index !in actual.relations.indices ||
                item.relationId != actual.relations[index].id || item.transactionId != null ||
                item.outcome !in setOf("confirmed", "deferred", "ignored", "duplicate")) invalidImportResultItems()
            else -> invalidImportResultItems()
        }
    }
    if (rows.size != actual.transactions.size || relations.size != actual.relations.size) invalidImportResultItems()
}

private fun resultId(text: String, allowZero: Boolean = false): Long = text.toLongOrNull()?.takeIf {
    it >= (if (allowZero) 0 else 1) && it.toString() == text
} ?: invalidImportResultItems()

private fun invalidImportResultItems(): Nothing = throw SerializationException("cash_import_result_items_invalid")
