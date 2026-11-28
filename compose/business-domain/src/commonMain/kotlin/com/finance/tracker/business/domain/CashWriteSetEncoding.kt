package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashMutationReceiptDto
import kotlinx.serialization.json.Json

val cashWriteSetJson = Json { encodeDefaults = true; explicitNulls = true }

const val CASH_WRITE_SET_MAX_BYTES = 64 * 1024 * 1024

fun cashWriteSetFitsBudget(body: String): Boolean = cashWriteSetFitsUtf8Budget(body, CASH_WRITE_SET_MAX_BYTES)

private val malformedUtf8Bytes = "\ud800".encodeToByteArray().size

internal fun cashWriteSetFitsUtf8Budget(body: String, limit: Int): Boolean {
    if (body.length > limit) return false
    var remaining = limit - body.length
    var index = 0
    while (index < body.length) {
        val unit = body[index++]
        if (unit.code < 0x80) continue
        remaining -= when {
            unit.code < 0x800 -> 1
            unit.isHighSurrogate() && index < body.length && body[index].isLowSurrogate() -> {
                index++
                2
            }
            unit.isSurrogate() -> malformedUtf8Bytes - 1
            else -> 2
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
