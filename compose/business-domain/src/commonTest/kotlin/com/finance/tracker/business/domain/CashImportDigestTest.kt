package com.finance.tracker.business.domain

import kotlin.test.*
import kotlin.time.Instant

class CashImportDigestTest {
    @Test fun decimalDigestPreservesRepresentationAndSignedZero() {
        for ((raw, text) in listOf("-5.00" to "-5.00", "0E+2147483648" to "0E+2147483648",
            "-0.0000000" to "-0E-7", "0.000001" to "0.000001", "0.0000001" to "1E-7",
            "123E2" to "1.23E+4", "1.230000" to "1.230000", "0E+999999999999999999" to "0E+999999999999999999")) {
            assertEquals(localCashSourceDigest(listOf(text)), localCashImportDigest(listOf(CashAmount.parse(raw))), raw)
            assertEquals(localCashSourceDigest(listOf(text)), localCashImportDigest(listOf(CashDecimalLiteral(raw))), raw)
        }
        assertEquals("415c93fe5233592bc1a9e22b655d7735cd9977d41ae431ed77d808f53859dde1",
            localCashImportDigest(listOf(CashAmount.parse("-0.0000000"))))
    }

    @Test fun nestedComputedValuesUseMicrosecondsAndLeaveSourceInputsUntouched() {
        val time = Instant.parse("2026-10-02T10:00:00.123456789Z")
        val input = linkedMapOf<String, Any?>("b" to listOf(CashAmount.parse("-5.00"), time), "a" to SourceJsonInteger(1))
        val before = input.toString()
        val expected = mapOf("b" to listOf("-5.00", "2026-10-02 10:00:00.123456+00:00"), "a" to SourceJsonInteger(1))
        assertEquals(localCashSourceDigest(listOf(expected)), localCashImportDigest(listOf(input)))
        assertEquals("1ff32b44028996e10f8437bface5ccaf2e28243ccd3eaa946bb7111481da2d9f", localCashImportDigest(listOf(input)))
        assertEquals(before, input.toString())
        assertEquals(localCashSourceDigest(listOf("2026-10-02 10:00:00+00:00")),
            localCashImportDigest(listOf(Instant.parse("2026-10-02T10:00:00Z"))))
        assertNotEquals(localCashSourceDigest(listOf(time)), localCashImportDigest(listOf(time)))
    }

    @Test fun virtualIdConversionPreservesTheWholeUnsigned128BitPrefix() {
        assertEquals("18446744073709551616", cashImportVirtualId("test", emptyList()) { "0".repeat(64) })
        assertEquals("340282366920938463481821351505477763071", cashImportVirtualId("test", emptyList()) { "f".repeat(64) })
    }

    @Test fun virtualIdsRetainTheSourceDigestNamespaceAndExactIntegerBits() {
        assertEquals("53190007190066493347657987356067602382", localCashImportVirtualId("transaction", listOf("occurrence:alpha")))
        assertEquals("162819088814594514885209114006859837067", localCashImportVirtualId("component", listOf("18446744073709551617", SourceJsonInteger(0))))
        assertEquals("53401005292528254830514679932208314915", localCashImportVirtualId("relation", listOf("payment_mirror", "10", "20")))
    }
}
