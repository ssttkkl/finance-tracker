package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceAccountTextPatternsTest {
    @Test fun amountBoundaryCanFindTheDecimalTailAfterAnInvalidLeadingWordBoundary() {
        assertEquals("34", CashSourceAccountTextPatterns.explicitAmount("A12.34元"))
        assertEquals("34", CashSourceAccountTextPatterns.explicitAmount("A" + "1".repeat(10_000) + ".34元"))
        assertNull(CashSourceAccountTextPatterns.explicitAmount("商户12元"))
        assertEquals("12", CashSourceAccountTextPatterns.explicitAmount("商户(卡)12元"))
        assertEquals("12", CashSourceAccountTextPatterns.explicitAmount("商户¥12元"))
        assertNull(CashSourceAccountTextPatterns.explicitAmount("😀𐐀12元"))
        assertEquals("12", CashSourceAccountTextPatterns.explicitAmount("😀12元"))
        assertEquals("12", CashSourceAccountTextPatterns.explicitAmount("\uD80012元"))
        assertNull(CashSourceAccountTextPatterns.explicitAmount("𠀀12元"))
        assertEquals("34", CashSourceAccountTextPatterns.explicitAmount("A" + "𑽐".repeat(1_000) + ".34元"))
    }
    @Test fun sourceUnicodeDigitsAndNativeSpaceModesRemainDistinct() {
        assertTrue(CashSourceAccountTextPatterns.regex("(?U)\\d{12,19}").matches("𑽐".repeat(12)))
        assertEquals("卡", CashSourceAccountTextPatterns.regex("(?U)分期\\s*[（(]\\s*\\d+\\s*期\\s*[）)]")
            .replace("卡分期(１２期)", ""))
        assertEquals("1234\u00a05678", CashSourceAccountTextPatterns.regex("[\\s\\-－—()（）]").replace("(1234\u00a05678)", ""))
    }
}
