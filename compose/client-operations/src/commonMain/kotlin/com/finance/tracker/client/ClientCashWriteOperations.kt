package com.finance.tracker.client

import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.business.contracts.AccountCommandViewDto
import com.finance.tracker.business.contracts.ImportCurrencyRequirementDto
import com.finance.tracker.business.domain.CashAmount
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class CashAccountType { CASH, LOAN, LEND }

data class NewCashRecord(
    val accountId: String,
    val amount: String,
    val currency: String,
    val occurredAt: String,
    val recordType: String,
    val recordSubtype: String? = null,
    val counterparty: String? = null,
    val note: String? = null,
    val categoryId: String? = null,
    val includeCategory: Boolean = categoryId != null,
    val counterpartyAccount: String? = null,
    val counterpartyAccountAttributes: List<String>? = null,
)

data class NewCashTransfer(
    val fromAccountId: String,
    val toAccountId: String,
    val amount: String,
    val fromCurrency: String,
    val toCurrency: String,
    val date: String,
    val time: String,
    val toAmount: String? = null,
    val note: String? = null,
)

@Serializable
data class ClientPendingCommand(
    val id: String,
    val kind: String,
    val path: String,
    val body: String,
    val accountIds: List<String>,
    val receipt: CashMutationReceiptDto? = null,
    val method: String = "POST",
    val batchId: String? = null,
    val beforeAccount: AccountCommandViewDto? = null,
    val reverses: String? = null,
    val completesReversal: Boolean = false,
    val rejection: String? = null,
    val planId: String? = null,
) {
    override fun toString(): String = "ClientPendingCommand(id=$id, kind=$kind)"
}

interface ClientCashCommandStore {
    suspend fun commands(session: ClientStoredSession, workspaceId: String): List<ClientPendingCommand>
    suspend fun command(session: ClientStoredSession, workspaceId: String, id: String): ClientPendingCommand?
    /** Freeze account commands only if the initial batch and session both still match in the same transaction. */
    suspend fun saveCommand(
        session: ClientStoredSession, workspaceId: String, previous: ClientPendingCommand?, command: ClientPendingCommand,
        expectedBatch: ClientCashImportBatch? = null,
    ): Boolean
    suspend fun checkpoint()
}

@Serializable
data class ClientPendingWrite(val id: String, val kind: String)

data class ClientCashWriteResult(val operationId: String, val receipt: CashMutationReceiptDto)

