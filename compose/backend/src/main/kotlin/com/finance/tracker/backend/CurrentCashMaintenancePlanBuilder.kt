package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import kotlinx.serialization.json.*

/** 当前流水维护的完整只读业务图；发布和确认由应用服务完成。 */
internal class CurrentCashMaintenancePlanBuilder(private val context: CashSqlContext) {
    private fun parent(id: Long) = context.sql.select(CASH_TRANSACTIONS.RECORD_ORIGIN).from(CASH_TRANSACTIONS)
        .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(context.workspace)).and(CASH_TRANSACTIONS.ID.eq(id))
        .and(CASH_TRANSACTIONS.DELETED_AT.isNull).fetchOne()?.value1() ?: throw MutationPlanFailure("record_not_found")
    fun build(request: MutationPlanRequest, previous: JsonObject? = null): JsonObject {
        if (request.intent !in MutationPlanRequest.intents) throw MutationPlanFailure("plan_input_invalid")
        val id = request.transactionId.toLong(); val origin = parent(id)
        val planner = if (request.intent == "maintain_relations") CurrentCashRelationPlanner(context, id) else null
        val seeds = planner?.seedIds.orEmpty().toMutableSet()
        for (addition in request.relationAdditions) {
            val target = addition.getValue("target_transaction_id").jsonPrimitive.long
            if (parent(target) !in setOf("import", "legacy") || origin !in setOf("import", "legacy"))
                throw CashPolicyError("relation_endpoint_incompatible", emptyList())
            seeds += target
        }
        val operation = MutationPlanBuilder(context).prepareMaintenanceOperation(request)
        val rows = listOf(mapOf("transaction_id" to request.transactionId, "operation_ref" to "op_1", "planned_action" to operation.getValue("planned_action")))
        @Suppress("UNCHECKED_CAST") val before = previous?.let(::cashSourceValue) as? Map<String, Any?>
        val result = CashImportBusinessPlanBuilder(context).buildOperations(listOf(operation), rows,
            mapOf("relation_choices" to request.relationChoices.map(::cashSourceValue)), before,
            includeUnchanged = true, additionalSeedIds = seeds, decisionGroupIds = planner?.decisionGroupIds.orEmpty(),
            currentRelationBuilder = planner?.let { current -> { snapshot, accounts -> current.build(snapshot, accounts) +
                current.manualGroups(snapshot, request.transactionId, request.relationAdditions.map { cashSourceValue(it) as Map<String, Any?> }) } })
        var wire = sourceJson(result).jsonObject
        for (raw in wire.getValue("relation_groups").jsonArray) {
            val group = raw.jsonObject
            if (group["action"] == JsonPrimitive("remove") && !group["split_field_diffs"]?.jsonArray.isNullOrEmpty() && "inherit_common_fields" !in group)
                throw CashPolicyError("inheritance_required", emptyList())
        }
        if (wire.getValue("operations").jsonArray.size != 1) throw CashPolicyError("relation_endpoint_incompatible", listOf("record_type", "record_subtype"))
        val draftIds = wire.getValue("operations").jsonArray.filter { it.jsonObject.getValue("current").jsonObject["state"] == JsonPrimitive("draft") }
            .map { it.jsonObject.getValue("transaction_id") }.toSet()
        val details = JsonObject(wire.getValue("item_details").jsonObject.mapValues { (_, value) ->
            val detail = value.jsonObject; val members = detail.getValue("members").jsonArray
            JsonObject(detail + ("members" to JsonArray(members.map { raw ->
                val member = raw.jsonObject
                JsonObject(member + mapOf("editable_fields" to if (members.size > 1) JsonArray(emptyList()) else
                    JsonArray(member.getValue("editable_fields").jsonArray.filter { it != JsonPrimitive("account_name") }),
                    "allowed_actions" to JsonArray((if (member["transaction_id"] in draftIds) listOf("save_draft", "finalize_draft") else listOf("edit_record")).map(::JsonPrimitive))))
            })))
        })
        val summary = wire.getValue("summary").jsonObject.toMutableMap()
        val resolved = wire.getValue("operations").jsonArray.sumOf { it.jsonObject["resolved_issue_ids"]?.jsonArray?.size ?: 0 }
        if (resolved > 0) summary["resolve_issue"] = JsonPrimitive(resolved)
        val effects = wire.getValue("type_effects").jsonObject
        wire = JsonObject(wire + mapOf("item_details" to details, "can_commit" to JsonPrimitive(true), "category_member_ids" to JsonArray(emptyList()),
            "manual_transfer_relations" to JsonArray(emptyList()), "mutation_units" to JsonArray(listOf(JsonArray(listOf(JsonPrimitive("op_1"))))),
            "summary" to JsonObject(summary + ("relations_removed" to JsonPrimitive((summary["relations_removed"]?.jsonPrimitive?.long ?: 0) +
                effects.getValue("removed_relation_ids").jsonArray.size + effects.getValue("removed_funding_ids").jsonArray.size)))))
        val affected = wire.getValue("relation_failures").jsonArray.flatMap { it.jsonObject.getValue("parent_ids").jsonArray }
            .mapNotNull { it.jsonPrimitive.content.toLongOrNull()?.takeIf { id -> id > 0 } }.toSet()
        val baseline = affected.sorted().chunked(500).flatMap { batch -> context.sql.select(CASH_TRANSACTION_ISSUES.TRANSACTION_ID, CASH_TRANSACTION_ISSUES.ISSUE_KEY, CASH_TRANSACTION_ISSUES.CODE)
            .from(CASH_TRANSACTION_ISSUES).where(CASH_TRANSACTION_ISSUES.WORKSPACE_ID.eq(context.workspace)).and(CASH_TRANSACTION_ISSUES.TRANSACTION_ID.`in`(batch))
            .fetch().map { Triple(it.value1()!!.toString(), it.value2()!!, it.value3()!!) } }
        return JsonObject(wire + ("additional_effects" to MutationPlanEffects.additionalEffects(request, wire, baseline)))
    }
}
