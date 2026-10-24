package com.finance.tracker.data

import com.finance.tracker.domain.*
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

class KtorCashCreationRepository(private val api: FinanceApiClient) : CashCreationRepository {
    override suspend fun createRecord(input: StructuredObject) { send("/api/v1/cash-records", input) }
    override suspend fun createTransfer(input: StructuredObject) { send("/api/v1/cashflow/transfer", input) }
    private suspend fun send(path: String, input: StructuredObject) {
        try { api.request<JsonObject>(HttpMethod.Post, path, input.toJsonObject()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { throw failure.toDomainFailure() }
    }
}
