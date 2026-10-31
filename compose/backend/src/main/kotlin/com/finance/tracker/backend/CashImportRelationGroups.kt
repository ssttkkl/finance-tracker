package com.finance.tracker.backend

import java.math.BigInteger
import java.time.Instant

private fun pythonImportDecimal(amount: CashAmount): String {
    val value = amount.representation
    val coefficient = value.coefficient
    val exponent = BigInteger(value.exponent.toString())
    val adjusted = exponent + BigInteger.valueOf(coefficient.length.toLong() - 1)
    val sign = if (value.negative) "-" else ""
    if (exponent.signum() > 0 || adjusted < BigInteger.valueOf(-6)) {
        val mantissa = coefficient.take(1) + if (coefficient.length > 1) "." + coefficient.drop(1) else ""
        return sign + mantissa + "E" + (if (adjusted.signum() >= 0) "+" else "") + adjusted
    }
    val point = coefficient.length + exponent.intValueExact()
    return sign + when {
        point <= 0 -> "0." + "0".repeat(-point) + coefficient
        point < coefficient.length -> coefficient.take(point) + "." + coefficient.drop(point)
        else -> coefficient
    }
}

/** Python 导入建议摘要沿用 default=str 的计算值表示。 */
internal fun cashImportDigest(vararg values: Any?): String {
    fun value(item: Any?): Any? = when (item) {
        is CashAmount -> pythonImportDecimal(item)
        is CashDecimalLiteral -> pythonImportDecimal(CashAmount.parse(item.text))
        is Instant -> ParsedImportCodec.timeText(item).replace('T', ' ')
        is Map<*, *> -> item.entries.associate { it.key.toString() to value(it.value) }
        is List<*> -> item.map(::value)
        else -> item
    }
    return sourceDigest(*values.map(::value).toTypedArray())
}

internal fun cashImportVirtual(kind: String, vararg values: Any?): BigInteger = BigInteger.ONE.shiftLeft(64).add(
    BigInteger(sourceDigest("cash-import-virtual-v1", kind, values.toList()).take(32), 16))

internal fun cashImportEdgeKey(edge: CashRelationGraphEdge): List<Any?> {
    val first = edge.primaryComponentId as? String
    val second = edge.secondaryComponentId as? String
    val pair = if (!first.isNullOrEmpty() && !second.isNullOrEmpty()) {
        if (edge.kind == "payment_mirror") listOf(first, second).sortedWith(cashRelationStringOrder) else listOf(first, second)
    } else listOf(edge.primaryTransactionId, edge.primaryComponentId, null)
    return listOf(edge.kind, edge.subtype, pair)
}

internal fun cashImportEdgeMap(edge: CashRelationGraphEdge): Map<String, Any?> = linkedMapOf(
    "id" to edge.id, "kind" to edge.kind, "primary_transaction_id" to edge.primaryTransactionId,
    "secondary_transaction_id" to edge.secondaryTransactionId, "primary_component_id" to edge.primaryComponentId,
    "secondary_component_id" to edge.secondaryComponentId, "primary_role" to edge.primaryRole,
    "secondary_role" to edge.secondaryRole, "subtype" to edge.subtype, "applied_amount" to edge.appliedAmount,
    "state" to edge.state, "active" to edge.active,
)

internal fun cashImportLink(edge: CashRelationGraphEdge, group: String? = null) = CashRelationLink(
    edge.id.toString(), edge.primaryTransactionId, edge.secondaryTransactionId, edge.state, edge.active, group)

internal fun cashImportClosures(edges: Collection<CashRelationGraphEdge>): Map<String, Set<String>> {
    val groups = CashRelationGroups()
    for (edge in edges) if (edge.active == true && edge.state == "confirmed" && edge.secondaryTransactionId != null)
        groups.join(edge.primaryTransactionId as String, edge.secondaryTransactionId as String)
    return groups.parents.keys.groupBy(groups::root).values.flatMap { members ->
        val all = members.toSet()
        members.map { it to all }
    }.toMap()
}

