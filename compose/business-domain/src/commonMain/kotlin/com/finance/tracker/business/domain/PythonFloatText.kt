package com.finance.tracker.business.domain

/** Python's shortest round-trip binary64 text, independent of platform float formatting. */
fun pythonFloatText(value: Double): String {
    if (value.isNaN()) return "nan"
    if (value == Double.POSITIVE_INFINITY) return "inf"
    if (value == Double.NEGATIVE_INFINITY) return "-inf"
    val bits = value.toBits()
    val sign = if (bits < 0) "-" else ""
    if (value == 0.0) return "${sign}0.0"
    return sign + selectBinary64Decimal(value, minimumPrecision = 1).pythonText()
}

/** Java 21's decimal selection, using the same exact binary interval as Python but a distinct display contract. */
internal fun javaBinary64Text(value: Double): String {
    if (value.isNaN()) return "NaN"
    if (value == Double.POSITIVE_INFINITY) return "Infinity"
    if (value == Double.NEGATIVE_INFINITY) return "-Infinity"
    val sign = if (value.toBits() < 0) "-" else ""
    if (value == 0.0) return "${sign}0.0"
    // If one digit round-trips, Java chooses the nearest decimal among both one and two digits.
    return sign + selectBinary64Decimal(value, minimumPrecision = 2).javaText()
}

private fun selectBinary64Decimal(value: Double, minimumPrecision: Int): FloatDecimal {
    val bits = value.toBits()
    val fraction = bits and 0x000fffffffffffffL
    val encodedExponent = ((bits ushr 52) and 0x7ff).toInt()
    val significand = if (encodedExponent == 0) fraction else fraction + (1L shl 52)
    val exponent = if (encodedExponent == 0) -1074 else encodedExponent - 1075
    val exact = FloatDecimal.binary(significand, exponent)
    // At normal powers of two the preceding spacing is half the following spacing.
    val lower = if (fraction == 0L && encodedExponent > 1) {
        FloatDecimal.binary(significand * 4 - 1, exponent - 2)
    } else {
        FloatDecimal.binary(significand * 2 - 1, exponent - 1)
    }
    val upper = FloatDecimal.binary(significand * 2 + 1, exponent - 1)
    val includeMidpoints = significand % 2 == 0L
    for (precision in minimumPrecision..17) {
        if (exact.coefficient.length <= precision) return exact
        val prefix = exact.coefficient.take(precision).toLong()
        val tail = exact.coefficient.substring(precision)
        val roundUp = tail[0] > '5' || tail[0] == '5' &&
            (tail.drop(1).any { it != '0' } || prefix % 2 != 0L)
        val nearest = prefix + if (roundUp) 1 else 0
        val stepExponent = exact.exponent + exact.coefficient.length - precision
        val selected = listOf(nearest, nearest - 1, nearest + 1)
            .filter { it > 0 }
            .map { FloatDecimal.of(it.toString(), stepExponent) }
            .filter { candidate ->
                candidate.coefficient.length <= precision &&
                    (candidate > lower || includeMidpoints && candidate == lower) &&
                    (candidate < upper || includeMidpoints && candidate == upper)
            }
            .minWithOrNull(
                compareBy<FloatDecimal> { it.distance(exact) }
                    .thenBy { it.coefficient.last().digitToInt() % 2 },
            )
        if (selected != null) return selected
    }
    error("binary64 has no 17-digit round-trip representation")
}

/** Exact finite binary64 value used by Python-style numeric equality, not shortest display text. */
internal fun exactBinary64Decimal(value: Double): DecimalRepresentation {
    require(value.isFinite())
    if (value == 0.0) return DecimalRepresentation(false, "0", 0)
    val bits = value.toBits()
    val fraction = bits and 0x000fffffffffffffL
    val encodedExponent = ((bits ushr 52) and 0x7ff).toInt()
    val significand = if (encodedExponent == 0) fraction else fraction + (1L shl 52)
    val exponent = if (encodedExponent == 0) -1074 else encodedExponent - 1075
    val exact = FloatDecimal.binary(significand, exponent)
    return DecimalRepresentation(bits < 0, exact.coefficient, exact.exponent.toLong())
}

