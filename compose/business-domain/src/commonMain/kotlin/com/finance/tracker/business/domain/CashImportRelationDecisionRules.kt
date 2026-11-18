package com.finance.tracker.business.domain

/** Apply already-validated relation decisions to mutable preview state; persistence remains outside these rules. */
class CashImportRelationDecisionRules<I : Comparable<I>, T : Comparable<T>>(
    private val integer: (Any?) -> I?, private val copyValue: (Any?) -> Any?,
    private val digestValues: (List<Any?>) -> String,
) {
    @Suppress("UNCHECKED_CAST")
    private fun map(value: Any?): Map<String, Any?> = value as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    private fun maps(value: Any?): List<Map<String, Any?>> = value as List<Map<String, Any?>>
    private fun copy(value: Any?): Any? = copyValue(value)
    private fun digest(vararg values: Any?): String = digestValues(values.toList())

    private fun publicFact(fact: CashProjectionFact<I, T>, values: Map<String, Any?>): CashProjectionFact<I, T> = fact.withPublicFields(
        if ("counterparty" in values) values["counterparty"] as String else fact.counterparty,
        if ("category_id" in values) values["category_id"] as? String else fact.categoryId,
        if ("note" in values) values["note"] as String else fact.note)

    private fun publicValues(snapshot: CashImportPreviewSnapshot<I, T>, parent: String, values: Map<String, Any?>, replace: Boolean = false) {
        val entry = snapshot.entryPublic.getValue(parent)
        val updated = if (replace) map(copy(values)) else map(entry.getValue("values")) + map(copy(values))
        snapshot.entryPublic[parent] = entry + mapOf("values" to updated)
    }

    fun <M> restoreSplitValues(snapshot: CashImportPreviewSnapshot<I, T>, groups: List<MutableMap<String, Any?>>,
        readMembers: () -> Map<Long, M>, standaloneValues: (M) -> Map<String, Any?>,
        confirmedClosure: (Set<Long>) -> Set<Long>) {
        for (group in groups) {
            val diffs = group["split_field_diffs"]
            val empty = diffs == null || diffs == false || diffs is Collection<*> && diffs.isEmpty() || diffs is Map<*, *> && diffs.isEmpty()
            if (group["origin"] == "current" && group["action"] == "remove" && empty && "inherit_common_fields" !in group)
                group["inherit_common_fields"] = false
        }
        val selected = groups.filter { it["action"] == "remove" && it["inherit_common_fields"] == false }
        if (selected.isEmpty()) return
        val members = readMembers()
        for (group in selected) {
            val identifiers = (group["old_parent_ids"] ?: group.getValue("parent_ids")) as List<*>
            val old = confirmedClosure(identifiers.map { it.toString().toLong() }.toSet())
            for (identifier in old) {
                val member = members[identifier] ?: continue
                val parent = identifier.toString()
                val fact = snapshot.facts[parent] ?: continue
                val values = standaloneValues(member)
                publicValues(snapshot, parent, values, replace = true)
                snapshot.facts[parent] = publicFact(fact, values.filterKeys { it in setOf("note", "counterparty", "category_id") })
            }
        }
    }

    private fun edge(value: Map<String, Any?>): CashRelationGraphEdge {
        val amount = value["applied_amount"]?.let { if (it is String) CashDecimalLiteral(it) else it }
        return CashRelationGraphEdge(value.getValue("id"), value.getValue("kind"), value.getValue("primary_transaction_id"),
            value["secondary_transaction_id"], value["primary_component_id"], value["secondary_component_id"], value["primary_role"],
            value["secondary_role"], amount, value.getValue("state"), value.getValue("active"))
    }
    private fun id(edge: CashRelationGraphEdge) = integer(edge.id) ?: relationFailure("relation_data_invalid")

    fun applyGroups(snapshot: CashImportPreviewSnapshot<I, T>, groups: MutableList<MutableMap<String, Any?>>): List<Map<String, Any?>> {
        val failures = mutableListOf<Map<String, Any?>>()
        val discarded = mutableSetOf<String>()
        val closures = cashImportClosures(snapshot.context.relations)
        val edgesByGroup = linkedMapOf<String, List<CashRelationGraphEdge>>()
        val groupByEdge = linkedMapOf<String, MutableMap<String, Any?>>()
        for (group in groups) {
            if (group["origin"] == "current" && !(group.getValue("changed") as Boolean)) continue
            val action = group.getValue("action") as String
            val state = when (action) { "accept" -> "confirmed"; "defer" -> "draft"; "ignore" -> "ignored"; "remove" -> null; else -> error("invalid relation group action") }
            val edges = maps(group.getValue("edges")).map { raw -> edge(raw).let { value ->
                if (value.active == true) if (state == null) value.copy(active = false) else value.copy(state = state) else value
            } }
            edgesByGroup[group["suggestion_id"] as String] = edges
            edges.forEach { groupByEdge[it.id.toString()] = group }
        }
        val changes = edgesByGroup.flatMap { (suggestion, edges) -> edges.map { cashImportLink(it, suggestion) } }
        for (identifiers in cashRelationCommitGroups(snapshot.edges.values.map(::cashImportLink), changes)) {
            val related = identifiers.map(groupByEdge::getValue).associateBy { it["suggestion_id"] as String }
            val edges = related.keys.flatMap(edgesByGroup::getValue)
            val before = edges.associate { id(it) to snapshot.edges[id(it)] }
            val commit = "cg_" + digest("cash-import-commit-group-v1", related.keys.sortedWith(cashRelationStringOrder)).take(40)
            related.values.forEach { it["commit_group_id"] = commit }
            edges.forEach { snapshot.edges[id(it)] = it }
            try {
                val parents = related.values.flatMap { group -> (group.getValue("parent_ids") as List<*>).flatMap {
                    val parent = it as String; closures[parent] ?: setOf(parent)
                } }.toSet()
                var build = snapshot.build(parents)
                val conflicts = linkedMapOf<String, List<Any?>>()
                val decisions = linkedMapOf<String, Any?>()
                for (group in related.values) for ((field, value) in map(group["common_fields"] ?: emptyMap<String, Any?>())) {
                    if (field in decisions && decisions[field] != value) conflicts[field] = listOf(decisions[field], value)
                    else decisions[field] = value
                }
                for (projection in build.projections) {
                    if (related.values.none { it["action"] == "accept" } || projection.memberIds.size < 2) continue
                    val byField = linkedMapOf<String, MutableMap<String, Any?>>()
                    val seen = mutableSetOf<Any?>()
                    for (member in projection.memberIds) {
                        val entry = snapshot.entryPublic[member.toString()] ?: continue
                        if (!seen.add(entry["entry_id"])) continue
                        for (raw in entry.getValue("manual_fields") as List<*>) {
                            val field = raw as String
                            if (field in commonCashFields) {
                                val value = map(entry.getValue("values"))[field]
                                byField.getOrPut(field) { linkedMapOf() }[digest(value)] = value
                            }
                        }
                    }
                    for ((field, variants) in byField) if (field !in decisions) {
                        if (variants.size > 1) conflicts[field] = variants.values.toList()
                        else decisions[field] = variants.values.first()
                    }
                    if (conflicts.isEmpty()) {
                        val root = projection.primaryRecord.id.toString()
                        if (root in snapshot.entryPublic) publicValues(snapshot, root, decisions)
                        snapshot.facts[root] = publicFact(snapshot.facts.getValue(root), decisions)
                    }
                }
                if (conflicts.isNotEmpty()) {
                    for (group in related.values) if (group["action"] == "accept") {
                        group["action"] = "defer"; group["common_field_conflicts"] = conflicts; group["code"] = "common_field_conflict"
                        for (edge in edgesByGroup.getValue(group["suggestion_id"] as String)) {
                            val identifier = id(edge); snapshot.edges[identifier] = snapshot.edges.getValue(identifier).copy(state = "draft")
                        }
                    }
                    build = snapshot.build(parents)
                } else for (group in related.values) if (group["action"] == "accept") group["common_fields"] = copy(decisions)
                val applied = build.projections.flatMap { it.relations }.associate { it.id to it.appliedAmount }
                for (edge in edges) if (id(edge) in applied) snapshot.edges[id(edge)] = edge.copy(appliedAmount = applied[id(edge)])
                for (group in related.values) group["edges"] = edgesByGroup.getValue(group["suggestion_id"] as String)
                    .map { cashImportEdgeMap(snapshot.edges.getValue(id(it))) }
            } catch (failure: CashRelationFailure) {
                for ((identifier, edge) in before) if (edge == null) snapshot.edges.remove(identifier) else snapshot.edges[identifier] = edge
                if (related.values.any { it["origin"] in setOf("manual", "current") || it["explicit_decision"] == true })
                    throw CashPolicyError(failure.code, emptyList())
                for (group in related.values) discarded += group["suggestion_id"] as String
            }
        }
        groups.removeAll { (it["suggestion_id"] as String) in discarded }
        return failures
    }
}
