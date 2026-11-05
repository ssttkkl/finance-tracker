package com.finance.tracker.business.domain

import kotlin.test.*
import kotlin.time.Instant

class CashImportPreviewPreparationRulesTest {
    private val context = CashImportPreviewSnapshotTest().snapshot().context

    @Test fun sourceInputsKeepScopeAndProviderSemanticsWithoutMutatingOperations() {
        val sources = mapOf("platform" to SourceOccurrence("alipay", "owner", "a".repeat(64), "v1", "table", 0,
            emptyMap(), providerId = "ALI-1"), "bank" to SourceOccurrence("ccb_debit", null, "b".repeat(64), "v1", "table", 0, emptyMap()))
        val operations = sources.keys.map { ref -> mapOf<String, Any?>("row_refs" to listOf(ref), "values" to mapOf("record_id" to "saved-$ref"),
            "planned_action" to "create_posted", "source_updates" to listOf(mapOf("row_ref" to ref, "bind" to true))) }
        val events = mutableListOf<String>()
        val rules = CashImportPreviewPreparationRules<Long, Instant>({ events += "active" }, { events += "accounts"; emptyMap() },
            { ops, rows -> events += "candidates"; assertEquals(2, ops.size); assertSame(sources, rows); emptySet() },
            { seeds, groups -> events += "context"; assertTrue(seeds.isEmpty()); assertTrue(groups.isEmpty()); context })
        val result = rules.prepare(operations, sources)
        val platform = result.operations[0]
        val bank = result.operations[1]
        assertEquals("owner", platform["source_scope_key"])
        assertEquals("saved-platform", platform["matching_record_id"])
        assertEquals("file:${sources.getValue("bank").fileScopeKey}", bank["source_scope_key"])
        assertEquals("occurrence:${sources.getValue("bank").occurrenceKey}", bank["matching_record_id"])
        assertEquals("alipay", (platform["stable_source_key"] as List<*>).first())
        assertEquals(listOf("active", "active", "accounts", "candidates", "context"), events)
        assertTrue(operations.all { "source_scope_key" !in it && "stable_source_key" !in it })
    }

    @Test fun candidateQueriesOnlySeeNewFormalFactsAndRetainedTargetsDoNotChangeTheSeedContract() {
        val operations = listOf("create_posted", "finalize_draft", "update", "duplicate").mapIndexed { index, action ->
            mapOf<String, Any?>("planned_action" to action, "transaction_id" to if (index == 0) null else "9007199254740993") }
        val accounts = mapOf("10" to mapOf<String, Any?>("revision" to 9007199254740995L))
        val rules = CashImportPreviewPreparationRules<Long, Instant>({}, { accounts },
            { ops, _ -> assertEquals(listOf("create_posted", "finalize_draft"), ops.map { it["planned_action"] }); setOf(33L) },
            { seeds, groups -> assertEquals(setOf(33L, 44L, 9007199254740993L), seeds); assertEquals(listOf("group"), groups)
                context.copy(seedIds = seeds, decisionGroupIds = groups) })
        val result = rules.prepareOperations(operations, entryChoices = listOf(mapOf("edit_ref" to "edit")),
            previousPlan = mapOf("entry_targets" to mapOf("edit" to mapOf("parent_ids" to listOf("18446744073709551616")))),
            additionalSeedIds = setOf(44L), decisionGroupIds = listOf("group"))
        assertSame(operations, result.operations)
        assertSame(accounts, result.accounts)
        assertEquals(setOf("18446744073709551616"), result.retainedParentIds)
        assertEquals(setOf(33L), result.candidateIds)
    }

    @Test fun staleTargetsFailBeforeReadsAndGuardsAndProgramFailuresKeepTheirOriginalErrors() {
        val failure = IllegalStateException("guard or query failure")
        val guard = CashImportPreviewPreparationRules<Long, Instant>({ throw failure }, { error("not reached") },
            { _, _ -> error("not reached") }, { _, _ -> error("not reached") })
        assertSame(failure, assertFailsWith<IllegalStateException> { guard.prepare(listOf(emptyMap()), emptyMap()) })
        val stale = CashImportPreviewPreparationRules<Long, Instant>({}, { error("not reached") },
            { _, _ -> error("not reached") }, { _, _ -> error("not reached") })
        assertEquals("plan_changed", assertFailsWith<CashImportPlanError> {
            stale.prepareOperations(emptyList(), entryChoices = listOf(mapOf("edit_ref" to "missing"))) }.code)
        val query = CashImportPreviewPreparationRules<Long, Instant>({}, { emptyMap() },
            { _, _ -> throw failure }, { _, _ -> error("not reached") })
        assertSame(failure, assertFailsWith<IllegalStateException> { query.prepareOperations(emptyList()) })
    }
}
