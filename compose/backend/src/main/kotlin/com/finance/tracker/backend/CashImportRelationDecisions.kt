package com.finance.tracker.backend

import java.math.BigInteger

internal data class CashImportRelationChoiceResult(val invalidated: List<Map<String, Any?>>, val sources: Map<String, Map<String, Any?>>,
    val generations: Map<String, BigInteger>)

/** 处理冻结关系选择、拆分公共值和最终预览图；真实写入由确认事务处理。 */
internal object CashImportRelationDecisions {
    @Suppress("UNCHECKED_CAST")
    private fun map(value: Any?): Map<String, Any?> = value as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    private fun maps(value: Any?): List<Map<String, Any?>> = value as List<Map<String, Any?>>
    private fun copy(value: Any?): Any? = CashRecordPolicy.copyValue(value)
    private fun invalid(): Nothing = throw CashImportPlanError("plan_input_invalid")
    private fun generation(value: Any?): BigInteger = value?.toString()?.toBigInteger() ?: BigInteger.ZERO

    fun applyChoices(groups: List<MutableMap<String, Any?>>, choices: List<Map<String, Any?>>,
        previous: Map<String, Any?>?): CashImportRelationChoiceResult {
        val old = maps(previous?.get("relation_groups") ?: emptyList<Map<String, Any?>>()).associateBy { it["suggestion_id"] as String }
        val stale = map(previous?.get("invalidated_relation_choices") ?: emptyMap<String, Any?>())
        val generations = map(previous?.get("relation_choice_generations") ?: emptyMap<String, Any?>())
            .mapValuesTo(linkedMapOf()) { generation(it.value) }
        fun identify(group: MutableMap<String, Any?>) {
            val source = group.getValue("source_suggestion_id") as String
            val version = generations[source] ?: BigInteger.ZERO
            group["choice_generation"] = version
            group["suggestion_id"] = if (version.signum() != 0) "sg_" + cashImportDigest(
                "cash-relation-choice-generation-v1", source, version).take(40) else source
        }
        for (group in groups) { group["source_suggestion_id"] = group.getValue("suggestion_id"); identify(group) }
        for (choice in choices) {
            val identifier = choice.getValue("suggestion_id") as String
            val before = old[identifier]
            if (before == null) {
                if (identifier !in stale || map(stale[identifier])["choice"] != choice) invalid()
            } else if (choice["candidate_id"] != null && maps(before.getValue("candidates")).none {
                it["candidate_id"] == choice["candidate_id"]
            }) invalid()
        }
        val current = groups.associateByTo(linkedMapOf()) { it["suggestion_id"] as String }
        val invalidated = mutableListOf<Map<String, Any?>>()
        val touched = mutableSetOf<Map<String, Any?>>()
        val sources = linkedMapOf<String, Map<String, Any?>>()
        val explicit = mutableSetOf<String>()
        fun invalidate(choice: Map<String, Any?>) {
            val identifier = choice["suggestion_id"] as String
            invalidated += linkedMapOf("kind" to "relation", "reference" to identifier, "code" to "relation_context_changed")
            val refs = maps(old[identifier]?.get("member_refs") ?: map(stale.getValue(identifier)).getValue("member_refs"))
            sources[identifier] = linkedMapOf("choice" to copy(choice), "member_refs" to copy(refs))
            touched.addAll(refs)
            old[identifier]?.let { before ->
                val source = (before["source_suggestion_id"] ?: identifier) as String
                generations[source] = maxOf(generations[source] ?: BigInteger.ZERO,
                    generation(before["choice_generation"]) + BigInteger.ONE)
            }
            current.remove(identifier)?.let { identify(it); current[it["suggestion_id"] as String] = it }
        }
        for (choice in choices) {
            val identifier = choice["suggestion_id"] as String
            val group = current[identifier]
            if (group == null) { invalidate(choice); continue }
            val action = choice.getValue("action") as String
            if (action !in (group.getValue("allowed_actions") as List<*>)) invalid()
            val candidateId = choice["candidate_id"] as? String
            if (!candidateId.isNullOrEmpty()) {
                val candidate = maps(group.getValue("candidates")).firstOrNull { it["candidate_id"] == candidateId }
                if (candidate == null) { invalidate(choice); continue }
                for (key in listOf("edges", "evidence", "parent_ids", "member_refs")) group[key] = copy(candidate.getValue(key))
                group["candidate_id"] = candidateId
            } else if (action == "accept" && group["origin"] == "current") {
                val confirmation = group["direct_confirmation"]?.let(::map) ?: invalid()
                for (key in listOf("edges", "evidence", "parent_ids", "member_refs")) group[key] = copy(confirmation.getValue(key))
            } else if (action == "accept" && maps(group["edges"]).any { it["secondary_transaction_id"] == null }) invalid()
            if ("common_fields" in choice) {
                if (action != "accept") invalid()
                group["common_fields"] = copy(choice["common_fields"])
            }
            if ("inherit_common_fields" in choice) {
                if (action != "remove") invalid()
                group["inherit_common_fields"] = choice["inherit_common_fields"]
            }
            group["action"] = action
            explicit += identifier
            if (group["origin"] == "current") group["changed"] = true
        }
        for (group in groups) if (group["origin"] != "current" && group["suggestion_id"] !in explicit &&
            maps(group["member_refs"]).any { it in touched }) group["action"] = "defer"
        return CashImportRelationChoiceResult(invalidated, sources, generations)
    }

