package com.finance.tracker.business.domain

import kotlin.test.*
import kotlinx.serialization.json.*

class CashRelationPipelineTest {
    private val golden=Json.parseToJsonElement("""{"phases":[{"kind":"payment_mirror","primary_fact_id":"p1","secondary_fact_id":"b1","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"payment_mirror.same_account.exact.business_day.v1","confidence":"strong","evidence":{"amount_delta":"0.00","time_delta_seconds":5,"same_currency":true,"counterparty_similarity":"商户","source_pair":["alipay","icbc_debit"],"rule_id":"payment_mirror.same_account.exact.business_day.v1","candidate_count":1,"signals":["platform_bank","exact_amount","time_window","platform_not_after_bank","same_account"],"open_leg":false,"anchor_role":"","candidate_fact_ids":["b1"],"extras":{"lag_bank_minus_platform":5}},"created_by":"system","anchor_fact_id":"","open_leg":false},{"kind":"payment_mirror","primary_fact_id":"rr","secondary_fact_id":"br","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"payment_mirror.refund_dual_source.v1","confidence":"strong","evidence":{"amount_delta":"0.00","time_delta_seconds":0,"same_currency":true,"counterparty_similarity":"商户","source_pair":["wechat","icbc_debit"],"rule_id":"payment_mirror.refund_dual_source.v1","candidate_count":1,"signals":["platform_bank","exact_amount","time_window","platform_not_after_bank","same_account"],"open_leg":false,"anchor_role":"","candidate_fact_ids":["br"],"extras":{"lag_bank_minus_platform":0}},"created_by":"system","anchor_fact_id":"","open_leg":false},{"kind":"transfer_pair","primary_fact_id":"to","secondary_fact_id":"ti","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"transfer_pair.normalized.same_currency.time10.v1","confidence":"strong","evidence":{"amount_delta":"0.00","time_delta_seconds":5,"same_currency":true,"counterparty_similarity":"","source_pair":["",""],"rule_id":"transfer_pair.normalized.same_currency.time10.v1","candidate_count":1,"signals":["opposite_sign","record_subtype","counterparty_account"],"open_leg":false,"anchor_role":"","candidate_fact_ids":["ti"],"extras":{}},"created_by":"system","anchor_fact_id":"to","open_leg":false},{"kind":"refund_offset","primary_fact_id":"p1","secondary_fact_id":"br","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"refund_offset.merchant_or_order.v1","confidence":"strong","evidence":{"amount_delta":"70.00","time_delta_seconds":172795,"same_currency":true,"counterparty_similarity":"商户","source_pair":["icbc_debit","icbc_debit"],"rule_id":"refund_offset.merchant_or_order.v1","candidate_count":2,"signals":["refund","merchant","same_account","partial_nearest_unique"],"open_leg":false,"anchor_role":"","candidate_fact_ids":["p1","re"],"extras":{"refund_amount":"30.00","expense_amount":"100.00","remaining_before":"100.00","days":"1","within_auto":"true","priority":"2"}},"created_by":"system","anchor_fact_id":"br","open_leg":false}],"discovery_true":true,"discovery_seed_consumption":true,"discovery_seed_positive":true,"discovery_empty":false,"canonical":[{"kind":"payment_mirror","primary_fact_id":"p1","secondary_fact_id":"b1","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"payment_mirror.same_account.exact.business_day.v1","confidence":"strong","evidence":{"amount_delta":"0.00","time_delta_seconds":5,"same_currency":true,"counterparty_similarity":"商户","source_pair":["alipay","icbc_debit"],"rule_id":"payment_mirror.same_account.exact.business_day.v1","candidate_count":1,"signals":["platform_bank","exact_amount","time_window","platform_not_after_bank","same_account"],"open_leg":false,"anchor_role":"","candidate_fact_ids":["b1"],"extras":{"lag_bank_minus_platform":5}},"created_by":"system","anchor_fact_id":"","open_leg":false}],"bank_seeds":["br"],"shared":{"p1":"60.00","b1":"60.00"},"demote":[{"kind":"refund_offset","primary_fact_id":"p1","secondary_fact_id":"rr","primary_fact_type":"cash","secondary_fact_type":"cash","status":"accepted","rule_id":"scan.alipay.order_prefix.v1","confidence":"weak","evidence":{"amount_delta":"0","time_delta_seconds":0,"same_currency":true,"counterparty_similarity":"","source_pair":["",""],"rule_id":"scan.alipay.order_prefix.v1","candidate_count":1,"signals":[],"open_leg":false,"anchor_role":"","candidate_fact_ids":[],"extras":{"refund_amount":"100.00"}},"created_by":"system","anchor_fact_id":"p1","open_leg":false}]}""").jsonObject
    private val rules=RelationPipelineTestAdapters.rules
    private fun encode(value:Any?)=sourceJson(value,{null},::pythonFloatText)
    private fun fact(id: String, amount: String = "-100.00", account: String = "a",
        recordType: String = "consumption", billSource: String = "alipay", source: String = "alipay",
        occurred: String = "2026-10-01T10:00:00+00:00", recordId: String = "R-$id",
        counterparty: String = "商户", recordSubtype: String = "not_applicable") = RelationFact(id = id,
        amount = CashAmount.parse(amount), currency = "CNY", accountId = account, accountName = "账户", occurredAt = occurred,
        counterparty = counterparty, note = "", recordType = recordType, recordSubtype = recordSubtype,
        billSource = billSource, source = source, recordId = recordId, paymentMethod = "余额")
    private fun proposals(values: List<RelationProposal>): JsonElement = JsonArray(values.map { it.toJsonValue(::encode) })

