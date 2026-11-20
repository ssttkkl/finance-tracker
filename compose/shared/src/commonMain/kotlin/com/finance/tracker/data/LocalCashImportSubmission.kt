package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.business.contracts.CashWriteSetRequestDto
import com.finance.tracker.business.contracts.StoredCashImportSubmission
import com.finance.tracker.business.domain.inspectStoredCashImportSubmission

/** Immutable original operation. Its text is sensitive local recovery data, never a diagnostic. */
internal class LocalCashImportSubmission private constructor(
    val idempotencyKey: String,
    val requestBody: String,
    val receiptBody: String?,
    val baseRejected: Boolean,
    val resultInput: LocalCashImportResultInput?,
    knownBase: String? = null,
) {
    fun request(): CashWriteSetRequestDto = cashWriteSetJson.decodeFromString(requestBody)
    val baseLedgerSeq by lazy { knownBase ?: request().baseLedgerSeq }
    private val confirmed by lazy { receiptBody?.let { cashWriteSetJson.decodeFromString<CashMutationReceiptDto>(it) } }
    fun receipt(): CashMutationReceiptDto? = confirmed

    fun forAttempt(key: String): LocalCashImportSubmission {
        cashKeyHeaders(key)
        check(receiptBody == null && !baseRejected) { "cash_import_submission_state_invalid" }
        return LocalCashImportSubmission(key, requestBody, null, false, resultInput, baseLedgerSeq)
    }

    fun withReceipt(receipt: CashMutationReceiptDto): LocalCashImportSubmission {
        validateCashWriteSetReceipt(receipt)
        val previous = receipt()
        if (previous != null) {
            check(previous == receipt) { "cash_import_receipt_changed" }
            return this
        }
        return LocalCashImportSubmission(idempotencyKey, requestBody, cashWriteSetJson.encodeToString(receipt), false, resultInput, baseLedgerSeq)
    }

    fun withBaseRejection(): LocalCashImportSubmission {
        check(receiptBody == null) { "cash_import_submission_committed" }
        return if (baseRejected) this else LocalCashImportSubmission(idempotencyKey, requestBody, null, true, resultInput, baseLedgerSeq)
    }

    fun encode(): String = cashWriteSetJson.encodeToString(storageValue())

    fun storageValue() = StoredCashImportSubmission(idempotencyKey, requestBody, receiptBody, baseRejected, resultInput?.encode())

    companion object {
        internal fun fromWorker(key: String, request: String, resultInput: String, base: String): LocalCashImportSubmission {
            cashKeyHeaders(key)
            return LocalCashImportSubmission(key, request, null, false, LocalCashImportResultInput.fromWorker(resultInput), base)
        }

        internal fun fromVerifiedStorage(key: String, request: String, receipt: String?, baseRejected: Boolean,
            resultInput: String?, base: String? = null): LocalCashImportSubmission =
            LocalCashImportSubmission(key, request, receipt, baseRejected, resultInput?.let(LocalCashImportResultInput::fromWorker), base)

        fun freeze(key: String, input: CashWriteSetRequestDto, resultInput: LocalCashImportResultInput? = null): LocalCashImportSubmission {
            cashKeyHeaders(key)
            return LocalCashImportSubmission(key, cashWriteSetJson.encodeToString(input), null, false, resultInput, input.baseLedgerSeq)
        }

        fun restore(key: String, request: String, receipt: String? = null, baseRejected: Boolean = false, resultInput: LocalCashImportResultInput? = null): LocalCashImportSubmission {
            cashKeyHeaders(key)
            check(receipt == null || !baseRejected) { "cash_import_submission_state_invalid" }
            cashWriteSetJson.decodeFromString<CashWriteSetRequestDto>(request)
            receipt?.let { validateCashWriteSetReceipt(cashWriteSetJson.decodeFromString(it)) }
            return LocalCashImportSubmission(key, request, receipt, baseRejected, resultInput)
        }

        fun decode(text: String): LocalCashImportSubmission = inspectStoredCashImportSubmission(text).let { (stored, base) ->
            fromVerifiedStorage(stored.key, stored.request, stored.receipt, stored.baseRejected, stored.resultInput, base)
        }

        fun freezeReceipt(receipt: CashMutationReceiptDto): String {
            validateCashWriteSetReceipt(receipt)
            return cashWriteSetJson.encodeToString(receipt)
        }
    }
}

internal expect suspend fun readStoredCashImportSubmission(body: String): LocalCashImportSubmission

/** A confirmed write stays successful when its local recovery work fails; retain the original cause. */
internal data class LocalCashImportSubmissionResult(val receipt: CashMutationReceiptDto, val recoveryFailure: Throwable? = null)

/** Both the authoritative rejection and the local failure remain available without relying on suppressed exceptions. */
internal class LocalCashImportRejectionStorageFailure(val rejection: ApiFailure, cause: Throwable) :
    Exception("cash_import_rejection_storage_failed", cause)
