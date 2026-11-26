package com.finance.tracker.data

import com.finance.tracker.domain.*
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.client.ClientNetworkUnavailable
import com.finance.tracker.client.ClientOperationFailure
import com.finance.tracker.client.ClientPendingCommand
import com.finance.tracker.client.executeCashCommand

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
                val result = executeCashCommand(command, lookupFirst,
                    findReceipt = { originalKey ->
                        commandRequest {
                            try { fixed.readCashMutationReceiptByKey(originalKey) }
                            catch (failure: ApiFailure) {
                                if (failure.status == 404 && failure.code == "receipt_not_found") null else throw failure
                            }
                        }
                    },
                    submit = { original ->
                        commandRequest {
                            fixed.request<CashMutationReceiptDto>(HttpMethod.parse(original.method), original.path, FrozenJsonBody(original.body),
                                extraHeaders = mapOf("Idempotency-Key" to original.id, "X-FT-Account-IDs" to original.accountIds.joinToString(",")),
                                preserveHttpRejectionOnBodyFailure = true, requireResponseBody = true)
                        }
                    })
                refresh(result.receipt.ledgerSeq)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ClientOperationFailure) {
            val source = failure.cause as? ApiFailure
            throw DomainFailure(failure.code, failure.status ?: 0, FailureCategory.RECOVERABLE,
                receiptId = source?.receiptId, commitState = failure.commitState, diagnostic = source?.diagnostic, cause = failure)
        }
        catch (failure: Exception) { throw failure.toDomainFailure() }
    }

    private suspend fun <T> commandRequest(request: suspend () -> T): T = try {
        request()
    } catch (failure: ApiFailure) {
        if (failure.status in setOf(401, 403)) throw failure
        val code = if (failure.status in 200..299) "protocol_error" else failure.code
        throw ClientOperationFailure(code, failure.status, failure, failure.commitState)
    } catch (failure: FinanceTransportFailure) {
        throw ClientNetworkUnavailable(failure)
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
