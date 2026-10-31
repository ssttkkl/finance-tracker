package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.math.BigInteger
import kotlinx.serialization.json.JsonElement

/** 导入及维护共用的关系组落账；外层管理授权、锁、投影和事务提交。 */
internal class CashPlanRelationWriter(private val context: CashSqlContext, private val userId: String) {
    @Suppress("UNCHECKED_CAST") private fun map(value: Any?) = value as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun maps(value: Any?) = value as List<Map<String, Any?>>
    private fun list(value: Any?) = value as List<*>
    private fun ref(value: Any?) = value as String
    private fun value(value: Any?) = if (value is JsonElement) cashSourceValue(value) else value
    private val limit = BigInteger.ONE.shiftLeft(63)
    private fun parent(value: Any?, identity: Map<String, String>): String? = value?.let {
        val result = identity[it] ?: ref(it)
        if (BigInteger(result).signum() <= 0 || BigInteger(result) >= limit) relationFailure("relation_endpoint_incomplete")
        result
    }
    private fun part(value: Any?, components: Map<String, String>): String? = value?.let { components[it] ?: ref(it) }
    private fun edge(value: Map<String, Any?>): CashRelationGraphEdge = CashRelationGraphEdge(value.getValue("id"), value.getValue("kind"),
        value.getValue("primary_transaction_id"), value["secondary_transaction_id"], value["primary_component_id"], value["secondary_component_id"],
        value["primary_role"], value["secondary_role"], value.getValue("subtype"), value["applied_amount"]?.let {
            if (it is CashAmount || it is CashDecimalLiteral) it else CashDecimalLiteral(it.toString())
        }, value.getValue("state"), value.getValue("active"))
    private fun key(edge: CashRelationGraphEdge) = strictCashInteger(edge.id)!!
    private fun proof(group: Map<String, Any?>, id: Any?) = map(map(group["evidence"]).getValue(id.toString()))
    private fun evidence(value: Map<String, Any?>) = CashRelationEvidence(value.getOrDefault("primary_original_expense", false),
        value.getOrDefault("secondary_original_expense", false), value.getOrDefault("investment_refund_hard_key", false), value.getOrDefault("p2p_return_hard_key", false))
    private fun amount(value: Any?): CashAmount = when (value) {
        null -> CashAmount.parse("0")
        is CashStoredDecimal -> CashAmount.parse(value.text)
        is CashAmount -> value
        is CashDecimalLiteral -> CashAmount.parse(value.text)
        else -> CashAmount.parse(value.toString())
    }

    fun saveGroups(plan: Map<String, Any?>, fresh: Map<String, Any?>, identity: Map<String, String>, components: Map<String, String>,
        affected: MutableSet<Long>, manual: Set<String>, beforeGroup: (List<Map<String, Any?>>) -> Unit = {}): List<Map<String, Any?>> {
        context.requireActive()
        val current = maps(fresh["relation_groups"]).associateBy { it["suggestion_id"] }
        val batches = maps(plan["relation_groups"]).groupBy { it.getOrDefault("commit_group_id", it.getValue("suggestion_id")) as String }
        val results = mutableListOf<Map<String, Any?>>()
        for ((commitId, members) in batches) {
            beforeGroup(members)
            if (members.any { it["action"] == "skip" }) {
                for (group in members) {
                    val code = group.getOrDefault("code", "relation_save_failed") as String
                    results += mapOf("result_kind" to "relation", "item_ref" to group["suggestion_id"], "outcome" to "failed", "code" to code)
                    val parents = list(group["parent_ids"]).map { BigInteger(identity[it] ?: ref(it)) }.filter { it < limit }.map { it.longValueExact() }.toSet()
                    relationProblem(parents, commitId, code)
                    affected += parents
                }
                continue
            }
            val existing = existingGroups(members, identity, components)
            if (existing != null) {
                results += members.map { mapOf("result_kind" to "relation", "item_ref" to it["suggestion_id"], "outcome" to "duplicate", "relation_id" to existing.getValue(ref(it["suggestion_id"])).toString()) }
                continue
            }
            for (group in members) {
                val updated = current[group["suggestion_id"]]
                if (updated == null || updated["action"] != group["action"] || !CashRecordPolicy.valuesEqual(updated["edges"], group["edges"])) relationFailure("relation_context_changed")
            }
            val written = writeGroup(members, identity, components, commitId, manual)
            for (group in members) {
                results += mapOf("result_kind" to "relation", "item_ref" to group["suggestion_id"], "outcome" to
                    mapOf("accept" to "confirmed", "defer" to "deferred", "ignore" to "ignored").getValue(ref(group["action"])), "relation_id" to written.getValue(ref(group["suggestion_id"])).toString())
                affected += list(group["parent_ids"]).map { (identity[it] ?: ref(it)).toLong() }
            }
        }
        return results
    }

