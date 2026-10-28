package com.finance.tracker

import com.finance.tracker.core.instantForLocalDateTime
import kotlin.test.*

class CashLocalTimeInputTest {
    @Test fun usesSelectedTimeZoneWithoutChangingWallClock() {
        assertEquals("2026-10-24T04:30:00Z", instantForLocalDateTime("2026-10-24T12:30", "Asia/Shanghai"))
        assertEquals("2026-10-24T12:30:00Z", instantForLocalDateTime("2026-10-24T12:30", "UTC"))
    }
    @Test fun rejectsNonexistentAmbiguousAndInvalidLocalTimes() {
        assertNull(instantForLocalDateTime("2026-03-08T02:30", "America/New_York"))
        assertNull(instantForLocalDateTime("2026-11-01T01:30", "America/New_York"))
        assertNull(instantForLocalDateTime("2026-02-30T12:30", "UTC"))
        assertNull(instantForLocalDateTime("2026-10-24T25:30", "UTC"))
        assertNull(instantForLocalDateTime("2026-10-24T12:30", "Unknown/Zone"))
    }
    @Test fun halfHourDstAndQuarterHourZoneArePreserved() {
        assertNull(instantForLocalDateTime("2026-04-05T01:45", "Australia/Lord_Howe"))
        assertNull(instantForLocalDateTime("2026-10-04T02:15", "Australia/Lord_Howe"))
        assertEquals("2026-10-24T06:45:00Z", instantForLocalDateTime("2026-10-24T12:30", "Asia/Kathmandu"))
    }
}