internal fun cashImportRef(snapshot: CashImportPreviewSnapshot, identifier: String): Map<String, Any?> =
    snapshot.parentOps[identifier]?.let { mapOf("row_ref" to (it["row_refs"] as List<*>).first()) }
        ?: mapOf("transaction_id" to identifier)

internal data class CashImportRelationGroupResult(val groups: List<MutableMap<String, Any?>>, val failures: List<Map<String, Any?>>)

internal object CashImportRelationGroups {
    private data class Member(val edge: CashRelationGraphEdge, val evidence: Map<String, Any?>, val anchor: String?,
        val strong: Boolean, val rule: String, val candidates: List<String>, val candidateCount: Int)
    private data class Variant(val edge: CashRelationGraphEdge, val evidence: Map<String, Any?>)
    private val freshActions = setOf("create_posted", "finalize_draft")
    private fun evidence(snapshot: CashImportPreviewSnapshot, first: CashRelationEndpoint, second: CashRelationEndpoint?,
        investment: Boolean = false, p2p: Boolean = false) = CashRelationEvidence(
        CashImportRelationDiscovery.originalExpense(snapshot.values.getValue(first.transactionId as String)),
        second?.let { CashImportRelationDiscovery.originalExpense(snapshot.values.getValue(it.transactionId as String)) } ?: false,
        investment, p2p)
    private fun evidenceMap(value: CashRelationEvidence): Map<String, Any?> = linkedMapOf(
        "primary_original_expense" to value.primaryOriginalExpense, "secondary_original_expense" to value.secondaryOriginalExpense,
        "investment_refund_hard_key" to value.investmentRefundHardKey, "p2p_return_hard_key" to value.p2pReturnHardKey)

    private fun member(snapshot: CashImportPreviewSnapshot, proposal: RelationProposal, old: Set<List<Any?>>): Member? {
        val first = snapshot.components[proposal.primaryFactId] ?: relationFailure("relation_endpoint_incomplete")
        val second = proposal.secondaryFactId?.let(snapshot.components::get)
        if (proposal.secondaryFactId != null && second == null) relationFailure("relation_endpoint_incomplete")
        if (second != null && first.transactionId == second.transactionId) relationFailure("relation_endpoint_incompatible")
        val proof = evidence(snapshot, first, second,
            proposal.kind == "refund_offset" && "platform_hard_key" in proposal.evidence.signals,
            proposal.subtype == "p2p_return" && "platform_hard_key" in proposal.evidence.signals)
        val roles = second?.let { discoverCashRelationRoles(proposal.kind, proposal.subtype, first, it, proof) }
        val edge = CashRelationGraphEdge(cashImportVirtual("relation", proposal.kind, proposal.subtype, first.componentId, second?.componentId),
            proposal.kind, first.transactionId, second?.transactionId, first.componentId, second?.componentId,
            roles?.first, roles?.second, proposal.subtype,
            if (proposal.kind == "refund_offset") CashDecimalLiteral(CashRelationGeometry.asDecimal(proposal.evidence.extras["refund_amount"]).toString()) else null,
            state = "draft")
        if (cashImportEdgeKey(edge) in old) return null
        val anchor = proposal.effectiveAnchorFactId.ifEmpty { null }
        if (anchor != null && anchor !in setOf(edge.primaryComponentId, edge.secondaryComponentId)) relationFailure("relation_endpoint_incompatible")
        return Member(edge, evidenceMap(proof), anchor, proposal.status == "accepted" && proposal.confidence == "strong",
            proposal.ruleId, proposal.evidence.candidateFactIds, proposal.evidence.candidateCount)
    }

