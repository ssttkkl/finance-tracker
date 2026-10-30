package com.finance.tracker.backend

import java.math.BigDecimal

internal data class RelationEdge(val factAId: String, val factBId: String, val kind: String = "", val subtype: String = "")

internal class MatchContext(var workspaceId: String = "") {
    val usedFactIds = linkedSetOf<String>()
    val acceptedMirrors = mutableListOf<RelationEdge>()
    val acceptedPlatformRefunds = mutableListOf<RelationEdge>()
    val acceptedTransfers = mutableListOf<RelationEdge>()
    var remainingByExpense: Map<String, BigDecimal>? = null
    fun mirrorPairs(): List<Pair<String, String>> = acceptedMirrors.filter { it.factAId.isNotEmpty() && it.factBId.isNotEmpty() }
        .map { it.factAId to it.factBId }
    fun platformRefundPairs(): List<Pair<String, String>> =
        acceptedPlatformRefunds.filter { it.factAId.isNotEmpty() && it.factBId.isNotEmpty() }.map { it.factAId to it.factBId }
}

/** 关系识别 Phase B→D 管线；Phase A 由调用方先执行。 */
internal object CashRelationPipeline {
    fun needsRelationDiscovery(facts: List<RelationFact>, seedIds: Collection<String>? = null): Boolean {
        if (facts.isEmpty()) return false
        if (seedIds == null) return true
        val seeds = seedIds.toSet()
        val selected = facts.filter { it.id in seeds }
        if (selected.isEmpty() || selected.size != seeds.size ||
            selected.any { it.recordType != "consumption" || it.signedAmount.signum() >= 0 }) return true
        val families = facts.map { it to CashRelationCore.sourceGroup(it) }
        return families.map { it.second }.toSet().containsAll(setOf("platform", "bank")) ||
            families.any { it.second in setOf("platform", "bank") && it.first.signedAmount.signum() >= 0 }
    }

    fun matchCanonicalPaymentMirrors(facts: List<RelationFact>, aliasesByTail: Map<String, List<String>> = emptyMap(),
        accountIdentifiers: Map<String, List<String>> = emptyMap(), occupiedFactIds: Set<String> = emptySet(),
        blockedPairs: Set<Pair<String, String>> = emptySet()): List<RelationProposal> {
        val index = CashFactCandidateIndex(facts) { CashRelationCore.sourceGroup(it) }
        return CashMirrorMatch.matchGreedy(facts, aliasesByTail, accountIdentifiers, null, index,
            occupiedFactIds, blockedPairs)
    }

