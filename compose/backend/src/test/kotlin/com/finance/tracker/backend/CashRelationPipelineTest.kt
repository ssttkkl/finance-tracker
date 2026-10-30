package com.finance.tracker.backend

import java.math.BigDecimal
import kotlin.test.*
import kotlinx.serialization.json.*

class CashRelationPipelineTest {
    private val golden: JsonObject by lazy {
        Json.parseToJsonElement(javaClass.getResourceAsStream("/python-cash-relation-pipeline.json")!!
            .bufferedReader().use { it.readText() }).jsonObject.getValue("cases").jsonObject
    }
    private fun fact(id: String, amount: String = "-100.00", account: String = "a",
        recordType: String = "consumption", billSource: String = "alipay", source: String = "alipay",
        occurred: String = "2026-10-01T10:00:00+00:00", recordId: String = "R-$id",
        counterparty: String = "商户", recordSubtype: String = "not_applicable") = RelationFact(id = id,
        amount = BigDecimal(amount), currency = "CNY", accountId = account, accountName = "账户", occurredAt = occurred,
        counterparty = counterparty, note = "", recordType = recordType, recordSubtype = recordSubtype,
        billSource = billSource, source = source, recordId = recordId, paymentMethod = "余额")
    private fun proposals(values: List<RelationProposal>): JsonElement = sourceJson(values.map { it.json() })

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
        val index = CashFactCandidateIndex(facts) { CashRelationCore.sourceGroup(it) }
        assertEquals(golden.getValue("phases"), proposals(CashRelationPipeline.run(facts, index = index)))
        assertEquals(golden.getValue("discovery_true").jsonPrimitive.boolean, CashRelationPipeline.needsRelationDiscovery(facts))
        assertEquals(golden.getValue("discovery_seed_consumption").jsonPrimitive.boolean,
            CashRelationPipeline.needsRelationDiscovery(facts, listOf("p1")))
        assertEquals(golden.getValue("discovery_seed_positive").jsonPrimitive.boolean,
            CashRelationPipeline.needsRelationDiscovery(facts, listOf("rr")))
        assertEquals(golden.getValue("discovery_empty").jsonPrimitive.boolean, CashRelationPipeline.needsRelationDiscovery(emptyList()))
        assertEquals(golden.getValue("canonical"), proposals(CashRelationPipeline.matchCanonicalPaymentMirrors(listOf(p1, b1))))
        assertEquals(golden.getValue("bank_seeds"), sourceJson(CashRelationPipeline.bankRefundSeedIds(facts, setOf("rr"))))
        val shared = CashRelationPipeline.shareRefundRemainingAcrossMirrorEvents(listOf(p1, b1),
            mapOf("p1" to setOf("p1", "b1"), "b1" to setOf("p1", "b1")),
            mapOf("p1" to BigDecimal("60.00"), "b1" to BigDecimal("100.00")))
        assertEquals(golden.getValue("shared"), sourceJson(shared.mapValues { it.value.toPlainString() }))
        val evidence = RelationEvidence(ruleId = "scan.alipay.order_prefix.v1",
            extras = mapOf("refund_amount" to "100.00"))
        val phaseA = listOf(RelationProposal(kind = "refund_offset", primaryFactId = "p1", secondaryFactId = "rr",
            status = "accepted", ruleId = "scan.alipay.order_prefix.v1", evidence = evidence, anchorFactId = "p1"))
        val demoted = CashRelationPipeline.demoteOverlappingPhaseARefunds(listOf(p1, b1, refund, bankRefund), phaseA,
            listOf(mapOf("kind" to "refund_offset", "status" to "accepted", "primary_fact_id" to "b1",
                "secondary_fact_id" to "br")), listOf("p1" to "b1"))
        assertEquals(golden.getValue("demote"), proposals(demoted))
    }
}
