package com.finance.tracker.business.domain

/** Complete decided preview state using the existing grouping, graph and presentation rules. */
class CashImportPreviewPlanRules<I : Comparable<I>, T : Comparable<T>>(
    private val buildGroups: (CashImportPreviewSnapshot<I, T>, List<RelationProposal>) -> CashImportRelationGroupResult,
    private val applyChoices: (List<MutableMap<String, Any?>>, List<Map<String, Any?>>, Map<String, Any?>?) -> CashImportRelationChoiceResult<*>,
    private val restoreSplitValues: (CashImportPreviewSnapshot<I, T>, List<MutableMap<String, Any?>>) -> Unit,
    private val applyGroups: (CashImportPreviewSnapshot<I, T>, MutableList<MutableMap<String, Any?>>) -> List<Map<String, Any?>>,
    private val render: (List<ParsedImportFile>, CashImportPreviewSnapshot<I, T>, List<Map<String, Any?>>, List<Map<String, Any?>>, Map<String, Map<String, Any?>>, Boolean, Set<String>) -> MutableMap<String, Any?>,
    private val digest: (List<Any?>) -> String,
    private val groupValue: (Any?) -> Any?,
) {
    fun present(snapshot: CashImportPreviewSnapshot<I, T>, proposals: List<RelationProposal>,
        files: List<ParsedImportFile>, accounts: Map<String, Map<String, Any?>>, candidateIds: Set<Long>,
        retainedParentIds: Set<String>, sourceCandidateSets: Map<String, List<String>>, discoverySeeds: Set<String>,
        relationChoices: List<Map<String, Any?>>, previous: Map<String, Any?>?, includeUnchanged: Boolean = false,
        additionalGroups: (() -> List<MutableMap<String, Any?>>)? = null): MutableMap<String, Any?> {
        val grouped = buildGroups(snapshot, proposals)
        val groups = grouped.groups.toMutableList()
        additionalGroups?.let { groups.addAll(it()) }
        val selected = applyChoices(groups, relationChoices, previous)
        for (group in groups) if (group["origin"] == "current" && group["action"] == "remove" &&
            (group["split_field_diffs"] as? List<*>).isNullOrEmpty() && group["inherit_common_fields"] == null)
            group["inherit_common_fields"] = false
        restoreSplitValues(snapshot, groups)
        val failures = linkedMapOf<String, Map<String, Any?>>()
        for (failure in grouped.failures + applyGroups(snapshot, groups)) {
            val parents = (failure["parent_ids"] as List<*>).map { it.toString() }.distinct().sortedWith(cashRelationStringOrder)
            val value = failure + ("parent_ids" to parents)
            val key = digest(listOf(failure["code"], parents, failure["suggestion_id"]))
            if (key !in failures) failures[key] = value
        }
        for (failure in failures.values) for (parent in failure["parent_ids"] as List<*>) {
            snapshot.facts[parent]?.let { snapshot.facts[parent.toString()] = it.withAttention(true) }
        }
        val result = render(files, snapshot, groups, failures.values.toList(), accounts, includeUnchanged, retainedParentIds)
        result.putAll(linkedMapOf("invalidated_choices" to selected.invalidated,
            "invalidated_relation_choices" to selected.sources, "relation_choice_generations" to selected.generations,
            "source_candidate_sets" to sourceCandidateSets, "relation_groups" to groupValue(groups),
            "relation_failures" to failures.values.toList(), "dependencies" to linkedMapOf(
                "seed_ids" to snapshot.context.seedIds.sorted(), "token" to cashRelationReadContextToken(snapshot.context),
                "candidate_ids" to candidateIds.sorted(), "discovery_seeds" to discoverySeeds.sortedWith(cashRelationStringOrder)),
            "type_effects" to mapOf("removed_relation_ids" to snapshot.removedRelations.sorted(),
                "removed_funding_ids" to snapshot.removedFunding.sorted())))
        return result
    }
}

fun <I : Comparable<I>, T : Comparable<T>> cashImportPreviewDiscoverySeeds(snapshot: CashImportPreviewSnapshot<I, T>): Set<String> =
    snapshot.components.values.filter {
        snapshot.parentOps[it.transactionId]?.get("planned_action") in setOf("create_posted", "finalize_draft")
    }.map { it.componentId.toString() }.toSet()
