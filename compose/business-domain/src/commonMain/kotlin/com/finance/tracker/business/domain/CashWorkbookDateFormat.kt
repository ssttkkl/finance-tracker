package com.finance.tracker.business.domain

/** Recognize the existing workbook date-format language without a platform regex or locale. */
fun cashWorkbookIsDateFormat(formatId: Int, format: String?): Boolean {
    if (formatId in 14..22 || formatId in 45..47) return true
    if (format.isNullOrEmpty()) return false
    var index = 0
    var text = buildString(format.length) {
        while (index < format.length) {
            val char = format[index]
            val next = format.getOrNull(index + 1)
            when {
                char.code == 92 && next != null && next in "-,. \\" -> index++
                char == ';' && next == '@' -> index += 2
                else -> { append(char); index++ }
            }
        }
    }
    if (text.length >= 3 && text.first() == '[' && text.last() == ']') {
        val body = text.substring(1, text.lastIndex)
        if (listOf("hH", "mM", "sS").any { letters -> body.isNotEmpty() && body.all { it in letters } }) return true
    }
    if (text.startsWith("[DBNum") && text.getOrNull(6) in '1'..'3' && text.getOrNull(7) == ']') text = text.drop(8)
    if (text.startsWith("[$-")) {
        val close = text.indexOf(']', startIndex = 3)
        if (close >= 0 && text.substring(3, close).none { it in "\r\n\u0085\u2028\u2029" }) text = text.substring(close + 1)
    }
    if (text.startsWith('[')) {
        val close = text.indexOf(']')
        if (close > 1 && text.substring(1, close).all { it in 'a'..'z' || it in 'A'..'Z' }) text = text.substring(close + 1)
    }
    val separator = text.indexOf(';')
    if (separator > 0 && separator < text.lastIndex) text = text.take(separator)
    if (text.none { it in "yYmMdDhHsS" }) return false

    // A nonempty date-character prefix, optional zeroes, then optional AM/PM characters.
    // Track all three possibilities when the alphabets overlap, without regex backtracking.
    var prefix = false
    var zeroes = false
    var suffix = false
    for ((position, char) in text.withIndex()) {
        val nextPrefix = (position == 0 || prefix) && (char in "[]yYmMdDhHsS-T/年月日,. :" || char.code == 34 || char.code == 92)
        val nextZeroes = (prefix || zeroes) && char == '0'
        val nextSuffix = (prefix || zeroes || suffix) && char in "ampAMP/"
        prefix = nextPrefix
        zeroes = nextZeroes
        suffix = nextSuffix
    }
    return prefix || zeroes || suffix
}
