package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.time.Instant
import kotlinx.serialization.json.JsonElement

/** 保存已重验的当前关系决定；外层事务负责权限、工作区锁、投影及收据。 */
internal class CurrentCashRelationWriter(private val context: CashSqlContext, private val userId: String, private val afterWrite: () -> Unit = {}) {
    private val repository = CurrentCashRelationWriteRepository(context)
    private val graphFields = listOf("kind", "primary_transaction_id", "secondary_transaction_id", "primary_component_id", "secondary_component_id",
        "primary_role", "secondary_role", "subtype", "applied_amount", "state", "active")
    @Suppress("UNCHECKED_CAST") private fun map(value: Any?) = value as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun maps(value: Any?) = value as List<Map<String, Any?>>
    private fun id(value: Any?) = value.toString().toLong()
    private fun normalize(value: Any?): Any? = when (value) {
        is JsonElement -> cashSourceValue(value)
        is CashStoredDecimal -> fixedCashDecimal(value)
        is Instant -> ParsedImportCodec.timeText(value)
        is Map<*, *> -> value.entries.associate { it.key.toString() to normalize(it.value) }
        is List<*> -> value.map(::normalize)
        else -> value
    }
    @Suppress("UNCHECKED_CAST") private fun copy(value: Any?) = CashRecordPolicy.copyValue(value) as MutableMap<String, Any?>
    private fun equal(left: Any?, right: Any?) = CashRecordPolicy.valuesEqual(left, right)
    private fun graphValue(key: String, value: Any?): Any? = when {
        key == "applied_amount" -> CashRecordPolicy.normalize("amount", value ?: "0")
        key.endsWith("_id") && value != null -> value.toString()
        else -> value
    }
    private fun parents(groups: List<Map<String, Any?>>): Set<Long> = groups.flatMap { group ->
        (group["parent_ids"] as List<*>).orEmpty() + (group["old_parent_ids"] as? List<*>).orEmpty() +
            maps(group["references"]).flatMap { listOfNotNull(it["primary_transaction_id"], it["secondary_transaction_id"], it["anchor_transaction_id"]) }
    }.map(::id).toSet()
    private fun removedParts(operations: List<Map<String, Any?>>): Set<Long> = operations.filter { map(it["current"])["state"] == "draft" }.flatMap { operation ->
        val retained = maps(map(operation["values"])["components"]).mapNotNull { it["component_id"]?.toString() }.toSet()
        maps(map(operation["current"])["components"]).mapNotNull { it["component_id"]?.toString() }.filter { it !in retained }.map(String::toLong)
    }.toSet()