    private fun markUsed(ctx: MatchContext, proposal: RelationProposal) {
        if (proposal.primaryFactId.isNotEmpty()) ctx.usedFactIds += proposal.primaryFactId
        proposal.secondaryFactId?.takeIf { it.isNotEmpty() }?.let { ctx.usedFactIds += it }
    }
    private fun expandRefundBlockedThroughMirrors(blocked: MutableSet<String>, pairs: List<Pair<String, String>>) {
        val adjacency = linkedMapOf<String, MutableSet<String>>()
        for ((left, right) in pairs) {
            if (left.isEmpty() || right.isEmpty()) continue
            adjacency.getOrPut(left) { linkedSetOf() } += right
            adjacency.getOrPut(right) { linkedSetOf() } += left
        }
        val pending = blocked.toMutableList()
        while (pending.isNotEmpty()) {
            val id = pending.removeAt(pending.size - 1)
            for (mirror in adjacency[id].orEmpty()) if (blocked.add(mirror)) pending += mirror
        }
    }
    private fun mirrorComponentsByFact(facts: List<RelationFact>, pairs: List<Pair<String, String>>): Map<String, Set<String>> {
        if (pairs.isEmpty()) return emptyMap()
        val result = linkedMapOf<String, Set<String>>()
        for (component in CashRelationGeometry.buildMirrorComponents(facts.map { it.id }, pairs)) {
            val frozen = component.toSet()
            for (id in frozen) result[id] = frozen
        }
        return result
    }
    private fun refundPairFacts(edge: RelationEdge, byId: Map<String, RelationFact>): Pair<RelationFact, RelationFact>? {
        val left = byId[edge.factAId] ?: return null; val right = byId[edge.factBId] ?: return null
        if (left.signedAmount.signum() < 0 && right.signedAmount.signum() > 0) return left to right
        if (right.signedAmount.signum() < 0 && left.signedAmount.signum() > 0) return right to left
        return null
    }
    private fun refundPairAlignmentScore(expense: RelationProposal, refund: RelationProposal): Pair<Int, Int> {
        val strong = mapOf(RELATION_RULE_PAYMENT_MIRROR_STRONG_V1 to 4, RELATION_RULE_PAYMENT_MIRROR_SHORT_WINDOW_TEXT_V1 to 3,
            RELATION_RULE_PAYMENT_MIRROR_SAME_ACCOUNT_BIZ_DAY_V1 to 3, RELATION_RULE_PAYMENT_MIRROR_BANK_DATE_ONLY_V1 to 3,
            RELATION_RULE_PAYMENT_MIRROR_REFUND_DUAL_SOURCE_V1 to 4)
        val rank = (strong[expense.ruleId] ?: 1) + (strong[refund.ruleId] ?: 1)
        return rank to -(expense.evidence.timeDeltaSeconds!! + refund.evidence.timeDeltaSeconds!!)
    }

