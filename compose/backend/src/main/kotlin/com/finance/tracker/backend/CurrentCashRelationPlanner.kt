package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import kotlinx.serialization.json.JsonElement

/** 从当前关系和原候选证明构建整组决定，不发现新配对或写入财务事实。 */
internal class CurrentCashRelationPlanner(private val context: CashSqlContext, transactionId: Long) {
    val seedIds: Set<Long>
    val decisionGroupIds: List<String>
    private val rows: List<Map<String, Any?>>
    @Suppress("UNCHECKED_CAST") private fun maps(value: Any?) = value as List<Map<String, Any?>>
    private fun edgeMap(edge: CashRelationGraphEdge) = cashImportEdgeMap(edge.copy(appliedAmount = edge.appliedAmount?.let { cashRelationDecimal(it)!! }))
    private fun convert(value: Any?): Any? = when (value) {
        is JsonElement -> cashSourceValue(value)
        is CashStoredDecimal -> CashAmount.parse(value.text)
        is Map<*, *> -> value.entries.associate { it.key.toString() to convert(it.value) }
        is List<*> -> value.map(::convert)
        else -> value
    }
    init {
        context.requireActive()
        val relation = TRANSACTION_RELATIONS
        val candidate = CASH_RELATION_CANDIDATES
        val roots = CashRelationReadRepository(context).confirmedClosure(setOf(transactionId))
        val identifiers = sortedSetOf<Long>()
        for (batch in roots.sorted().chunked(500)) {
            val branches = listOf(relation.PRIMARY_TRANSACTION_ID, relation.SECONDARY_TRANSACTION_ID, relation.ANCHOR_TRANSACTION_ID)
                .map { context.sql.select(relation.ID).from(relation).where(relation.WORKSPACE_ID.eq(context.workspace)).and(it.`in`(batch)) }
            val ids = branches[0].union(branches[1]).union(branches[2]).union(context.sql.select(candidate.RELATION_ID).from(candidate)
                .where(candidate.WORKSPACE_ID.eq(context.workspace)).and(candidate.CANDIDATE_TRANSACTION_ID.`in`(batch)))
            identifiers += context.sql.select(relation.ID).from(relation).where(relation.WORKSPACE_ID.eq(context.workspace))
                .and(relation.ACTIVE.isTrue).and(relation.ID.`in`(ids)).fetch(relation.ID).filterNotNull()
        }
        val repository = CurrentCashRelationWriteRepository(context)
        val direct = repository.planningRows(identifiers)
        decisionGroupIds = direct.mapNotNull { it["decision_group_id"] as? String }.distinct().sortedWith(cashRelationStringOrder)
        for (batch in decisionGroupIds.chunked(500)) identifiers += context.sql.select(relation.ID).from(relation)
            .where(relation.WORKSPACE_ID.eq(context.workspace)).and(relation.DECISION_GROUP_ID.`in`(batch)).fetch(relation.ID).filterNotNull()
        @Suppress("UNCHECKED_CAST")
        rows = repository.planningRows(identifiers).map { convert(it) as Map<String, Any?> }
        val wanted = rows.flatMap { row -> listOfNotNull(row["primary_transaction_id"], row["secondary_transaction_id"], row["anchor_transaction_id"]) +
            maps(row.getValue("candidates")).map { it.getValue("candidate_transaction_id") } }.map { it.toString().toLong() }.toSet()
        val seeds = roots.toMutableSet()
        for (batch in wanted.sorted().chunked(500)) seeds += context.sql.select(CASH_TRANSACTIONS.ID).from(CASH_TRANSACTIONS)
            .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(context.workspace)).and(CASH_TRANSACTIONS.ID.`in`(batch)).fetch(CASH_TRANSACTIONS.ID).filterNotNull()
        seedIds = seeds
    }

    fun build(snapshot: CashImportPreviewSnapshot, accounts: Map<String, Map<String, Any?>>): List<MutableMap<String, Any?>> {
        val removed = snapshot.parentOps.values.filter { (it["current"] as? Map<*, *>)?.get("state") == "draft" }.flatMap { operation ->
            val current = operation.getValue("current") as Map<*, *>
            val values = operation.getValue("values") as Map<*, *>
            val retained = maps(values["components"]).mapNotNull { it["component_id"]?.toString() }.toSet()
            maps(current["components"]).mapNotNull { it["component_id"]?.toString() }.filter { it !in retained }
        }.toSet()
        for ((id, edge) in snapshot.edges.toMap()) {
            val first = edge.primaryComponentId in removed; val second = edge.secondaryComponentId in removed
            if (first || second) snapshot.edges[id] = edge.copy(primaryComponentId = if (first) null else edge.primaryComponentId,
                primaryRole = if (first) null else edge.primaryRole, secondaryComponentId = if (second) null else edge.secondaryComponentId,
                secondaryRole = if (second) null else edge.secondaryRole)
        }
        val pending = snapshot.context.issueVersions.map { it.transactionId.toString() }.toSet()
        for ((id, fact) in snapshot.facts.toMap()) {
            val operation = snapshot.parentOps[id]
            val attention = id in pending || !(operation?.get("issues") as? List<*>).isNullOrEmpty() || !(operation?.get("retained_issue_ids") as? List<*>).isNullOrEmpty()
            if (fact.needsAttention != attention) snapshot.facts[id] = fact.withAttention(attention)
        }
        val grouped = rows.groupBy { it["decision_group_id"]?.let { group -> "group" to group } ?: ("relation" to it.getValue("id")) }
        val result = mutableListOf<MutableMap<String, Any?>>()
        for (members in grouped.values) {
            val edges = members.map { snapshot.edges.getValue(it.getValue("id").toString().toBigInteger()) }
            val active = edges.filter { it.active == true }
            if (active.isEmpty() || active.all { it.state == "ignored" }) continue
            val parents = edges.flatMap { listOfNotNull(it.primaryTransactionId, it.secondaryTransactionId) }
                .map { it.toString() }.filter { it in snapshot.facts }.toSet().sortedWith(cashRelationStringOrder)
            val dependencies = members.flatMap { row -> listOfNotNull(row["primary_transaction_id"], row["secondary_transaction_id"]) +
                maps(row.getValue("candidates")).map { it.getValue("candidate_transaction_id") } }.map { it.toString() }.toSet()
            val fields = listOf("state", "record_type", "record_subtype", "source_type", "source_scope_key", "source_explanation", "occurred_at", "amount", "currency", "cash_granularity", "components")
            val profile = dependencies.filter { it in snapshot.values }.associateWith { parent -> fields.associateWith { snapshot.values.getValue(parent)[it] } }
            val suggestion = "sg_" + cashImportDigest("cash-current-relation-v1", members, snapshot.context.token(), profile).take(40)
            val state = active.map { it.state }.distinct().singleOrNull() ?: "draft"
            val group = linkedMapOf<String, Any?>("suggestion_id" to suggestion, "origin" to "current", "changed" to false,
                "action" to mapOf("confirmed" to "accept", "draft" to "defer", "ignored" to "ignore").getValue(state.toString()),
                "edges" to edges.map(::edgeMap), "expected_record_edges" to edges.map(::edgeMap),
                "references" to CashRecordPolicy.copyValue(members),
                "evidence" to members.associate { it.getValue("id").toString() to CashRecordPolicy.copyValue(it["evidence"]) },
                "anchors" to members.associate { row -> row.getValue("id").toString() to row["anchor_component_id"]?.toString()?.takeIf { it !in removed } },
                "parent_ids" to parents, "old_parent_ids" to parents, "member_refs" to parents.map { mapOf("transaction_id" to it) },
                "allowed_actions" to if (active.all { it.state == "confirmed" }) listOf("remove") else listOf("defer", "ignore", "remove"),
                "candidates" to emptyList<Any>(), "candidate_id" to null,
                "dependency_parent_ids" to members.flatMap { maps(it.getValue("candidates")) }.map { it.getValue("candidate_transaction_id").toString() }.distinct().sortedWith(cashRelationStringOrder))
            if (active.size == edges.size && active.none { it.state == "confirmed" }) {
                val (choices, direct) = choices(snapshot, suggestion, members, edges)
                group["candidates"] = choices
                if (direct != null) group["direct_confirmation"] = direct
                if (choices.isNotEmpty() || direct != null) group["allowed_actions"] = listOf("accept") + (group.getValue("allowed_actions") as List<*>)
            }
            group["relation_ids"] = edges.map { it.id.toString() }
            group["split_field_diffs"] = context.entries.splitFieldDiffs(snapshot.build(additions = edges.map { it.copy(active = false) }).projections).map { diff ->
                mapOf("field" to diff.field, "current" to cashSourceValue(diff.current), "restored" to cashSourceValue(diff.restored),
                    "member_refs" to diff.memberIds.map { mapOf("transaction_id" to it.toString()) })
            }
            result += group
        }
        return result
    }

    private data class Variant(val edge: CashRelationGraphEdge, val proof: CashRelationEvidence, val direct: Boolean)
    private fun proofMap(proof: CashRelationEvidence) = mapOf("primary_original_expense" to proof.primaryOriginalExpense,
        "secondary_original_expense" to proof.secondaryOriginalExpense, "investment_refund_hard_key" to proof.investmentRefundHardKey, "p2p_return_hard_key" to proof.p2pReturnHardKey)
    private fun choices(snapshot: CashImportPreviewSnapshot, suggestion: String, rows: List<Map<String, Any?>>, edges: List<CashRelationGraphEdge>): Pair<List<Map<String, Any?>>, Map<String, Any?>?> {
        val options = rows.zip(edges).map { (row, edge) -> variants(snapshot, row, edge) }
        if (options.any { it.isEmpty() }) return emptyList<Map<String, Any?>>() to null
        val choices = mutableListOf<Map<String, Any?>>()
        var direct: Map<String, Any?>? = null
        val seen = mutableSetOf<String>(); val indices = IntArray(options.size); var combinations = 0
        while (combinations++ < 2000) {
            val values = options.mapIndexed { index, variants -> variants[indices[index]] }
            val additions = values.map { it.edge.copy(state = "confirmed") }; val keys = additions.map(::edgeMap)
            if (seen.add(cashImportDigest(keys))) {
                val build = try { snapshot.build(additions = additions) } catch (_: CashRelationFailure) { null }
                val parents = additions.flatMap { listOfNotNull(it.primaryTransactionId, it.secondaryTransactionId) }.map { it.toString() }.distinct().sortedWith(cashRelationStringOrder)
                val projection = build?.projections?.firstOrNull { item -> item.memberIds.any { it.toString() in parents } }
                if (projection != null) {
                    val choice = linkedMapOf<String, Any?>("candidate_id" to "candidate_" + cashImportDigest(suggestion, keys).take(40), "edges" to keys,
                        "evidence" to values.associate { it.edge.id.toString() to proofMap(it.proof) }, "parent_ids" to parents,
                        "member_refs" to parents.map { mapOf("transaction_id" to it) },
                        "display" to mapOf("occurred_at" to projection.occurredAt, "amount" to projection.netAmount,
                            "currency" to projection.primaryRecord.currency, "counterparty" to projection.primaryRecord.counterparty,
                            "note" to projection.primaryRecord.note, "accounts" to emptyList<Any>()))
                    if (values.all { it.direct }) direct = choice
                    choices += choice
                    if (choices.size == 20) break
                }
            }
            var position = indices.lastIndex
            while (position >= 0 && ++indices[position] == options[position].size) { indices[position] = 0; position-- }
            if (position < 0) break
        }
        return choices to direct
    }
    private fun variants(snapshot: CashImportPreviewSnapshot, row: Map<String, Any?>, edge: CashRelationGraphEdge): List<Variant> {
        val anchor = row["anchor_component_id"]?.toString(); val fixed = snapshot.components[anchor] ?: return emptyList()
        if (fixed.transactionId != row.getValue("anchor_transaction_id").toString()) return emptyList()
        val result = mutableListOf<Variant>()
        fun add(primary: CashRelationEndpoint?, secondary: CashRelationEndpoint?, direct: Boolean = false) {
            if (primary == null || secondary == null || primary.transactionId == secondary.transactionId) return
            val roles = try { inferCashRelationRoles(edge.kind, primary, secondary) } catch (_: CashRelationFailure) { return }
            result += Variant(edge.copy(primaryTransactionId = primary.transactionId, secondaryTransactionId = secondary.transactionId,
                primaryComponentId = primary.componentId, secondaryComponentId = secondary.componentId, primaryRole = roles.first, secondaryRole = roles.second),
                CashRelationEvidence(), direct)
        }
        val revisions = snapshot.context.transactionRevisions.toMap()
        val candidates = maps(row.getValue("candidates"))
        val valid = candidates.mapNotNull { candidate ->
            val part = snapshot.components[candidate["candidate_component_id"]?.toString()]
            if (candidate["validity"] != "valid" || revisions[candidate.getValue("candidate_transaction_id").toString().toLong()] != candidate["expected_row_revision"] ||
                part == null || part.transactionId != candidate.getValue("candidate_transaction_id").toString()) null else candidate to part
        }
        val opposite = if (anchor == edge.primaryComponentId) edge.secondaryComponentId else edge.primaryComponentId
        if (candidates.isEmpty() || valid.any { it.second.componentId == opposite }) add(snapshot.components[edge.primaryComponentId], snapshot.components[edge.secondaryComponentId], true)
        for ((_, part) in valid) {
            val pair = if (edge.kind in setOf("refund_offset", "transfer_pair")) {
                if (cashRelationDecimal(fixed.amount)!!.arithmeticValue().signum() < 0) fixed to part else part to fixed
            } else if (anchor == edge.secondaryComponentId) part to fixed else fixed to part
            add(pair.first, pair.second)
        }
        return result
    }
    fun manualGroups(snapshot: CashImportPreviewSnapshot, sourceId: String, additions: List<Map<String, Any?>>): List<MutableMap<String, Any?>> = additions.map { item ->
        val target = item.getValue("target_transaction_id") as String
        if (sourceId == target || sourceId !in snapshot.values || target !in snapshot.values)
            throw CashPolicyError("relation_endpoint_incompatible", emptyList())
        if (snapshot.context.transactionRevisions.toMap()[target.toLong()]?.toBigInteger() != item.getValue("expected_target_revision").toString().toBigInteger())
            throw MutationPlanFailure("record_changed")
        if (listOf(sourceId, target).any { snapshot.facts.getValue(it).state != "posted" })
            throw CashPolicyError("relation_endpoint_incompatible", emptyList())
        var primary = snapshot.components[item.getValue("anchor_component_id") as String]
        var secondary = snapshot.components[item.getValue("target_component_id") as String]
        if (primary == null || secondary == null || primary.transactionId != sourceId || secondary.transactionId != target)
            throw CashPolicyError("relation_endpoint_incompatible", emptyList())
        if (item["kind"] in setOf("refund_offset", "transfer_pair") && cashRelationDecimal(primary.amount)!!.arithmeticValue().signum() > 0) {
            val first = primary; primary = secondary; secondary = first
        }
        var edge = CashRelationGraphEdge(cashImportVirtual("manual-relation", item["kind"], primary.componentId, secondary.componentId),
            item["kind"], primary.transactionId, secondary.transactionId, primary.componentId, secondary.componentId, null, null,
            state = "confirmed")
        val proof = CashRelationEvidence()
        val roles: Pair<String, String>
        try { roles = inferCashRelationRoles(edge.kind, primary, secondary) }
        catch (failure: CashRelationFailure) { throw CashPolicyError(failure.code, emptyList()) }
        edge = edge.copy(primaryRole = roles.first, secondaryRole = roles.second)
        val parents = listOf(primary.transactionId as String, secondary.transactionId as String).distinct().sortedWith(cashRelationStringOrder)
        @Suppress("UNCHECKED_CAST") val common = item["common_fields"] as? Map<String, Any?> ?: emptyMap()
        if (common.keys.any { it !in commonCashFields }) throw CashPolicyError("field_not_editable", emptyList())
        for ((field, value) in common) {
            val normalized = CashRecordPolicy.normalize(field, value)
            val allowed = parents.map { parent ->
                @Suppress("UNCHECKED_CAST") val public = snapshot.entryPublic[parent]?.get("values") as? Map<String, Any?> ?: snapshot.values.getValue(parent)
                public[field]
            }
            val clear = when (field) { "counterparty_account_attrs" -> emptyList<Any>(); "category_id" -> null; else -> "" }
            if (!CashRecordPolicy.valuesEqual(normalized, clear) && allowed.none { CashRecordPolicy.valuesEqual(normalized, it) })
                throw CashPolicyError("common_field_conflict", listOf(field))
        }
        linkedMapOf<String, Any?>("suggestion_id" to "sg_" + cashImportDigest("manual-association-v1", item, snapshot.context.token()).take(40),
            "origin" to "manual", "action" to "accept", "edges" to listOf(edgeMap(edge)),
            "evidence" to mapOf(edge.id.toString() to proofMap(proof)), "anchors" to mapOf(edge.id.toString() to primary.componentId),
            "parent_ids" to parents, "member_refs" to parents.map { mapOf("transaction_id" to it) },
            "allowed_actions" to listOf("accept"), "candidates" to emptyList<Any>(), "candidate_id" to null).also {
            if ("common_fields" in item) it["common_fields"] = CashRecordPolicy.copyValue(item["common_fields"])
        }
    }
}