class ClientCashWriteOperations(
    private val session: ClientSessionOperations,
    private val store: ClientCashCommandStore,
    private val nextId: () -> String,
) {
    suspend fun createAccount(
        workspace: String?, name: String, type: CashAccountType, currencies: List<String>,
    ): ClientCashWriteResult = createAccount(workspace, name, type, currencies, batch = null)

    internal suspend fun createAccount(
        workspace: String?, name: String, type: CashAccountType, currencies: List<String>, batch: EditableImportBatch?,
    ): ClientCashWriteResult =
        executeAccount(workspace, "account_create", batch = batch) { key ->
            ClientAccountOperations.create(name, type.name.lowercase(), currencies, key)
        }

    internal suspend fun ensureImportAccountCurrencies(
        batch: EditableImportBatch, before: AccountCommandViewDto, currencies: List<String>,
    ): ClientCashWriteResult =
        executeAccount(batch.workspaceId, "account_update", batch, before) { key ->
            ClientAccountOperations.currencyRequirements(listOf(ImportCurrencyRequirementDto(key, before.accountId,
                before.accountRevision, currencies)))
        }

    internal suspend fun reverseAccountChange(
        batch: EditableImportBatch, original: ClientPendingCommand, action: AccountCompensationAction,
    ): ClientCashWriteResult {
        val current = action.current
        val deletes = action.kind == AccountCompensationKind.DELETE
        val kind = if (deletes) "account_delete" else "account_update"
        return executeAccount(batch.workspaceId, kind, batch, reverses = original.id,
            completesReversal = action.kind != AccountCompensationKind.DEACTIVATE) { key ->
            if (deletes) ClientAccountOperations.delete(current.accountId, current.accountRevision, key, session::component)
            else ClientAccountOperations.update(current.accountId, current.accountRevision, checkNotNull(action.patch), key, session::component)
        }
    }

    private suspend fun executeAccount(
        workspace: String?, kind: String, batch: EditableImportBatch?, before: AccountCommandViewDto? = null,
        reverses: String? = null, completesReversal: Boolean = false,
        request: (String) -> ClientAccountRequest,
    ): ClientCashWriteResult {
        val ledger = batch?.ledger ?: session.ledger(workspace)
        session.requireOnlineWriter(ledger)
        val id = nextId()
        val command = request(id)
        return freezeAndExecute(ledger, ClientPendingCommand(id, kind, command.path, checkNotNull(command.body), emptyList(),
            method = command.method, batchId = batch?.batch?.id, beforeAccount = before, reverses = reverses,
            completesReversal = completesReversal), batch?.batch)
    }

    internal suspend fun importCommands(batch: EditableImportBatch): List<ClientPendingCommand> {
        val commands = store.commands(batch.ledger.binding, batch.workspaceId).filter { it.batchId == batch.batch.id }
        session.requireCurrent(batch.ledger.binding)
        return commands
    }

    suspend fun add(workspace: String?, input: NewCashRecord): ClientCashWriteResult =
        execute(workspace) { ledger, key ->
            freezeCashRecord(input, accountNames(ledger, listOf(input.accountId)).single(), key)
        }

    suspend fun transfer(workspace: String?, input: NewCashTransfer): ClientCashWriteResult =
        execute(workspace) { ledger, key ->
            val (from, to) = accountNames(ledger, listOf(input.fromAccountId, input.toAccountId))
            freezeCashTransfer(input, from, to, key)
        }

    suspend fun commitMaintenance(workspace: String?, planId: String, revision: Long): ClientCashWriteResult {
        val command = freezeMaintenanceCommand(planId, revision, nextId(), session::component)
        val ledger = session.ledger(workspace)
        session.requireOnlineWriter(ledger)
        return freezeAndExecute(ledger, command)
    }

    suspend fun pending(workspace: String?): List<ClientPendingWrite> {
        val ledger = session.ledger(workspace)
        val pending = store.commands(ledger.binding, ledger.ledger.snapshot.workspaceId)
            .filter { it.receipt == null && it.rejection == null }.map { ClientPendingWrite(it.id, it.kind) }
        session.requireCurrent(ledger.binding)
        return pending
    }

    suspend fun resume(workspace: String?, id: String): ClientCashWriteResult {
        val ledger = session.ledger(workspace)
        val command = original(ledger, id)
        return complete(ledger, command, lookupFirst = true)
    }

    private suspend fun execute(
        workspace: String?, freeze: (ClientLedgerRead, String) -> ClientPendingCommand,
    ): ClientCashWriteResult {
        val ledger = session.ledger(workspace)
        session.requireOnlineWriter(ledger)
        return freezeAndExecute(ledger, freeze(ledger, nextId()))
    }

    private suspend fun freezeAndExecute(
        ledger: ClientLedgerRead, command: ClientPendingCommand, batch: ClientCashImportBatch? = null,
    ): ClientCashWriteResult {
        save(ledger, null, command, batch)
        store.checkpoint()
        return complete(ledger, original(ledger, command.id), lookupFirst = false)
    }

    private suspend fun original(ledger: ClientLedgerRead, id: String): ClientPendingCommand {
        session.requireCurrent(ledger.binding)
        val command = store.command(ledger.binding, ledger.ledger.snapshot.workspaceId, id)
        session.requireCurrent(ledger.binding)
        return command ?: throw ClientOperationFailure("operation_not_found", 404)
    }

    private suspend fun complete(ledger: ClientLedgerRead, command: ClientPendingCommand, lookupFirst: Boolean): ClientCashWriteResult {
        val verified = withMaintenancePlan(command, session::component)
        val result = try {
            val original = if (lookupFirst && verified.planId != null && command.receipt == null && command.rejection == null) {
                resolveMaintenanceCommand(ledger, verified)
            } else verified
            if (original.kind.startsWith("account_")) completeAccount(ledger, original, lookupFirst)
            else executeCashCommand(original, lookupFirst && verified.planId == null,
                findReceipt = { key ->
                    if (ledger.offline) throw ClientNetworkUnavailable()
                    session.cashWriteReceipt(ledger, key)
                },
                submit = { original ->
                    if (ledger.offline) throw ClientNetworkUnavailable()
                    session.submitCommand(ledger, original)
                })
        } catch (failure: ClientOperationFailure) {
            if (failure.commitState == "not_committed" && command.rejection == null)
                save(ledger, command, verified.copy(rejection = failure.code, receipt = null, body = "", accountIds = emptyList()))
            if (failure.operationId == null && failure.commitState != null)
                throw ClientOperationFailure(failure.code, failure.status, failure, failure.commitState, command.id)
            throw failure
        }
        if (command.receipt == null)
            save(ledger, command, verified.copy(receipt = result.receipt, body = "", accountIds = emptyList()))
        return result
    }

    private suspend fun completeAccount(ledger: ClientLedgerRead, original: ClientPendingCommand, lookupFirst: Boolean): ClientCashWriteResult {
        original.rejection?.let { throw ClientOperationFailure(it, commitState = "not_committed") }
        val action = when {
            original.receipt != null -> AccountWriteAction.VERIFY
            lookupFirst -> AccountWriteAction.RESUME
            else -> AccountWriteAction.SUBMIT
        }
        val confirmed = executeAccountWrite(accountWriteExpectation(original), action, read = {
            val receipt = original.receipt ?: run {
                if (ledger.offline) throw ClientNetworkUnavailable()
                session.accountReceipt(ledger, original.id)
            }
            receipt?.let { accountReceiptEvidence(it, it) }
        }, submit = {
            if (ledger.offline) throw ClientNetworkUnavailable()
            val receipt = session.submitCommand(ledger, original)
            accountReceiptEvidence(receipt, receipt)
        })
        return ClientCashWriteResult(original.id, confirmed.result)
    }

    private suspend fun resolveMaintenanceCommand(ledger: ClientLedgerRead, command: ClientPendingCommand): ClientPendingCommand {
        val resolution = resumeCashMaintenance(canReplay = { true }, verify = {
            try {
                val response = session.boundCashMaintenance(ledger).resolve(ledger.ledger.snapshot.workspaceId,
                    checkNotNull(command.planId), command.id)
                cashMaintenanceRecovery(response.status, response.receipt)
            } catch (failure: ClientNetworkUnavailable) {
                throw ClientOperationFailure("result_unknown", cause = failure, commitState = "unknown", operationId = command.id)
            } catch (failure: ClientOperationFailure) {
                if (failure.status in setOf(401, 403)) throw failure.withoutWriteProof()
                throw ClientOperationFailure("result_unknown", failure.status, failure, "unknown", command.id)
            }
        }, submit = {
            executeCashCommand(command, lookupFirst = false, findReceipt = { error("original_retry_does_not_query") },
                submit = { session.submitCommand(ledger, it) }).receipt
        })
        return when (resolution.status) {
            CashMaintenanceRecoveryStatus.COMMITTED -> command.copy(receipt = checkNotNull(resolution.receipt))
            CashMaintenanceRecoveryStatus.NOT_COMMITTED -> throw ClientOperationFailure("commit_not_applied", 409,
                commitState = "not_committed", operationId = command.id)
            CashMaintenanceRecoveryStatus.PENDING, CashMaintenanceRecoveryStatus.RETRY_ORIGINAL ->
                throw ClientOperationFailure("result_unknown", commitState = "unknown", operationId = command.id)
        }
    }

    private suspend fun save(
        ledger: ClientLedgerRead, previous: ClientPendingCommand?, command: ClientPendingCommand, expectedBatch: ClientCashImportBatch? = null,
    ) {
        session.requireCurrent(ledger.binding)
        if (!store.saveCommand(ledger.binding, ledger.ledger.snapshot.workspaceId, previous, command, expectedBatch))
            throw ClientOperationFailure("operation_conflict", operationId = command.id)
    }

    private fun accountNames(ledger: ClientLedgerRead, ids: List<String>): List<String> {
        val accounts = ledger.ledger.snapshot.facts.accounts
        return ids.map { id ->
            cashAccountFact(accounts, id)?.takeIf { it.active }?.name
                ?: throw ClientOperationFailure("account_not_available")
        }
    }
}

