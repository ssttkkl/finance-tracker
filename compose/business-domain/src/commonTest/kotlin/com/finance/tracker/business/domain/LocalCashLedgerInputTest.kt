package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashFiltersDto
import kotlin.time.Instant
import kotlin.test.*

class LocalCashLedgerInputTest {
    private val utc: (String?, String?, String?) -> CashLedgerDateBounds = { _, _, _ ->
        CashLedgerDateBounds(null, null, "UTC", null, null) }
    private fun input(value: CashFiltersDto = CashFiltersDto()) = normalizeLocalCashLedgerQueryInput(value, null, utc)
    @Test fun amountFiltersRejectExcessDigitsBeforeExpandingTheExponent() {
        // First rejection is deliberately small, so the red test never allocates a huge string.
        for (raw in listOf("1e20", "1e-19", "1e2147483647", "1e-2147483647", "0e-2147483647",
            "100000000000000000000", "0.0000000000000000001", "1.0000000000000000000")) {
            assertEquals("invalid_filter", assertFailsWith<CashProjectionDomainFailure> {
                input(CashFiltersDto(amountMin = raw))
            }.code, raw)
            assertEquals("invalid_filter", assertFailsWith<CashProjectionDomainFailure> {
                input(CashFiltersDto(amountMax = raw))
            }.code, raw)
        }
        val maximum = "99999999999999999999.999999999999999999"
        val bounds = input(CashFiltersDto(amountMin = "-$maximum", amountMax = maximum))
        assertEquals("-$maximum", bounds.filters["amount_min"])
        assertEquals(maximum, bounds.filters["amount_max"])
        assertEquals("0.000000000000000001", input(CashFiltersDto(amountMin = "1e-18")).filters["amount_min"])
        assertEquals("0", input(CashFiltersDto(amountMin = "0e2147483647")).filters["amount_min"])
    }
    @Test fun defaultShapeAndCanonicalFiniteValuesKeepTheExistingFilterProtocol() {
        assertEquals(linkedMapOf("date_from" to null, "date_to" to null, "account_id" to null, "counterparty" to null,
            "category_id" to null, "uncategorized" to "False", "currency" to null, "amount_min" to null, "amount_max" to null,
            "economic_type" to null, "business_label" to null, "composition" to null, "timezone" to "UTC", "state" to "all",
            "confirmation_status" to null, "hide_zero_amount" to "True"), input().filters)
        val value = input(CashFiltersDto(accountId = " +٠_０9  ", counterparty = "  literal%_\\  ", categoryId = " root ",
            currency = "cny", amountMin = "-0.000", amountMax = "+0010_0.2500e+1", hideZeroAmount = "FALSE"))
        assertEquals("9", value.filters["account_id"]); assertEquals("literal%_\\", value.filters["counterparty"])
        assertEquals("root", value.filters["category_id"]); assertEquals("CNY", value.filters["currency"])
        assertEquals("-0.000", value.filters["amount_min"]); assertEquals("1002.500", value.filters["amount_max"])
        assertEquals("False", value.filters["hide_zero_amount"])
        assertEquals(0, value.minimum!!.signum)
        assertEquals("1", input(CashFiltersDto(accountId = "0".repeat(4299) + "1")).filters["account_id"])
        val large = input(CashFiltersDto(amountMin = "-1e19", amountMax = "1e19"))
        assertEquals("-10000000000000000000", large.filters["amount_min"])
        assertTrue(large.minimum!! < LedgerArithmeticValue.fromCanonical("-9999999999999999999"))
        assertTrue(large.maximum!! > LedgerArithmeticValue.fromCanonical("9999999999999999999"))
    }
    @Test fun chosenCalendarAdapterOwnsOneConsistentPairOfBounds() {
        var calls = 0
        val expected = CashLedgerDateBounds("2026-03-08", "2026-03-08", "America/New_York",
            Instant.parse("2026-03-08T05:00:00Z"), Instant.parse("2026-03-09T04:00:00Z"))
        val value = normalizeLocalCashLedgerQueryInput(CashFiltersDto(dateFrom = "2026W107", dateTo = "2026-03-08"), " America/New_York ") { from, to, zone ->
            calls++; assertEquals("2026W107", from); assertEquals("2026-03-08", to); assertEquals(" America/New_York ", zone)
            expected
        }
        assertEquals(1, calls); assertEquals(expected.start, value.start); assertEquals(expected.end, value.end)
        assertEquals(expected.dateFrom, value.filters["date_from"]); assertEquals(expected.timeZoneId, value.filters["timezone"])
    }
    @Test fun typeLabelAliasesAndUnicodeCurrencyRetainTheirOwnInputRules() {
        for (filters in listOf(CashFiltersDto(economicType = "bank_security_transfer"), CashFiltersDto(businessLabel = " bank_security_transfer "))) {
            assertEquals("internal_transfer", input(filters).filters["economic_type"])
            assertEquals("bank_security_transfer", input(filters).filters["business_label"])
        }
        assertEquals("𐐀AB", input(CashFiltersDto(currency = "𐐨ab")).filters["currency"])
        for (raw in listOf("1", "True", "yes", "Y", "on", "t"))
            assertEquals("True", input(CashFiltersDto(uncategorized = raw)).filters["uncategorized"])
        for (raw in listOf("0", "False", "no", "N", "off", "f"))
            assertEquals("False", input(CashFiltersDto(uncategorized = raw)).filters["uncategorized"])
    }
    @Test fun malformedFieldsRejectInsteadOfChangingAnotherFilter() {
        val invalid = listOf(CashFiltersDto(accountId = "0"), CashFiltersDto(accountId = "-1"), CashFiltersDto(accountId = "4_"),
            CashFiltersDto(accountId = "1".repeat(4301)), CashFiltersDto(categoryId = " "), CashFiltersDto(categoryId = "a".repeat(65)),
            CashFiltersDto(categoryId = "root", uncategorized = "true"), CashFiltersDto(currency = " CNY"), CashFiltersDto(currency = "ĆY"),
            CashFiltersDto(currency = "CN1"), CashFiltersDto(amountMin = "NaN"), CashFiltersDto(amountMax = "Infinity"),
            CashFiltersDto(amountMin = "10", amountMax = "9"), CashFiltersDto(economicType = "unknown"), CashFiltersDto(composition = "unknown"),
            CashFiltersDto(businessLabel = " "), CashFiltersDto(economicType = "income", businessLabel = "tag"),
            CashFiltersDto(economicType = "bank_security_transfer", businessLabel = "different"), CashFiltersDto(state = "unknown"),
            CashFiltersDto(confirmationStatus = "unknown"), CashFiltersDto(hideZeroAmount = "yes"), CashFiltersDto(hideZeroAmount = ""))
        for (value in invalid) assertEquals("invalid_filter", assertFailsWith<CashProjectionDomainFailure> { input(value) }.code, value.toString())
        assertEquals("bool_parsing", assertFailsWith<CashProjectionDomainFailure> { input(CashFiltersDto(uncategorized = "yes ")) }.code)
        assertEquals("invalid_filter", assertFailsWith<CashProjectionDomainFailure> { input(CashFiltersDto(amountMin = "1e2147483648")) }.code)
    }
}
