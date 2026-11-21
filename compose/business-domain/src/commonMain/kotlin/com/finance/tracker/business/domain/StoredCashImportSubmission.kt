package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashWriteSetRequestDto
import com.finance.tracker.business.contracts.StoredCashImportSubmission
import kotlinx.serialization.json.*
import kotlinx.serialization.json.intOrNull

fun decodeStoredCashImportSubmission(text: String): StoredCashImportSubmission =
    inspectStoredCashImportSubmission(text).first

fun inspectStoredCashImportSubmission(text: String): Pair<StoredCashImportSubmission, String> {
    val parsed = cashWriteSetJson.parseToJsonElement(text).jsonObject
    val marker = parsed["baseRejected"]
    check(marker == null || marker is JsonPrimitive && !marker.isString && marker.booleanOrNull != null) {
        "cash_import_submission_state_invalid"
    }
    val stored = cashWriteSetJson.decodeFromJsonElement<StoredCashImportSubmission>(parsed)
    check(isCashWriteSetKey(stored.key) && (stored.receipt == null || !stored.baseRejected)) {
        "cash_import_submission_state_invalid"
    }
    val request = cashWriteSetJson.decodeFromString<CashWriteSetRequestDto>(stored.request)
    stored.receipt?.let { requireCashWriteSetReceipt(cashWriteSetJson.decodeFromString(it)) }
    stored.resultInput?.let(LocalCashImportResultInput::decode)
    stored.transport?.let { transport ->
        check(stored.receipt == null) { "cash_import_submission_state_invalid" }
        val compact = cashWriteSetJson.parseToJsonElement(transport) as? JsonObject ?: throw IllegalArgumentException("cash_import_submission_state_invalid")
        check((compact["command_version"] as? JsonPrimitive)?.intOrNull == 2) { "cash_import_submission_state_invalid" }
        check((compact["base_ledger_seq"] as? JsonPrimitive)?.takeIf { it.isString }?.content == request.baseLedgerSeq) {
            "cash_import_submission_state_invalid"
        }
        verifyCashWriteSetTransport(request, compact)
    }
    return stored to request.baseLedgerSeq
}
