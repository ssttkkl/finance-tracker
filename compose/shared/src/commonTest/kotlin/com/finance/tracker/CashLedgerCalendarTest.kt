package com.finance.tracker

import com.finance.tracker.core.localCashLedgerMonth
import com.finance.tracker.core.cashLedgerOffsetSeconds
import kotlin.time.Instant
import kotlin.test.*

class CashLedgerCalendarTest {
    @Test fun monthUsesExplicitZoneAndPreservesMicrosecondBoundary() {
        val last=Instant.parse("2026-06-30T14:59:59.999999Z")
        val first=Instant.parse("2026-06-30T15:00:00Z")
        assertEquals("2026-06",localCashLedgerMonth(last,"Asia/Tokyo"))
        assertEquals("2026-07",localCashLedgerMonth(first,"Asia/Tokyo"))
        assertEquals("2026-06",localCashLedgerMonth(first,"UTC"))
        assertEquals("2026-07",localCashLedgerMonth(Instant.parse("2026-06-30T18:15:00Z"),"Asia/Kathmandu"))
    }
    @Test fun realDstOffsetAndLegacyFixedZoneRemainDifferent() {
        assertEquals(-18000,cashLedgerOffsetSeconds(Instant.parse("2026-03-08T06:59:59Z"),"America/New_York"))
        assertEquals(-14400,cashLedgerOffsetSeconds(Instant.parse("2026-03-08T07:00:00Z"),"America/New_York"))
        val at=Instant.parse("2026-04-01T04:30:00Z")
        assertEquals("2026-04",localCashLedgerMonth(at,"America/New_York"))
        assertEquals("2026-03",localCashLedgerMonth(at,"EST"))
        assertEquals(-25200,cashLedgerOffsetSeconds(at,"MST"));assertEquals(-36000,cashLedgerOffsetSeconds(at,"HST"))
    }
    @Test fun historicalSecondOffsetsAndFullSourceYearRangeKeepGregorianMonth() {
        assertEquals(561,cashLedgerOffsetSeconds(Instant.parse("1900-02-28T23:50:39Z"),"Europe/Paris"))
        assertEquals("1900-03",localCashLedgerMonth(Instant.parse("1900-02-28T23:50:39Z"),"Europe/Paris"))
        assertEquals("0001-01",localCashLedgerMonth(Instant.parse("0001-01-01T00:00:00Z"),"UTC"))
        assertEquals("0000-12",localCashLedgerMonth(Instant.parse("0001-01-01T00:00:00Z"),"America/New_York"))
        assertEquals("10000-01",localCashLedgerMonth(Instant.parse("9999-12-31T23:59:59.999999Z"),"Pacific/Kiritimati"))
    }
    @Test fun unknownZoneDoesNotInventUtcMonth() {
        assertFails {localCashLedgerMonth(Instant.parse("2026-07-01T00:00:00Z"),"Not/A_Zone")}
    }
}
