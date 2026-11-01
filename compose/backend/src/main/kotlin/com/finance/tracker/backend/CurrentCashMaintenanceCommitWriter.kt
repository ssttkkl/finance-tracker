package com.finance.tracker.backend

import kotlinx.serialization.json.*

internal data class CurrentCashMaintenanceResult(val summary: JsonObject, val results: JsonArray)

/** 已授权并重验的无文件维护写入；调用方创建永久收据和提交事务。 */
internal class CurrentCashMaintenanceCommitWriter(private val context: CashSqlContext, private val userId: String, private val boundary: CashImportCommitBoundary) {
    @Suppress("UNCHECKED_CAST") private fun map(value: Any?) = value as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun maps(value: Any?) = value as List<Map<String, Any?>>
    fun apply(plan: JsonObject, fresh: JsonObject, request: MutationPlanRequest): CurrentCashMaintenanceResult {
        val frozen = map(cashSourceValue(plan)); val current = map(cashSourceValue(fresh))
        val operations = maps(frozen["operations"])
        val byReference = operations.associateBy { it["operation_ref"] as String }
        val projections = CashProjectionRepository(context); val state = projections.requireReadyStateLock()
        val actual = linkedMapOf<String, Map<String, Any?>>()
        val identity = linkedMapOf<String, String>(); val components = linkedMapOf<String, String>()
        val changes = mutableListOf<Pair<Map<String, Any?>?, Map<String, Any?>?>>()
        val affected = mutableSetOf<Long>(); val early = mutableListOf<Map<String, Any?>>()
        val excluded = mutableSetOf<String>()
        for (references in frozen["mutation_units"] as List<*>) {
            val unit = (references as List<*>).map { byReference.getValue(it as String) }
            val writer = CurrentCashRelationWriter(context, userId, afterWrite = { boundary.done("current_relation") })
            val coupled = writer.coupledGroups(frozen, unit)
            excluded += coupled.map { it["suggestion_id"] as String }
            if (coupled.isNotEmpty()) early += writer.beforeRecords(coupled, frozen)
            for (operation in unit) {
                if (operation["merge_draft"] != null) throw MutationPlanFailure("plan_input_invalid")
                val saved = CashRecordWriter(context).save(operation, emptyMap())
                boundary.done("record")
                identity[operation["preview_transaction_id"] as String] = saved.transactionId.toString()
                components.putAll(saved.componentIds)
                val outcome = mapOf("update_posted" to "updated_posted", "update_draft" to "saved_draft", "duplicate" to "duplicate")
                    .getValue(operation["planned_action"] as String)
                actual[operation["operation_ref"] as String] = mapOf("outcome" to outcome, "transaction_id" to saved.transactionId.toString())
                affected += saved.touched
                if (operation["planned_action"] != "duplicate") affected += saved.transactionId
                changes += saved.before to saved.after
            }
            if (coupled.isNotEmpty()) affected += writer.parentIds(coupled)
            context.entries.flush()
        }
        val rows = operations.map { operation -> mapOf("result_kind" to "row", "item_ref" to operation["transaction_id"],
            "operation_ref" to operation["operation_ref"]) + actual.getValue(operation["operation_ref"] as String) }
        val groups = early.toMutableList()
        boundary.done("current_relation_start")
        groups += CurrentCashRelationWriter(context, userId, afterWrite = { boundary.done("current_relation") }).saveGroups(frozen, current, affected, excluded)
        boundary.done("current_relation_end")
        val additions = maps(frozen["relation_groups"]).filter { it["origin"] != "current" }
        val relationWriter = CashPlanRelationWriter(context, userId)
        if (additions.isNotEmpty()) groups += relationWriter.saveGroups(frozen + ("relation_groups" to additions), current, identity, components, affected,
            request.relationChoices.map { it.getValue("suggestion_id").jsonPrimitive.content }.toSet() + additions.filter { it["origin"] == "manual" }.map { it["suggestion_id"] as String }) { members ->
                boundary.enter("relation_save", members.flatMap { it["parent_ids"] as List<*> }.distinct().map { mapOf("transaction_id" to it.toString()) }, "related")
            }
        boundary.enter("request")
        relationWriter.discoveryIssues(frozen, identity, affected)
        groups += relationWriter.discoveryResults(frozen, identity)
        val finalizer = CashPlanFinalizer(context); finalizer.balances(changes)
        val maintained = if (affected.isNotEmpty()) CashProjectionService.maintainInSession(context, affected, state) else null
        boundary.done("projection")
        context.entries.flush()
        val summary = listOf("updated_posted", "finalized_draft", "saved_draft", "merged_draft", "duplicate", "skipped", "relations_confirmed", "relations_deferred",
            "relations_ignored", "relations_removed", "relations_failed").associateWith { 0L }.toMutableMap()
        actual.values.forEach { summary[it["outcome"] as String] = summary.getOrDefault(it["outcome"] as String, 0) + 1 }
        summary["resolved_issues"] = operations.sumOf { (it["resolved_issue_ids"] as? List<*>).orEmpty().size }.toLong()
        groups.forEach { val key = "relations_" + it["outcome"]; summary[key] = summary.getOrDefault(key, 0) + 1 }
        val effects = map(frozen["type_effects"])
        summary["relations_removed"] = summary.getValue("relations_removed") + (effects["removed_relation_ids"] as List<*>).size + (effects["removed_funding_ids"] as List<*>).size
        val successful = affected + actual.values.filter { it["outcome"] != "skipped" }.map { it["transaction_id"].toString().toLong() }
        val locations = projections.currentReferences(successful, state.datasetId)
        val pendingItems = locations.values.filter { it.getValue("attention_count").jsonPrimitive.long > 0 }.map { it.getValue("projection_id") }.toSet().size
        val problem = groups.any { it["outcome"] == "failed" }
        val succeeded = (rows + groups).any { it["outcome"] !in setOf("skipped", "failed") }
        val written = summary.getValue("resolved_issues") > 0 || (rows + groups).any { it["outcome"] !in setOf("skipped", "duplicate") }
        val result = buildJsonObject {
            put("status", if (problem && succeeded) "partial" else if (problem) "failed" else if (written) "completed" else "no_changes")
            put("summary", sourceJson(summary)); put("retained_transaction_id", request.transactionId)
            put("projection_version", maintained?.get("projection_version") ?: JsonPrimitive(state.projectionVersion))
            put("needs_attention_count", finalizer.attentionCount(successful)); put("pending_item_count", pendingItems)
        }
        return CurrentCashMaintenanceResult(result, sourceJson(rows + groups).jsonArray)
    }
}
