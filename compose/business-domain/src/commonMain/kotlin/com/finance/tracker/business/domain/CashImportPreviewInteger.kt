package com.finance.tracker.business.domain

/** Exact computation-only identifiers, including preview hashes beyond stored Long identifiers. */
class CashImportPreviewInteger private constructor(private val number: LedgerArithmeticValue) : Comparable<CashImportPreviewInteger> {
    internal val positive get() = number.signum > 0
    override fun compareTo(other: CashImportPreviewInteger): Int = number.compareTo(other.number)
    override fun equals(other: Any?): Boolean = other is CashImportPreviewInteger && number == other.number
    override fun hashCode(): Int = number.hashCode()
    override fun toString(): String = number.toPlainString()

    companion object {
        fun parse(text: String): CashImportPreviewInteger = CashImportPreviewInteger(parseLocalCashIntegerText(text))
    }
}

internal fun localCashPreviewInteger(value: Any?): CashImportPreviewInteger? = when (value) {
    is CashImportPreviewInteger -> value
    else -> localCashIntegerText(value)?.let(CashImportPreviewInteger::parse)
}