private val jsonFloatDecimal = Regex("^([+-]?)([0-9]*)(?:\\.([0-9]*))?(?:[eE]([+-]?[0-9]+))?$")
private const val maximumFiniteBinary64Bits = 0x7fefffffffffffffL

/** Correctly rounded JSON decimal parsing; the platform parser supplies only a starting candidate. */
internal fun parsePythonJsonBinary64(text: String, approximate: (String) -> Double? = String::toDoubleOrNull): Double {
    val numeric = text.trim { it <= ' ' }
    val match = jsonFloatDecimal.matchEntire(numeric) ?: return text.toDouble()
    val whole = match.groupValues[2]
    val fraction = match.groupValues[3]
    if (whole.isEmpty() && fraction.isEmpty()) return text.toDouble()
    val negative = match.groupValues[1] == "-"
    fun signed(value: Double): Double = if (negative) -value else value
    val coefficient = (whole + fraction).trimStart('0')
    if (coefficient.isEmpty()) return signed(0.0)
    val rawExponent = match.groupValues[4].ifEmpty { "0" }
    val explicitExponent = rawExponent.toLongOrNull()
        ?: return signed(if (rawExponent.startsWith('-')) 0.0 else Double.POSITIVE_INFINITY)
    val shift = coefficient.length.toLong() - fraction.length
    val order = when {
        shift > 0 && explicitExponent > Long.MAX_VALUE - shift -> Long.MAX_VALUE
        shift < 0 && explicitExponent < Long.MIN_VALUE - shift -> Long.MIN_VALUE
        else -> explicitExponent + shift
    }
    if (order > 309) return signed(Double.POSITIVE_INFINITY)
    if (order < -324) return signed(0.0)

    // Compare significant digits with implicit trailing zeroes, never expanding an exponent.
    fun compare(bound: FloatDecimal): Int {
        val magnitude = order.compareTo(bound.coefficient.length.toLong() + bound.exponent)
        if (magnitude != 0) return magnitude
        for (index in 0 until maxOf(coefficient.length, bound.coefficient.length)) {
            val result = (coefficient.getOrNull(index) ?: '0').compareTo(bound.coefficient.getOrNull(index) ?: '0')
            if (result != 0) return result
        }
        return 0
    }
    // -1 means below this rounding interval, +1 above it, 0 inside it.
    fun position(bits: Long): Int {
        if (bits == 0L) return if (compare(FloatDecimal.binary(1, -1075)) <= 0) 0 else 1
        val fractionBits = bits and 0x000fffffffffffffL
        val encodedExponent = ((bits ushr 52) and 0x7ff).toInt()
        val significand = if (encodedExponent == 0) fractionBits else fractionBits + (1L shl 52)
        val exponent = if (encodedExponent == 0) -1074 else encodedExponent - 1075
        val lower = if (fractionBits == 0L && encodedExponent > 1) FloatDecimal.binary(significand * 4 - 1, exponent - 2)
            else FloatDecimal.binary(significand * 2 - 1, exponent - 1)
        val upper = FloatDecimal.binary(significand * 2 + 1, exponent - 1)
        val inclusive = significand % 2 == 0L
        val below = compare(lower)
        if (below < 0 || below == 0 && !inclusive) return -1
        val above = compare(upper)
        return if (above > 0 || above == 0 && !inclusive) 1 else 0
    }

    val initial = approximate(numeric)?.takeUnless { it.isNaN() }
    var candidate = initial?.let { (it.toBits() and Long.MAX_VALUE).coerceAtMost(maximumFiniteBinary64Bits) }
        ?: (maximumFiniteBinary64Bits ushr 1)
    var low = 0L
    var high = maximumFiniteBinary64Bits
    var first = true
    while (low <= high) {
        val direction = position(candidate)
        if (direction == 0) return signed(Double.fromBits(candidate))
        if (direction > 0) {
            if (candidate == maximumFiniteBinary64Bits) return signed(Double.POSITIVE_INFINITY)
            low = candidate + 1
        } else {
            high = candidate - 1
        }
        // Check the adjacent value cheaply, then use bounded monotonic binary search if necessary.
        candidate = if (first) candidate + direction else low + ((high - low) ushr 1)
        first = false
    }
    error("JSON decimal has no binary64 rounding interval")
}

