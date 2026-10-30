package com.finance.tracker.backend

import java.math.BigDecimal
import kotlin.test.*
import kotlinx.serialization.json.*

class CashRelationPlanningTest {
    private val golden: JsonObject by lazy {
        Json.parseToJsonElement(javaClass.getResourceAsStream("/python-cash-relation-planning.json")!!
            .bufferedReader().use { it.readText() }).jsonObject.getValue("cases").jsonObject
    }
    private fun fact(id: String = "1", amount: String = "-10.00", recordId: String = "ALI-1",
        occurred: String = "2026-10-01T10:00:00+00:00", recordType: String = "consumption",
        source: String = "alipay", accountId: String = "a") = RelationFact(id = id, amount = BigDecimal(amount),
        currency = "CNY", accountId = accountId, accountName = "账户", occurredAt = occurred, counterparty = "商户",
        recordType = recordType, recordSubtype = defaultCashRecordSubtype(recordType), billSource = source,
        source = source, recordId = recordId)
    private fun amountText(value: CashAmount?): String =
        if (value == null) "" else BigDecimal(value.numericText()).stripTrailingZeros().toPlainString()
    private fun evidenceJson(evidence: RelationEvidence): Map<String, Any?> = linkedMapOf(
        "amount_delta" to evidence.amountDelta, "time_delta_seconds" to evidence.timeDeltaSeconds,
        "same_currency" to evidence.sameCurrency, "counterparty_similarity" to evidence.counterpartySimilarity,
        "source_pair" to evidence.sourcePair.toList(), "rule_id" to evidence.ruleId,
        "candidate_count" to evidence.candidateCount, "signals" to evidence.signals,
        "open_leg" to evidence.openLeg, "anchor_role" to evidence.anchorRole,
        "candidate_fact_ids" to evidence.candidateFactIds, "extras" to evidence.extras)
    private fun proposalJson(proposals: List<RelationProposal>): List<Map<String, Any?>> = proposals
        .sortedWith(Comparator { left, right -> cashRelationStringOrder.compare(
            CashRelationSerialization.proposalKey(left), CashRelationSerialization.proposalKey(right)) })
        .map { proposal ->
            linkedMapOf<String, Any?>("key" to CashRelationSerialization.proposalKey(proposal),
                "body" to linkedMapOf<String, Any?>("kind" to proposal.kind,
                    "primary_fact_id" to proposal.primaryFactId, "secondary_fact_id" to proposal.secondaryFactId,
                    "primary_fact_type" to proposal.primaryFactType,
                    "secondary_fact_type" to proposal.secondaryFactType, "subtype" to proposal.subtype,
                    "status" to proposal.status, "rule_id" to proposal.ruleId,
                    "confidence" to proposal.confidence, "evidence" to evidenceJson(proposal.evidence),
                    "created_by" to proposal.createdBy, "anchor_fact_id" to proposal.effectiveAnchorFactId,
                    "open_leg" to proposal.isOpenLeg))
        }

