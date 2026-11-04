package com.finance.tracker.backend

import java.math.BigDecimal

internal data class ProjectionValidationContext(val activeFacts: List<RelationFact>,
    val projectionFacts: List<CashProjectionFact>, val componentToParent: List<Pair<String, Long>>,
    val components: List<CashRelationEndpoint> = emptyList())
internal data class ProjectionValidationSnapshot(val facts: List<CashProjectionFact>,
    val relations: List<CashProjectionRelation>, val componentToParent: List<Pair<String, Long>>)

/** 应用层关系提案规划与投影合法性校验；不持久化。 */
internal object CashRelationPlanning {
    private val fingerprints=com.finance.tracker.business.domain.CashRelationPlanFingerprints(CashRelationSerialization.sharedRules,
        {value:BigDecimal ->value.toPlainString()},{value ->sourceJson(value).toString()})
    private fun snapshotId(reference:String,used:MutableSet<Long>):Long=
        com.finance.tracker.business.domain.cashRelationSnapshotId(reference,used)
    private fun parentKey(fact: RelationFact): String =
        if (!fact.parentId.isNullOrEmpty()) "parent:${fact.parentId}"
        else "fact:${CashRelationSerialization.stableFactRef(fact.id, mapOf(fact.id to fact))}"
    private fun relationPayload(proposal: RelationProposal): Map<String, Any?> {
        val applied = if (proposal.kind == "refund_offset") {
            val amount = CashRelationGeometry.asDecimal(proposal.evidence.extras["refund_amount"]).abs()
            if (amount.signum() > 0) amount.toPlainString() else null
        } else null
        return linkedMapOf("kind" to proposal.kind,
            "primary_fact_id" to proposal.primaryFactId, "secondary_fact_id" to proposal.secondaryFactId,
            "status" to "accepted", "applied_amount" to applied)
    }
    private fun relationPayload(row: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
        "kind" to row["kind"],
        "primary_fact_id" to (row["primary_component_id"] ?: row["primary_fact_id"]),
        "secondary_fact_id" to (row["secondary_component_id"] ?: row["secondary_fact_id"]),
        "status" to row["status"], "applied_amount" to row["applied_amount"])
    private fun relationKey(payload: Map<String, Any?>): List<String> {
        val kind = payload["kind"]?.toString().orEmpty()
        var endpoints = listOf(payload["primary_fact_id"]?.toString().orEmpty(), payload["secondary_fact_id"]?.toString().orEmpty())
        if (kind == "payment_mirror") endpoints = endpoints.sortedWith(cashRelationStringOrder)
        return listOf(kind) + endpoints
    }

    fun buildValidationContext(facts: List<RelationFact>): ProjectionValidationContext {
        val active = facts.filter { !it.deleted }
        val byComponent = active.associateBy { it.id }
        val groups = linkedMapOf<String, MutableList<RelationFact>>()
        for (fact in active) groups.getOrPut(parentKey(fact)) { mutableListOf() } += fact
        val used = mutableSetOf<Long>()
        val usedAccountIds = mutableSetOf<Long>()
        val accountIds = active.mapNotNull { it.accountId.takeIf { value -> value.isNotEmpty() } }.toSortedSet()
            .associateWith { snapshotId(it, usedAccountIds) }
        val componentToParent = linkedMapOf<String, Long>()
        val projectionFacts = mutableListOf<CashProjectionFact>()
        val components = mutableListOf<CashRelationEndpoint>()
        for ((_, members) in groups.entries.sortedBy { it.key }) {
            val ordered = members.sortedWith(CashRelationCore.relationOrderKey(members.first()))
            val first = ordered.first()
            val currency = first.currency.uppercase()
            if (ordered.any { it.currency.uppercase() != currency }) throw CashProjectionDomainFailure("projection.invalid_fact")
            val projectionId = snapshotId(parentKey(first), used)
            val aggregate = ordered.size > 1 || ordered.any { it.cashGranularity == "aggregate" }
            for (member in ordered) {
                componentToParent[member.id] = projectionId
                components += CashRelationEndpoint(member.id, projectionId.toString(), CashAmount.parseOrNull(member.signedAmount.toPlainString()),
                    currency, accountIds[member.accountId]?.toString(), member.recordType, member.recordSubtype)
            }
            val occurred = CashRelationGeometry.parseDt(first.occurredAt)
            var amount = BigDecimal.ZERO
            for (item in ordered) amount += item.signedAmount
            projectionFacts += CashProjectionFact.create(projectionId, if (aggregate) null else accountIds[first.accountId], occurred,
                amount.toPlainString(), currency, first.counterparty, null, first.note, first.billSource.ifEmpty { first.source },
                CashRelationSerialization.stableFactRef(first.id, byComponent), null,
                if (aggregate) "aggregate" else "atomic", "posted", null, false,
                stableSourceKey = first.stableSourceKey, recordOrigin = first.recordOrigin)
        }
        return ProjectionValidationContext(active, projectionFacts,
            componentToParent.entries.map { it.key to it.value }.sortedBy { it.first }, components)
    }

