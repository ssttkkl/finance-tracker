package com.finance.tracker

import com.finance.tracker.business.domain.CashProjectionDomainFailure
import com.finance.tracker.core.localCashLedgerDateBounds
import com.finance.tracker.core.cashLedgerTimeZoneIds
import kotlin.time.Instant
import kotlin.test.*

class CashLedgerDateBoundsTest {
    @Test fun originalNamedZoneCatalogIsAvailableWithoutAcceptingAdditionalInputForms() {
        assertEquals(603, cashLedgerTimeZoneIds.size)
        for (zone in cashLedgerTimeZoneIds) assertEquals(zone, localCashLedgerDateBounds(null, null, zone).timeZoneId)
    }
    @Test fun explicitZoneBoundsUseTheNativeFirstValidInstant() {
        // Independent Temurin 21.0.6 LocalDate.atStartOfDay results, not converted local clock text.
        val cases = listOf(
            Triple("UTC", "0001-01-01", -62135596800L),
            Triple("UTC", "9999-12-31", 253402214400L),
            Triple("Asia/Tokyo", "2026-07-01", 1782831600L),
            Triple("Asia/Shanghai", "0001-01-01", -62135625943L),
            Triple("Europe/Berlin", "9999-07-01", 253386396000L),
            Triple("America/New_York", "2026-03-08", 1772946000L),
            Triple("America/New_York", "2026-11-01", 1793505600L),
            Triple("America/Sao_Paulo", "2018-11-04", 1541300400L),
            Triple("America/Havana", "2020-11-01", 1604203200L),
            Triple("America/St_Johns", "1988-10-30", 594178200L),
            Triple("Pacific/Apia", "2011-12-30", 1325239200L),
            Triple("Africa/Monrovia", "1972-01-07", 63593070L),
            Triple("Asia/Kathmandu", "1986-01-01", 504901800L),
            Triple("Europe/Paris", "1880-01-01", -2840141361L),
            Triple("Europe/Berlin", "0001-01-01", -62135600008L),
            Triple("Pacific/Kwajalein", "1993-08-21", 745934400L))
        for ((zone, date, second) in cases) {
            val value = localCashLedgerDateBounds(date, null, zone)
            assertEquals(second, value.start?.epochSeconds, "$zone $date")
            assertEquals(0, value.start?.nanosecondsOfSecond)
            assertEquals(date, value.dateFrom)
            assertNull(value.end)
        }
    }
    @Test fun endIsTheNextLocalDayInsteadOfAUniformDayDuration() {
        val spring = localCashLedgerDateBounds("2026W107", "2026-03-08", "America/New_York")
        assertEquals("2026-03-08", spring.dateFrom)
        assertEquals(23 * 3600L, spring.end!!.epochSeconds - spring.start!!.epochSeconds)
        val autumn = localCashLedgerDateBounds("2026-11-01", "2026-11-01", "America/New_York")
        assertEquals(25 * 3600L, autumn.end!!.epochSeconds - autumn.start!!.epochSeconds)
        val skipped = localCashLedgerDateBounds("2011-12-30", "2011-12-30", "Pacific/Apia")
        assertEquals(skipped.start, skipped.end)
    }
    @Test fun fixedLegacyNamesAndEmptyDefaultsKeepTheirMeaning() {
        val defaults = localCashLedgerDateBounds(null, "", null)
        assertEquals("UTC", defaults.timeZoneId); assertNull(defaults.start); assertNull(defaults.end)
        assertEquals(localCashLedgerDateBounds("2026-01-01", null, "America/New_York").start,
            localCashLedgerDateBounds("2026-01-01", null, "US/Eastern").start)
        for ((zone, seconds) in listOf("EST" to 18000, "MST" to 25200, "HST" to 36000)) {
            val value = localCashLedgerDateBounds("2026-07-01", null, "  $zone  ")
            assertEquals(zone, value.timeZoneId)
            assertEquals(Instant.parse("2026-07-01T00:00:00Z").epochSeconds + seconds, value.start?.epochSeconds)
        }
    }
    @Test fun invalidInputNeverDefaultsAndTheOriginalUpperBoundaryRemainsExplicit() {
        for ((from, to, zone) in listOf(Triple("2026-02-30", null, "UTC"), Triple("2026-07-02", "2026-07-01", "UTC"),
            Triple(null, null, "Not/A_Zone"), Triple(null, null, "   "), Triple(null, null, "+01:00"), Triple(null, null, "UTC+01:00"), Triple(null, null, "UT"),
            Triple(null, null, "utc"), Triple(null, null, "america/new_york"))) {
            assertEquals("invalid_filter", assertFailsWith<CashProjectionDomainFailure> { localCashLedgerDateBounds(from, to, zone) }.code)
        }
        assertEquals("internal", assertFailsWith<CashProjectionDomainFailure> {
            localCashLedgerDateBounds(null, "9999-12-31", "Not/A_Zone")
        }.code)
    }
}
