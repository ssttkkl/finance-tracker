package com.finance.tracker

private data class DisplayDecimalParts(val sign: Int, val digits: String, val scale: Int)

private val DISPLAY_DECIMAL_PATTERN = Regex("^([+-]?)(\\d+)(?:\\.(\\d+))?$")

/** Exact decimal helpers used only for portfolio presentation calculations. */
fun displayDecimalAdd(left: String?, right: String?): String? {
    val a = parseDisplayDecimal(left ?: "0") ?: return null
    val b = parseDisplayDecimal(right ?: "0") ?: return null
    val scale = maxOf(a.scale, b.scale)
    val first = a.digits + "0".repeat(scale - a.scale)
    val second = b.digits + "0".repeat(scale - b.scale)
    val result = when {
        a.sign == b.sign -> DisplayDecimalParts(a.sign, addDisplayUnsigned(first, second), scale)
        compareDisplayUnsigned(first, second) == 0 -> DisplayDecimalParts(1, "0", scale)
        compareDisplayUnsigned(first, second) > 0 -> DisplayDecimalParts(a.sign, subtractDisplayUnsigned(first, second), scale)
        else -> DisplayDecimalParts(b.sign, subtractDisplayUnsigned(second, first), scale)
    }
    return formatDisplayDecimal(result)
}

fun displayDecimalCompare(left: String?, right: String?): Int? {
    if (left == null || right == null) return null
    val a = parseDisplayDecimal(left) ?: return null
    val b = parseDisplayDecimal(right) ?: return null
    if (a.digits == "0" && b.digits == "0") return 0
    if (a.sign != b.sign) return a.sign.compareTo(b.sign)
    val scale = maxOf(a.scale, b.scale)
    val magnitude = compareDisplayUnsigned(
        a.digits + "0".repeat(scale - a.scale),
        b.digits + "0".repeat(scale - b.scale),
    )
    return magnitude * a.sign
}

fun displayDecimalSign(value: String?): Int? {
    if (value == null) return null
    val parsed = parseDisplayDecimal(value) ?: return null
    return if (parsed.digits == "0") 0 else parsed.sign
}

fun displayDecimalAbs(value: String?): String? {
    if (value == null) return null
    val parsed = parseDisplayDecimal(value) ?: return null
    return formatDisplayDecimal(parsed.copy(sign = 1))
}

fun displayDecimalMultiply(left: String?, right: String?): String? {
    if (left == null || right == null) return null
    val a = parseDisplayDecimal(left) ?: return null
    val b = parseDisplayDecimal(right) ?: return null
    val sign = if (a.sign == b.sign || a.digits == "0" || b.digits == "0") 1 else -1
    return formatDisplayDecimal(
        DisplayDecimalParts(sign, multiplyDisplayUnsigned(a.digits, b.digits), a.scale + b.scale),
    )
}

fun displayDecimalDivide(numerator: String?, denominator: String?, precision: Int = 18): String? {
    if (numerator == null || denominator == null || precision < 0) return null
    val n = parseDisplayDecimal(numerator) ?: return null
    val d = parseDisplayDecimal(denominator) ?: return null
    if (d.digits == "0") return null
    val exponent = d.scale + precision - n.scale
    val scaledNumerator: String
    val scaledDenominator: String
    if (exponent >= 0) {
        scaledNumerator = n.digits + "0".repeat(exponent)
        scaledDenominator = d.digits
    } else {
        scaledNumerator = n.digits
        scaledDenominator = d.digits + "0".repeat(-exponent)
    }
    val (quotient, remainder) = divideDisplayUnsigned(scaledNumerator, scaledDenominator)
    val rounded = if (compareDisplayUnsigned(multiplyDisplayDigit(remainder, 2), scaledDenominator) >= 0) {
        addDisplayUnsigned(quotient, "1")
    } else quotient
    val sign = if (n.sign == d.sign || rounded == "0") 1 else -1
    return formatDisplayDecimal(DisplayDecimalParts(sign, rounded, precision))
}

fun displayDecimalRound(value: String?, scale: Int): String? {
    if (value == null || scale < 0) return null
    val parsed = parseDisplayDecimal(value) ?: return null
    if (parsed.scale <= scale) return formatDisplayDecimal(parsed)
    val keptLength = (parsed.digits.length - (parsed.scale - scale)).coerceAtLeast(0)
    val kept = if (keptLength == 0) "0" else parsed.digits.take(keptLength)
    val firstDropped = parsed.digits.getOrNull(keptLength) ?: '0'
    val rounded = if (firstDropped >= '5') addDisplayUnsigned(kept, "1") else kept
    return formatDisplayDecimal(DisplayDecimalParts(parsed.sign, rounded, scale))
}

