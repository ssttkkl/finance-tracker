package com.finance.tracker.business.domain



const val RELATION_RULE_PAYMENT_MIRROR_STRONG_V1 = "payment_mirror.platform_bank.exact.time10.cross.v2"
const val RELATION_RULE_PAYMENT_MIRROR_SHORT_WINDOW_TEXT_V1 = "payment_mirror.platform_bank.short_window.text.unique.v3"
const val RELATION_RULE_PAYMENT_MIRROR_WEAK_V1 = "payment_mirror.platform_bank.near.weak.v2"
const val RELATION_RULE_PAYMENT_MIRROR_BANK_DATE_ONLY_V1 = "payment_mirror.bank_date_only.v1"
const val RELATION_RULE_PAYMENT_MIRROR_SAME_ACCOUNT_BIZ_DAY_V1 = "payment_mirror.same_account.exact.business_day.v1"
const val RELATION_RULE_PAYMENT_MIRROR_REFUND_DUAL_SOURCE_V1 = "payment_mirror.refund_dual_source.v1"
private const val PAYMENT_MIRROR_STRONG_SECONDS = 10
private const val PAYMENT_MIRROR_SHORT_WINDOW_SECONDS = 60
private const val OPEN_LEG_TOP_K = 20
private val icbcMirrorSources = setOf("icbc_credit", "icbc_debit")