    private fun publicFact(fact: CashProjectionFact, values: Map<String, Any?>): CashProjectionFact = CashProjectionFact.create(
        fact.id, fact.accountId, fact.occurredAt, fact.amount?.toString(), fact.currency,
        if ("counterparty" in values) values["counterparty"] as String else fact.counterparty,
        if ("category_id" in values) values["category_id"] as? String else fact.categoryId,
        if ("note" in values) values["note"] as String else fact.note, fact.sourceType, fact.recordId,
        fact.fundingRelationId, fact.cashGranularity, fact.state, fact.createdAt, fact.needsAttention)

    private fun publicValues(snapshot: CashImportPreviewSnapshot, parent: String, values: Map<String, Any?>, replace: Boolean = false) {
        val entry = snapshot.entryPublic.getValue(parent)
        val updated = if (replace) map(copy(values)) else map(entry.getValue("values")) + map(copy(values))
        snapshot.entryPublic[parent] = entry + mapOf("values" to updated)
    }

    fun restoreSplitValues(context: CashSqlContext, snapshot: CashImportPreviewSnapshot, groups: List<MutableMap<String, Any?>>) {
        context.requireActive()
        for (group in groups) {
            val diffs = group["split_field_diffs"]
            val empty = diffs == null || diffs == false || diffs is Collection<*> && diffs.isEmpty() || diffs is Map<*, *> && diffs.isEmpty()
            if (group["origin"] == "current" && group["action"] == "remove" && empty && "inherit_common_fields" !in group)
                group["inherit_common_fields"] = false
        }
        val selected = groups.filter { it["action"] == "remove" && it["inherit_common_fields"] == false }
        if (selected.isEmpty()) return
        val members = context.entries.forTransactions(snapshot.context.transactionIds).first
        val relations = CashRelationReadRepository(context)
        for (group in selected) {
            val identifiers = (group["old_parent_ids"] ?: group.getValue("parent_ids")) as List<*>
            val old = relations.confirmedClosure(identifiers.map { it.toString().toLong() }.toSet())
            for (identifier in old) {
                val member = members[identifier] ?: continue
                val parent = identifier.toString()
                val fact = snapshot.facts[parent] ?: continue
                val values = map(cashSourceValue(member.standaloneValues))
                publicValues(snapshot, parent, values, replace = true)
                snapshot.facts[parent] = publicFact(fact, values.filterKeys { it in setOf("note", "counterparty", "category_id") })
            }
        }
    }

    private fun edge(value: Map<String, Any?>): CashRelationGraphEdge {
        val amount = value["applied_amount"]?.let { if (it is String) CashDecimalLiteral(it) else it }
        return CashRelationGraphEdge(value.getValue("id"), value.getValue("kind"), value.getValue("primary_transaction_id"),
            value["secondary_transaction_id"], value["primary_component_id"], value["secondary_component_id"], value["primary_role"],
            value["secondary_role"], value.getValue("subtype"), amount, value.getValue("state"), value.getValue("active"))
    }
    private fun id(edge: CashRelationGraphEdge) = strictCashInteger(edge.id) ?: relationFailure("relation_data_invalid")

    fun applyGroups(snapshot: CashImportPreviewSnapshot, groups: List<MutableMap<String, Any?>>): List<Map<String, Any?>> {
        val failures = mutableListOf<Map<String, Any?>>()
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
            val commit = "cg_" + cashImportDigest("cash-import-commit-group-v1", related.keys.sortedWith(cashRelationStringOrder)).take(40)
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
                                byField.getOrPut(field) { linkedMapOf() }[cashImportDigest(value)] = value
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
                if (related.values.any { it["origin"] == "manual" }) throw CashPolicyError(failure.code, emptyList())
                for (group in related.values) {
                    group["action"] = "skip"; group["code"] = failure.code
                    failures += linkedMapOf("code" to failure.code, "parent_ids" to group["parent_ids"], "suggestion_id" to group["suggestion_id"])
                }
            }
        }
        return failures
    }
}
