package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class CashAmountTest {
    @Test
    fun numericEqualityKeepsTheOriginalDecimalRepresentation() {
        val first = CashAmount.parse("-0012.3400")
        val second = CashAmount.parse("-1234e-2")
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(DecimalRepresentation(true, "123400", -4), first.representation)
        assertEquals("-123400E-4", first.toString())
        assertEquals("-1234E-2", first.numericText())
        assertNotEquals(first, CashAmount.parse("12.34"))
        assertNotEquals<Any>(first, "-12.34")
    }

    @Test
    fun zeroSignsAndLargeExponentsRemainRepresentationalFacts() {
        val negative = CashAmount.parse("-0e+2147483648")
        val positive = CashAmount.parse("0")
        assertEquals(positive, negative)
        assertEquals(positive.hashCode(), negative.hashCode())
        assertEquals("-0E2147483648", negative.toString())
        assertEquals("0", negative.numericText())
        assertEquals(DecimalRepresentation(true, "0", 2147483648L), negative.representation)
    }

    @Test
    fun numericRangeAndFieldErrorsRemainExact() {
        assertEquals("1E-18", CashAmount.parse("0.000000000000000001").numericText())
        assertEquals("99999999999999999999E0", CashAmount.parse("99999999999999999999").numericText())
        val cases = mapOf(
            "wrong" to "amount must be decimal-compatible",
            "NaN" to "amount must be finite",
            "1e-19" to "amount exceeds NUMERIC(38,18): must have at most 18 decimal places",
            "1e20" to "amount exceeds NUMERIC(38,18)",
        )
        for ((text, message) in cases) {
            assertNull(CashAmount.parseOrNull(text))
            assertEquals(message, assertFailsWith<IllegalArgumentException> { exactCashAmount(text, "amount") }.message)
            assertEquals("invalid_cash_amount", assertFailsWith<IllegalArgumentException> { CashAmount.parse(text) }.message)
        }
    }
}