    @Test fun relationPlanningMatchesPython() {
        val expense = fact(id = "e1", amount = "-100.00", recordId = "E1")
        val refund = fact(id = "r1", amount = "30.00", recordId = "R1", recordType = "refund",
            occurred = "2026-10-02T10:00:00+00:00")
        val componentA = fact(id = "c1", amount = "-20.00", recordId = "C1").copy(parentId = "e1", componentOrdinal = 1)
        val componentB = fact(id = "c2", amount = "-5.00", recordId = "C2").copy(parentId = "e1", componentOrdinal = 2)
        val context = CashRelationPlanning.buildValidationContext(
            listOf(expense, refund, componentA, componentB))
        val contextActual = linkedMapOf<String, Any?>(
            "facts" to context.projectionFacts.map { linkedMapOf("id" to it.id, "amount" to amountText(it.amount),
                "currency" to it.currency, "record_id" to it.recordId, "counterparty" to it.counterparty,
                "note" to it.note, "source_type" to it.sourceType, "cash_granularity" to it.cashGranularity) },
            "component_to_parent" to context.componentToParent.map { listOf(it.first, it.second) },
            "active_ids" to context.activeFacts.map { it.id })
        assertEquals(golden.getValue("context"), sourceJson(contextActual))

        val legal = RelationProposal(kind = "refund_offset", primaryFactId = "e1", secondaryFactId = "r1",
            status = "accepted", ruleId = "scan.refund",
            evidence = RelationEvidence(extras = linkedMapOf("refund_amount" to "30.00")), anchorFactId = "e1")
        val illegal = legal.copy(evidence = RelationEvidence(extras = linkedMapOf("refund_amount" to "130.00")))
        val openLeg = RelationProposal(kind = "transfer_pair", primaryFactId = "e1", secondaryFactId = null,
            status = "pending_review", ruleId = "scan.open", evidence = RelationEvidence(openLeg = true))
        val rows = listOf<Map<String, Any?>>(linkedMapOf("kind" to "refund_offset", "status" to "accepted",
            "primary_fact_id" to "e1", "secondary_fact_id" to "r1", "applied_amount" to "20.00",
            "rule_id" to "scan.refund", "created_by" to "system", "decided_by" to "user"))
        assertEquals(golden.getValue("reason"), sourceJson(listOf(
            CashRelationPlanning.validationReason(listOf(expense, refund), emptyList(), legal),
            CashRelationPlanning.validationReason(listOf(expense, refund), emptyList(), illegal),
            CashRelationPlanning.validationReason(listOf(expense, refund), emptyList(), openLeg),
            CashRelationPlanning.validationReason(listOf(expense, refund), rows, legal))))
        assertEquals(golden.getValue("filtered"), sourceJson(proposalJson(
            CashRelationPlanning.filterIllegalProposals(listOf(expense, refund), emptyList(),
                listOf(legal, illegal, openLeg)))))
        val blocked = CashRelationPlanning.refundBlockedIds(listOf(expense, refund, componentA), listOf(
            linkedMapOf("kind" to "refund_offset", "status" to "accepted", "primary_fact_id" to "e1",
                "secondary_fact_id" to "r1"),
            linkedMapOf("kind" to "refund_offset", "status" to "superseded", "primary_fact_id" to "c1",
                "secondary_fact_id" to "r1"),
            linkedMapOf("kind" to "payment_mirror", "status" to "accepted", "primary_fact_id" to "c2",
                "secondary_fact_id" to "c1")),
            mapOf("e1" to BigDecimal("80.00"), "c1" to BigDecimal("20.00"))).sorted()
        assertEquals(golden.getValue("blocked"), sourceJson(blocked))
        assertEquals(golden.getValue("context_digest").jsonPrimitive.content,
            CashRelationPlanning.relationContextDigest(listOf(expense, refund), rows, listOf(legal)))
        assertEquals(golden.getValue("external_digest").jsonPrimitive.content,
            CashRelationPlanning.externalContextDigest(listOf(expense, refund), rows,
                mapOf("1234" to listOf("a", "b")), mapOf("6222" to listOf("a"))))

        val p1 = fact(id = "p1", amount = "-100.00", recordId = "P1")
        val b1 = fact(id = "b1", amount = "-100.00", recordId = "B1", source = "", occurred =
            "2026-10-01T10:00:05+00:00").copy(billSource = "icbc_debit")
        val wechatExpense = fact(id = "we1", amount = "-30.00", recordId = "WE1", source = "wechat")
        val wechatRefund = fact(id = "wr1", amount = "30.00", recordId = "WR1", recordType = "refund",
            source = "wechat", occurred = "2026-10-03T10:00:00+00:00")
        val transferOut = fact(id = "to1", amount = "-50.00", recordId = "TO1", recordType = "transfer_out",
            source = "", occurred = "2026-10-01T12:00:00+00:00", accountId = "c").copy(billSource = "ccb_debit")
        val transferIn = fact(id = "ti1", amount = "50.00", recordId = "TI1", recordType = "transfer_in",
            source = "", occurred = "2026-10-01T12:00:05+00:00", accountId = "d").copy(billSource = "icbc_debit")
        val planFacts = listOf(p1, b1, wechatExpense, wechatRefund, transferOut, transferIn)
        val plan = CashRelationPlanning.planRelationProposals(planFacts, workspaceId = "w")
        val seeded = CashRelationPlanning.planRelationProposals(planFacts, seedIds = listOf("p1", "we1"),
            workspaceId = "w")
        val strict = CashRelationPlanning.planRelationProposals(planFacts, requireUniqueMirrors = true,
            workspaceId = "w")
        val planActual = linkedMapOf<String, Any?>(
            "facts" to plan.facts.map { it.id },
            "proposals" to proposalJson(plan.proposals),
            "context_digest" to plan.contextDigest,
            "seeded_keys" to seeded.proposals.map { CashRelationSerialization.proposalKey(it) },
            "seeded_digest" to seeded.contextDigest,
            "strict_keys" to strict.proposals.map { CashRelationSerialization.proposalKey(it) },
            "strict_digest" to strict.contextDigest)
        assertEquals(golden.getValue("plan"), sourceJson(planActual))
    }
}
