package com.finance.tracker.client

import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.business.contracts.AccountCommandViewDto
import com.finance.tracker.business.domain.CashAmount
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
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
        execute(workspace, "account_create", "/api/v1/accounts", emptyList(), batch = batch) { _, _ ->
            buildJsonObject {
                put("name", name)
                put("type", type.name.lowercase())
                put("currencies", buildJsonArray { currencies.forEach { add(it) } })
            }
        }

    internal suspend fun ensureImportAccountCurrencies(
        batch: EditableImportBatch, before: AccountCommandViewDto, currencies: List<String>,
    ): ClientCashWriteResult =
        execute(batch.workspaceId, "account_update", "/api/v1/cash-import/accounts/currency-requirements",
            emptyList(), batch = batch, beforeAccount = before) { _, key ->
            buildJsonObject {
                put("items", buildJsonArray { add(buildJsonObject {
                    put("idempotency_key", key)
                    put("account_id", before.accountId)
                    put("expected_revision", before.accountRevision)
                    put("currencies", buildJsonArray { currencies.forEach { add(it) } })
                }) })
            }
        }

    internal suspend fun reverseAccountChange(
        batch: EditableImportBatch, original: ClientPendingCommand, current: AccountCommandViewDto,
    ): ClientCashWriteResult {
        val created = original.beforeAccount == null
        val method = if (created && !current.active) "DELETE" else "PATCH"
        if (method == "DELETE" && current.capabilities["can_delete"] != true)
            throw ClientOperationFailure("account_in_use")
        if (method == "PATCH" && current.capabilities["can_update"] != true)
            throw ClientOperationFailure("workspace_forbidden", 403)
        val kind = if (method == "DELETE") "account_delete" else "account_update"
        return execute(batch.workspaceId, kind, "/api/v1/accounts/by-id/${session.component(current.accountId)}", emptyList(),
            method, batch, reverses = original.id, completesReversal = method == "DELETE" || !created) { _, _ ->
            buildJsonObject {
                put("expected_revision", current.accountRevision)
                if (method == "PATCH") put("patch", buildJsonObject {
                    if (created) put("active", false)
                    else put("currencies", buildJsonArray { original.beforeAccount.currencies.forEach { add(it) } })
                })
            }
        }
    }

    internal suspend fun importCommands(batch: EditableImportBatch): List<ClientPendingCommand> {
        val commands = store.commands(batch.ledger.binding, batch.workspaceId).filter { it.batchId == batch.batch.id }
        session.requireCurrent(batch.ledger.binding)
        return commands
    }

    suspend fun add(workspace: String?, input: NewCashRecord): ClientCashWriteResult {
        if (CashAmount.parseOrNull(input.amount) == null) throw ClientOperationFailure("invalid_amount")
        return execute(workspace, "cash_create_manual", "/api/v1/cash-records/commands", listOf(input.accountId)) { ledger, _ ->
            val account = accountNames(ledger, listOf(input.accountId)).single()
            buildJsonObject {
                put("account_name", account)
                put("amount", input.amount)
                put("currency", input.currency)
                put("occurred_at", input.occurredAt)
                put("record_type", input.recordType)
                input.recordSubtype?.let { put("record_subtype", it) }
                input.counterparty?.let { put("counterparty", it) }
                input.note?.let { put("note", it) }
                input.categoryId?.let { put("category_id", it) }
            }
        }
    }

    suspend fun transfer(workspace: String?, input: NewCashTransfer): ClientCashWriteResult {
        if (CashAmount.parseOrNull(input.amount) == null || input.toAmount?.let { CashAmount.parseOrNull(it) == null } == true)
            throw ClientOperationFailure("invalid_amount")
        val ids = listOf(input.fromAccountId, input.toAccountId)
        return execute(workspace, "cash_create_transfer", "/api/v1/cashflow/transfer/commands", ids) { ledger, _ ->
            val (from, to) = accountNames(ledger, ids)
            buildJsonObject {
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
        }
    }

    suspend fun commitMaintenance(workspace: String?, planId: String, revision: Long): ClientCashWriteResult {
        if (revision < 1 || planId.isBlank()) throw ClientOperationFailure("plan_input_invalid")
        return execute(workspace, "mutation", "/api/v1/cash-mutations/plans/${session.component(planId)}/commit", emptyList()) { _, _ ->
            buildJsonObject { put("expected_plan_revision", revision) }
        }
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
        workspace: String?, kind: String, path: String, accountIds: List<String>,
        method: String = "POST", batch: EditableImportBatch? = null, beforeAccount: AccountCommandViewDto? = null,
        reverses: String? = null, completesReversal: Boolean = false,
        payload: (ClientLedgerRead, String) -> JsonObject,
    ): ClientCashWriteResult {
        val ledger = batch?.ledger ?: session.ledger(workspace)
        session.requireOnlineWriter(ledger)
        val id = nextId()
        val command = ClientPendingCommand(id, kind, path, payload(ledger, id).toString(), accountIds,
            method = method, batchId = batch?.batch?.id, beforeAccount = beforeAccount, reverses = reverses, completesReversal = completesReversal)
        save(ledger, null, command, batch?.batch)
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
        val result = try {
            executeCashCommand(command, lookupFirst,
                findReceipt = { key ->
                    if (ledger.offline) throw ClientNetworkUnavailable()
                    session.cashImportReceipt(ledger, key)
                },
                submit = { original ->
                    if (ledger.offline) throw ClientNetworkUnavailable()
                    session.submitCommand(ledger, original)
                })
        } catch (failure: ClientOperationFailure) {
            if (failure.commitState == "not_committed" && command.rejection == null)
                save(ledger, command, command.copy(rejection = failure.code, receipt = null, body = "", accountIds = emptyList()))
            throw failure
        }
        if (command.receipt == null)
            save(ledger, command, command.copy(receipt = result.receipt, body = "", accountIds = emptyList()))
        return result
    }

    private suspend fun save(
        ledger: ClientLedgerRead, previous: ClientPendingCommand?, command: ClientPendingCommand, expectedBatch: ClientCashImportBatch? = null,
    ) {
        session.requireCurrent(ledger.binding)
        if (!store.saveCommand(ledger.binding, ledger.ledger.snapshot.workspaceId, previous, command, expectedBatch))
            throw ClientOperationFailure("operation_conflict", operationId = command.id)
    }

    private fun accountNames(ledger: ClientLedgerRead, ids: List<String>): List<String> {
        val accounts = ledger.ledger.snapshot.facts.accounts.associateBy { it.id }
        return ids.map { id ->
            accounts[id]?.takeIf { it.active && it.type in setOf("cash", "loan", "lend") }?.name
                ?: throw ClientOperationFailure("account_not_available")
        }
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
    val receipt = command.receipt ?: try {
        val known = if (lookupFirst) {
            try {
                findReceipt(command.id)
            } catch (failure: ClientOperationFailure) {
                if (failure.status in setOf(401, 403)) throw failure
                throw ClientOperationFailure("result_unknown", failure.status, failure, "unknown", command.id)
            }
        } else null
        known ?: submit(command)
    } catch (failure: ClientNetworkUnavailable) {
        throw ClientOperationFailure("result_unknown", cause = failure, commitState = "unknown", operationId = command.id)
    } catch (failure: ClientOperationFailure) {
        if (failure.commitState == "unknown" || failure.status == 408 || failure.status?.let { it >= 500 } == true ||
            failure.code in setOf("transport_error", "protocol_error"))
            throw ClientOperationFailure("result_unknown", failure.status, failure, "unknown", command.id)
        throw failure
    }
    val creation = command.kind in setOf("cash_create_manual", "cash_create_transfer")
    if (receipt.operationKind != command.kind || !receipt.committed || receipt.receiptId.isBlank() ||
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
