package com.finance.tracker.business.domain

import kotlin.time.Instant

/** Python Decimal text preserves the original scale and signed zero; source digests use a different representation. */
private fun importDecimalText(amount: CashAmount): String {
    val value = amount.representation
    val coefficient = value.coefficient
    val exponent = value.exponent
    // CashAmount bounds nonzero magnitudes; a zero coefficient has length one even for a huge exponent.
    val adjusted = exponent + coefficient.length - 1L
    val sign = if (value.negative) "-" else ""
    if (exponent > 0 || adjusted < -6) {
        val mantissa = coefficient.take(1) + if (coefficient.length > 1) "." + coefficient.drop(1) else ""
        return sign + mantissa + "E" + (if (adjusted >= 0) "+" else "") + adjusted
    }
    val point = (coefficient.length.toLong() + exponent).toInt()
    return sign + when {
        point <= 0 -> "0." + "0".repeat(-point) + coefficient
        point < coefficient.length -> coefficient.take(point) + "." + coefficient.drop(point)
        else -> coefficient
    }
}

/** Convert only computed import values; the injected source digest retains the existing open-value encoding. */
fun cashImportComputedDigest(values: List<Any?>, timestampText: (Any?) -> String?,
    sourceDigest: (List<Any?>) -> String): String {
    fun value(item: Any?): Any? = when (item) {
        is CashAmount -> importDecimalText(item)
        is CashDecimalLiteral -> importDecimalText(CashAmount.parse(item.text))
        is Map<*, *> -> item.entries.associate { it.key.toString() to value(it.value) }
        is List<*> -> item.map(::value)
        else -> timestampText(item) ?: item
    }
    return sourceDigest(values.map(::value))
}

/** Local computed values use the shared timestamp and decimal representations before stable source JSON. */
fun localCashImportDigest(values: List<Any?>): String = cashImportComputedDigest(values,
    { if (it is Instant) formatPythonCashTimestamp(it).replace('T', ' ') else null }, ::localCashSourceDigest)

/** Preview-only identifiers retain 2^64 plus the unsigned first 128 digest bits; no platform BigInteger needed. */
fun cashImportVirtualId(kind: String, values: List<Any?>, sourceDigest: (List<Any?>) -> String): String {
    val prefix = sourceDigest(listOf("cash-import-virtual-v1", kind, values)).take(32)
    check(prefix.length == 32) { "invalid_import_virtual_digest" }
    // Unsigned 128-bit values fit 39 decimal digits. Each carry is at most 159, on every target.
    val decimal = IntArray(39)
    for (char in prefix) {
        var carry = char.digitToInt(16)
        for (index in decimal.indices.reversed()) {
            val value = decimal[index] * 16 + carry
            decimal[index] = value % 10
            carry = value / 10
        }
    }
    val number = buildString(39) { decimal.forEach { append(('0'.code + it).toChar()) } }.trimStart('0').ifEmpty { "0" }
    return addUnsigned(number, "18446744073709551616")
}

fun localCashImportVirtualId(kind: String, values: List<Any?>): String = cashImportVirtualId(kind, values, ::localCashSourceDigest)
