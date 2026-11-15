package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.business.contracts.CashWriteSetRequestDto
import kotlinx.serialization.Serializable

/** Immutable original operation. Its text is sensitive local recovery data, never a diagnostic. */
internal class LocalCashImportSubmission private constructor(
    val idempotencyKey: String,
    val requestBody: String,
    val receiptBody: String?,
) {
    fun receipt(): CashMutationReceiptDto? = receiptBody?.let { cashWriteSetJson.decodeFromString<CashMutationReceiptDto>(it) }

    fun withReceipt(receipt: CashMutationReceiptDto): LocalCashImportSubmission {
        validateCashWriteSetReceipt(receipt)
        val previous = receipt()
        if (previous != null) {
            check(previous == receipt) { "cash_import_receipt_changed" }
            return this
        }
        return LocalCashImportSubmission(idempotencyKey, requestBody, cashWriteSetJson.encodeToString(receipt))
    }

    fun encode(): String = cashWriteSetJson.encodeToString(StoredSubmission(idempotencyKey, requestBody, receiptBody))

    companion object {
        fun freeze(key: String, input: CashWriteSetRequestDto): LocalCashImportSubmission {
            cashKeyHeaders(key)
            return LocalCashImportSubmission(key, cashWriteSetJson.encodeToString(input), null)
        }

        fun restore(key: String, request: String, receipt: String? = null): LocalCashImportSubmission {
            cashKeyHeaders(key)
            cashWriteSetJson.decodeFromString<CashWriteSetRequestDto>(request)
            receipt?.let { validateCashWriteSetReceipt(cashWriteSetJson.decodeFromString(it)) }
            return LocalCashImportSubmission(key, request, receipt)
        }

        fun decode(text: String): LocalCashImportSubmission {
            val stored = cashWriteSetJson.decodeFromString<StoredSubmission>(text)
            return restore(stored.key, stored.request, stored.receipt)
        }

        fun freezeReceipt(receipt: CashMutationReceiptDto): String {
            validateCashWriteSetReceipt(receipt)
            return cashWriteSetJson.encodeToString(receipt)
        }
    }
}

@Serializable
private data class StoredSubmission(val key: String, val request: String, val receipt: String?)

/** A confirmed write stays successful when its local recovery work fails; retain the original cause. */
internal data class LocalCashImportSubmissionResult(val receipt: CashMutationReceiptDto, val recoveryFailure: Throwable? = null)