    fun build(snapshot: CashImportPreviewSnapshot, proposals: List<RelationProposal>): CashImportRelationGroupResult {
        val old = snapshot.edges.values.filter { it.active == true }.map(::cashImportEdgeKey).toSet()
        val converted = linkedMapOf<String, Member>()
        val failures = mutableListOf<Map<String, Any?>>()
        val fresh = snapshot.parentOps.filterValues { it["planned_action"] in freshActions }.keys
        for (proposal in proposals) {
            val parents = listOfNotNull(snapshot.components[proposal.primaryFactId], snapshot.components[proposal.secondaryFactId])
                .map { it.transactionId as String }.toSet()
            try { member(snapshot, proposal, old)?.let { converted[it.edge.id.toString()] = it } }
            catch (failure: CashRelationFailure) {
                if (parents.any { it in fresh }) failures += linkedMapOf("code" to failure.code, "parent_ids" to parents.sortedWith(cashRelationStringOrder))
            }
        }
        val closures = cashImportClosures(snapshot.context.relations)
        val versions = snapshot.context.transactionRevisions.toMap()
        val result = mutableListOf<MutableMap<String, Any?>>()
        for (identifiers in cashRelationCommitGroups(snapshot.edges.values.map(::cashImportLink), converted.values.map { cashImportLink(it.edge) })) {
            val members = identifiers.map(converted::getValue)
            val parents = members.flatMap { listOfNotNull(it.edge.primaryTransactionId as String, it.edge.secondaryTransactionId as? String) }.toSet()
            if (parents.none { it in fresh }) continue
            val dependencyParents = parents + members.flatMap { item -> item.candidates.mapNotNull { snapshot.components[it]?.transactionId as? String } }
            val ordered = dependencyParents.sortedWith(cashRelationStringOrder)
            val fingerprint = ordered.map { listOf(it, it.toLongOrNull()?.let(versions::get), snapshot.parentOps[it]?.get("values"),
                snapshot.values.getValue(it)["source_explanation"]) }
            val complete = dependencyParents.filter { BigInteger(it) < BigInteger.ONE.shiftLeft(63) }
                .flatMap { closures[it] ?: setOf(it) }.toSet()
            val context = snapshot.context
            val relationIds = context.relations.filter { it.primaryTransactionId in complete || it.secondaryTransactionId in complete }
                .map { strictCashInteger(it.id)!! }.toSet()
            val dependency = listOf(
                context.transactionRevisions.filter { it.first.toString() in complete }.map { listOf(it.first, it.second) },
                context.componentVersions.filter { it.transactionId.toString() in complete }.map { listOf(it.id, it.transactionId, it.accountId, it.amount, it.currency, it.ordinal) },
                context.relationRevisions.filter { it.first.toBigInteger() in relationIds }.map { listOf(it.first, it.second) },
                context.fundingVersions.filter { snapshot.components[it.componentId.toString()]?.transactionId in complete }
                    .map { listOf(it.id, it.componentId, it.investmentEventId, it.direction) },
                context.issueVersions.filter { it.transactionId.toString() in complete }.map { listOf(it.id, it.transactionId, it.revision) })
            val proof = members.map { listOf(it.rule, it.anchor, it.candidateCount, it.candidates) }
            val suggestion = "sg_" + cashImportDigest("cash-import-suggestion-v1", identifiers.sortedWith(cashRelationStringOrder), fingerprint, dependency, proof).take(40)
            val group = linkedMapOf<String, Any?>("suggestion_id" to suggestion, "action" to if (members.all { it.strong }) "accept" else "defer",
                "edges" to members.map { cashImportEdgeMap(it.edge) }, "evidence" to members.associate { it.edge.id.toString() to it.evidence },
                "anchors" to members.associate { it.edge.id.toString() to (it.anchor ?: it.edge.primaryComponentId) },
                "parent_ids" to parents.sortedWith(cashRelationStringOrder), "member_refs" to parents.sortedWith(cashRelationStringOrder).map { cashImportRef(snapshot, it) },
                "allowed_actions" to if (members.all { it.edge.secondaryTransactionId != null }) listOf("accept", "defer", "ignore") else listOf("defer", "ignore"),
                "candidates" to emptyList<Any?>(), "candidate_id" to null, "dependency_parent_ids" to ordered)
            if (group["action"] == "defer") {
                val candidates = candidateChoices(snapshot, suggestion, members)
                group["candidates"] = candidates
                if (candidates.isNotEmpty()) group["allowed_actions"] = listOf("accept", "defer", "ignore")
            }
            result += group
        }
        return CashImportRelationGroupResult(result, failures)
    }

