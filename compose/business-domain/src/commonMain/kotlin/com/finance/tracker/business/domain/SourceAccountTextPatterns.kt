package com.finance.tracker.business.domain

interface CashSourceAccountPatterns {
    fun regex(pattern: String): Regex
    fun explicitAmount(text: String): String?
}

internal const val sourceAccountAmountTokenPattern = "(?U)(?:¥|￥)\\s*(\\d+(?:\\.\\d+)?)|\\b(\\d+(?:\\.\\d+)?)\\s*元"

/** The source-account vocabulary's existing JVM Unicode properties, with explicit word boundaries. */
object CashSourceAccountTextPatterns : CashSourceAccountPatterns {
    private val unicodeSpaces = pythonWhitespace.filter { it !in 0x1c..0x1f }
        .joinToString("") { "\\u" + it.toString(16).padStart(4, '0') }
    private val amount = regex(sourceAccountAmountTokenPattern.replace("\\b", ""))

    override fun regex(pattern: String): Regex {
        val unicode = pattern.startsWith("(?U)")
        val spaces = if (unicode) unicodeSpaces else "\\u0020\\u0009-\\u000d"
        val digits = if (unicode) SourceAccountCharacterData.digitPattern else "[0-9]"
        val converted = pattern.removePrefix("(?U)")
            .replace("[\\s\\-－—()（）]", "[$spaces\\-－—()（）]")
            .replace("\\s", "[$spaces]").replace("\\d", digits)
        check("\\b" !in converted) { "Source amount boundaries are evaluated explicitly" }
        return cashSourceEffectRegex(converted, unixLines = false)
    }

    override fun explicitAmount(text: String): String? {
        var index = 0
        while (index < text.length) {
            val first = text[index]
            val paired = first.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()
            val code = if (paired) 0x10000 + ((first.code - 0xd800) shl 10) + text[index + 1].code - 0xdc00 else first.code
            // Test only valid starts before running the token regex. Parsing and rejecting each
            // suffix of a word-adjacent digit run would introduce quadratic work.
            if (first in "¥￥" || SourceAccountCharacterData.isDigit(code) && !SourceAccountCharacterData.wordBefore(text, index)) {
                amount.matchAt(text, index)?.let { return it.groupValues[1].ifEmpty { it.groupValues[2] } }
            }
            index += if (paired) 2 else 1
        }
        return null
    }
}
