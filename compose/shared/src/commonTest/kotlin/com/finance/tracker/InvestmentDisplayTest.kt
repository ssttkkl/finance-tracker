package com.finance.tracker

import com.finance.tracker.app.*
import com.finance.tracker.core.*
import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*

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
        val accounts = listOf(Account(1, "Broker A", "investment"), Account(2, "Broker B", "investment"))
        val portfolio = Portfolio(accounts = listOf(
            PortfolioAccount("Broker A", "USD", listOf(
                position("AAPL", shares = "2", cost = "20", price = "12", value = "24", profit = "4", quoteAt = "2026-09-24T12:00:00Z"),
                position("MSFT", shares = "1", cost = "30", price = "50", value = "50", profit = "20"),
            )),
            PortfolioAccount("Broker B", "USD", listOf(
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
        val account = Account(1, "Broker A", "investment")
        val oldPosition = position("AAPL", shares = "2", cost = "20", price = "12", value = "24", profit = "4", quoteAt = "2026-09-24T12:00:00Z")
        val previous = Portfolio(
            accounts = listOf(PortfolioAccount("Broker A", "USD", listOf(oldPosition))),
            totalMarketValue = "24",
            totalProfit = "4",
        )
        val incomingPosition = oldPosition.copy(currentPrice = null, marketValue = null, profit = null)
        val incoming = Portfolio(accounts = listOf(PortfolioAccount("Broker A", "USD", listOf(incomingPosition))))

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
        val event = InvestmentEvent(
            eventId = "event-1",
            occurredAt = "2026-09-25T10:30:00Z",
            account = Account(1, "Broker", "investment"),
            recordType = "trade",
            currency = "USD",
            fromAsset = InvestmentAsset("USD", "10.25"),
            toAsset = InvestmentAsset("AAPL", "1"),
            recordId = "record-1",
        )

        assertEquals("copy_839b83828f", investmentEventTitleResourceKey(event))
        assertEquals(listOf("copy_e3863e1f11" to "−10.25 USD", "copy_db47592d34" to "+1 AAPL"), investmentAssetLineFacts(event))
    }

    @Test
    fun investmentEvidenceAvoidsDuplicateCashFactsAndAddsSourceContext() {
        val account = Account(1, "现金", "cash")
        val event = InvestmentEvent(
            eventId = "event-2",
            occurredAt = "2026-09-25T10:30:00Z",
            account = Account(2, "Broker", "investment"),
            recordType = "funding",
            currency = "USD",
            fromAsset = InvestmentAsset("USD", "-100"),
            sourceType = "import",
            recordId = "record-2",
        )
        val relations = listOf(
            InvestmentRelation(
                kind = "cash_investment_funding", status = "accepted", direction = "investment_to_cash",
                cashAccount = account, cashAmount = "100", cashCurrency = "USD",
                cashOccurredAt = event.occurredAt, cashRecordId = "cash-1",
            ),
            InvestmentRelation(
                kind = "cash_investment_funding", status = "accepted", direction = "cash_to_investment",
                cashAccount = account, cashAmount = "10", cashCurrency = "USD",
                cashOccurredAt = "2026-09-25T10:45:00Z", cashRecordId = "cash-2",
            ),
        )

        val facts = investmentEvidenceFacts(event, relations)
        assertEquals(listOf("investment_fact_cash_account", "investment_fact_cash_amount", "investment_fact_cash_time", "investment_fact_note"), facts.map(InvestmentEvidenceFact::labelResourceKey))
        assertEquals("现金", facts[0].value)
        assertEquals("−10 USD", facts[1].value)
        assertTrue(Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}$").matches(facts[2].value))
        assertEquals("copy_6d33d6cd64", facts[3].valueResourceKey)
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
    ) = PortfolioPosition(
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
