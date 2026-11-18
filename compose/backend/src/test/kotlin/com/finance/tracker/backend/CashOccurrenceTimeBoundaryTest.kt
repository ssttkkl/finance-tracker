package com.finance.tracker.backend

import java.time.Instant
import kotlin.test.*

class CashOccurrenceTimeBoundaryTest {
    @Test fun manualAndRecordPolicyRejectCashDatesOutsideTheApprovedUtcRange() {
        for (text in listOf("1899-12-31T23:59:59.999999Z", "2101-01-01T00:00:00Z",
            "1900-01-01T00:00:00+01:00", "2100-12-31T23:59:59-01:00", "0001-01-01T00:00:00Z", "9999-12-31T23:59:59Z")) {
            assertFailsWith<IllegalArgumentException>(text) { manualCashOccurrenceTimestamp(text) }
            assertFailsWith<InvalidCashValue>(text) { CashRecordPolicy.normalize("occurred_at", text) }
            assertFailsWith<InvalidCashValue>(text) { CashRecordPolicy.normalize("occurred_at", Instant.parse(text)) }
            assertEquals(Instant.parse(text), pythonCashTimestamp(text))
            assertEquals(Instant.parse(text), manualCashTimestamp(text))
        }
    }

    @Test fun manualAndRecordPolicyAcceptBothEndsAfterOffsetConversion() {
        for ((text, expected) in listOf("1900-01-01" to "1900-01-01T00:00:00Z",
            "1899-12-31T23:00:00-01:00" to "1900-01-01T00:00:00Z",
            "2101-01-01T00:59:59.999999999+01:00" to "2100-12-31T23:59:59.999999Z"))
            assertEquals(Instant.parse(expected), manualCashOccurrenceTimestamp(text), text)
        assertEquals(Instant.parse("2100-12-31T23:59:59.999999Z"),
            CashRecordPolicy.normalize("occurred_at", "2100-12-31T23:59:59.999999999Z"))
        assertNull(CashRecordPolicy.normalize("occurred_at", null))
    }
}
