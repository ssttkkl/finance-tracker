package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashMutationReceiptDto
import kotlinx.serialization.json.Json

val cashWriteSetJson = Json { encodeDefaults = true; explicitNulls = true }

fun isCashWriteSetKey(key: String): Boolean = key.matches(Regex("[A-Za-z0-9_:.~-]{1,255}"))

fun requireCashWriteSetReceipt(receipt: CashMutationReceiptDto) {
    val sequence = receipt.ledgerSeq
    check(receipt.committed && receipt.operationKind == "cash_write_set" && receipt.planId == null &&
        receipt.writeSetResult != null && sequence != null && receipt.receiptId.isNotBlank()) { "cash_write_set_receipt_invalid" }
    check(sequence.toLongOrNull()?.let { it >= 0 && it.toString() == sequence } == true) { "ledger_version_invalid" }
}