    private fun alignRefundPairMirrors(facts: List<RelationFact>, ctx: MatchContext,
        aliasesByTail: Map<String, List<String>>, accountIdentifiers: Map<String, List<String>>,
        index: CashFactCandidateIndex?): Pair<List<RelationProposal>, Set<String>> {
        val byId = facts.filter { !it.deleted }.associateBy { it.id }
        val pairs = ctx.acceptedPlatformRefunds.mapNotNull { refundPairFacts(it, byId) }
        val platformPairs = mutableListOf<Pair<RelationFact, RelationFact>>()
        val bankPairs = mutableListOf<Pair<RelationFact, RelationFact>>()
        for ((expense, refund) in pairs) {
            val groups = setOf(CashRelationCore.sourceGroup(expense), CashRelationCore.sourceGroup(refund))
            if (groups.size != 1) continue
            if (groups.single() == "platform") platformPairs += expense to refund
            else if (groups.single() == "bank") bankPairs += expense to refund
        }
        data class Candidate(val score: Pair<Int, Int>, val platformPair: Pair<String, String>,
            val bankPair: Pair<String, String>, val expense: RelationProposal, val refund: RelationProposal)
        val candidates = mutableListOf<Candidate>()
        val bankByExpense = bankPairs.groupBy { it.first.id }
        for ((platformExpense, platformRefund) in platformPairs) {
            val bankCandidates = if (index == null) bankPairs else {
                val expenseIds = index.mirrorCandidates(platformExpense).map { it.id }.toSet()
                val refundIds = index.mirrorCandidates(platformRefund).map { it.id }.toSet()
                expenseIds.flatMap { bankByExpense[it].orEmpty() }.filter { it.second.id in refundIds }
            }
            for ((bankExpense, bankRefund) in bankCandidates) {
                val expenseProposal = CashMirrorMatch.evaluate(platformExpense, listOf(bankExpense), aliasesByTail, accountIdentifiers) ?: continue
                val refundProposal = CashMirrorMatch.evaluate(platformRefund, listOf(bankRefund), aliasesByTail, accountIdentifiers) ?: continue
                if (expenseProposal.status != "accepted" || refundProposal.status != "accepted") continue
                candidates += Candidate(refundPairAlignmentScore(expenseProposal, refundProposal),
                    platformExpense.id to platformRefund.id, bankExpense.id to bankRefund.id, expenseProposal, refundProposal)
            }
        }
        fun uniqueBest(keyIndex: Int): Set<Int> {
            val grouped = linkedMapOf<Pair<String, String>, MutableList<Pair<Int, Pair<Int, Int>>>>()
            for ((idx, candidate) in candidates.withIndex()) {
                val key = if (keyIndex == 1) candidate.platformPair else candidate.bankPair
                grouped.getOrPut(key) { mutableListOf() } += idx to candidate.score
            }
            val selected = mutableSetOf<Int>()
            for (values in grouped.values) {
                val bestScore = values.map { it.second }.maxWithOrNull(compareBy({ it.first }, { it.second }))!!
                val best = values.filter { it.second == bestScore }.map { it.first }
                if (best.size == 1) selected += best.single()
            }
            return selected
        }
        val platformBest = uniqueBest(1); val bankBest = uniqueBest(2)
        val selected = candidates.withIndex().filter { it.index in platformBest && it.index in bankBest }
            .map { it.value }.sortedWith(compareBy({ it.platformPair.toString() }, { it.bankPair.toString() }))
        val existing = ctx.acceptedMirrors.map { setOf(it.factAId, it.factBId) }.toMutableSet()
        val neighbors = linkedMapOf<String, MutableSet<String>>()
        for (edge in ctx.acceptedMirrors) if (edge.factAId.isNotEmpty() && edge.factBId.isNotEmpty()) {
            neighbors.getOrPut(edge.factAId) { linkedSetOf() } += edge.factBId
            neighbors.getOrPut(edge.factBId) { linkedSetOf() } += edge.factAId
        }
        val candidateFactIds = candidates.flatMap { listOf(it.platformPair.first, it.platformPair.second, it.bankPair.first, it.bankPair.second) }.toSet()
        val aligned = mutableSetOf<String>()
        val out = mutableListOf<RelationProposal>()
        for (candidate in selected) {
            val proposals = listOf(candidate.expense, candidate.refund)
            if (proposals.any { proposal ->
                    setOf(proposal.primaryFactId, proposal.secondaryFactId) !in existing &&
                        ((neighbors[proposal.primaryFactId].orEmpty() - proposal.secondaryFactId!!).isNotEmpty() ||
                            (neighbors[proposal.secondaryFactId].orEmpty() - proposal.primaryFactId).isNotEmpty())
                }) continue
            aligned += listOf(candidate.platformPair.first, candidate.platformPair.second, candidate.bankPair.first, candidate.bankPair.second)
            for (proposal in proposals) {
                val left = proposal.primaryFactId; val right = proposal.secondaryFactId ?: continue
                if (left.isEmpty() || setOf(left, right) in existing) continue
                out += proposal
                existing.add(setOf(left, right))
                neighbors.getOrPut(left) { linkedSetOf() } += right
                neighbors.getOrPut(right) { linkedSetOf() } += left
                ctx.acceptedMirrors += RelationEdge(left, right, "payment_mirror", proposal.subtype)
                markUsed(ctx, proposal)
            }
        }
        return out to (candidateFactIds - aligned)
    }

