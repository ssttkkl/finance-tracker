package com.finance.tracker.business.domain

/** Overlay normalized operations on prepared preview state, preserving whole-operation business rejection. */
class CashImportPreviewOverlayRules<I : Comparable<I>, T : Comparable<T>>(
    private val recordValues: CashRecordValues, private val now: () -> T,
    private val virtualId: (String, List<Any?>) -> I,
    private val createFact: (String, Map<String, Any?>, Boolean) -> CashProjectionFact<I, T>,
    private val fundingRoleCompatible: (Any?, Any?, Any?) -> Boolean = ::cashFundingRoleCompatible,
) {
    private fun copy(value: Map<*, *>): MutableMap<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return recordValues.copyValue(value) as MutableMap<String, Any?>
    }
    private fun virtual(kind: String, vararg values: Any?): I = virtualId(kind, values.toList())
    private fun endpoint(id: String, value: Map<String, Any?>, part: Map<String, Any?>) = CashRelationEndpoint(
        part["component_id"], id, part["amount"], part["currency"], part["account_id"], value["record_type"], value["record_subtype"],
    )

    private fun oldClosure(edges: Collection<CashRelationGraphEdge>, identifier: String): Set<String> {
        val neighbors = linkedMapOf<String, MutableSet<String>>()
        for (edge in edges) if (edge.active == true && edge.state == "confirmed") {
            val first = edge.primaryTransactionId?.toString() ?: continue
            val second = edge.secondaryTransactionId?.toString() ?: continue
            neighbors.getOrPut(first) { linkedSetOf() }.add(second)
            neighbors.getOrPut(second) { linkedSetOf() }.add(first)
        }
        val reached = linkedSetOf(identifier)
        val pending = mutableListOf(identifier)
        while (pending.isNotEmpty()) for (next in neighbors[pending.removeAt(pending.lastIndex)].orEmpty())
            if (reached.add(next)) pending.add(next)
        return reached
    }

    private fun typeEffects(snapshot: CashImportPreviewSnapshot<I, T>, identifier: String, fundingParents: Map<Long, String>) {
        for ((key, edge) in snapshot.edges.toMap()) {
            if (edge.active != true || edge.state != "confirmed" || identifier !in setOf(edge.primaryTransactionId, edge.secondaryTransactionId)) continue
            try {
                validateCashRelationRoles(edge.kind,
                    snapshot.components.getValue(edge.primaryComponentId.toString()),
                    snapshot.components.getValue(edge.secondaryComponentId.toString()),
                    edge.primaryRole, edge.secondaryRole)
            } catch (failure: CashRelationFailure) {
                if (failure.code !in setOf("relation_endpoint_incompatible", "relation_role_incompatible")) throw failure
                snapshot.edges[key] = edge.copy(active = false)
                snapshot.removedRelations.add(key)
            }
        }
        for ((key, row) in snapshot.funding.toMap()) {
            if (fundingParents[row.componentId] != identifier) continue
            val part = snapshot.components[row.componentId.toString()] ?: continue
            if (part.transactionId == identifier && !fundingRoleCompatible(row.direction, part.amount, part.recordType)) {
                snapshot.funding.remove(key)
                snapshot.removedFunding.add(key)
            }
        }
    }

    fun apply(snapshot: CashImportPreviewSnapshot<I, T>) {
        val graph = snapshot.context
        val values = snapshot.values
        val operations = snapshot.operations
        val fundingParents = graph.componentVersions.associate { it.id to it.transactionId.toString() }
        for (operation in operations.toList()) {
            val identifier = operation["transaction_id"]?.toString() ?: virtual("transaction", operation["occurrence_key"]).toString()
            operation["preview_transaction_id"] = identifier
            @Suppress("UNCHECKED_CAST") val raw = operation["values"] as Map<String, Any?>
            val value = copy(raw)
            for (field in financialCashFields) if (field in value) value[field] = recordValues.normalize(field, value[field])
            if (value["record_origin"] == null) value["record_origin"] = values[identifier]?.get("record_origin") ?: "import"
            value["created_at"] = values[identifier]?.get("created_at") ?: now()
            value["source_explanation"] = operation["source_explanation"] ?: values[identifier]?.get("source_explanation") ?: emptyMap<String, Any?>()
            value["source_scope_key"] = operation["source_scope_key"]
            value["matching_record_id"] = operation["matching_record_id"]
            val existingKey = snapshot.facts[identifier]?.stableSourceKey.orEmpty()
            val incomingKey = (operation["stable_source_key"] as? List<*>)?.map { it.toString() }.orEmpty()
            value["stable_source_key"] = if (existingKey.size == 5 && incomingKey.size == 5)
                cashSourceOrderKey(value["source_type"]?.toString(), "", listOf(existingKey, incomingKey))
            else incomingKey.ifEmpty { existingKey }
            @Suppress("UNCHECKED_CAST") val parts = value["components"] as List<MutableMap<String, Any?>>
            for (part in parts) if (part["component_id"] == null)
                part["component_id"] = virtual("component", identifier, part["ordinal"]).toString()
            val typeUpdate = operation["type_changed"] == true && operation["transaction_id"] != null &&
                value["state"] == "posted" && value["record_origin"] != "manual_transfer"
            val previousValue = snapshot.values[identifier]
            val previousFact = snapshot.facts[identifier]
            val previousParts = if (typeUpdate) snapshot.components.filterValues { it.transactionId == identifier } else emptyMap()
            val previousEdges = if (typeUpdate) snapshot.edges.toMap() else emptyMap()
            val previousFunding = if (typeUpdate) snapshot.funding.toMap() else emptyMap()
            val previousRemovedRelations = if (typeUpdate) snapshot.removedRelations.toSet() else emptySet()
            val previousRemovedFunding = if (typeUpdate) snapshot.removedFunding.toSet() else emptySet()
            val closure = if (typeUpdate) oldClosure(graph.relations, identifier) else emptySet()
            @Suppress("UNCHECKED_CAST") val previous = snapshot.values[identifier]?.get("components") as? List<Map<String, Any?>>
            previous.orEmpty().forEach { snapshot.components.remove(it["component_id"].toString()) }
            snapshot.values[identifier] = value
            val issues = operation["issues"] as? List<*>
            val retained = operation["retained_issue_ids"] as? List<*>
            snapshot.facts[identifier] = createFact(identifier, value, !issues.isNullOrEmpty() || !retained.isNullOrEmpty())
            if (value["state"] == "posted") parts.forEach { snapshot.components[it["component_id"].toString()] = endpoint(identifier, value, it) }
            if (typeUpdate) try {
                typeEffects(snapshot, identifier, fundingParents)
                snapshot.build(closure)
            } catch (_: CashRelationFailure) {
                if (previousValue == null) snapshot.values.remove(identifier) else snapshot.values[identifier] = previousValue
                if (previousFact == null) snapshot.facts.remove(identifier) else snapshot.facts[identifier] = previousFact
                snapshot.components.entries.removeAll { it.value.transactionId == identifier }
                snapshot.components.putAll(previousParts)
                snapshot.edges.clear(); snapshot.edges.putAll(previousEdges)
                snapshot.funding.clear(); snapshot.funding.putAll(previousFunding)
                snapshot.removedRelations.clear(); snapshot.removedRelations.addAll(previousRemovedRelations)
                snapshot.removedFunding.clear(); snapshot.removedFunding.addAll(previousRemovedFunding)
                operations.remove(operation)
                for (row in snapshot.rows) if (row["operation_ref"] == operation["operation_ref"]) {
                    row["planned_action"] = "skip"
                    row["code"] = "relation_endpoint_incompatible"
                    row["operation_ref"] = null
                    val issue = cashImportIssue("relation_endpoint_incompatible")
                    row["issues"] = listOf(linkedMapOf("code" to issue.code, "message" to issue.message,
                        "field_path" to issue.fieldPath, "blocking_scope" to issue.blockingScope,
                        "next_action" to issue.nextAction))
                }
                continue
            }
            snapshot.parentOps[identifier] = operation
            operation["preview_components"] = parts.map { linkedMapOf("ordinal" to it["ordinal"], "component_id" to it["component_id"]) }
        }
    }
}
