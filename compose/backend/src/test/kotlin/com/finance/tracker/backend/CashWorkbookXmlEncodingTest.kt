package com.finance.tracker.backend

import com.finance.tracker.business.domain.CashPlatformStatementTextReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import kotlin.test.*

/** Full native-file baseline before replacing XML byte decoding, not a charset fallback policy. */
class CashWorkbookXmlEncodingTest {
    private data class Encoding(
        val label: String,
        val charset: String,
        val declaration: String?,
        val bom: String = "",
        val escapeNonLatin: Boolean = false,
    )

    private val records = listOf(
        listOf("交易时间", "收/支", "金额(元)", "交易对方", "交易单号"),
        listOf("2026-08-15 10:00:00", "支出", "20.00", "合成商户é𐐀Σ", "wx-1"),
    )

    private fun workbook(): ByteArray = XSSFWorkbook().use { book ->
        val sheet = book.createSheet()
        records.forEachIndexed { index, cells ->
            val row = sheet.createRow(index)
            cells.forEachIndexed { column, text -> row.createCell(column).setCellValue(text) }
        }
        ByteArrayOutputStream().also { book.write(it) }.toByteArray()
    }

    private fun reencode(source: ByteArray, encoding: Encoding): ByteArray {
        val target = ByteArrayOutputStream()
        ZipOutputStream(target).use { output ->
            ZipInputStream(ByteArrayInputStream(source)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    var bytes = input.readBytes()
                    if (entry.name.endsWith(".xml") || entry.name.endsWith(".rels")) {
                        var text = bytes.decodeToString(throwOnInvalidSequence = true)
                            .replaceFirst(Regex("^<\\?xml[^?]*\\?>"), "")
                        if (encoding.escapeNonLatin) text = buildString {
                            text.codePoints().forEach { point ->
                                if (point > 255) append("&#x").append(point.toString(16)).append(';')
                                else appendCodePoint(point)
                            }
                        }
                        if (encoding.declaration != null)
                            text = "<?xml version=\"1.0\" encoding=\"${encoding.declaration}\"?>" + text
                        val bom = encoding.bom.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        bytes = bom + text.toByteArray(Charset.forName(encoding.charset))
                    }
                    output.putNextEntry(ZipEntry(entry.name)); output.write(bytes); output.closeEntry()
                }
            }
        }
        return target.toByteArray()
    }

    @Test fun acceptedXmlEncodingsPreserveTheCompleteDocumentIncludingSupplementaryText() {
        val expected = CashPlatformStatementTextReader.collectRows(records, "wechat", "sheet:0", true)
        val source = workbook()
        val encodings = listOf(
            Encoding("UTF-8", "UTF-8", "UTF-8"),
            Encoding("UTF-8 BOM", "UTF-8", "UTF-8", "efbbbf"),
            Encoding("UTF-8 no declaration", "UTF-8", null),
            Encoding("UTF-16LE", "UTF-16LE", "UTF-16LE", "fffe"),
            Encoding("UTF-16BE", "UTF-16BE", "UTF-16BE", "feff"),
            Encoding("UTF-16LE no BOM", "UTF-16LE", "UTF-16LE"),
            Encoding("UTF-16BE no BOM", "UTF-16BE", "UTF-16BE"),
            Encoding("generic UTF-16 LE", "UTF-16LE", "UTF-16", "fffe"),
            Encoding("generic UTF-16 BE", "UTF-16BE", "UTF-16", "feff"),
            Encoding("UTF-16LE no declaration", "UTF-16LE", null, "fffe"),
            Encoding("UTF-16BE no declaration", "UTF-16BE", null, "feff"),
            Encoding("GB18030 declaration", "GB18030", "GB18030"),
            Encoding("Latin-1 and numeric refs", "ISO-8859-1", "ISO-8859-1", escapeNonLatin = true),
            Encoding("Windows-1252 and numeric refs", "windows-1252", "windows-1252", escapeNonLatin = true),
        )
        for (encoding in encodings) assertEquals(expected,
            CashStatementDocumentReader.readPlatformDocument(reencode(source, encoding), "wechat"), encoding.label)
    }

    @Test fun missingContradictoryAndUnsupportedEncodingDeclarationsKeepTheirTypedFailure() {
        val source = workbook()
        val encodings = listOf(
            Encoding("UTF-16LE without BOM or declaration", "UTF-16LE", null),
            Encoding("UTF-16BE without BOM or declaration", "UTF-16BE", null),
            Encoding("GB18030 without declaration", "GB18030", null),
            Encoding("GB18030 mislabeled UTF-8", "GB18030", "UTF-8"),
            Encoding("UTF-8 mislabeled GB18030", "UTF-8", "GB18030"),
            Encoding("LE mislabeled BE", "UTF-16LE", "UTF-16BE", "fffe"),
            Encoding("BE mislabeled LE", "UTF-16BE", "UTF-16LE", "feff"),
            Encoding("UTF-32LE", "UTF-32LE", "UTF-32LE", "fffe0000"),
            Encoding("UTF-32BE", "UTF-32BE", "UTF-32BE", "0000feff"),
            Encoding("UTF-32LE no BOM", "UTF-32LE", "UTF-32LE"),
            Encoding("UTF-32BE no BOM", "UTF-32BE", "UTF-32BE"),
            Encoding("UTF-32LE no BOM or declaration", "UTF-32LE", null),
            Encoding("UTF-32BE no BOM or declaration", "UTF-32BE", null),
            Encoding("unknown declaration", "UTF-8", "not-a-charset"),
        )
        for (encoding in encodings) assertEquals("statement_unreadable", assertFailsWith<StatementDocumentFailure>(encoding.label) {
            CashStatementDocumentReader.readPlatformDocument(reencode(source, encoding), "wechat")
        }.code, encoding.label)
    }
}
