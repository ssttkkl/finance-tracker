package com.finance.tracker.business.domain

import java.math.BigDecimal
import java.math.BigInteger
import java.util.Random
import kotlin.test.*

class CashMirrorAmountParityTest {
    @Test fun normalizedGroupMoneyAndLegacyFactTextMatchBigDecimal() {
        val random=Random(185794)
        val amounts=MutableList(2000) {
            BigDecimal(BigInteger(120,random),18).let {if(random.nextBoolean()) it.negate() else it}
                .setScale(18+random.nextInt(7)).toString()
        }
        amounts+=listOf("-0.00","0.0000000","0E+999999999","1E+19","1.0E+2","0.0000010","-0.00000010","99999999999999999999.999999999999999999")
        for(text in amounts) {
            val legacy=BigDecimal(text);val amount=CashAmount.parse(text)
            assertEquals(legacy.stripTrailingZeros().toPlainString(),cashCanonicalPlainText(amount),text)
            assertEquals(legacy.toString(),cashLegacyDecimalText(amount),text)
            val fact:RelationFact<Any> = RelationFact("test",amount,"CNY","a",note="中文",rawPayload=mapOf("nested" to listOf(1,2.0)))
            assertEquals(fact.copy(amount=legacy).toString(),fact.copy(amount=cashLegacyDecimalText(amount)).toString(),text)
        }
    }
}
