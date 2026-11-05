package com.finance.tracker.backend

import com.finance.tracker.business.domain.contains as sharedCoverageContains
import com.finance.tracker.business.domain.comparable as sharedCoverageComparable
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime

internal typealias StatementDocumentFailure = com.finance.tracker.business.domain.StatementDocumentFailure

/** 加密 PDF 的密码失败；后续导入上下文据此区分必填与错误，不并入通用解析失败。 */
internal class StatementPdfPasswordFailure(val required: Boolean) : RuntimeException("pdf_password")

internal typealias RawCashRow = com.finance.tracker.business.contracts.RawCashRow

internal typealias CashSourceCoverage = com.finance.tracker.business.contracts.CashSourceCoverage

internal fun CashSourceCoverage.contains(value: Any?): Boolean =
    this.sharedCoverageContains(sharedCoverageTime(value))

internal fun CashSourceCoverage.comparable(other: CashSourceCoverage, value: Any?): Boolean =
    this.sharedCoverageComparable(other, sharedCoverageTime(value))

private fun sharedCoverageTime(value: Any?): Any? {
    val instant = when (value) {
        is Instant -> value
        is OffsetDateTime -> value.toInstant()
        is ZonedDateTime -> value.toInstant()
        else -> return value
    }
    return kotlin.time.Instant.fromEpochSeconds(instant.epochSecond, instant.nano.toLong())
}

internal fun normalizeStatementTimestamp(value: String, source: String): String =
    com.finance.tracker.business.domain.normalizeStatementTimestamp(value, source)

internal typealias CashStatementDocument = com.finance.tracker.business.contracts.CashStatementDocument

/** 按账单字节和来源提示选择具体格式读取器；只返回原始文档，业务归一仍在后续切片。 */
internal object CashStatementDocuments {
    private val PDF_SOURCES = setOf("icbc", "icbc_credit", "icbc_debit")
    private val BANK_SOURCES = setOf("ccb_debit", "icbc_asia")

    fun readDocument(content: ByteArray, source: String? = null, password: String? = null): CashStatementDocument {
        if (content.isEmpty()) throw StatementDocumentFailure("statement_input_invalid")
        val normalized = source?.replace('-', '_')
        if (content.startsWithPdf() || normalized in PDF_SOURCES)
            return IcbcPdfDocumentReader.readIcbcDocument(content, normalized, password)
        if (normalized in BANK_SOURCES || normalized == null && (content.isOle() || content.isUtf16()))
            return BankStatementDocumentReader.readBankDocument(content, normalized)
        return CashStatementDocumentReader.readPlatformDocument(content, normalized)
    }

    private fun ByteArray.startsWithPdf(): Boolean =
        size >= 5 && this[0] == '%'.code.toByte() && this[1] == 'P'.code.toByte() && this[2] == 'D'.code.toByte() &&
            this[3] == 'F'.code.toByte() && this[4] == '-'.code.toByte()

    private fun ByteArray.isOle(): Boolean = size >= 8 && listOf(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)
        .withIndex().all { (index, value) -> this[index] == value.toByte() }

    private fun ByteArray.isUtf16(): Boolean = size >= 2 &&
        ((this[0] == 0xFF.toByte() && this[1] == 0xFE.toByte()) || (this[0] == 0xFE.toByte() && this[1] == 0xFF.toByte()))
}

/** 平台 CSV/TSV/工作簿账单的一次读取。 */
internal object CashStatementDocumentReader {
    private const val MAX_INPUT_BYTES = 100 * 1024 * 1024
    private const val MAX_UNCOMPRESSED_BYTES = 100 * 1024 * 1024

    fun readPlatformDocument(content: ByteArray, source: String? = null): CashStatementDocument {
        if (source != null && !com.finance.tracker.business.domain.CashPlatformStatementTextReader.supportsSource(source)) fail("statement_source_unsupported")
        if (content.isEmpty() || content.size > MAX_INPUT_BYTES) fail("statement_input_invalid")
        if (content.size >= 2 && content[0] == 'P'.code.toByte() && content[1] == 'K'.code.toByte()) {
            preflightWorkbook(content)
            return readWorkbook(content, source ?: fail("statement_unreadable"))
        }
        val text = decode(content) ?: fail("statement_encoding_unsupported")
        return com.finance.tracker.business.domain.CashPlatformStatementTextReader.readCsv(text, source)
    }

    private fun fail(code: String): Nothing = throw StatementDocumentFailure(code)

