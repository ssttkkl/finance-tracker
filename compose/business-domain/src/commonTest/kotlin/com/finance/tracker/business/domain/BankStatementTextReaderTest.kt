package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BankStatementTextReaderTest {
    @Test fun ccbRowsKeepDeclarationSequenceAndWorkbookPadding() {
        val records = listOf(listOf("中国建设银行个人活期账户全部交易明细"),
            listOf("卡号/账号：12345678 起始日期：20260101 结束日期：20260131"),
            listOf("序号", "交易日期", "交易金额", "账户余额", "摘要"),
            listOf("1", "20260102", "-1.00", "100.00"))
        val doc = CashBankStatementTextReader.readCcb(records)
        assertEquals("", doc.rows.single().payload?.get("摘要"))
        assertEquals(4, doc.rows.single().position)
        assertEquals(true, doc.coverage.complete)
        assertEquals("2025-12-31T16:00:00+00:00", doc.coverage.start)
        assertEquals("2026-01-31T16:00:00+00:00", doc.coverage.end)
        assertEquals("day", doc.coverage.timePrecision)
        assertEquals(4, records[3].size)
    }

    @Test fun tsvByteEntryKeepsInputAndSourceRejection() {
        assertEquals("statement_input_invalid", assertFailsWith<StatementDocumentFailure> {
            readIcbcAsiaStatementDocument(byteArrayOf())
        }.code)
        assertEquals("statement_source_unsupported", assertFailsWith<StatementDocumentFailure> {
            readIcbcAsiaStatementDocument("ordinary utf8".encodeToByteArray())
        }.code)
    }
    @Test fun utf16MalformedPairsAndHistoricalCoverageKeepTheirNativeSemantics() {
        fun bigEndian(vararg units: Int): ByteArray = byteArrayOf(0xfe.toByte(), 0xff.toByte()) +
            units.flatMap { listOf((it ushr 8).toByte(), it.toByte()) }.toByteArray()
        assertEquals("\ufffd", decodeBankStatementUtf16(bigEndian(0xd800, 0x41)))
        assertEquals("\ufffdA", decodeBankStatementUtf16(bigEndian(0xdc00, 0x41)))
        assertEquals("😀", decodeBankStatementUtf16(bigEndian(0xd83d, 0xde00)))
        assertEquals("\ufffd", decodeBankStatementUtf16(bigEndian(0xd800) + byteArrayOf(0x41)))
        assertEquals("\ufeff", decodeBankStatementUtf16(bigEndian(0xfeff)))
        val records = listOf(listOf("中国建设银行个人活期账户全部交易明细"),
            listOf("卡号/账号：12345678 起始日期：00000101 结束日期：00000101"),
            listOf("序号", "交易日期", "交易金额", "账户余额"), listOf("1", "00000101", "1.00", "1.00"))
        val coverage = CashBankStatementTextReader.readCcb(records).coverage
        assertEquals("-0001-12-31T15:54:17+00:00", coverage.start)
        assertEquals("0000-01-01T15:54:17+00:00", coverage.end)
        assertEquals(true, coverage.complete)
    }

}
