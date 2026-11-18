package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.*

class CashRelationValidationTest {
    private val rules=RelationValidationTestAdapters.rules
    private val serialization=RelationSerializationTestAdapters.rules
    private val golden=Json.parseToJsonElement(CashRelationPlanningOriginalVectors.json).jsonObject.getValue("cases").jsonObject
    private fun fact(id:String="e1",amount:String="-100.00",recordId:String="E1",type:String="consumption",occurred:String="2026-10-01T10:00:00+00:00")=
        RelationFact(id,CashAmount.parse(amount),"CNY","a",accountName="账户",occurredAt=occurred,counterparty="商户",
            recordType=type,recordSubtype=defaultCashRecordSubtype(type),billSource="alipay",source="alipay",recordId=recordId)
    private fun encode(value:Any?)=sourceJson(value,{null},::pythonFloatText)
    private fun proposal(amount:String="30.00")=RelationProposal(kind="refund_offset",primaryFactId="e1",secondaryFactId="r1",
        status="accepted",ruleId="scan.refund",evidence=RelationEvidence(extras=linkedMapOf("refund_amount" to amount)),anchorFactId="e1")
    @Test fun completeValidationContextReasonsAndFilteringMatchOriginalOracle() {
        val expense=fact();val refund=fact("r1","30.00","R1","refund","2026-10-02T10:00:00+00:00")
        val a=fact("c1","-20.00","C1").copy(parentId="e1",componentOrdinal=1)
        val b=fact("c2","-5.00","C2").copy(parentId="e1",componentOrdinal=2)
        val context=rules.buildValidationContext(listOf(expense,refund,a,b))
        val actual=linkedMapOf<String,Any?>("facts" to context.projectionFacts.map {linkedMapOf("id" to it.id,
            "amount" to LedgerArithmeticValue.fromCanonical(it.amount!!.numericText()).toPlainString(),"currency" to it.currency,
            "record_id" to it.recordId,"counterparty" to it.counterparty,"note" to it.note,"source_type" to it.sourceType,
            "cash_granularity" to it.cashGranularity)},"component_to_parent" to context.componentToParent.map {listOf(it.first,it.second)},
            "active_ids" to context.activeFacts.map {it.id})
        assertEquals(golden.getValue("context"),encode(actual))
        val legal=proposal();val illegal=proposal("130.00")
        val open=RelationProposal(kind="transfer_pair",primaryFactId="e1",secondaryFactId=null,status="pending_review",
            ruleId="scan.open",evidence=RelationEvidence(openLeg=true))
        val rows=listOf<Map<String,Any?>>(linkedMapOf("kind" to "refund_offset","status" to "accepted",
            "primary_fact_id" to "e1","secondary_fact_id" to "r1","applied_amount" to "20.00",
            "rule_id" to "scan.refund","created_by" to "system","decided_by" to "user"))
        val facts=listOf(expense,refund)
        assertEquals(golden.getValue("reason"),encode(listOf(rules.validationReason(facts,emptyList(),legal),
            rules.validationReason(facts,emptyList(),illegal),rules.validationReason(facts,emptyList(),open),rules.validationReason(facts,rows,legal))))
        val filtered=rules.filterIllegalProposals(facts,emptyList(),listOf(legal,illegal,open))
        val result=JsonArray(filtered.sortedWith(compareBy<RelationProposal> {serialization.proposalKey(it)}
            .thenBy {CashAmount.parse(it.evidence.extras["refund_amount"]?.toString() ?: "0")}).map {p ->
                buildJsonObject {put("key",serialization.proposalKey(p));put("body",p.toJsonValue(::encode))}})
        assertEquals(golden.getValue("filtered"),result)
    }
    @Test fun localizedInputsExcludeUnrelatedProjectionButRetainWholeComponentContext() {
        val facts=listOf(fact(),fact("r1","30.00","R1","refund"),fact("other","-5","O"))
        val context=rules.buildValidationContext(facts)
        val local=assertNotNull(rules.localizedValidationInputs(context,emptyList(),emptyList(),proposal()))
        val mapping=context.componentToParent.toMap()
        assertEquals(setOf(mapping.getValue("e1"),mapping.getValue("r1")),local.third.projectionFacts.map {it.id}.toSet())
        assertEquals(context.components,local.third.components)
        assertEquals(context.activeFacts,local.third.activeFacts)
        // The original localized context retains every component; the graph conservatively rejects unrelated parents.
        assertEquals("projection_invalid",rules.validationReason(facts,local.first,proposal(),emptyList(),local.third))
        assertNull(rules.localizedValidationInputs(context,emptyList(),emptyList(),proposal().copy(secondaryFactId="missing")))
    }
}
