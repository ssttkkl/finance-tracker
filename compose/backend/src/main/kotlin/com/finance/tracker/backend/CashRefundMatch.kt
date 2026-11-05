package com.finance.tracker.backend

import java.math.BigDecimal
import java.time.Duration

internal const val REFUND_CANDIDATE_DAYS = 15
internal const val REFUND_AUTO_ACCEPT_DAYS = 15
internal const val REFUND_ORDER_LOCK_AUTO_ACCEPT_DAYS = 30
internal const val RELATION_RULE_REFUND_OFFSET_V1 = "refund_offset.merchant_or_order.v1"

/** 退款种子与支出候选的弱/强证据排序；不写关系。 */
internal object CashRefundMatch {
    private val genericCounterparties = setOf("消费", "支付", "交易", "退款", "退货", "收入", "支出")
    private data class Match(val fact: RelationFact, val evidence: RelationEvidence, val status: String,
        val confidence: String, val titleExact: Boolean, val priority: Int)

    private fun merchantKey(value: String?): String =
        Regex("(?U)[\\W_]+").replace(value.orEmpty(), "").lowercase()
    private fun isGeneric(value: String?) = merchantKey(value).let { it.isEmpty() || it in genericCounterparties }
    private fun merchantExact(left: String?, right: String?): Boolean {
        val a = merchantKey(left); val b = merchantKey(right)
        return a.isNotEmpty() && b.isNotEmpty() && !isGeneric(left) && !isGeneric(right) && a == b
    }
    private fun priority(orderLock: Boolean, titleExact: Boolean, accountExact: Boolean, merchantExact: Boolean,
        amountExact: Boolean, merchantMatch: Boolean): Int = when {
        orderLock -> 4
        titleExact -> 3
        accountExact -> 3
        merchantExact -> 2
        amountExact -> 1
        merchantMatch -> 0
        else -> -1
    }
    private fun orderKey(fact: RelationFact): List<String> = CashRelationCore.stableFactOrderKey(fact)
    private val orderComparator = Comparator<RelationFact> { left, right ->
        val a = orderKey(left); val b = orderKey(right)
        for (index in a.indices) {
            val result = pythonStringOrder(a[index], b[index])
            if (result != 0) return@Comparator result
        }
        0
    }

