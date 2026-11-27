package com.finance.tracker.client

import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.business.contracts.CashImportConfirmation
import com.finance.tracker.business.contracts.CashImportConfirmationStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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
    suspend fun status(key: String): CashImportConfirmation?
    suspend fun submit(attempt: StoredCashImportSubmission): CashImportConfirmation
    suspend fun recordBaseRejection(attempt: StoredCashImportSubmission, reason: ClientOperationFailure)
    suspend fun rebase(attempt: StoredCashImportSubmission, reason: ClientOperationFailure): CashMutationReceiptDto?
    suspend fun discardRejectedAttempt(attempt: StoredCashImportSubmission, reason: ClientOperationFailure)
    suspend fun complete(attempt: StoredCashImportSubmission, receipt: CashMutationReceiptDto): Result
    suspend fun awaitNextAttempt(attempt: Int) = Unit
    suspend fun awaitStatusPoll() = delay(500)
    suspend fun observeStatus(operation: CashImportConfirmation) = Unit
    fun retriesExhausted(reason: ClientOperationFailure): Nothing = throw reason
}

sealed interface ClientImportConfirmationOutcome<out Result> {
    data class Observed(val operation: CashImportConfirmation) : ClientImportConfirmationOutcome<Nothing>
    data class Complete<Result>(val result: Result) : ClientImportConfirmationOutcome<Result>
}

/** Receipts are authoritative. Only the original operation's explicit refusal permits a rebase. */
suspend fun <Result> resumeCashImportSubmission(host: ClientImportSubmissionHost<Result>): Result =
    completedImport(continueCashImportSubmission(host, waitForReceipt = true))

internal fun <Result> completedImport(outcome: ClientImportConfirmationOutcome<Result>): Result =
    when (outcome) {
        is ClientImportConfirmationOutcome.Complete -> outcome.result
        is ClientImportConfirmationOutcome.Observed -> error("cash_import_final_receipt_required")
    }

suspend fun <Result> continueCashImportSubmission(
    host: ClientImportSubmissionHost<Result>, waitForReceipt: Boolean,
): ClientImportConfirmationOutcome<Result> {
    var retries = 0
    while (true) {
        val original = when (val value = host.current()) {
            is ClientImportSubmissionState.Pending -> value.attempt
            is ClientImportSubmissionState.Complete -> return ClientImportConfirmationOutcome.Complete(value.result)
        }
        try {
            val saved = original.receipt?.let { cashWriteSetJson.decodeFromString<CashMutationReceiptDto>(it) }
            val receipt = saved ?: when (val resolution = resolveFrozenImport(host, original, waitForReceipt)) {
                is FrozenImportResolution.Observed -> return ClientImportConfirmationOutcome.Observed(resolution.operation)
                is FrozenImportResolution.Receipt -> resolution.value
            }
            requireCashWriteSetReceipt(receipt)
            return ClientImportConfirmationOutcome.Complete(host.complete(original, receipt))
        } catch (rejected: ImportBaseRejected) {
            if (retries >= 3) host.retriesExhausted(rejected.reason)
            val receipt = host.rebase(original, rejected.reason)
            if (receipt != null) {
                requireCashWriteSetReceipt(receipt)
                return ClientImportConfirmationOutcome.Complete(host.complete(original, receipt))
            }
            host.awaitNextAttempt(retries)
            retries++
        }
    }
}

private sealed interface FrozenImportResolution {
    class Observed(val operation: CashImportConfirmation) : FrozenImportResolution
    class Receipt(val value: CashMutationReceiptDto) : FrozenImportResolution
}

private class ImportBaseRejected(val reason: ClientOperationFailure) : Exception(reason)

