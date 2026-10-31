package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.CASH_SOURCE_BINDINGS
import com.finance.tracker.backend.persistence.generated.tables.references.CASH_TRANSACTIONS
import java.math.BigInteger
import java.time.Instant
import kotlinx.serialization.json.JsonElement

internal data class CashImportPreviewSnapshot(
    val context: CashRelationSqlContext,
    val values: MutableMap<String, MutableMap<String, Any?>>,
    val facts: MutableMap<String, CashProjectionFact>,
    val components: MutableMap<String, CashRelationEndpoint>,
    val edges: MutableMap<BigInteger, CashRelationGraphEdge>,
    val funding: MutableMap<Long, CashFundingSqlRow>,
    val parentOps: MutableMap<String, MutableMap<String, Any?>>,
    val entryPublic: MutableMap<String, Map<String, Any?>>,
    val removedRelations: MutableSet<BigInteger>,
    val removedFunding: MutableSet<Long>,
    val operations: MutableList<MutableMap<String, Any?>>,
    val rows: MutableList<MutableMap<String, Any?>>,
) {
    private fun withFunding(fact: CashProjectionFact, fundingId: Long?): CashProjectionFact = CashProjectionFact.create(
        fact.id, fact.accountId, fact.occurredAt, fact.amount?.toString(), fact.currency, fact.counterparty,
        fact.categoryId, fact.note, fact.sourceType, fact.recordId, fundingId, fact.cashGranularity,
        fact.state, fact.createdAt, fact.needsAttention,
    )

    fun build(parentIds: Set<String>? = null, additions: List<CashRelationGraphEdge> = emptyList()): CashProjectionBuild {
        val parents = parentIds ?: facts.keys
        val selectedEdges = (if (parentIds == null) edges.values.toList() else edges.values.filter {
            it.primaryTransactionId in parents || it.secondaryTransactionId in parents
        }).associateByTo(linkedMapOf()) { strictCashInteger(it.id)!! }.apply {
            additions.forEach { put(strictCashInteger(it.id)!!, it) }
        }.values.toList()
        val pending = selectedEdges.filter { it.active == true && it.state == "draft" }
            .flatMap { listOfNotNull(it.primaryTransactionId?.toString(), it.secondaryTransactionId?.toString()) }.toSet()
        val fundingRows = funding.values.filter { row ->
            val part = components[row.componentId.toString()]
            part?.transactionId in parents
        }
        val fundingByParent = fundingRows.mapNotNull { row ->
            components[row.componentId.toString()]?.transactionId?.toString()?.let { it to row.id }
        }.toMap()
        val formal = parents.mapNotNull { id -> facts[id]?.takeIf { it.state == "posted" }?.let { fact ->
            withFunding(fact.withAttention(fact.needsAttention || id in pending), fundingByParent[id])
        } }
        val parts = parents.flatMap { id ->
            @Suppress("UNCHECKED_CAST") val listed = values[id]?.get("components") as? List<Map<String, Any?>>
            listed.orEmpty().mapNotNull { components[it["component_id"].toString()] }
        }
        val componentIds = parts.map { it.componentId }.toSet()
        val complete = buildCashRelationGraph(formal, parts, selectedEdges,
            fundingRows.map { it.componentId.toString() }.filter { it in componentIds })
        val drafts = buildCashProjections(parents.mapNotNull { facts[it]?.takeIf { fact -> fact.state == "draft" } }, emptyList())
        return CashProjectionBuild(complete.projections + drafts.projections)
    }
}

/** 在预览的同一只读上下文中构建预计财务事实；真实写入由确认事务另行处理。 */
internal class CashImportPreviewSnapshotBuilder(private val context: CashSqlContext, private val now: () -> Instant = context.now) {
    private val sql get() = context.sql
    private val workspace get() = context.workspace
    private fun copy(value: Map<*, *>): MutableMap<String, Any?> {
        @Suppress("UNCHECKED_CAST") val result = CashRecordPolicy.copyValue(value) as MutableMap<String, Any?>
        return result
    }
    private fun json(value: JsonElement?): Any? = cashSourceValue(value)
    private fun amount(value: Any?): String? = when (value) {
        null -> null
        is CashAmount -> value.toString()
        is CashStoredDecimal -> value.text
        else -> value.toString()
    }
    private fun virtual(kind: String, vararg values: Any?): BigInteger =
        BigInteger.ONE.shiftLeft(64).add(BigInteger(sourceDigest("cash-import-virtual-v1", kind, values.toList()).take(32), 16))

