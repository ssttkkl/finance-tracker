package com.finance.tracker.business.domain

import java.math.BigDecimal
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class CashAmountOrderParityTest {
    @Test fun orderingMatchesExactDecimalAcrossSignsAndScales() {
        val random=Random(41051)
        val values=mutableListOf("0", "-0.000", "0E+12", "1E-18", "-1E-18",
            "99999999999999999999.999999999999999999", "-99999999999999999999.999999999999999999", "1.00", "1")
        repeat(2000) {
            val digits=buildString {repeat(random.nextInt(1,39)) {append(random.nextInt(10))}}
            val scale=random.nextInt(0,19).coerceAtLeast((digits.length-20).coerceAtLeast(0))
            values+=BigDecimal(digits).movePointLeft(scale).let {if(random.nextBoolean()) it.negate() else it}.toPlainString()
        }
        for(index in values.indices) {
            val a=values[index]; val b=values[(index*97+11)%values.size]
            val expected=BigDecimal(a).compareTo(BigDecimal(b))
            assertEquals(expected,CashAmount.parse(a).compareTo(CashAmount.parse(b)),"$a / $b")
            assertEquals(expected==0,CashAmount.parse(a)==CashAmount.parse(b),"equality $a / $b")
        }
    }
}
