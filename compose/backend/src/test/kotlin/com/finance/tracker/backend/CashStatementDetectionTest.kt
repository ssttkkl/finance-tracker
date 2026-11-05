package com.finance.tracker.backend

import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.test.*
import kotlinx.serialization.json.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.openxmlformats.schemas.spreadsheetml.x2006.main.STCellType

class CashStatementDetectionTest {
    private val wechatHeader = "交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注"
    private val wechatRow = "2026-10-24 12:00:00,商户消费,合成商户,合成商品,支出,12.50,零钱,支付成功,synthetic-order,synthetic-merchant,合成备注"

    @Test fun recognizesAllSixBuiltInSourcesUsingExistingPublicGoldenBytes() {
        val resources = mapOf(
            "python-cash-statement-documents.json" to listOf(0, 1),
            "python-bank-statement-documents.json" to listOf(0, 6),
            "python-icbc-pdf-documents.json" to listOf(0, 1),
        )
        val recognized = mutableSetOf<String>()
        for ((resource, indices) in resources) {
            val cases = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/$resource"))
                .bufferedReader().use { it.readText() }).jsonArray
            for (index in indices) {
                val case = cases[index].jsonObject
                val bytes = Base64.getDecoder().decode(case.getValue("content").jsonPrimitive.content)
                val expected = case.getValue("document").jsonObject.getValue("source_type").jsonPrimitive.content
                val document = CashStatementDocuments.readDocument(bytes)
                assertEquals(expected, document.sourceType, "$resource:$index")
                recognized += document.sourceType
            }
        }
        assertEquals(setOf("alipay", "wechat", "ccb_debit", "icbc_asia", "icbc_credit", "icbc_debit"), recognized)
    }

    @Test fun probesEveryCandidateBeforeReadingOnlyUniqueMatch() {
        val calls = mutableListOf<String>()
        val expected = CashStatementDocuments.readDocument("$wechatHeader\n$wechatRow\n".encodeToByteArray(), "wechat")
        val candidates = (0..5).map { index -> CashStatementCandidate(
            matches = { calls += "probe-$index"; index == 2 },
            read = { calls += "read-$index"; expected },
        ) }
        assertSame(expected, readUniqueCashStatement(candidates))
        assertEquals((0..5).map { "probe-$it" } + "read-2", calls)
    }

    @Test fun zeroAndMultipleMatchesNeverReadAnyCandidate() {
        for (matches in listOf(emptySet(), setOf(1, 4))) {
            var probes = 0
            var reads = 0
            val failure = assertFailsWith<StatementDocumentFailure> {
                readUniqueCashStatement((0..5).map { index -> CashStatementCandidate(
                    matches = { probes++; index in matches },
                    read = { reads++; error("unexpected full parse") },
                ) })
            }
            assertEquals("import_channel_unrecognized", failure.code)
            assertEquals(6, probes)
            assertEquals(0, reads)
        }
    }

    @Test fun matchedParserFailureDoesNotFallBack() {
        var reads = 0
        val expected = StatementDocumentFailure("statement_header_invalid")
        val actual = assertFailsWith<StatementDocumentFailure> {
            readUniqueCashStatement((0..5).map { index -> CashStatementCandidate(
                matches = { index == 3 },
                read = { reads++; throw expected },
            ) })
        }
        assertSame(expected, actual)
        assertEquals(1, reads)
    }

    @Test fun passwordProbeFailureIsPreservedWithoutFullParse() {
        val expected = StatementPdfPasswordFailure(true)
        var reads = 0
        val actual = assertFailsWith<StatementPdfPasswordFailure> {
            readUniqueCashStatement(listOf(CashStatementCandidate(
                matches = { throw expected }, read = { reads++; error("unexpected full parse") },
            )))
        }
        assertSame(expected, actual)
        assertEquals(0, reads)
    }

    @Test fun unknownContentReturnsRecognitionError() {
        val failure = assertFailsWith<StatementDocumentFailure> {
            CashStatementDocuments.readDocument("not a cash statement".encodeToByteArray())
        }
        assertEquals("import_channel_unrecognized", failure.code)
    }

    @Test fun ambiguousPlatformHeaderDoesNotChooseFirstSource() {
        val header = "$wechatHeader,金额,交易订单号"
        val row = "$wechatRow,12.50,synthetic-alipay-order"
        val failure = assertFailsWith<StatementDocumentFailure> {
            CashStatementDocuments.readDocument("$header\n$row\n".encodeToByteArray())
        }
        assertEquals("import_channel_unrecognized", failure.code)
    }

    @Test fun conflictingHeaderRowsAreAmbiguousInEitherOrderAndWorkbookStringRepresentation() {
        val alipay = listOf("交易时间", "收/支", "金额", "交易对方", "交易订单号")
        for (rows in listOf(listOf(alipay, wechatHeader.split(',')), listOf(wechatHeader.split(','), alipay))) {
            val csv = rows.joinToString("\n") { it.joinToString(",") }.encodeToByteArray()
            assertEquals("import_channel_unrecognized", assertFailsWith<StatementDocumentFailure> {
                CashStatementDocuments.readDocument(csv)
            }.code)
            for (mixed in listOf(false, true)) {
                val content = XSSFWorkbook().use { book ->
                    val sheet = book.createSheet("合成表头")
                    rows.forEachIndexed { rowIndex, values ->
                        val row = sheet.createRow(rowIndex)
                        values.forEachIndexed { column, value ->
                            val cell = row.createCell(column)
                            cell.setCellValue(value)
                            if (mixed && rowIndex == 1) {
                                cell.ctCell.t = STCellType.INLINE_STR
                                cell.ctCell.unsetV()
                                cell.ctCell.addNewIs().t = value
                            }
                        }
                    }
                    ByteArrayOutputStream().also { book.write(it) }.toByteArray()
                }
                assertEquals("import_channel_unrecognized", assertFailsWith<StatementDocumentFailure> {
                    CashStatementDocuments.readDocument(content)
                }.code)
            }
        }
    }

    @Test fun continuesToAcceptWechatCsv() {
        val document = CashStatementDocuments.readDocument("$wechatHeader\n$wechatRow\n".encodeToByteArray())
        assertEquals("wechat", document.sourceType)
        assertEquals("synthetic-order", document.rows.single().payload?.get("交易单号"))
    }

    @Test fun recognitionErrorIsKeptAsOneFileFailure() {
        val store = InMemoryParsedImportStore()
        val service = CashImportContextService(store, "test-workspace", "test-user")
        val session = service.create(listOf(
            CashImportUpload("unknown", "unknown.csv", "not a cash statement".encodeToByteArray()),
            CashImportUpload("valid", "misleading-extension.pdf", "$wechatHeader\n$wechatRow\n".encodeToByteArray()),
        ))
        assertEquals(listOf("failed", "parsed"), session.files.map { it.status })
        assertEquals("import_channel_unrecognized", session.files.first().errorCode)
        assertEquals("暂不支持这种账单" to "reselect_file", cashIssueReasons[session.files.first().errorCode])
        assertEquals("wechat", session.files.last().sourceType)
    }
}
