package com.finance.tracker

data class AllocationBalance(val state: String, val difference: String, val total: String)

private data class DecimalParts(val digits: String, val scale: Int)

private val DECIMAL_PATTERN = Regex("^[+-]?\\d+(?:\\.\\d+)?$")
private val UNSIGNED_DECIMAL_PATTERN = Regex("^\\d+(?:\\.\\d+)?$")

fun isExactDecimalString(value: String): Boolean = DECIMAL_PATTERN.matches(value)

/** Adds import allocation values as decimal digit strings; no binary floating point is used. */
fun allocationBalance(targetValue: String, values: List<String>): AllocationBalance {
    if (values.size < 2) return AllocationBalance("invalid", "", "")
    val target = parseUnsigned(targetValue.trim().removePrefix("+").removePrefix("-"))
        ?: return AllocationBalance("invalid", "", "")
    var hasEmpty = false
    val amounts = values.map { value ->
        if (value.trim().isEmpty()) {
            hasEmpty = true
            DecimalParts("0", 0)
        } else {
            parseUnsigned(value.trim()) ?: return AllocationBalance("invalid", "", "")
        }
    }
    val scale = maxOf(target.scale, amounts.maxOf { it.scale })
    val targetDigits = rescale(target, scale)
    val totalDigits = amounts.fold("0") { sum, part -> addUnsigned(sum, rescale(part, scale)) }
    val comparison = compareUnsigned(targetDigits, totalDigits)
    val magnitude = if (comparison >= 0) subtractUnsigned(targetDigits, totalDigits) else subtractUnsigned(totalDigits, targetDigits)
    val difference = formatSigned(magnitude, scale, if (comparison < 0) -1 else 1)
    return AllocationBalance(
        state = if (comparison == 0 && !hasEmpty) "complete" else "incomplete",
        difference = difference,
        total = formatSigned(targetDigits, scale, 1),
    )
}

private fun parseUnsigned(value: String): DecimalParts? {
    if (!UNSIGNED_DECIMAL_PATTERN.matches(value)) return null
    val separator = value.indexOf('.')
    val scale = if (separator < 0) 0 else value.length - separator - 1
    val digits = value.replace(".", "").trimStart('0').ifEmpty { "0" }
    return DecimalParts(digits, scale)
}

private fun rescale(value: DecimalParts, scale: Int): String = value.digits + "0".repeat(scale - value.scale)

private fun compareUnsigned(first: String, second: String): Int {
    val a = first.trimStart('0').ifEmpty { "0" }
    val b = second.trimStart('0').ifEmpty { "0" }
    if (a.length != b.length) return a.length.compareTo(b.length)
    return a.compareTo(b)
}

private fun addUnsigned(first: String, second: String): String {
    val width = maxOf(first.length, second.length)
    val a = first.padStart(width, '0')
    val b = second.padStart(width, '0')
    val output = StringBuilder(width + 1)
    var carry = 0
    for (index in width - 1 downTo 0) {
        val sum = (a[index] - '0') + (b[index] - '0') + carry
        output.append(('0'.code + sum % 10).toChar())
        carry = sum / 10
    }
    if (carry > 0) output.append(('0'.code + carry).toChar())
    return output.reverse().toString().trimStart('0').ifEmpty { "0" }
}

/** Requires first >= second. */
private fun subtractUnsigned(first: String, second: String): String {
    val a = first.padStart(maxOf(first.length, second.length), '0')
    val b = second.padStart(a.length, '0')
    val output = StringBuilder(a.length)
    var borrow = 0
    for (index in a.lastIndex downTo 0) {
        var digit = (a[index] - '0') - borrow - (b[index] - '0')
        borrow = if (digit < 0) 1 else 0
        if (digit < 0) digit += 10
        output.append(('0'.code + digit).toChar())
    }
    return output.reverse().toString().trimStart('0').ifEmpty { "0" }
}

private fun formatSigned(digitsValue: String, scale: Int, sign: Int): String {
    val digits = digitsValue.trimStart('0').ifEmpty { "0" }
    val raw = digits.padStart(scale + 1, '0')
    val body = if (scale == 0) raw else "${raw.dropLast(scale)}.${raw.takeLast(scale)}"
    return if (sign < 0 && digits != "0") "-$body" else body
}
