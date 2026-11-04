package com.finance.tracker.business.domain


const val RELATION_RULE_TRANSFER_PAIR_STRONG_V1 = "transfer_pair.normalized.same_currency.time10.v1"
const val RELATION_RULE_PERSONAL_FX_EXCHANGE_V1 = "transfer_pair.currency_exchange.time60.v1"
const val RELATION_RULE_INTERNAL_ACCOUNT_TRANSFER_V1 = "transfer_pair.internal_account_transfer.v1"
const val RELATION_RULE_CROSS_BORDER_REMITTANCE_V1 = "transfer_pair.cross_border_remittance.v1"
private const val TRANSFER_PAIR_STRONG_SECONDS = 10
private const val PERSONAL_FX_STRONG_SECONDS = 60
private const val TRANSFER_PAIR_MATCH_WINDOW_SECONDS = 300
private const val TARGETED_TRANSFER_WINDOW_SECONDS = 7 * 24 * 60 * 60
private const val TARGETED_TRANSFER_DAY_PAD = 7
private val standardSubtypes = setOf("ordinary_transfer", "withdraw_to_bank", "credit_repayment")
private val targetedSubtypes = setOf("ordinary_transfer", "cross_border_remittance", "internal_account_transfer")

/** 标准化资金移动的转账/购汇配对；不读文本或来源快照。 */
class CashTransferMatchRules<A>(private val core:CashRelationCoreRules<A>,
    private val sameMagnitude:(A,A)->Boolean, private val amountDeltaText:(A,A)->String,
    private val timeDelta:(Any?,Any?)->Long,
    private val createIndex:(List<RelationFact<A>>)->CashFactCandidateIndex<A>) {
    fun validateCounterpartyAccount(value: Any?, attrs: List<Any?>): Boolean {
        val account = value?.toString().orEmpty().trim()
        val normalized = attrs.map { it.toString() }
        val allowed = setOf("full", "tail", "masked", "reconstructed")
        val combinations = setOf(emptyList(), listOf("full"), listOf("tail"), listOf("masked"), listOf("masked", "reconstructed"))
        if (normalized.any { it !in allowed } || normalized !in combinations) return false
        if (account.isEmpty()) return normalized.isEmpty()
        if (normalized.isEmpty()) return false
        if (normalized == listOf("tail") && !Regex("[0-9]{4}").matches(account)) return false
        if (normalized == listOf("full")) {
            if ("*" in account || "＊" in account || account.all { it in '0'..'9' } && account.length <= 4) return false
        }
        if (normalized == listOf("masked") && listOf("*", "＊").none { it in account }) return false
        if (normalized == listOf("masked", "reconstructed")) {
            if (account.any { it !in '0'..'9' } || account.length <= 4) return false
        }
        return true
    }
    private fun fullAccountIdentifier(value: String?): String {
        val text = value.orEmpty().trim()
        if (text.isEmpty() || "*" in text || "＊" in text) return ""
        val identifier = Regex("[ \t\n\u000B\u000C\r\\-()（）]").replace(text, "")
        return if (identifier.isNotEmpty() && identifier.all { it in '0'..'9' } && identifier.length > 4) identifier else ""
    }
    private fun accountTail(value: String?): String {
        val digits = value.orEmpty().filter { it in '0'..'9' }
        return if (digits.length >= 4) digits.takeLast(4) else ""
    }
    private fun mapped(mapping: Map<String, List<String>>?, value: String?): Set<String> {
        if (mapping == null || value.isNullOrEmpty()) return emptySet()
        return mapping[value].orEmpty().map { it.toString() }.toSet()
    }
    fun accountTargets(value: String?, attrs: List<Any?>, accountIdentifiers: Map<String, List<String>>?,
        cardTails: Map<String, List<String>>?): Set<String> {
        if (!validateCounterpartyAccount(value, attrs)) return emptySet()
        val normalized = attrs.map { it.toString() }
        if (normalized == listOf("tail")) return mapped(cardTails, value)
        if (normalized == listOf("full") || normalized == listOf("masked", "reconstructed")) {
            val exact = mapped(accountIdentifiers, fullAccountIdentifier(value))
            if (exact.isNotEmpty()) return exact
            return mapped(cardTails, accountTail(value))
        }
        if (normalized != listOf("masked")) return emptySet()
        val masked = Regex("([0-9]+)\\*+([0-9]+)").matchEntire(value.orEmpty().trim()) ?: return emptySet()
        val prefix = masked.groupValues[1]; val suffix = masked.groupValues[2]
        if (suffix.length < 4) return emptySet()
        val targets = linkedSetOf<String>()
        for ((identifier, accounts) in accountIdentifiers.orEmpty()) {
            val normalizedIdentifier = fullAccountIdentifier(identifier)
            if (normalizedIdentifier.isNotEmpty() && normalizedIdentifier.length > prefix.length + suffix.length &&
                normalizedIdentifier.startsWith(prefix) && normalizedIdentifier.endsWith(suffix))
                targets += accounts.map { it.toString() }
        }
        targets += mapped(cardTails, suffix.takeLast(4))
        return targets
    }
    private fun hasValidAttrs(seed: RelationFact<A>): Boolean =
        validateCounterpartyAccount(seed.counterpartyAccount, seed.counterpartyAccountAttrs) && seed.counterpartyAccount.isNotEmpty()
    private fun registeredAccounts(accountIdentifiers: Map<String, List<String>>?, cardTails: Map<String, List<String>>?): Set<String> =
        listOfNotNull(accountIdentifiers, cardTails).flatMap { it.values.flatten() }.map { it.toString() }.toSet()
    private fun candidateMatchesAccount(seed: RelationFact<A>, candidate: RelationFact<A>,
        accountIdentifiers: Map<String, List<String>>?, cardTails: Map<String, List<String>>?): Pair<Boolean, Boolean> {
        if (!hasValidAttrs(seed)) return true to true
        val targets = accountTargets(seed.counterpartyAccount, seed.counterpartyAccountAttrs, accountIdentifiers, cardTails)
        if (targets.isEmpty()) {
            if (candidate.accountId in registeredAccounts(accountIdentifiers, cardTails)) return false to true
            return true to true
        }
        if (targets.size > 1) return (candidate.accountId in targets) to false
        return (candidate.accountId in targets) to true
    }
    private fun deltaSeconds(seed: RelationFact<A>, candidate: RelationFact<A>): Long =
        timeDelta(seed.occurredAt, candidate.occurredAt)

    private fun proposal(seed: RelationFact<A>, candidates: List<RelationFact<A>>, ruleId: String,
        accepted: Boolean, sameCurrency: Boolean): RelationProposal {
        val ordered = candidates.sortedWith(Comparator { left, right ->
            val delta = deltaSeconds(seed, left).compareTo(deltaSeconds(seed, right))
            if (delta != 0) delta else core.relationOrderKey(left).compare(left, right)
        })
        val best = ordered.first()
        val byId = ordered.associateBy { it.id }
        val evidence = RelationEvidence(
            amountDelta = if (sameCurrency) amountDeltaText(seed.signedAmount,best.signedAmount) else "0",
            timeDeltaSeconds = deltaSeconds(seed, best).toInt(), sameCurrency = sameCurrency, ruleId = ruleId,
            candidateCount = ordered.size,
            candidateFactIds = core.topKCandidateIds(ordered.map { it.id },
                key = Comparator { left, right -> core.relationOrderKey(byId.getValue(left)).compare(byId.getValue(left), byId.getValue(right)) }),
            signals = listOf("opposite_sign", "record_subtype", "counterparty_account"))
        return if (accepted) RelationProposal(kind = "transfer_pair", primaryFactId = seed.id, secondaryFactId = best.id,
            primaryFactType = seed.factType, secondaryFactType = best.factType, status = "accepted",
            ruleId = ruleId, confidence = RELATION_CONFIDENCE_STRONG, evidence = evidence, anchorFactId = seed.id)
        else RelationProposal(kind = "transfer_pair", primaryFactId = seed.id, secondaryFactId = null,
            primaryFactType = seed.factType, secondaryFactType = null, status = "pending_review",
            ruleId = ruleId, confidence = RELATION_CONFIDENCE_WEAK,
            evidence = evidence.copy(openLeg = true, anchorRole = "out"), anchorFactId = seed.id, openLeg = true)
    }

    fun evaluateTransferPair(seed: RelationFact<A>, candidates: List<RelationFact<A>>,
        accountIdentifiers: Map<String, List<String>>? = null, cardTails: Map<String, List<String>>? = null): RelationProposal? {
        if (seed.deleted || seed.cashGranularity == "aggregate" || core.signedDirection(seed) >= 0) return null
        val subtype = seed.recordSubtype
        if (subtype !in standardSubtypes) return null
        val eligible = mutableListOf<RelationFact<A>>()
        var counterpartUnique = true
        for (candidate in candidates) {
            if (candidate.id == seed.id || candidate.deleted || candidate.cashGranularity == "aggregate" ||
                core.signedDirection(candidate) <= 0) continue
            if (candidate.recordSubtype != subtype) continue
            if (subtype == "ordinary_transfer") {
                if (!core.isTransferOutRecord(seed) || !core.isTransferInRecord(candidate)) continue
                if (candidate.accountId == seed.accountId) continue
            }
            if (seed.currency.uppercase() != candidate.currency.uppercase()) continue
            if (!sameMagnitude(seed.signedAmount,candidate.signedAmount)) continue
            if (deltaSeconds(seed, candidate) > TRANSFER_PAIR_MATCH_WINDOW_SECONDS) continue
            val (accountEligible, accountUnique) = candidateMatchesAccount(seed, candidate, accountIdentifiers, cardTails)
            if (!accountEligible) continue
            counterpartUnique = counterpartUnique && accountUnique
            eligible += candidate
        }
        if (eligible.isEmpty()) return null
        return proposal(seed, eligible,
            RELATION_RULE_TRANSFER_PAIR_STRONG_V1,
            eligible.size == 1 && counterpartUnique && deltaSeconds(seed, eligible[0]) <= TRANSFER_PAIR_STRONG_SECONDS, true)
    }

    fun matchPersonalFxExchange(seed: RelationFact<A>, candidates: List<RelationFact<A>>): RelationProposal? {
        if (seed.deleted || seed.cashGranularity == "aggregate" || !core.isFxOutRecord(seed) ||
            seed.recordSubtype != "currency_exchange") return null
        val eligible = candidates.filter {
            !it.deleted && it.cashGranularity != "aggregate" && core.isFxInRecord(it) &&
                it.recordSubtype == "currency_exchange" && it.currency.uppercase() != seed.currency.uppercase() &&
                deltaSeconds(seed, it) <= PERSONAL_FX_STRONG_SECONDS
        }
        if (eligible.isEmpty()) return null
        return proposal(seed, eligible, RELATION_RULE_PERSONAL_FX_EXCHANGE_V1, eligible.size == 1, false)
    }

    fun matchNormalizedSubtypeTransfers(facts: List<RelationFact<A>>, seedIds: Collection<String>? = null,
        index: CashFactCandidateIndex<A>? = null, accountIdentifiers: Map<String, List<String>>? = null,
        cardTails: Map<String, List<String>>? = null): List<RelationProposal> {
        val active = facts.filter { !it.deleted && it.factType == "cash" && it.cashGranularity != "aggregate" }
        val selected = seedIds?.map { it.toString() }?.toSet()
        data class Edge<A>(val delta: Long, val seedId: String, val candidateId: String, val seed: RelationFact<A>,
            val candidate: RelationFact<A>, val ruleId: String, val sameCurrency: Boolean)
        val edges = mutableListOf<Edge<A>>()
        for (seed in active) {
            val subtype = seed.recordSubtype
            if (!core.isTransferOutRecord(seed) || subtype !in targetedSubtypes) continue
            val targets = accountTargets(seed.counterpartyAccount, seed.counterpartyAccountAttrs, accountIdentifiers, cardTails)
            if (targets.size != 1) continue
            val target = targets.single()
            for (candidate in index?.transferInCandidates(seed, TARGETED_TRANSFER_DAY_PAD) ?: active) {
                if (candidate.id == seed.id || candidate.deleted || !core.isTransferInRecord(candidate) ||
                    candidate.accountId != target || deltaSeconds(seed, candidate) > TARGETED_TRANSFER_WINDOW_SECONDS) continue
                val sameCurrency = seed.currency.uppercase() == candidate.currency.uppercase()
                if (sameCurrency && !sameMagnitude(seed.signedAmount,candidate.signedAmount)) continue
                val internal = target == seed.accountId
                if (!sameCurrency && !internal && subtype != "cross_border_remittance") continue
                val rule = when {
                    internal -> RELATION_RULE_INTERNAL_ACCOUNT_TRANSFER_V1
                    else -> RELATION_RULE_CROSS_BORDER_REMITTANCE_V1
                }
                edges += Edge(deltaSeconds(seed, candidate), seed.id, candidate.id, seed, candidate, rule, sameCurrency)
            }
        }
        val proposals = mutableListOf<RelationProposal>()
        val assigned = mutableSetOf<String>()
        val sorted = edges.sortedWith(Comparator { left, right ->
            val delta = left.delta.compareTo(right.delta)
            if (delta != 0) return@Comparator delta
            val seed = core.relationOrderKey(left.seed).compare(left.seed, right.seed)
            if (seed != 0) return@Comparator seed
            core.relationOrderKey(left.candidate).compare(left.candidate, right.candidate)
        })
        for (edge in sorted) {
            if (edge.seedId in assigned || edge.candidateId in assigned) continue
            assigned += edge.seedId; assigned += edge.candidateId
            if (selected != null && edge.seedId !in selected && edge.candidateId !in selected) continue
            proposals += proposal(edge.seed, listOf(edge.candidate), edge.ruleId, true, edge.sameCurrency)
        }
        return proposals
    }

    fun matchTransferPairsPhaseC(facts: List<RelationFact<A>>, seedIds: Collection<String>? = null,
        index: CashFactCandidateIndex<A>? = null, accountIdentifiers: Map<String, List<String>>? = null,
        cardTails: Map<String, List<String>>? = null): List<RelationProposal> {
        val active = facts.filter { !it.deleted && it.factType == "cash" }
        val selected = seedIds?.map { it.toString() }?.toSet()
        val selectedPositive = if (selected != null) active.filter { selected.contains(it.id) && core.signedDirection(it) > 0 }
            .map { it.id }.toSet() else emptySet()
        val candidateIndex = index ?: createIndex(active)
        val proposals = matchNormalizedSubtypeTransfers(active, seedIds, index, accountIdentifiers, cardTails).toMutableList()
        val used = mutableSetOf<String>()
        for (proposal in proposals) {
            used += proposal.primaryFactId
            proposal.secondaryFactId?.let { used += it }
        }
        for (seed in active.sortedWith(core.relationOrderKey(active.first()))) {
            if (seed.id in used || core.signedDirection(seed) >= 0) continue
            val seedSelected = selected == null || seed.id in selected
            val window = if (seed.recordSubtype == "currency_exchange") PERSONAL_FX_STRONG_SECONDS else TRANSFER_PAIR_MATCH_WINDOW_SECONDS
            val candidates = candidateIndex.transferPairCandidates(seed, window).filter { it.id !in used }
            if (!seedSelected && (selectedPositive.isEmpty() || candidates.none { it.id in selectedPositive })) continue
            val proposal = (if (seed.recordSubtype == "currency_exchange") matchPersonalFxExchange(seed, candidates)
            else evaluateTransferPair(seed, candidates, accountIdentifiers, cardTails)) ?: continue
            if (!seedSelected) {
                if (proposal.secondaryFactId != null) {
                    if (proposal.secondaryFactId !in selectedPositive) continue
                } else if (selectedPositive.intersect(proposal.evidence.candidateFactIds.toSet()).isEmpty()) continue
            }
            proposals += proposal
            used += proposal.primaryFactId
            proposal.secondaryFactId?.let { used += it }
        }
        return proposals
    }
}

val cashTransferMatch=CashTransferMatchRules<CashAmount>(cashRelationCore,
    {a,b -> a.numericText().removePrefix("-")==b.numericText().removePrefix("-")},
    ::absoluteCashDifferenceText,::relationTimeDeltaSeconds,::cashFactCandidateIndex)

internal fun absoluteCashDifferenceText(a:CashAmount,b:CashAmount):String {
    val left=a.representation; val right=b.representation
    val scale=maxOf(0L,-left.exponent,-right.exponent).toInt()
    fun digits(value:DecimalRepresentation)=if(value.coefficient=="0") "0"
        else value.coefficient+"0".repeat((value.exponent+scale).toInt())
    val first=digits(left); val second=digits(right)
    val comparison=compareUnsigned(first,second)
    val magnitude=if(comparison>=0) subtractUnsigned(first,second) else subtractUnsigned(second,first)
    return formatSigned(magnitude,scale,comparison)
}
