package com.finance.tracker.business.domain

/** Production B–D discovery over local exact values; Phase A and graph validation belong to the full plan. */
internal object LocalCashRelationPipeline {
    private val core = LocalCashRelationArithmetic.core
    val geometry = CashRelationGeometryRules(core,
        { value -> formatCashCandidateDay(parseRelationTimestampInstant(value).epochSeconds.floorDiv(86400L)) },
        ::relationTimeDeltaSeconds)
    val mirror = CashMirrorMatchRules(core, geometry, ::money,
        { a, b -> a.compareTo(b) == 0 }, { a, b -> a.subtract(b).abs().toPlainString() },
        { parseRelationTimestampInstant(it).epochSeconds }, geometry::mainStyleCrossVerify, ::SourceJsonInteger)
    val transfer = CashTransferMatchRules(core,
        { a, b -> a.abs().compareTo(b.abs()) == 0 }, { a, b -> a.abs().subtract(b.abs()).toPlainString() },
        ::relationTimeDeltaSeconds, { index(it, includeSourceGroup = false) })
    private val tolerance = LocalCashRelationArithmetic.amount("0.0001")
    val diamond = CashRefundDiamondRules(core,
        { expense, refund -> expense.abs().add(tolerance) >= refund.abs() }, { it.abs().toPlainString() },
        ::relationTimeDeltaSeconds)
    val refund = CashRefundMatchRules(core, ::normalizeLocalCashMerchant,
        ::parseRelationTimestampInstant, ::relationElapsedNanos,
        { it.abs() }, { a, b -> a.subtract(b) }, { it.toPlainString() }, LocalCashRelationArithmetic::amount)
    val rules = CashRelationPipelineRules(core, geometry, mirror, transfer, diamond, refund, { index(it) },
        { it.abs() }, { a, b -> a.add(b) }, { a, b -> a.subtract(b) },
        LocalCashRelationArithmetic::amount, LedgerArithmeticValue.ZERO)

    fun index(facts: List<RelationFact<LedgerArithmeticValue>>, includeSourceGroup: Boolean = true) =
        CashFactCandidateIndex(facts, core, { money(it.abs()) }, { value ->
            val instant = parseRelationTimestampInstant(value)
            CashCandidateTime(instant.epochSeconds + instant.nanosecondsOfSecond / 1000000000.0) {
                formatCashCandidateDay(instant.epochSeconds.floorDiv(86400L))
            }
        }, ::shiftCashCandidateDay, if (includeSourceGroup) core::sourceGroup else { _ -> "other" })

    private fun money(value: LedgerArithmeticValue): String {
        if (value.signum == 0) return "0"
        val text = value.toPlainString()
        return if ('.' in text) text.trimEnd('0').trimEnd('.') else text
    }
}
