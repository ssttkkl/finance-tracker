package com.finance.tracker.backend

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook

class CashWorkbookDocumentTest {
    private val headers = listOf("交易时间", "交易类型", "交易对方", "商品", "收/支", "金额(元)",
        "支付方式", "当前状态", "交易单号", "商户单号", "备注")
    private val large by lazy { workbook(250) }

    private fun workbook(count: Int): ByteArray = XSSFWorkbook().use { book ->
        val sheet = book.createSheet("账单")
        sheet.createRow(0).createCell(0).setCellValue("微信支付账单明细")
        sheet.createRow(1).createCell(0).setCellValue("微信昵称：[合成测试]")
        val heading = sheet.createRow(3)
        headers.forEachIndexed { column, value -> heading.createCell(column).setCellValue(value) }
        repeat(count) { index ->
            val values = listOf("2026-10-24 12:00:00", "商户消费", "合成商户", "合成商品", "支出", "12.50",
                "零钱", "支付成功", "synthetic-order-$index", "synthetic-merchant-$index", "原文备注-$index")
            val row = sheet.createRow(index + 4)
            values.forEachIndexed { column, value -> row.createCell(column).setCellValue(value) }
        }
        ByteArrayOutputStream().also { book.write(it) }.toByteArray()
    }

    private fun entries(content: ByteArray): LinkedHashMap<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(content)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
            }
        }
        return result
    }

    private fun packed(parts: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            parts.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun changed(name: String, transform: (String) -> String): ByteArray {
        val parts = entries(large)
        parts[name] = transform(parts.getValue(name).decodeToString()).encodeToByteArray()
        return packed(parts)
    }

    private fun rejected(content: ByteArray, code: String = "statement_unreadable") {
        assertEquals(code, assertFailsWith<StatementDocumentFailure> {
            CashStatementDocumentReader.readPlatformDocument(content, "wechat")
        }.code)
    }

    @Test fun readsCompleteLargeWorksheetWithAndWithoutSourceHint() {
        assertTrue(entries(large).getValue("xl/worksheets/sheet1.xml").size > 65536)
        for (source in listOf(null, "wechat")) {
            val document = CashStatementDocumentReader.readPlatformDocument(large, source)
            assertEquals("wechat", document.sourceType)
            assertEquals(250, document.observedCount)
            assertEquals("原文备注-249", document.rows.last().payload?.get("备注"))
            assertEquals("synthetic-order-249", document.rows.last().payload?.get("交易单号"))
            assertEquals("12.50", document.rows.last().payload?.get("金额(元)"))
            assertTrue(document.rows.all { it.errorCode == null })
        }
    }

    @Test fun detectsSmallWorkbookWithoutSourceHint() {
        val document = CashStatementDocuments.readDocument(workbook(1))
        assertEquals("wechat", document.sourceType)
        assertEquals(1, document.rows.size)
        assertEquals("synthetic-order-0", document.rows.single().payload?.get("交易单号"))
    }

    @Test fun doesNotTrustWorksheetDimension() {
        val content = changed("xl/worksheets/sheet1.xml") {
            it.replace(Regex("<dimension ref=\"[^\"]+\"/?>"), "<dimension ref=\"A1:A1\"/>")
        }
        val document = CashStatementDocumentReader.readPlatformDocument(content, "wechat")
        assertEquals(250, document.rows.size)
        assertEquals("原文备注-249", document.rows.last().payload?.get("备注"))
    }

    @Test fun validatesMalformedXmlBeyondFirstChunk() {
        rejected(changed("xl/worksheets/sheet1.xml") { it.removeSuffix("</worksheet>") })
    }

    @Test fun validatesLateRowAndColumnCoordinates() {
        rejected(changed("xl/worksheets/sheet1.xml") {
            it.replace("<row r=\"254\"", "<row r=\"200001\"")
        }, "statement_rows_too_large")
        rejected(changed("xl/worksheets/sheet1.xml") {
            it.replace("r=\"A254\"", "r=\"DY254\"")
        }, "statement_header_invalid")
    }

    @Test fun implicitColumnCannotBypassTheExplicitColumnLimit() {
        val parts = entries(workbook(1))
        parts["custom/implicit.xml"] = """<worksheet><sheetData><row r="1"><c r="DX1"/><c/></row></sheetData></worksheet>""".encodeToByteArray()
        rejected(packed(parts), "statement_header_invalid")
    }

    @Test fun validatesWorksheetContentOutsideConventionalPath() {
        val parts = entries(workbook(1))
        parts["custom/hidden.xml"] = """<worksheet><sheetData><row r="200001"/></sheetData></worksheet>""".encodeToByteArray()
        rejected(packed(parts), "statement_rows_too_large")
    }

    @Test fun rejectsDtdAndEntitiesInAnyXmlPart() {
        for (name in listOf("docProps/extra.xml", "custom/extra.rels")) {
            val parts = entries(workbook(1))
            parts[name] = """<!DOCTYPE x [<!ENTITY unsafe SYSTEM "file:///not-a-real-qa-secret">]><x>&unsafe;</x>""".encodeToByteArray()
            rejected(packed(parts))
        }
    }

    @Test fun rejectsNonemptyDirectoryWithUnknownDeclaredSize() {
        val parts = entries(workbook(1))
        parts["hidden/"] = "unaccounted content".encodeToByteArray()
        rejected(packed(parts))
    }

    @Test fun rejectsUnexpectedMembersAndExcessMemberCount() {
        val parts = entries(workbook(1))
        parts["untrusted.bin"] = byteArrayOf(1)
        rejected(packed(parts))
        parts.remove("untrusted.bin")
        repeat(1001) { parts["extra/$it.xml"] = "<x/>".encodeToByteArray() }
        rejected(packed(parts), "statement_rows_too_large")
    }

    @Test fun rejectsCorruptZipAsDocumentFailure() {
        val content = workbook(1)
        rejected(content.copyOf(content.size / 2))
    }

    @Test fun boundsActualExpandedBytesWithoutTrustingMemberSize() {
        val content = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                for ((name, bytes) in entries(workbook(1))) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("custom/expanded.xml"))
                zip.write("<x>".encodeToByteArray())
                val padding = ByteArray(8192) { ' '.code.toByte() }
                repeat(12800) { zip.write(padding) }
                zip.write("</x>".encodeToByteArray()); zip.closeEntry()
            }
        }.toByteArray()
        assertTrue(content.size < 1024 * 1024)
        rejected(content, "statement_rows_too_large")
    }

    @Test fun rejectsDeepXmlAndEmptyRowCoordinatesAsTypedFailures() {
        val parts = entries(workbook(1))
        parts["custom/deep.xml"] = ("<x>".repeat(129) + "</x>".repeat(129)).encodeToByteArray()
        rejected(packed(parts))
        rejected(changed("xl/worksheets/sheet1.xml") { it.replace("<row r=\"254\"", "<row r=\"\"") },
            "statement_rows_too_large")
    }
}
