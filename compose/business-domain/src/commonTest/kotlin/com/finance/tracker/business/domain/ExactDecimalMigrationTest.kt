package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExactDecimalMigrationTest {
    @Test fun finiteSyntaxCanBeValidatedBeforeTheApplicationNumericRange() {
        for((text,expected) in listOf("1e21" to DecimalRepresentation(false,"1",21),"-1e-2147483649" to DecimalRepresentation(true,"1",-2147483649),
            " １２_３.４５００ " to DecimalRepresentation(false,"1234500",-4),"-0E2147483648" to DecimalRepresentation(true,"0",2147483648)))
            assertEquals(expected,LedgerDecimal.parseRepresentationOrNull(text),text)
        for(text in listOf("NaN","sNaN1","Infinity","İnf","bad","1e9999999999999999999")) assertNull(LedgerDecimal.parseRepresentationOrNull(text),text)
        assertNull(LedgerDecimal.parseOrNull("1e21"));assertNull(LedgerDecimal.parseOrNull("-1e-2147483649"))
    }
    @Test fun decimalValidationPreservesExactRepresentationIndependentOfContext() {
        val cases = listOf(
            "0" to Triple(false, "0", 0L), "-0" to Triple(true, "0", 0L),
            "0E-19" to Triple(false, "0", -19L), "-0.0000" to Triple(true, "0", -4L),
            "1E+19" to Triple(false, "1", 19L), "1E-18" to Triple(false, "1", -18L),
            "-1E-18" to Triple(true, "1", -18L),
            "1.2345678901234567890" to Triple(false, "12345678901234567890", -19L),
            "99999999999999999999.999999999999999999" to Triple(false, "99999999999999999999999999999999999999", -18L),
            "-99999999999999999999.999999999999999999" to Triple(true, "99999999999999999999999999999999999999", -18L),
        )
        cases.forEach { (text, expected) ->
            val value = assertNotNull(LedgerDecimal.parseOrNull(text), text)
            assertEquals(expected.first, value.representation.negative, text)
            assertEquals(expected.second, value.representation.coefficient, text)
            assertEquals(expected.third, value.representation.exponent, text)
        }
    }

    @Test fun decimalValidationRejectsNonfiniteAndOutOfRangeTypedValues() {
        listOf("NaN", "sNaN", "Infinity", "-Infinity", "1E+20", "1E-19", "0E-20",
            "99999999999999999999.9999999999999999999").forEach { text ->
            assertNull(LedgerDecimal.parseOrNull(text), text)
        }
    }
}