    private fun scopes(ids: Set<Long>): Map<Pair<String, String>, String> {
        val table = CASH_SOURCE_BINDINGS
        val grouped = linkedMapOf<Pair<String, String>, MutableMap<String, MutableSet<String>>>()
        for (batch in ids.sorted().chunked(400)) {
            sql.select(table.TRANSACTION_ID, table.SOURCE_TYPE, table.SOURCE_SCOPE_KIND, table.SOURCE_SCOPE_KEY)
                .from(table).where(table.WORKSPACE_ID.eq(workspace)).and(table.TRANSACTION_ID.`in`(batch)).fetch().forEach { row ->
                    val key = row[table.TRANSACTION_ID]!!.toString() to row[table.SOURCE_TYPE]!!
                    grouped.getOrPut(key) { linkedMapOf() }.getOrPut(row[table.SOURCE_SCOPE_KIND]!!) { linkedSetOf() }
                        .add(row[table.SOURCE_SCOPE_KEY]!!)
                }
        }
        return grouped.mapValues { (key, kinds) ->
            val account = kinds["account"].orEmpty()
            val file = kinds["file_local"].orEmpty()
            when {
                account.size == 1 -> account.first()
                account.isNotEmpty() -> "ambiguous:${key.first}"
                file.size == 1 -> "file:${file.first()}"
                else -> "ambiguous:${key.first}"
            }
        }
    }

    private fun currentValues(ids: Set<Long>, scopes: Map<Pair<String, String>, String>): MutableMap<String, MutableMap<String, Any?>> {
        val table = CASH_TRANSACTIONS
        val result = linkedMapOf<String, MutableMap<String, Any?>>()
        for (batch in ids.sorted().chunked(400)) {
            sql.selectFrom(table).where(table.WORKSPACE_ID.eq(workspace)).and(table.ID.`in`(batch)).fetch().forEach { row ->
                val id = row[table.ID]!!.toString()
                val source = row[table.SOURCE_TYPE]
                result[id] = linkedMapOf(
                    "counterparty" to row[table.COUNTERPARTY]!!,
                    "counterparty_account" to row[table.COUNTERPARTY_ACCOUNT]!!,
                    "counterparty_account_attrs" to json(row[table.COUNTERPARTY_ACCOUNT_ATTRS]),
                    "note" to row[table.NOTE]!!,
                    "category_id" to row[table.CATEGORY_ID],
                    "record_type" to row[table.RECORD_TYPE],
                    "record_subtype" to row[table.RECORD_SUBTYPE],
                    "source_payload" to json(row[table.SOURCE_PAYLOAD]),
                    "amount" to row[table.AMOUNT]?.let { CashRecordPolicy.normalize("amount", it.text) },
                    "currency" to row[table.CURRENCY],
                    "occurred_at" to row[table.OCCURRED_AT],
                    "account_id" to row[table.ACCOUNT_ID]?.toString(),
                    "components" to mutableListOf<MutableMap<String, Any?>>(),
                    "cash_granularity" to row[table.CASH_GRANULARITY]!!,
                    "source_type" to source,
                    "record_id" to row[table.RECORD_ID]!!,
                    "state" to row[table.STATE]!!,
                    "created_at" to row[table.CREATED_AT]!!,
                    "record_origin" to row[table.RECORD_ORIGIN]!!,
                    "manual_transfer_id" to row[table.MANUAL_TRANSFER_ID],
                    "source_explanation" to extractCashSourceExplanation(json(row[table.RELATION_METADATA])),
                    "source_scope_key" to scopes[id to source].let { it ?: "unbound:$id" },
                    "matching_record_id" to "occurrence:$id",
                )
            }
        }
        return result
    }