    fun localizedValidationInputs(context: ProjectionValidationContext, acceptedRelations: List<Map<String, Any?>>,
        companions: List<RelationProposal>, proposal: RelationProposal): Triple<List<Map<String, Any?>>, List<RelationProposal>, ProjectionValidationContext>? {
        val componentToParent = context.componentToParent.toMap()
        val relationRows = mutableListOf<Triple<Map<String, Any?>, Long, Long>>()
        val proposalEdges = mutableListOf<Triple<RelationProposal, Long, Long>>()
        val edges = mutableListOf<Pair<Long, Long>>()
        for (relation in acceptedRelations) {
            if (relation["status"] != "accepted") continue
            val payload = relationPayload(relation)
            val primary = componentToParent[payload["primary_fact_id"]?.toString().orEmpty()] ?: return null
            val secondary = componentToParent[payload["secondary_fact_id"]?.toString().orEmpty()] ?: return null
            relationRows += Triple(relation, primary, secondary); edges += primary to secondary
        }
        for (companion in companions) {
            if (companion.secondaryFactId.isNullOrEmpty() || companion.isOpenLeg) continue
            val primary = componentToParent[companion.primaryFactId] ?: return null
            val secondary = componentToParent[companion.secondaryFactId] ?: return null
            proposalEdges += Triple(companion, primary, secondary); edges += primary to secondary
        }
        val candidatePrimary = componentToParent[proposal.primaryFactId] ?: return null
        val candidateSecondary = componentToParent[proposal.secondaryFactId.orEmpty()] ?: return null
        val adjacency = linkedMapOf<Long, MutableSet<Long>>()
        for ((left, right) in edges) { adjacency.getOrPut(left) { linkedSetOf() } += right; adjacency.getOrPut(right) { linkedSetOf() } += left }
        fun connected(start: Long): Set<Long> {
            val reached = linkedSetOf<Long>(); val pending = mutableListOf(start)
            while (pending.isNotEmpty()) { val current = pending.removeAt(pending.size - 1); if (!reached.add(current)) continue
                pending += adjacency[current].orEmpty() - reached }
            return reached
        }
        val affected = (connected(candidatePrimary) + connected(candidateSecondary)).toSet()
        val localRelations = relationRows.filter { it.second in affected && it.third in affected }.map { it.first }
        val localCompanions = proposalEdges.filter { it.second in affected && it.third in affected }.map { it.first }
        val localFacts = context.projectionFacts.filter { it.id.storedCashId() in affected }
        if (localFacts.map { it.id.storedCashId() }.toSet() != affected) return null
        return Triple(localRelations, localCompanions, context.copy(projectionFacts = localFacts))
    }