    fun existingGroups(groups: List<Map<String, Any?>>, identity: Map<String, String>, components: Map<String, String>): Map<String, Long>? {
        val additions = mutableListOf<Pair<Map<String, Any?>, CashRelationGraphEdge>>()
        for (group in groups) {
            if (group["action"] == "skip") return null
            for (raw in maps(group["edges"])) {
                val edge = edge(raw)
                val first = identity[edge.primaryTransactionId] ?: edge.primaryTransactionId as String
                val second = edge.secondaryTransactionId?.let { identity[it] ?: ref(it) }
                if (BigInteger(first) >= limit || second != null && BigInteger(second) >= limit) return null
                additions += group to edge.copy(primaryTransactionId = first, secondaryTransactionId = second,
                    primaryComponentId = part(edge.primaryComponentId, components), secondaryComponentId = part(edge.secondaryComponentId, components))
            }
        }
        val parents = additions.flatMap { listOfNotNull(it.second.primaryTransactionId, it.second.secondaryTransactionId) }.map { it.toString().toLong() }.toSet()
        val reader = CashRelationReadRepository(context)
        val repository = CurrentCashRelationWriteRepository(context)
        val rows = reader.graphRowsForTransactions(parents).associate { cashImportEdgeKey(it.edge()) to repository.get(it.id) }
        val result = linkedMapOf<String, Long>()
        val matched = mutableSetOf<Long>()
        val joint = mutableSetOf<String>()
        for ((group, edge) in additions) {
            val found = rows[cashImportEdgeKey(edge)] ?: return null
            val expected = cashImportEdgeMap(edge)
            for (field in listOf("kind", "subtype", "state", "active", "primary_transaction_id", "secondary_transaction_id", "primary_component_id", "secondary_component_id", "primary_role", "secondary_role")) {
                val actual = if (field.endsWith("_id")) found[field]?.toString() else found[field]
                if (actual != expected[field]) return null
            }
            val anchor = part(map(group["anchors"])[edge.id.toString()], components)
            val anchorParent = if (anchor == edge.primaryComponentId) edge.primaryTransactionId else edge.secondaryTransactionId
            if (amount(found["applied_amount"]) != amount(edge.appliedAmount) || found["anchor_component_id"]?.toString() != anchor ||
                found["anchor_transaction_id"]?.toString() != anchorParent || !CashRecordPolicy.valuesEqual(value(found["evidence"]), proof(group, edge.id))) return null
            if (edge.state == "draft") {
                val expectedCandidates = candidateValues(group, edge, anchorParent as String?, identity, components)
                fun candidateKey(value: Map<String, Any?>) = listOf(value["candidate_transaction_id"]?.toString(), value["candidate_component_id"]?.toString(),
                    value["expected_row_revision"].toString(), sourceDigest(value(value["evidence"])))
                val candidates = maps(found["candidates"])
                if (candidates.any { it["validity"] != "valid" } || expectedCandidates.map(::candidateKey).toSet() != candidates.map(::candidateKey).toSet()) return null
            }
            val id = found["id"] as Long
            matched += id
            (found["decision_group_id"] as String?)?.takeIf { it.isNotEmpty() }?.let(joint::add)
            result.putIfAbsent(group["suggestion_id"] as String, id)
        }
        if (reader.graphRowsForGroups(joint.toList()).any { it.id !in matched || !it.active }) return null
        return result
    }

    fun writeGroup(members: List<Map<String, Any?>>, identity: Map<String, String>, components: Map<String, String>, commitId: String,
        manual: Set<String>, unavailable: Set<String> = emptySet()): Map<String, Long> {
        val additions = mutableListOf<CashRelationGraphEdge>()
        val proofs = linkedMapOf<BigInteger, CashRelationEvidence>()
        val groups = linkedMapOf<BigInteger, Map<String, Any?>>()
        for (group in members) for (raw in maps(group["edges"])) {
            val edge = edge(raw)
            val converted = edge.copy(primaryTransactionId = parent(edge.primaryTransactionId, identity), secondaryTransactionId = parent(edge.secondaryTransactionId, identity),
                primaryComponentId = part(edge.primaryComponentId, components), secondaryComponentId = part(edge.secondaryComponentId, components))
            additions += converted
            groups[key(edge)] = group
            proofs[key(edge)] = evidence(proof(group, edge.id))
        }
        val parents = additions.flatMap { listOfNotNull(it.primaryTransactionId, it.secondaryTransactionId) }.map { it.toString().toLong() }.toSet()
        val service = CashRelationContextService(context)
        val prepared = service.validate(service.load(parents), additions = additions, evidenceByRelation = proofs)
        for (group in members) if (group["action"] == "accept") context.entries.chooseCommon(list(group["parent_ids"]).map { parent(it, identity)!!.toLong() },
            map(group.getOrDefault("common_fields", emptyMap<String, Any?>())))
        val resolved = prepared.relations.associateBy(::key)
        val repository = CurrentCashRelationWriteRepository(context)
        val written = linkedMapOf<String, Long>()
        for (original in additions) {
            val edge = resolved.getValue(key(original))
            val group = groups.getValue(key(original))
            val anchor = part(map(group["anchors"])[edge.id.toString()], components)
            val anchorParent = if (anchor == edge.primaryComponentId) edge.primaryTransactionId as String else edge.secondaryTransactionId as String?
            val row = repository.add(cashImportEdgeMap(edge).filterKeys { it !in setOf("id", "applied_amount") } + mapOf(
                "anchor_transaction_id" to anchorParent, "anchor_component_id" to anchor, "decision_group_id" to if (additions.size > 1) commitId else null,
                "applied_amount" to amount(edge.appliedAmount).toString(), "evidence" to proof(group, edge.id), "created_by" to userId,
                "decision_source" to if (group["suggestion_id"] in manual) "manual" else "rule", "decided_by" to if (group["action"] != "defer") userId else "",
                "decided_at" to if (group["action"] != "defer") context.now() else null))
            written.putIfAbsent(group["suggestion_id"] as String, row["id"] as Long)
            if (edge.state == "draft") repository.setCandidates(row["id"], candidateValues(group, edge, anchorParent, identity, components, unavailable), row["row_revision"])
        }
        return written
    }