    private fun fact(id: String, value: Map<String, Any?>, attention: Boolean): CashProjectionFact = CashProjectionFact.create(
        BigInteger(id), (value["account_id"] as? String)?.toLong(), value["occurred_at"] as? Instant,
        amount(value["amount"]), value["currency"] as? String, value["counterparty"] as? String ?: "",
        value["category_id"] as? String, value["note"] as? String ?: "", value["source_type"] as? String,
        value["record_id"] as? String ?: "", cashGranularity = value["cash_granularity"] as String,
        state = value["state"] as String, createdAt = value["created_at"] as Instant, needsAttention = attention,
    )
    private fun publicFact(fact: CashProjectionFact, values: Map<String, Any?>): CashProjectionFact = CashProjectionFact.create(
        fact.id, fact.accountId, fact.occurredAt, amount(fact.amount), fact.currency,
        values["counterparty"] as String, values["category_id"] as? String, values["note"] as String,
        fact.sourceType, fact.recordId, fact.fundingRelationId, fact.cashGranularity, fact.state, fact.createdAt, fact.needsAttention,
    )
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

    private fun typeEffects(snapshot: CashImportPreviewSnapshot, identifier: String, fundingParents: Map<Long, String>) {
        for ((key, edge) in snapshot.edges.toMap()) {
            if (edge.active != true || edge.state != "confirmed" || identifier !in setOf(edge.primaryTransactionId, edge.secondaryTransactionId)) continue
            try {
                validateCashRelationRoles(edge.kind, edge.subtype,
                    snapshot.components.getValue(edge.primaryComponentId.toString()),
                    snapshot.components.getValue(edge.secondaryComponentId.toString()),
                    edge.primaryRole, edge.secondaryRole)
            } catch (failure: CashRelationFailure) {
                if (failure.code != "relation_endpoint_incompatible") throw failure
                snapshot.edges[key] = edge.copy(active = false)
                snapshot.removedRelations.add(key)
            }
        }
        for ((key, row) in snapshot.funding.toMap()) {
            if (fundingParents[row.componentId] != identifier) continue
            val part = snapshot.components[row.componentId.toString()] ?: continue
            if (part.transactionId == identifier && !cashFundingRoleCompatible(row.direction, part.amount, part.recordType)) {
                snapshot.funding.remove(key)
                snapshot.removedFunding.add(key)
            }
        }
    }

