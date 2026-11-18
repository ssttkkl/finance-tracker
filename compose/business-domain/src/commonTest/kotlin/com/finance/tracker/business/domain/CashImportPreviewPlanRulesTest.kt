package com.finance.tracker.business.domain

import kotlin.test.*
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray

class CashImportPreviewPlanRulesTest {
    private fun preview(): CashImportPreviewSnapshot<CashImportPreviewInteger, Instant> {
        val source = LocalCashRelationContextTest().snapshot()
        return buildLocalCashImportPreviewSnapshot(source, readLocalCashRelationContext(source, emptySet()),
            LocalCashImportPreviewSnapshotTest().operation, JsonArray(emptyList())) { Instant.parse("2026-10-01T09:00:00Z") }
    }

    @Test fun duplicateBusinessFailuresCoalesceBeforeAttentionAndPresentation() {
        val snapshot = preview()
        val parent = snapshot.facts.keys.single()
        val first = mapOf("code" to "relation_context_changed", "parent_ids" to listOf("missing", parent, parent),
            "suggestion_id" to "sg", "first" to true)
        val second = mapOf("code" to "relation_context_changed", "parent_ids" to listOf(parent, "missing"),
            "suggestion_id" to "sg", "first" to false)
        val rules = CashImportPreviewPlanRules<CashImportPreviewInteger, Instant>(
            { _, _ -> CashImportRelationGroupResult(emptyList(), listOf(first)) },
            { groups, choices, previous -> applyLocalCashImportRelationChoices(groups, choices, previous) },
            { _, _ -> }, { _, _ -> listOf(second) }, ::presentLocalCashImportPreview,
            ::localCashImportDigest, ::copyLocalCashValue)
        val result = rules.present(snapshot, emptyList(), emptyList(), emptyMap(), emptySet(), emptySet(), emptyMap(),
            cashImportPreviewDiscoverySeeds(snapshot), emptyList(), null)
        val failures = result.getValue("relation_failures") as List<*>
        assertEquals(1, failures.size)
        val failure = failures.single() as Map<*, *>
        assertEquals(true, failure["first"])
        assertEquals(listOf(parent, "missing").sortedWith(cashRelationStringOrder), failure["parent_ids"])
        assertEquals(setOf(parent), snapshot.facts.keys)
        assertTrue(snapshot.facts.getValue(parent).needsAttention)
        assertEquals(1, (result.getValue("processing_details") as List<*>).size)
        assertEquals(1, (result.getValue("summary") as Map<*, *>)["relations_failed"])
    }

    @Test fun currentGroupsArriveBeforeChoicesAndNullRemovalDefaultsAreRestoredBeforeReading() {
        val snapshot = preview()
        val group = linkedMapOf<String, Any?>("origin" to "current", "action" to "remove",
            "split_field_diffs" to emptyList<Any?>(), "inherit_common_fields" to null)
        var selected = false
        var restored = false
        val rules = CashImportPreviewPlanRules<CashImportPreviewInteger, Instant>(
            { _, _ -> CashImportRelationGroupResult(emptyList(), emptyList()) },
            { groups, _, _ ->
                assertSame(group, groups.single())
                assertNull(group["inherit_common_fields"])
                selected = true
                CashImportRelationChoiceResult<Any?>(emptyList(), emptyMap(), emptyMap())
            },
            { _, groups ->
                assertTrue(selected)
                assertEquals(false, groups.single()["inherit_common_fields"])
                restored = true
            },
            { _, _ -> assertTrue(restored); emptyList() },
            { _, _, _, _, _, _, _ -> linkedMapOf() }, ::localCashImportDigest, ::copyLocalCashValue)
        val result = rules.present(snapshot, emptyList(), emptyList(), emptyMap(), emptySet(), emptySet(), emptyMap(),
            emptySet(), emptyList(), null, additionalGroups = { listOf(group) })
        assertTrue(restored)
        assertEquals(false, ((result.getValue("relation_groups") as List<*>).single() as Map<*, *>)["inherit_common_fields"])
    }

    @Test fun programErrorsAtEveryStageStopCompletionAndKeepTheirOriginalIdentity() {
        val stages = listOf("group", "additional", "choice", "restore", "apply", "digest", "render", "group-value")
        for (failed in stages) {
            val snapshot = preview()
            val error = IllegalStateException("internal $failed failure")
            val reached = mutableListOf<String>()
            fun stage(name: String) { reached += name; if (name == failed) throw error }
            val rules = CashImportPreviewPlanRules<CashImportPreviewInteger, Instant>(
                { _, _ -> stage("group"); CashImportRelationGroupResult(emptyList(), listOf(mapOf(
                    "code" to "relation_context_changed", "parent_ids" to listOf(snapshot.facts.keys.single()), "suggestion_id" to "sg"))) },
                { _, _, _ -> stage("choice"); CashImportRelationChoiceResult<Any?>(emptyList(), emptyMap(), emptyMap()) },
                { _, _ -> stage("restore") },
                { _, _ -> stage("apply"); emptyList() },
                { _, _, _, _, _, _, _ -> stage("render"); linkedMapOf() },
                { stage("digest"); localCashImportDigest(it) }, { stage("group-value"); it })
            assertSame(error, assertFailsWith<IllegalStateException> {
                rules.present(snapshot, emptyList(), emptyList(), emptyMap(), emptySet(), emptySet(), emptyMap(),
                    emptySet(), emptyList(), null, additionalGroups = { stage("additional"); emptyList() })
            })
            assertEquals(stages.take(stages.indexOf(failed) + 1), reached)
        }
    }
}
