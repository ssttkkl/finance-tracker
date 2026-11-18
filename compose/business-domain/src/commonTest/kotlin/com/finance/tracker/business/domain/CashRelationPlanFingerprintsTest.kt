package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.*

class CashRelationPlanFingerprintsTest {
    private val serialization=RelationSerializationTestAdapters.rules
    private val rules=CashRelationPlanFingerprints(serialization,
        {value:CashAmount ->LedgerArithmeticValue.fromCashAmount(value).toPlainString()},
        {value ->sourceJson(value,{null},::pythonFloatText).toString()})
    private val golden=Json.parseToJsonElement(CashRelationPlanningOriginalVectors.json).jsonObject.getValue("cases").jsonObject
    private fun fact(id:String,amount:String,recordId:String,type:String="consumption",occurred:String="2026-10-01T10:00:00+00:00")=
        RelationFact(id,CashAmount.parse(amount),"CNY","a",accountName="账户",occurredAt=occurred,counterparty="商户",
            recordType=type,recordSubtype=defaultCashRecordSubtype(type),billSource="alipay",source="alipay",recordId=recordId)
    @Test fun contextAndExternalDigestsRetainOriginalCanonicalPayloads() {
        val facts=listOf(fact("e1","-100.00","E1"),fact("r1","30.00","R1","refund","2026-10-02T10:00:00+00:00"))
        val proposal=RelationProposal(kind="refund_offset",primaryFactId="e1",secondaryFactId="r1",status="accepted",ruleId="scan.refund",
            evidence=RelationEvidence(extras=linkedMapOf("refund_amount" to "30.00")),anchorFactId="e1")
        val rows=listOf<Map<String,Any?>>(linkedMapOf("kind" to "refund_offset","status" to "accepted","primary_fact_id" to "e1",
            "secondary_fact_id" to "r1","applied_amount" to "20.00","rule_id" to "scan.refund","created_by" to "system","decided_by" to "user"))
        val digest=rules.relationContextDigest(facts,rows,listOf(proposal))
        assertEquals(golden.getValue("context_digest").jsonPrimitive.content,digest)
        assertEquals(digest,rules.relationContextDigest(facts.reversed(),rows,listOf(proposal)))
        assertNotEquals(digest,rules.relationContextDigest(facts,rows,listOf(proposal.copy(status="pending_review"))))
        val aliases=mapOf("1234" to listOf("a","b"));val identifiers=mapOf("6222" to listOf("a"))
        val external=rules.externalContextDigest(facts,rows,aliases,identifiers)
        assertEquals(golden.getValue("external_digest").jsonPrimitive.content,external)
        assertEquals(external,rules.externalContextDigest(facts.reversed(),rows,mapOf("1234" to listOf("b","a")),identifiers))
        assertNotEquals(external,rules.externalContextDigest(facts,rows,aliases,mapOf("6222" to listOf("other"))))
    }
    @Test fun virtualIdentifiersRemainStableAndAvoidUsedValues() {
        val used=mutableSetOf<Long>()
        val first=cashRelationSnapshotId("parent:账户",used)
        val second=cashRelationSnapshotId("parent:账户",used)
        assertTrue(first>0)
        assertEquals(if(first<Long.MAX_VALUE) first+1 else 1,second)
        assertEquals(setOf(first,second),used)
        assertEquals(first,cashRelationSnapshotId("parent:账户",mutableSetOf()))
    }
}