    fun build(prepared: PreparedCashImportPreview, rows: List<Map<String, Any?>>): CashImportPreviewSnapshot {
        context.requireActive()
        val graph = prepared.context
        val values = currentValues(graph.transactionIds, scopes(graph.transactionIds))
        val current = values.keys.map(String::toLong).toSet()
        for (part in graph.componentVersions) {
            val row = values[part.transactionId.toString()] ?: continue
            @Suppress("UNCHECKED_CAST") val components = row["components"] as MutableList<MutableMap<String, Any?>>
            components += linkedMapOf("component_id" to part.id.toString(), "account_id" to part.accountId?.toString(),
                "amount" to part.amount?.let { CashRecordPolicy.normalize("amount", it) }, "currency" to part.currency,
                "ordinal" to part.ordinal)
        }
        val facts = graph.facts.associateByTo(linkedMapOf()) { it.id.toString() }
        val components = graph.components.associateByTo(linkedMapOf()) { it.componentId as String }
        val edges = graph.relations.associateByTo(linkedMapOf()) { strictCashInteger(it.id)!! }
        val funding = graph.fundingVersions.associateByTo(linkedMapOf()) { it.id }
        val operations = prepared.operations.mapTo(mutableListOf(), ::copy)
        val snapshot = CashImportPreviewSnapshot(graph, values, facts, components, edges, funding, linkedMapOf(),
            linkedMapOf(), linkedSetOf(), linkedSetOf(), operations, rows.mapTo(mutableListOf(), ::copy))
        for ((id, value) in values) if (value["state"] == "draft" && id.toLong() in graph.activeTransactionIds)
            snapshot.facts[id] = fact(id, value, true)
        val fundingParents = graph.componentVersions.associate { it.id to it.transactionId.toString() }
        for (operation in operations.toList()) {
            val identifier = operation["transaction_id"]?.toString() ?: virtual("transaction", operation["occurrence_key"]).toString()
            operation["preview_transaction_id"] = identifier
            @Suppress("UNCHECKED_CAST") val raw = operation["values"] as Map<String, Any?>
            val value = copy(raw)
            for (field in financialCashFields) if (field in value) value[field] = CashRecordPolicy.normalize(field, value[field])
            value.putIfAbsent("record_origin", values[identifier]?.get("record_origin") ?: "import")
            value["created_at"] = values[identifier]?.get("created_at") ?: now()
            value["source_explanation"] = operation["source_explanation"] ?: values[identifier]?.get("source_explanation") ?: emptyMap<String, Any?>()
            value["source_scope_key"] = operation["source_scope_key"]
            value["matching_record_id"] = operation["matching_record_id"]
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
            snapshot.facts[identifier] = fact(identifier, value, !issues.isNullOrEmpty() || !retained.isNullOrEmpty())
            if (value["state"] == "posted") parts.forEach { snapshot.components[it["component_id"].toString()] = endpoint(identifier, value, it) }
            if (typeUpdate) try {
                typeEffects(snapshot, identifier, fundingParents)
                snapshot.build(closure)
            } catch (_: CashRelationFailure) {
                if (previousValue == null) snapshot.values.remove(identifier) else snapshot.values[identifier] = previousValue
                if (previousFact == null) snapshot.facts.remove(identifier) else snapshot.facts[identifier] = previousFact
                snapshot.components.entries.removeIf { it.value.transactionId == identifier }
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
        val (members, entries) = context.entries.forTransactions(current)
        val patches = linkedMapOf<String, MutableMap<String, Any?>>()
        for ((identifier, operation) in snapshot.parentOps) {
            val member = identifier.toLongOrNull()?.let(members::get) ?: continue
            @Suppress("UNCHECKED_CAST") val edit = operation["entry_edit"] as? Map<String, Any?>
            @Suppress("UNCHECKED_CAST") val patch = edit?.get("patch") as? Map<String, Any?>
            if (patch != null) patches.getOrPut(member.entryId) { linkedMapOf() }.putAll(patch)
        }
        for ((identifier, existingFact) in snapshot.facts.toMap()) {
            val member = identifier.toLongOrNull()?.let(members::get)
            val entry = member?.let { entries[it.entryId] } ?: continue
            val root = snapshot.values[entry.rootTransactionId.toString()] ?: snapshot.values.getValue(identifier)
            val public = linkedMapOf<String, Any?>()
            for (field in commonCashFields) public[field] = if (field in entry.manualFields) json(entry.values[field]) else root[field]
            public.putAll(patches[entry.id].orEmpty())
            snapshot.entryPublic[identifier] = linkedMapOf("entry_id" to entry.id, "values" to public,
                "manual_fields" to (entry.manualFields + patches[entry.id].orEmpty().keys).toSet().sortedWith(::pythonStringOrder))
            snapshot.facts[identifier] = publicFact(existingFact, public)
        }
        for ((identifier, operation) in snapshot.parentOps) if (identifier !in snapshot.entryPublic) {
            val public = linkedMapOf<String, Any?>()
            for (field in commonCashFields) public[field] = snapshot.values.getValue(identifier)[field]
            @Suppress("UNCHECKED_CAST") val edit = operation["entry_edit"] as? Map<String, Any?>
            @Suppress("UNCHECKED_CAST") val patch = edit?.get("patch") as? Map<String, Any?>
            public.putAll(patch.orEmpty())
            @Suppress("UNCHECKED_CAST") val manual = operation["manual_fields"] as? List<String>
            snapshot.entryPublic[identifier] = linkedMapOf("entry_id" to "new:$identifier", "values" to public,
                "manual_fields" to (manual.orEmpty() + patch.orEmpty().keys).toSet().sortedWith(::pythonStringOrder))
            snapshot.facts[identifier] = publicFact(snapshot.facts.getValue(identifier), public)
        }
        return snapshot
    }
}
