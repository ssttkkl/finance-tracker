package com.finance.tracker.backend

import com.finance.tracker.business.domain.readLocalCashXlsxDocument
import java.util.Base64
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFRichTextString
import org.apache.poi.ss.usermodel.FormulaError
import org.openxmlformats.schemas.spreadsheetml.x2006.main.STCellType
import org.openxmlformats.schemas.spreadsheetml.x2006.main.STCellFormulaType
import kotlinx.serialization.json.*
import kotlin.test.*

class LocalCashXlsxParityTest {
    private fun workbook(edit: (XSSFWorkbook) -> Unit = {}): ByteArray = XSSFWorkbook().use { book ->
        val sheet = book.createSheet("selected")
        val records = listOf(listOf("交易时间", "收/支", "金额(元)", "交易对方", "交易单号", "商品")) +
            List(3) { listOf("2026-08-15 10:00:00", "支出", "20", "合成商户", "wx-$it", "原商品") }
        records.forEachIndexed { index, cells -> cells.forEachIndexed { column, text ->
            (sheet.getRow(index) ?: sheet.createRow(index)).createCell(column).setCellValue(text)
        } }
        edit(book)
        ByteArrayOutputStream().also { book.write(it) }.toByteArray()
    }
    private fun compare(content: ByteArray, label: String) {
        assertEquals(CashStatementDocumentReader.readPlatformDocument(content, "wechat"), readLocalCashXlsxDocument(content, "wechat"), label)
    }
    private fun rewrite(content: ByteArray, change: (String, String) -> String): ByteArray = ByteArrayOutputStream().also { target ->
        ZipOutputStream(target).use { output -> ZipInputStream(ByteArrayInputStream(content)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                output.putNextEntry(ZipEntry(entry.name))
                output.write(change(entry.name, input.readBytes().decodeToString()).encodeToByteArray())
                output.closeEntry()
            }
        } }
    }.toByteArray()
    @Test fun foreignNamespaceAttributesCannotOverrideSheetDateWindowOrCellValues() {
        val source = workbook { book ->
            book.createSheet("decoy").createRow(0).createCell(0).setCellValue("wrong sheet")
            val style = book.createCellStyle().apply { dataFormat = 14 }
            book.getSheetAt(0).getRow(1).getCell(0).apply { setCellValue(45000.0); cellStyle = style }
            book.getSheetAt(0).getRow(1).getCell(2).setCellValue(20.0)
        }
        for (kind in listOf("sheet", "date-window", "cell-type", "cell-style")) {
            var rewritten = false
            val changed = rewrite(source) { name, original -> (when {
                kind == "sheet" && name == "xl/workbook.xml" -> {
                    val decoy = Regex("<sheet[^>]*name=\"decoy\"[^>]*r:id=\"([^\"]+)\"")
                        .find(original)?.groupValues?.get(1) ?: error("synthetic decoy id missing")
                    original.replace("<sheet name=\"selected\"", "<sheet xmlns:foreign=\"urn:unrelated\" foreign:id=\"$decoy\" name=\"selected\"")
                }
                kind == "date-window" && name == "xl/workbook.xml" -> original.replace("<workbookPr ", "<workbookPr xmlns:foreign=\"urn:unrelated\" foreign:date1904=\"1\" ")
                kind == "cell-type" && name == "xl/worksheets/sheet1.xml" -> original.replace("<c r=\"C2\"", "<c xmlns:foreign=\"urn:unrelated\" foreign:t=\"b\" r=\"C2\"")
                kind == "cell-style" && name == "xl/worksheets/sheet1.xml" -> original.replace("<c r=\"C2\"", "<c xmlns:foreign=\"urn:unrelated\" foreign:s=\"1\" r=\"C2\"")
                else -> original
            }).also { if (it != original) rewritten = true } }
            assertTrue(rewritten, kind)
            val native = CashStatementDocumentReader.readPlatformDocument(source, "wechat")
            assertEquals(native, CashStatementDocumentReader.readPlatformDocument(changed, "wechat"), "native $kind")
            assertEquals(native, readLocalCashXlsxDocument(changed, "wechat"), "local $kind")
        }
    }
    @Test fun extensionPayloadCannotInsertNumberFormatsOrCellStyles() {
        val source = workbook { book -> book.getSheetAt(0).getRow(1).getCell(2).setCellValue(20.0) }
        val expected = CashStatementDocumentReader.readPlatformDocument(source, "wechat")
        val payloads = listOf(
            "<foreign:cellXfs xmlns:foreign=\"urn:unrelated\"><xf numFmtId=\"14\"/></foreign:cellXfs>",
            "<cellXfs><xf numFmtId=\"14\"/></cellXfs>",
            "<numFmt numFmtId=\"0\" formatCode=\"yyyy-mm-dd\"/>",
        )
        for (payload in payloads) {
            var rewritten = false
            val changed = rewrite(source) { name, original ->
                if (name != "xl/styles.xml") original else {
                    val marker = "<cellXfs "
                    assertTrue(marker in original)
                    rewritten = true
                    original.replace(marker, "<extLst><ext uri=\"urn:unrelated\">$payload</ext></extLst>$marker")
                }
            }
            assertTrue(rewritten)
            assertEquals(expected, CashStatementDocumentReader.readPlatformDocument(changed, "wechat"), "native $payload")
            assertEquals(expected, readLocalCashXlsxDocument(changed, "wechat"), "local $payload")
        }
    }
    @Test fun errorCellsKeepNativeCodesAndCannotSmuggleNumericAmounts() {
        val valid = FormulaError.entries.map { it.string }
        assertEquals(10, valid.size)
        for (text in listOf("20", "", "#SPILL!", "#CALC!", "#DIV/0! ", "#div/0!") + valid + listOf(null)) {
            val content = workbook { book -> book.getSheetAt(0).getRow(1).getCell(2).apply {
                setCellValue(20.0)
                ctCell.t = STCellType.E
                if (text == null) ctCell.unsetV() else ctCell.v = text
            } }
            if (text == null || text in valid) compare(content, "error $text") else {
                assertEquals("statement_unreadable", assertFailsWith<StatementDocumentFailure>("native $text") {
                    CashStatementDocumentReader.readPlatformDocument(content, "wechat")
                }.code)
                assertEquals("statement_unreadable", assertFailsWith<StatementDocumentFailure>("local $text") {
                    readLocalCashXlsxDocument(content, "wechat")
                }.code)
            }
        }
    }
    @Test fun absentEmptyAndWhitespaceValuesKeepNativeCellTypeSemantics() {
        for (kind in listOf(null, STCellType.N, STCellType.S, STCellType.STR, STCellType.B)) {
            val values = if (kind == STCellType.S) listOf(null, "", " \t\n ", "0", "1", "-1", "2147483648", "not-index", "0.0")
                else listOf(null, "", " \t\n ")
            for (text in values) for (dateStyle in if (kind == null || kind == STCellType.N) listOf(false, true) else listOf(false)) {
                val content = workbook { book -> book.getSheetAt(0).getRow(1).getCell(2).apply {
                    setCellValue(20.0)
                    if (kind == null) ctCell.unsetT() else ctCell.t = kind
                    if (text == null) ctCell.unsetV() else ctCell.v = text
                    if (dateStyle) cellStyle = book.createCellStyle().apply { dataFormat = 14 }
                } }
                val label = "type=$kind value=$text date=$dateStyle"
                val expected = runCatching { CashStatementDocumentReader.readPlatformDocument(content, "wechat") }
                if (expected.isSuccess) assertEquals(expected.getOrThrow(), readLocalCashXlsxDocument(content, "wechat"), label)
                else {
                    assertEquals("statement_unreadable", assertIs<StatementDocumentFailure>(expected.exceptionOrNull()).code, label)
                    assertEquals("statement_unreadable", assertFailsWith<StatementDocumentFailure>(label) {
                        readLocalCashXlsxDocument(content, "wechat")
                    }.code)
                }
            }
        }
    }
    @Test fun contentTypesCannotBeMissingMalformedOrInAnotherNamespace() {
        val source = workbook()
        for (text in listOf("", "not XML", "<Types/>", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>")) {
            val content = rewrite(source) { name, original -> if (name == "[Content_Types].xml") text else original }
            assertEquals("statement_unreadable", assertFailsWith<StatementDocumentFailure> { CashStatementDocumentReader.readPlatformDocument(content, "wechat") }.code)
            assertEquals("statement_unreadable", assertFailsWith<StatementDocumentFailure> { readLocalCashXlsxDocument(content, "wechat") }.code)
        }
    }
    @Test fun richWhitespaceRunsAndInlineWhitespaceKeepTheirOriginalCells() {
        val content = workbook { book ->
            val rich = XSSFRichTextString("A B")
            rich.applyFont(0, 1, book.createFont()); rich.applyFont(1, 2, book.createFont()); rich.applyFont(2, 3, book.createFont())
            book.getSheetAt(0).getRow(1).getCell(5).setCellValue(rich)
            book.getSheetAt(0).getRow(2).getCell(5).setCellValue(" \n \t")
        }
        compare(content, "rich and shared whitespace")
        val inline = rewrite(content) { name, original -> if (name == "xl/worksheets/sheet1.xml")
            original.replace(Regex("<c r=\"F4\"[^>]*>.*?</c>"), "<c r=\"F4\" t=\"inlineStr\"><is><t xml:space=\"preserve\"> \n \t</t></is></c>") else original }
        compare(inline, "inline whitespace")
    }
    @Test fun theFirstWorkbookViewOwnsActiveSheetSelection() {
        compare(workbook { book ->
            book.createSheet("decoy").createRow(0).createCell(0).setCellValue("wrong sheet")
            book.ctWorkbook.bookViews.addNewWorkbookView().activeTab = 1
        }, "first workbook view")
    }
    @Test fun storedBooleanWordsKeepTheNativeOneOnlyTruthRule() {
        for (text in listOf("0", "1", "true", "false", "", "2")) compare(workbook { book ->
            book.getSheetAt(0).getRow(1).getCell(5).apply { setCellValue(true); ctCell.v = text }
        }, "boolean $text")
    }
    @Test fun everyFormulaKindRejectsTheSelectedFileDespiteAValidCachedValue() {
        val cases = listOf(
            "normal" to "SUM(A1:B2)", "shared" to "sum(a1, 1.00)", "shared" to " a1 + 1 ",
            "shared" to "A1+01", "shared" to "selected!a1", "shared" to "'selected'!a1",
            "shared" to "1e2+A1", "shared" to "", "array" to "SUM(A1:A2)", "dataTable" to "A1",
        )
        for ((kind, formula) in cases) {
            val content = workbook { book ->
                book.getSheetAt(0).getRow(1).getCell(2).apply {
                    setCellValue(20.0)
                    ctCell.addNewF().apply {
                        stringValue = formula
                        when (kind) {
                            "shared" -> { t = STCellFormulaType.SHARED; si = 0; if (formula.isNotEmpty()) ref = "C2:C3" }
                            "array" -> { t = STCellFormulaType.ARRAY; ref = "C2:C3" }
                            "dataTable" -> t = STCellFormulaType.DATA_TABLE
                        }
                    }
                }
            }
            assertEquals("statement_unreadable", assertFailsWith<StatementDocumentFailure>("$kind $formula") {
                readLocalCashXlsxDocument(content, "wechat")
            }.code)
        }
    }
    @Test fun formulasOnAnUnselectedWorksheetDoNotChangeTheSelectedStatementsValues() {
        compare(workbook { book ->
            book.createSheet("unused").createRow(0).createCell(0).cellFormula = "SUM(A1:B2)"
        }, "unselected formula")
    }
    @Test fun activeSheetSparseRowsAndEveryCellKindKeepOriginalText() {
        val content = XSSFWorkbook().use { book ->
            book.createSheet("decoy").createRow(0).createCell(0).setCellValue("wrong sheet")
            val sheet = book.createSheet("selected")
            book.setActiveSheet(1)
            val header = listOf("交易时间", "收/支", "金额(元)", "交易对方", "交易单号", "商品", "备注")
            sheet.createRow(2)
            val head = sheet.getRow(2)
            header.forEachIndexed { column, text -> head.createCell(column).setCellValue(text) }
            val values = listOf("plain", "a\r\nb\t𐐀Σ", "_x000D_", "rich & < >",
                "_x005F_x000D_", "_xD83D__xDE00_", "_X000D_", "_x00gd_")
            values.forEachIndexed { index, value ->
                val row = sheet.createRow(index + 3)
                listOf("2026-08-15 10:00:00", "支出", "20", value, "wx-$index").forEachIndexed { column, text -> row.createCell(column).setCellValue(text) }
                when (index) {
                    0 -> row.createCell(5).setCellValue(true)
                    1 -> row.createCell(5).setCellErrorValue(FormulaError.DIV0.code)
                    2 -> row.createCell(5).setCellValue("=SUM(A1:B2)")
                    3 -> row.createCell(5).setCellValue(XSSFRichTextString("富文本𐐀Σ").also { it.applyFont(0, 2, book.createFont()) })
                }
                row.createCell(6).setBlank()
            }
            sheet.createRow(16) // The logical gap and final blank physical row are both meaningful to POI.
            ByteArrayOutputStream().also { book.write(it) }.toByteArray()
        }
        assertEquals(CashStatementDocumentReader.readPlatformDocument(content, "wechat"), readLocalCashXlsxDocument(content, "wechat"))
    }
    @Test fun everyOriginalWorkbookKeepsCompleteDocumentOrExactFailure() {
        val cases = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/python-cash-statement-documents.json"))
            .bufferedReader().use { it.readText() }).jsonArray.filter {
            val bytes = Base64.getDecoder().decode(it.jsonObject.getValue("content").jsonPrimitive.content)
            bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        }
        assertEquals(8, cases.size)
        for ((index, raw) in cases.withIndex()) {
            val case = raw.jsonObject
            val content = Base64.getDecoder().decode(case.getValue("content").jsonPrimitive.content)
            val before = content.copyOf()
            val source = case.getValue("source").jsonPrimitive.content
            val expected = runCatching { CashStatementDocumentReader.readPlatformDocument(content, source) }
            val actual = runCatching { readLocalCashXlsxDocument(content, source) }
            if (case.getValue("ok").jsonPrimitive.boolean) {
                assertEquals(expected.getOrThrow(), actual.getOrThrow(), "complete document $index")
                assertEquals(CashStatementRowNormalization.normalizeDocument(expected.getOrThrow()),
                    com.finance.tracker.business.domain.normalizeLocalCashStatement(actual.getOrThrow()), "normalized $index")
            } else {
                val code = case.getValue("error").jsonPrimitive.content
                assertEquals(code, assertIs<StatementDocumentFailure>(expected.exceptionOrNull()).code, "native $index")
                assertEquals(code, assertIs<StatementDocumentFailure>(actual.exceptionOrNull()).code, "local $index")
            }
            assertContentEquals(before, content)
        }
    }
}
