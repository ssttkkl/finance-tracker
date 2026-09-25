package com.finance.tracker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InvestmentDisplayTest {
    @Test
    fun displayDecimalMathKeepsFinancialValuesExact() {
        assertEquals("1.05", displayDecimalAdd("1.25", "-0.2"))
        assertEquals("-0.5", displayDecimalAdd("-1", "0.5"))
        assertEquals("123.4", displayDecimalMultiply("1.234", "100"))
        assertEquals("100", displayDecimalMultiply("10", "10"))
        assertEquals("0.3333", displayDecimalDivide("1", "3", precision = 4))
        assertEquals("1.5", displayDecimalDivide("3", "2", precision = 4))
        assertEquals("-1.24", displayDecimalRound("-1.235", scale = 2))
        assertNull(displayDecimalDivide("1", "0"))
    }

    @Test
    fun portfolioPercentDoesNotRenderMissingCurrencyAsNull() {
        assertEquals("+10%", formatPortfolioPercent("0.1"))
        assertEquals("—", formatPortfolioPercent(null))
    }

    @Test
    fun holdingsMergeAndSortWithoutBinaryFloatingPoint() {
        val accounts = listOf(AccountDto(1, "Broker A", "investment"), AccountDto(2, "Broker B", "investment"))
        val portfolio = PortfolioDto(accounts = listOf(
            PortfolioAccountDto("Broker A", "USD", listOf(
                position("AAPL", shares = "2", cost = "20", price = "12", value = "24", profit = "4", quoteAt = "2026-09-24T12:00:00Z"),
                position("MSFT", shares = "1", cost = "30", price = "50", value = "50", profit = "20"),
            )),
            PortfolioAccountDto("Broker B", "USD", listOf(
                position("AAPL", shares = "1", cost = "12", price = "15", value = "15", profit = "3", quoteAt = "2026-09-25T12:00:00Z"),
            )),
        ))

        val merged = investmentDisplayData(portfolio, accounts, InvestmentDisplayOptions(grouping = "merge", sort = "ticker_asc"))

        assertEquals(listOf("AAPL", "MSFT"), merged.rows.map { it.position.ticker })
        val aapl = merged.rows.first().position
        assertTrue(merged.rows.first().merged)
        assertEquals("3", aapl.shares)
        assertEquals("32", aapl.totalCost)
        assertEquals("13", aapl.currentPrice)
        assertEquals("39", aapl.marketValue)
        assertEquals("7", aapl.profit)
        assertEquals("2026-09-25T12:00:00Z", aapl.quoteObservedAt)
        assertEquals("Broker A", merged.accounts.first().name)
    }

    @Test
    fun selectedAccountSummaryUsesExactValuesAndRetainsKnownQuotes() {
        val account = AccountDto(1, "Broker A", "investment")
        val oldPosition = position("AAPL", shares = "2", cost = "20", price = "12", value = "24", profit = "4", quoteAt = "2026-09-24T12:00:00Z")
        val previous = PortfolioDto(
            accounts = listOf(PortfolioAccountDto("Broker A", "USD", listOf(oldPosition))),
            totalMarketValue = "24",
            totalProfit = "4",
        )
        val incomingPosition = oldPosition.copy(currentPrice = null, marketValue = null, profit = null)
        val incoming = PortfolioDto(accounts = listOf(PortfolioAccountDto("Broker A", "USD", listOf(incomingPosition))))

        val retained = retainKnownInvestmentValuation(previous, incoming)
        val summary = investmentDisplayData(retained, listOf(account), InvestmentDisplayOptions(accountId = "1"))

        assertEquals("12", retained.accounts.single().positions.single().currentPrice)
        assertEquals("24", summary.marketValue)
        assertEquals("4", summary.profit)
        assertEquals("0.166666666666666667", summary.profitRate)
        assertFalse(summary.rows.single().merged)
    }

    @Test
    fun investmentEventLabelsAndAssetDirectionsMatchTheWebPresentation() {
        val event = InvestmentEventDto(
            eventId = "event-1",
            occurredAt = "2026-09-25T10:30:00Z",
            account = AccountDto(1, "Broker", "investment"),
            recordType = "trade",
            currency = "USD",
            fromAsset = InvestmentAssetDto("USD", "10.25"),
            toAsset = InvestmentAssetDto("AAPL", "1"),
            recordId = "record-1",
        )

        assertEquals("买入", eventTitle(event))
        assertEquals(listOf("流出" to "−10.25 USD", "流入" to "+1 AAPL"), investmentAssetLines(event))
    }

    @Test
    fun investmentEvidenceAvoidsDuplicateCashFactsAndAddsSourceContext() {
        val account = AccountDto(1, "现金", "cash")
        val event = InvestmentEventDto(
            eventId = "event-2",
            occurredAt = "2026-09-25T10:30:00Z",
            account = AccountDto(2, "Broker", "investment"),
            recordType = "funding",
            currency = "USD",
            fromAsset = InvestmentAssetDto("USD", "-100"),
            sourceType = "import",
            recordId = "record-2",
        )
        val relations = listOf(
            InvestmentRelationDto(
                kind = "cash_investment_funding", status = "accepted", direction = "investment_to_cash",
                cashAccount = account, cashAmount = "100", cashCurrency = "USD",
                cashOccurredAt = event.occurredAt, cashRecordId = "cash-1",
            ),
            InvestmentRelationDto(
                kind = "cash_investment_funding", status = "accepted", direction = "cash_to_investment",
                cashAccount = account, cashAmount = "10", cashCurrency = "USD",
                cashOccurredAt = "2026-09-25T10:45:00Z", cashRecordId = "cash-2",
            ),
        )

        val facts = investmentEvidenceFacts(event, relations)
        assertEquals(listOf("现金账户", "现金金额", "现金时间", "备注"), facts.map(InvestmentEvidenceFact::label))
        assertEquals("现金", facts[0].value)
        assertEquals("−10 USD", facts[1].value)
        assertTrue(Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}$").matches(facts[2].value))
        assertEquals("导入记录", facts[3].value)
    }

    @Test
    fun displayPreferencesRejectUnknownValuesAndKeepSupportedOptions() {
        val value = validateInvestmentDisplayOptions(
            InvestmentDisplayOptions(accountId = "42", sort = "unknown", grouping = "merge", currency = "usd", period = "30d"),
        )

        assertEquals("42", value.accountId)
        assertEquals("market_value_desc", value.sort)
        assertEquals("merge", value.grouping)
        assertEquals("", value.currency)
        assertEquals("30d", value.period)
    }

    private fun position(
        ticker: String,
        shares: String,
        cost: String,
        price: String,
        value: String,
        profit: String,
        quoteAt: String? = null,
    ) = PortfolioPositionDto(
        ticker = ticker,
        shares = shares,
        totalCost = cost,
        costCurrency = "USD",
        currentPrice = price,
        marketValue = value,
        profit = profit,
        quoteCurrency = "USD",
        quoteObservedAt = quoteAt,
        quoteSession = "regular",
        displayCurrency = "USD",
        displayMarketValue = value,
        usdMarketValue = value,
    )
}
