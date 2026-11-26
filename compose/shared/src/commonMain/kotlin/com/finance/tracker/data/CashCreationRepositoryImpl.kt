package com.finance.tracker.data

import com.finance.tracker.domain.*
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import com.finance.tracker.client.ClientPendingCommand

class KtorCashCreationRepository(private val api: FinanceApiClient,
    private val captureRefresh: suspend () -> (suspend (String?) -> Unit) = { {} }) : CashCreationRepository {
    private var operations: CashBusinessOperations? = null
    internal constructor(api: FinanceApiClient, operations: CashBusinessOperations) : this(api) { this.operations = operations }
    override val supportsIdempotentSave: Boolean get() = operations != null

    override suspend fun createRecord(input: StructuredObject) { send("/api/v1/cash-records", input) }
    override suspend fun createTransfer(input: StructuredObject) { send("/api/v1/cashflow/transfer", input) }

    override suspend fun save(mode: CashCreationMode, input: StructuredObject, key: String, accountIds: List<String>,
        workspaceId: String, principalEmail: String) = save(mode, input, key, accountIds, workspaceId, principalEmail, lookupFirst = false)

    override suspend fun resume(mode: CashCreationMode, input: StructuredObject, key: String, accountIds: List<String>,
        workspaceId: String, principalEmail: String) = save(mode, input, key, accountIds, workspaceId, principalEmail, lookupFirst = true)

    private suspend fun save(mode: CashCreationMode, input: StructuredObject, key: String, accountIds: List<String>,
        workspaceId: String, principalEmail: String, lookupFirst: Boolean) {
        val operations = checkNotNull(operations)
        val (path, kind) = when (mode) {
            CashCreationMode.ORDINARY -> "/api/v1/cash-records/commands" to "cash_create_manual"
            CashCreationMode.TRANSFER -> "/api/v1/cashflow/transfer/commands" to "cash_create_transfer"
        }
        val command = ClientPendingCommand(key, kind, path, input.toJsonObject().toString(), accountIds.toList())
        try {
            operations.run(write = true) { fixed, refresh ->
                if (fixed.expectedWorkspace != workspaceId || fixed.expectedPrincipalEmail != principalEmail)
                    throw CancellationException("ledger_session_operation_superseded")
                refresh(fixed.executeFixedCashCommand(command, lookupFirst).ledgerSeq)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { throw failure.toDomainFailure() }
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
