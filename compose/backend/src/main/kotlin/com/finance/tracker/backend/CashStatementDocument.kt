package com.finance.tracker.backend

import com.finance.tracker.business.domain.contains as sharedCoverageContains
import com.finance.tracker.business.domain.comparable as sharedCoverageComparable
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import java.nio.charset.CodingErrorAction
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
    fun readDocument(content: ByteArray, source: String? = null, password: String? = null): CashStatementDocument {
        if (content.isEmpty() || content.size > 100 * 1024 * 1024) throw StatementDocumentFailure("statement_input_invalid")
        val normalized = source?.replace('-', '_')
        if (normalized == null) {
            val detection = CashStatementDetection(content, password)
            val candidates = listOf("alipay", "wechat", "icbc_credit", "icbc_debit", "ccb_debit", "icbc_asia").map { kind ->
                CashStatementCandidate({ detection.matches(kind) }, { readDocument(content, kind, password) })
            }
            return readUniqueCashStatement(candidates)
        }
        return when (com.finance.tracker.business.domain.cashStatementFormat(content, normalized)) {
            com.finance.tracker.business.domain.CashStatementFormat.PDF -> IcbcPdfDocumentReader.readIcbcDocument(content, normalized, password)
            com.finance.tracker.business.domain.CashStatementFormat.XLS,
            com.finance.tracker.business.domain.CashStatementFormat.ICBC_ASIA -> BankStatementDocumentReader.readBankDocument(content, normalized)
            com.finance.tracker.business.domain.CashStatementFormat.XLSX,
            com.finance.tracker.business.domain.CashStatementFormat.CSV -> CashStatementDocumentReader.readPlatformDocument(content, normalized)
        }
    }
}

/** 平台 CSV/TSV/工作簿账单的一次读取。 */
internal object CashStatementDocumentReader {
    private const val MAX_INPUT_BYTES = com.finance.tracker.business.domain.CashPlatformStatementTextReader.MAX_INPUT_BYTES

    fun readPlatformDocument(content: ByteArray, source: String? = null): CashStatementDocument {
        val workbook = content.size >= 2 && content[0] == 'P'.code.toByte() && content[1] == 'K'.code.toByte()
        if (!workbook) return com.finance.tracker.business.domain.CashPlatformStatementTextReader.readCsvBytes(content, source)
        if (source != null && !com.finance.tracker.business.domain.CashPlatformStatementTextReader.supportsSource(source)) fail("statement_source_unsupported")
        if (content.size > MAX_INPUT_BYTES) fail("statement_input_invalid")
        preflightWorkbook(content)
        return readWorkbook(content, source)
    }

    private fun fail(code: String): Nothing = throw StatementDocumentFailure(code)

    internal fun matchingSources(cells: Collection<String>): Set<String> = com.finance.tracker.business.domain.CashPlatformStatementTextReader.matchingSources(cells)

    internal fun probeSources(content: ByteArray): Set<String> {
        if (content.size >= 2 && content[0] == 'P'.code.toByte() && content[1] == 'K'.code.toByte())
            return CashWorkbookHeaderProbe.sources(content, ::matchingSources)
        val prefix = content.copyOf(minOf(content.size, 65536))
        val text = decodePrefix(prefix, content.size <= prefix.size) ?: return emptySet()
        return parseDelimited(text, ',').take(50).flatMap(::matchingSources).toSet()
    }

    private fun decodePrefix(content: ByteArray, complete: Boolean): String? {
        for (charset in listOf(StandardCharsets.UTF_8, java.nio.charset.Charset.forName("GB18030"))) {
            try {
                val decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                val output = java.nio.CharBuffer.allocate(content.size)
                decoder.decode(ByteBuffer.wrap(content), output, complete).also { if (it.isError) it.throwException() }
                output.flip()
                return output.toString().removePrefix("\uFEFF")
            } catch (_: java.nio.charset.CharacterCodingException) { }
        }
        return null
    }

    private fun readWorkbook(content: ByteArray, source: String?): CashStatementDocument {
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
                if (cashWorkbookDateFormatted(cell))
                    com.finance.tracker.business.domain.cashPlatformWorkbookDateText(cell.numericCellValue, cashWorkbookUses1904(cell))
                else pythonNumberText(cell.numericCellValue)
            else -> null
        }
    }

    private fun pythonNumberText(value: Double): String {
        return com.finance.tracker.business.domain.cashPlatformWorkbookNumberText(value)
    }

    /** 与 Python 预检相同的解压预算、成员类型与工作表坐标边界；不信任 workbook 维度声明。 */
    private fun preflightWorkbook(content: ByteArray) {
        com.finance.tracker.business.domain.preflightCashWorkbookXmlArchive(content, ::validateWorkbookXml)
    }

    private fun validateWorkbookXml(content: ByteArray) {
        val factory = XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
            xmlResolver = javax.xml.stream.XMLResolver { _, _, _, _ -> throw XMLStreamException("external_entities_disabled") }
        }
        try {
            val reader = factory.createXMLStreamReader(ByteArrayInputStream(content))
            var depth = 0
            var worksheet = false
            val guard = com.finance.tracker.business.domain.CashWorksheetXmlGuard()
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        depth++
                        if (depth > 128) fail("statement_unreadable")
                        if (depth == 1) worksheet = reader.localName == "worksheet"
                        if (worksheet) guard.start(reader.localName) { attribute(reader, it) }
                    }
                    XMLStreamConstants.END_ELEMENT -> {
                        if (worksheet) guard.end()
                        depth--
                    }
                    XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE -> fail("statement_unreadable")
                }
            }
            reader.close()
        } catch (failure: StatementDocumentFailure) { throw failure }
        catch (failure: XMLStreamException) {
            // StAX may wrap our measured stream's budget failure.
            var cause: Throwable? = failure
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            while (cause != null && seen.add(cause)) {
                if (cause is StatementDocumentFailure) throw cause
                cause = cause.cause
            }
            fail("statement_unreadable")
        }
    }

    private fun attribute(reader: javax.xml.stream.XMLStreamReader, name: String): String? {
        for (index in 0 until reader.attributeCount) if (reader.getAttributeLocalName(index) == name)
            return reader.getAttributeValue(index)
        return null
    }

    internal fun parseDelimited(text: String, delimiter: Char, strict: Boolean = false): List<List<String>> =
        com.finance.tracker.business.domain.parseCashStatementDelimited(text, delimiter, strict)
}
