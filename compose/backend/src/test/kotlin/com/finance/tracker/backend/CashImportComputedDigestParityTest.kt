package com.finance.tracker.backend

import com.finance.tracker.business.domain.SourceJsonInteger
import com.finance.tracker.business.domain.localCashImportDigest
import com.finance.tracker.business.domain.localCashImportVirtualId
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import kotlin.test.*

class CashImportComputedDigestParityTest {
    @Test fun nativeDecimalStringsRemainExactAcrossScaleAndSignedZero() {
        var checked = 0
        for (sign in listOf("", "-")) for (coefficient in listOf("0", "1", "1200", "12345678901234567890"))
            for (exponent in -60..30) {
                val raw = "$sign${coefficient}E$exponent"
                val amount = CashAmount.parseOrNull(raw) ?: continue
                val decimal = BigDecimal(raw).toString().let {
                    if (amount.representation.negative && amount.representation.coefficient == "0") "-$it" else it
                }
                val expected = sourceDigest(decimal)
                assertEquals(expected, cashImportDigest(amount), raw)
                assertEquals(expected, localCashImportDigest(listOf(amount)), raw)
                assertEquals(expected, localCashImportDigest(listOf(CashDecimalLiteral(raw))), raw)
                checked++
            }
        assertTrue(checked > 200)
    }

    @Test fun computedNestedValuesMatchNativeTimestampAndSourceValueBoundaries() {
        for (raw in listOf("0001-01-01T00:00:00Z", "1969-12-31T23:59:59.999999999Z",
            "2026-10-02T10:00:00.123456789Z", "9999-12-31T23:59:59.000000999Z")) {
            val nativeTime = Instant.parse(raw)
            val localTime = kotlin.time.Instant.fromEpochSeconds(nativeTime.epochSecond, nativeTime.nano.toLong())
            val first = linkedMapOf("amount" to CashAmount.parse("-0.0000000"), "time" to nativeTime,
                "nested" to listOf(CashDecimalLiteral("123E2"), 1.0, null, false, "原文"))
            val second = first + ("time" to localTime)
            val expectedTime = ParsedImportCodec.timeText(nativeTime).replace('T', ' ')
            assertEquals(sourceDigest(mapOf("amount" to "-0E-7", "time" to expectedTime,
                "nested" to listOf("1.23E+4", 1.0, null, false, "原文"))), cashImportDigest(first))
            assertEquals(cashImportDigest(first), localCashImportDigest(listOf(second)), raw)
        }
    }

    @Test fun virtualIdsMatchNativeUnsignedHexConversionAndNeverNarrowThroughLong() {
        val offset = BigInteger.ONE.shiftLeft(64)
        val ids = mutableSetOf<String>()
        for (index in 0..999) {
            val key = "18446744073709551617"
            val original = offset + BigInteger(sourceDigest("cash-import-virtual-v1", "component", listOf(key, index)).take(32), 16)
            assertEquals(original, cashImportVirtual("component", key, index))
            val local = localCashImportVirtualId("component", listOf(key, SourceJsonInteger(index)))
            assertEquals(original.toString(), local)
            assertTrue(local.toBigInteger() >= offset)
            assertTrue(ids.add(local))
        }
    }
}
