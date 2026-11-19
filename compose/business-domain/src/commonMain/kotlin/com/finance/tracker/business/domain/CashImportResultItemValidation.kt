package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.business.contracts.CashMutationResultDto
import kotlinx.serialization.SerializationException

/** The permanent service defines item_ref as an explicit original array position, never an opaque preview ref. */
fun validateImportResultItems(receipt: CashMutationReceiptDto, items: List<CashMutationResultDto>) {
    val actual = checkNotNull(receipt.writeSetResult)
    val rows = mutableSetOf<Int>()
    val relations = mutableSetOf<Int>()
    val ids = mutableSetOf<String>()
    for (item in items) {
        importResultId(item.resultId)
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

fun importResultId(text: String, allowZero: Boolean = false): Long = text.toLongOrNull()?.takeIf {
    it >= (if (allowZero) 0 else 1) && it.toString() == text
} ?: invalidImportResultItems()

fun invalidImportResultItems(): Nothing = throw SerializationException("cash_import_result_items_invalid")
