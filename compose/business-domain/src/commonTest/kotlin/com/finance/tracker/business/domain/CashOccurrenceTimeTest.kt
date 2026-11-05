package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlin.test.*
import kotlin.time.Instant

class CashOccurrenceTimeTest {
    @Test fun recordInputsUseUtcBoundariesAndKeepMicrosecondNormalization() {
        for ((text, expected) in listOf(
            "1900-01-01T00:00:00Z" to "1900-01-01T00:00:00Z",
            "1899-12-31T23:00:00-01:00" to "1900-01-01T00:00:00Z",
            "2101-01-01T00:59:59.999999999+01:00" to "2100-12-31T23:59:59.999999Z",
            "2100-12-31T23:59:59.999999Z" to "2100-12-31T23:59:59.999999Z")) {
            assertEquals(Instant.parse(expected), LocalCashRecordValues.normalize("occurred_at", text), text)
            assertEquals(Instant.parse(expected), LocalCashRecordValues.normalize("occurred_at", Instant.parse(text)), text)
        }
        assertNull(LocalCashRecordValues.normalize("occurred_at", null))
    }

    @Test fun outOfRangeCashInputsAreRejectedWithoutRestrictingTheGeneralParser() {
        for (text in listOf("1899-12-31T23:59:59.999999Z", "2101-01-01T00:00:00Z",
            "1900-01-01T00:00:00+01:00", "2100-12-31T23:59:59-01:00", "0001-01-01T00:00:00Z", "9999-12-31T23:59:59Z")) {
            val parsed = parsePythonCashTimestamp(text)
            assertFailsWith<InvalidCashValue>(text) { LocalCashRecordValues.normalize("occurred_at", text) }
            assertFailsWith<InvalidCashValue>(text) { LocalCashRecordValues.normalize("occurred_at", parsed) }
        }
    }

    @Test fun sourceRowsKeepOriginalDatesAndReportTheExistingUnreadableTimeIssue() {
        for ((date, legal) in listOf("1899-12-31T23:59:59.999999Z" to false, "1900-01-01T00:00:00Z" to true,
            "2100-12-31T23:59:59.999999Z" to true, "2101-01-01T00:00:00Z" to false,
            "1900-01-01 08:05:43" to true, "1900-01-01 08:05:42" to false,
            "2101-01-01 07:59:59" to true, "2101-01-01 08:00:00" to false)) {
            val payload = mapOf("交易时间" to date, "金额" to "1.00", "收/支" to "支出", "交易对方" to "来源商户",
                "交易订单号" to "test-order-12345")
            val document = CashStatementDocument("alipay", "csv:0", "v1", listOf(RawCashRow(1, payload)), null, null,
                CashSourceCoverage(null, null, false, "", emptyMap(), "v1"), 1, 1)
            val row = normalizeLocalCashStatement(document).rows.single()
            assertEquals(payload, row.fields?.get("source_payload"))
            if (legal) {
                assertFalse("time_unreadable" in row.issueCodes, date)
                assertNotNull(row.fields?.get("occurred_at"), date)
            } else {
                assertTrue("time_unreadable" in row.issueCodes, date)
                assertNull(row.fields?.get("occurred_at"), date)
            }
            assertEquals(date, document.rows.single().payload?.get("交易时间"))
        }
    }
}