    fun evaluate(seed: RelationFact, candidates: List<RelationFact>,
        remainingByExpense: Map<String, BigDecimal> = emptyMap(),
        candidateEventIds: Map<String, String> = emptyMap()): RelationProposal? {
        if (seed.deleted || seed.factType != "cash") return null
        val refundSeed = seed.signedAmount.signum() > 0 && CashRefundSignals.hasRefundSignalForFact(seed)
        if (!refundSeed) return null
        val matches = mutableListOf<Match>()
        for (candidate in candidates) {
            if (candidate.id == seed.id || candidate.deleted || candidate.factType != "cash") continue
            if (!CashRelationCore.isRefundExpenseCandidate(candidate)) continue
            if (candidate.accountId != seed.accountId) continue
            if (!CashRelationCore.compatiblePlatformScope(seed, candidate)) continue
            if (candidate.currency.uppercase() != seed.currency.uppercase()) continue
            if (candidate.signedAmount.signum() >= 0) continue
            val refundDt = runCatching { CashRelationGeometry.parseDt(seed.occurredAt) }.getOrNull() ?: continue
            val expenseDt = runCatching { CashRelationGeometry.parseDt(candidate.occurredAt) }.getOrNull() ?: continue
            if (refundDt < expenseDt) continue
            val days = Duration.between(expenseDt, refundDt).toNanos() / 1_000_000_000.0 / 86400.0
            val refundAbs = seed.signedAmount.abs()
            val expenseAbs = candidate.signedAmount.abs()
            val remaining = remainingByExpense[candidate.id] ?: expenseAbs
            if (refundAbs > remaining) continue
            val orderLock = seed.recordId.isNotEmpty() && candidate.recordId.isNotEmpty() && seed.recordId == candidate.recordId ||
                CashRelationGeometry.mainStyleCrossVerify(
                    mapOf("counterparty" to seed.counterparty, "note" to seed.note),
                    mapOf("counterparty" to candidate.counterparty, "note" to candidate.note)) &&
                listOf("订单", "order", "交易号", "txn", "商户单号").any {
                    it in cashRelationTextBlob(seed.note, candidate.note)
                }
            val window = if (orderLock) REFUND_ORDER_LOCK_AUTO_ACCEPT_DAYS else REFUND_CANDIDATE_DAYS
            if (days > window) continue
            val merchantExact = merchantExact(seed.counterparty, candidate.counterparty)
            val accountExact = seed.counterpartyAccount.replace(" ", "").isNotEmpty() &&
                seed.counterpartyAccount.replace(" ", "") == candidate.counterpartyAccount.replace(" ", "")
            val merchantMatch = !isGeneric(seed.counterparty) && !isGeneric(candidate.counterparty) &&
                (merchantExact || CashRelationGeometry.mainStyleCrossVerify(
                    mapOf("counterparty" to seed.counterparty, "note" to ""),
                    mapOf("counterparty" to candidate.counterparty, "note" to "")))
            val titleExact = CashRefundSignals.refundTitleExactMatch(seed, candidate)
            val sameAccount = seed.accountId == candidate.accountId
            val exact = refundAbs.compareTo(expenseAbs) == 0 || refundAbs.compareTo(remaining) == 0
            val strongLink = merchantMatch || orderLock || titleExact || accountExact
            val weakLink = sameAccount && CashRefundSignals.hasRefundSignalForFact(seed) && exact && !strongLink
            if (!strongLink && !(refundSeed && weakLink)) continue
            val rank = priority(orderLock, titleExact, accountExact, merchantExact, exact, merchantMatch)
            val withinAuto = days <= REFUND_AUTO_ACCEPT_DAYS || (orderLock && days <= REFUND_ORDER_LOCK_AUTO_ACCEPT_DAYS)
            var status = if (strongLink && withinAuto) "accepted" else "pending_review"
            var confidence = if (strongLink && withinAuto) RELATION_CONFIDENCE_STRONG else RELATION_CONFIDENCE_WEAK
            if (weakLink && !strongLink) { status = "pending_review"; confidence = RELATION_CONFIDENCE_WEAK }
            val signals = listOfNotNull("refund", if (merchantMatch) "merchant" else null,
                if (orderLock) "order_lock" else null, if (titleExact) "title_exact" else null,
                if (accountExact) "counterparty_account" else null, if (sameAccount) "same_account" else null,
                if (weakLink && !strongLink) "weak_link" else null)
            val evidence = RelationEvidence(
                amountDelta = (remaining - refundAbs).toPlainString(),
                timeDeltaSeconds = Duration.between(expenseDt, refundDt).seconds.toInt(),
                sameCurrency = true, counterpartySimilarity = seed.counterparty.ifEmpty { candidate.counterparty },
                sourcePair = (seed.billSource.ifEmpty { seed.source }) to (candidate.billSource.ifEmpty { candidate.source }),
                ruleId = RELATION_RULE_REFUND_OFFSET_V1, signals = signals,
                extras = linkedMapOf("refund_amount" to refundAbs.toPlainString(),
                    "expense_amount" to expenseAbs.toPlainString(), "remaining_before" to remaining.toPlainString(),
                    "days" to days.toInt().toString(), "within_auto" to withinAuto.toString().lowercase(),
                    "priority" to rank.toString()))
            matches += Match(candidate, evidence, status, confidence, titleExact, rank)
        }
        var selectedMatches = matches
        if (candidateEventIds.isNotEmpty() && matches.isNotEmpty()) {
            val byEvent = linkedMapOf<String, MutableList<Match>>()
            for (match in matches) byEvent.getOrPut(candidateEventIds[match.fact.id] ?: match.fact.id) { mutableListOf() } += match
            val collapsed = mutableListOf<Match>()
            for (eventId in byEvent.keys.sortedWith(cashRelationStringOrder)) {
                val eventMatches = byEvent.getValue(eventId)
                val bestPriority = eventMatches.maxOf { it.priority }
                val best = eventMatches.filter { it.priority == bestPriority }.sortedWith(Comparator { left, right ->
                    val status = if (left.status == "accepted") 0 else 1
                    val other = if (right.status == "accepted") 0 else 1
                    if (status != other) return@Comparator status - other
                    val time = left.evidence.timeDeltaSeconds!!.compareTo(right.evidence.timeDeltaSeconds!!)
                    if (time != 0) return@Comparator time
                    orderComparator.compare(left.fact, right.fact)
                })
                var selected = best.first()
                val representative = eventMatches.firstOrNull { it.fact.id == eventId }?.fact ?: selected.fact
                if (representative.id != selected.fact.id) selected = selected.copy(fact = representative)
                collapsed += selected
            }
            selectedMatches = collapsed
        }
        if (selectedMatches.isEmpty()) {
            if (!refundSeed || candidates.isNotEmpty()) return null
            val evidence = RelationEvidence(amountDelta = "0", timeDeltaSeconds = 0, sameCurrency = true,
                ruleId = RELATION_RULE_REFUND_OFFSET_V1, candidateCount = 0,
                signals = listOf("refund", "open_leg_zero_candidate"), openLeg = true, anchorRole = "refund",
                candidateFactIds = emptyList(), extras = linkedMapOf("refund_amount" to seed.signedAmount.abs().toPlainString()))
            return RelationProposal(kind = "refund_offset", primaryFactId = seed.id, secondaryFactId = null,
                secondaryFactType = null, status = "pending_review", ruleId = RELATION_RULE_REFUND_OFFSET_V1,
                confidence = RELATION_CONFIDENCE_WEAK, evidence = evidence, anchorFactId = seed.id, openLeg = true)
        }
        if (refundSeed && selectedMatches.size == 1 && "weak_link" in selectedMatches[0].evidence.signals) {
            val match = selectedMatches[0]
            val withinAuto = (match.evidence.extras["within_auto"] == "true")
            val evidence = match.evidence.copy(signals = (match.evidence.signals + "weak_unique_strong").distinct())
            return RelationProposal(kind = "refund_offset", primaryFactId = match.fact.id, secondaryFactId = seed.id,
                status = if (withinAuto) "accepted" else "pending_review", ruleId = RELATION_RULE_REFUND_OFFSET_V1,
                confidence = RELATION_CONFIDENCE_STRONG, evidence = evidence, anchorFactId = seed.id, openLeg = false)
        }
        val matchesById = selectedMatches.associate { it.fact.id to it.fact }
        val strong = selectedMatches.filter { it.status == "accepted" }
        if (refundSeed && strong.isNotEmpty()) {
            val highest = strong.maxOf { it.priority }
            val top = strong.filter { it.priority == highest }
            val titleOnly = top.filter { it.titleExact && "counterparty_account" !in it.evidence.signals }
            val accountOnly = top.filter { "counterparty_account" in it.evidence.signals && !it.titleExact }
            if (titleOnly.isNotEmpty() && accountOnly.isNotEmpty()) return null
            if (top.size == 1) {
                val match = top.single()
                val signals = match.evidence.signals.toMutableList()
                if (match.titleExact) signals += "title_exact_unique"
                val evidence = match.evidence.copy(candidateCount = selectedMatches.size,
                    candidateFactIds = candidates(selectedMatches, matchesById), signals = signals.distinct())
                return RelationProposal(kind = "refund_offset", primaryFactId = match.fact.id, secondaryFactId = seed.id,
                    status = "accepted", ruleId = RELATION_RULE_REFUND_OFFSET_V1, confidence = RELATION_CONFIDENCE_STRONG,
                    evidence = evidence, anchorFactId = seed.id, openLeg = false)
            }
            if (top.isNotEmpty() && top.all { "counterparty_account" in it.evidence.signals }) return null
            val nearestDelta = top.minOf { it.evidence.timeDeltaSeconds!! }
            val nearest = top.filter { it.evidence.timeDeltaSeconds == nearestDelta }
            if (nearest.size == 1) {
                val match = nearest.single()
                val refundAmount = CashRelationGeometry.asDecimal(match.evidence.extras["refund_amount"])
                val remaining = CashRelationGeometry.asDecimal(match.evidence.extras["remaining_before"])
                val signal = if (refundAmount.compareTo(remaining) == 0) "full_nearest_unique" else "partial_nearest_unique"
                val evidence = match.evidence.copy(candidateCount = selectedMatches.size,
                    candidateFactIds = candidates(selectedMatches, matchesById),
                    signals = (match.evidence.signals + signal).distinct())
                return RelationProposal(kind = "refund_offset", primaryFactId = match.fact.id, secondaryFactId = seed.id,
                    status = "accepted", ruleId = RELATION_RULE_REFUND_OFFSET_V1, confidence = RELATION_CONFIDENCE_STRONG,
                    evidence = evidence, anchorFactId = seed.id, openLeg = false)
            }
        }
        if (selectedMatches.size == 1) {
            val match = selectedMatches.single()
            return RelationProposal(kind = "refund_offset", primaryFactId = match.fact.id, secondaryFactId = seed.id,
                status = "pending_review", ruleId = RELATION_RULE_REFUND_OFFSET_V1,
                confidence = RELATION_CONFIDENCE_WEAK, evidence = match.evidence.copy(candidateCount = 1),
                anchorFactId = seed.id, openLeg = false)
        }
        val sorted = selectedMatches.sortedWith(Comparator { left, right ->
            val status = (if (left.status == "accepted") 0 else 1) - (if (right.status == "accepted") 0 else 1)
            if (status != 0) return@Comparator status
            val priority = right.priority.compareTo(left.priority)
            if (priority != 0) return@Comparator priority
            val time = left.evidence.timeDeltaSeconds!!.compareTo(right.evidence.timeDeltaSeconds!!)
            if (time != 0) return@Comparator time
            orderComparator.compare(left.fact, right.fact)
        })
        val candidateIds = candidates(selectedMatches, matchesById)
        val base = sorted.first().evidence
        val evidence = RelationEvidence(amountDelta = base.amountDelta, timeDeltaSeconds = base.timeDeltaSeconds,
            sameCurrency = true, counterpartySimilarity = base.counterpartySimilarity, sourcePair = base.sourcePair,
            ruleId = RELATION_RULE_REFUND_OFFSET_V1, candidateCount = selectedMatches.size,
            signals = sorted.flatMap { it.evidence.signals }.filter { it.isNotEmpty() }.distinct(),
            openLeg = true, anchorRole = "refund", candidateFactIds = candidateIds, extras = base.extras)
        return RelationProposal(kind = "refund_offset", primaryFactId = seed.id, secondaryFactId = null,
            secondaryFactType = null, status = "pending_review", ruleId = RELATION_RULE_REFUND_OFFSET_V1,
            confidence = RELATION_CONFIDENCE_WEAK, evidence = evidence, anchorFactId = seed.id, openLeg = true)
    }

    private fun candidates(matches: List<Match>, byId: Map<String, RelationFact>): List<String> =
        CashRelationCore.topKCandidateIds(matches.map { it.fact.id },
            key = Comparator { left, right -> orderComparator.compare(byId.getValue(left), byId.getValue(right)) })
}
