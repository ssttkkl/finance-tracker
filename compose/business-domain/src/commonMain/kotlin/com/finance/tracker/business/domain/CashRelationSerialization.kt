package com.finance.tracker.business.domain

/** 关系计划的稳定引用序列化与余量；宿主解释精确值和平台标量。 */
class CashRelationSerializationRules<A>(private val core:CashRelationCoreRules<A>,
    private val parseAmount:(Any?)->A,private val absolute:(A)->A,private val subtract:(A,A)->A,
    private val canonicalTime:(Any?)->String,private val cacheScalar:(Any?)->Any?) {
    private val references=CashRelationReferences(core)
    fun isHumanDecision(relation:Map<String,Any?>)=references.isHumanDecision(relation)
    fun stableFactRef(factId:String?,factsById:Map<String,RelationFact<A>> = emptyMap())=references.stableFactRef(factId,factsById)
    fun canonicalOccurredAt(value:Any?):String=canonicalTime(value)
    fun proposalKey(proposal:RelationProposal,facts:List<RelationFact<A>> = emptyList())=references.proposalKey(proposal,facts)
    fun factDetailRow(fact: RelationFact<A>): Map<String, Any?> = linkedMapOf(
        "id" to fact.id, "record_id" to fact.recordId, "amount" to fact.amount, "currency" to fact.currency,
        "occurred_at" to fact.occurredAt, "counterparty" to fact.counterparty,
        "counterparty_account" to fact.counterpartyAccount, "counterparty_account_attrs" to fact.counterpartyAccountAttrs,
        "payment_method" to fact.paymentMethod, "note" to fact.note, "record_type" to fact.recordType,
        "record_subtype" to fact.recordSubtype, "account_id" to fact.accountId, "account_name" to fact.accountName,
        "account_type" to fact.accountType, "bill_source" to fact.billSource, "source_type" to fact.billSource,
        "source" to fact.source, "fact_type" to fact.factType, "raw_payload" to fact.rawPayload,
        "relation_metadata" to fact.relationMetadata, "parent_id" to fact.parentId)
    fun initialRemaining(facts: List<RelationFact<A>>, relations: List<Map<String, Any?>>): MutableMap<String, A> {
        val remaining = linkedMapOf<String, A>()
        for (fact in facts) if (core.signedDirection(fact) < 0) remaining[fact.id] = absolute(fact.signedAmount)
        val byId = facts.associateBy { it.id }
        for (relation in relations) {
            if (relation["kind"] != "refund_offset" || relation["status"] != "accepted") continue
            val primary = relation["primary_fact_id"]?.toString().orEmpty()
            val secondary = relation["secondary_fact_id"]?.toString().orEmpty()
            val refund = byId[secondary] ?: continue
            if (primary !in remaining) continue
            val applied = relation["applied_amount"]
            val appliedAmount = if (applied == null || applied.toString().isEmpty()) absolute(refund.signedAmount)
            else runCatching { absolute(parseAmount(applied)) }.getOrElse { absolute(refund.signedAmount) }
            remaining[primary] = subtract(remaining.getValue(primary),appliedAmount)
        }
        return remaining
    }
    fun cacheJsonValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associate { it.key.toString() to cacheJsonValue(it.value) }
        is Set<*> -> value.map(::cacheJsonValue).sortedWith(Comparator { left, right ->
            cashRelationStringOrder.compare(left.toString(), right.toString()) })
        is List<*> -> value.map(::cacheJsonValue)
        else -> cacheScalar(value)
    }
    fun serialize(plan: RelationPlan<A>,integerValue:(Int)->Any={it}): Map<String, Any?> {
        if (plan.contextDigest.isEmpty() || plan.externalContextDigest.isEmpty())
            throw IllegalArgumentException("import_relation_reconfirmation_required")
        val byId = plan.facts.associateBy { it.id }
        fun ref(value: String?): String = stableFactRef(value.orEmpty(), byId)
        val proposals = plan.proposals.sortedWith(Comparator { left, right ->
            cashRelationStringOrder.compare(proposalKey(left, plan.facts), proposalKey(right, plan.facts))
        }).map { proposal ->
            val evidence = proposal.evidence
            linkedMapOf<String, Any?>(
                "proposal_key" to proposalKey(proposal, plan.facts), "kind" to proposal.kind,
                "status" to proposal.status, "rule_id" to proposal.ruleId,
                "confidence" to proposal.confidence, "primary_ref" to ref(proposal.primaryFactId),
                "secondary_ref" to ref(proposal.secondaryFactId), "anchor_ref" to ref(proposal.effectiveAnchorFactId),
                "primary_fact_type" to proposal.primaryFactType, "secondary_fact_type" to proposal.secondaryFactType,
                "created_by" to proposal.createdBy, "open_leg" to proposal.isOpenLeg,
                "evidence" to linkedMapOf<String, Any?>(
                    "amount_delta" to evidence.amountDelta, "time_delta_seconds" to evidence.timeDeltaSeconds?.let(integerValue),
                    "same_currency" to evidence.sameCurrency, "counterparty_similarity" to evidence.counterpartySimilarity,
                    "source_pair" to evidence.sourcePair.toList(), "rule_id" to evidence.ruleId,
                    "candidate_count" to integerValue(evidence.candidateCount), "signals" to evidence.signals,
                    "open_leg" to evidence.openLeg, "anchor_role" to evidence.anchorRole,
                    "candidate_refs" to evidence.candidateFactIds.map(::ref),
                    "extras" to cacheJsonValue(evidence.extras)))
        }
        return linkedMapOf("version" to integerValue(1), "plan_digest" to plan.contextDigest,
            "external_context_digest" to plan.externalContextDigest, "proposals" to proposals)
    }
}