// Bounded arithmetic for binary64 conversion only; this is not an accounting decimal type.
private data class FloatDecimal(val coefficient: String, val exponent: Int) : Comparable<FloatDecimal> {
    override fun compareTo(other: FloatDecimal): Int {
        if (coefficient == "0" || other.coefficient == "0") {
            return (coefficient != "0").compareTo(other.coefficient != "0")
        }
        val magnitude = (coefficient.length + exponent).compareTo(other.coefficient.length + other.exponent)
        if (magnitude != 0) return magnitude
        val length = maxOf(coefficient.length, other.coefficient.length)
        return coefficient.padEnd(length, '0').compareTo(other.coefficient.padEnd(length, '0'))
    }

    fun distance(other: FloatDecimal): FloatDecimal {
        val scale = minOf(exponent, other.exponent)
        val first = coefficient + "0".repeat(exponent - scale)
        val second = other.coefficient + "0".repeat(other.exponent - scale)
        val (greater, smaller) = if (this >= other) first to second else second to first
        val padded = smaller.padStart(greater.length, '0')
        val result = CharArray(greater.length)
        var borrow = 0
        for (index in greater.lastIndex downTo 0) {
            val digit = greater[index] - padded[index] - borrow
            result[index] = ('0'.code + if (digit < 0) digit + 10 else digit).toChar()
            borrow = if (digit < 0) 1 else 0
        }
        check(borrow == 0)
        return of(result.concatToString().trimStart('0').ifEmpty { "0" }, scale)
    }

    fun javaText(): String {
        val scientificExponent = coefficient.length + exponent - 1
        if (scientificExponent !in -3..6) {
            return coefficient.take(1) + "." + coefficient.drop(1).ifEmpty { "0" } + "E" + scientificExponent
        }
        val point = coefficient.length + exponent
        return when {
            point <= 0 -> "0." + "0".repeat(-point) + coefficient
            point >= coefficient.length -> coefficient + "0".repeat(point - coefficient.length) + ".0"
            else -> coefficient.take(point) + "." + coefficient.drop(point)
        }
    }

    fun pythonText(): String {
        val scientificExponent = coefficient.length + exponent - 1
        if (scientificExponent !in -4..15) {
            val mantissa = coefficient.take(1) + if (coefficient.length > 1) "." + coefficient.drop(1) else ""
            val power = if (scientificExponent >= 0) {
                "+" + scientificExponent.toString().padStart(2, '0')
            } else {
                "-" + (-scientificExponent).toString().padStart(2, '0')
            }
            return mantissa + "e" + power
        }
        val point = coefficient.length + exponent
        return when {
            point <= 0 -> "0." + "0".repeat(-point) + coefficient
            point >= coefficient.length -> coefficient + "0".repeat(point - coefficient.length) + ".0"
            else -> coefficient.take(point) + "." + coefficient.drop(point)
        }
    }

    companion object {
        fun of(coefficient: String, exponent: Int): FloatDecimal {
            if (coefficient == "0") return FloatDecimal("0", 0)
            val trimmed = coefficient.trimEnd('0')
            return FloatDecimal(trimmed, exponent + coefficient.length - trimmed.length)
        }

        fun binary(significand: Long, exponent: Int): FloatDecimal {
            // Base 10^9 limbs keep each multiplication within signed Long on every target.
            val limbs = mutableListOf(significand % 1_000_000_000, significand / 1_000_000_000)
            if (limbs.last() == 0L) limbs.removeAt(limbs.lastIndex)
            var remaining = if (exponent < 0) -exponent else exponent
            val chunk = if (exponent < 0) 12 else 29
            val base = if (exponent < 0) 5L else 2L
            while (remaining > 0) {
                val count = minOf(remaining, chunk)
                var factor = 1L
                repeat(count) { factor *= base }
                var carry = 0L
                for (index in limbs.indices) {
                    val product = limbs[index] * factor + carry
                    limbs[index] = product % 1_000_000_000
                    carry = product / 1_000_000_000
                }
                if (carry > 0) limbs.add(carry)
                remaining -= count
            }
            val coefficient = buildString {
                append(limbs.last())
                for (index in limbs.lastIndex - 1 downTo 0) append(limbs[index].toString().padStart(9, '0'))
            }
            return of(coefficient, minOf(exponent, 0))
        }
    }
}
