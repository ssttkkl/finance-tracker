package com.finance.tracker.business.domain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class LedgerArithmeticValueParityTest {
    @Test fun exactAndHalfEvenOperationsMatchBigDecimal() {
        val random=Random(81273)
        fun sample():String {
            val coefficient=buildString {repeat(random.nextInt(1,120)) {append(random.nextInt(10))}}
            val sign=if(random.nextBoolean()) "-" else ""
            return sign+coefficient+"E"+random.nextInt(-120,121)
        }
        repeat(3000) {
            val aText=sample();val bText=sample()
            val a=LedgerArithmeticValue.fromCanonical(aText);val b=LedgerArithmeticValue.fromCanonical(bText)
            val x=BigDecimal(aText);val y=BigDecimal(bText)
            assertEquals(x.toString(),a.toString(),aText)
            assertEquals(x.toPlainString(),a.toPlainString(),aText)
            assertEquals(x.compareTo(y),a.compareTo(b),"compare $aText / $bText")
            assertEquals(x.add(y).toString(),a.add(b).toString(),"add $aText / $bText")
            assertEquals(x.subtract(y).toString(),a.subtract(b).toString(),"subtract $aText / $bText")
            for(precision in listOf(1,2,38,80)) {
                val context=MathContext(precision,RoundingMode.HALF_EVEN)
                assertEquals(x.round(context).toString(),a.round(precision).toString(),"round $aText / $precision")
                assertEquals(x.add(y,context).toString(),a.add(b,precision).toString(),"add $aText / $bText / $precision")
                assertEquals(x.subtract(y,context).toString(),a.subtract(b,precision).toString(),"subtract $aText / $bText / $precision")
            }
        }
    }
    @Test fun persistedRepresentationsAndAccumulationMatchNativeArithmeticBoundary() {
        val fixed=listOf("-0.00","0E-19","0E2147483647","0E2147483648","0E999999999999",
            "99999999999999999999.999999999999999999","-0.000000000000000001","1.2300","１２.３０")
        for(text in fixed) {
            val amount=CashAmount.parse(text)
            val parts=amount.representation
            val expected=if(parts.coefficient=="0" && parts.exponent>Int.MAX_VALUE) BigDecimal.ZERO else BigDecimal(amount.toString())
            val actual=LedgerArithmeticValue.fromCashAmount(amount)
            assertEquals(expected.toString(),actual.toString(),text)
            assertEquals(expected.toPlainString(),actual.toPlainString(),text)
        }
        val random=Random(414017)
        var expected=BigDecimal.ZERO
        var actual=LedgerArithmeticValue.ZERO
        repeat(2000) {
            val value=BigDecimal.valueOf(random.nextLong(),random.nextInt(0,19))
            expected=expected.add(value)
            actual=actual.add(LedgerArithmeticValue.fromCashAmount(CashAmount.parse(value.toPlainString())))
            assertEquals(expected.toPlainString(),actual.toPlainString())
        }
    }

}