    fun validationReason(facts: List<RelationFact>, acceptedRelations: List<Map<String, Any?>>, proposal: RelationProposal,
        companionProposals: List<RelationProposal> = emptyList(),
        validationContext: ProjectionValidationContext? = null): String? {
        if (proposal.secondaryFactId.isNullOrEmpty() || proposal.isOpenLeg) return null
        return try {
            val context = validationContext ?: buildValidationContext(facts)
            val componentToParent = context.componentToParent.toMap()
            val relationPayloads = acceptedRelations.filter { it["status"] == "accepted" }.map(::relationPayload).toMutableList()
            val seenProposals = mutableSetOf<String>()
            val seenRelationKeys = relationPayloads.map(::relationKey).toMutableSet()
            for (candidate in companionProposals) {
                if (candidate.secondaryFactId.isNullOrEmpty() || candidate.isOpenLeg) continue
                val key = CashRelationSerialization.proposalKey(candidate, context.activeFacts)
                if (!seenProposals.add(key)) continue
                val payload = relationPayload(candidate)
                if (seenRelationKeys.add(relationKey(payload))) relationPayloads += payload
            }
            val candidateKey = CashRelationSerialization.proposalKey(proposal, context.activeFacts)
            if (candidateKey !in seenProposals) {
                val payload = relationPayload(proposal)
                if (seenRelationKeys.add(relationKey(payload))) relationPayloads += payload
            }
            val usedRelationIds = mutableSetOf<Long>()
            val components = context.components.associateBy { it.componentId }
            val edges = mutableListOf<CashRelationGraphEdge>()
            for ((index, payload) in relationPayloads.withIndex()) {
                val primaryComponent = payload["primary_fact_id"]?.toString().orEmpty()
                val secondaryComponent = payload["secondary_fact_id"]?.toString().orEmpty()
                val primary = componentToParent[primaryComponent] ?: return "projection_invalid"
                val secondary = componentToParent[secondaryComponent] ?: return "projection_invalid"
                if (primaryComponent !in components || secondaryComponent !in components) return "projection_invalid"
                val reference = sourceJson(linkedMapOf("kind" to payload["kind"],
                    "primary" to primaryComponent, "secondary" to secondaryComponent, "index" to index)).toString()
                val relationId = snapshotId(reference, usedRelationIds)
                val applied = payload["applied_amount"]?.let {
                    CashDecimalLiteral(if (it is BigDecimal) it.toPlainString() else it.toString())
                }
                val roles = inferCashRelationRoles(payload["kind"], components.getValue(primaryComponent),
                    components.getValue(secondaryComponent))
                edges += CashRelationGraphEdge(relationId, payload["kind"], primary.toString(), secondary.toString(),
                    primaryComponent, secondaryComponent, roles.first, roles.second, applied)
            }
            buildCashRelationGraph(context.projectionFacts, context.components, edges)
            null
        } catch (_: CashProjectionFailure) { "projection_invalid" }
        catch (_: CashRelationFailure) { "projection_invalid" }
        catch (_: IllegalArgumentException) { "projection_invalid" }
        catch (_: ArithmeticException) { "projection_invalid" }
    }

    fun filterIllegalProposals(facts: List<RelationFact>, activeRelations: List<Map<String, Any?>>,
        proposals: List<RelationProposal>, validationContext: ProjectionValidationContext? = null): List<RelationProposal> {
        val context = validationContext ?: buildValidationContext(facts)
        val acceptedBatch = proposals.filter { it.createdBy == "system" && it.status == "accepted" &&
            !it.secondaryFactId.isNullOrEmpty() && !it.isOpenLeg }
        val batchReason = if (acceptedBatch.isEmpty()) null else validationReason(facts, activeRelations,
            acceptedBatch.first(), acceptedBatch, context)
        val filtered = mutableListOf<RelationProposal>()
        for (proposal in proposals) {
            if (proposal.createdBy != "system" || proposal.secondaryFactId.isNullOrEmpty() || proposal.isOpenLeg) {
                filtered += proposal; continue
            }
            val reason = if (proposal.status == "accepted") batchReason else validationReason(facts, activeRelations,
                proposal, acceptedBatch + proposal, context)
            if (reason != null) continue
            filtered += proposal
        }
        return filtered
    }

    fun relationContextDigest(facts:List<RelationFact>,relations:List<Map<String,Any?>>,proposals:List<RelationProposal>):String=
        fingerprints.relationContextDigest(facts,relations,proposals)
    fun externalContextDigest(facts:List<RelationFact>,relations:List<Map<String,Any?>>,aliasesByTail:Map<String,List<String>>,
        accountIdentifiers:Map<String,List<String>>):String=fingerprints.externalContextDigest(facts,relations,aliasesByTail,accountIdentifiers)

