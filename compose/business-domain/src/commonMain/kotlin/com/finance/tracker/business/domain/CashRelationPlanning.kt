package com.finance.tracker.business.domain

/** Full Phase A→D relation plan, using the same shared validation as actual graph writes. */
class CashRelationPlanningRules<A:Comparable<A>,I:Comparable<I>,T:Comparable<T>>(
    private val core:CashRelationCoreRules<A>,private val serialization:CashRelationSerializationRules<A>,
    private val hardKey:CashRefundHardKeyRules<A,*>,private val pipeline:CashRelationPipelineRules<A,T>,
    private val validation:CashRelationValidationRules<A,I,T>,private val fingerprints:CashRelationPlanFingerprints<A>,
    private val createIndex:(List<RelationFact<A>>)->CashFactCandidateIndex<A>,private val factFromRow:(Map<String,Any?>)->RelationFact<A>,
    private val absolute:(A)->A,private val subtract:(A,A)->A,private val parseAmount:(Any?)->A,private val sign:(A)->Int) {
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
    private fun factKey(fact: RelationFact<A>): List<String> = core.stableFactOrderKey(fact)
    private fun refundAmount(proposal:RelationProposal):A=absolute(parseAmount(proposal.evidence.extras["refund_amount"].toString()))

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
            if(row["platform_status"]==null) row["platform_status"]=payload["status"] ?: payload["platform_status"] ?: ""
            if(row["status"]==null) row["status"]=payload["status"] ?: row["platform_status"] ?: ""
            if (truthy(payload["txn_id"])) row["txn_id"] = payload["txn_id"]
            if (truthy(payload["merchant_order_id"])) row["merchant_order_id"] = payload["merchant_order_id"]
            val txnType = payload["txn_type"] ?: payload["type"]
            if (truthy(txnType)) row["txn_type"] = txnType
        }
        if (!truthy(row["txn_id"]) && truthy(row["record_id"])) row["txn_id"] = row["record_id"]
        row
    }

    /** 追加已接受关系边到匹配上下文，供后续阶段占用扩展使用。 */
    fun appendRelationEdge(context: MatchContext<A>, relation: Map<String, Any?>) {
        val primary = text(relation["primary_fact_id"])
        val secondary = text(relation["secondary_fact_id"])
        if (primary.isEmpty() || secondary.isEmpty()) return
        val kind = text(relation["kind"])
        val edge = RelationEdge(primary, secondary, kind)
        if (kind == "payment_mirror") context.acceptedMirrors += edge
        else if (kind == "refund_offset") context.acceptedPlatformRefunds += edge
    }

    fun refundBlockedIds(facts: List<RelationFact<A>>, relations: List<Map<String, Any?>>,
        remaining: Map<String, A>): MutableSet<String> {
        val byId = facts.associateBy { it.id }
        val blocked = linkedSetOf<String>()
        for (relation in relations) {
            if (relation["status"] == "superseded") continue
            // 支付镜像把两行来源合并为同一经济事件，不消耗退款额度。
            if (relation["kind"] == "payment_mirror") continue
            val primary = text(relation["primary_fact_id"])
            val secondary = text(relation["secondary_fact_id"])
            val refreshableOpenLeg = relation["status"] == "pending_review" &&
                core.isOpenLegRelation(relation) && relation["created_by"] == "system" &&
                !serialization.isHumanDecision(relation) && !truthy(relation["candidate_fact_ids"])
            val keepExpenseCandidate = relation["status"] == "accepted" && secondary.isNotEmpty() &&
                primary in remaining && sign(remaining.getValue(primary)) > 0 &&
                byId[primary]?.signedAmount?.let(sign) == -1
            if (primary.isNotEmpty() && !keepExpenseCandidate && !refreshableOpenLeg) blocked += primary
            if (secondary.isNotEmpty()) blocked += secondary
            val anchor = text(relation["anchor_fact_id"])
            if (anchor.isNotEmpty() && !refreshableOpenLeg) blocked += anchor
        }
        return blocked
    }

    /** 构建完整的 Phase A → B-D 关系计划；无持久化副作用。 */
    fun planRelationProposals(facts: List<RelationFact<A>>, detailedRows: List<Map<String, Any?>>? = null,
        seedIds: List<String>? = null, acceptedRelations: List<Map<String, Any?>> = emptyList(),
        aliasesByTail: Map<String, List<String>>? = null, accountIdentifiers: Map<String, List<String>>? = null,
        remainingByExpense: Map<String, A>? = null, workspaceId: String = "",
        requireUniqueMirrors: Boolean = false): RelationPlan<A> {
        val normalized = facts.filter { !it.deleted }.sortedWith(Comparator { left, right ->
            compareKeys(factKey(left), factKey(right))
        })
        val factById = normalized.associateBy { it.id }
        val rawRows = detailedRows?.takeIf { it.isNotEmpty() } ?: normalized.map(serialization::factDetailRow)
        val factsByRecordId = normalized.filter { it.recordId.isNotEmpty() }.associateBy { it.recordId }
        val rows = enrichPlatformRefundRows(rawRows).sortedWith(Comparator { left, right ->
            fun key(row: Map<String, Any?>): List<String> {
                val fact = factById[text(row["id"])] ?: factsByRecordId[text(row["record_id"])]
                    ?: factFromRow(row)
                return factKey(fact)
            }
            compareKeys(key(left), key(right))
        })
        val activeRelations = acceptedRelations.map { LinkedHashMap(it) }
        val remaining = (remainingByExpense?.let { LinkedHashMap(it) }
            ?: serialization.initialRemaining(normalized, activeRelations))
        val linkedPairs = activeRelations.filter { relation ->
            relation["kind"] == "refund_offset" && relation["status"] != "superseded" &&
                text(relation["primary_fact_id"]).isNotEmpty() && text(relation["secondary_fact_id"]).isNotEmpty()
        }.map { text(it["primary_fact_id"]) to text(it["secondary_fact_id"]) }.toSet()
        var phaseA = hardKey.match(rows, factById, linkedPairs, remaining)
        for (proposal in phaseA) {
            if (proposal.status != "accepted" || proposal.kind != "refund_offset") continue
            val expense = factById[proposal.primaryFactId]
            val refund = factById[proposal.secondaryFactId.orEmpty()]
            if (expense != null && refund != null && sign(expense.signedAmount) < 0)
                remaining[expense.id] = subtract(remaining[expense.id] ?: absolute(expense.signedAmount),refundAmount(proposal))
        }
        val context=MatchContext<A>(workspaceId)
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
        val index=createIndex(normalized)
        val seeds = (seedIds ?: normalized.map { it.id }).map { it.toString() }.filter { it in factById }
            .sortedWith(Comparator { left, right -> compareKeys(factKey(factById.getValue(left)),
                factKey(factById.getValue(right))) })
        val proposals = pipeline.run(normalized, ctx = context, seedIds = seeds, index = index,
            aliasesByTail = aliasesByTail.orEmpty(), accountIdentifiers = accountIdentifiers.orEmpty(),
            transferBlockedIds = transferBlocked, refundBlockedIds = refundBlocked,
            merchantRefundSeedIds = seeds, requireUniqueMirrors = requireUniqueMirrors)
        phaseA = pipeline.demoteOverlappingPhaseARefunds(normalized, phaseA, activeRelations,
            context.mirrorPairs())
        val all = (phaseA + proposals).sortedWith(Comparator { left, right ->
            cashRelationStringOrder.compare(serialization.proposalKey(left, normalized),
                serialization.proposalKey(right, normalized))
        })
        val filtered=validation.filterIllegalProposals(normalized,activeRelations,all,validation.buildValidationContext(normalized))
        return RelationPlan(normalized, filtered,
            fingerprints.relationContextDigest(normalized,activeRelations,filtered))
    }
}
