package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashSourceCoverage
import com.finance.tracker.business.contracts.CashStatementDocument
import com.finance.tracker.business.contracts.RawCashRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

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

    @Test fun csvBytesReachLocalNormalizationWithOriginalQuotedFieldsAndExactAmounts() {
        val text="\ufeff交易时间,收/支,金额,交易对方,商品说明,交易订单号\n"+"""2025-01-01 12:34:56,支出,1.234567890123456789,商户,"  原文,不裁剪  ",test-order-12345"""+"\n"
        val bytes=text.encodeToByteArray()
        val document=parseLocalCashCsvStatement(bytes,"alipay")
        val row=document.rows.single()
        assertEquals("csv:0",document.tableKey);assertEquals("alipay",document.sourceType)
        assertEquals(2,row.position);assertNull(row.errorCode)
        assertEquals("-1.234567890123456789",row.fields?.get("amount"))
        assertEquals("2025-01-01T04:34:56+00:00",row.fields?.get("occurred_at"))
        assertEquals("  原文,不裁剪  ",row.fields?.get("note"))
        assertEquals("test-order-12345",row.providerId)
        assertEquals(text,bytes.decodeToString())
    }
    @Test fun csvByteBoundaryKeepsSourceInputAndDecodeFailureOrder() {
        assertEquals("statement_source_unsupported",assertFailsWith<StatementDocumentFailure> {
            parseLocalCashCsvStatement(byteArrayOf(),"icbc")
        }.code)
        assertEquals("statement_input_invalid",assertFailsWith<StatementDocumentFailure> {
            parseLocalCashCsvStatement(byteArrayOf(),"alipay")
        }.code)
        assertEquals("statement_encoding_unsupported",assertFailsWith<StatementDocumentFailure> {
            parseLocalCashCsvStatement(byteArrayOf(0xff.toByte()),"alipay")
        }.code)
    }
    @Test fun oversizedCsvIsRejectedBeforeAnyTextDecoding() {
        val bytes=ByteArray(100*1024*1024+1)
        assertEquals("statement_input_invalid",assertFailsWith<StatementDocumentFailure> {
            parseLocalCashCsvStatement(bytes,"alipay")
        }.code)
        assertEquals("statement_source_unsupported",assertFailsWith<StatementDocumentFailure> {
            parseLocalCashCsvStatement(bytes,"unsupported")
        }.code)
    }
    @Test fun icbcAsiaBytesReachLocalNormalizationWithTheirDeclaredAccountAndCurrency() {
        val text="銀行名稱\t工銀亞洲\r\n銀行賬號\t1234567890\r\n幣種\tHKD\r\n總筆數\t1\r\n日期\t2026-08-01\t-\t2026-08-31\r\n"+
            "序號\t交易時間\t\t起息日期\t業務類型\t摘要\t收入金額\t支出金額\t餘額\t對方賬號\t對方戶名\t憑證號\t匯率\t備註\t交易場所\r\n"+
            "1\t2026-08-15\t10:00:00\t2026-08-15\t消费\t消费\t\t-20.00\t1000.00\t6222\t商户\tv1\t\t\t\n"
        val bytes=byteArrayOf(0xff.toByte(),0xfe.toByte())+text.flatMap {listOf(it.code.toByte(),(it.code ushr 8).toByte())}.toByteArray()
        val document=parseLocalIcbcAsiaStatement(bytes,"icbc-asia")
        assertEquals("icbc_asia",document.sourceType);assertEquals("1234567890",document.sourceAccountIdentifier)
        assertEquals(true,document.coverage.complete)
        val row=document.rows.single()
        assertNull(row.errorCode);assertEquals("-20.00",row.fields?.get("amount"));assertEquals("HKD",row.fields?.get("currency"))
        assertEquals("2026-08-15T02:00:00+00:00",row.fields?.get("occurred_at"))
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
