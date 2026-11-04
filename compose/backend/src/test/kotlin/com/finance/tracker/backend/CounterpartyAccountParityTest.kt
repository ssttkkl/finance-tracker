package com.finance.tracker.backend

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** Previous JVM regex normalizer, kept only as an oracle for the shared code-point scanner. */
class CounterpartyAccountParityTest {
    private val digitRanges = pythonIntegerDigitStarts.joinToString("") { "\\x{${it.toString(16)}}-\\x{${(it + 9).toString(16)}}" }
    private val digitClass = "[$digitRanges]"
    private val spaces = pythonWhitespace.joinToString("") { "\\x{${it.toString(16)}}" }
    private val whitespace = Regex("[$spaces]+")
    private val separators = Regex("[$spaces\\-()（）]")
    private val maskedPattern = Regex("($digitClass+)\\*+($digitClass+)")
    private val tailPattern = Regex("(?<!$digitClass)($digitClass{4})(?!$digitClass)")
    private fun decimal(text: String) = text.isNotEmpty() && text.codePoints().allMatch { code ->
        pythonIntegerDigitStarts.any { code in it until it + 10 }
    }
    private fun digit(text: String) = text.isNotEmpty() && text.codePoints().allMatch { code ->
        code in pythonNondecimalDigits || pythonIntegerDigitStarts.any { code in it until it + 10 }
    }
    private fun previous(input: String, source: String, referenceValue: String): CounterpartyAccount {
        val text = input.trim { it.code in pythonWhitespace }
        if (text in setOf("", "/", "-", "--", "(空)", "（空）")) return CounterpartyAccount("", emptyList())
        if ("*" in text || "＊" in text) {
            var maskedText = text.replace(whitespace, "").replace('＊', '*')
            val compact = maskedText.replace(separators, "")
            val masked = maskedPattern.matchEntire(compact)
            if (masked != null) maskedText = compact
            if (source == "icbc_asia") {
                val reference = referenceValue.replace(separators, "")
                if (masked != null && decimal(reference) && reference.length >= 6 &&
                    masked.groupValues[1].length >= 4 && masked.groupValues[2].length >= 2 &&
                    masked.groupValues[1].length + masked.groupValues[2].length < reference.length &&
                    maskedText.length == reference.length && reference.startsWith(masked.groupValues[1])) {
                    val reconstructed = reference.dropLast(masked.groupValues[2].length) + masked.groupValues[2]
                    // Equality intentionally ignores runtime proof; proof binding is tested through both write validators.
                    return CounterpartyAccount(reconstructed, listOf("masked", "reconstructed"))
                }
            }
            return CounterpartyAccount(maskedText, listOf("masked"))
        }
        val digits = text.replace(separators, "")
        if (digit(digits)) {
            if (digits.length < 4) return CounterpartyAccount("", emptyList())
            if (digits.length == 4) return CounterpartyAccount(digits, listOf("tail"))
            return CounterpartyAccount(digits, listOf("full"))
        }
        if ("@" !in text) {
            val tail = tailPattern.find(text)
            if (tail != null) return CounterpartyAccount(tail.groupValues[1], listOf("tail"))
        }
        return CounterpartyAccount(text, listOf("full"))
    }

    @Test
    fun codePointScannerMatchesPriorRegexForEveryDigitBlockAndMixedInputs() {
        val tokens = mutableListOf("a", "@", "*", "＊", "-", "（", "）", " ", "\u001c", "¹", "😀")
        val values = mutableListOf<String>()
        for (zero in pythonIntegerDigitStarts) {
            val one = String(Character.toChars(zero + 1))
            tokens += one
            values += listOf(one.repeat(4), "tail:" + one.repeat(4), one.repeat(5) + "x1234",
                "(" + one.repeat(4) + ")********" + one.repeat(4))
        }
        pythonNondecimalDigits.forEach { code -> values += "x" + String(Character.toChars(code)).repeat(4) }
        val random = Random(8123)
        repeat(5000) { values += (0 until random.nextInt(0, 28)).joinToString("") { tokens[random.nextInt(tokens.size)] } }
        for (value in values) for (source in listOf("", "icbc_asia")) {
            val reference = value.take(4) + "000011111234"
            assertEquals(previous(value, source, reference), CashRecordClassification.normalizeAccount(value, source, reference),
                "source=$source codePoints=${value.codePoints().toArray().toList()}")
        }
    }
}