fun freezeCashRecord(input: NewCashRecord, accountName: String, key: String): ClientPendingCommand {
    if (CashAmount.parseOrNull(input.amount) == null) throw ClientOperationFailure("invalid_amount")
    val body = buildJsonObject {
        put("account_name", accountName)
        put("amount", input.amount)
        put("currency", input.currency)
        put("occurred_at", input.occurredAt)
        put("record_type", input.recordType)
        input.recordSubtype?.let { put("record_subtype", it) }
        input.counterparty?.let { put("counterparty", it) }
        input.counterpartyAccount?.let { put("counterparty_account", it) }
        input.counterpartyAccountAttributes?.let { put("counterparty_account_attrs", JsonArray(it.map(::JsonPrimitive))) }
        input.note?.let { put("note", it) }
        if (input.includeCategory) put("category_id", input.categoryId?.let(::JsonPrimitive) ?: JsonNull)
    }
    return ClientPendingCommand(key, "cash_create_manual", "/api/v1/cash-records/commands", body.toString(), listOf(input.accountId))
}

fun freezeCashTransfer(input: NewCashTransfer, from: String, to: String, key: String): ClientPendingCommand {
    if (CashAmount.parseOrNull(input.amount) == null || input.toAmount?.let { CashAmount.parseOrNull(it) == null } == true)
        throw ClientOperationFailure("invalid_amount")
    val body = buildJsonObject {
        put("from_account", from)
        put("to_account", to)
        put("amount", input.amount)
        put("from_currency", input.fromCurrency)
        put("to_currency", input.toCurrency)
        put("date", input.date)
        put("time", input.time)
        input.toAmount?.let { put("to_amount", it) }
        input.note?.let { put("note", it) }
    }
    return ClientPendingCommand(key, "cash_create_transfer", "/api/v1/cashflow/transfer/commands", body.toString(),
        listOf(input.fromAccountId, input.toAccountId))
}

