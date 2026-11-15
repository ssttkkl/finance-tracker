package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class LocalCashImportRelationWritesTest {
    private val empty = JsonObject(emptyMap())
    private val array = JsonArray(emptyList())
    private val time = "2026-10-03T09:00:00Z"
    private fun source(kind: String, alternatives: Boolean): LedgerSnapshotDto {
        val base = LocalCashRelationContextTest().snapshot()
        return base.copy(facts = base.facts.copy(accounts = listOf(LedgerAccountFactDto("10", "Cash", "cash", true,
            JsonArray(listOf(JsonPrimitive("CNY"))), "9007199254740993")),
            cashTransactions = base.facts.cashTransactions.filter { it.id == "1" || alternatives && it.id == "2" }.map {
                val row = if (it.id == "2") it.copy(rowRevision = "9223372036854775807") else it
                if (kind == "transfer_pair") row.copy(recordType = "transfer_out", recordSubtype = "ordinary_transfer") else row
            },
            cashTransactionComponents = base.facts.cashTransactionComponents.filter { it.cashTransactionId == "1" || alternatives && it.cashTransactionId == "2" },
            transactionRelations = emptyList(), cashTransactionIssues = emptyList()))
    }
    private fun files(kind: String) = listOf(ParsedImportFile("refund", "a".repeat(64), "parsed", ParsedCashDocument("alipay", "csv", "p",
        listOf(ParsedCashRow(1, mapOf("amount" to when (kind) { "refund_offset" -> "2.50"; "transfer_pair" -> "5.00"; else -> "-5.00" }, "currency" to "CNY", "occurred_at" to time,
            "record_type" to when (kind) { "refund_offset" -> "refund"; "transfer_pair" -> "transfer_in"; else -> "consumption" },
            "record_subtype" to if (kind == "transfer_pair") "ordinary_transfer" else "not_applicable", "note" to "Refund S", "source_payload" to emptyMap<String, Any?>()),
            paymentMethod = "支付宝余额", providerId = "REFUND-1")), "owner", "Account",
        CashSourceCoverage(null, null, false, "", emptyMap(), "v"), 1, 1)))

    private fun calculate(action: String? = null, strong: Boolean = false, kind: String = "refund_offset", alternatives: Boolean = false,
        openLeg: Boolean = false, multipleRows: Boolean = false): Triple<LedgerSnapshotDto, JsonObject, CashWriteSetDto> {
        val source = source(kind, alternatives); val files = files(kind).map { file -> if (!multipleRows) file else file.copy(
            document = file.document!!.let { document -> document.copy(rows = document.rows + document.rows.single().copy(position = 2, providerId = "REFUND-2"),
                observedCount = 2, declaredCount = 2) }) }
        val selections = JsonArray(scanLocalCashFunding(files).groups.map { buildJsonObject {
            put("source_account_ref", it.sourceAccountRef); put("account_id", "10"); put("expected_account_revision", 9007199254740993L)
        } })
        val operations = prepareLocalCashImportOperations(files, source, selections, wireValues = false)
        val prepared = LocalCashImportPreviewPreparation(source) {}.prepare(operations.operations, operations.source.rows.rows,
            additionalSeedIds = source.facts.cashTransactions.map { it.id.toLong() }.toSet())
        fun preview() = buildLocalCashImportPreviewSnapshot(source, prepared.context, prepared.operations, operations.rows) { Instant.parse(time) }
        val firstPreview = preview()
        val proposals = firstPreview.parentOps.keys.map { parent ->
            val part = firstPreview.components.values.single { it.transactionId == parent }.componentId as String
            RelationProposal(kind, if (openLeg) part else "11", if (openLeg) null else part,
            anchorFactId = if (alternatives || openLeg) part else "", status = if (strong) "accepted" else "pending_review",
            confidence = if (strong) "strong" else "weak", ruleId = "discovery-rule-is-not-stored-by-the-original-writer",
            evidence = RelationEvidence(candidateFactIds = if (alternatives) listOf("11", "22") else emptyList(),
                candidateCount = if (alternatives) 2 else 1, extras = mapOf("refund_amount" to "2.50")))
        }
        val first = completeLocalCashImportPreviewPlan(source, prepared, firstPreview, proposals, files,
            prepareLocalCashImportInput(selections, array, array), null, operations.candidateSets)
        val group = (first.getValue("relation_groups") as List<*>).single() as Map<String, Any?>
        val choices = if (action == null) array else buildJsonArray { add(buildJsonObject {
            put("suggestion_id", group.getValue("suggestion_id") as String); put("action", action)
            if (action == "accept") {
                val candidate = (group.getValue("candidates") as List<*>).firstOrNull() as? Map<String, Any?>
                candidate?.let { put("candidate_id", it.getValue("candidate_id") as String) }
                put("common_fields", buildJsonObject { put("note", "Chosen B") })
            }
        }) }
        val computed = if (action == null) first else completeLocalCashImportPreviewPlan(source, prepared, preview(), proposals, files,
            prepareLocalCashImportInput(selections, array, choices), first, operations.candidateSets)
        val plan = freezeLocalCashImportPreviewPlan(computed)
        val sources = buildLocalCashImportSourceWrites(source, files, operations.source, plan)
        return Triple(source, plan, buildLocalCashImportRelationWrites(source, plan, sources, choices))
    }

    @Test fun originalDefaultDeferKeepsTheNewCandidateAndExactOldAnchorRevision() {
        val (source, plan, writes) = calculate()
        val row = writes.relations.single(); val transaction = writes.transactions.single()
        assertEquals(CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto("1"), "9007199254740993"), row.primaryTransaction)
        assertEquals(ExistingCashWriteReferenceDto("11"), row.primaryComponent)
        assertEquals(row.primaryTransaction, row.anchorTransaction); assertEquals(row.primaryComponent, row.anchorComponent)
        assertEquals(transaction.target, row.secondaryTransaction); assertEquals(transaction.components.single().reference, row.secondaryComponent)
        assertEquals("draft", row.state); assertTrue(row.active); assertEquals("rule", row.decisionSource)
        assertNull(row.decisionGroupId); assertEquals("", row.ruleId); assertEquals("", row.decisionReason)
        val candidate = row.candidates.single()
        assertEquals(transaction.target, candidate.transaction); assertEquals(row.secondaryComponent, candidate.component)
        assertEquals("valid", candidate.validity)
        val group = plan.getValue("relation_groups").jsonArray.single().jsonObject
        val edge = group.getValue("edges").jsonArray.single().jsonObject
        assertEquals(NewCashWriteReferenceDto("relation:" + edge.getValue("id").jsonPrimitive.content), row.target.reference)
        assertNull(row.target.expectedRevision)
        assertEquals(group.getValue("evidence").jsonObject.getValue(edge.getValue("id").jsonPrimitive.content), row.evidence)
        assertEquals(listOf(row.target.reference), writes.relationGroups.single().relations)
        assertTrue(writes.relationGroups.single().commonFields.isEmpty())
        assertTrue(writes.entries.isEmpty()); assertTrue(writes.entryDependencies.isEmpty())
        prepareCashImportWriteSetRelationBatches(writes, source.facts)
    }

    @Test fun explicitAcceptKeepsBSeparateFromSourceValuesAndCandidates() {
        val (source, _, writes) = calculate("accept")
        val row = writes.relations.single()
        assertEquals("confirmed", row.state); assertEquals("manual", row.decisionSource); assertTrue(row.candidates.isEmpty())
        assertEquals(CashAmount.parse("2.50"), CashAmount.parse(row.appliedAmount))
        assertEquals(buildJsonObject { put("note", "Chosen B") }, writes.relationGroups.single().commonFields)
        assertEquals("Refund S", writes.transactions.single().values.note); assertNull(writes.transactions.single().entryEdit)
        validateCashWriteSetConfirmedGraph(writes, source.facts)
        prepareCashImportWriteSetCommonChoices(writes, source.facts)
    }

    @Test fun explicitDeferAndIgnoreHaveTheirOriginalDecisionSourceAndNoB() {
        for ((action, state) in listOf("defer" to "draft", "ignore" to "ignored")) {
            val (source, _, writes) = calculate(action)
            val row = writes.relations.single()
            assertEquals(state, row.state); assertEquals("manual", row.decisionSource)
            assertEquals(if (action == "defer") 1 else 0, row.candidates.size)
            assertTrue(writes.relationGroups.single().commonFields.isEmpty())
            prepareCashImportWriteSetRelationBatches(writes, source.facts)
        }
    }

    @Test fun deferredAndIgnoredNonRefundsRetainTheOriginalZeroAppliedAmountDefault() {
        for (kind in listOf("payment_mirror", "transfer_pair")) for (action in listOf(null, "ignore")) {
            val (source, _, writes) = calculate(action = action, kind = kind)
            assertEquals(if (action == null) "draft" else "ignored", writes.relations.single().state)
            assertEquals("0", writes.relations.single().appliedAmount)
            assertEquals(if (action == null) 1 else 0, writes.relations.single().candidates.size)
            prepareCashImportWriteSetRelationBatches(writes, source.facts)
        }
    }

    @Test fun existingAlternativesKeepExactObservedRevisionsAndCandidateEvidence() {
        val (source, plan, writes) = calculate(alternatives = true)
        val row = writes.relations.single()
        assertEquals(writes.transactions.single().target, row.anchorTransaction)
        assertEquals(listOf("1", "2"), row.candidates.map { (it.transaction.reference as ExistingCashWriteReferenceDto).id })
        assertEquals(listOf("9007199254740993", "9223372036854775807"), row.candidates.map { it.transaction.expectedRevision })
        assertEquals(listOf(ExistingCashWriteReferenceDto("11"), ExistingCashWriteReferenceDto("22")), row.candidates.map { it.component })
        assertTrue(row.candidates.all { it.validity == "valid" })
        val group = plan.getValue("relation_groups").jsonArray.single().jsonObject
        assertEquals(2, group.getValue("candidates").jsonArray.size)
        assertEquals(group.getValue("candidates").jsonArray.last().jsonObject.getValue("evidence").jsonObject.values.single(), row.candidates.last().evidence)
        prepareCashImportWriteSetRelationBatches(writes, source.facts)
    }

    @Test fun openRefundLegKeepsTheNewRefundAnchorAndTheOriginalExpenseCandidates() {
        val (source, _, writes) = calculate(alternatives = true, openLeg = true)
        val row = writes.relations.single()
        assertEquals(writes.transactions.single().target, row.primaryTransaction); assertNull(row.secondaryTransaction)
        assertEquals(row.primaryTransaction, row.anchorTransaction); assertEquals("draft", row.state)
        assertNull(row.primaryRole); assertNull(row.secondaryRole)
        assertEquals(2, row.candidates.size)
        prepareCashImportWriteSetRelationBatches(writes, source.facts)
    }

    @Test fun twoRefundsKeepTheirCompleteJointGroupAndOneCommonDecision() {
        val (source, plan, writes) = calculate("accept", multipleRows = true)
        assertEquals(2, writes.transactions.size); assertEquals(2, writes.relations.size)
        val choice = writes.relationGroups.single()
        assertEquals(writes.relations.map { it.target.reference }, choice.relations)
        assertEquals(buildJsonObject { put("note", "Chosen B") }, choice.commonFields)
        val edges = plan.getValue("relation_groups").jsonArray.single().jsonObject.getValue("edges").jsonArray
        assertEquals(edges.map { it.jsonObject.getValue("id").jsonPrimitive.content },
            writes.relations.map { (it.target.reference as NewCashWriteReferenceDto).ref.removePrefix("relation:") })
        assertTrue(writes.relations.all { it.state == "confirmed" && it.candidates.isEmpty() && it.decisionGroupId == null })
        validateCashWriteSetConfirmedGraph(writes, source.facts)
        assertEquals(listOf(listOf(choice)), prepareCashImportWriteSetRelationBatches(writes, source.facts))
    }

    @Test fun automaticStrongConfirmationIsStoredAsRuleWithoutCandidateRows() {
        val (source, _, writes) = calculate(strong = true)
        val row = writes.relations.single()
        assertEquals("confirmed", row.state); assertEquals("rule", row.decisionSource); assertTrue(row.candidates.isEmpty())
        validateCashWriteSetConfirmedGraph(writes, source.facts)
    }
}
