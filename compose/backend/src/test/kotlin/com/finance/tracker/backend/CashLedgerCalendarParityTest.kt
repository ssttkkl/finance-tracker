package com.finance.tracker.backend

import com.finance.tracker.business.domain.cashLedgerMonthAtOffset
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class CashLedgerCalendarParityTest {
    @Test fun sharedGregorianMonthMatchesNativeBrowserCalendarAcrossZoneAndYearBoundaries() {
        val instants = listOf(1, 2, 400, 1582, 1900, 1970, 2000, 2026, 2400, 9999).flatMap { year ->
            listOf(1, 3, 7, 12).flatMap { month ->
                val first = LocalDate.of(year, month, 1)
                listOf(first.atStartOfDay().toInstant(java.time.ZoneOffset.UTC),
                    first.withDayOfMonth(first.lengthOfMonth()).atTime(23, 59, 59, 999999000).toInstant(java.time.ZoneOffset.UTC))
            }
        }
        for (name in ZoneId.getAvailableZoneIds().sorted()) {
            val zone = ZoneId.of(name)
            for (at in instants) {
                val local = at.atZone(zone)
                val expected = "%04d-%02d".format(Locale.ROOT, local.year, local.monthValue)
                assertEquals(expected, cashLedgerMonthAtOffset(Instant.fromEpochSeconds(at.epochSecond, at.nano),
                    zone.rules.getOffset(at).totalSeconds), "$name at $at")
            }
        }
    }
}
