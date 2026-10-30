package com.finance.tracker.backend

import java.math.BigDecimal
import kotlin.test.*
import kotlinx.serialization.json.*

class CashRelationPlanCacheTest {
    private val golden: JsonObject by lazy {
        Json.parseToJsonElement(javaClass.getResourceAsStream("/python-cash-relation-plan-cache.json")!!
            .bufferedReader().use { it.readText() }).jsonObject.getValue("cases").jsonObject
    }
    private fun fact(id: String = "p1", amount: String = "-100.00", recordId: String = "R1",
        source: String = "alipay", recordType: String = "consumption",
        occurred: String = "2026-10-01T10:00:00+00:00") = RelationFact(id = id, amount = BigDecimal(amount),
        currency = "CNY", accountId = "a", accountName = "账户", occurredAt = occurred, counterparty = "商户",
        recordType = recordType, recordSubtype = defaultCashRecordSubtype(recordType), billSource = source,
        source = source, recordId = recordId)
    private fun proposal() = RelationProposal(kind = "refund_offset", primaryFactId = "p1", secondaryFactId = "q1",
        status = "accepted", ruleId = "scan.refund", anchorFactId = "p1",
        evidence = RelationEvidence(amountDelta = "0", timeDeltaSeconds = 5, signals = listOf("refund"),
            candidateFactIds = listOf("q1"), extras = linkedMapOf("refund_amount" to "30.00")))
    private fun evidenceJson(evidence: RelationEvidence): Map<String, Any?> = linkedMapOf(
        "amount_delta" to evidence.amountDelta, "time_delta_seconds" to evidence.timeDeltaSeconds,
        "same_currency" to evidence.sameCurrency, "counterparty_similarity" to evidence.counterpartySimilarity,
        "source_pair" to evidence.sourcePair.toList(), "rule_id" to evidence.ruleId,
        "candidate_count" to evidence.candidateCount, "signals" to evidence.signals,
        "open_leg" to evidence.openLeg, "anchor_role" to evidence.anchorRole,
        "candidate_fact_ids" to evidence.candidateFactIds, "extras" to evidence.extras)
    private fun proposalJson(proposals: List<RelationProposal>, facts: List<RelationFact>): List<Map<String, Any?>> =
        proposals.map { proposal ->
            linkedMapOf<String, Any?>("key" to CashRelationSerialization.proposalKey(proposal, facts),
                "body" to linkedMapOf<String, Any?>("kind" to proposal.kind,
                    "primary_fact_id" to proposal.primaryFactId, "secondary_fact_id" to proposal.secondaryFactId,
                    "primary_fact_type" to proposal.primaryFactType,
                    "secondary_fact_type" to proposal.secondaryFactType, "subtype" to proposal.subtype,
                    "status" to proposal.status, "rule_id" to proposal.ruleId,
                    "confidence" to proposal.confidence, "evidence" to evidenceJson(proposal.evidence),
                    "created_by" to proposal.createdBy, "anchor_fact_id" to proposal.effectiveAnchorFactId,
                    "open_leg" to proposal.isOpenLeg))
        }

