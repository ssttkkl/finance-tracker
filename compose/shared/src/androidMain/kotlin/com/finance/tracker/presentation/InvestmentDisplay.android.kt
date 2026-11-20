package com.finance.tracker.presentation

import com.finance.tracker.domain.Portfolio
import com.finance.tracker.business.domain.calculateInvestmentDisplay

internal actual suspend fun loadInvestmentDisplay(portfolio: Portfolio, accountName: String?, options: InvestmentDisplayOptions): InvestmentDisplayData =
    calculateInvestmentDisplay(portfolio, accountName, options)
