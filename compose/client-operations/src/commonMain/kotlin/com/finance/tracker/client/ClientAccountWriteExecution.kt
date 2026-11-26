package com.finance.tracker.client

import com.finance.tracker.business.contracts.AccountCommandViewDto
import com.finance.tracker.business.contracts.CashMutationReceiptDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class AccountWriteExpectation(val kind: String?, val accountId: String? = null, val previousRevision: Long? = null)

enum class AccountWriteAction { SUBMIT, RESUME, VERIFY }

data class AccountWriteEvidence<Result>(
    val result: Result,
    val account: AccountCommandViewDto?,
    val kind: String?,
    val receiptId: String?,
    val accountId: String?,
    val accountRevision: Long?,
    val committed: Boolean,
)

data class ConfirmedAccountWrite<Result>(val result: Result, val account: AccountCommandViewDto, val kind: String)

fun <Result> accountReceiptEvidence(receipt: CashMutationReceiptDto, result: Result) =
    AccountWriteEvidence(result, receipt.account, receipt.operationKind, receipt.receiptId, receipt.accountId,
        receipt.accountRevision, receipt.committed)

fun <Result> accountResponseEvidence(account: AccountCommandViewDto, kind: String, result: Result) =
    AccountWriteEvidence(result, account, kind, account.receiptId, account.accountId, account.accountRevision, account.committed == true)

private val accountWriteKinds = setOf("account_create", "account_update", "account_delete")

fun <Result> validateAccountWrite(
    expected: AccountWriteExpectation, evidence: AccountWriteEvidence<Result>,
): ConfirmedAccountWrite<Result> {
    val account = evidence.account
    val kind = evidence.kind
    if (account == null || kind == null || kind !in accountWriteKinds || !evidence.committed || evidence.receiptId.isNullOrBlank() ||
        account.accountId.toLongOrNull()?.let { it > 0 } != true || account.accountRevision <= 0 ||
        evidence.accountId != account.accountId || evidence.accountRevision != account.accountRevision ||
        account.receiptId != null && account.receiptId != evidence.receiptId ||
        (kind == "account_delete") != account.deleted || expected.kind != null && kind != expected.kind ||
        expected.accountId != null && account.accountId != expected.accountId ||
        expected.previousRevision?.let { it == Long.MAX_VALUE || account.accountRevision != it + 1 } == true)
        throw ClientOperationFailure("protocol_error")
    return ConfirmedAccountWrite(evidence.result, account.copy(committed = true, receiptId = evidence.receiptId), kind)
}

/** Lookup never authorizes a different write. Only a confirmed original submission may be published. */
suspend fun <Result> executeAccountWrite(
    expected: AccountWriteExpectation,
    action: AccountWriteAction,
    read: suspend () -> AccountWriteEvidence<Result>?,
    submit: suspend () -> AccountWriteEvidence<Result>,
): ConfirmedAccountWrite<Result> {
    if (action != AccountWriteAction.SUBMIT) {
        val known = try { read() } catch (failure: ClientNetworkUnavailable) {
            throw unknownAccountWrite(failure)
        } catch (failure: ClientOperationFailure) {
            if (failure.status in setOf(401, 403)) throw failure
            throw unknownAccountWrite(failure)
        }
        if (known != null) return confirmAccountWrite(expected, known)
        if (action == AccountWriteAction.VERIFY) throw ClientOperationFailure("receipt_not_found", 404)
    }
    val submitted = try { submit() } catch (failure: ClientNetworkUnavailable) {
        throw unknownAccountWrite(failure)
    } catch (failure: ClientOperationFailure) {
        if (isAccountWriteRefusal(failure, action == AccountWriteAction.SUBMIT))
            throw ClientOperationFailure(failure.code, failure.status, failure, "not_committed")
        if (failure.status in setOf(401, 403)) throw failure
        throw unknownAccountWrite(failure)
    }
    return confirmAccountWrite(expected, submitted)
}

private fun <Result> confirmAccountWrite(expected: AccountWriteExpectation, evidence: AccountWriteEvidence<Result>): ConfirmedAccountWrite<Result> =
    try { validateAccountWrite(expected, evidence) }
    catch (failure: ClientOperationFailure) { throw unknownAccountWrite(failure) }

private fun unknownAccountWrite(cause: Throwable) =
    ClientOperationFailure("result_unknown", (cause as? ClientOperationFailure)?.status, cause, "unknown")

fun isAccountWriteRefusal(failure: ClientOperationFailure, firstAttempt: Boolean): Boolean =
    failure.commitState == "not_committed" ||
        firstAttempt && failure.status?.let { it in 400..499 } == true && failure.code in definiteAccountRefusals

private val definiteAccountRefusals = setOf(
    "account_payload_invalid", "account_changed", "account_not_found", "account_in_use", "account_active",
    "account_name_unavailable", "workspace_forbidden",
)

internal fun accountWriteExpectation(command: ClientPendingCommand): AccountWriteExpectation {
    if (command.kind == "account_create" || command.body.isEmpty())
        return AccountWriteExpectation(command.kind)
    return try {
        val body: JsonObject = clientJson.parseToJsonElement(command.body).jsonObject
        val path = command.path
        if (path == "/api/v1/cash-import/accounts/currency-requirements") {
            val item = body.getValue("items").jsonArray.single().jsonObject
            val id = item.getValue("account_id").jsonPrimitive.content
            val revision = checkNotNull(item.getValue("expected_revision").jsonPrimitive.longOrNull)
            check(item.getValue("idempotency_key") == JsonPrimitive(command.id))
            AccountWriteExpectation(command.kind, id, revision)
        } else {
            val id = path.removePrefix("/api/v1/accounts/by-id/")
            check(id.toLongOrNull()?.let { it > 0 } == true)
            val revision = checkNotNull(body.getValue("expected_revision").jsonPrimitive.longOrNull)
            AccountWriteExpectation(command.kind, id, revision)
        }
    } catch (failure: Exception) {
        throw unknownAccountWrite(failure)
    }
}
