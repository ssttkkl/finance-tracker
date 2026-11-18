package com.finance.tracker.business.domain

import kotlin.test.*

class LedgerArithmeticValueTest {
    private fun number(text:String)=LedgerArithmeticValue.fromCanonical(text)
    @Test fun accumulatedValuesAreNotLimitedToSingleFactRange() {
        val amount=CashAmount.parse("99999999999999999999.999999999999999999")
        val a=LedgerArithmeticValue.fromCashAmount(amount)
        assertEquals("199999999999999999999.999999999999999998",a.add(a).toPlainString())
        assertNull(CashAmount.parseOrNull(a.add(a).toPlainString()))
        assertEquals("0.000000000000000000",a.subtract(a).toPlainString())
        assertEquals("0.00",number("-0.00").toPlainString())
        assertEquals(number("1.00"),number("1"))
        assertEquals(number("1.00").hashCode(),number("1").hashCode())
        assertTrue(number("-1E-18") < number("0"))
        assertTrue(number("1E100") > number("999E97"))
    }
    @Test fun halfEvenRoundingRetainsScaleAndCarry() {
        assertEquals("1.2",number("1.25").round(2).toString())
        assertEquals("1.4",number("1.35").round(2).toString())
        assertEquals("-1.2",number("-1.25").round(2).toString())
        assertEquals("1.0E+3",number("999").round(2).toString())
        assertEquals("0.0012",number("0.0012500").round(2).toString())
        assertEquals("0.000001",number("1E-6").toString())
        assertEquals("1E-7",number("1E-7").toString())
        assertEquals("0E+8",number("0E+8").toString())
        assertEquals("0",LedgerArithmeticValue.fromCashAmount(CashAmount.parse("0E999999999999")).toString())
        assertFailsWith<IllegalArgumentException> {number("1_000")}
        assertFailsWith<IllegalArgumentException> {number(" 1")}
        assertFailsWith<IllegalArgumentException> {number("NaN")}
    }
}
