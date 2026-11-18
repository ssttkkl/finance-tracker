package com.finance.tracker.business.domain

/** Phase A configuration over normalized local amounts, exact platform dates and unrounded remaining values. */
internal object LocalCashPlatformRefund {
    private fun decimal(value: Any?): LedgerArithmeticValue? = if (value == null) null
        else try { LocalCashRelationArithmetic.amount(value) }
        catch (_: NumberFormatException) { null }

    val rules = CashPlatformRefundRules(::decimal, { it.abs() }, { it.signum }, { a, b -> a.compareTo(b) },
        ::parseLocalCashPlatformDate, { from, to -> relationElapsedNanos(from, to) / 1000000000.0 })
    val hardKeys = CashRefundHardKeyRules(LocalCashRelationArithmetic.core, rules, { it.abs() }, { it.signum },
        { a, b -> a.compareTo(b) }, { a, b -> a.subtract(b) }, { it.toPlainString() }, LedgerArithmeticValue.ZERO)
}
