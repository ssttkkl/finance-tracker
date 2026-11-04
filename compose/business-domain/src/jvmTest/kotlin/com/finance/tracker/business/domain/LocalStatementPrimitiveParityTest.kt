package com.finance.tracker.business.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalStatementPrimitiveParityTest {
    @Test fun zeroPlainKeepsOriginalBigDecimalAcceptanceAndScale() {
        val inputs = (-200..200).flatMap { exponent ->
            listOf("0E$exponent", "-0.000E$exponent", "+.000E$exponent", "000.00E$exponent")
        } + listOf("", "0,000.00", "0_0.00", "NaN", "0E2147483647", "0E2147483648", "0E-2147483648", "0.0E-2147483647", "0E99999999999999999999")
        for (input in inputs) {
            val original = try { BigDecimal(input).abs().toPlainString() } catch (_: NumberFormatException) { "0" }
            assertEquals(original, statementZeroPlain(input), input)
        }
    }

    @Test fun finiteRowPatternVocabularyMatchesOriginalJvmRegex() {
        val digits = pythonIntegerDigitStarts.joinToString("", "[", "]") { "\\x{${it.toString(16)}}-\\x{${(it + 9).toString(16)}}" }
        val spaces = pythonWhitespace.joinToString("") { "\\x{${it.toString(16)}}" }
        val patterns = listOf("[$spaces]+", "^(银行转证券|证券转银行|银转证|证转银)$digits+[^$spaces]*$",
            "[:$digits$spaces.-]+", "[$spaces\\-－—()（）]", "\\d{12,19}", "\\d{4,6}[*＊]{2,}\\d{4}",
            "(\\d{4}-\\d{2}-\\d{2})", "(\\d{2}:\\d{2}:\\d{2})", "[0-9]{4,6}[*＊]{2,}([0-9]{4})",
            "[（(](?:尾号)?([0-9]{4})[）)]$", "([\u4e00-\u9fff]+银行)([\u4e00-\u9fff]*卡)?",
            "[^\u4e00-\u9fff]", "[+-]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]{1,18})?")
        val points = (pythonWhitespace.toList() + pythonIntegerDigitStarts.flatMap { listOf(it, it + 9) } +
            listOf(0xd800, 0xdc00, 0x1f600)).distinct()
        val inputs = listOf("", "123456789012", "1234**5678", "2025-01-01 12:34:56", "工商银行（尾号1234）", "-1,000.00") +
            points.flatMap { point ->
                val char = buildString { appendPythonCodePoint(point) }
                listOf(char, "$char$char", "银行转证券${char}x", "银行转证券${char}\r\n", ":$char.-", "1234**${char}678", "A${char}B")
            }
        fun matches(regex: Regex, input: String) = regex.findAll(input).map { m -> m.range to m.groups.map { it?.value } }.toList()
        for (pattern in patterns) {
            val original = Regex(pattern)
            val local = localStatementPattern(pattern)
            for (input in inputs) {
                assertEquals(original.matches(input), local.matches(input), "matches $pattern $input")
                assertEquals(matches(original, input), matches(local, input), "find $pattern $input")
                assertEquals(original.replace(input, ""), local.replace(input, ""), "replace $pattern $input")
            }
        }
    }
}
