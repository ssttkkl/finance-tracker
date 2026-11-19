package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashWriteSetRequestDto
import com.finance.tracker.business.contracts.StoredCashImportSubmission
import kotlinx.serialization.json.*

fun decodeStoredCashImportSubmission(text: String): StoredCashImportSubmission {
    val parsed = cashWriteSetJson.parseToJsonElement(text).jsonObject
    val marker = parsed["baseRejected"]
    check(marker == null || marker is JsonPrimitive && !marker.isString && marker.booleanOrNull != null) {
        "cash_import_submission_state_invalid"
    }
    val stored = cashWriteSetJson.decodeFromJsonElement<StoredCashImportSubmission>(parsed)
    check(isCashWriteSetKey(stored.key) && (stored.receipt == null || !stored.baseRejected)) {
        "cash_import_submission_state_invalid"
    }
    cashWriteSetJson.decodeFromString<CashWriteSetRequestDto>(stored.request)
    stored.receipt?.let { requireCashWriteSetReceipt(cashWriteSetJson.decodeFromString(it)) }
    stored.resultInput?.let(LocalCashImportResultInput::decode)
    return stored
}
