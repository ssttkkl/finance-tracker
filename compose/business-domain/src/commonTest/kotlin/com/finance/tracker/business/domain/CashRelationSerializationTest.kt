package com.finance.tracker.business.domain

import kotlin.test.*
import kotlinx.serialization.json.*

class CashRelationSerializationTest {
    private val cached=Json.parseToJsonElement("""{"version":1,"plan_digest":"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd","external_context_digest":"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee","proposals":[{"proposal_key":"proposal:a9468b3f1e8ae985e19d61ee4dcb5b58","kind":"refund_offset","status":"pending_review","rule_id":"scan.refund","confidence":"weak","primary_ref":"alipay:R1","secondary_ref":"wechat:R2","anchor_ref":"alipay:R1","primary_fact_type":"cash","secondary_fact_type":"cash","created_by":"system","open_leg":false,"evidence":{"amount_delta":"0.00","time_delta_seconds":null,"same_currency":true,"counterparty_similarity":"","source_pair":["",""],"rule_id":"","candidate_count":1,"signals":[],"open_leg":false,"anchor_role":"","candidate_refs":["wechat:R2","alipay:R1"],"extras":{"refund_amount":"30.00"}}},{"proposal_key":"proposal:b033578bd7cee042c047a749ef853aef","kind":"refund_offset","status":"accepted","rule_id":"scan.refund","confidence":"weak","primary_ref":"alipay:R1","secondary_ref":"wechat:R2","anchor_ref":"alipay:R1","primary_fact_type":"cash","secondary_fact_type":"cash","created_by":"system","open_leg":false,"evidence":{"amount_delta":"0","time_delta_seconds":5,"same_currency":true,"counterparty_similarity":"","source_pair":["",""],"rule_id":"","candidate_count":1,"signals":["refund"],"open_leg":false,"anchor_role":"","candidate_refs":["wechat:R2"],"extras":{"refund_amount":"30.00"}}},{"proposal_key":"proposal:e50b7ad4ba72100e36d1a58713c9907c","kind":"transfer_pair","status":"pending_review","rule_id":"scan.open","confidence":"weak","primary_ref":"wechat:R2","secondary_ref":"","anchor_ref":"wechat:R2","primary_fact_type":"cash","secondary_fact_type":"cash","created_by":"system","open_leg":true,"evidence":{"amount_delta":"0","time_delta_seconds":0,"same_currency":true,"counterparty_similarity":"","source_pair":["",""],"rule_id":"","candidate_count":1,"signals":[],"open_leg":true,"anchor_role":"","candidate_refs":["alipay:R1"],"extras":{}}}]}""")
    private val rules=CashRelationSerializationRules(cashRelationCore,
        {value ->CashAmount.parse(value.toString())},PlatformRefundTestAdapters::absolute,
        RelationPipelineTestAdapters::difference,
        {value ->runCatching {cashRelationCore.stableTimestamp(value)}.getOrElse {value.toString()}},
        {value ->if(value is CashAmount) (if(PlatformRefundTestAdapters.sign(value)<0) "-" else "")+cashAbsolutePlainText(value) else value})
    private fun fact(id:String="p1",amount:String="-100.00",recordId:String="R1",source:String="alipay",
        type:String="consumption",occurred:String="2026-10-01T10:00:00+00:00")=
        RelationFact(id=id,amount=CashAmount.parse(amount),currency="CNY",accountId="a",accountName="账户",
            occurredAt=occurred,counterparty="商户",recordType=type,recordSubtype="not_applicable",
            billSource=source,source=source,recordId=recordId)
    private fun proposal()=RelationProposal(kind="refund_offset",primaryFactId="p1",secondaryFactId="q1",
        status="accepted",ruleId="scan.refund",anchorFactId="p1",
        evidence=RelationEvidence(amountDelta="0",timeDeltaSeconds=5,signals=listOf("refund"),
            candidateFactIds=listOf("q1"),extras=linkedMapOf("refund_amount" to "30.00")))
    @Test fun serializedPlanKeepsOriginalStableReferencesAndEvidence() {
        val facts=listOf(fact(),fact("q1","30.00","R2","wechat","refund","2026-10-02T10:00:00+00:00"))
        val open=proposal().copy(kind="transfer_pair",primaryFactId="q1",secondaryFactId=null,
            status="pending_review",ruleId="scan.open",anchorFactId="",
            evidence=RelationEvidence(openLeg=true,candidateFactIds=listOf("p1")))
        val pending=proposal().copy(status="pending_review",confidence="weak",
            evidence=RelationEvidence(amountDelta="0.00",timeDeltaSeconds=null,
                candidateFactIds=listOf("q1","p1"),extras=linkedMapOf("refund_amount" to "30.00")))
        val plan=RelationPlan(facts,listOf(proposal(),open,pending),"d".repeat(64),"e".repeat(64))
        assertEquals(cached,sourceJson(rules.serialize(plan,::SourceJsonInteger),{null},::pythonFloatText))
        assertEquals("import_relation_reconfirmation_required",assertFailsWith<IllegalArgumentException> {
            rules.serialize(plan.copy(contextDigest=""))
        }.message)
    }
    @Test fun remainingPreservesOverdrawAndInvalidAppliedFallback() {
        val f=listOf(fact(),fact("q1","30.00",type="refund"))
        fun row(applied:Any?)=mapOf("kind" to "refund_offset","status" to "accepted",
            "primary_fact_id" to "p1","secondary_fact_id" to "q1","applied_amount" to applied)
        val result=rules.initialRemaining(f,listOf(row("90.00"),row("bad")))
        assertEquals(CashAmount.parse("-20.00"),result["p1"])
        assertEquals(setOf("p1"),result.keys)
        assertEquals(CashAmount.parse("100.00"),rules.initialRemaining(f,emptyList())["p1"])
        assertEquals("R1",rules.factDetailRow(f[0])["record_id"])
        assertEquals(CashAmount.parse("-100.00"),rules.factDetailRow(f[0])["amount"])
    }
    @Test fun nestedCacheContainersSortWithPythonCodePointOrder() {
        val value=mapOf("nested" to listOf(mapOf("amount" to CashAmount.parse("-1.20")),
            setOf("😀","\uE000","a")),"float" to 1.0)
        val result=rules.cacheJsonValue(value) as Map<*,*>
        assertEquals(1.0,result["float"])
        assertEquals(listOf(mapOf("amount" to "-1.20"),listOf("a","\uE000","😀")),result["nested"])
    }

}
