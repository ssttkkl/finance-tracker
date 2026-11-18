package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class CashFundingScanPrimitiveParityTest {
    @Test fun fixedFundingPatternsKeepJvmUnicodeWhitespaceAndMatchGroups() {
        val ranges = pythonWhitespace.joinToString("") { "\\x{${it.toString(16)}}" }
        val patterns = listOf(
            "(?U)[¥￥]\\s*([+-]?[0-9][0-9,]*(?:\\.[0-9]+)?)(?![0-9A-Za-z.,+-])|(?<![0-9A-Za-z.,+-])([+-]?[0-9][0-9,]*(?:\\.[0-9]+)?)\\s*元",
            "(?U)[0-9](?:[\\s\\-－—·]*[0-9]){7,}", "(?U)\\s+", "[^0-9]", "[*＊•●]",
            "([^@${ranges}]{1,64})@([^@${ranges}]{1,253})",
            "[+]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]{1,18})?",
        )
        val spaces = (pythonWhitespace.toList() + listOf(0xfeff, 0x180e, 0x200b)).map { buildString { appendPythonCodePoint(it) } }
        val inputs = spaces.flatMap { space -> listOf("¥${space}1.00", "1.00${space}元", "1234${space}5678",
            "a${space}b@example.com", "a@ex${space}ample.com", "${space}前缀${space}") } +
            listOf("￥-1,234.50", "1.0元+2元", "¥0.1x", "1234567890123456", "1234－5678", "１,２３４元", "😀@example.com")
        fun result(regex: Regex, input: String) = regex.findAll(input).map { it.range to it.groupValues }.toList()
        for (pattern in patterns) {
            val sharedPattern = pattern.replace(Regex("\\\\x\\{([0-9a-f]+)\\}")) { "\\u" + it.groupValues[1].padStart(4, '0') }
            for (input in inputs) assertEquals(result(Regex(pattern), input), result(fundingSourcePattern(sharedPattern), input), "$pattern $input")
        }
    }
}
