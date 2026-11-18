package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class CashWorkbookNumberTextTest {
    @Test fun javaNotationAndTinyValuesKeepTheirOriginalCellText() {
        val vectors = listOf(
            0.0 to "0", -0.0 to "0", 1.0 to "1", -1.0 to "-1",
            0.1 to "0.1", 0.001 to "0.001", 0.0001 to "1.0E-4",
            Double.fromBits(1) to "4.9E-324", Double.fromBits(2) to "9.9E-324",
            Double.fromBits(3) to "1.5E-323", Double.fromBits(0x0010000000000000L) to "2.2250738585072014E-308",
            Double.fromBits(0x3ff0000000000001L) to "1.0000000000000002",
            1e15 to "1000000000000000", 1e16 to "1.0E16", 1e23 to "1.0E23",
            Double.MAX_VALUE to "1.7976931348623157E308",
            Double.POSITIVE_INFINITY to "Infinity", Double.NEGATIVE_INFINITY to "-Infinity", Double.NaN to "NaN",
        )
        for ((value, text) in vectors) assertEquals(text, cashPlatformWorkbookNumberText(value), "bits=${value.toBits()}")
    }

    @Test fun javaDisplayHasIndependentSignedZeroAndScientificThresholds() {
        for ((value, text) in listOf(-0.0 to "-0.0", 0.0 to "0.0", 1e6 to "1000000.0", 1e7 to "1.0E7",
            1e-3 to "0.001", 1e-4 to "1.0E-4", -Double.fromBits(1) to "-4.9E-324")) {
            assertEquals(text, javaBinary64Text(value))
        }
    }

    @Test fun bankIntegerRenderingRemainsDistinctAndSaturatesExactlyAsBefore() {
        for ((value, text) in listOf(1e16 to "10000000000000000", 1e23 to "9223372036854775807",
            -1e23 to "-9223372036854775808", Double.MAX_VALUE to "9223372036854775807",
            -Double.MAX_VALUE to "-9223372036854775808", -0.0 to "0", 0.0001 to "1.0E-4",
            Double.fromBits(1) to "4.9E-324", Double.POSITIVE_INFINITY to "Infinity", Double.NaN to "NaN")) {
            assertEquals(text, cashCcbWorkbookNumberText(value), "bits=${value.toBits()}")
        }
        assertEquals("5e-324", pythonFloatText(Double.fromBits(1)))
        assertEquals("1e+16", pythonFloatText(1e16))
    }
}
