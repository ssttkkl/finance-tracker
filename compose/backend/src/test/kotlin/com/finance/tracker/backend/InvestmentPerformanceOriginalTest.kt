package com.finance.tracker.backend

import kotlin.test.*

class InvestmentPerformanceOriginalTest {
    private val start = QuoteTime.parse("2026-08-01T00:00:00+00:00"); private val end = QuoteTime.parse("2026-08-02T00:00:00+00:00")
    private fun equal(expected: String, actual: String?) { assertNotNull(actual); assertEquals(0, expected.toBigDecimal().compareTo(actual.toBigDecimal())) }
    @Test fun test_total_period_pnl_includes_realized_sale_gain_without_treating_trade_as_external_flow() { equal("200", calculateTotalInvestmentPeriodPnl("1500", "1700", emptyList())) }
    @Test fun test_total_period_pnl_excludes_external_deposit_from_return() { equal("100", calculateTotalInvestmentPeriodPnl("1000", "2100", listOf("1000"))) }
    @Test fun test_instrument_period_pnl_adjusts_for_buys_and_sells() { equal("220", calculateInstrumentPeriodPnl("1000", "1320", listOf(InvestmentInstrumentFlow(QuoteTime.parse("2026-08-01T04:00:00+00:00"), "220"), InvestmentInstrumentFlow(QuoteTime.parse("2026-08-01T16:00:00+00:00"), "-120")))) }
    @Test fun test_period_return_uses_time_weighted_capital_and_not_simple_opening_value() { equal("0.08", calculateInvestmentPeriodReturn("100", "1000", listOf(InvestmentPeriodFlow(QuoteTime.parse("2026-08-01T12:00:00+00:00"), "500")), start, end)) }
    @Test fun test_period_return_is_empty_when_time_weighted_capital_is_not_positive() { assertNull(calculateInvestmentPeriodReturn("10", "0", emptyList(), start, end)) }
    @Test fun test_period_return_rejects_naive_or_reversed_period_boundaries() { assertContains(assertFailsWith<IllegalArgumentException> { calculateInvestmentPeriodReturn("1", "1", emptyList(), QuoteTime.parse("2026-08-01T00:00:00"), end) }.message!!, "timezone-aware"); assertContains(assertFailsWith<IllegalArgumentException> { calculateInvestmentPeriodReturn("1", "1", emptyList(), end, start) }.message!!, "period") }
}
