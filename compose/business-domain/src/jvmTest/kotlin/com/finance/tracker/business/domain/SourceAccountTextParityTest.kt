package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class SourceAccountTextParityTest {
    @Test fun allCodePointPrefixesMatchOriginalAmountBoundaryAndUnicodeDigitProperty() {
        val amount = Regex("(?U)(?:¥|￥)\\s*(\\d+(?:\\.\\d+)?)|\\b(\\d+(?:\\.\\d+)?)\\s*元")
        val digit = CashSourceAccountTextPatterns.regex("(?U)\\d")
        for (code in 0..0x10ffff) {
            val prefix = String(Character.toChars(code))
            val text = prefix + "12.34元"
            val expected = amount.find(text)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            val actual = CashSourceAccountTextPatterns.explicitAmount(text)
            if (expected != actual) assertEquals(expected, actual, "boundary ${code.toString(16)}")
            val expectedDigit = Character.isDigit(code)
            val actualDigit = digit.matches(prefix)
            if (expectedDigit != actualDigit) assertEquals(expectedDigit, actualDigit, "digit ${code.toString(16)}")
        }
    }
}
