package com.finance.tracker.data

import com.finance.tracker.domain.*
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

class KtorCashCreationRepository(private val api: FinanceApiClient,
    private val captureRefresh: suspend () -> (suspend (String?) -> Unit) = { {} }) : CashCreationRepository {
    private var operations: CashBusinessOperations? = null
    internal constructor(api: FinanceApiClient, operations: CashBusinessOperations) : this(api) { this.operations = operations }

    override suspend fun createRecord(input: StructuredObject) { send("/api/v1/cash-records", input) }
    override suspend fun createTransfer(input: StructuredObject) { send("/api/v1/cashflow/transfer", input) }
    private suspend fun send(path: String, input: StructuredObject) {
        val frozen = input.toJsonObject()
        try {
            val bound = operations
            if (bound == null) {
                val refresh = captureRefresh()
                api.request<JsonObject>(HttpMethod.Post, path, frozen)
                refresh(null)
            } else bound.run { fixed, refresh ->
                fixed.request<JsonObject>(HttpMethod.Post, path, frozen)
                refresh(null)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { throw failure.toDomainFailure() }
    }
}