    private fun readWorkbook(content: ByteArray, source: String): CashStatementDocument {
        try {
            org.apache.poi.ss.usermodel.WorkbookFactory.create(ByteArrayInputStream(content)).use { workbook ->
                val index = workbook.activeSheetIndex
                if (index !in 0 until workbook.numberOfSheets) fail("statement_header_invalid")
                val sheet = workbook.getSheetAt(index)
                val rows = mutableListOf<List<String>>()
                for (rowIndex in sheet.firstRowNum..sheet.lastRowNum) {
                    val row = sheet.getRow(rowIndex)
                    if (row == null) { rows.add(emptyList()); continue }
                    val cells = mutableListOf<String>()
                    val width = maxOf(0, row.lastCellNum.toInt())
                    for (column in 0 until width) {
                        val cell = row.getCell(column, org.apache.poi.ss.usermodel.Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)
                        cells += cellText(cell) ?: ""
                    }
                    rows += cells
                }
                return com.finance.tracker.business.domain.CashPlatformStatementTextReader.collectRows(rows, source, "sheet:$index", padTrailing = true)
            }
        } catch (failure: StatementDocumentFailure) {
            throw failure
        } catch (_: Exception) {
            fail("statement_unreadable")
        }
    }

    private fun cellText(cell: org.apache.poi.ss.usermodel.Cell?): String? {
        if (cell == null) return null
        return when (cell.cellType) {
            org.apache.poi.ss.usermodel.CellType.BLANK -> ""
            org.apache.poi.ss.usermodel.CellType.STRING -> cell.stringCellValue
            org.apache.poi.ss.usermodel.CellType.BOOLEAN -> if (cell.booleanCellValue) "TRUE" else "FALSE"
            org.apache.poi.ss.usermodel.CellType.FORMULA -> "=" + cell.cellFormula
            org.apache.poi.ss.usermodel.CellType.ERROR -> org.apache.poi.ss.usermodel.FormulaError.forInt(cell.errorCellValue).string
            org.apache.poi.ss.usermodel.CellType.NUMERIC ->
                if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell))
                    cell.localDateTimeCellValue.toString().replace('T', ' ')
                else pythonNumberText(cell.numericCellValue)
            else -> null
        }
    }

    private fun pythonNumberText(value: Double): String {
        if (value.isFinite() && value == Math.rint(value) && Math.abs(value) < 1e16)
            return value.toLong().toString()
        return value.toString()
    }

    /** 与 Python 预检相同的解压预算、成员类型与工作表坐标边界；不信任 workbook 维度声明。 */
    private fun preflightWorkbook(content: ByteArray) {
        val names = mutableSetOf<String>()
        var uncompressed = 0L
        var members = 0
        ZipInputStream(ByteArrayInputStream(content)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                members++
                if (members > 1000 || !names.add(entry.name)) fail("statement_rows_too_large")
                if (entry.isDirectory) {
                    if (entry.size > 0) fail("statement_unreadable")
                    continue
                }
                if (!entry.name.endsWith(".xml") && !entry.name.endsWith(".rels")) fail("statement_unreadable")
                val bytes = zip.readNBytes(65536)
                var total = bytes.size.toLong()
                if (bytes.size == 65536) {
                    val rest = zip.readBytes()
                    total += rest.size
                }
                uncompressed += total
                if (uncompressed > MAX_UNCOMPRESSED_BYTES) fail("statement_rows_too_large")
                if (entry.name.endsWith(".xml") && entry.name.contains("xl/worksheets/")) validateWorksheetXml(bytes)
            }
        }
    }

    private fun validateWorksheetXml(first: ByteArray) {
        val factory = XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
        }
        try {
            val reader = factory.createXMLStreamReader(ByteArrayInputStream(first))
            val guard = com.finance.tracker.business.domain.CashWorksheetXmlGuard()
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> guard.start(reader.localName) { attribute(reader, it) }
                    XMLStreamConstants.END_ELEMENT -> guard.end()
                }
            }
            reader.close()
        } catch (failure: StatementDocumentFailure) { throw failure }
        catch (_: Exception) { fail("statement_unreadable") }
    }

    private fun attribute(reader: javax.xml.stream.XMLStreamReader, name: String): String? {
        for (index in 0 until reader.attributeCount) if (reader.getAttributeLocalName(index) == name)
            return reader.getAttributeValue(index)
        return null
    }

    private fun decode(content: ByteArray): String? =
        com.finance.tracker.business.domain.decodeCashStatementText(content)

    internal fun parseDelimited(text: String, delimiter: Char, strict: Boolean = false): List<List<String>> =
        com.finance.tracker.business.domain.parseCashStatementDelimited(text, delimiter, strict)
}
