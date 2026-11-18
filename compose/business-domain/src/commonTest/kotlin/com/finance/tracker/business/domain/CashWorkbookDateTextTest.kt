package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CashWorkbookDateTextTest {
    @Test fun serialDateWindowsAndTheOriginalFictitiousLeapDayArePreserved() {
        for ((serial, text) in listOf(0.0 to "1899-12-31 00:00", 1.0 to "1900-01-01 00:00",
            59.0 to "1900-02-28 00:00", 60.0 to "1900-03-01 00:00", 61.0 to "1900-03-01 00:00",
            1.5 to "1900-01-01 12:00", -0.0 to "1899-12-31 00:00")) {
            assertEquals(text, cashPlatformWorkbookDateText(serial, use1904 = false))
        }
        assertEquals("1904-01-01 00:00", cashPlatformWorkbookDateText(0.0, use1904 = true))
        assertEquals("1904-01-02 12:00", cashPlatformWorkbookDateText(1.5, use1904 = true))
    }
    @Test fun millisecondRoundingAndBankFixedSecondsKeepSeparateText() {
        assertEquals("2023-03-15 02:57:46.667", cashPlatformWorkbookDateText(45000.123456789, false))
        assertEquals("2023-03-15 02:57:46", cashCcbWorkbookDateText(45000.123456789, false))
        assertEquals("1900-01-01 00:00:00", cashCcbWorkbookDateText(1.0, false))
        assertEquals("1900-01-01 00:00", cashPlatformWorkbookDateText(0.9999999999, false))
        assertEquals("1899-12-31 00:00", cashPlatformWorkbookDateText(Double.fromBits(1), false))
    }
    @Test fun originalDayAdjustmentKeepsItsIntOverflowBeforeWidening() {
        val value = Double.fromBits(4746794007248502783L)
        assertEquals("-5877707-06-20 23:59:59.983", cashPlatformWorkbookDateText(value, true))
        assertEquals("-5877707-06-20 23:59:59", cashCcbWorkbookDateText(value, true))
    }
    @Test fun invalidRawDateValuesDoNotProduceInventedDates() {
        for (value in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals("statement_unreadable", assertFailsWith<StatementDocumentFailure> {
                cashPlatformWorkbookDateText(value, false)
            }.code)
        }
    }
}
