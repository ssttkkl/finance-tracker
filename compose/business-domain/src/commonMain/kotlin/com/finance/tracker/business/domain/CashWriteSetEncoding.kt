package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashMutationReceiptDto
import kotlinx.serialization.json.Json

val cashWriteSetJson = Json { encodeDefaults = true; explicitNulls = true }

const val CASH_WRITE_SET_MAX_BYTES = 64 * 1024 * 1024

fun cashWriteSetFitsBudget(body: String): Boolean = cashWriteSetFitsUtf8Budget(body, CASH_WRITE_SET_MAX_BYTES)

internal fun cashWriteSetFitsUtf8Budget(body: String, maxBytes: Int): Boolean {
    if (body.length > maxBytes) return false
    var remaining = maxBytes - body.length
    var index = 0
    while (index < body.length) {
        val char = body[index++]
        when {
            char.code < 0x80 -> Unit
            char.code < 0x800 -> remaining--
            char.isHighSurrogate() -> {
                if (index == body.length || !body[index].isLowSurrogate())
                    return body.encodeToByteArray().size <= maxBytes
                index++
                remaining -= 2
            }
            char.isLowSurrogate() -> return body.encodeToByteArray().size <= maxBytes
            else -> remaining -= 2
        }
        if (remaining < 0) return false
    }
    return true
}

fun isCashWriteSetKey(key: String): Boolean = key.matches(Regex("[A-Za-z0-9_:.~-]{1,255}"))

fun requireCashWriteSetReceipt(receipt: CashMutationReceiptDto) {
    val sequence = receipt.ledgerSeq
    check(receipt.committed && receipt.operationKind == "cash_write_set" && receipt.planId == null &&
        receipt.writeSetResult != null && sequence != null && receipt.receiptId.isNotBlank()) { "cash_write_set_receipt_invalid" }
    check(sequence.toLongOrNull()?.let { it >= 0 && it.toString() == sequence } == true) { "ledger_version_invalid" }
}
