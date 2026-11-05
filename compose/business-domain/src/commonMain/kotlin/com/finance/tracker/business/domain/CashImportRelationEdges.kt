package com.finance.tracker.business.domain

fun cashImportEdgeMap(edge: CashRelationGraphEdge): Map<String, Any?> = linkedMapOf(
    "id" to edge.id, "kind" to edge.kind, "primary_transaction_id" to edge.primaryTransactionId,
    "secondary_transaction_id" to edge.secondaryTransactionId, "primary_component_id" to edge.primaryComponentId,
    "secondary_component_id" to edge.secondaryComponentId, "primary_role" to edge.primaryRole,
    "secondary_role" to edge.secondaryRole, "applied_amount" to edge.appliedAmount,
    "state" to edge.state, "active" to edge.active,
)

fun cashImportLink(edge: CashRelationGraphEdge, group: String? = null) = CashRelationLink(
    edge.id.toString(), edge.primaryTransactionId, edge.secondaryTransactionId, edge.state, edge.active, group)

fun cashImportClosures(edges: Collection<CashRelationGraphEdge>): Map<String, Set<String>> {
    val groups = CashRelationGroups()
    for (edge in edges) if (edge.active == true && edge.state == "confirmed" && edge.secondaryTransactionId != null)
        groups.join(edge.primaryTransactionId as String, edge.secondaryTransactionId as String)
    return groups.parents.keys.groupBy(groups::root).values.flatMap { members ->
        val all = members.toSet()
        members.map { it to all }
    }.toMap()
}

fun <I : Comparable<I>, T : Comparable<T>> cashImportRef(snapshot: CashImportPreviewSnapshot<I, T>, identifier: String): Map<String, Any?> =
    snapshot.parentOps[identifier]?.let { (it["row_refs"] as List<*>).firstOrNull()?.let { ref -> mapOf("row_ref" to ref) } }
        ?: mapOf("transaction_id" to identifier)

fun cashImportEdgeKey(edge: CashRelationGraphEdge): List<Any?> {
    val first = edge.primaryComponentId as? String
    val second = edge.secondaryComponentId as? String
    val pair = if (!first.isNullOrEmpty() && !second.isNullOrEmpty()) {
        if (edge.kind == "payment_mirror") listOf(first, second).sortedWith(cashRelationStringOrder) else listOf(first, second)
    } else listOf(edge.primaryTransactionId, edge.primaryComponentId, null)
    return listOf(edge.kind, pair)
}

