package com.finance.tracker.backend

import kotlinx.serialization.json.*

/** 与导入/维护计划共用的 entry 目标指纹；只对冻结上下文求值。 */
internal data class PlanRelationEdge(
    val id: Long, val kind: String, val primaryTransactionId: String, val secondaryTransactionId: String?,
    val primaryComponentId: String?, val secondaryComponentId: String?, val primaryRole: String?,
    val secondaryRole: String?, val subtype: String, val appliedAmount: String?, val state: String, val active: Boolean,
)

internal data class EntryTargetInput(
    val parentIds: List<String>, val rows: List<JsonArray>, val entries: List<JsonArray>,
    val relationRevisions: List<JsonArray>, val dependencies: List<JsonObject>, val finalEdges: List<PlanRelationEdge>,
    val parentRowRefs: Map<String, List<String>>,
)

internal object MutationPlanEntryTarget {
    fun target(input: EntryTargetInput): Pair<String, JsonObject> {
        val parents = input.parentIds.sortedWith(cashRelationStringOrder)
        val target = buildJsonObject {
            put("parent_ids", JsonArray(parents.map(::JsonPrimitive)))
            put("members", JsonArray(parents.map { parent ->
                buildJsonObject {
                    val references = input.parentRowRefs[parent]
                    if (references != null) put("row_refs", JsonArray(references.map(::JsonPrimitive)))
                    else put("transaction_id", parent)
                }
            }))
            put("rows", JsonArray(input.rows))
            put("entries", JsonArray(input.entries))
            put("relations", JsonArray(input.relationRevisions))
            put("relation_dependencies", JsonArray(input.dependencies))
            put("final_edges", JsonArray(input.finalEdges.sortedBy { it.id }.map(::edgeJson)))
        }
        return "ie_" + cashSourceDigest(JsonPrimitive("cash-import-entry-v1"), target) to target
    }

    private fun edgeJson(edge: PlanRelationEdge) = buildJsonObject {
        put("id", edge.id); put("kind", edge.kind)
        put("primary_transaction_id", edge.primaryTransactionId)
        put("secondary_transaction_id", edge.secondaryTransactionId?.let(::JsonPrimitive) ?: JsonNull)
        put("primary_component_id", edge.primaryComponentId?.let(::JsonPrimitive) ?: JsonNull)
        put("secondary_component_id", edge.secondaryComponentId?.let(::JsonPrimitive) ?: JsonNull)
        put("primary_role", edge.primaryRole?.let(::JsonPrimitive) ?: JsonNull)
        put("secondary_role", edge.secondaryRole?.let(::JsonPrimitive) ?: JsonNull)
        put("subtype", edge.subtype)
        put("applied_amount", edge.appliedAmount?.let(::JsonPrimitive) ?: JsonNull)
        put("state", edge.state); put("active", edge.active)
    }

    /** 从当前关系上下文行构造冻结边；Python `asdict` 的金额按 `str(Decimal)` 参与摘要。 */
    fun edge(row: CashRelationSqlRow): PlanRelationEdge = PlanRelationEdge(
        row.id, row.kind, row.primary.toString(), row.secondary?.toString(), row.primaryComponent?.toString(),
        row.secondaryComponent?.toString(), row.primaryRole, row.secondaryRole, row.subtype,
        fixedCashDecimal(row.applied), row.state, row.active,
    )
}

internal fun MutationPlanEntryTarget.edge(edge: CashRelationGraphEdge): PlanRelationEdge = PlanRelationEdge(
    strictCashInteger(edge.id)?.toLong() ?: throw CashPolicyError("relation_data_invalid", emptyList()),
    edge.kind?.toString() ?: throw CashPolicyError("relation_data_invalid", emptyList()),
    edge.primaryTransactionId?.toString() ?: throw CashPolicyError("relation_data_invalid", emptyList()),
    edge.secondaryTransactionId?.toString(), edge.primaryComponentId?.toString(), edge.secondaryComponentId?.toString(),
    edge.primaryRole?.toString(), edge.secondaryRole?.toString(), edge.subtype?.toString() ?: "",
    edge.appliedAmount?.let { value ->
        if (value is CashAmount) {
            val decimal = java.math.BigDecimal(value.numericText())
            (if (value.toString().startsWith('-') && decimal.signum() == 0) "-" else "") + decimal.toPlainString()
        } else value.toString()
    }, edge.state?.toString() ?: "", edge.active as? Boolean ?: false,
)