private suspend fun <Result> resolveFrozenImport(
    host: ClientImportSubmissionHost<Result>, original: StoredCashImportSubmission, waitForReceipt: Boolean,
): FrozenImportResolution {
    val existing = lookupImportOperation { host.status(original.key) }
    if (existing == null && original.baseRejected)
        throw ImportBaseRejected(ClientOperationFailure("ledger_base_changed", 409, commitState = "not_committed"))
    try {
        val first = requireCashImportConfirmation(original.key, existing ?: host.submit(original))
        host.observeStatus(first)
        val final = if (!waitForReceipt || !first.status.awaitingResult) first else withTimeoutOrNull(120_000) {
            var status = first
            while (status.status.awaitingResult) {
                host.awaitStatusPoll()
                val next = requireCashImportConfirmation(original.key, lookupImportOperation { host.status(original.key) } ?: throw unknownImport())
                if (next != status) host.observeStatus(next)
                status = next
            }
            status
        } ?: throw unknownImport()
        return when (final.status) {
            CashImportConfirmationStatus.COMMITTED ->
                FrozenImportResolution.Receipt(receiptForConfirmation(final) { host.receipt(original.key) })
            CashImportConfirmationStatus.CANCELLED -> throw ClientOperationFailure("import_cancelled", 409, commitState = "not_committed", operationId = original.key)
            CashImportConfirmationStatus.REJECTED -> {
                val rejection = final.rejection ?: throw unknownImport()
                throw ClientOperationFailure(rejection.code, rejection.httpStatus, commitState = "not_committed")
            }
            else -> FrozenImportResolution.Observed(final)
        }
    } catch (failure: ClientNetworkUnavailable) {
        throw unknownImport(failure)
    } catch (failure: ClientOperationFailure) {
        when {
            failure.status == 409 && failure.code == "ledger_base_changed" && failure.commitState == "not_committed" -> {
                host.recordBaseRejection(original, failure)
                throw ImportBaseRejected(failure)
            }
            failure.commitState == "not_committed" &&
                (failure.status == 422 && failure.code == "write_set_replay_mismatch" ||
                    failure.status == 409 && failure.code == "rules_version_changed" ||
                    failure.status == 413 && failure.code == "payload_too_large") -> {
                lookupImportReceipt { host.receipt(original.key) }?.let { return FrozenImportResolution.Receipt(it) }
                host.discardRejectedAttempt(original, failure)
                throw failure
            }
            failure.commitState != "not_committed" &&
                (failure.commitState == "unknown" || failure.status in setOf(408, 413) ||
                    failure.status?.let { it >= 500 } == true || failure.code in setOf("transport_error", "protocol_error")) ->
                throw unknownImport(failure, failure.status)
            else -> throw failure
        }
    }
}

fun requireCashImportConfirmation(key: String, operation: CashImportConfirmation): CashImportConfirmation {
    if (operation.operationId != key ||
        (operation.status == CashImportConfirmationStatus.COMMITTED) != !operation.receiptId.isNullOrBlank() ||
        (operation.status == CashImportConfirmationStatus.REJECTED) != (operation.rejection != null) ||
        operation.rejection?.let { it.code.isBlank() || it.httpStatus !in 400..599 } == true) {
        throw ClientOperationFailure("protocol_error")
    }
    return operation
}

private val CashImportConfirmationStatus.awaitingResult: Boolean
    get() = this in setOf(CashImportConfirmationStatus.ACCEPTED, CashImportConfirmationStatus.RUNNING,
        CashImportConfirmationStatus.CANCEL_REQUESTED)

private fun unknownImport(cause: Throwable? = null, status: Int? = null): ClientOperationFailure =
    ClientOperationFailure("result_unknown", status, cause, "unknown")

private suspend fun <T> lookupImportOperation(read: suspend () -> T?): T? =
    try {
        read()
    } catch (failure: ClientNetworkUnavailable) {
        throw unknownImport(failure)
    } catch (failure: ClientOperationFailure) {
        if (failure.status in setOf(401, 403) || failure.status == null && failure.code == "workspace_conflict")
            throw failure
        throw unknownImport(failure, failure.status)
    }

internal suspend fun receiptForConfirmation(
    confirmation: CashImportConfirmation, read: suspend () -> CashMutationReceiptDto?,
): CashMutationReceiptDto {
    val receipt = lookupImportReceipt(read) ?: throw unknownImport()
    if (receipt.receiptId != confirmation.receiptId) throw unknownImport()
    requireCashWriteSetReceipt(receipt)
    return receipt
}

suspend fun lookupImportReceipt(read: suspend () -> CashMutationReceiptDto?): CashMutationReceiptDto? =
    lookupImportOperation(read)

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
