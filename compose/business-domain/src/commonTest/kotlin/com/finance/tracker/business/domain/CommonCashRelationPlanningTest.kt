package com.finance.tracker.business.domain

import kotlin.test.*
import kotlinx.serialization.json.*

class CommonCashRelationPlanningTest {
    private val planning=RelationPlanningTestAdapters.rules
    private val validation=RelationValidationTestAdapters.rules
    private val fingerprints=CashRelationPlanFingerprints(RelationSerializationTestAdapters.rules,
        {value:CashAmount ->LedgerArithmeticValue.fromCashAmount(value).toPlainString()},
        {value ->sourceJson(value,{null},::pythonFloatText).toString()})
    private val serialization=RelationSerializationTestAdapters.rules
    private fun encode(value:Any?)=sourceJson(value,{item ->if(item is CashAmount) LedgerArithmeticValue.fromCashAmount(item).toPlainString() else null},::pythonFloatText)
    private val golden: JsonObject by lazy {
        Json.parseToJsonElement(CashRelationPlanningOriginalVectors.json).jsonObject.getValue("cases").jsonObject
    }
    private fun fact(id: String = "1", amount: String = "-10.00", recordId: String = "ALI-1",
        occurred: String = "2026-10-01T10:00:00+00:00", recordType: String = "consumption",
        source: String = "alipay", accountId: String = "a") = RelationFact(id = id, amount = CashAmount.parse(amount),
        currency = "CNY", accountId = accountId, accountName = "账户", occurredAt = occurred, counterparty = "商户",
        recordType = recordType, recordSubtype = defaultCashRecordSubtype(recordType), billSource = source,
        source = source, recordId = recordId)
    private fun amountText(value: CashAmount?): String =
        if (value == null) "" else LedgerArithmeticValue.fromCanonical(value.numericText()).toPlainString()
    private fun evidenceJson(evidence: RelationEvidence): Map<String, Any?> = linkedMapOf(
        "amount_delta" to evidence.amountDelta, "time_delta_seconds" to evidence.timeDeltaSeconds?.let(::SourceJsonInteger),
        "same_currency" to evidence.sameCurrency, "counterparty_similarity" to evidence.counterpartySimilarity,
        "source_pair" to evidence.sourcePair.toList(), "rule_id" to evidence.ruleId,
        "candidate_count" to SourceJsonInteger(evidence.candidateCount), "signals" to evidence.signals,
        "open_leg" to evidence.openLeg, "anchor_role" to evidence.anchorRole,
        "candidate_fact_ids" to evidence.candidateFactIds, "extras" to evidence.extras)
    private fun proposalJson(proposals: List<RelationProposal>): List<Map<String, Any?>> = proposals
        .sortedWith(Comparator { left, right ->
            val key = cashRelationStringOrder.compare(
                serialization.proposalKey(left), serialization.proposalKey(right))
            if (key != 0) key else {
                val a = left.evidence.extras["refund_amount"]?.toString()?.let(CashAmount::parseOrNull)
                val b = right.evidence.extras["refund_amount"]?.toString()?.let(CashAmount::parseOrNull)
                when { a == null && b == null -> 0; a == null -> -1; b == null -> 1; else -> a.compareTo(b) }
            }
        })
        .map { proposal ->
            linkedMapOf<String, Any?>("key" to serialization.proposalKey(proposal),
                "body" to linkedMapOf<String, Any?>("kind" to proposal.kind,
                    "primary_fact_id" to proposal.primaryFactId, "secondary_fact_id" to proposal.secondaryFactId,
                    "primary_fact_type" to proposal.primaryFactType,
                    "secondary_fact_type" to proposal.secondaryFactType,
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
        val context = validation.buildValidationContext(
            listOf(expense, refund, componentA, componentB))
        val contextActual = linkedMapOf<String, Any?>(
            "facts" to context.projectionFacts.map { linkedMapOf("id" to it.id, "amount" to amountText(it.amount),
                "currency" to it.currency, "record_id" to it.recordId, "counterparty" to it.counterparty,
                "note" to it.note, "source_type" to it.sourceType, "cash_granularity" to it.cashGranularity) },
            "component_to_parent" to context.componentToParent.map { listOf(it.first, it.second) },
            "active_ids" to context.activeFacts.map { it.id })
        assertEquals(golden.getValue("context"), encode(contextActual))

        val legal = RelationProposal(kind = "refund_offset", primaryFactId = "e1", secondaryFactId = "r1",
            status = "accepted", ruleId = "scan.refund",
            evidence = RelationEvidence(extras = linkedMapOf("refund_amount" to "30.00")), anchorFactId = "e1")
        val illegal = legal.copy(evidence = RelationEvidence(extras = linkedMapOf("refund_amount" to "130.00")))
        val openLeg = RelationProposal(kind = "transfer_pair", primaryFactId = "e1", secondaryFactId = null,
            status = "pending_review", ruleId = "scan.open", evidence = RelationEvidence(openLeg = true))
        val rows = listOf<Map<String, Any?>>(linkedMapOf("kind" to "refund_offset", "status" to "accepted",
            "primary_fact_id" to "e1", "secondary_fact_id" to "r1", "applied_amount" to "20.00",
            "rule_id" to "scan.refund", "created_by" to "system", "decided_by" to "user"))
        assertEquals(golden.getValue("reason"), encode(listOf(
            validation.validationReason(listOf(expense, refund), emptyList(), legal),
            validation.validationReason(listOf(expense, refund), emptyList(), illegal),
            validation.validationReason(listOf(expense, refund), emptyList(), openLeg),
            validation.validationReason(listOf(expense, refund), rows, legal))))
        assertEquals(golden.getValue("filtered"), encode(proposalJson(
            validation.filterIllegalProposals(listOf(expense, refund), emptyList(),
                listOf(legal, illegal, openLeg)))))
        val blocked = planning.refundBlockedIds(listOf(expense, refund, componentA), listOf(
            linkedMapOf("kind" to "refund_offset", "status" to "accepted", "primary_fact_id" to "e1",
                "secondary_fact_id" to "r1"),
            linkedMapOf("kind" to "refund_offset", "status" to "superseded", "primary_fact_id" to "c1",
                "secondary_fact_id" to "r1"),
            linkedMapOf("kind" to "payment_mirror", "status" to "accepted", "primary_fact_id" to "c2",
                "secondary_fact_id" to "c1")),
            mapOf("e1" to CashAmount.parse("80.00"), "c1" to CashAmount.parse("20.00"))).sorted()
        assertEquals(golden.getValue("blocked"), encode(blocked))
        assertEquals(golden.getValue("context_digest").jsonPrimitive.content,
            fingerprints.relationContextDigest(listOf(expense, refund), rows, listOf(legal)))
        assertEquals(golden.getValue("external_digest").jsonPrimitive.content,
            fingerprints.externalContextDigest(listOf(expense, refund), rows,
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
        val plan = planning.planRelationProposals(planFacts, workspaceId = "w")
        val seeded = planning.planRelationProposals(planFacts, seedIds = listOf("p1", "we1"),
            workspaceId = "w")
        val strict = planning.planRelationProposals(planFacts, requireUniqueMirrors = true,
            workspaceId = "w")
        val planActual = linkedMapOf<String, Any?>(
            "facts" to plan.facts.map { it.id },
            "proposals" to proposalJson(plan.proposals),
            "context_digest" to plan.contextDigest,
            "seeded_keys" to seeded.proposals.map { serialization.proposalKey(it) },
            "seeded_digest" to seeded.contextDigest,
            "strict_keys" to strict.proposals.map { serialization.proposalKey(it) },
            "strict_digest" to strict.contextDigest)
        assertEquals(golden.getValue("plan"), encode(planActual))
    }
    @Test fun planIsStableAcrossInputOrderAndDoesNotMutateCallerRemaining() {
        val platform=fact(id="p1",amount="-100.00",recordId="P1")
        val bank=fact(id="b1",amount="-100.00",recordId="B1",source="",occurred="2026-10-01T10:00:05+00:00").copy(billSource="icbc_debit")
        val remaining=linkedMapOf("p1" to CashAmount.parse("70.00"),"b1" to CashAmount.parse("70.00"))
        val before=remaining.toMap()
        val first=planning.planRelationProposals(listOf(platform,bank),remainingByExpense=remaining)
        val second=planning.planRelationProposals(listOf(bank,platform),remainingByExpense=remaining)
        assertEquals(first,second)
        assertEquals(before,remaining)
        assertEquals(listOf("payment_mirror"),first.proposals.map {it.kind})
        val deleted=fact(id="deleted").copy(deleted=true,occurredAt="invalid")
        assertEquals(first,planning.planRelationProposals(listOf(platform,deleted,bank),remainingByExpense=remaining))
    }
    @Test fun metadataEnrichmentKeepsExplicitBlankStatusAndDoesNotMutateSourceRows() {
        val payload=mapOf("status" to "done","txn_id" to "platform-id","merchant_order_id" to "merchant","type" to "refund")
        val source=linkedMapOf<String,Any?>("id" to "one","record_id" to "source-id","status" to "","platform_status" to null,
            "raw_payload" to emptyMap<String,Any?>(),"source_payload" to payload,
            "relation_metadata" to mapOf("offset_group" to "group","offset_strength" to "strong"),"offset_group" to "chosen")
        val before=source.toMap()
        val row=planning.enrichPlatformRefundRows(listOf(source)).single()
        assertEquals(before,source)
        assertEquals("chosen",row["offset_group"])
        assertEquals("strong",row["offset_strength"])
        assertEquals("",row["status"])
        assertEquals("done",row["platform_status"])
        assertEquals("platform-id",row["txn_id"])
        assertEquals("merchant",row["merchant_order_id"])
        assertEquals("refund",row["txn_type"])
        assertEquals(payload,row["raw_payload"])
    }

}
