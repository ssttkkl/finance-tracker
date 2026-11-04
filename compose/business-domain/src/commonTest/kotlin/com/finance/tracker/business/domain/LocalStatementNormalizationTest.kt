package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashSourceCoverage
import com.finance.tracker.business.contracts.CashStatementDocument
import com.finance.tracker.business.contracts.RawCashRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalStatementNormalizationTest {
    @Test fun publicEntryKeepsSignedZeroPrecisionAndFieldShape() {
        for ((amount, direction, expected) in listOf(
            Triple("0.00", "收入", "0.00"), Triple("0.00", "支出", "0.00"),
            Triple("-0.00", "支出", "-0.00"), Triple("1.234567890123456789", "支出", "-1.234567890123456789"),
            Triple("١٢.٣٠", "收入", "12.30"), Triple("0E+100", "收入", "0"),
        )) {
            val payload = mapOf("交易时间" to "2025-01-01 12:34:56", "金额" to amount, "收/支" to direction,
                "交易对方" to "原商户", "商品说明" to "  完整来源原文  ", "交易订单号" to "test-order-12345")
            val input = CashStatementDocument("alipay", "csv:0", "v1", listOf(RawCashRow(1, payload)), null, null,
                CashSourceCoverage(null, null, false, "", emptyMap(), "v1"), 1, 1)
            val row = normalizeLocalCashStatement(input).rows.single()
            assertNull(row.errorCode)
            assertEquals(emptyList(), row.issueCodes)
            assertEquals(expected, row.fields?.get("amount"))
            assertEquals("2025-01-01T04:34:56+00:00", row.fields?.get("occurred_at"))
            assertEquals("  完整来源原文  ", row.fields?.get("note"))
            assertEquals(payload, row.fields?.get("source_payload"))
            assertEquals(amount, input.rows.single().payload?.get("金额"))
        }
    }

    @Test fun defaultJvmLineEndAndSupplementaryDigitSemanticsArePortable() {
        assertEquals(listOf(1, 3), cashSourceEffectRegex("$", unixLines = false).findAll("X\r\n").map { it.range.first }.toList())
        assertEquals(listOf(1, 2), cashSourceEffectRegex("$", unixLines = false).findAll("X\u0085").map { it.range.first }.toList())
        assertEquals(false, localStatementPattern("\\d{4}").matches("١٢٣٤"))
        assertEquals(true, localStatementPattern("\\d{4}").matches("1234"))
    }
    @Test fun stringPayloadDigestKeepsCanonicalBytesAndRejectsInvalidUtf16() {
        assertEquals("6b1f3bc462772276a82847fc1857d7e2b03443872aab21d97bc6f7368e24e5c8",
            statementStringPayloadDigest(mapOf("尾号" to "0012", "说明" to "原文\n", "😀" to "😀", "\ue000" to "界")))
        assertEquals("", statementStringPayloadDigest(emptyMap<String, String>()))
        assertEquals("", statementStringPayloadDigest(mapOf("raw" to 1)))
        assertEquals("", statementStringPayloadDigest(mapOf("raw" to buildString { appendPythonCodePoint(0xd800) })))
    }

}
