package com.finance.tracker.backend

import kotlinx.serialization.json.*

/** 已持有工作区写锁的确认核心；异常由外层事务整体回滚。 */
internal class CashImportCommitWriter(private val context: CashSqlContext, private val userId: String, private val boundary: CashImportCommitBoundary) {
    @Suppress("UNCHECKED_CAST") private fun maps(value: Any?) = value as List<Map<String, Any?>>
    private fun fingerprint(operation: Map<String, Any?>): String = sourceDigest(operation.filterKeys { it in setOf("occurrence_key", "transaction_id", "expected_revision", "planned_action", "current", "values",
        "manual_fields", "field_diffs", "pending_fields", "source_updates", "issues", "expected_issues", "retained_issue_ids", "expected_accounts", "source_explanation", "source_scope_key",
        "candidate_ids", "candidate_set_ref", "entry_edit", "entry_edit_group", "entry_snapshot", "type_changed", "preview_transaction_id", "preview_components", "issue_resolutions", "resolved_issue_ids", "expected_source_bindings", "merge_draft") })

    fun apply(current: ParsedImportSession, planId: String, requestKey: String, requestDigest: String): JsonObject {
        val plan = current.plan ?: throw CashImportPlanError("plan_changed")
        if (plan["can_commit"] != true) throw CashImportPlanError("plan_input_invalid")
        val operations = maps(plan["operations"])
        boundary.files = current.files
        boundary.rows("submit_revalidation", operations)
        val sources = CashImportSourcePlanner(context).prepare(current.files)
        val fresh = try { CashImportBusinessPlanBuilder(context).build(current.files, current.previewInput, plan, sources) }
            catch (failure: CashImportPlanError) { if (failure.code == "plan_input_invalid") throw CashImportPlanError("plan_changed", diagnostic = failure.diagnostic) else throw failure }
        val freshOperations = maps(fresh["operations"])
        if (operations.size != freshOperations.size || operations.zip(freshOperations).any { fingerprint(it.first) != fingerprint(it.second) } ||
            listOf("entry_edits", "relation_groups", "relation_failures", "row_decisions").any { sourceDigest(plan.getOrDefault(it, emptyList<Any?>())) != sourceDigest(fresh.getOrDefault(it, emptyList<Any?>())) })
            throw CashImportPlanError("plan_changed")
        val projections = CashProjectionRepository(context)
        val state = projections.requireReadyStateLock()
        boundary.done("submit_revalidation")
        val preparation = CashImportCommitPreparation(context)
        boundary.enter("source_evidence", current.files.map { mapOf("file_ref" to it.fileRef) }, if (current.files.size == 1) "file" else "related")
        preparation.saveEvidence(current.files, sources, plan) { members -> boundary.enter("source_evidence", members.map { mapOf("file_ref" to it.fileRef) }, if (members.size == 1) "file" else "related") }
        boundary.enter("source_evidence", current.files.map { mapOf("file_ref" to it.fileRef) }, if (current.files.size == 1) "file" else "related")
        boundary.done("source_evidence")
        val sets = CashImportCommitPreparation.frozenCandidateSets(plan.getOrDefault("source_candidate_sets", emptyMap<String, Any?>()))
        val actual = linkedMapOf<String, Map<String, Any?>>()
        val identity = linkedMapOf<String, String>()
        val components = linkedMapOf<String, String>()
        val changes = mutableListOf<Pair<Map<String, Any?>?, Map<String, Any?>?>>()
        val affected = mutableSetOf<Long>()
        val records = sources.records.associateBy { it.transactionId }
        boundary.rows("batch_write", operations)
        val bulk = preparation.newBatches(operations, sources.rows.rows) { boundary.rows("batch_write", it) }
        boundary.done("batch_write")
        for (operation in operations) {
            boundary.rows("row_write", listOf(operation), if ((operation["row_refs"] as List<*>).size == 1) "row" else "related")
            val ref = operation["operation_ref"] as String
            if (operation["planned_action"] == "duplicate" && cashImportIssuesUnchanged(operation)) {
                val record = records.getValue(operation["transaction_id"] as String)
                val missing = maps(operation["source_updates"]).filter { it["bind"] == true }.any { update ->
                    sources.rows.rows.getValue(update["row_ref"] as String).bindingSpecs.any { it.key !in record.bindingKeys }
                }
                if (!missing) {
                    identity[operation["preview_transaction_id"] as String] = operation["transaction_id"] as String
                    maps(operation["preview_components"]).forEach { components[it["component_id"] as String] = it["component_id"] as String }
                    actual[ref] = mapOf("outcome" to "duplicate", "transaction_id" to operation["transaction_id"])
                    boundary.done("row_write")
                    continue
                }
            }
            val saved = bulk[ref] ?: CashRecordWriter(context).save(preparation.candidateOperation(operation, sets), sources.rows.rows)
            identity[operation["preview_transaction_id"] as String] = saved.transactionId.toString()
            components.putAll(saved.componentIds)
            val outcome = mapOf("create_posted" to "created_posted", "create_draft" to "saved_draft", "update_draft" to "saved_draft", "update_posted" to "updated_posted", "finalize_draft" to "finalized_draft", "merge_draft" to "merged_draft", "duplicate" to "duplicate").getValue(operation["planned_action"] as String)
            actual[ref] = mapOf("outcome" to outcome, "transaction_id" to saved.transactionId.toString())
            if (operation["planned_action"] != "duplicate" || (operation["issues"] as List<*>).isNotEmpty()) affected += saved.transactionId
            affected += saved.touched
            changes += saved.before to saved.after
            boundary.done("row_write")
        }
        val rows = CashPlanFinalizer.rowResults(plan, actual)
        val freshByOccurrence = freshOperations.associateBy { it["occurrence_key"] }
        val used = operations.flatMap { maps(freshByOccurrence.getValue(it["occurrence_key"]).getOrDefault("used_account_selections", emptyList<Any?>())) }
        boundary.rows("account_mapping", operations)
        CashImportAccountMappings(context.connection, context.workspace, context.sqlite).publish(used, userId)
        boundary.done("account_mapping")
        boundary.enter("request")
        val relations = CashPlanRelationWriter(context, userId)
        val byParent = operations.associateBy { it["preview_transaction_id"] }
        val groups = relations.saveGroups(plan, fresh, identity, components, affected, maps(current.previewInput["relation_choices"]).map { it["suggestion_id"] as String }.toSet()) { members ->
            val positions = members.flatMap { it["parent_ids"] as List<*> }.distinct().flatMap { parent ->
                val refs = byParent[parent]?.get("row_refs") as? List<*>
                if (refs.isNullOrEmpty()) listOf(mapOf("transaction_id" to parent.toString())) else refs.distinct().map { mapOf("row_ref" to it) }
            }
            boundary.enter("relation_save", positions, "related")
        }.toMutableList()
        boundary.enter("request")
        relations.discoveryIssues(plan, identity, affected)
        groups += relations.discoveryResults(plan, identity)
        boundary.done("relation_save")
        val finalizer = CashPlanFinalizer(context)
        boundary.rows("balances", operations)
        finalizer.balances(changes)
        boundary.done("balances")
        boundary.rows("projection", operations)
        if (affected.isNotEmpty()) CashProjectionService.maintainInSession(context, affected, state)
        boundary.done("projection")
        boundary.rows("entry_edit", operations)
        val edited = finalizer.saveEntryChoices(maps(plan.getOrDefault("entry_edits", emptyList<Any?>())), identity, groups)
        boundary.done("entry_edit")
        boundary.enter("request")
        val summary = listOf("created_posted", "updated_posted", "saved_draft", "finalized_draft", "duplicate", "skipped", "relations_confirmed", "relations_deferred", "relations_ignored", "relations_failed").associateWith { 0 }.toMutableMap()
        fun increment(value: String) { summary[value] = summary.getOrDefault(value, 0) + 1 }
        actual.values.forEach { increment(it["outcome"] as String) }
        if (edited != 0) summary["updated_entries"] = edited
        rows.filter { it["operation_ref"] == null }.forEach { increment(it["outcome"] as String) }
        summary["duplicate"] = rows.count { it["outcome"] in setOf("duplicate", "duplicate_in_batch") }
        summary["skipped"] = rows.count { it["outcome"] == "skipped" }
        summary.remove("duplicate_in_batch")
        groups.forEach { increment("relations_" + it["outcome"]) }
        val successful = affected + actual.values.filter { it["transaction_id"] != null && it["outcome"] != "skipped" }.map { it["transaction_id"].toString().toLong() }
        val pending = finalizer.attentionCount(successful)
        val locations = projections.currentReferences(successful, state.datasetId)
        val pendingItems = locations.values.filter { it.getValue("attention_count").jsonPrimitive.long > 0 }.map { it.getValue("projection_id") }.toSet().size
        val files = current.files.map { mapOf("result_kind" to "file", "item_ref" to it.fileRef, "outcome" to if (it.status == "parsed") "recognized" else "skipped", "code" to it.errorCode) }
        val normalSkips = setOf("row_not_selected", "unpaid_closed", "failed_repay", "account_opening")
        val problem = rows.any { it["outcome"] == "skipped" && it["code"] !in normalSkips } || groups.any { it["outcome"] == "failed" } || files.any { it["outcome"] == "skipped" }
        val succeeded = (rows + groups).any { it["outcome"] !in setOf("skipped", "failed") }
        val written = actual.values.any { it["outcome"] !in setOf("skipped", "duplicate", "duplicate_in_batch") } || edited != 0 || groups.any { it["outcome"] in setOf("confirmed", "deferred", "ignored") }
        val status = if (problem && succeeded) "partial" else if (problem) "failed" else if (written) "completed" else "no_changes"
        boundary.enter("request")
        val receipt = ReceiptRepository(context.connection, context.workspace, context.sqlite, context.now).create(userId, requestKey, requestDigest, "import",
            mapOf("status" to status, "summary" to summary, "needs_attention_count" to pending, "commit_outcome" to mapOf("has_new_failures" to false, "has_business_writes" to written, "reason_codes" to emptyList<String>()),
                "pending_item_count" to pendingItems, "projection_version" to state.projectionVersion), rows + groups + files, planId, sessionDigest = tokenDigest(current.sessionId))
        boundary.done("receipt")
        return receipt
    }
}
