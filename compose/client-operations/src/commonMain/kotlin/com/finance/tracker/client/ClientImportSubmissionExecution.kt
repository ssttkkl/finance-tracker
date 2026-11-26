package com.finance.tracker.client

import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.business.contracts.StoredCashImportSubmission
import com.finance.tracker.business.domain.CashImportPlanError
import com.finance.tracker.business.domain.cashWriteSetJson
import com.finance.tracker.business.domain.requireCashWriteSetReceipt

/**
 * The host reads and changes one batch using its captured session and durable store.
 * The frozen logical request may use a platform-specific transport encoding.
 */
sealed interface ClientImportSubmissionState<out Result> {
    data class Pending(val attempt: StoredCashImportSubmission) : ClientImportSubmissionState<Nothing>
    data class Complete<Result>(val result: Result) : ClientImportSubmissionState<Result>
}

interface ClientImportSubmissionHost<Result> {
    suspend fun current(): ClientImportSubmissionState<Result>
    suspend fun receipt(key: String): CashMutationReceiptDto?
    suspend fun submit(attempt: StoredCashImportSubmission): CashMutationReceiptDto
    suspend fun recordBaseRejection(attempt: StoredCashImportSubmission, reason: ClientOperationFailure)
    suspend fun rebase(attempt: StoredCashImportSubmission, reason: ClientOperationFailure): CashMutationReceiptDto?
    suspend fun discardReplayMismatch(attempt: StoredCashImportSubmission)
    suspend fun complete(attempt: StoredCashImportSubmission, receipt: CashMutationReceiptDto): Result
    suspend fun awaitNextAttempt(attempt: Int) = Unit
    fun retriesExhausted(reason: ClientOperationFailure): Nothing = throw reason
}

/** Receipts are authoritative. Only an explicit refusal from the original submit can permit a rebase. */
suspend fun <Result> resumeCashImportSubmission(host: ClientImportSubmissionHost<Result>): Result {
    var retries = 0
    while (true) {
        val original = when (val value = host.current()) {
            is ClientImportSubmissionState.Pending -> value.attempt
            is ClientImportSubmissionState.Complete -> return value.result
        }
        try {
            val receipt = original.receipt?.let { cashWriteSetJson.decodeFromString<CashMutationReceiptDto>(it) }
                ?: resolveFrozenImport(host, original)
            requireCashWriteSetReceipt(receipt)
            return host.complete(original, receipt)
        } catch (rejected: ImportBaseRejected) {
            if (retries >= 3) host.retriesExhausted(rejected.reason)
            val receipt = host.rebase(original, rejected.reason)
            if (receipt != null) {
                requireCashWriteSetReceipt(receipt)
                return host.complete(original, receipt)
            }
            host.awaitNextAttempt(retries)
            retries++
        }
    }
}

private class ImportBaseRejected(val reason: ClientOperationFailure) : Exception(reason)

private suspend fun <Result> resolveFrozenImport(
    host: ClientImportSubmissionHost<Result>, original: StoredCashImportSubmission,
): CashMutationReceiptDto {
    lookupImportReceipt { host.receipt(original.key) }?.let { return it }
    if (original.baseRejected)
        throw ImportBaseRejected(ClientOperationFailure("ledger_base_changed", 409, commitState = "not_committed"))
    return try {
        host.submit(original)
    } catch (failure: ClientNetworkUnavailable) {
        throw ClientOperationFailure("result_unknown", cause = failure, commitState = "unknown")
    } catch (failure: ClientOperationFailure) {
        when {
            failure.status == 409 && failure.code == "ledger_base_changed" && failure.commitState == "not_committed" -> {
                host.recordBaseRejection(original, failure)
                throw ImportBaseRejected(failure)
            }
            failure.status == 422 && failure.code == "write_set_replay_mismatch" && failure.commitState == "not_committed" -> {
                lookupImportReceipt { host.receipt(original.key) }?.let { return it }
                host.discardReplayMismatch(original)
                throw failure
            }
            failure.commitState == "unknown" || failure.status == 408 || failure.status?.let { it >= 500 } == true ||
                failure.code in setOf("transport_error", "protocol_error") ->
                throw ClientOperationFailure("result_unknown", failure.status, failure, "unknown")
            else -> throw failure
        }
    }
}

suspend fun lookupImportReceipt(read: suspend () -> CashMutationReceiptDto?): CashMutationReceiptDto? =
    try {
        read()
    } catch (failure: ClientNetworkUnavailable) {
        throw ClientOperationFailure("result_unknown", cause = failure, commitState = "unknown")
    } catch (failure: ClientOperationFailure) {
        if (failure.status in setOf(401, 403)) throw failure
        throw ClientOperationFailure("result_unknown", failure.status, failure, "unknown")
    }

/** Retire a proven rejection only on newer facts and after rechecking the original receipt. */
suspend fun <Replacement> rebaseRejectedCashImport(
    originalBase: String, newerBase: String, reason: ClientOperationFailure,
    readReceipt: suspend () -> CashMutationReceiptDto?,
    calculate: suspend () -> Replacement,
    retire: suspend (Replacement?) -> Unit,
): CashMutationReceiptDto? {
    if (newerBase.toLong() <= originalBase.toLong()) throw reason
    lookupImportReceipt(readReceipt)?.let { return it }
    val replacement = try {
        calculate()
    } catch (failure: CashImportPlanError) {
        retire(null)
        throw failure
    }
    retire(replacement)
    return null
}
