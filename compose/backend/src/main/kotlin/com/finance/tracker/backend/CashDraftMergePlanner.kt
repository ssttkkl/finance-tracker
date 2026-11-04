package com.finance.tracker.backend

import kotlinx.serialization.json.*

internal class CashDraftMergePlanner(private val context: CashSqlContext, private val planner: MutationPlanBuilder) {
    @Suppress("UNCHECKED_CAST") private fun map(value: Any?) = value as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun maps(value: Any?) = value as List<Map<String, Any?>>
    private fun same(field: String, first: Any?, second: Any?) = CashRecordPolicy.valuesEqual(
        CashRecordPolicy.normalize(field, first), CashRecordPolicy.normalize(field, second))
    private val common = setOf("category_id", "note", "counterparty", "counterparty_account", "counterparty_account_attrs")
    private val financial = listOf("account_id", "amount", "currency")
    private fun key(part: Map<String, Any?>, index: Int) = part["component_id"]?.toString() ?: "source_new_$index"
    private fun placeholder(record: Map<String, Any?>, part: Map<String, Any?>) = maps(record["components"]).size == 1 &&
        part["account_id"] == null && listOf("amount", "currency").all { same(it, part[it], record[it]) }
    private fun components(source: List<Map<String, Any?>>, target: List<Map<String, Any?>>, reject: Boolean = true): Map<String, String?> {
        val matches = source.mapIndexed { index, part -> key(part, index) to target.filter { other ->
            financial.all { part[it] == null || other[it] == null || same(it, part[it], other[it]) }
        } }.toMap()
        if (reject && target.isNotEmpty() && source.withIndex().any { (index, part) -> financial.any { part[it] != null } && matches.getValue(key(part, index)).isEmpty() })
            throw CashPolicyError("fixed_field_conflict", listOf("components"))
        val proven = source.mapIndexedNotNull { index, part ->
            val key = key(part, index); val found = matches.getValue(key)
            if (found.size == 1 && financial.all { part[it] != null && found.single()[it] != null }) key to found.single()["component_id"]?.toString() else null
        }.toMap()
        val counts = proven.values.groupingBy { it }.eachCount()
        return source.mapIndexed { index, part -> key(part, index).let { it to proven[it]?.takeIf { targetId -> counts[targetId] == 1 } } }.toMap()
    }
    private fun fixed(source: Map<String, Any?>, target: Map<String, Any?>) {
        val conflicts = listOf("amount", "currency", "occurred_at", "account_id").filter {
            source[it] != null && target[it] != null && !same(it, source[it], target[it])
        }
        if (conflicts.isNotEmpty()) throw CashPolicyError("fixed_field_conflict", conflicts)
        if (maps(source["components"]).any { part -> financial.all { part[it] != null } } && source["cash_granularity"] != target["cash_granularity"])
            throw CashPolicyError("fixed_field_conflict", listOf("cash_granularity"))
        components(maps(source["components"]).filterNot { placeholder(source, it) }, maps(target["components"]))
    }