    fun demoteOverlappingPhaseARefunds(facts: List<RelationFact>, phaseA: List<RelationProposal>,
        acceptedRelations: List<Map<String, Any?>>, mirrorPairs: List<Pair<String, String>>): List<RelationProposal> {
        if (mirrorPairs.isEmpty()) return phaseA
        val byId = facts.associateBy { it.id }
        fun groupFor(id: String?): String = byId[id.orEmpty()]?.let { CashRelationCore.sourceGroup(it) } ?: ""
        val components = mirrorComponentsByFact(facts, mirrorPairs)
        val eventCaps = linkedMapOf<Set<String>, BigDecimal>()
        for (component in components.values.toSet()) {
            val expenses = component.mapNotNull { byId[it] }.filter { it.signedAmount.signum() < 0 }
            if (expenses.isNotEmpty()) eventCaps[component] = expenses.minOf { it.signedAmount.abs() }
        }
        val consumed = linkedMapOf<Set<String>, BigDecimal>()
        val phaseAPlatformBankIds = mutableSetOf<String>()
        val phaseAPlatformComponents = mutableSetOf<Set<String>>()
        for (proposal in phaseA) {
            if (proposal.status != "accepted" || proposal.kind != "refund_offset" || proposal.primaryFactId.isEmpty() ||
                proposal.secondaryFactId.isNullOrEmpty() || groupFor(proposal.primaryFactId) != "platform" ||
                groupFor(proposal.secondaryFactId) != "platform") continue
            components[proposal.secondaryFactId]?.let { phaseAPlatformComponents += it }
        }
        for (proposal in phaseA) {
            if (proposal.status != "accepted" || proposal.kind != "refund_offset" || proposal.primaryFactId.isEmpty() ||
                proposal.secondaryFactId.isNullOrEmpty() || groupFor(proposal.primaryFactId) != "platform" ||
                groupFor(proposal.secondaryFactId) != "platform") continue
            val platformRefund = byId[proposal.secondaryFactId] ?: continue
            for (relation in acceptedRelations) {
                if (relation["kind"] != "refund_offset" || relation["status"] != "accepted") continue
                val bankRefund = byId[relation["secondary_fact_id"]?.toString().orEmpty()] ?: continue
                val bankExpense = byId[relation["primary_fact_id"]?.toString().orEmpty()] ?: continue
                if (CashRelationCore.sourceGroup(bankExpense) != "bank" || CashRelationCore.sourceGroup(bankRefund) != "bank") continue
                val mirror = CashMirrorMatch.evaluate(platformRefund, listOf(bankRefund))
                if (mirror != null && mirror.status == "accepted") phaseAPlatformBankIds += bankRefund.id
            }
        }
        for (relation in acceptedRelations) {
            if (relation["kind"] != "refund_offset" || relation["status"] != "accepted") continue
            val primaryId = relation["primary_fact_id"]?.toString().orEmpty()
            val secondaryId = relation["secondary_fact_id"]?.toString().orEmpty()
            val primary = byId[primaryId] ?: continue
            val refund = byId[secondaryId] ?: continue
            val component = components[primaryId] ?: continue
            if (primary.signedAmount.signum() >= 0 || refund.signedAmount.signum() <= 0) continue
            if (groupFor(primary.id) == "bank" && secondaryId in phaseAPlatformBankIds) continue
            if (groupFor(primary.id) == "bank" && components[secondaryId] != null && components[secondaryId] in phaseAPlatformComponents) continue
            consumed[component] = (consumed[component] ?: BigDecimal.ZERO) + refund.signedAmount.abs()
        }
        val adjusted = mutableListOf<RelationProposal>()
        for (proposal in phaseA) {
            if (proposal.status != "accepted" || proposal.kind != "refund_offset" || proposal.primaryFactId.isEmpty() ||
                proposal.secondaryFactId.isNullOrEmpty()) { adjusted += proposal; continue }
            val component = components[proposal.primaryFactId]
            val refund = byId[proposal.secondaryFactId]
            val cap = component?.let { eventCaps[it] }
            if (component == null || cap == null || refund == null || refund.signedAmount.signum() <= 0) { adjusted += proposal; continue }
            val refundAmount = CashRelationGeometry.asDecimal(proposal.evidence.extras["refund_amount"]).takeIf { it.signum() > 0 }
                ?: refund.signedAmount.abs()
            val used = consumed[component] ?: BigDecimal.ZERO
            if (used + refundAmount > cap) adjusted += proposal.copy(status = "pending_review")
            else { consumed[component] = used + refundAmount; adjusted += proposal }
        }
        return adjusted
    }

