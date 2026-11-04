package com.finance.tracker.business.domain

import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Random
import kotlin.test.*

class CashRefundDiamondParityTest {
    private fun match(pay:String,refund:String,start:String="2026-01-01T00:00:00Z",end:String="2026-01-01T00:00:00Z"):List<RelationProposal> {
        fun fact(id:String,amount:String,source:String,type:String,time:String)=RelationFact(id,CashAmount.parse(amount),"CNY","a",
            occurredAt=time,recordType=type,billSource=source,source=source)
        return cashRefundDiamond.match(listOf(fact("bp",pay,"bank","consumption",start),fact("br",refund,"bank","refund",end),
            fact("pp",pay,"alipay","consumption",start),fact("pr",refund,"alipay","refund",end)),
            listOf("bp" to "pp","br" to "pr"),listOf("pp" to "pr"))
    }
    @Test fun toleranceAndAmountEvidenceMatchBigDecimalForFixedRandomValues() {
        val random=Random(725103L)
        val changes=listOf("0.0001","0.000100000000000001","0.000099999999999999","-0.0001","0")
        repeat(2000) {index ->
            val payment=BigDecimal(BigInteger(120,random),18)
            val refund=payment+BigDecimal(changes[index%changes.size])
            val actual=match(payment.negate().toPlainString(),refund.toPlainString())
            val expected=payment+BigDecimal("0.0001")>=refund
            assertEquals(expected,actual.isNotEmpty(),"pair $index")
            if(expected) assertEquals(refund.toPlainString(),actual.single().evidence.extras["refund_amount"])
        }
    }
    @Test fun evidenceTimeMatchesChronoUnitIncludingLongIntervalsAndMicroseconds() {
        val instants=listOf("0001-01-01T00:00:00.000001Z","1969-12-31T23:59:59.999999Z","2026-01-01T00:00:01.000001Z","9999-12-31T23:59:59Z")
        for(a in instants) for(b in instants) {
            val expected=kotlin.math.abs(ChronoUnit.SECONDS.between(Instant.parse(a),Instant.parse(b))).toInt()
            assertEquals(expected,match("-1","1",a,b).single().evidence.timeDeltaSeconds,"$a / $b")
        }
    }
}