    fun prepare(request: MutationPlanRequest): Map<String, Any?> {
        val source = planner.record(request.transactionId.toLong())
        val choice = request.issueResolutions.single { it.resolution == "merge_into" }
        val target = planner.record(choice.targetTransactionId!!.toLong())
        if (target.origin !in setOf("import", "legacy")) throw CashPolicyError("manual_relation_forbidden", emptyList())
        if (target.revision != choice.expectedTargetRevision) throw MutationPlanFailure("record_changed")
        val bindings = CashSourceWriteRepository(context).validateDraftMerge(source.id, target.id)
        val sourceFields = planner.fields(source); val targetFields = planner.fields(target)
        fixed(sourceFields, targetFields)
        val sourceIssues = planner.issues(source.id); val targetIssues = planner.issues(target.id)
        val selected = sourceIssues.singleOrNull { it.id.toString() == choice.issueId }
        if (selected == null || selected.revision != choice.expectedIssueRevision) throw ReceiptConflict("issue_changed")
        if (selected.code != "source_identity_ambiguous") throw CashPolicyError("issue_resolution_invalid", emptyList())
        val sourceEntry = planner.entry(source.id); val targetEntry = planner.entry(target.id)
        val sourceProtected = planner.protectedFields(source, includeCommon = true) + sourceEntry?.manualFields.orEmpty()
        val targetProtected = planner.protectedFields(target, includeCommon = true) + targetEntry?.manualFields.orEmpty()
        val sourcePublic = common.associateWith { if (sourceEntry != null) cashSourceValue(planner.entryValue(sourceEntry, it)) else sourceFields[it] }
        val targetPublic = common.associateWith { if (targetEntry != null) cashSourceValue(planner.entryValue(targetEntry, it)) else targetFields[it] }
        val patch = request.patch.mapValues { cashSourceValue(it.value) }
        val others = request.issueResolutions.filter { it !== choice }
        val resolved = prepareCashSourceResolutions(sourceFields, sourceIssues,
            others.map { CashSourceResolution(it.issueId, it.expectedIssueRevision, it.resolution) }, patch, sourceProtected)
        for (item in others.filter { it.resolution == "apply_source" }) {
            val proposed = sourceFields + pendingCashSourcePatch(sourceIssues.single { it.id.toString() == item.issueId }).mapValues { CashRecordPolicy.normalize(it.key, cashSourceValue(it.value)) }
            fixed(proposed, targetFields)
        }
        val preparedSource = map(cashSourceValue(planner.prepare(source, sourceFields, emptyMap(), sourceEntry, resolved.patch, resolved.resolutions, sourceIssues)))
        val effective = map(preparedSource["values"]); fixed(effective, targetFields)
        val incoming = effective.filter { (field, value) -> field in mutableCashFields && (value != null || field in sourceProtected) }.toMutableMap()
        for (field in listOf("amount", "currency", "occurred_at", "account_id")) if (targetFields[field] == null && effective[field] != null) incoming[field] = effective[field]
        val conflicts = (common intersect sourceProtected intersect targetProtected).filter { !CashRecordPolicy.valuesEqual(sourcePublic[it], targetPublic[it]) && it !in patch }.sortedWith(cashRelationStringOrder)
        if (conflicts.isNotEmpty()) throw CashPolicyError("source_resolution_conflict", conflicts)
        val operation = map(cashSourceValue(planner.prepare(target, targetFields, patch, targetEntry, incoming, existingIssues = targetIssues))).toMutableMap()
        val values = map(operation["values"])
        val effectiveMap = components(maps(effective["components"]), maps(values["components"]), reject = false)
        val componentMap = maps(sourceFields["components"]).associate { it.getValue("component_id").toString() to effectiveMap[it.getValue("component_id").toString()] }
        val inherited = (sourceProtected - targetProtected) intersect common
        val publicPatch = inherited.associateWith { sourcePublic[it] }.toMutableMap()
        publicPatch.putAll(patch.filterKeys { it in common })
        if (targetEntry != null && publicPatch.isNotEmpty()) operation["entry_edit"] = mapOf("entry_id" to targetEntry.id, "revision" to targetEntry.revision, "patch" to publicPatch)
        if (target.state == "draft") operation["manual_fields"] = ((operation["manual_fields"] as List<*>).map { it.toString() }.toSet() + inherited).sortedWith(cashRelationStringOrder)
        val resolutions = resolved.resolutions.map { mapOf("issue_id" to it.choice.issueId, "expected_issue_revision" to it.choice.expectedRevision,
            "resolution" to it.choice.resolution, "body_digest" to it.bodyDigest) } + mapOf("issue_id" to choice.issueId, "expected_issue_revision" to choice.expectedIssueRevision,
            "resolution" to choice.resolution, "target_transaction_id" to choice.targetTransactionId, "expected_target_revision" to choice.expectedTargetRevision,
            "body_digest" to cashSourceDigest(JsonPrimitive(selected.code), selected.fieldPath?.let(::JsonPrimitive), selected.pendingFields, selected.pendingSourcePayload, selected.origin))
        val resolvedIds = resolutions.map { it.getValue("issue_id").toString() }.sortedWith(cashRelationStringOrder)
        operation["issue_resolutions"] = resolutions; operation["resolved_issue_ids"] = resolvedIds
        val refs = CashDraftMergeFacts(context).references(source.id, target.id)
        val pending = incoming.filter { (field, value) -> field !in patch && !same(field, value, values[field]) }.toMutableMap()
        if (maps(effective["components"]).withIndex().any { (index, part) -> effectiveMap[key(part, index)] == null &&
                !placeholder(effective, part) && financial.any { part[it] != null } }) pending["components"] = maps(effective["components"]).map { it.filterKeys { key -> key != "component_id" } }
        val retained = sourceIssues.filter { it.id.toString() !in resolvedIds }
        val displayed = (operation["display_retained_issues"] as List<*>).toMutableList()
        displayed.addAll(retained.map { cashSourceValue(planner.issueJson(cashImportIssue(it.code, fieldPath = it.fieldPath, afterImportAction = "open_home_item"))) })
        if (pending.isNotEmpty()) displayed.add(cashSourceValue(planner.issueJson(cashImportIssue("source_update_pending", afterImportAction = "open_home_item"))))
        operation["display_retained_issues"] = displayed
        val categoryOrigin = if ("category_id" in incoming && ("category_id" !in targetProtected || "category_id" in sourceProtected))
            if ("category_id" in sourceProtected) "human" else "source" else null
        val merge = map(cashSourceValue(sourceJson(cashMergeWire(mapOf("source_transaction_id" to source.id.toString(), "target_transaction_id" to target.id.toString(),
            "source_revision" to source.revision, "source_fields" to sourceFields, "category_origin" to categoryOrigin,
            "component_map" to componentMap, "pending_fields" to pending, "retained_issue_ids" to retained.map { it.id.toString() },
            "expected_bindings" to bindings, "references" to refs,
            "snapshot" to mapOf("count" to refs.values.sumOf { it.size }, "digest" to cashImportDigest(refs,
                cashSourceValue(source.manualOverrides), cashSourceValue(target.manualOverrides), cashSourceValue(source.relationMetadata), cashSourceValue(target.relationMetadata)))))))).toMutableMap()
        merge["relations"] = remapRelations(maps(merge["references"].let(::map)["relations"]), source.id, target.id, componentMap,
            maps(values["components"]).mapNotNull { it["component_id"]?.toString() }.toSet())
        operation["merge_draft"] = merge
        CashDraftMergeReferences.prepare(operation)
        operation["planned_action"] = "merge_draft"
        return planner.decorate(target, sourceJson(cashMergeWire(operation)).jsonObject)
    }