    private fun variants(snapshot: CashImportPreviewSnapshot, item: Member): List<Variant> {
        val edge = item.edge
        val anchor = item.anchor ?: when {
            edge.secondaryComponentId in item.candidates || edge.secondaryComponentId == null -> edge.primaryComponentId as String
            edge.primaryComponentId in item.candidates -> edge.secondaryComponentId as String
            else -> return if (edge.secondaryTransactionId != null) listOf(Variant(edge, item.evidence)) else emptyList()
        }
        val result = mutableListOf<Variant>()
        if (edge.secondaryTransactionId != null) result += Variant(edge, item.evidence)
        for (identifier in item.candidates) {
            val candidate = snapshot.components[identifier] ?: continue
            val fixed = snapshot.components.getValue(anchor)
            if (candidate.transactionId == fixed.transactionId) continue
            val (first, second) = if (edge.kind in setOf("refund_offset", "transfer_pair")) {
                if (cashRelationDecimal(fixed.amount)!!.arithmeticValue().signum() < 0) fixed to candidate else candidate to fixed
            } else if (anchor == edge.primaryComponentId) fixed to candidate else candidate to fixed
            val proof = evidence(snapshot, first, second)
            val roles = try { discoverCashRelationRoles(edge.kind, edge.subtype, first, second, proof) } catch (_: CashRelationFailure) { continue }
            result += Variant(edge.copy(primaryTransactionId = first.transactionId, secondaryTransactionId = second.transactionId,
                primaryComponentId = first.componentId, secondaryComponentId = second.componentId,
                primaryRole = roles.first, secondaryRole = roles.second), evidenceMap(proof))
        }
        return result
    }

    private fun combinations(options: List<List<Variant>>): Sequence<List<Variant>> = sequence {
        if (options.any { it.isEmpty() }) return@sequence
        val indices = IntArray(options.size)
        while (true) {
            yield(options.mapIndexed { index, variants -> variants[indices[index]] })
            var cursor = indices.lastIndex
            while (cursor >= 0 && ++indices[cursor] == options[cursor].size) { indices[cursor] = 0; cursor-- }
            if (cursor < 0) return@sequence
        }
    }

    private fun candidateChoices(snapshot: CashImportPreviewSnapshot, suggestion: String, members: List<Member>): List<Map<String, Any?>> {
        val options = members.map { variants(snapshot, it) }
        val closures = cashImportClosures(snapshot.edges.values)
        val result = mutableListOf<Map<String, Any?>>()
        val seen = mutableSetOf<List<List<Any?>>>()
        for (combination in combinations(options).take(2000)) {
            val edges = combination.map { it.edge.copy(state = "confirmed") }
            val keys = edges.map(::cashImportEdgeKey)
            if (!seen.add(keys)) continue
            val parents = edges.flatMap { listOfNotNull(it.primaryTransactionId as String, it.secondaryTransactionId as? String) }.toSet()
            val complete = parents.flatMap { closures[it] ?: setOf(it) }.toSet()
            val build = try { snapshot.build(complete, additions = edges) } catch (_: CashRelationFailure) { continue }
            val projection = build.projections.firstOrNull { it.primaryRecord.id.toString() in parents } ?: continue
            result += linkedMapOf("candidate_id" to "candidate_" + cashImportDigest(suggestion, keys).take(40),
                "member_refs" to parents.sortedWith(cashRelationStringOrder).map { cashImportRef(snapshot, it) },
                "parent_ids" to parents.sortedWith(cashRelationStringOrder), "edges" to edges.map(::cashImportEdgeMap),
                "evidence" to combination.associate { it.edge.id.toString() to it.evidence },
                "display" to linkedMapOf("occurred_at" to projection.occurredAt, "amount" to projection.netAmount,
                    "currency" to projection.primaryRecord.currency, "counterparty" to projection.primaryRecord.counterparty,
                    "note" to projection.primaryRecord.note, "accounts" to emptyList<Any?>()))
            if (result.size == 20) break
        }
        return result
    }
}