    fun shareRefundRemainingAcrossMirrorEvents(facts: List<RelationFact>, components: Map<String, Set<String>>,
        remainingByExpense: Map<String, BigDecimal>): MutableMap<String, BigDecimal> {
        val shared = remainingByExpense.toMutableMap()
        if (components.isEmpty()) return shared
        val byId = facts.associateBy { it.id }
        val visited = mutableSetOf<Set<String>>()
        for (component in components.values) {
            if (!visited.add(component)) continue
            val expenses = component.mapNotNull { byId[it] }.filter { it.signedAmount.signum() < 0 }
            if (expenses.size < 2) continue
            val eventAmount = expenses.minOf { it.signedAmount.abs() }
            var consumed = BigDecimal.ZERO
            for (fact in expenses) {
                val factAmount = fact.signedAmount.abs()
                val factRemaining = shared[fact.id] ?: factAmount
                consumed += (factAmount - factRemaining).max(BigDecimal.ZERO)
            }
            val eventRemaining = (eventAmount - consumed).max(BigDecimal.ZERO)
            for (fact in expenses) shared[fact.id] = eventRemaining
        }
        return shared
    }

    fun bankRefundSeedIds(facts: List<RelationFact>, blocked: Set<String>): List<String> =
        facts.sortedWith(CashRelationCore.relationOrderKey(facts.firstOrNull() ?: RelationFact("", BigDecimal.ZERO, "", "")))
            .filter { it.id !in blocked && CashRelationCore.sourceGroup(it) == "bank" && it.signedAmount.signum() > 0 &&
                CashRefundSignals.hasRefundSignalForFact(it) }.map { it.id }