    fun coupledGroups(plan: Map<String, Any?>, unit: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val removed = removedParts(unit); val commits = mutableSetOf<String>()
        val groups = maps(plan["relation_groups"])
        for (group in groups) if (group["origin"] == "current" && group["changed"] == true && group["action"] in setOf("ignore", "remove")) {
            for (row in maps(group["references"])) {
                val counts = maps(row["candidates"]).groupingBy { candidate -> id(candidate["candidate_transaction_id"]) to
                    candidate["candidate_component_id"]?.let(::id)?.takeIf { it !in removed } }.eachCount()
                if (counts.values.any { it > 1 }) commits += (group["commit_group_id"] ?: group["suggestion_id"]) as String
            }
        }
        return groups.filter { it["origin"] == "current" && it["changed"] == true && (it["commit_group_id"] ?: it["suggestion_id"]) in commits }
    }
    fun parentIds(groups: List<Map<String, Any?>>) = parents(groups)
    private fun result(group: Map<String, Any?>, changed: Boolean): Map<String, Any?> {
        val first = maps(group["references"]).first()
        return mapOf("result_kind" to "relation", "item_ref" to group["suggestion_id"], "outcome" to if (!changed) "duplicate" else
            mapOf("accept" to "confirmed", "defer" to "deferred", "ignore" to "ignored", "remove" to "removed").getValue(group["action"] as String),
            "relation_id" to first["id"].toString(), "transaction_id" to first["anchor_transaction_id"].toString())
    }
    fun beforeRecords(groups: List<Map<String, Any?>>, plan: Map<String, Any?>): List<Map<String, Any?>> {
        val rows = checkAfterRecords(groups, plan, true)
        val written = write(groups, rows, parents(groups), true)
        return groups.map { result(it, written.getValue(it["suggestion_id"] as String)) }
    }
    fun saveGroups(plan: Map<String, Any?>, fresh: Map<String, Any?>, affected: MutableSet<Long>, excluded: Set<String> = emptySet()): List<Map<String, Any?>> {
        context.requireActive()
        val current = maps(fresh["relation_groups"]).associateBy { it["suggestion_id"] }
        val grouped = maps(plan["relation_groups"]).filter { it["origin"] == "current" && it["changed"] == true && it["suggestion_id"] !in excluded }
            .groupBy { it["commit_group_id"] ?: it["suggestion_id"] }
        val results = mutableListOf<Map<String, Any?>>()
        for (groups in grouped.values) {
            if (groups.any { it["action"] == "skip" || !equal(current[it["suggestion_id"]], it) }) relationFailure("relation_context_changed")
            val parents = parents(groups)
            val rows = checkAfterRecords(groups, plan, false)
            val written = write(groups, rows, parents, false)
            context.entries.flush()
            results += groups.map { result(it, written.getValue(it["suggestion_id"] as String)) }
            affected += parents
        }
        return results
    }
    private fun checkAfterRecords(groups: List<Map<String, Any?>>, plan: Map<String, Any?>, beforeRecords: Boolean): Map<Long, Map<String, Any?>> {
        val originals = groups.flatMap { maps(it["references"]) }.associateBy { id(it["id"]) }
        val edges = groups.flatMap { maps(it["expected_record_edges"]) }.associateBy { id(it["id"]) }
        @Suppress("UNCHECKED_CAST") val actual = repository.planningRows(originals.keys).associate { id(it["id"]) to normalize(it) as Map<String, Any?> }
        if (actual.keys != originals.keys) relationFailure("relation_context_changed")
        if (beforeRecords) {
            if (!equal(actual, originals)) relationFailure("relation_context_changed")
            return actual
        }
        val operations = maps(plan["operations"]).filter { it["planned_action"] != "duplicate" }
        val removed = removedParts(operations)
        val revisions = operations.map { id(it["transaction_id"]) }.distinct().sorted().chunked(500).flatMap { batch ->
            context.sql.select(CASH_TRANSACTIONS.ID, CASH_TRANSACTIONS.ROW_REVISION).from(CASH_TRANSACTIONS)
                .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(context.workspace)).and(CASH_TRANSACTIONS.ID.`in`(batch)).fetch().map { it.value1()!! to it.value2()!! }
        }.toMap()
        val updated = operations.filter { revisions[id(it["transaction_id"])] != id(it["expected_revision"]) }.map { id(it["transaction_id"]) }.toSet()
        val typeRemoved = (map(plan["type_effects"])["removed_relation_ids"] as List<*>).map(::id).toSet()
        for ((identifier, original) in originals) {
            val row = actual.getValue(identifier); val expected = copy(original)
            for (key in graphFields) {
                if (!equal(graphValue(key, row[key]), graphValue(key, edges.getValue(identifier)[key]))) relationFailure("relation_context_changed")
                expected[key] = row[key]
            }
            if (expected["anchor_component_id"]?.let(::id) in removed) expected["anchor_component_id"] = null
            if (identifier in typeRemoved) expected.putAll(mapOf("active" to false, "decision_source" to "system", "decided_by" to "system",
                "decided_at" to row["decided_at"], "decision_reason" to "record_type_changed"))
            val endpointUpdated = row["active"] == true && listOfNotNull(row["primary_transaction_id"], row["secondary_transaction_id"], row["anchor_transaction_id"]).any { id(it) in updated }
            val candidates = maps(expected["candidates"]).map { candidate -> copy(candidate).apply {
                if (this["candidate_component_id"]?.let(::id) in removed) putAll(mapOf("candidate_component_id" to null, "validity" to "incomplete"))
                val parent = id(this["candidate_transaction_id"])
                if (endpointUpdated || parent in updated && id(this["expected_row_revision"]) != revisions[parent]) this["validity"] = "stale"
            } }.sortedBy { id(it["id"]) }
            expected["candidates"] = candidates
            val expectedRevision = id(expected.remove("row_revision"))
            val current = copy(row - "row_revision").apply { this["candidates"] = maps(row["candidates"]).sortedBy { id(it["id"]) } }
            if (!equal(current, expected)) relationFailure("relation_context_changed")
            val old = copy(original - "row_revision").apply { this["candidates"] = maps(original["candidates"]).sortedBy { id(it["id"]) } }
            val changed = !equal(expected, old); val revision = id(row["row_revision"])
            if (changed && revision <= expectedRevision || !changed && revision != expectedRevision) relationFailure("relation_context_changed")
        }
        return actual
    }
    private fun edge(value: Map<String, Any?>) = CashRelationGraphEdge(value["id"], value["kind"], value["primary_transaction_id"], value["secondary_transaction_id"],
        value["primary_component_id"], value["secondary_component_id"], value["primary_role"], value["secondary_role"], value["subtype"],
        value["applied_amount"]?.let { CashRecordPolicy.normalize("amount", it) }, value["state"], value["active"])
    private fun write(groups: List<Map<String, Any?>>, rows: Map<Long, Map<String, Any?>>, parents: Set<Long>, beforeRecords: Boolean): Map<String, Boolean> {
        val decisionGroups = groups.flatMap { maps(it["references"]) }.mapNotNull { it["decision_group_id"] as? String }.distinct().sortedWith(cashRelationStringOrder)
        val cleanup = groups.filter { it["action"] != "accept" }.flatMap { maps(it["references"]) }.mapNotNull { it["decision_group_id"] as? String }.toSet()
        val service = CashRelationContextService(context); val graph = service.load(parents, decisionGroups)
        val previous = graph.relations.associateBy { id(it.id) }
        val changes = mutableListOf<CashRelationGraphEdge>(); val evidence = mutableMapOf<Long, CashRelationEvidence>()
        for (group in groups) for (value in maps(group["edges"])) {
            var edge = edge(value)
            if (beforeRecords && rows.getValue(id(edge.id))["active"] != true) edge = previous.getValue(id(edge.id))
            changes += edge
            if (group["action"] == "accept") {
                val proof = map(map(group["evidence"])[edge.id.toString()])
                evidence[id(edge.id)] = CashRelationEvidence(proof["primary_original_expense"], proof["secondary_original_expense"], proof["investment_refund_hard_key"], proof["p2p_return_hard_key"])
            }
        }
        val prepared = service.validateLoaded(graph, updates = changes, evidenceByRelation = evidence, cleanupGroupIds = cleanup)
        for (group in groups) when (group["action"]) {
            "accept" -> context.entries.chooseCommon((group["parent_ids"] as List<*>).map(::id), map(group["common_fields"] ?: emptyMap<String, Any?>()))
            "remove" -> context.entries.prepareSplit(((group["old_parent_ids"] ?: group["parent_ids"]) as List<*>).map(::id), group["inherit_common_fields"])
        }
        val resolved = prepared.relations.associateBy { id(it.id) }; val written = linkedMapOf<String, Boolean>()
        for (group in groups) {
            var changed = false
            for (value in maps(group["edges"])) {
                val row = rows.getValue(id(value["id"])); val edge = resolved.getValue(id(value["id"]))
                if (row["active"] != true) continue
                val values = cashImportEdgeMap(edge).filterKeys { it != "id" }
                if (graphFields.all { equal(graphValue(it, row[it]), graphValue(it, values[it])) }) continue
                repository.replace(edge.id, values + mapOf("applied_amount" to (edge.appliedAmount ?: CashAmount.parse("0")),
                    "anchor_component_id" to map(group["anchors"])[edge.id.toString()],
                    "evidence" to if (group["action"] == "accept") map(group["evidence"])[edge.id.toString()] else row["evidence"],
                    "decision_source" to "manual", "decided_by" to userId, "decided_at" to context.now(),
                    "decision_reason" to mapOf("accept" to "user_confirmed", "defer" to "user_deferred", "ignore" to "user_ignored", "remove" to "user_removed").getValue(group["action"] as String)), id(row["row_revision"]))
                afterWrite(); changed = true
            }
            written[group["suggestion_id"] as String] = changed
        }
        return written
    }
}