/** 平台×银行同笔支付镜像；纯匹配，不写关系。 */
class CashMirrorMatchRules<A>(private val core:CashRelationCoreRules<A>,private val geometry:CashRelationGeometryRules<A>,
    private val money:(A)->String,private val sameAmount:(A,A)->Boolean,private val absoluteDelta:(A,A)->String,
    private val epochSeconds:(Any?)->Long,private val crossVerify:(RelationFact<A>,RelationFact<A>)->Boolean,
    private val integerMetadata:(Int)->Any = {it}) {
    private fun channel(fact: RelationFact<A>): String = fact.billSource.ifEmpty { fact.source }.trim().lowercase()
    private fun groupKey(fact: RelationFact<A>): String? {
        val day = runCatching { geometry.businessDayUtc(fact) }.getOrNull() ?: return null
        if (core.signedDirection(fact) == 0) return null
        return listOf(fact.accountId.trim(), fact.counterparty.trim(), fact.currency.trim().uppercase(),
            money(fact.signedAmount), if (core.signedDirection(fact) > 0) "1" else "-1", day.toString())
            .joinToString("\u0000")
    }
    private fun complete(fact: RelationFact<A>): Boolean =
        fact.accountId.trim().isNotEmpty() && fact.counterparty.trim().isNotEmpty() &&
            fact.currency.trim().isNotEmpty() && channel(fact).isNotEmpty()

    private fun paymentMethodAliasMatch(platformFact: RelationFact<A>, accountId: String,
        aliasesByTail: Map<String, List<String>>, accountIdentifiers: Map<String, List<String>>): String {
        val paymentMethod = platformFact.paymentMethod
        val digits = paymentMethod.filter { it in '0'..'9' }
        if (digits.length < 4) return ""
        val fullAccounts = accountIdentifiers[digits].orEmpty().map { it.toString() }.toSet()
        if (digits.length > 4 && !Regex("[*＊]").containsMatchIn(paymentMethod) && fullAccounts.size == 1 &&
            accountId in fullAccounts) return "exact"
        val tail = digits.takeLast(4)
        val tailAccounts = linkedSetOf<String>()
        aliasesByTail[tail].orEmpty().forEach { tailAccounts += it.toString() }
        for ((identifier, mapped) in accountIdentifiers) if (identifier.toString().endsWith(tail))
            mapped.forEach { tailAccounts += it.toString() }
        return if (tailAccounts.size == 1 && accountId in tailAccounts) "tail" else ""
    }

    fun evaluate(seed: RelationFact<A>, candidates: List<RelationFact<A>>,
        aliasesByTail: Map<String, List<String>> = emptyMap(),
        accountIdentifiers: Map<String, List<String>> = emptyMap(),
        requireUniqueMirrors: Boolean = false): RelationProposal? {
        if (seed.deleted || seed.factType != "cash") return null
        val seedAmount = seed.signedAmount
        if (core.signedDirection(seed) == 0) return null
        val seedGroup = core.sourceGroup(seed)
        if (seedGroup !in setOf("platform", "bank")) return null
        val seedTails = geometry.extractCardTails(seed.text)
        data class Match<A>(val candidate: RelationFact<A>, val evidence: RelationEvidence, val status: String,
            val confidence: String, val score: Int)
        val matches = mutableListOf<Match<A>>()
        for (candidate in candidates) {
            if (candidate.id == seed.id || candidate.deleted || candidate.factType != "cash") continue
            if (candidate.accountId != seed.accountId) continue
            val candidateGroup = core.sourceGroup(candidate)
            if (candidateGroup !in setOf("platform", "bank")) continue
            if (setOf(seedGroup, candidateGroup) != setOf("platform", "bank")) continue
            if (candidate.currency.uppercase() != seed.currency.uppercase()) continue
            val candidateAmount = candidate.signedAmount
            if (!((core.isPaymentMirrorExpense(seed) && core.isPaymentMirrorExpense(candidate)) ||
                    (core.isPaymentMirrorRefund(seed) && core.isPaymentMirrorRefund(candidate)))) continue
            val exact = sameAmount(seedAmount,candidateAmount)
            val dt = runCatching { geometry.timeDeltaSeconds(seed.occurredAt, candidate.occurredAt) }.getOrNull() ?: continue
            val sameDay = runCatching { geometry.sameCalendarDay(seed.occurredAt, candidate.occurredAt) }.getOrNull() ?: false
            val candidateTails = geometry.extractCardTails(candidate.text)
            val sharedTails = seedTails.intersect(candidateTails)
            var aliasHit = false
            for (tail in seedTails + candidateTails) {
                val accounts = aliasesByTail[tail].orEmpty()
                if (accounts.isEmpty()) continue
                if (seed.accountId in accounts || candidate.accountId in accounts ||
                    seed.accountName in accounts || candidate.accountName in accounts) { aliasHit = true; break }
            }
            val cross = crossVerify(seed,candidate)
            val paymentMatch = paymentMethodAliasMatch(if (seedGroup == "platform") seed else candidate,
                seed.accountId, aliasesByTail, accountIdentifiers)
            aliasHit = aliasHit || paymentMatch.isNotEmpty()
            val cardOk = sharedTails.isNotEmpty() || aliasHit
            val textOrCard = cross || cardOk
            val seedCounterparty = seed.counterparty.trim(); val candidateCounterparty = candidate.counterparty.trim()
            val counterpartyEvidence = seedCounterparty.length >= 2 && candidateCounterparty.length >= 2 &&
                (seedCounterparty in candidateCounterparty || candidateCounterparty in seedCounterparty)
            val platformFact: RelationFact<A>; val bankFact: RelationFact<A>
            if (seedGroup == "platform") { platformFact = seed; bankFact = candidate } else { platformFact = candidate; bankFact = seed }
            val lag = runCatching {
                (epochSeconds(bankFact.occurredAt) -
                    epochSeconds(platformFact.occurredAt)).toInt()
            }.getOrNull() ?: continue
            val platformNotAfter = lag >= 0
            val bizSameDay = runCatching { geometry.sameBusinessDayUtc(seed, candidate) }.getOrNull() ?: false
            val bankDateOnly = (seedGroup == "bank" && geometry.factIsBankDateOnly(seed)) ||
                (candidateGroup == "bank" && geometry.factIsBankDateOnly(candidate))
            val bothRefundish = core.isPaymentMirrorRefund(seed) && core.isPaymentMirrorRefund(candidate)
            val hasIdentity = textOrCard || counterpartyEvidence
            var score: Int
            var status: String
            var conf = RELATION_CONFIDENCE_WEAK
            var rule = RELATION_RULE_PAYMENT_MIRROR_WEAK_V1
            val outer = 5 * 60L
            when {
                exact && core.signedDirection(seed) > 0 && core.signedDirection(candidate) > 0 && bothRefundish &&
                    (bankDateOnly || dt <= PAYMENT_MIRROR_SHORT_WINDOW_SECONDS || bizSameDay) &&
                    (!bankDateOnly || hasIdentity) -> {
                    status = "accepted"; conf = RELATION_CONFIDENCE_STRONG
                    rule = RELATION_RULE_PAYMENT_MIRROR_REFUND_DUAL_SOURCE_V1; score = 4550
                }
                exact && bizSameDay && (!bankDateOnly || hasIdentity) -> {
                    status = "accepted"; conf = RELATION_CONFIDENCE_STRONG
                    if (bankDateOnly) { rule = RELATION_RULE_PAYMENT_MIRROR_BANK_DATE_ONLY_V1; score = 4500 }
                    else { rule = RELATION_RULE_PAYMENT_MIRROR_SAME_ACCOUNT_BIZ_DAY_V1; score = 4480 }
                }
                exact && bizSameDay && bankDateOnly -> { status = "pending_review"; score = 1500 }
                exact && dt <= PAYMENT_MIRROR_STRONG_SECONDS && textOrCard -> {
                    status = "accepted"; conf = RELATION_CONFIDENCE_STRONG
                    rule = RELATION_RULE_PAYMENT_MIRROR_STRONG_V1; score = 4000 - dt.toInt()
                }
                exact && textOrCard && dt <= PAYMENT_MIRROR_SHORT_WINDOW_SECONDS && platformNotAfter -> {
                    status = "accepted"; conf = RELATION_CONFIDENCE_STRONG
                    rule = RELATION_RULE_PAYMENT_MIRROR_SHORT_WINDOW_TEXT_V1; score = 2000 - dt.toInt()
                }
                exact && textOrCard && dt > PAYMENT_MIRROR_SHORT_WINDOW_SECONDS && dt <= outer -> {
                    status = "pending_review"; score = (1400 - minOf(dt, 1399L)).toInt()
                }
                exact && textOrCard && sameDay && dt > outer -> { status = "pending_review"; score = 1200 }
                !exact && textOrCard && dt <= PAYMENT_MIRROR_SHORT_WINDOW_SECONDS -> {
                    status = "pending_review"; score = (500 - dt).toInt()
                }
                else -> continue
            }
            val signals = listOfNotNull("platform_bank", if (exact) "exact_amount" else "amount_delta",
                if (dt <= PAYMENT_MIRROR_STRONG_SECONDS) "time_window" else "short_window",
                if (platformNotAfter) "platform_not_after_bank" else "platform_after_bank",
                if (sharedTails.isNotEmpty()) "card_tail" else null, if (aliasHit) "alias" else null,
                if (cross) "text_cross" else null, "same_account")
            val evidence = RelationEvidence(amountDelta = absoluteDelta(seedAmount,candidateAmount), timeDeltaSeconds = dt.toInt(),
                sameCurrency = true, counterpartySimilarity = seed.counterparty.ifEmpty { candidate.counterparty },
                sourcePair = (seed.billSource.ifEmpty { seed.source }) to (candidate.billSource.ifEmpty { candidate.source }),
                ruleId = rule, signals = signals, extras = linkedMapOf("lag_bank_minus_platform" to integerMetadata(lag)))
            matches += Match(candidate, evidence, status, conf, score)
        }
        if (matches.isEmpty()) return null
        val sorted = matches.sortedWith(Comparator { left, right ->
            val score = right.score.compareTo(left.score)
            if (score != 0) return@Comparator score
            val time = left.evidence.timeDeltaSeconds!!.compareTo(right.evidence.timeDeltaSeconds!!)
            if (time != 0) return@Comparator time
            core.relationOrderKey(left.candidate).compare(left.candidate, right.candidate)
        })
        val best = sorted.first()
        var status = best.status
        var conf = best.confidence
        var rule = best.evidence.ruleId
        val strong = sorted.filter { it.status == "accepted" && zeroDelta(it.evidence.amountDelta) }
        val nearestOk = setOf(RELATION_RULE_PAYMENT_MIRROR_SAME_ACCOUNT_BIZ_DAY_V1,
            RELATION_RULE_PAYMENT_MIRROR_BANK_DATE_ONLY_V1, RELATION_RULE_PAYMENT_MIRROR_REFUND_DUAL_SOURCE_V1)
        if (status == "accepted" && strong.size != 1 && rule !in nearestOk) {
            status = "pending_review"; conf = RELATION_CONFIDENCE_WEAK; rule = RELATION_RULE_PAYMENT_MIRROR_WEAK_V1
        } else if (status == "accepted" && rule == RELATION_RULE_PAYMENT_MIRROR_SHORT_WINDOW_TEXT_V1) {
            val shortText = sorted.filter { zeroDelta(it.evidence.amountDelta) &&
                it.evidence.timeDeltaSeconds!! <= PAYMENT_MIRROR_SHORT_WINDOW_SECONDS && it.status == "accepted" }
            if (shortText.size != 1) { status = "pending_review"; conf = RELATION_CONFIDENCE_WEAK; rule = RELATION_RULE_PAYMENT_MIRROR_WEAK_V1 }
        }
        if (status == "accepted" && (requireUniqueMirrors || channel(seed) in icbcMirrorSources || channel(best.candidate) in icbcMirrorSources)) {
            val equal = sorted.filter { it.score == best.score &&
                (!requireUniqueMirrors || it.evidence.timeDeltaSeconds == best.evidence.timeDeltaSeconds) }
            if (equal.size > 1) { status = "pending_review"; conf = RELATION_CONFIDENCE_WEAK; rule = RELATION_RULE_PAYMENT_MIRROR_WEAK_V1 }
        }
        val candidateIds = sorted.take(OPEN_LEG_TOP_K).map { it.candidate.id }
        val evidence = best.evidence.copy(candidateCount = sorted.size, candidateFactIds = candidateIds, ruleId = rule)
        val primary: RelationFact<A>; val secondary: RelationFact<A>
        if (geometry.platformScore(best.candidate) > geometry.platformScore(seed)) {
            primary = best.candidate; secondary = seed
        } else { primary = seed; secondary = best.candidate }
        return RelationProposal(kind = "payment_mirror", primaryFactId = primary.id, secondaryFactId = secondary.id,
            status = status, ruleId = rule, confidence = conf, evidence = evidence)
    }

    private fun pendingGroup(left: RelationFact<A>, right: RelationFact<A>, candidateIds: List<String>,
        aliasesByTail: Map<String, List<String>>, accountIdentifiers: Map<String, List<String>>): RelationProposal? {
        val proposal = evaluate(left, listOf(right), aliasesByTail, accountIdentifiers) ?: return null
        val evidence = proposal.evidence.copy(candidateCount = candidateIds.size, candidateFactIds = candidateIds,
            ruleId = RELATION_RULE_PAYMENT_MIRROR_WEAK_V1,
            signals = (proposal.evidence.signals + "candidate_group_pending").distinct())
        return proposal.copy(status = "pending_review", confidence = RELATION_CONFIDENCE_WEAK,
            ruleId = RELATION_RULE_PAYMENT_MIRROR_WEAK_V1, evidence = evidence)
    }

    private fun deterministicGroups(facts: List<RelationFact<A>>, seedIds: Set<String>?,
        aliasesByTail: Map<String, List<String>>, accountIdentifiers: Map<String, List<String>>,
        blockedPairs: Set<Pair<String, String>>): Pair<List<RelationProposal>, Set<String>> {
        data class Grouped<A>(val group: String, val channelValue: String, val fact: RelationFact<A>)
        val grouped = linkedMapOf<String, MutableList<Grouped<A>>>()
        val handled = mutableSetOf<String>()
        for (fact in facts) {
            if (!(core.isPaymentMirrorExpense(fact) || core.isPaymentMirrorRefund(fact))) continue
            val group = core.sourceGroup(fact)
            if (group !in setOf("platform", "bank")) continue
            val key = groupKey(fact) ?: continue
            if (channel(fact).isEmpty()) continue
            grouped.getOrPut(key) { mutableListOf() } += Grouped(group, channel(fact), fact)
        }
        val proposals = mutableListOf<RelationProposal>()
        val claimed = mutableSetOf<String>()
        fun blocked(a: String, b: String) = (a to b) in blockedPairs || (b to a) in blockedPairs
        for ((_, items) in grouped.entries.sortedBy { it.key }) {
            val platforms = items.filter { it.group == "platform" }.groupBy { it.channelValue }
            val banks = items.filter { it.group == "bank" }.groupBy { it.channelValue }
            data class ChannelPair<A>(val names: List<String>, val platforms: List<RelationFact<A>>, val banks: List<RelationFact<A>>)
            val channelPairs = mutableListOf<ChannelPair<A>>()
            for ((pc, pf) in platforms) for ((bc, bf) in banks)
                channelPairs += ChannelPair(listOf(pc, bc).sortedWith(cashRelationStringOrder), pf.map { it.fact }, bf.map { it.fact })
            for (pairValue in channelPairs.sortedBy { it.names.joinToString() }) {
                val groupFacts = (pairValue.platforms + pairValue.banks).filter { it.id !in claimed }
                if (groupFacts.isEmpty()) continue
                if (seedIds != null && groupFacts.none { it.id in seedIds }) continue
                val anchor = groupFacts.first()
                val orderedPlatforms = pairValue.platforms.filter { it.id !in claimed }
                    .sortedWith(core.relationOrderKey(anchor))
                val orderedBanks = pairValue.banks.filter { it.id !in claimed }
                    .sortedWith(core.relationOrderKey(anchor))
                if (orderedPlatforms.isEmpty() || orderedBanks.isEmpty()) continue
                if (orderedPlatforms.zip(orderedBanks).any { (left, right) -> blocked(left.id, right.id) }) continue
                handled += groupFacts.map { it.id }
                val candidateIds = (orderedPlatforms + orderedBanks).map { it.id }
                val isComplete = groupFacts.all { complete(it) }
                val forcePending = if (isComplete && orderedPlatforms.size == orderedBanks.size) {
                    groupFacts.any { channel(it) in icbcMirrorSources } && (orderedPlatforms.size > 1 || orderedBanks.size > 1)
                } else true
                for ((platform, bank) in orderedPlatforms.zip(orderedBanks)) {
                    if (blocked(platform.id, bank.id)) continue
                    val proposal = if (forcePending) pendingGroup(platform, bank, candidateIds, aliasesByTail, accountIdentifiers)
                    else evaluate(platform, listOf(bank), aliasesByTail, accountIdentifiers)
                    if (proposal != null) {
                        proposals += proposal
                        claimed += proposal.primaryFactId
                        proposal.secondaryFactId?.let { claimed += it }
                    }
                }
            }
        }
        return proposals to handled
    }

    private fun uniqueMirrors(facts: List<RelationFact<A>>, seedIds: Set<String>?, index: CashFactCandidateIndex<A>?,
        occupied: Set<String>, blockedPairs: Set<Pair<String, String>>, aliasesByTail: Map<String, List<String>>,
        accountIdentifiers: Map<String, List<String>>): List<RelationProposal> {
        val active = facts.filter { !it.deleted && it.factType == "cash" && it.id !in occupied }.associateBy { it.id }
        val requested = if (seedIds == null) active.keys.toSet() else seedIds.intersect(active.keys)
        val pools = linkedMapOf<String, List<RelationFact<A>>>()
        val preferred = linkedMapOf<String, RelationProposal?>()
        fun pool(identifier: String): List<RelationFact<A>> = pools.getOrPut(identifier) {
            val source = index?.mirrorCandidates(active.getValue(identifier)) ?: active.values.toList()
            source.filter { it.id in active && it.id != identifier && (identifier to it.id) !in blockedPairs && (it.id to identifier) !in blockedPairs }
        }
        fun best(identifier: String): RelationProposal? = preferred.getOrPut(identifier) {
            evaluate(active.getValue(identifier), pool(identifier), aliasesByTail, accountIdentifiers, requireUniqueMirrors = true)
        }
        fun pair(proposal: RelationProposal) = setOf(proposal.primaryFactId, proposal.secondaryFactId!!)
        val strong = linkedMapOf<Set<String>, RelationProposal>()
        val weak = linkedMapOf<String, Pair<RelationProposal, RelationProposal>>()
        for (identifier in requested.sortedWith(Comparator { left, right ->
            core.relationOrderKey(active.getValue(left)).compare(active.getValue(left), active.getValue(right)) })) {
            val forward = best(identifier) ?: continue
            val other = pair(forward).first { it != identifier }
            val reverse = best(other)
            if (forward.status == "accepted" && reverse != null && reverse.status == "accepted" && pair(forward) == pair(reverse)) {
                if(pair(forward) !in strong) strong[pair(forward)]=forward
                continue
            }
            var anchor = identifier; var proof = forward
            if (reverse != null && reverse.evidence.candidateCount > forward.evidence.candidateCount) { anchor = other; proof = reverse }
            if(anchor !in weak) weak[anchor]=forward to proof
        }
        val result = strong.values.toMutableList()
        val used = result.flatMap { listOfNotNull(it.primaryFactId, it.secondaryFactId) }.toMutableSet()
        val orderedWeak = weak.entries.sortedWith(Comparator { left, right ->
            val count = right.value.second.evidence.candidateCount.compareTo(left.value.second.evidence.candidateCount)
            if (count != 0) return@Comparator count
            core.relationOrderKey(active.getValue(left.key))
                .compare(active.getValue(left.key), active.getValue(right.key))
        })
        for ((anchor, pairValue) in orderedWeak) {
            if (anchor in used) continue
            val (default, proof) = pairValue
            val offered = proof.evidence.candidateFactIds.toSet() + pair(default) - anchor
            val available = pool(anchor).filter { it.id in offered && it.id !in used && (anchor in requested || it.id in requested) }
            val chosen = evaluate(active.getValue(anchor), available, aliasesByTail, accountIdentifiers, requireUniqueMirrors = true) ?: continue
            result += chosen.copy(anchorFactId = anchor, status = "pending_review", confidence = RELATION_CONFIDENCE_WEAK,
                ruleId = RELATION_RULE_PAYMENT_MIRROR_WEAK_V1,
                evidence = chosen.evidence.copy(ruleId = RELATION_RULE_PAYMENT_MIRROR_WEAK_V1,
                    candidateCount = proof.evidence.candidateCount, candidateFactIds = proof.evidence.candidateFactIds,
                    signals = (chosen.evidence.signals + "mutual_uniqueness_required").distinct()))
            used += pair(chosen)
        }
        return result
    }

    fun matchGreedy(facts: List<RelationFact<A>>, aliasesByTail: Map<String, List<String>> = emptyMap(),
        accountIdentifiers: Map<String, List<String>> = emptyMap(), seedIds: Collection<String>? = null,
        index: CashFactCandidateIndex<A>? = null, occupiedFactIds: Set<String> = emptySet(),
        blockedPairs: Set<Pair<String, String>> = emptySet(), requireUniqueMirrors: Boolean = false): List<RelationProposal> {
        if (requireUniqueMirrors) return uniqueMirrors(facts, seedIds?.toSet(), index, occupiedFactIds, blockedPairs, aliasesByTail, accountIdentifiers)
        val active = facts.filter { !it.deleted && it.factType == "cash" && it.id !in occupiedFactIds }
        val byId = active.associateBy { it.id }
        val requested = seedIds?.map { it.toString() }?.filter { it in byId }?.toSet()
        val seeds = if (seedIds == null) active.filter { core.sourceGroup(it) == "platform" }
            else seedIds.mapNotNull { byId[it] }.filter { core.sourceGroup(it) in setOf("platform", "bank") }
        val sortedSeeds = seeds.sortedWith(Comparator { left, right ->
            val group = (if (core.sourceGroup(left) == "platform") 0 else 1)
                .compareTo(if (core.sourceGroup(right) == "platform") 0 else 1)
            if (group != 0) return@Comparator group
            core.relationOrderKey(left).compare(left, right)
        })
        val deterministic = deterministicGroups(active, requested, aliasesByTail, accountIdentifiers, blockedPairs)
        val proposals = deterministic.first.toMutableList()
        val groupedIds = deterministic.second
        val used = proposals.flatMap { listOfNotNull(it.primaryFactId, it.secondaryFactId) }.toMutableSet()
        if (active.any { channel(it) in icbcMirrorSources }) {
            val priority = mapOf(RELATION_RULE_PAYMENT_MIRROR_REFUND_DUAL_SOURCE_V1 to 5,
                RELATION_RULE_PAYMENT_MIRROR_SAME_ACCOUNT_BIZ_DAY_V1 to 5,
                RELATION_RULE_PAYMENT_MIRROR_BANK_DATE_ONLY_V1 to 5,
                RELATION_RULE_PAYMENT_MIRROR_STRONG_V1 to 4,
                RELATION_RULE_PAYMENT_MIRROR_SHORT_WINDOW_TEXT_V1 to 3,
                RELATION_RULE_PAYMENT_MIRROR_WEAK_V1 to 1)
            val pairCandidates = linkedMapOf<Set<String>, RelationProposal>()
            for (seed in sortedSeeds) {
                if (seed.id in used || seed.id in groupedIds) continue
                val poolValue = (index?.mirrorCandidates(seed) ?: active).filter { candidate ->
                    candidate.id in byId && candidate.id !in used && candidate.id != seed.id &&
                        (channel(seed) in icbcMirrorSources || channel(candidate) in icbcMirrorSources) &&
                        (seed.id to candidate.id) !in blockedPairs && (candidate.id to seed.id) !in blockedPairs
                }
                if (poolValue.isEmpty()) continue
                val proposal = evaluate(seed, poolValue, aliasesByTail, accountIdentifiers) ?: continue
                val key = setOf(proposal.primaryFactId, proposal.secondaryFactId!!)
                val current = pairCandidates[key]
                fun better(left: RelationProposal, right: RelationProposal): Boolean {
                    val leftPriority = priority[left.ruleId] ?: 0; val rightPriority = priority[right.ruleId] ?: 0
                    if (leftPriority != rightPriority) return leftPriority > rightPriority
                    if (left.evidence.timeDeltaSeconds != right.evidence.timeDeltaSeconds)
                        return left.evidence.timeDeltaSeconds!! < right.evidence.timeDeltaSeconds!!
                    return listOfNotNull(left.primaryFactId, left.secondaryFactId).sorted().joinToString(",") >
                        listOfNotNull(right.primaryFactId, right.secondaryFactId).sorted().joinToString(",")
                }
                if (current == null || better(proposal, current)) pairCandidates[key] = proposal
            }
            for (proposal in pairCandidates.values.sortedWith(Comparator { left, right ->
                val priorityOrder = (priority[right.ruleId] ?: 0).compareTo(priority[left.ruleId] ?: 0)
                if (priorityOrder != 0) return@Comparator priorityOrder
                val time = left.evidence.timeDeltaSeconds!!.compareTo(right.evidence.timeDeltaSeconds!!)
                if (time != 0) return@Comparator time
                core.relationOrderKey(byId.getValue(left.primaryFactId))
                    .compare(byId.getValue(left.primaryFactId), byId.getValue(right.primaryFactId))
            })) {
                if (proposal.primaryFactId in used || proposal.secondaryFactId in used) continue
                used += proposal.primaryFactId
                proposal.secondaryFactId?.let { used += it }
                proposals += proposal
            }
        }
        for (seed in sortedSeeds) {
            if (seed.id in used || seed.id in groupedIds) continue
            val others = (index?.mirrorCandidates(seed) ?: active).filter {
                it.id != seed.id && it.id !in used && it.id !in occupiedFactIds && (seed.id to it.id) !in blockedPairs && (it.id to seed.id) !in blockedPairs
            }
            val proposal = evaluate(seed, others, aliasesByTail, accountIdentifiers) ?: continue
            used += proposal.primaryFactId
            proposal.secondaryFactId?.let { used += it }
            proposals += proposal
        }
        return proposals
    }
}