    @Test fun cachedRelationPlanMatchesPython() {
        val refund = fact(id = "q1", amount = "30.00", recordId = "R2", source = "wechat",
            recordType = "refund", occurred = "2026-10-02T10:00:00+00:00")
        val facts = listOf(fact(), refund)
        val openLeg = proposal().copy(kind = "transfer_pair", primaryFactId = "q1", secondaryFactId = null,
            status = "pending_review", ruleId = "scan.open", anchorFactId = "",
            evidence = RelationEvidence(openLeg = true, candidateFactIds = listOf("p1")))
        val pending = proposal().copy(status = "pending_review", confidence = "weak",
            evidence = RelationEvidence(amountDelta = "0.00", timeDeltaSeconds = null,
                candidateFactIds = listOf("q1", "p1"), extras = linkedMapOf("refund_amount" to "30.00")))
        val plan = RelationPlan(facts, listOf(proposal(), openLeg, pending), "d".repeat(64), "e".repeat(64))
        val cached = CashRelationSerialization.serialize(plan)
        assertEquals(golden.getValue("cached"), sourceJson(cached))

        val decoded = CashRelationPlanCache.decodeCachedProposals(facts, cached)
        assertEquals(golden.getValue("decoded"), sourceJson(proposalJson(decoded, facts)))
        assertEquals(golden.getValue("decoded_facts"), sourceJson(facts.map { it.id }))
        assertEquals(golden.getValue("by_reference"),
            sourceJson(CashRelationPlanCache.factsByCachedReference(facts).keys.sorted()))
        assertEquals(golden.getValue("resolve_ref"), sourceJson(listOf("p1", "preview:p1", "alipay:R1", "R2", "missing")
            .map { CashRelationPlanCache.resolveFactReference(it, facts) }))
        assertEquals(golden.getValue("seeds"), sourceJson(linkedMapOf(
            "expanded" to CashRelationPlanCache.resolveSeeds(facts, listOf("p1", "unknown", "q1")),
            "all" to CashRelationPlanCache.resolveSeeds(facts, null))))

        val aliases = listOf(
            mapOf("alias_type" to "card_tail", "alias_value" to "1234", "account_id" to "a"),
            mapOf("alias_type" to "card_tail", "alias_value" to "12-34", "account_id" to "b"),
            mapOf("alias_type" to "card_tail", "alias_value" to "12345", "account_id" to "c"),
            mapOf("alias_type" to "account_identifier", "alias_value" to "622202123456", "account_id" to "a"),
            mapOf("alias_type" to "account_identifier", "alias_value" to "1234", "account_id" to "d"))
        val (tails, identifiers) = CashRelationPlanCache.aliasIndexes(aliases)
        assertEquals(golden.getValue("aliases"), sourceJson(linkedMapOf(
            "card_tails" to tails.mapValues { it.value.sorted() },
            "account_identifiers" to identifiers.mapValues { it.value.sorted() })))

        val rows = listOf<Map<String, Any?>>(
            linkedMapOf("id" to "p1", "record_id" to "R1", "amount" to "-100.00", "currency" to "CNY",
                "record_type" to "consumption", "cash_granularity" to "aggregate",
                "components" to listOf(linkedMapOf("id" to "c1", "ordinal" to 1, "amount" to "-60.00",
                    "currency" to "CNY", "account_id" to "a"),
                    linkedMapOf("id" to "c2", "ordinal" to 2, "amount" to "-40.00", "currency" to "CNY",
                        "account_id" to "b"))),
            linkedMapOf("id" to "q1", "record_id" to "R2", "amount" to "30.00", "currency" to "CNY",
                "record_type" to "refund", "components" to listOf(linkedMapOf("id" to "c3", "ordinal" to 1,
                    "amount" to "30.00", "currency" to "CNY", "account_id" to "a"))),
            linkedMapOf("id" to "s1", "record_id" to "R3", "amount" to "-5.00", "currency" to "CNY",
                "record_type" to "consumption"))
        val expanded = CashRelationPlanCache.expandComponentRows(rows).map { item ->
            linkedMapOf<String, Any?>().apply {
                for (key in listOf("id", "parent_id", "component_ordinal", "amount", "account_id",
                    "cash_granularity", "currency", "record_id")) put(key, item[key])
            }
        }
        assertEquals(golden.getValue("expanded"), sourceJson(expanded))

        val relations = listOf<Map<String, Any?>>(
            linkedMapOf("id" to 1, "kind" to "refund_offset", "status" to "accepted", "primary_fact_id" to "p1",
                "secondary_fact_id" to "q1", "applied_amount" to "10.00", "created_by" to "system", "decided_by" to ""),
            linkedMapOf("id" to 2, "kind" to "refund_offset", "status" to "accepted", "primary_fact_id" to "s1",
                "secondary_fact_id" to "missing", "created_by" to "system", "decided_by" to ""),
            linkedMapOf("id" to 3, "kind" to "payment_mirror", "status" to "accepted", "primary_fact_id" to "p1",
                "secondary_fact_id" to "s1", "created_by" to "system", "decided_by" to ""))
        assertEquals(golden.getValue("remaining"), sourceJson(CashRelationPlanCache.refundRemaining(facts, relations)
            .mapValues { it.value.toPlainString() }))

        val decision = linkedMapOf<String, Any?>("proposal_key" to
            CashRelationSerialization.proposalKey(proposal(), facts),
            "primary_record_ref" to "alipay:R1", "secondary_record_ref" to "R2")
        val decisions = linkedMapOf<String, Any?>(
            "mirror" to (CashRelationPlanCache.decisionMirrorEndpoints(
                proposal().copy(kind = "payment_mirror"), decision, facts)?.toList()),
            "matches" to CashRelationPlanCache.decisionMatches(proposal(), decision, facts),
            "primary" to CashRelationPlanCache.decisionPrimaryMatches(proposal(), decision, facts),
            "wrong_key" to CashRelationPlanCache.decisionMatches(proposal(),
                decision + ("proposal_key" to "proposal:bad"), facts),
            "candidate" to CashRelationPlanCache.decisionMatches(proposal(),
                decision + ("secondary_record_ref" to "R1"), facts))
        assertEquals(golden.getValue("decisions"), sourceJson(decisions))

        val acceptedReason = runCatching { CashRelationPlanCache.validateCachedPlanLegality(facts,
            listOf(mapOf("kind" to "refund_offset", "status" to "accepted", "primary_fact_id" to "p1",
                "secondary_fact_id" to "q1", "applied_amount" to "10.00")),
            listOf(proposal())) }.exceptionOrNull()
        val pendingReason = runCatching { CashRelationPlanCache.validateCachedPlanLegality(facts, emptyList(),
            listOf(proposal().copy(status = "pending_review",
                evidence = RelationEvidence(extras = linkedMapOf("refund_amount" to "130.00"))))) }.exceptionOrNull()
        assertEquals(golden.getValue("legality"), sourceJson(listOf(
            acceptedReason?.message, pendingReason?.message)))
        assertEquals(golden.getValue("decode_error").jsonPrimitive.content,
            assertFailsWith<IllegalArgumentException> {
                CashRelationPlanCache.decodeCachedProposals(facts, cached + ("version" to 2))
            }.message)
        @Suppress("UNCHECKED_CAST")
        val broken = LinkedHashMap(cached)
        val proposals = (cached["proposals"] as List<Map<String, Any?>>).map { LinkedHashMap(it) }.toMutableList()
        proposals[0]["proposal_key"] = "proposal:bad"
        broken["proposals"] = proposals
        assertEquals(golden.getValue("key_error").jsonPrimitive.content,
            assertFailsWith<IllegalArgumentException> {
                CashRelationPlanCache.decodeCachedProposals(facts, broken)
            }.message)
    }
}
