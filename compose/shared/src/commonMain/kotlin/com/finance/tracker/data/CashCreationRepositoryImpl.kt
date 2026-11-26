package com.finance.tracker.data

import com.finance.tracker.domain.*
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import com.finance.tracker.client.ClientOperationFailure
import com.finance.tracker.client.NewCashRecord
import com.finance.tracker.client.NewCashTransfer
import com.finance.tracker.client.freezeCashRecord
import com.finance.tracker.client.freezeCashTransfer
import com.finance.tracker.client.ClientPendingCommand

class KtorCashCreationRepository(private val api: FinanceApiClient,
    private val captureRefresh: suspend () -> (suspend (String?) -> Unit) = { {} }) : CashCreationRepository {
    private var operations: CashBusinessOperations? = null
    private var journal: (suspend (FinanceApiClient) -> CashCreationJournal)? = null
    internal constructor(api: FinanceApiClient, operations: CashBusinessOperations,
        journal: (suspend (FinanceApiClient) -> CashCreationJournal)? = null) : this(api) {
        this.operations = operations
        this.journal = journal
    }
    override val supportsIdempotentSave: Boolean get() = operations != null
    override val supportsDurableRecovery: Boolean get() = journal != null

    override suspend fun pending(workspaceId: String, principalEmail: String): CashCreationRecovery? =
        bound(workspaceId, principalEmail) { fixed, _ -> checkNotNull(journal).invoke(fixed).read() }

    override suspend fun acknowledgeRecovery(commandId: String, workspaceId: String, principalEmail: String) {
        bound(workspaceId, principalEmail) { fixed, _ ->
            val storage = checkNotNull(journal).invoke(fixed)
            val saved = storage.read()
            if (saved != null && saved.command.id == commandId && saved.command.receipt != null) storage.swap(saved, null)
        }
    }

    override suspend fun saveRecovery(recovery: CashCreationRecovery, workspaceId: String, principalEmail: String, lookupFirst: Boolean) {
        bound(workspaceId, principalEmail, write = true) { fixed, refresh ->
            val storage = checkNotNull(journal).invoke(fixed)
            val saved = storage.read()
            if (saved != null && saved.command.copy(receipt = null) != recovery.command.copy(receipt = null))
                creationConflict()
            if (saved == null && !storage.swap(null, recovery)) creationConflict()
            val original = saved ?: recovery
            val receipt = try {
                fixed.executeFixedCashCommand(original.command, lookupFirst || saved != null)
            } catch (failure: ClientOperationFailure) {
                if (failure.commitState == "not_committed" && !storage.swap(original, null))
                    throw ClientOperationFailure("result_unknown", cause = failure, commitState = "unknown", operationId = original.command.id)
                throw failure
            }
            if (original.command.receipt == null && !storage.swap(original, original.copy(command = original.command.copy(receipt = receipt))))
                throw ClientOperationFailure("result_unknown", commitState = "unknown", operationId = original.command.id)
            refresh(receipt.ledgerSeq)
        }
    }

    private suspend fun <T> bound(workspaceId: String, principalEmail: String, write: Boolean = false,
        action: suspend (FinanceApiClient, suspend (String?) -> Unit) -> T): T {
        val operations = checkNotNull(operations)
        return try {
            operations.run(write) { fixed, refresh ->
                if (fixed.expectedWorkspace != workspaceId || fixed.expectedPrincipalEmail != principalEmail)
                    throw CancellationException("ledger_session_operation_superseded")
                action(fixed, refresh)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { throw failure.toDomainFailure() }
    }

    private fun creationConflict(): Nothing = throw DomainFailure("operation_conflict", 409, FailureCategory.RECOVERABLE)

    override suspend fun createRecord(input: StructuredObject) { send("/api/v1/cash-records", input) }
    override suspend fun createTransfer(input: StructuredObject) { send("/api/v1/cashflow/transfer", input) }

    override suspend fun save(mode: CashCreationMode, input: StructuredObject, key: String, accountIds: List<String>,
        workspaceId: String, principalEmail: String) = save(mode, input, key, accountIds, workspaceId, principalEmail, lookupFirst = false)

    override suspend fun resume(mode: CashCreationMode, input: StructuredObject, key: String, accountIds: List<String>,
        workspaceId: String, principalEmail: String) = save(mode, input, key, accountIds, workspaceId, principalEmail, lookupFirst = true)

    private suspend fun save(mode: CashCreationMode, input: StructuredObject, key: String, accountIds: List<String>,
        workspaceId: String, principalEmail: String, lookupFirst: Boolean) {
        bound(workspaceId, principalEmail, write = true) { fixed, refresh ->
            val command = freezeComposeCashCreation(mode, input, key, accountIds)
            refresh(fixed.executeFixedCashCommand(command, lookupFirst).ledgerSeq)
        }
    }
    private suspend fun send(path: String, input: StructuredObject) {
        val frozen = input.toJsonObject()
        try {
            val bound = operations
            if (bound == null) {
                val refresh = captureRefresh()
                api.request<JsonObject>(HttpMethod.Post, path, frozen)
                refresh(null)
            } else bound.run(write = true) { fixed, refresh ->
                fixed.request<JsonObject>(HttpMethod.Post, path, frozen)
                refresh(null)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { throw failure.toDomainFailure() }
    }
}

internal fun freezeComposeCashCreation(mode: CashCreationMode, input: StructuredObject, key: String, accountIds: List<String>): ClientPendingCommand {
    val fields = input.toJsonObject()
    fun text(field: String) = fields[field]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
    fun required(field: String) = checkNotNull(text(field)) { "cash_creation_input_missing" }
    return when (mode) {
        CashCreationMode.ORDINARY -> freezeCashRecord(NewCashRecord(
            accountIds.single(), required("amount"), required("currency"), required("occurred_at"), required("record_type"),
            text("record_subtype"), text("counterparty"), text("note"), text("category_id"), "category_id" in fields,
            text("counterparty_account"), fields["counterparty_account_attrs"]?.jsonArray?.map { it.jsonPrimitive.content },
        ), required("account_name"), key)
        CashCreationMode.TRANSFER -> freezeCashTransfer(NewCashTransfer(
            accountIds[0], accountIds[1], required("amount"), required("from_currency"), required("to_currency"),
            required("date"), required("time"), text("to_amount"), text("note"),
        ), required("from_account"), required("to_account"), key)
    }
}
