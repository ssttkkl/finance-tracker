package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.business.contracts.CashWriteSetRequestDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Immutable original operation. Its text is sensitive local recovery data, never a diagnostic. */
internal class LocalCashImportSubmission private constructor(
    val idempotencyKey: String,
    val requestBody: String,
    val receiptBody: String?,
    val baseRejected: Boolean,
) {
    fun receipt(): CashMutationReceiptDto? = receiptBody?.let { cashWriteSetJson.decodeFromString<CashMutationReceiptDto>(it) }

    fun withReceipt(receipt: CashMutationReceiptDto): LocalCashImportSubmission {
        validateCashWriteSetReceipt(receipt)
        val previous = receipt()
        if (previous != null) {
            check(previous == receipt) { "cash_import_receipt_changed" }
            return this
        }
        return LocalCashImportSubmission(idempotencyKey, requestBody, cashWriteSetJson.encodeToString(receipt), false)
    }

    fun withBaseRejection(): LocalCashImportSubmission {
        check(receiptBody == null) { "cash_import_submission_committed" }
        return if (baseRejected) this else LocalCashImportSubmission(idempotencyKey, requestBody, null, true)
    }

    fun encode(): String = cashWriteSetJson.encodeToString(StoredSubmission(idempotencyKey, requestBody, receiptBody, baseRejected))

    companion object {
        fun freeze(key: String, input: CashWriteSetRequestDto): LocalCashImportSubmission {
            cashKeyHeaders(key)
            return LocalCashImportSubmission(key, cashWriteSetJson.encodeToString(input), null, false)
        }

        fun restore(key: String, request: String, receipt: String? = null, baseRejected: Boolean = false): LocalCashImportSubmission {
            cashKeyHeaders(key)
            check(receipt == null || !baseRejected) { "cash_import_submission_state_invalid" }
            cashWriteSetJson.decodeFromString<CashWriteSetRequestDto>(request)
            receipt?.let { validateCashWriteSetReceipt(cashWriteSetJson.decodeFromString(it)) }
            return LocalCashImportSubmission(key, request, receipt, baseRejected)
        }

        fun decode(text: String): LocalCashImportSubmission {
            val marker = cashWriteSetJson.parseToJsonElement(text).jsonObject["baseRejected"]
            check(marker == null || marker is JsonPrimitive && !marker.isString && marker.booleanOrNull != null) {
                "cash_import_submission_state_invalid"
            }
            val stored = cashWriteSetJson.decodeFromString<StoredSubmission>(text)
            return restore(stored.key, stored.request, stored.receipt, stored.baseRejected)
        }

        fun freezeReceipt(receipt: CashMutationReceiptDto): String {
            validateCashWriteSetReceipt(receipt)
            return cashWriteSetJson.encodeToString(receipt)
        }
    }
}

@Serializable
private data class StoredSubmission(val key: String, val request: String, val receipt: String?, val baseRejected: Boolean = false)

/** A confirmed write stays successful when its local recovery work fails; retain the original cause. */
internal data class LocalCashImportSubmissionResult(val receipt: CashMutationReceiptDto, val recoveryFailure: Throwable? = null)

/** Both the authoritative rejection and the local failure remain available without relying on suppressed exceptions. */
internal class LocalCashImportRejectionStorageFailure(val rejection: ApiFailure, cause: Throwable) :
    Exception("cash_import_rejection_storage_failed", cause)
