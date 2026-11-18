package com.finance.tracker.business.domain

import java.math.BigDecimal
import java.math.BigInteger
import java.util.Random
import kotlin.test.*

class CashTransferAmountParityTest {
    @Test fun magnitudeDifferencePreservesBigDecimalScaleAndSigns() {
        val random=Random(741965)
        repeat(2000) {
            val scaleA=random.nextInt(19); val scaleB=random.nextInt(19)
            val a=BigDecimal(BigInteger(60,random),scaleA).let {if(random.nextBoolean()) it.negate() else it}
            val b=BigDecimal(BigInteger(60,random),scaleB).let {if(random.nextBoolean()) it.negate() else it}
            assertEquals(a.abs().subtract(b.abs()).toPlainString(),absoluteCashDifferenceText(CashAmount.parse(a.toString()),CashAmount.parse(b.toString())))
        }
        for((a,b) in listOf("-0.0000000000000000000" to "0E+999999999", "1E+19" to "-1.00", "-0.00" to "0.0000")) {
            assertEquals(BigDecimal(a).abs().subtract(BigDecimal(b).abs()).toPlainString(),absoluteCashDifferenceText(CashAmount.parse(a),CashAmount.parse(b)))
        }
    }
}
