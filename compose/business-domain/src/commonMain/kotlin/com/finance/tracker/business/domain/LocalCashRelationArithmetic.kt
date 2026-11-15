package com.finance.tracker.business.domain

import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

/** Discovery-only accumulation over normalized amounts; no stored-column restriction or intermediate rounding. */
internal object LocalCashRelationArithmetic {
    fun amount(value: Any?): LedgerArithmeticValue = when (value) {
        is LedgerArithmeticValue -> value
        is String -> parseSourceAccountAmount(value)
        is CashAmount -> parseSourceAccountAmount(value.toString())
        is CashDecimalLiteral -> parseSourceAccountAmount(value.text)
        else -> throw InvalidCashValue()
    }

    val core = CashRelationCoreRules<LedgerArithmeticValue>({ it.signum }, ::stableCashRelationTimestamp)
    val serialization = CashRelationSerializationRules(core, ::amount, { it.abs() }, { a, b -> a.subtract(b) },
        ::canonicalTime, ::cacheScalar)

    private fun canonicalTime(value: Any?): String {
        val text = value?.toString().orEmpty()
        if (text.isEmpty()) return text
        return try { formatPythonCashTimestamp(parseRelationTimestampInstant(value)) }
        catch (_: IllegalArgumentException) { text }
        catch (_: ArithmeticException) { text }
    }

    private fun cacheScalar(value: Any?): Any? = when (value) {
        null, is String, is Boolean, is Number, is SourceJsonInteger, is CashImportPreviewInteger, is JsonPrimitive -> value
        is LedgerArithmeticValue -> value.toPlainString()
        is CashAmount, is CashDecimalLiteral -> amount(value).toPlainString()
        is Instant -> canonicalTime(value)
        else -> value.toString()
    }
}

/** The original Duration.toNanos boundary throws on overflow instead of saturating. */
internal fun relationElapsedNanos(from: Instant, to: Instant): Long {
    // Instant's entire epoch-second domain and the difference both fit Long.
    var seconds = to.epochSeconds - from.epochSeconds
    var nanos = (to.nanosecondsOfSecond - from.nanosecondsOfSecond).toLong()
    if (seconds > 0 && nanos < 0) { seconds--; nanos += 1000000000L }
    else if (seconds < 0 && nanos > 0) { seconds++; nanos -= 1000000000L }
    if (seconds > Long.MAX_VALUE / 1000000000L || seconds < Long.MIN_VALUE / 1000000000L)
        throw ArithmeticException("long overflow")
    val whole = seconds * 1000000000L
    if (nanos > 0 && whole > Long.MAX_VALUE - nanos || nanos < 0 && whole < Long.MIN_VALUE - nanos)
        throw ArithmeticException("long overflow")
    return whole + nanos
}
