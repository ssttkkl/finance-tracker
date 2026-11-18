package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class LocalCashPlatformDateTest {
    private fun same(expected: String, vararg inputs: String) {
        for (input in inputs) assertEquals(Instant.parse(expected), parseLocalCashPlatformDate(input), input)
    }

    @Test fun isoOffsetsAndSlashesKeepTheOriginalUtcInstant() {
        same("2026-01-01T02:00:00Z", "2026-01-01T10:00:00+08:00", "2026/01/01T10:00:00+08:00")
        same("2026-01-01T10:00:00Z", "2026-01-01T10:00", "2026-01-01t10:00z", "2026-01-01T10:00:00Z")
        same("2026-01-01T01:59:30Z", "2026-01-01T10:00:00+08:00:30")
    }

    @Test fun smartFallbackKeepsMonthEndClampingAndMidnightCarry() {
        same("2025-02-28T10:20:30Z", "2025-02-31 10:20:30", "2025/02/29 10:20:30")
        same("2024-02-29T00:00:00Z", "2024-02-31")
        same("2026-01-02T00:00:00Z", "2026-01-01 24:00", "2026-01-01 24:00:00")
        same("2026-01-01T10:20:30Z", "2026-01-01 10:20:30+08:00", "2026-01-01 10:20:30.trailing")
    }

    @Test fun strictIsoAndUnexpectedSuffixesDoNotUseSmartSpaceRules() {
        for (input in listOf("2025-02-31T10:20:30", "2026-01-01T24:00", "2026-01-01 24:00:01",
            "2026-01-01T10:20:60", "2026-01-01T10:20:30+19:00", "2026-01-01T10:20:30,1", "2026-01-01 junk"))
            assertNull(parseLocalCashPlatformDate(input), input)
        same("2026-01-01T10:20:30Z", "2026-01-01T10:20:30ZZ")
        same("2026-01-01T10:19:30Z", "2026-01-01T10:20Z:30")
    }

    @Test fun isoNanosecondsAreRetainedWhileSpaceFallbackStillTakesNineteenUnits() {
        same("2026-01-01T10:20:30.123456789Z", "2026-01-01T10:20:30.123456789", "2026-01-01T10:20:30.123456789Z")
        same("2026-01-01T10:20:30Z", "2026-01-01 10:20:30.123456789", "2026-01-01T10:20:30.")
        assertNull(parseLocalCashPlatformDate("2026-01-01T10:20:30.1234567890"))
    }

    @Test fun frozenTrimAndUtf16PrefixMatchTheOriginalBoundary() {
        same("2026-01-01T10:20:30Z", "\u00a02026-01-01T10:20:30Z\u3000", "2026-01-01 10:20:30\ud83d\ude00 suffix")
        for (input in listOf("\u00852026-01-01T10:20:30Z", "\u200b2026-01-01T10:20:30Z", "\ud8002026-01-01"))
            assertNull(parseLocalCashPlatformDate(input))
        assertNull(parseLocalCashPlatformDate(null))
    }

    @Test fun prolepticYearsRemainSeparateFromTheCashOccurrenceRange() {
        same("0000-02-29T00:00:00Z", "0000-02-29", "0000-02-29T00:00:00Z")
        same("-0001-01-01T00:00:00Z", "-0001-01-01", "-0001-01-01T00:00:00Z")
        same("+10000-01-01T00:00:00Z", "+10000-01-01", "+10000-01-01T00:00:00Z")
        assertNull(parseLocalCashPlatformDate("-0000-01-01"))
        assertNull(parseLocalCashPlatformDate("+999999999-12-31T23:59:59-18:00"))
        assertNull(parseLocalCashPlatformDate("-999999999-01-01T00:00:00+18:00"))
    }
}