    private fun candidateValues(group: Map<String, Any?>, edge: CashRelationGraphEdge, anchorParent: String?, identity: Map<String, String>,
        components: Map<String, String>, unavailable: Set<String> = emptySet()): List<Map<String, Any?>> {
        val candidates = linkedMapOf<Pair<String, String?>, Map<String, Any?>>()
        for (choice in maps(group["candidates"])) {
            val edges = maps(choice["edges"])
            val variant = edges.firstOrNull { strictCashInteger(it["id"]) == key(edge) } ?: continue
            if (edges.any { item -> listOf("primary", "secondary").any { role ->
                    val parent = item["${role}_transaction_id"] as String?
                    parent in unavailable || parent != null && BigInteger(identity[parent] ?: parent) >= limit
                } }) continue
            for (role in listOf("primary", "secondary")) {
                val component = part(variant["${role}_component_id"], components)
                val original = variant["${role}_transaction_id"] as String? ?: continue
                val id = identity[original] ?: original
                if (id == anchorParent) continue
                val target = context.sql.select(CASH_TRANSACTIONS.ID, CASH_TRANSACTIONS.ROW_REVISION).from(CASH_TRANSACTIONS)
                    .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(context.workspace).and(CASH_TRANSACTIONS.ID.eq(id.toLong())).and(CASH_TRANSACTIONS.DELETED_AT.isNull)).fetchOne() ?: continue
                candidates[id to component] = mapOf("candidate_transaction_id" to id, "candidate_component_id" to component,
                    "expected_row_revision" to target.value2(), "evidence" to map(choice["evidence"])[edge.id.toString()])
            }
        }
        return candidates.values.toList()
    }

    private fun relationProblem(parents: Set<Long>, reference: String, code: String) {
        val source = CashSourceWriteRepository(context)
        for (batch in parents.toList().chunked(500)) {
            val ids = context.sql.select(CASH_TRANSACTIONS.ID).from(CASH_TRANSACTIONS).where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(context.workspace)
                .and(CASH_TRANSACTIONS.ID.`in`(batch)).and(CASH_TRANSACTIONS.DELETED_AT.isNull)).fetch().map { it.value1()!! }
            ids.forEach { source.saveIssue(it, "relation:" + sourceDigest(reference), code) }
        }
    }
    fun discoveryIssues(plan: Map<String, Any?>, identity: Map<String, String>, affected: MutableSet<Long>) {
        for (failure in maps(plan["relation_failures"])) {
            if ((failure["suggestion_id"] as String?).orEmpty().isNotEmpty()) continue
            val parents = list(failure["parent_ids"]).map { BigInteger(identity[it] ?: ref(it)) }.filter { it < limit }.map { it.longValueExact() }.toSet()
            relationProblem(parents, sourceDigest("discovery", parents.sorted()), failure["code"] as String)
            affected += parents
        }
    }
    fun discoveryResults(plan: Map<String, Any?>, identity: Map<String, String>): List<Map<String, Any?>> {
        val results = linkedMapOf<String, Map<String, Any?>>()
        for (failure in maps(plan["relation_failures"])) {
            if ((failure["suggestion_id"] as String?).orEmpty().isNotEmpty()) continue
            val originals = list(failure["parent_ids"]).map { ref(it) }.distinct().sortedWith(cashRelationStringOrder)
            val reference = "discovery:" + sourceDigest(failure["code"], originals)
            val parents = originals.map { BigInteger(identity[it] ?: it) }.filter { it.signum() > 0 && it < limit }
            results.putIfAbsent(reference, mapOf("result_kind" to "relation", "item_ref" to reference, "outcome" to "failed", "code" to failure["code"],
                "transaction_id" to parents.minOrNull()?.toString()))
        }
        return results.values.toList()
    }
}
