package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlatformStatementTextReaderTest {
    @Test fun delimitedRecordsKeepOriginalQuotedAndStrictSemantics() {
        assertEquals(listOf(listOf("a", "b\r\nc"), listOf("", "x")),
            parseCashStatementDelimited("a,\"b\r\nc\"\r\n,x\n", ','))
        assertEquals(listOf(listOf("a\rb")), parseCashStatementDelimited("a\rb", ','))
        assertEquals(listOf(listOf("a"), listOf("b")), parseCashStatementDelimited("a\rb", ',', strict = true))
        assertEquals(listOf(listOf("a\"b", "c")), parseCashStatementDelimited("\"a\"\"b\",c", ','))
        assertEquals("statement_unreadable", assertFailsWith<StatementDocumentFailure> {
            parseCashStatementDelimited("\"a\"x,b", ',', strict = true)
        }.code)
        assertEquals("statement_unreadable", assertFailsWith<StatementDocumentFailure> {
            parseCashStatementDelimited("\"a", ',', strict = true)
        }.code)
    }

    @Test fun csvTextKeepsRawCellsPositionsAndDeclaredCoverage() {
        val text = """
            微信昵称：[合成昵称]
            起始时间：[2026-08-01 00:00:00]
            终止时间：[2026-08-31 23:59:59]
            导出类型：[全部]
            共1笔记录
            交易时间,收/支,金额(元),交易对方,交易单号,商品
            2026-08-15 10:00:00,支出,20.00,原商户,wx-test-id,"  原文,不裁剪  "
        """.trimIndent()
        val document = CashPlatformStatementTextReader.readCsv(text)
        assertEquals("wechat", document.sourceType)
        assertEquals(7, document.rows.single().position)
        assertEquals("  原文,不裁剪  ", document.rows.single().payload?.get("商品"))
        assertEquals("合成昵称", document.sourceNickname)
        assertEquals(true, document.coverage.complete)
        assertEquals("2026-07-31T16:00:00+00:00", document.coverage.start)
        assertEquals("2026-08-31T15:59:59+00:00", document.coverage.end)
        assertEquals("platform-declared-range-filter-count-v1", document.coverage.evidence)
        assertEquals(1L, document.declaredCount)
    }

    @Test fun missingProviderHeaderAndBadRowShapeRemainDistinct() {
        assertEquals("statement_header_not_found", assertFailsWith<StatementDocumentFailure> {
            CashPlatformStatementTextReader.readCsv("交易时间,收/支,金额,交易对方\n2026-01-01,支出,1,商户", "alipay")
        }.code)
        val result = CashPlatformStatementTextReader.readCsv("交易时间,收/支,金额,交易对方,交易订单号\n2026-01-01,支出,1,商户", "alipay")
        assertEquals("row_shape_invalid", result.rows.single().errorCode)
        assertEquals(false, result.coverage.complete)
    }
    @Test fun workbookPaddingAndPlatformCharacterBoundariesKeepTheirOriginalContract() {
        val header = listOf("交易时间", "收/支", "金额(元)", "交易对方", "交易单号", "商品")
        val row = listOf("2026-08-15 10:00:00", "支出", "20.00", "原商户", "wx-test-id")
        val input = listOf(header, row)
        val workbook = CashPlatformStatementTextReader.collectRows(input, "wechat", "sheet:2", padTrailing = true)
        assertEquals("", workbook.rows.single().payload?.get("商品"))
        assertEquals("sheet:2", workbook.tableKey)
        val csv = CashPlatformStatementTextReader.collectRows(input, "wechat", "csv:0", padTrailing = false)
        assertEquals("row_shape_invalid", csv.rows.single().errorCode)
        assertEquals(5, input[1].size)
        assertEquals(false, platformStatementCellBlank("\ufeff"))
        assertEquals(true, platformStatementCellBlank("\u001c"))
        assertEquals(false, platformStatementCellBlank("\u0085"))
        assertEquals(1L, platformStatementUtf8Size(buildString { appendPythonCodePoint(0xd800) }))
        assertEquals(4L, platformStatementUtf8Size("😀"))
        assertEquals(listOf("1"), platformStatementPattern("(?m)^\\s*共\\s*(\\d[\\d,]*)\\s*笔").findAll("a\u0085共1笔").map { it.groupValues[1] }.toList())
        assertEquals(emptyList(), platformStatementPattern("(?m)^\\s*共\\s*(\\d[\\d,]*)\\s*笔").findAll("\u00a0共١笔").map { it.groupValues[1] }.toList())
    }

}