private fun parseDisplayDecimal(value: String): DisplayDecimalParts? {
    val match = DISPLAY_DECIMAL_PATTERN.matchEntire(value) ?: return null
    val fraction = match.groupValues[3]
    val rawDigits = match.groupValues[2] + fraction
    val digits = rawDigits.trimStart('0').ifEmpty { "0" }
    var scale = fraction.length
    var normalizedDigits = digits
    while (scale > 0 && normalizedDigits.endsWith('0')) {
        normalizedDigits = normalizedDigits.dropLast(1)
        scale--
    }
    val sign = if (match.groupValues[1] == "-") -1 else 1
    return DisplayDecimalParts(if (normalizedDigits == "0") 1 else sign, normalizedDigits, scale)
}

private fun formatDisplayDecimal(value: DisplayDecimalParts): String {
    val digits = value.digits.trimStart('0').ifEmpty { "0" }
    val raw = digits.padStart(value.scale + 1, '0')
    val body = if (value.scale == 0) raw else "${raw.dropLast(value.scale)}.${raw.takeLast(value.scale)}"
    val normalized = if (value.scale == 0) body else body.trimEnd('0').trimEnd('.').ifEmpty { "0" }
    return if (value.sign < 0 && digits != "0") "-$normalized" else normalized
}

private fun compareDisplayUnsigned(left: String, right: String): Int {
    val a = left.trimStart('0').ifEmpty { "0" }
    val b = right.trimStart('0').ifEmpty { "0" }
    if (a.length != b.length) return a.length.compareTo(b.length)
    return a.compareTo(b)
}

private fun addDisplayUnsigned(left: String, right: String): String {
    val width = maxOf(left.length, right.length)
    val a = left.padStart(width, '0')
    val b = right.padStart(width, '0')
    val output = StringBuilder(width + 1)
    var carry = 0
    for (index in width - 1 downTo 0) {
        val digit = (a[index] - '0') + (b[index] - '0') + carry
        output.append(('0'.code + digit % 10).toChar())
        carry = digit / 10
    }
    if (carry > 0) output.append(('0'.code + carry).toChar())
    return output.reverse().toString().trimStart('0').ifEmpty { "0" }
}

/** Requires [left] >= [right]. */
private fun subtractDisplayUnsigned(left: String, right: String): String {
    val width = maxOf(left.length, right.length)
    val a = left.padStart(width, '0')
    val b = right.padStart(width, '0')
    val output = StringBuilder(width)
    var borrow = 0
    for (index in width - 1 downTo 0) {
        var digit = (a[index] - '0') - (b[index] - '0') - borrow
        borrow = if (digit < 0) 1 else 0
        if (digit < 0) digit += 10
        output.append(('0'.code + digit).toChar())
    }
    return output.reverse().toString().trimStart('0').ifEmpty { "0" }
}

private fun multiplyDisplayUnsigned(left: String, right: String): String {
    val digits = IntArray(left.length + right.length)
    for (i in left.lastIndex downTo 0) {
        for (j in right.lastIndex downTo 0) {
            val index = i + j + 1
            val product = (left[i] - '0') * (right[j] - '0') + digits[index]
            digits[index] = product % 10
            digits[index - 1] += product / 10
        }
    }
    return digits.joinToString("").trimStart('0').ifEmpty { "0" }
}

private fun multiplyDisplayDigit(value: String, digit: Int): String {
    if (digit == 0 || value == "0") return "0"
    val output = StringBuilder(value.length + 1)
    var carry = 0
    for (index in value.lastIndex downTo 0) {
        val product = (value[index] - '0') * digit + carry
        output.append(('0'.code + product % 10).toChar())
        carry = product / 10
    }
    if (carry > 0) output.append(('0'.code + carry).toChar())
    return output.reverse().toString().trimStart('0').ifEmpty { "0" }
}

private fun divideDisplayUnsigned(numerator: String, denominator: String): Pair<String, String> {
    val quotient = StringBuilder(numerator.length)
    var remainder = "0"
    for (digit in numerator) {
        remainder = (remainder + digit).trimStart('0').ifEmpty { "0" }
        var quotientDigit = 0
        while (compareDisplayUnsigned(remainder, denominator) >= 0) {
            remainder = subtractDisplayUnsigned(remainder, denominator)
            quotientDigit++
        }
        quotient.append(('0'.code + quotientDigit).toChar())
    }
    return quotient.toString().trimStart('0').ifEmpty { "0" } to remainder
}