    fun run(facts: List<RelationFact>, ctx: MatchContext = MatchContext(), seedIds: Collection<String>? = null,
        index: CashFactCandidateIndex? = null, aliasesByTail: Map<String, List<String>> = emptyMap(),
        accountIdentifiers: Map<String, List<String>> = emptyMap(), transferBlockedIds: Set<String> = emptySet(),
        refundBlockedIds: Set<String> = emptySet(), merchantRefundSeedIds: Collection<String>? = null,
        requireUniqueMirrors: Boolean = false): List<RelationProposal> {
        if (ctx.remainingByExpense == null) ctx.remainingByExpense = linkedMapOf()
        val transferBlocked = transferBlockedIds.toMutableSet()
        val refundBlocked = refundBlockedIds.toMutableSet()
        transferBlocked += ctx.usedFactIds; refundBlocked += ctx.usedFactIds
        val active = facts.filter { !it.deleted }.sortedWith(CashRelationCore.relationOrderKey(facts.firstOrNull()
            ?: RelationFact("", BigDecimal.ZERO, "", "")))
        val byId = active.associateBy { it.id }
        val out = mutableListOf<RelationProposal>()
        val (aligned, blockedRefundIds) = alignRefundPairMirrors(active, ctx, aliasesByTail, accountIdentifiers, index)
        out += aligned
        val occupiedMirrorIds = ctx.acceptedMirrors.flatMap { listOf(it.factAId, it.factBId) }.filter { it.isNotEmpty() }.toMutableSet()
        occupiedMirrorIds += blockedRefundIds
        val mirrorProps = CashMirrorMatch.matchGreedy(active, aliasesByTail, accountIdentifiers, seedIds, index,
            occupiedMirrorIds, emptySet(), requireUniqueMirrors)
        for (proposal in mirrorProps) {
            out += proposal
            if (proposal.status == "accepted" && proposal.primaryFactId.isNotEmpty() && !proposal.secondaryFactId.isNullOrEmpty()) {
                ctx.acceptedMirrors += RelationEdge(proposal.primaryFactId, proposal.secondaryFactId, "payment_mirror", proposal.subtype)
                markUsed(ctx, proposal)
            }
        }
        expandRefundBlockedThroughMirrors(refundBlocked, ctx.mirrorPairs())
        val mirrorComponents = mirrorComponentsByFact(active, ctx.mirrorPairs())
        val transferProps = CashTransferMatch.matchTransferPairsPhaseC(active, seedIds, index, accountIdentifiers, aliasesByTail)
        for (proposal in transferProps) {
            if (proposal.primaryFactId in transferBlocked || proposal.secondaryFactId?.let { it in transferBlocked } == true) continue
            out += proposal
            transferBlocked += proposal.primaryFactId
            proposal.secondaryFactId?.let { transferBlocked += it }
            if (proposal.status == "accepted" && proposal.primaryFactId.isNotEmpty() && !proposal.secondaryFactId.isNullOrEmpty()) {
                ctx.acceptedTransfers += RelationEdge(proposal.primaryFactId, proposal.secondaryFactId, "transfer_pair", proposal.subtype)
                markUsed(ctx, proposal)
            }
        }
        val bankSeeds = bankRefundSeedIds(active, refundBlocked)
        val diamond = CashRefundDiamond.match(active, ctx.mirrorPairs(), ctx.platformRefundPairs(), bankSeeds)
        for (proposal in diamond) {
            out += proposal
            refundBlocked += proposal.primaryFactId
            proposal.secondaryFactId?.let { refundBlocked += it }
            if (proposal.status == "accepted") markUsed(ctx, proposal)
            expandRefundBlockedThroughMirrors(refundBlocked, ctx.mirrorPairs())
        }
        val refundSeeds = if (merchantRefundSeedIds == null) active.filter { it.factType == "cash" }
            else merchantRefundSeedIds.mapNotNull { byId[it] }
        val remaining = shareRefundRemainingAcrossMirrorEvents(active, mirrorComponents, ctx.remainingByExpense!!)
        for (seed in refundSeeds) {
            if (seed.id in refundBlocked || seed.factType != "cash") continue
            val others = if (index != null) index.refundCandidates(seed).filter { it.id != seed.id && it.id !in refundBlocked }
            else active.filter { it.id != seed.id && it.id !in refundBlocked }
            val eventIds = linkedMapOf<String, String>()
            for (fact in others) {
                val component = mirrorComponents[fact.id] ?: setOf(fact.id)
                val componentFacts = component.mapNotNull { byId[it] }
                val representative = CashRelationGeometry.canonicalMirrorFact(componentFacts)
                    ?: componentFacts.minWith(CashRelationCore.relationOrderKey(componentFacts.first()))
                eventIds[fact.id] = representative.id
            }
            val proposal = CashRefundMatch.evaluate(seed, others, remaining, eventIds) ?: continue
            out += proposal
            if (proposal.kind == "refund_offset" && proposal.status == "accepted" && proposal.primaryFactId.isNotEmpty() &&
                !proposal.secondaryFactId.isNullOrEmpty()) {
                refundBlocked += proposal.secondaryFactId
                if (proposal.anchorFactId.isNotEmpty()) refundBlocked += proposal.anchorFactId
                val refundAmount = CashRelationGeometry.asDecimal(proposal.evidence.extras["refund_amount"])
                val component = mirrorComponents[proposal.primaryFactId] ?: setOf(proposal.primaryFactId)
                val eventRemaining = (remaining[proposal.primaryFactId]
                    ?: byId[proposal.primaryFactId]?.signedAmount?.abs() ?: BigDecimal.ZERO) - refundAmount
                for (expenseId in component) if (byId[expenseId]?.signedAmount?.signum() ?: 0 < 0) remaining[expenseId] = eventRemaining
                if (eventRemaining.signum() <= 0) refundBlocked += component
            } else {
                refundBlocked += proposal.primaryFactId
                proposal.secondaryFactId?.let { refundBlocked += it }
                if (proposal.anchorFactId.isNotEmpty()) refundBlocked += proposal.anchorFactId
            }
            expandRefundBlockedThroughMirrors(refundBlocked, ctx.mirrorPairs())
        }
        return out
    }
}
