package com.finance.tracker.backend

import java.math.BigDecimal
import java.time.Duration

internal const val REFUND_CANDIDATE_DAYS = com.finance.tracker.business.domain.REFUND_CANDIDATE_DAYS
internal const val REFUND_AUTO_ACCEPT_DAYS = com.finance.tracker.business.domain.REFUND_AUTO_ACCEPT_DAYS
internal const val REFUND_ORDER_LOCK_AUTO_ACCEPT_DAYS = com.finance.tracker.business.domain.REFUND_ORDER_LOCK_AUTO_ACCEPT_DAYS
internal const val RELATION_RULE_REFUND_OFFSET_V1 = com.finance.tracker.business.domain.RELATION_RULE_REFUND_OFFSET_V1

internal object CashRefundMatch {
    private val rules = com.finance.tracker.business.domain.CashRefundMatchRules(
        CashRelationCore.sharedRules,
        { value -> Regex("(?U)[\\W_]+").replace(value.orEmpty(), "").lowercase() },
        CashRelationGeometry::parseDt, { a, b -> Duration.between(a, b).toNanos() },
        { value: BigDecimal -> value.abs() }, { a, b -> a.subtract(b) },
        { it.toPlainString() }, CashRelationGeometry::asDecimal)
    val sharedRules get()=rules
    fun evaluate(seed: RelationFact, candidates: List<RelationFact>,
        remainingByExpense: Map<String, BigDecimal> = emptyMap(),
        candidateEventIds: Map<String, String> = emptyMap()) =
        rules.evaluate(seed, candidates, remainingByExpense, candidateEventIds)
}
