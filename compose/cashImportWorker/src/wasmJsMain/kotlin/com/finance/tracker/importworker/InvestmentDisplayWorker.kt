@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package com.finance.tracker.importworker

import com.finance.tracker.business.contracts.InvestmentDisplayOptions
import com.finance.tracker.business.contracts.Portfolio
import com.finance.tracker.business.domain.calculateInvestmentDisplay
import kotlinx.serialization.json.Json
import kotlin.js.JsExport

@JsExport
fun calculatePortfolioDisplay(portfolio: String, account: String?, options: String): String =
    Json.encodeToString(calculateInvestmentDisplay(Json.decodeFromString<Portfolio>(portfolio), account,
        Json.decodeFromString<InvestmentDisplayOptions>(options)))