    @Test fun pipelineOrchestrationMatchesPython() {
        val p1 = fact("p1")
        val b1 = fact("b1", billSource = "icbc_debit", source = "", occurred = "2026-10-01T10:00:05+00:00")
        val to = fact("to", "-50.00", "c", "transfer_out", "ccb_debit", "", recordId = "TO", recordSubtype = "ordinary_transfer")
        val ti = fact("ti", "50.00", "d", "transfer_in", "icbc_debit", "", "2026-10-01T10:00:05+00:00", "TI", recordSubtype = "ordinary_transfer")
        val expense = fact("re", "-30.00", recordType = "consumption", billSource = "wechat", source = "wechat", recordId = "RE")
        val refund = fact("rr", "30.00", recordType = "refund", billSource = "wechat", source = "wechat",
            occurred = "2026-10-03T10:00:00+00:00", recordId = "RR")
        val bankRefund = fact("br", "30.00", recordType = "refund", billSource = "icbc_debit", source = "",
            occurred = "2026-10-03T10:00:00+00:00", recordId = "BR")
        val facts = listOf(p1, b1, to, ti, expense, refund, bankRefund)
        val index = cashFactCandidateIndex(facts,cashRelationCore::sourceGroup)
        assertEquals(golden.getValue("phases"), proposals(rules.run(facts, index = index)))
        assertEquals(golden.getValue("discovery_true").jsonPrimitive.boolean, rules.needsRelationDiscovery(facts))
        assertEquals(golden.getValue("discovery_seed_consumption").jsonPrimitive.boolean,
            rules.needsRelationDiscovery(facts, listOf("p1")))
        assertEquals(golden.getValue("discovery_seed_positive").jsonPrimitive.boolean,
            rules.needsRelationDiscovery(facts, listOf("rr")))
        assertEquals(golden.getValue("discovery_empty").jsonPrimitive.boolean, rules.needsRelationDiscovery(emptyList()))
        assertEquals(golden.getValue("canonical"), proposals(rules.matchCanonicalPaymentMirrors(listOf(p1, b1))))
        assertEquals(golden.getValue("bank_seeds"), encode(rules.bankRefundSeedIds(facts, setOf("rr"))))
        val shared = rules.shareRefundRemainingAcrossMirrorEvents(listOf(p1, b1),
            mapOf("p1" to setOf("p1", "b1"), "b1" to setOf("p1", "b1")),
            mapOf("p1" to CashAmount.parse("60.00"), "b1" to CashAmount.parse("100.00")))
        assertEquals(golden.getValue("shared"), encode(shared.mapValues { cashAbsolutePlainText(it.value) }))
        val evidence = RelationEvidence(ruleId = "scan.alipay.order_prefix.v1",
            extras = mapOf("refund_amount" to "100.00"))
        val phaseA = listOf(RelationProposal(kind = "refund_offset", primaryFactId = "p1", secondaryFactId = "rr",
            status = "accepted", ruleId = "scan.alipay.order_prefix.v1", evidence = evidence, anchorFactId = "p1"))
        val demoted = rules.demoteOverlappingPhaseARefunds(listOf(p1, b1, refund, bankRefund), phaseA,
            listOf(mapOf("kind" to "refund_offset", "status" to "accepted", "primary_fact_id" to "b1",
                "secondary_fact_id" to "br")), listOf("p1" to "b1"))
        assertEquals(golden.getValue("demote"), proposals(demoted))
    }
    @Test fun sharedEventsDemoteOverCapacityAndKeepCallerState() {
        val p=fact("p",amount="-10.00")
        val b=fact("b",amount="-10.00",billSource="icbc_debit",source="")
        val r1=fact("r1",amount="6.00",recordType="refund")
        val r2=fact("r2",amount="5.00",recordType="refund")
        val members=setOf("p","b")
        val remaining=mapOf("p" to CashAmount.parse("2.00"),"b" to CashAmount.parse("5.00"))
        val shared=rules.shareRefundRemainingAcrossMirrorEvents(listOf(p,b),mapOf("p" to members,"b" to members),remaining)
        assertEquals(CashAmount.parse("0"),shared["p"])
        assertEquals(CashAmount.parse("0"),shared["b"])
        assertEquals(CashAmount.parse("2.00"),remaining["p"])
        fun proposal(id:String,amount:String)=RelationProposal(kind="refund_offset",primaryFactId="p",secondaryFactId=id,
            status="accepted",ruleId="scan.alipay.order_prefix.v1",
            evidence=RelationEvidence(extras=mapOf("refund_amount" to amount)))
        val proposed=listOf(proposal("r1","6.00"),proposal("r2","5.00"))
        assertEquals(listOf("accepted","pending_review"),rules.demoteOverlappingPhaseARefunds(listOf(p,b,r1,r2),proposed,
            emptyList(),listOf("p" to "b")).map {it.status})
        assertTrue(proposed.all {it.status=="accepted"})
    }

    @Test fun contextTracksAcceptedMirrorsAndRespectsOccupiedEdges() {
        val p=fact("p")
        val b=fact("b",billSource="icbc_debit",source="",occurred="2026-10-01T10:00:05+00:00")
        val ctx=MatchContext<CashAmount>("workspace")
        val output=rules.run(listOf(p,b),ctx)
        assertEquals(listOf("payment_mirror"),output.map {it.kind})
        assertEquals(setOf("p","b"),ctx.usedFactIds)
        assertEquals(listOf("p" to "b"),ctx.mirrorPairs())
        assertTrue(rules.run(listOf(p,b),ctx).isEmpty())
        assertEquals("workspace",ctx.workspaceId)
        assertTrue(rules.matchCanonicalPaymentMirrors(listOf(p,b),occupiedFactIds=setOf("p")).isEmpty())
    }

}