    private fun remapRelations(rows: List<Map<String, Any?>>, source: Long, target: Long, mapping: Map<String, String?>, targetParts: Set<String>): List<Map<String, Any?>> {
        val keys = linkedSetOf<List<Any?>>()
        return rows.map { original ->
            val row = original.toMutableMap()
            for (role in listOf("primary", "secondary", "anchor")) {
                if (row[role + "_transaction_id"]?.toString() == source.toString()) {
                    if (row["active"] == true && row["state"] == "confirmed") throw CashRelationFailure("relation_context_changed")
                    row[role + "_transaction_id"] = target
                    row[role + "_component_id"] = mapping[row[role + "_component_id"]?.toString()]?.toLong()
                    if (row[role + "_component_id"] == null && role != "anchor") row[role + "_role"] = null
                } else if (row[role + "_transaction_id"]?.toString() == target.toString() && row[role + "_component_id"]?.toString() !in targetParts) {
                    row[role + "_component_id"] = null; if (role != "anchor") row[role + "_role"] = null
                }
            }
            fun number(field: String) = row[field]?.toString()?.toLong()
            if (number("secondary_transaction_id") == number("primary_transaction_id")) throw CashRelationFailure("relation_endpoint_incompatible")
            if (row["active"] == true) {
                val key = if (row["secondary_transaction_id"] == null) listOf(row["kind"], number("anchor_transaction_id"), number("anchor_component_id"))
                else {
                    val ends = listOf(number("primary_transaction_id")!! to (number("primary_component_id") ?: 0L), number("secondary_transaction_id")!! to (number("secondary_component_id") ?: 0L))
                    listOf(row["kind"], if (row["kind"] == "payment_mirror") ends.sortedWith(compareBy({ it.first }, { it.second })) else ends)
                }
                if (!keys.add(key)) throw CashRelationFailure("relation_context_changed")
            }
            row
        }
    }

    companion object {
        @Suppress("UNCHECKED_CAST") fun overlay(snapshot: CashImportPreviewSnapshot, operation: Map<String, Any?>) {
            val merge = operation["merge_draft"] as Map<String, Any?>; val source = merge["source_transaction_id"].toString(); val target = merge["target_transaction_id"].toString()
            snapshot.facts.remove(source); snapshot.values.remove(source); snapshot.components.entries.removeIf { it.value.transactionId == source }
            for (row in merge["relations"] as List<Map<String, Any?>>) {
                val id = row.getValue("id").toString().toBigInteger(); val edge = snapshot.edges[id] ?: continue
                snapshot.edges[id] = edge.copy(primaryTransactionId = row["primary_transaction_id"]?.toString(), secondaryTransactionId = row["secondary_transaction_id"]?.toString(),
                    primaryComponentId = row["primary_component_id"]?.toString(), secondaryComponentId = row["secondary_component_id"]?.toString(),
                    primaryRole = row["primary_role"], secondaryRole = row["secondary_role"])
            }
            if ((merge["retained_issue_ids"] as List<*>).isNotEmpty() || (merge["pending_fields"] as Map<*, *>).isNotEmpty())
                snapshot.facts[target] = snapshot.facts.getValue(target).withAttention(true)
        }
    }
}
