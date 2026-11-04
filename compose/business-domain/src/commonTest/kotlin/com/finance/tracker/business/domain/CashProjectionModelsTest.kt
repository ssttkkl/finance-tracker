package com.finance.tracker.business.domain

import kotlin.time.Instant
import kotlin.test.*

class CashProjectionModelsTest {
    // Only normalized ASCII currency fixtures; native Unicode normalization remains its existing adapter.
    private val values=CashProjectionValues<Long,Instant>({it>0L},{runCatching {formatPythonCashTimestamp(it)}.isSuccess},{it.uppercase()})
    private val at=Instant.parse("2026-10-01T10:00:00Z")
    private fun fact(id:Long=1,amount:String?="-10.00",state:String="posted",occurred:Instant?=at,
        created:Instant?=null,funding:Long?=null,currency:String?="CNY")=CashProjectionFact.create(values,
        id,101L,occurred,amount,currency,"商户",null,"","alipay","R-$id",funding,"atomic",state,created)
    @Test fun postedAndDraftStatesRetainDifferentRequiredFacts() {
        val posted=fact(currency="cny")
        assertEquals("CNY",posted.currency)
        assertEquals(CashAmount.parse("-10.00"),posted.amount)
        assertSame(posted,posted.withAttention(false))
        val attention=posted.withAttention(true).withPublicFields("新商户","category","note")
        assertEquals(posted.id,attention.id)
        assertEquals(posted.occurredAt,attention.occurredAt)
        assertEquals(posted.amount,attention.amount)
        assertTrue(attention.needsAttention)
        assertEquals("新商户",attention.counterparty)
        val draft=fact(amount=null,state="draft",occurred=null,created=at,currency=null)
        assertNull(draft.amount)
        assertEquals(at,draft.createdAt)
        for(block in listOf<()->Unit>({fact(id=0)},{fact(amount=null)},{fact(occurred=null)},
            {fact(state="draft",occurred=null)},{fact(state="draft",funding=9)},
            {fact(currency="USDD")})) {
            assertEquals("projection.invalid_fact",assertFailsWith<CashProjectionDomainFailure>(block=block).code)
        }
    }
    @Test fun relationAmountsAndProjectionIdentityKeepExactTypes() {
        val a=fact(id=9007199254740993L)
        val relation=CashProjectionRelation.create(values,1L,"refund_offset",a.id,2L,"accepted","1.00")
        assertEquals(CashAmount.parse("1"),relation.appliedAmount)
        for(amount in listOf("-1","invalid")) assertEquals("projection.invalid_relation",assertFailsWith<CashProjectionDomainFailure> {
            CashProjectionRelation.create(values,1L,"refund_offset",a.id,2L,"accepted",amount)
        }.code)
        assertEquals("projection.invalid_relation",assertFailsWith<CashProjectionDomainFailure> {
            CashProjectionRelation.create(values,0L,"payment_mirror",a.id,2L)
        }.code)
        val projection=CashProjection("cash:${a.id}",a,a.amount,CashEconomicType.EXPENSE,true,null,null,
            listOf(a.id),listOf(a to listOf("root")),listOf(relation),emptyList(),attentionCount=1)
        assertEquals(at,projection.sortAt)
        assertEquals("pending",projection.confirmationStatus)
        assertEquals(setOf(a.id),CashProjectionBuild(listOf(projection)).memberIds)
        assertEquals(CASH_PROJECTION_RULES_VERSION,CashProjectionBuild(listOf(projection)).rulesVersion)
    }
}
