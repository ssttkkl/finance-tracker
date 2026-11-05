package com.finance.tracker.business.domain

import kotlin.test.*
import kotlin.time.Instant

class CashImportRelationDecisionRulesTest {
    private val rules = CashImportRelationDecisionRules<Long, Instant>({ it as? Long }, ::copyLocalCashValue, ::localCashImportDigest)
    private val time = Instant.parse("2026-10-02T10:00:00Z")
    private val values = CashProjectionValues<Long, Instant>({ it > 0 }, { true }, { it })
    private val projections = CashProjectionRules(values, Instant::toString)
    private val graph = CashRelationGraphRules(values, projections, { it as? Long }, String::toLong)
    private val relationId = 9007199254740993L
    private fun edge() = CashRelationGraphEdge(relationId, "payment_mirror", "1", "2", "11", "22", "expense", "expense")
    private fun preview(): CashImportPreviewSnapshot<Long, Instant> {
        val facts = (1L..2L).associateTo(linkedMapOf()) { id -> id.toString() to CashProjectionFact.create(values, id, 10L,
            time, "-5.00", "CNY", "对方$id", null, "来源备注$id", if (id == 1L) "ccb_debit" else "alipay", "record$id",
            recordType = "consumption", recordSubtype = "not_applicable") }
        val context = CashRelationReadContext(setOf(1L, 2L), emptyList(), setOf(1L, 2L), facts.values.toList(), emptyList(),
            emptyList(), emptyList(), emptyList(), setOf(1L, 2L), emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptySet())
        val parts = facts.mapValuesTo(linkedMapOf()) { (id, f) ->
            CashRelationEndpoint(id + id, id, f.amount, "CNY", f.accountId.toString(), "consumption", "not_applicable") }
        return CashImportPreviewSnapshot(context,
            parts.mapValuesTo(linkedMapOf()) { (_, p) -> linkedMapOf("components" to listOf(mapOf("component_id" to p.componentId))) },
            facts, parts.values.associateByTo(linkedMapOf()) { it.componentId as String }, linkedMapOf(), linkedMapOf(), linkedMapOf(),
            facts.mapValuesTo(linkedMapOf()) { (id, f) -> mapOf("entry_id" to "entry$id", "values" to mapOf("note" to f.note),
                "manual_fields" to emptyList<String>()) }, linkedSetOf(), linkedSetOf(), mutableListOf(), mutableListOf(),
            values, { it as? Long }, graph::build, { facts, relations -> projections.build(facts, relations) })
    }
    private fun group(origin: String = "new", action: String = "accept"): MutableMap<String, Any?> = linkedMapOf(
        "suggestion_id" to "suggestion", "origin" to origin, "changed" to true, "action" to action,
        "edges" to listOf(cashImportEdgeMap(edge())), "parent_ids" to listOf("1", "2"), "common_fields" to emptyMap<String, Any?>())

    @Test fun acceptedGroupsBuildOneProjectionAndKeepExactRelationIds() {
        val preview = preview()
        val groups = mutableListOf(group().apply { this["common_fields"] = mapOf("note" to "共同备注") })
        assertEquals(emptyList(), rules.applyGroups(preview, groups))
        val result = preview.build().projections.single()
        assertEquals(setOf(1L, 2L), result.memberIds.toSet())
        assertEquals(relationId, result.relations.single().id)
        assertEquals("共同备注", result.primaryRecord.note)
        assertTrue(groups.single()["commit_group_id"].toString().startsWith("cg_"))
        assertEquals("confirmed", preview.edges.getValue(relationId).state)
    }

    @Test fun conflictingManualFieldsDeferTheWholeDecision() {
        val preview = preview()
        for (id in listOf("1", "2")) preview.entryPublic[id] = preview.entryPublic.getValue(id) +
            mapOf("manual_fields" to listOf("note"), "values" to mapOf("note" to "人工备注$id"))
        val groups = mutableListOf(group())
        rules.applyGroups(preview, groups)
        assertEquals("defer", groups.single()["action"])
        assertEquals("common_field_conflict", groups.single()["code"])
        assertEquals("draft", preview.edges.getValue(relationId).state)
        assertEquals(2, preview.build().projections.size)
    }

    @Test fun invalidAutomaticGroupsRollbackWhileExplicitDecisionsRejectAndProgramErrorsPropagate() {
        fun broken() = preview().also { it.components["22"] = it.components.getValue("22").copy(amount = CashDecimalLiteral("-6.00")) }
        val automatic = broken()
        val groups = mutableListOf(group())
        rules.applyGroups(automatic, groups)
        assertTrue(groups.isEmpty())
        assertTrue(automatic.edges.isEmpty())
        val explicit = broken()
        assertEquals("relation_endpoint_incompatible",
            assertFailsWith<CashPolicyError> { rules.applyGroups(explicit, mutableListOf(group("manual"))) }.code)
        assertTrue(explicit.edges.isEmpty())
        val failure = IllegalStateException("graph program error")
        val program = preview().copy(graphBuilder = { _, _, _, _ -> throw failure })
        assertSame(failure, assertFailsWith<IllegalStateException> { rules.applyGroups(program, mutableListOf(group())) })
    }

    @Test fun removingCurrentRelationsRestoresStandaloneValuesOnlyWhenSelected() {
        val preview = preview()
        preview.edges[relationId] = edge()
        val groups = mutableListOf(group("current", "remove"))
        var loads = 0
        rules.restoreSplitValues(preview, groups, {
            loads++
            mapOf(1L to mapOf("note" to "独立备注1"), 2L to mapOf("note" to "独立备注2"))
        }, { it }, { ids -> assertEquals(setOf(1L, 2L), ids); setOf(1L, 2L) })
        assertEquals(1, loads)
        assertEquals(false, groups.single()["inherit_common_fields"])
        assertEquals("独立备注1", preview.facts.getValue("1").note)
        assertEquals("独立备注2", preview.facts.getValue("2").note)
        rules.applyGroups(preview, groups)
        assertEquals(false, preview.edges.getValue(relationId).active)
        assertEquals(2, preview.build().projections.size)
        rules.restoreSplitValues<Map<String, Any?>>(preview, listOf(group("current", "remove").apply { this["inherit_common_fields"] = true }),
            { error("no selected groups must not load members") }, { it },
            { error("no selected groups must not load closure") })
    }
}