    private fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is String -> value.isNotEmpty()
        is Number -> value.toDouble() != 0.0
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> true
    }
    private fun text(value: Any?): String = value?.toString().orEmpty()
    private fun compareKeys(first: List<String>, second: List<String>): Int {
        for (index in first.indices) {
            val result = cashRelationStringOrder.compare(first[index], second[index])
            if (result != 0) return result
        }
        return 0
    }
    private fun factKey(fact: RelationFact): List<String> = CashRelationCore.stableFactOrderKey(fact)
    private fun refundAmount(proposal: RelationProposal): BigDecimal =
        BigDecimal(proposal.evidence.extras["refund_amount"].toString()).abs()

    /** 把持久化行里的结构化来源元数据暴露给 Phase A；返回副本，不修改调用方行。 */
    fun enrichPlatformRefundRows(rows: List<Map<String, Any?>>): List<Map<String, Any?>> = rows.map { sourceRow ->
        val row = LinkedHashMap(sourceRow)
        val metadata = row["relation_metadata"] as? Map<*, *>
        if (metadata != null) {
            for (key in listOf("offset_group", "offset_role", "offset_rule_hint", "offset_match_type", "offset_strength"))
                if (key in metadata && !truthy(row[key])) row[key] = metadata[key]
        }
        var payload = row["raw_payload"] as? Map<*, *>
        if ((payload == null || payload.isEmpty()) && row["source_payload"] is Map<*, *>) {
            payload = row["source_payload"] as Map<*, *>
            row["raw_payload"] = payload
        }
        if (payload != null && payload.isNotEmpty()) {
            row.putIfAbsent("platform_status", payload["status"] ?: payload["platform_status"] ?: "")
            row.putIfAbsent("status", payload["status"] ?: row["platform_status"] ?: "")
            if (truthy(payload["txn_id"])) row["txn_id"] = payload["txn_id"]
            if (truthy(payload["merchant_order_id"])) row["merchant_order_id"] = payload["merchant_order_id"]
            val txnType = payload["txn_type"] ?: payload["type"]
            if (truthy(txnType)) row["txn_type"] = txnType
        }
        if (!truthy(row["txn_id"]) && truthy(row["record_id"])) row["txn_id"] = row["record_id"]
        row
    }

    /** 追加已接受关系边到匹配上下文，供后续阶段占用扩展使用。 */
    fun appendRelationEdge(context: MatchContext, relation: Map<String, Any?>) {
        val primary = text(relation["primary_fact_id"])
        val secondary = text(relation["secondary_fact_id"])
        if (primary.isEmpty() || secondary.isEmpty()) return
        val kind = text(relation["kind"])
        val edge = RelationEdge(primary, secondary, kind)
        if (kind == "payment_mirror") context.acceptedMirrors += edge
        else if (kind == "refund_offset") context.acceptedPlatformRefunds += edge
    }

    fun refundBlockedIds(facts: List<RelationFact>, relations: List<Map<String, Any?>>,
        remaining: Map<String, BigDecimal>): MutableSet<String> {
        val byId = facts.associateBy { it.id }
        val blocked = linkedSetOf<String>()
        for (relation in relations) {
            if (relation["status"] == "superseded") continue
            // 支付镜像把两行来源合并为同一经济事件，不消耗退款额度。
            if (relation["kind"] == "payment_mirror") continue
            val primary = text(relation["primary_fact_id"])
            val secondary = text(relation["secondary_fact_id"])
            val refreshableOpenLeg = relation["status"] == "pending_review" &&
                CashRelationCore.isOpenLegRelation(relation) && relation["created_by"] == "system" &&
                !CashRelationSerialization.isHumanDecision(relation) && !truthy(relation["candidate_fact_ids"])
            val keepExpenseCandidate = relation["status"] == "accepted" && secondary.isNotEmpty() &&
                primary in remaining && remaining.getValue(primary).signum() > 0 &&
                byId[primary]?.signedAmount?.signum() == -1
            if (primary.isNotEmpty() && !keepExpenseCandidate && !refreshableOpenLeg) blocked += primary
            if (secondary.isNotEmpty()) blocked += secondary
            val anchor = text(relation["anchor_fact_id"])
            if (anchor.isNotEmpty() && !refreshableOpenLeg) blocked += anchor
        }
        return blocked
    }

    /** 构建完整的 Phase A → B-D 关系计划；无持久化副作用。 */
    fun planRelationProposals(facts: List<RelationFact>, detailedRows: List<Map<String, Any?>>? = null,
        seedIds: List<String>? = null, acceptedRelations: List<Map<String, Any?>> = emptyList(),
        aliasesByTail: Map<String, List<String>>? = null, accountIdentifiers: Map<String, List<String>>? = null,
        remainingByExpense: Map<String, BigDecimal>? = null, workspaceId: String = "",
        requireUniqueMirrors: Boolean = false): RelationPlan {
        val normalized = facts.filter { !it.deleted }.sortedWith(Comparator { left, right ->
            compareKeys(factKey(left), factKey(right))
        })
        val factById = normalized.associateBy { it.id }
        val rawRows = detailedRows?.takeIf { it.isNotEmpty() } ?: normalized.map(CashRelationSerialization::factDetailRow)
        val factsByRecordId = normalized.filter { it.recordId.isNotEmpty() }.associateBy { it.recordId }
        val rows = enrichPlatformRefundRows(rawRows).sortedWith(Comparator { left, right ->
            fun key(row: Map<String, Any?>): List<String> {
                val fact = factById[text(row["id"])] ?: factsByRecordId[text(row["record_id"])]
                    ?: CashRelationSerialization.factViewFromRow(row)
                return factKey(fact)
            }
            compareKeys(key(left), key(right))
        })
        val activeRelations = acceptedRelations.map { LinkedHashMap(it) }
        val remaining = (remainingByExpense?.let { LinkedHashMap(it) }
            ?: CashRelationSerialization.initialRemaining(normalized, activeRelations))
        val linkedPairs = activeRelations.filter { relation ->
            relation["kind"] == "refund_offset" && relation["status"] != "superseded" &&
                text(relation["primary_fact_id"]).isNotEmpty() && text(relation["secondary_fact_id"]).isNotEmpty()
        }.map { text(it["primary_fact_id"]) to text(it["secondary_fact_id"]) }.toSet()
        var phaseA = CashRefundHardKey.match(rows, factById, linkedPairs, remaining)
        for (proposal in phaseA) {
            if (proposal.status != "accepted" || proposal.kind != "refund_offset") continue
            val expense = factById[proposal.primaryFactId]
            val refund = factById[proposal.secondaryFactId.orEmpty()]
            if (expense != null && refund != null && expense.signedAmount.signum() < 0)
                remaining[expense.id] = (remaining[expense.id] ?: expense.signedAmount.abs()) - refundAmount(proposal)
        }
        val context = MatchContext(workspaceId)
        context.remainingByExpense = LinkedHashMap(remaining)
        for (relation in activeRelations) if (relation["status"] == "accepted") appendRelationEdge(context, relation)
        for (proposal in phaseA) if (proposal.status == "accepted" && !proposal.secondaryFactId.isNullOrEmpty())
            appendRelationEdge(context, linkedMapOf("kind" to proposal.kind,
                "primary_fact_id" to proposal.primaryFactId, "secondary_fact_id" to proposal.secondaryFactId))
        val refundBlocked = refundBlockedIds(normalized, activeRelations, remaining)
        val phaseARelations = phaseA.map { linkedMapOf<String, Any?>("kind" to it.kind, "status" to it.status,
            "primary_fact_id" to it.primaryFactId, "secondary_fact_id" to it.secondaryFactId,
            "anchor_fact_id" to it.effectiveAnchorFactId, "created_by" to it.createdBy) }
        refundBlocked += refundBlockedIds(normalized, phaseARelations, remaining)
        val transferBlocked = activeRelations.filter { relation ->
            relation["kind"] == "transfer_pair" && relation["status"] == "accepted"
        }.flatMap { relation -> listOf("primary_fact_id", "secondary_fact_id").map { text(relation[it]) } }
            .filter { it.isNotEmpty() }.toSet()
        val index = CashFactCandidateIndex(normalized, CashRelationCore::sourceGroup)
        val seeds = (seedIds ?: normalized.map { it.id }).map { it.toString() }.filter { it in factById }
            .sortedWith(Comparator { left, right -> compareKeys(factKey(factById.getValue(left)),
                factKey(factById.getValue(right))) })
        val proposals = CashRelationPipeline.run(normalized, ctx = context, seedIds = seeds, index = index,
            aliasesByTail = aliasesByTail.orEmpty(), accountIdentifiers = accountIdentifiers.orEmpty(),
            transferBlockedIds = transferBlocked, refundBlockedIds = refundBlocked,
            merchantRefundSeedIds = seeds, requireUniqueMirrors = requireUniqueMirrors)
        phaseA = CashRelationPipeline.demoteOverlappingPhaseARefunds(normalized, phaseA, activeRelations,
            context.mirrorPairs())
        val all = (phaseA + proposals).sortedWith(Comparator { left, right ->
            cashRelationStringOrder.compare(CashRelationSerialization.proposalKey(left, normalized),
                CashRelationSerialization.proposalKey(right, normalized))
        })
        val filtered = filterIllegalProposals(normalized, activeRelations, all, buildValidationContext(normalized))
        return RelationPlan(normalized, filtered,
            relationContextDigest(normalized, activeRelations, filtered))
    }
}
