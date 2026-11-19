@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.presentation

import com.finance.tracker.core.awaitResult
import com.finance.tracker.data.checkHandler
import com.finance.tracker.data.importComputeWorkerCall
import com.finance.tracker.data.loadCashComputeWorkerClient
import com.finance.tracker.domain.Portfolio
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json

internal actual suspend fun loadInvestmentDisplay(portfolio: Portfolio, accountName: String?, options: InvestmentDisplayOptions): InvestmentDisplayData {
    val result = importComputeWorkerCall {
        loadCashComputeWorkerClient().await().investmentDisplay(Json.encodeToString(portfolio), accountName, Json.encodeToString(options)).awaitResult()
    }
    result.checkHandler()
    return Json.decodeFromString(checkNotNull(result.body) { "investment_display_worker_response_invalid" })
}
