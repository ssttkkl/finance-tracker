package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SourceAccountAmountTest {
    @Test fun sourceAmountsKeepExactScaleAndNativeUnicodeDigitBoundary() {
        for ((text, expected) in listOf("１２.３００" to "12.300", "+.١٢" to "0.12", "1." to "1",
            "-0.000" to "0.000", "1E+3" to "1000", "1e00000000000000000000001" to "10"))
            assertEquals(expected, parseSourceAccountAmount(text).toPlainString(), text)
        for (text in listOf(" 1", "1 ", "1_0", "NaN", "Infinity", "1e", "1e-2147483648", "1.0e-2147483647",
            "1e2147483649", "𑽑", "𝟙", "1\uD800"))
            assertFailsWith<NumberFormatException>(text) { parseSourceAccountAmount(text) }
        assertEquals("1E+2147483648", parseSourceAccountAmount("1e2147483648").toString())
        assertEquals("1.0E+2147483649", parseSourceAccountAmount("1.0e2147483649").toString())
    }
}
