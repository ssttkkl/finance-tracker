package com.finance.tracker.data

import com.finance.tracker.domain.*
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import com.finance.tracker.business.contracts.CashMutationReceiptDto

class KtorCashCreationRepository(private val api: FinanceApiClient,
    private val captureRefresh: suspend () -> (suspend (String?) -> Unit) = { {} }) : CashCreationRepository {
    private var operations: CashBusinessOperations? = null
    internal constructor(api: FinanceApiClient, operations: CashBusinessOperations) : this(api) { this.operations = operations }
    override val supportsIdempotentSave: Boolean get() = operations != null

    override suspend fun createRecord(input: StructuredObject) { send("/api/v1/cash-records", input) }
    override suspend fun createTransfer(input: StructuredObject) { send("/api/v1/cashflow/transfer", input) }

    override suspend fun save(mode: CashCreationMode, input: StructuredObject, key: String, accountIds: List<String>,
        workspaceId: String, principalEmail: String) {
        val operations = checkNotNull(operations)
        val (path, kind) = when (mode) {
            CashCreationMode.ORDINARY -> "/api/v1/cash-records/commands" to "cash_create_manual"
            CashCreationMode.TRANSFER -> "/api/v1/cashflow/transfer/commands" to "cash_create_transfer"
        }
        try {
            operations.run(write = true) { fixed, refresh ->
                if (fixed.expectedWorkspace != workspaceId || fixed.expectedPrincipalEmail != principalEmail)
                    throw CancellationException("ledger_session_operation_superseded")
                val receipt = fixed.request<CashMutationReceiptDto>(HttpMethod.Post, path, input.toJsonObject(),
                    extraHeaders = mapOf("Idempotency-Key" to key, "X-FT-Account-IDs" to accountIds.joinToString(",")), requireResponseBody = true)
                if (!receipt.committed || receipt.operationKind != kind || receipt.receiptId.isBlank() || receipt.ledgerSeq == null)
                    throw DomainFailure("cash_creation_receipt_invalid", 200, FailureCategory.RECOVERABLE)
                refresh(receipt.ledgerSeq)
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
