package com.finance.tracker.backend

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*

class CashRelationSerializationTest {
    private val golden: JsonObject by lazy {
        Json.parseToJsonElement(javaClass.getResourceAsStream("/python-cash-relation-serialization.json")!!
            .bufferedReader().use { it.readText() }).jsonObject.getValue("cases").jsonObject
    }
    private fun fact(id: String = "1", amount: String = "-10.00", recordId: String = "R-$id") = RelationFact(
        id = id, amount = BigDecimal(amount), currency = "CNY", accountId = "a", accountName = "现金",
        occurredAt = "2026-10-01T10:00:00+00:00", recordId = recordId, billSource = "alipay", source = "alipay")

    @Test fun relationSerializationMatchesPython() {
        val rows = listOf<Map<String, Any?>>(
            linkedMapOf("id" to "1", "amount" to "-10.00", "record_id" to "ALI-1", "source_type" to "alipay",
                "raw_payload" to mapOf("订单号" to "A1"), "counterparty_account_attrs" to listOf("tail"),
                "record_type" to "transfer_out", "record_subtype" to "not_applicable", "ordinal" to "3"),
            linkedMapOf("id" to "2", "amount" to "5.00", "source_payload" to mapOf("交易单号" to "W1"),
                "bill_source" to "wechat", "deleted_at" to "2026-10-01T00:00:00+00:00", "account_name" to "现金"),
            linkedMapOf("id" to "3", "amount" to "1.00", "component_ordinal" to "bad", "currency" to "",
                "fact_type" to "investment"))
        val facts = rows.map(CashRelationSerialization::factViewFromRow)
        assertEquals(golden.getValue("rows"), sourceJson(facts.map(CashRelationSerialization::factDetailRow)))
        assertEquals(golden.getValue("human").jsonArray.map { it.jsonPrimitive.boolean }, listOf(
            CashRelationSerialization.isHumanDecision(mapOf("created_by" to "system", "decided_by" to "user")),
            CashRelationSerialization.isHumanDecision(mapOf("created_by" to "system", "decided_by" to "system")),
            CashRelationSerialization.isHumanDecision(mapOf("created_by" to "admin"))))
        assertEquals(golden.getValue("refs").jsonArray.map { it.jsonPrimitive.content }, listOf(
            CashRelationSerialization.stableFactRef("1", mapOf("1" to fact(recordId = "ALI-1"))),
            CashRelationSerialization.stableFactRef("preview:9"),
            CashRelationSerialization.stableFactRef("missing")))
        val proposal = RelationProposal(kind = "refund_offset", primaryFactId = "1", secondaryFactId = "2",
            status = "pending_review", ruleId = "scan.rule", anchorFactId = "1",
            evidence = RelationEvidence(amountDelta = "0", timeDeltaSeconds = 5, signals = listOf("refund"),
                candidateFactIds = listOf("2", "preview:9"),
                extras = linkedMapOf("amount" to BigDecimal("1.20"),
                    "when" to Instant.parse("2026-10-01T00:00:00Z"), "items" to setOf(2, 1))))
        val openLeg = RelationProposal(kind = "transfer_pair", primaryFactId = "preview:7", secondaryFactId = null,
            status = "pending_review", ruleId = "scan.rule2", anchorFactId = "preview:7",
            evidence = RelationEvidence(openLeg = true, anchorRole = "out", candidateFactIds = listOf("1")))
        val plan = RelationPlan(facts + fact("7", "-10.00", ""), listOf(proposal, openLeg),
            "d".repeat(64), "e".repeat(64))
        assertEquals(golden.getValue("keys"), sourceJson(listOf(
            CashRelationSerialization.proposalKey(proposal, plan.facts),
            CashRelationSerialization.proposalKey(openLeg, plan.facts))))
        assertEquals(golden.getValue("remaining"), sourceJson(CashRelationSerialization.initialRemaining(plan.facts,
            listOf(mapOf("kind" to "refund_offset", "status" to "accepted", "primary_fact_id" to "1",
                "secondary_fact_id" to "2", "applied_amount" to "3.50"),
                mapOf("kind" to "refund_offset", "status" to "pending_review", "primary_fact_id" to "1",
                    "secondary_fact_id" to "2", "applied_amount" to "99"))).mapValues { it.value.toPlainString() }))
        assertEquals(golden.getValue("serialized"), sourceJson(CashRelationSerialization.serialize(plan)))
        assertEquals(golden.getValue("error").jsonPrimitive.content, assertFailsWith<IllegalArgumentException> {
            CashRelationSerialization.serialize(RelationPlan(emptyList(), emptyList(), ""))
        }.message)
    }
}
