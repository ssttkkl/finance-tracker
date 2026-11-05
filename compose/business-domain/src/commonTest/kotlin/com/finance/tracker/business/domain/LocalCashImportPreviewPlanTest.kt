package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class LocalCashImportPreviewPlanTest {
    private val now = Instant.parse("2026-10-01T09:00:00Z")

    @Test fun computedPlanCombinesPreparedOperationsPresentationAndSavedEntryDecisions() {
        val source = LocalCashRelationContextTest().snapshot().let {
            it.copy(facts = it.facts.copy(cashTransactions = it.facts.cashTransactions.filter { tx -> tx.id != "5" }))
        }
        @Suppress("UNCHECKED_CAST") val operations = decodeLocalCashValue(LocalCashImportPreviewSnapshotTest().operation) as List<Map<String, Any?>>
        val before = localCashImportDigest(listOf(operations))
        val prepared = LocalCashImportPreviewPreparation(source) {}.prepareOperations(operations)
        fun preview() = buildLocalCashImportPreviewSnapshot(source, prepared.context, prepared.operations, emptyList()) { now }
        val files = listOf(ParsedImportFile("file", "a".repeat(64), "parsed", filename = "statement.csv"))
        val first = completeLocalCashImportPreviewPlan(source, prepared, preview(), emptyList(), files, emptyMap(), null)
        val reference = (first.getValue("entry_targets") as Map<*, *>).keys.single() as String
        val choice = mapOf("edit_ref" to reference, "patch" to mapOf("note" to "checked-plan"))
        val oldDigest = localCashImportDigest(listOf(first))
        val snapshot = preview()
        val result = completeLocalCashImportPreviewPlan(source, prepared, snapshot, emptyList(), files,
            mapOf("entry_choices" to listOf(choice)), first, mapOf("candidate-proof" to listOf("1")))
        val display = ((result.getValue("items") as List<*>).single() as Map<*, *>)["display"] as Map<*, *>
        assertEquals("checked-plan", display["note"])
        assertTrue(display["amount"] is CashAmount)
        assertTrue(display["occurred_at"] is Instant)
        assertEquals(true, result["can_commit"])
        assertEquals(snapshot.operations, result["operations"])
        assertEquals(snapshot.rows, result["row_decisions"])
        assertEquals(emptyList<Any?>(), result["relation_groups"])
        assertEquals(emptyList<Any?>(), result["relation_failures"])
        assertEquals(mapOf("candidate-proof" to listOf("1")), result["source_candidate_sets"])
        val dependencies = result.getValue("dependencies") as Map<*, *>
        assertEquals(cashRelationReadContextToken(snapshot.context), dependencies["token"])
        val seeds = (dependencies["discovery_seeds"] as List<*>).map { it.toString() }
        assertEquals(1, seeds.size)
        assertTrue(CashImportPreviewInteger.parse(seeds.single()) > CashImportPreviewInteger.parse("18446744073709551616"))
        assertEquals(oldDigest, localCashImportDigest(listOf(first)))
        assertEquals(before, localCashImportDigest(listOf(operations)))
        assertEquals("confirmed-note", snapshot.facts.getValue(snapshot.parentOps.keys.single()).note)
    }

    @Test fun realRefundProposalIsGroupedChosenAndAppliedBeforeThePlanIsPresented() {
        val base = LocalCashRelationContextTest().snapshot()
        val source = base.copy(facts = base.facts.copy(transactionRelations = emptyList(),
            cashTransactions = base.facts.cashTransactions.filter { it.id != "5" }))
        @Suppress("UNCHECKED_CAST") val operations = decodeLocalCashValue(LocalCashImportPreviewSnapshotTest().operation) as List<MutableMap<String, Any?>>
        operations.single()["stable_source_key"] = listOf("ccb_debit", "account", "owner", "provider", "record-new")
        @Suppress("UNCHECKED_CAST") val values = operations.single().getValue("values") as MutableMap<String, Any?>
        values["amount"] = CashAmount.parse("5.00")
        values["record_type"] = "refund"
        for (item in values.getValue("components") as List<*>) {
            @Suppress("UNCHECKED_CAST") val part = item as MutableMap<String, Any?>
            part["amount"] = CashAmount.parse("5.00")
        }
        val prepared = LocalCashImportPreviewPreparation(source) {}.prepareOperations(operations, additionalSeedIds = setOf(1L))
        fun preview() = buildLocalCashImportPreviewSnapshot(source, prepared.context, prepared.operations, emptyList()) { now }
        val firstPreview = preview()
        val parent = firstPreview.parentOps.keys.single()
        val component = firstPreview.components.values.single { it.transactionId == parent }.componentId as String
        val proposal = RelationProposal("refund_offset", "11", component,
            evidence = RelationEvidence(extras = mapOf("refund_amount" to "5.00")))
        val first = completeLocalCashImportPreviewPlan(source, prepared, firstPreview, listOf(proposal), emptyList(), emptyMap(), null)
        val group = (first.getValue("relation_groups") as List<*>).single() as Map<*, *>
        val candidate = (group["candidates"] as List<*>).single() as Map<*, *>
        val choice = mapOf("suggestion_id" to group["suggestion_id"], "candidate_id" to candidate["candidate_id"],
            "action" to "accept", "common_fields" to mapOf("note" to "confirmed-refund"))
        val original = localCashImportDigest(listOf(first))
        val selectedPreview = preview()
        val selected = completeLocalCashImportPreviewPlan(source, prepared, selectedPreview, listOf(proposal), emptyList(),
            mapOf("relation_choices" to listOf(choice)), first)
        assertEquals(emptyList<Any?>(), selected["invalidated_choices"])
        assertEquals(emptyList<Any?>(), selected["relation_failures"])
        val selectedGroup = (selected.getValue("relation_groups") as List<*>).single() as Map<*, *>
        assertEquals("accept", selectedGroup["action"])
        val edge = (selectedGroup["edges"] as List<*>).single() as Map<*, *>
        assertEquals(CashAmount.parse("5.00").representation, (edge["applied_amount"] as CashAmount).representation)
        assertTrue(edge["id"] is CashImportPreviewInteger)
        val relation = selectedPreview.build().projections.flatMap { it.relations }.single()
        assertEquals(CashAmount.parse("5.00"), relation.appliedAmount)
        val projection = selectedPreview.build().projections.single { it.relations.isNotEmpty() }
        assertEquals("confirmed-refund", projection.primaryRecord.note)
        assertEquals("confirmed-note", selectedPreview.facts.getValue(parent).note)
        assertTrue((selected.getValue("items") as List<*>).any {
            ((it as Map<*, *>)["display"] as Map<*, *>)["note"] == "confirmed-refund"
        })
        assertEquals(original, localCashImportDigest(listOf(first)))
        assertEquals("公共备注", source.facts.cashEntries.single().note)
    }
}