private val legacyMaintenancePath = Regex("/api/v1/cash-mutations/plans/(mp_[A-Za-z0-9_-]{43}\\.[A-Za-z0-9_-]{16})/commit")

private fun withMaintenancePlan(command: ClientPendingCommand, encode: (String) -> String): ClientPendingCommand {
    if (command.kind != "mutation") return command
    val planId = command.planId ?: legacyMaintenancePath.matchEntire(command.path)?.groupValues?.get(1)
    if (planId == null || command.path != "/api/v1/cash-mutations/plans/${encode(planId)}/commit" ||
        command.method != "POST" || !com.finance.tracker.business.domain.isCashWriteSetKey(command.id))
        throw ClientOperationFailure("result_unknown", commitState = "unknown", operationId = command.id)
    return command.copy(planId = planId)
}

fun freezeMaintenanceCommand(planId: String, revision: Long, key: String, encode: (String) -> String): ClientPendingCommand {
    if (revision < 1 || revision == Long.MAX_VALUE || planId.isBlank() || !com.finance.tracker.business.domain.isCashWriteSetKey(key))
        throw ClientOperationFailure("plan_input_invalid")
    return ClientPendingCommand(key, "mutation", "/api/v1/cash-mutations/plans/${encode(planId)}/commit",
        buildJsonObject { put("expected_plan_revision", revision) }.toString(), emptyList(), planId = planId)
}

fun cashWriteWasRejected(kind: String, status: Int?, code: String, commitState: String?): Boolean {
    if (commitState != null) return commitState == "not_committed"
    return when (kind) {
        "cash_create_manual" -> status in setOf(400, 422) && code == "invalid_record"
        "mutation" -> status in setOf(409, 410) &&
            code in setOf("record_changed", "issue_changed", "relation_context_changed", "plan_changed", "plan_expired")
        else -> false
    }
}

/** The host supplies the original scope, frozen command, and access to its permanent receipt. */
suspend fun executeCashCommand(
    command: ClientPendingCommand,
    lookupFirst: Boolean,
    findReceipt: suspend (String) -> CashMutationReceiptDto?,
    submit: suspend (ClientPendingCommand) -> CashMutationReceiptDto,
): ClientCashWriteResult {
    command.rejection?.let {
        throw ClientOperationFailure(it, commitState = "not_committed", operationId = command.id)
    }
    val creation = command.kind in setOf("cash_create_manual", "cash_create_transfer")
    val receipt = command.receipt ?: try {
        val known = if (lookupFirst) {
            try {
                findReceipt(command.id)
            } catch (failure: ClientOperationFailure) {
                if (failure.status in setOf(401, 403)) throw failure.withoutWriteProof()
                throw ClientOperationFailure("result_unknown", failure.status, failure, "unknown", command.id)
            }
        } else null
        known ?: submit(command)
    } catch (failure: ClientNetworkUnavailable) {
        throw ClientOperationFailure("result_unknown", cause = failure, commitState = "unknown", operationId = command.id)
    } catch (failure: ClientOperationFailure) {
        if (failure.status in setOf(401, 403) && failure.commitState == "unknown") throw failure
        if (cashWriteWasRejected(command.kind, failure.status, failure.code, failure.commitState))
            throw ClientOperationFailure(failure.code, failure.status, failure, "not_committed", command.id)
        if (failure.commitState == "unknown" || failure.status == 408 || failure.status?.let { it >= 500 } == true ||
            failure.code in setOf("transport_error", "protocol_error"))
            throw ClientOperationFailure("result_unknown", failure.status, failure, "unknown", command.id)
        throw failure
    }
    if (receipt.operationKind != command.kind || !receipt.committed || receipt.receiptId.isBlank() ||
        command.planId != null && receipt.planId != command.planId ||
        creation && receipt.ledgerSeq == null)
        throw ClientOperationFailure("result_unknown", commitState = "unknown", operationId = command.id)
    if (creation && receipt.status == "failed") {
        val reason = receipt.commitOutcome?.takeIf { !it.hasBusinessWrites && it.hasNewFailures }?.reasonCodes?.singleOrNull()
        val code = when (reason) {
            "account_changed" -> "account_changed"
            "row_save_failed" -> "invalid_record"
            else -> throw ClientOperationFailure("result_unknown", commitState = "unknown", operationId = command.id)
        }
        throw ClientOperationFailure(code, 409, commitState = "not_committed", operationId = command.id)
    }
    return ClientCashWriteResult(command.id, receipt)
}
