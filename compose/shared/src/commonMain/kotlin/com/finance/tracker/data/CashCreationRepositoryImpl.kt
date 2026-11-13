package com.finance.tracker.data

import com.finance.tracker.domain.*
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

class KtorCashCreationRepository(private val api: FinanceApiClient,
    private val captureRefresh: suspend () -> (suspend (String?) -> Unit) = { {} }) : CashCreationRepository {
    override suspend fun createRecord(input: StructuredObject) { send("/api/v1/cash-records", input) }
    override suspend fun createTransfer(input: StructuredObject) { send("/api/v1/cashflow/transfer", input) }
    private suspend fun send(path: String, input: StructuredObject) {
        val refresh = captureRefresh()
        try {
            api.request<JsonObject>(HttpMethod.Post, path, input.toJsonObject())
            refresh(null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { throw failure.toDomainFailure() }
    }
}