private fun zeroDelta(value:String)=requireNotNull(LedgerDecimal.parseRepresentationOrNull(value)).coefficient=="0"

val cashMirrorMatch=CashMirrorMatchRules(cashRelationCore,cashRelationGeometry,::cashCanonicalPlainText,
    {a,b ->a==b},{a,b ->absoluteCashDifferenceText(a,b).removePrefix("-")},
    {parseRelationTimestampInstant(it).epochSeconds}, {a,b ->
        val left:RelationFact<Any> = a; val right:RelationFact<Any> = b
        cashRelationGeometry.mainStyleCrossVerify(left.copy(amount=cashLegacyDecimalText(a.amount)),right.copy(amount=cashLegacyDecimalText(b.amount)))
    },::SourceJsonInteger)

internal fun cashCanonicalPlainText(value:CashAmount):String {
    val decimal=requireNotNull(LedgerDecimal.parseOrNull(value.numericText()))
    return formatSigned(decimal.digits,decimal.scale,decimal.sign)
}
internal fun cashLegacyDecimalText(value:CashAmount):String {
    val representation=value.representation
    val digits=representation.coefficient
    val adjusted=representation.exponent+digits.length-1
    val sign=if(representation.negative && digits!="0") "-" else ""
    if(representation.exponent<=0 && adjusted>=-6) {
        val scale=(-representation.exponent).toInt()
        return formatSigned(digits,scale,if(sign.isEmpty()) 1 else -1)
    }
    return sign+digits.first()+(if(digits.length>1) "."+digits.drop(1) else "")+"E"+(if(adjusted>=0) "+" else "")+adjusted
}
