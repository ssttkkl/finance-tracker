package com.finance.tracker.business.domain

import kotlin.test.*
import kotlin.time.Instant

class CashImportPreviewOverlayTest {
    private val time = Instant.parse("2026-10-02T10:00:00Z")
    private val values = CashProjectionValues<Long, Instant>({ it > 0 }, { true }, { it })
    private fun fact(id: String, row: Map<String, Any?>, attention: Boolean) = CashProjectionFact.create(values,
        id.toLong(), row["account_id"].toString().toLong(), row["occurred_at"] as Instant, row["amount"].toString(),
        row["currency"] as String, row["counterparty"] as String, null, row["note"] as String,
        row["source_type"] as String, row["record_id"] as String, state = row["state"] as String,
        createdAt = row["created_at"] as Instant, needsAttention = attention, recordType = row["record_type"] as String,
        recordSubtype = row["record_subtype"] as String, recordOrigin = row["record_origin"] as String)
    private fun row(id: String): MutableMap<String, Any?> = linkedMapOf("account_id" to "10", "occurred_at" to time,
        "amount" to "-5.00", "currency" to "CNY", "counterparty" to "对方", "note" to "备注", "source_type" to "ccb_debit",
        "record_id" to "record$id", "state" to "posted", "record_type" to "consumption", "record_subtype" to "not_applicable",
        "record_origin" to "import", "created_at" to time, "cash_granularity" to "atomic", "components" to listOf(
            linkedMapOf("component_id" to id + id, "account_id" to "10", "amount" to "-5.00", "currency" to "CNY", "ordinal" to 0L)))
    internal fun snapshot(): CashImportPreviewSnapshot<Long, Instant> {
        val rows = linkedMapOf("1" to row("1"), "2" to row("2"))
        val facts = rows.mapValuesTo(linkedMapOf()) { (id, row) -> fact(id, row, false) }
        val edge = CashRelationGraphEdge(100L, "payment_mirror", "1", "2", "11", "22", "expense", "expense")
        val context = CashRelationReadContext(setOf(1L, 2L), emptyList(), setOf(1L, 2L), facts.values.toList(), emptyList(),
            listOf(edge), emptyList(), emptyList(), setOf(1L, 2L), listOf(CashRelationComponentVersion(11L, 1L, 10L, "-5.00", "CNY", 0L)),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptySet())
        return CashImportPreviewSnapshot(context, rows, facts,
            rows.map { (id, _) -> id + id to CashRelationEndpoint(id + id, id, CashAmount.parse("-5.00"), "CNY", "10", "consumption", "not_applicable") }
                .toMap(linkedMapOf()), linkedMapOf(100L to edge), linkedMapOf(), linkedMapOf(), linkedMapOf(),
            linkedSetOf(), linkedSetOf(), mutableListOf(), mutableListOf(), values, { it as? Long },
            { _, _, _, _ -> CashProjectionBuild(emptyList()) }, { _, _ -> CashProjectionBuild(emptyList()) })
    }
    private fun operation(value: Map<String, Any?>, existing: Boolean = true) = linkedMapOf<String, Any?>(
        "transaction_id" to if (existing) "1" else null, "values" to value, "operation_ref" to "op", "occurrence_key" to "stable",
        "type_changed" to existing, "issues" to emptyList<Any>(), "retained_issue_ids" to emptyList<Any>())
    private fun rules() = CashImportPreviewOverlayRules(LocalCashRecordValues, { time },
        { kind: String, _: List<Any?> -> if (kind == "transaction") 999L else 9999L }, ::fact)

    private fun fundedSnapshot(): CashImportPreviewSnapshot<Long, Instant> {
        val preview = snapshot()
        preview.edges.clear()
        preview.values.getValue("1")["record_type"] = "investment_out"
        preview.components["11"] = preview.components.getValue("11").copy(recordType = "investment_out")
        preview.facts["1"] = CashProjectionFact.create(values, 1L, 10L, time, "-5.00", "CNY", "对方", null, "备注",
            "ccb_debit", "record1", fundingRelationId = 200L, createdAt = time, recordType = "investment_out",
            recordSubtype = "not_applicable", recordOrigin = "import")
        preview.funding[200L] = CashRelationFundingVersion(200L, 11L, 300L, "cash_to_investment")
        return preview.copy(context = preview.context.copy(facts = preview.facts.values.toList(), relations = emptyList(),
            fundingVersions = preview.funding.values.toList(), fundingComponentIds = setOf("11")))
    }

    @Test fun newOperationsKeepStableVirtualPartsAndDoNotMutateTheInputValue() {
        val preview = snapshot()
        val input = row("3").apply { this["record_origin"] = null; this["components"] = listOf(
            linkedMapOf("account_id" to "10", "amount" to "-5.00", "currency" to "CNY", "ordinal" to 0L)) }
        preview.operations += operation(input, false)
        rules().apply(preview)
        assertEquals("999", preview.operations.single()["preview_transaction_id"])
        assertTrue("9999" in preview.components)
        assertEquals("import", preview.facts.getValue("999").recordOrigin)
        assertNull(input["record_origin"])
        assertNull(((input["components"] as List<*>).single() as Map<*, *>)["component_id"])
        assertEquals(listOf(mapOf("ordinal" to SourceJsonInteger(0), "component_id" to "9999")), preview.operations.single()["preview_components"])
    }

    @Test fun typeChangesRemoveOnlyIncompatibleRelations() {
        val preview = snapshot()
        preview.operations += operation(row("1").apply { this["record_type"] = "other" })
        rules().apply(preview)
        assertEquals(false, preview.edges.getValue(100L).active)
        assertEquals(setOf(100L), preview.removedRelations)
        assertTrue(preview.removedFunding.isEmpty())
        assertEquals("other", preview.facts.getValue("1").recordType)
        assertEquals(time, preview.facts.getValue("1").createdAt)
    }

    @Test fun fundingRoleChangesRemoveTheFundingReference() {
        val preview = fundedSnapshot()
        preview.operations += operation(row("1"))
        rules().apply(preview)
        assertTrue(preview.funding.isEmpty())
        assertEquals(setOf(200L), preview.removedFunding)
        assertTrue(preview.removedRelations.isEmpty())
    }

    @Test fun graphBusinessFailureRestoresTheWholeOperationAndProgramErrorsDoNotBecomeSkips() {
        for (original in listOf(snapshot(), fundedSnapshot())) {
            val preview = original.copy(graphBuilder = { _, _, _, _ -> throw CashRelationFailure("relation_graph_invalid") })
            val before = listOf(preview.values.toMap(), preview.facts.toMap(), preview.components.toMap(), preview.edges.toMap(), preview.funding.toMap())
            preview.operations += operation(row("1").apply { this["record_type"] = "other" })
            preview.rows += linkedMapOf("operation_ref" to "op", "planned_action" to "update")
            rules().apply(preview)
            assertEquals(before, listOf(preview.values, preview.facts, preview.components, preview.edges, preview.funding))
            assertTrue(preview.operations.isEmpty())
            assertTrue(preview.removedRelations.isEmpty())
            assertTrue(preview.removedFunding.isEmpty())
            assertEquals("skip", preview.rows.single()["planned_action"])
            assertEquals("relation_endpoint_incompatible", preview.rows.single()["code"])
            assertNull(preview.rows.single()["operation_ref"])
        }
        val error = IllegalStateException("graph program error")
        val broken = snapshot().copy(graphBuilder = { _, _, _, _ -> throw error })
        broken.operations += operation(row("1"))
        assertSame(error, assertFailsWith<IllegalStateException> { rules().apply(broken) })
    }
}
