package com.finance.tracker.backend

import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.*

class CashDraftProjectionMigrationTest {
    private val created=Instant.parse("2026-01-02T00:00:00Z")
    private fun draft(amount: String?=null,currency: String?=null,account: Long?=null,occurred: Instant?=null,creation: Instant?=created,state: String="draft")=
        CashProjectionFact.create(1,account,occurred,amount,currency,"待补流水",null,"来源原文","fixture","draft-1",state=state,createdAt=creation)
    private fun posted(id: Long=2,amount: String="-10",attention: Boolean=false)=CashProjectionFact.create(id,101,created,amount,"CNY","正式流水",null,"","fixture",id.toString(),needsAttention=attention)
    private fun build(vararg facts: CashProjectionFact)=buildCashProjections(facts.toList(),emptyList()).projections.single()
    private fun refund(expense: CashProjectionFact,refund: CashProjectionFact,amount: String?=null)=buildCashProjections(listOf(expense,refund),
        listOf(CashProjectionRelation.create(1,"refund_offset",2,3,appliedAmount=amount))).projections.single()
    @Test fun unknownDraftIsSingletonWithoutFakeFinancialValues() {
        val item=build(draft())
        assertEquals("draft",item.state);assertFalse(item.statisticsEligible);assertNull(item.netAmount);assertNull(item.economicType)
        assertNull(item.occurredAt);assertNull(item.primaryRecord.currency);assertEquals(listOf(1L),item.memberIds);assertEquals(emptyList(),item.relations)
        assertEquals(1,item.attentionCount);assertEquals("pending",item.confirmationStatus);assertEquals(created,item.sortAt);assertTrue(item.visible)
    }
    @Test fun knownDraftAmountRetainsValueWithoutStatisticsOrGuessedType() {
        val item=build(draft("123.45","CNY",101))
        assertEquals(CashAmount.parse("123.45"),item.netAmount);assertNull(item.economicType);assertFalse(item.statisticsEligible)
    }
    @Test fun occurrenceIsSortKeyInsteadOfCreationTime() {
        val occurred=Instant.parse("2025-12-01T00:00:00Z");val item=build(draft(occurred=occurred))
        assertEquals(occurred,item.occurredAt);assertEquals(occurred,item.sortAt);assertEquals(created,item.primaryRecord.createdAt)
    }
    @Test fun pendingPostedRecordsKeepStatisticsWithoutUnconfirmedMerge() {
        val result=buildCashProjections(listOf(posted(attention=true),posted(3,attention=true),draft()),
            listOf(CashProjectionRelation.create(1,"payment_mirror",2,3,status="pending_review")))
        assertEquals(3,result.projections.size)
        for(item in result.projections) {assertEquals("pending",item.confirmationStatus);assertEquals(item.state=="posted",item.statisticsEligible);assertEquals(1,item.attentionCount)}
    }
    @Test fun confirmedRelationCannotConsumeCompleteDraft() {
        assertEquals("projection.invalid_relation",assertFailsWith<CashProjectionFailure> {buildCashProjections(listOf(draft("-10","CNY",101,created),posted()),
            listOf(CashProjectionRelation.create(1,"payment_mirror",1,2)))}.code)
    }
    @Test fun attentionCountsUniqueMembersAndKeepsFullRefundVisible() {
        val item=refund(posted(attention=true),posted(3,"10",true))
        assertEquals("posted",item.state);assertTrue(item.statisticsEligible);assertEquals(2,item.attentionCount);assertEquals("pending",item.confirmationStatus)
        assertEquals(CashAmount.parse("0"),item.netAmount);assertTrue(item.visible);assertNull(item.hiddenReason)
        val cleared=refund(posted(),posted(3,"10"))
        assertEquals("confirmed",cleared.confirmationStatus);assertEquals(0,cleared.attentionCount);assertTrue(cleared.visible);assertNull(cleared.hiddenReason)
    }
    @Test fun draftKeepsValueValidationAndTypedTimeAndBooleanBoundary() {
        for(action in listOf<()->Unit>({draft(amount="NaN")},{draft(currency="INVALID")},{draft(creation=null)},{draft(state="pending")}))
            assertEquals("projection.invalid_fact",assertFailsWith<CashProjectionFailure>(block=action).code)
        val factory=CashProjectionFact.Companion.javaClass.methods.single {it.name=="create"}
        assertEquals(Instant::class.java,factory.parameterTypes[2]);assertEquals(Instant::class.java,factory.parameterTypes[13])
        assertFalse(factory.parameterTypes[2].isAssignableFrom(LocalDateTime::class.java))
        assertFalse(factory.parameterTypes[13].isAssignableFrom(LocalDateTime::class.java))
        assertEquals(Boolean::class.javaPrimitiveType,factory.parameterTypes[14])
        assertFalse(factory.parameterTypes[14].isAssignableFrom(String::class.java))
    }
    @Test fun largeFormalRefundKeepsExactPrecision() {
        val amount="12345678901234567890.123456789012345678"
        val item=refund(posted(amount="-$amount"),posted(3,amount),amount)
        assertEquals(CashAmount.parse("0"),item.netAmount);assertTrue(item.visible);assertNull(item.hiddenReason)
    }
}
