package com.finance.tracker.business.domain

import kotlin.test.*

class CashLedgerFilterDateTest {
    @Test fun calendarAndIsoWeekFormsNormalizeWithoutADeviceCalendar() {
        for ((expected, forms) in listOf(
            "2026-07-01" to listOf("2026-07-01", "20260701", "2026-W27-3", "2026W273"),
            "2014-12-29" to listOf("2015-W01", "2015W01", "2015-W01-1", "2015W011"),
            "2016-01-03" to listOf("2015-W53-7", "2015W537"),
            "2026-12-28" to listOf("2026-W53", "2026W53"),
            "0001-01-01" to listOf("0001-01-01", "00010101", "0001-W01-1"),
            "9999-12-31" to listOf("9999-12-31", "99991231", "9999-W52-5"),
        )) for (raw in forms) assertEquals(expected, normalizeCashLedgerFilterDate(raw), raw)
    }
    @Test fun onlyTheExistingDateLanguageIsAccepted() {
        for (raw in listOf("", "2026-7-1", "2026-07-01T00:00:00Z", " 2026-07-01", "2026-07-01\n", "２０２６-07-01",
            "2026-02-29", "1900-02-29", "0000-01-01", "10000-01-01", "2026-W00", "2026-W54", "2025-W53",
            "2026W270", "2026-W27-8", "9999-W52-7", "2026-W271", "2026W27-1"))
            assertEquals("invalid_filter", assertFailsWith<CashProjectionDomainFailure> { normalizeCashLedgerFilterDate(raw) }.code, raw)
        assertEquals("2000-02-29", normalizeCashLedgerFilterDate("20000229"))
    }
}
