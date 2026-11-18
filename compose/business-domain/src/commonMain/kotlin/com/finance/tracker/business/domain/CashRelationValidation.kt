package com.finance.tracker.business.domain

data class ProjectionValidationContext<A,I:Comparable<I>,T:Comparable<T>>(val activeFacts: List<RelationFact<A>>,
    val projectionFacts: List<CashProjectionFact<I,T>>, val componentToParent: List<Pair<String, Long>>,
    val components: List<CashRelationEndpoint> = emptyList())

/** Proposal validation uses the same projection and graph rules as actual writes. */
class CashRelationValidationRules<A,I:Comparable<I>,T:Comparable<T>>(private val core:CashRelationCoreRules<A>,
    private val serialization:CashRelationSerializationRules<A>,private val values:CashProjectionValues<I,T>,
    private val graph:CashRelationGraphRules<I,T>,private val plainText:(A)->String,private val parseTime:(Any?)->T,
    private val idFromLong:(Long)->I,private val storedId:(I)->Long,private val decimalText:(Any?)->String,
    private val appliedText:(Any?)->String,private val encode:(Map<String,Any?>)->String) {
    private fun snapshotId(reference:String,used:MutableSet<Long>)=cashRelationSnapshotId(reference,used)
    private fun reconfirm():Nothing=throw IllegalArgumentException("import_relation_reconfirmation_required")
    private fun parentKey(fact: RelationFact<A>): String =
        if (!fact.parentId.isNullOrEmpty()) "parent:${fact.parentId}"
        else "fact:${serialization.stableFactRef(fact.id, mapOf(fact.id to fact))}"
    private fun relationPayload(proposal: RelationProposal): Map<String, Any?> {
        val applied = if (proposal.kind == "refund_offset") {
            val amount = LedgerArithmeticValue.fromCanonical(decimalText(proposal.evidence.extras["refund_amount"])).abs()
            if (amount.signum > 0) amount.toPlainString() else null
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

    fun buildValidationContext(facts: List<RelationFact<A>>): ProjectionValidationContext<A,I,T> {
        val active = facts.filter { !it.deleted }
        val byComponent = active.associateBy { it.id }
        val groups = linkedMapOf<String, MutableList<RelationFact<A>>>()
        for (fact in active) groups.getOrPut(parentKey(fact)) { mutableListOf() } += fact
        val used = mutableSetOf<Long>()
        val usedAccountIds = mutableSetOf<Long>()
        val accountIds = active.mapNotNull { it.accountId.takeIf { value -> value.isNotEmpty() } }.distinct().sorted()
            .associateWith { snapshotId(it, usedAccountIds) }
        val componentToParent = linkedMapOf<String, Long>()
        val projectionFacts = mutableListOf<CashProjectionFact<I,T>>()
        val components = mutableListOf<CashRelationEndpoint>()
        for ((_, members) in groups.entries.sortedBy { it.key }) {
            val ordered = members.sortedWith(core.relationOrderKey(members.first()))
            val first = ordered.first()
            val currency = first.currency.uppercase()
            if (ordered.any { it.currency.uppercase() != currency }) throw CashProjectionDomainFailure("projection.invalid_fact")
            val projectionId = snapshotId(parentKey(first), used)
            val aggregate = ordered.size > 1 || ordered.any { it.cashGranularity == "aggregate" }
            for (member in ordered) {
                componentToParent[member.id] = projectionId
                components += CashRelationEndpoint(member.id, projectionId.toString(), CashAmount.parseOrNull(plainText(member.signedAmount)),
                    currency, accountIds[member.accountId]?.toString(), member.recordType, member.recordSubtype)
            }
            val occurred = parseTime(first.occurredAt)
            var amount = LedgerArithmeticValue.ZERO
            for (item in ordered) amount=amount.add(LedgerArithmeticValue.fromCanonical(plainText(item.signedAmount)))
            projectionFacts += CashProjectionFact.create(values,idFromLong(projectionId), if (aggregate) null else accountIds[first.accountId], occurred,
                amount.toPlainString(), currency, first.counterparty, null, first.note, first.billSource.ifEmpty { first.source },
                serialization.stableFactRef(first.id, byComponent), null,
                if (aggregate) "aggregate" else "atomic", "posted", null, false,
                stableSourceKey = first.stableSourceKey, recordOrigin = first.recordOrigin)
        }
        return ProjectionValidationContext(active, projectionFacts,
            componentToParent.entries.map { it.key to it.value }.sortedBy { it.first }, components)
    }

    fun localizedValidationInputs(context: ProjectionValidationContext<A,I,T>, acceptedRelations: List<Map<String, Any?>>,
        companions: List<RelationProposal>, proposal: RelationProposal): Triple<List<Map<String, Any?>>, List<RelationProposal>, ProjectionValidationContext<A,I,T>>? {
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
        val localFacts = context.projectionFacts.filter { storedId(it.id) in affected }
        if (localFacts.map { storedId(it.id) }.toSet() != affected) return null
        return Triple(localRelations, localCompanions, context.copy(projectionFacts = localFacts))
    }

    fun validationReason(facts: List<RelationFact<A>>, acceptedRelations: List<Map<String, Any?>>, proposal: RelationProposal,
        companionProposals: List<RelationProposal> = emptyList(),
        validationContext: ProjectionValidationContext<A,I,T>? = null): String? {
        if (proposal.secondaryFactId.isNullOrEmpty() || proposal.isOpenLeg) return null
        return try {
            val context = validationContext ?: buildValidationContext(facts)
            val componentToParent = context.componentToParent.toMap()
            val relationPayloads = acceptedRelations.filter { it["status"] == "accepted" }.map(::relationPayload).toMutableList()
            val seenProposals = mutableSetOf<String>()
            val seenRelationKeys = relationPayloads.map(::relationKey).toMutableSet()
            for (candidate in companionProposals) {
                if (candidate.secondaryFactId.isNullOrEmpty() || candidate.isOpenLeg) continue
                val key = serialization.proposalKey(candidate, context.activeFacts)
                if (!seenProposals.add(key)) continue
                val payload = relationPayload(candidate)
                if (seenRelationKeys.add(relationKey(payload))) relationPayloads += payload
            }
            val candidateKey = serialization.proposalKey(proposal, context.activeFacts)
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
                val reference = encode(linkedMapOf("kind" to payload["kind"],
                    "primary" to primaryComponent, "secondary" to secondaryComponent, "index" to SourceJsonInteger(index)))
                val relationId = snapshotId(reference, usedRelationIds)
                val applied = payload["applied_amount"]?.let {
                    CashDecimalLiteral(appliedText(it))
                }
                val roles = inferCashRelationRoles(payload["kind"], components.getValue(primaryComponent),
                    components.getValue(secondaryComponent))
                edges += CashRelationGraphEdge(relationId, payload["kind"], primary.toString(), secondary.toString(),
                    primaryComponent, secondaryComponent, roles.first, roles.second, applied)
            }
            graph.build(context.projectionFacts, context.components, edges)
            null
        } catch (_: CashProjectionFailure) { "projection_invalid" }
        catch (_: CashRelationFailure) { "projection_invalid" }
        catch (_: IllegalArgumentException) { "projection_invalid" }
        catch (_: ArithmeticException) { "projection_invalid" }
    }

    fun filterIllegalProposals(facts: List<RelationFact<A>>, activeRelations: List<Map<String, Any?>>,
        proposals: List<RelationProposal>, validationContext: ProjectionValidationContext<A,I,T>? = null): List<RelationProposal> {
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

    /** 在写入前确认缓存的自动关系图仍满足投影不变量。 */
    fun validateCachedPlanLegality(facts: List<RelationFact<A>>, acceptedRelations: List<Map<String, Any?>>,
        proposals: List<RelationProposal>, validationContext: ProjectionValidationContext<A,I,T>? = null) {
        val context = validationContext ?: buildValidationContext(facts)
        val companions = proposals.filter { it.createdBy == "system" && it.status == "accepted" &&
            !it.secondaryFactId.isNullOrEmpty() && !it.isOpenLeg }
        val batchReason = companions.firstOrNull()?.let {
            validationReason(facts, acceptedRelations, it, companions, context)
        }
        for (proposal in proposals) {
            if (proposal.createdBy != "system" || proposal.secondaryFactId.isNullOrEmpty() || proposal.isOpenLeg) continue
            val reason = if (proposal.status == "accepted") batchReason else {
                val localized = localizedValidationInputs(context, acceptedRelations,
                    companions, proposal)
                if (localized == null) validationReason(facts, acceptedRelations,
                    proposal, companions + proposal, context)
                else validationReason(facts, localized.first, proposal,
                    localized.second + proposal, localized.third)
            }
            if (reason != null) reconfirm()
        }
    }

}
