package com.finance.tracker.backend

import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class CashProjectionMigrationTest {
    private fun fact(id: Long,amount: String?,day: Int=id.toInt().coerceIn(1,28),currency: String?="CNY",account: Long?=101,
        state: String="posted",created: Instant?=null,occurred: Instant?=Instant.parse("2026-01-${day.toString().padStart(2,'0')}T09:00:00Z"),
        funding: Long?=null,granularity: String="atomic",attention: Boolean=false) = CashProjectionFact.create(
        id,account,occurred,amount,currency,"示例商户","category-fixture","","fixture","cash-$id",funding,granularity,state,created,attention)
    private fun relation(id: Long,kind: String,left: Long,right: Long,subtype: String="",status: String="accepted",amount: String?=null,
        allocation: Pair<String,String>?=null)=CashProjectionRelation.create(id,kind,left,right,status,subtype,amount,allocation)
    private fun build(facts: List<CashProjectionFact>,vararg relations: CashProjectionRelation)=buildCashProjections(facts,relations.toList())
    private fun invalid(block:()->Unit) {assertEquals("projection.invalid_relation",assertFailsWith<CashProjectionFailure>(block=block).code)}
    @Test fun allFactsHaveOneProjectionAndOnlyAcceptedRelationsApply() {
        val result=build(listOf(fact(1,"-20",1),fact(2,"30",2),fact(3,"0",3)))
        assertEquals(listOf("cash:1","cash:2","cash:3"),result.projections.map {it.projectionId})
        assertEquals(listOf("expense","income","internal_transfer"),result.projections.map {it.economicType?.value})
        assertEquals(listOf(true,true,false),result.projections.map {it.visible})
        assertEquals(setOf(1L,2L,3L),result.memberIds)
        assertEquals(listOf("cash:50","cash:51"),build(listOf(fact(50,"-12",10),fact(51,"-12",10)),
            relation(105,"payment_mirror",50,51,status="pending_review")).projections.map {it.projectionId})
    }
    @Test fun builderPassesOnlyComponentRelationsToEachGroup() {
        val received=mutableListOf<Pair<List<Long>,List<Long>>>()
        val result=buildCashProjections(listOf(fact(1,"-10"),fact(2,"10"),fact(3,"-20"),fact(4,"20"),fact(5,"-30"),fact(6,"5")),
            listOf(relation(1,"transfer_pair",1,2,"ordinary_transfer"),relation(2,"transfer_pair",3,4,"ordinary_transfer")),
            observeGroup={ids,relations ->received.add(ids to relations.map {it.id.longValueExact()})})
        assertEquals(4,result.projections.size);assertEquals(2,received.sumOf {it.second.size})
        assertEquals(listOf(emptyList(),emptyList(),listOf(1L),listOf(2L)),received.map {it.second}.sortedBy {it.firstOrNull() ?: 0})
    }
    @Test fun mirrorUsesPrimaryOnceAndIsDeterministic() {
        fun result()=build(listOf(fact(10,"-100",4),fact(11,"-100",4)),relation(101,"payment_mirror",10,11))
        val first=result();val item=first.projections.single()
        assertEquals("cash:10",item.projectionId);assertEquals(CashAmount.parse("-100"),item.netAmount)
        assertEquals(listOf(10L,11L),item.memberIds);assertEquals(listOf("payment_mirror"),item.compositions)
        assertEquals(10L,item.primaryRecord.id);assertEquals(first,result())
    }
    @Test fun transferPairIsVisibleInternalTransferForEveryOriginalSubtype() {
        for(subtype in listOf("ordinary_transfer","cross_currency_remittance","credit_repayment","currency_exchange","bank_security_transfer")) {
            val item=build(listOf(fact(40,"-200",7),fact(41,"14",7,"USD",102)),relation(104,"transfer_pair",40,41,subtype)).projections.single()
            assertEquals("internal_transfer",item.economicType?.value);assertEquals(subtype,item.transferSubtype)
            assertEquals(CashAmount.parse("0"),item.netAmount);assertTrue(item.visible);assertNull(item.hiddenReason)
        }
    }
    @Test fun refundsReduceExpenseWithoutMovingDateOrCreatingIncome() {
        val partial=build(listOf(fact(20,"-100",5),fact(21,"30",8)),relation(102,"refund_offset",20,21)).projections.single()
        val full=build(listOf(fact(30,"-50",6),fact(31,"50",9)),relation(103,"refund_offset",30,31)).projections.single()
        assertEquals(CashAmount.parse("-70"),partial.netAmount);assertEquals(5,partial.occurredAt!!.atOffset(ZoneOffset.UTC).dayOfMonth)
        assertEquals("expense",partial.economicType?.value);assertTrue(partial.visible)
        assertEquals(CashAmount.parse("0"),full.netAmount);assertEquals("expense",full.economicType?.value)
        assertTrue(full.visible);assertNull(full.hiddenReason)
    }
    @Test fun zeroRefundRemainsVisibleWithEvidence() {
        val relation=relation(1,"refund_offset",1,2)
        val item=build(listOf(fact(1,"0",5),fact(2,"0",6)),relation).projections.single()
        assertEquals("expense",item.economicType?.value);assertEquals(CashAmount.parse("0"),item.netAmount)
        assertTrue(item.visible);assertNull(item.hiddenReason);assertEquals(listOf(1L,2L),item.memberIds)
        assertEquals(listOf(relation),item.relations)
    }
    @Test fun refundRejectsSingleZeroEndpointOrZeroCrossCurrency() {
        for((expense,refund,currency) in listOf(Triple("0","12.50","CNY"),Triple("-12.50","0","CNY"),Triple("0","0","USD")))
            invalid {build(listOf(fact(1,expense,5),fact(2,refund,6,currency)),relation(1,"refund_offset",1,2))}
    }
    @Test fun mirroredExpenseAndRefundDiamondOffsetsOnce() {
        val item=build(listOf(fact(1,"-56.40"),fact(2,"-56.40"),fact(3,"45.21"),fact(4,"45.21")),
            relation(1,"payment_mirror",1,2),relation(2,"payment_mirror",3,4),
            relation(3,"refund_offset",1,3,amount="45.21"),relation(4,"refund_offset",2,4,amount="45.21")).projections.single()
        assertEquals(CashAmount.parse("-11.19"),item.netAmount);assertEquals(listOf(1L,2L,3L,4L),item.memberIds);assertEquals(4,item.relations.size)
    }
    @Test fun invalidRelationsFailClosed() {
        for((kind,left,right) in listOf(Triple("refund_offset",21L,20L),Triple("refund_offset",20L,999L),Triple("unknown",20L,21L)))
            invalid {build(listOf(fact(20,"-100",5),fact(21,"30",8)),relation(1,kind,left,right))}
    }
    @Test fun crossCurrencyAndExcessRefundFailClosed() {
        invalid {build(listOf(fact(20,"-100",5),fact(21,"30",8,"USD")),relation(102,"refund_offset",20,21))}
        invalid {build(listOf(fact(60,"-20",11),fact(61,"30",12)),relation(106,"refund_offset",60,61))}
    }
    @Test fun relationKindInvariantsFailClosed() {
        invalid {build(listOf(fact(10,"-100",4),fact(11,"-99",4)),relation(101,"payment_mirror",10,11))}
        invalid {build(listOf(fact(10,"-100",4),fact(11,"-100",4,"USD")),relation(101,"payment_mirror",10,11))}
        invalid {build(listOf(fact(40,"-200",7),fact(41,"200",7)),relation(9,"transfer_pair",40,40))}
        invalid {build(listOf(fact(40,"-200",7),fact(41,"200",7),fact(42,"30",7)),relation(10,"transfer_pair",40,41),relation(11,"refund_offset",40,42))}
    }
    @Test fun transferEndpointInvariantsFailClosed() {
        for(amount in listOf("199","-200")) invalid {build(listOf(fact(40,"-200",7),fact(41,amount,7)),relation(104,"transfer_pair",40,41,"ordinary_transfer"))}
    }
    @Test fun transferRejectsZeroAmountEndpoint() {
        for(index in 0..1) invalid {build(listOf(fact(40,if(index==0) "0" else "-200",7),fact(41,if(index==1) "0" else "200",7)),relation(104,"transfer_pair",40,41,"ordinary_transfer"))}
    }
    @Test fun currencyExchangeRequiresDistinctCurrencies() {
        invalid {build(listOf(fact(40,"-200",7),fact(41,"200",7)),relation(104,"transfer_pair",40,41,"currency_exchange"))}
    }
    @Test fun multipleRootsAndCyclesFailClosed() {
        invalid {build(listOf(fact(10,"-100",4),fact(11,"-100",4)),relation(1,"payment_mirror",10,11),relation(2,"payment_mirror",11,10))}
    }
    @Test fun projectionTypesAreImmutableAndUseControlledValues() {
        val fact=fact(1,"-1");val relation=relation(1,"payment_mirror",1,2)
        assertEquals("expense",CashEconomicType.EXPENSE.value);assertEquals("payment_mirror",CashProjectionComposition.PAYMENT_MIRROR.value)
        assertEquals("CNY",fact.currency);assertEquals("accepted",relation.status)
        assertTrue(fact.javaClass.declaredFields.filterNot {java.lang.reflect.Modifier.isStatic(it.modifiers)}.all {java.lang.reflect.Modifier.isFinal(it.modifiers)})
        assertTrue(fact.javaClass.methods.none {it.name=="setAmount"})
        assertEquals("projection.incomplete",CashProjectionFailure("projection.incomplete").code)
    }
    @Test fun projectionFactRejectsEveryOriginalNonexactAmount() {
        for(amount in listOf("NaN","Infinity","1.0000000000000000001"))
            assertEquals("projection.invalid_fact",assertFailsWith<CashProjectionFailure> {fact(1,amount)}.code)
    }
    @Test fun refundArithmeticPreservesDecimalRepresentationAndPythonContext() {
        for((expense,refund,expected) in listOf(Triple("-1.2300","0.2000","-1.0300"),
            Triple("-1.23"+"0".repeat(100),"0.20"+"0".repeat(100),"-1.03"+"0".repeat(77)),
            Triple("-0.0000","0.00","0.0000"))) {
            val item=build(listOf(fact(1,expense),fact(2,refund)),relation(1,"refund_offset",1,2)).projections.single()
            assertEquals(CashAmount.parse(expected).representation,item.netAmount!!.representation)
        }
    }
}
