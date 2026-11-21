package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.business.contracts.CashWriteSetRequestDto
import com.finance.tracker.business.contracts.StoredCashImportSubmission
import com.finance.tracker.business.domain.inspectStoredCashImportSubmission
import com.finance.tracker.business.domain.verifyCashWriteSetTransport
import kotlinx.serialization.json.jsonObject

/** Immutable original operation. Its text is sensitive local recovery data, never a diagnostic. */
internal class LocalCashImportSubmission private constructor(
    val idempotencyKey: String,
    val requestBody: String,
    val receiptBody: String?,
    val baseRejected: Boolean,
    val resultInput: LocalCashImportResultInput?,
    knownBase: String? = null,
    /** Frozen compact transport chosen for this exact operation; null keeps the original full command. */
    val transportBody: String? = null,
) {
    fun request(): CashWriteSetRequestDto = cashWriteSetJson.decodeFromString(requestBody)
    val baseLedgerSeq by lazy { knownBase ?: request().baseLedgerSeq }
    private val confirmed by lazy { receiptBody?.let { cashWriteSetJson.decodeFromString<CashMutationReceiptDto>(it) } }
    fun receipt(): CashMutationReceiptDto? = confirmed

    fun forAttempt(key: String): LocalCashImportSubmission {
        cashKeyHeaders(key)
        check(receiptBody == null && !baseRejected) { "cash_import_submission_state_invalid" }
        return LocalCashImportSubmission(key, requestBody, null, false, resultInput, baseLedgerSeq, transportBody)
    }

    fun withReceipt(receipt: CashMutationReceiptDto): LocalCashImportSubmission {
        validateCashWriteSetReceipt(receipt)
        val previous = receipt()
        if (previous != null) {
            check(previous == receipt) { "cash_import_receipt_changed" }
            return this
        }
        // A committed receipt is the authoritative result; the transport choice no longer affects replay.
        return LocalCashImportSubmission(idempotencyKey, requestBody, cashWriteSetJson.encodeToString(receipt), false, resultInput, baseLedgerSeq, null)
    }

    fun withBaseRejection(): LocalCashImportSubmission {
        check(receiptBody == null) { "cash_import_submission_committed" }
        return if (baseRejected) this else LocalCashImportSubmission(idempotencyKey, requestBody, null, true, resultInput, baseLedgerSeq, transportBody)
    }

    fun encode(): String = cashWriteSetJson.encodeToString(storageValue())

    fun storageValue() = StoredCashImportSubmission(idempotencyKey, requestBody, receiptBody, baseRejected,
        resultInput?.encode(), transportBody)

    fun withoutTransport(): LocalCashImportSubmission = LocalCashImportSubmission(
        idempotencyKey, requestBody, receiptBody, baseRejected, resultInput, baseLedgerSeq, null)

    fun withTransport(transport: String): LocalCashImportSubmission {
        cashKeyHeaders(idempotencyKey)
        check(receiptBody == null && !baseRejected && this.transportBody == null) { "cash_import_submission_state_invalid" }
        val compact = cashWriteSetJson.parseToJsonElement(transport) as? kotlinx.serialization.json.JsonObject
            ?: throw IllegalArgumentException("cash_import_submission_state_invalid")
        check((compact["base_ledger_seq"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content == baseLedgerSeq) {
            "cash_import_submission_state_invalid"
        }
        verifyCashWriteSetTransport(request(), compact)
        return LocalCashImportSubmission(idempotencyKey, requestBody, null, false, resultInput, baseLedgerSeq, transport)
    }

    companion object {
        internal fun fromWorker(key: String, request: String, resultInput: String, base: String,
            transport: String? = null): LocalCashImportSubmission {
            cashKeyHeaders(key)
            transport?.let {
                verifyCashWriteSetTransport(cashWriteSetJson.decodeFromString(request),
                    cashWriteSetJson.parseToJsonElement(it).jsonObject)
            }
            return LocalCashImportSubmission(key, request, null, false, LocalCashImportResultInput.fromWorker(resultInput), base, transport)
        }

        internal fun fromVerifiedStorage(key: String, request: String, receipt: String?, baseRejected: Boolean,
            resultInput: String?, base: String? = null, transport: String? = null): LocalCashImportSubmission {
            transport?.let {
                check(receipt == null) { "cash_import_submission_state_invalid" }
                verifyCashWriteSetTransport(cashWriteSetJson.decodeFromString(request),
                    cashWriteSetJson.parseToJsonElement(it).jsonObject)
            }
            return LocalCashImportSubmission(key, request, receipt, baseRejected, resultInput?.let(LocalCashImportResultInput::fromWorker), base, transport)
        }

        fun freeze(key: String, input: CashWriteSetRequestDto, resultInput: LocalCashImportResultInput? = null,
            transport: String? = null): LocalCashImportSubmission {
            cashKeyHeaders(key)
            return LocalCashImportSubmission(key, cashWriteSetJson.encodeToString(input), null, false, resultInput, input.baseLedgerSeq, transport)
        }

        fun restore(key: String, request: String, receipt: String? = null, baseRejected: Boolean = false,
            resultInput: LocalCashImportResultInput? = null, transport: String? = null): LocalCashImportSubmission {
            cashKeyHeaders(key)
            check(receipt == null || !baseRejected) { "cash_import_submission_state_invalid" }
            val decoded = cashWriteSetJson.decodeFromString<CashWriteSetRequestDto>(request)
            receipt?.let { validateCashWriteSetReceipt(cashWriteSetJson.decodeFromString(it)) }
            transport?.let {
                check(receipt == null) { "cash_import_submission_state_invalid" }
                verifyCashWriteSetTransport(decoded, cashWriteSetJson.parseToJsonElement(it).jsonObject)
            }
            return LocalCashImportSubmission(key, request, receipt, baseRejected, resultInput, transportBody = transport)
        }

        fun decode(text: String): LocalCashImportSubmission = inspectStoredCashImportSubmission(text).let { (stored, base) ->
            fromVerifiedStorage(stored.key, stored.request, stored.receipt, stored.baseRejected, stored.resultInput, base, stored.transport)
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
