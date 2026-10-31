package com.finance.tracker.backend

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.zip.ZipInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.ZoneOffset
import kotlinx.serialization.json.*

internal class StatementDocumentFailure(val code: String) : RuntimeException(code)

/** 加密 PDF 的密码失败；后续导入上下文据此区分必填与错误，不并入通用解析失败。 */
internal class StatementPdfPasswordFailure(val required: Boolean) : RuntimeException("pdf_password")

internal data class RawCashRow(val position: Int, val payload: Map<String, String>?, val errorCode: String? = null,
    val documentPosition: String? = null)

internal data class CashSourceCoverage(
    val start: String?, val end: String?, val complete: Boolean, val evidence: String,
    val filters: Map<String, String>, val strategyVersion: String,
    val startInclusive: Boolean = true, val endInclusive: Boolean = false,
    val timePrecision: String = "second", val documentKey: String? = null, val pageTotal: Int? = null,
    val pageFingerprints: Map<String, String> = emptyMap(),
) {
    init {
        val hasEvidence = documentKey != null || pageTotal != null || pageFingerprints.isNotEmpty()
        if (hasEvidence) {
            val key = documentKey
            val total = pageTotal
            if (key == null || !Regex("[0-9a-f]{64}").matches(key) ||
                total == null || total !in 1..2000 ||
                pageFingerprints.isEmpty() || pageFingerprints.size > total ||
                pageFingerprints.any { (page, fingerprint) ->
                    !Regex("[1-9][0-9]{0,3}").matches(page) || page.toInt() > total ||
                        !Regex("[0-9a-f]{64}").matches(fingerprint)
                })
                throw IllegalArgumentException("invalid_source_document_evidence")
        }
    }

    fun comparable(other: CashSourceCoverage, value: Any?): Boolean =
        contains(value) && other.contains(value) && filters == other.filters &&
            timePrecision == other.timePrecision && strategyVersion == other.strategyVersion

    fun contains(value: Any?): Boolean {
        if (!complete || evidence.isEmpty() || start == null || end == null || value == null) return false
        if (!hasExplicitOffset(start) || !hasExplicitOffset(end) || !hasExplicitOffset(value)) return false
        return try {
            val startTime = pythonCashTimestamp(start)
            val endTime = pythonCashTimestamp(end)
            val whenTime = pythonCashTimestamp(value)
            startTime < endTime &&
                (if (startInclusive) startTime <= whenTime else startTime < whenTime) &&
                (if (endInclusive) whenTime <= endTime else whenTime < endTime)
        } catch (_: Exception) {
            false
        }
    }

    private fun hasExplicitOffset(value: Any?): Boolean = when (value) {
        is Instant, is OffsetDateTime, is ZonedDateTime -> true
        is String -> Regex("[T ].*?(?:Z|[+-]\\d{2}(?::?\\d{2})?(?::?\\d{2})?)$").containsMatchIn(value)
        else -> false
    }
}

private val statementSourceTimezones = mapOf("alipay" to "Asia/Shanghai", "wechat" to "Asia/Shanghai",
    "icbc_credit" to "Asia/Shanghai", "icbc_debit" to "Asia/Shanghai", "ccb_debit" to "Asia/Shanghai",
    "icbc_asia" to "Asia/Hong_Kong")

private val explicitStatementOffset = Regex("(?:Z|[+-]\\d{2}(?::?\\d{2})?)$")
private val basicStatementDate = Regex("\\d{8}")
private val basicStatementDateTime = Regex("(\\d{4})(\\d{2})(\\d{2})T(\\d{2})(\\d{2})(\\d{2})(.*)")
private val compactStatementOffset = Regex("([+-]\\d{2})(\\d{2})$")
private val hourOnlyStatementOffset = Regex("([+-]\\d{2})$")

/** 来源时间按渠道时区解释并输出 UTC；无 offset 的时间不使用本机时区。 */
internal fun normalizeStatementTimestamp(value: String, source: String): String {
    var text = value.trim { it.code in pythonWhitespace }.replace(',', '.')
    if (basicStatementDate.matches(text)) text = "${text.take(4)}-${text.substring(4, 6)}-${text.substring(6, 8)}"
    else basicStatementDateTime.matchEntire(text)?.let {
        text = "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}T" +
            "${it.groupValues[4]}:${it.groupValues[5]}:${it.groupValues[6]}${it.groupValues[7]}"
    }
    text = compactStatementOffset.replace(text) { "${it.groupValues[1]}:${it.groupValues[2]}" }
    text = hourOnlyStatementOffset.replace(text) { "${it.groupValues[1]}:00" }
    if (text.length > 10 && text[10] == ' ') text = text.substring(0, 10) + "T" + text.substring(11)
    val zone = ZoneId.of(statementSourceTimezones[source] ?: "UTC")
    val instant = try {
        try {
            val parsed = pythonCashTimestamp(text)
            if (explicitStatementOffset.containsMatchIn(text) || text.endsWith("Z")) parsed
            else LocalDateTime.ofInstant(parsed, ZoneOffset.UTC).atZone(zone).toInstant()
        } catch (_: InvalidCashValue) {
            val parsed = pythonCashTimestamp(text, allowNaive = true)
            LocalDateTime.ofInstant(parsed, ZoneOffset.UTC).atZone(zone).toInstant()
        }
    } catch (_: InvalidCashValue) {
        throw IllegalArgumentException("invalid timestamp")
    }
    return manualInstantText(instant)
}

internal data class CashStatementDocument(
    val sourceType: String, val tableKey: String, val parserVersion: String, val rows: List<RawCashRow>,
    val sourceAccountIdentifier: String?, val sourceAccountLabel: String?, val coverage: CashSourceCoverage,
    val declaredCount: Long?, val observedCount: Int, val sourceCurrency: String? = null,
)

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
    private val required = mapOf(
        "alipay" to setOf("交易时间", "收/支", "金额", "交易对方"),
        "wechat" to setOf("交易时间", "收/支", "金额(元)", "交易对方"),
    )
    private const val MAX_INPUT_BYTES = 100 * 1024 * 1024
    private const val MAX_UNCOMPRESSED_BYTES = 100 * 1024 * 1024
    private const val MAX_PARSED_BYTES = 25 * 1024 * 1024
    private const val MAX_ROWS = 100_000
    private const val MAX_PHYSICAL_ROWS = 200_000
    private const val MAX_COLUMNS = 128
    private const val DATE = "\\d{4}[-/]\\d{2}[-/]\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:Z|[+-]\\d{2}:\\d{2})?"

    fun readPlatformDocument(content: ByteArray, source: String? = null): CashStatementDocument {
        if (source != null && source !in required) fail("statement_source_unsupported")
        if (content.isEmpty() || content.size > MAX_INPUT_BYTES) fail("statement_input_invalid")
        if (content.size >= 2 && content[0] == 'P'.code.toByte() && content[1] == 'K'.code.toByte()) {
            preflightWorkbook(content)
            return readWorkbook(content, source ?: fail("statement_unreadable"))
        }
        val text = decode(content) ?: fail("statement_encoding_unsupported")
        val records = parseCsv(text)
        return collect(records, source, "csv:0", padTrailing = false)
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
                return collect(rows, source, "sheet:$index", padTrailing = true)
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
            var depth = 0; var rows = 0; var cells = 0; var currentRow = 0; var lastColumn = 0
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        depth++
                        if (depth > 128) fail("statement_unreadable")
                        when (reader.localName) {
                            "row" -> {
                                rows++
                                cells = 0; lastColumn = 0
                                val index = attribute(reader, "r") ?: (currentRow + 1).toString()
                                if (!index.all { it in '0'..'9' } || index.length > 7) fail("statement_rows_too_large")
                                val value = index.toInt()
                                if (value !in 1..MAX_PHYSICAL_ROWS || rows > MAX_PHYSICAL_ROWS || value <= currentRow)
                                    fail("statement_rows_too_large")
                                currentRow = value
                            }
                            "c" -> {
                                cells++
                                val coordinate = attribute(reader, "r")
                                var column = lastColumn + 1
                                if (coordinate != null) {
                                    val match = Regex("([A-Z]{1,3})([0-9]{1,7})").matchEntire(coordinate)
                                        ?: fail("statement_header_invalid")
                                    column = 0
                                    for (letter in match.groupValues[1]) column = column * 26 + (letter - 'A' + 1)
                                    val rowIndex = match.groupValues[2].toInt()
                                    if (column > MAX_COLUMNS) fail("statement_header_invalid")
                                    if (rowIndex !in 1..MAX_PHYSICAL_ROWS) fail("statement_rows_too_large")
                                    if (rowIndex != currentRow) fail("statement_unreadable")
                                }
                                if (currentRow == 0 || column <= lastColumn || cells > MAX_COLUMNS) fail("statement_unreadable")
                                lastColumn = column
                            }
                        }
                    }
                    XMLStreamConstants.END_ELEMENT -> depth--
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

    private fun decode(content: ByteArray): String? {
        for (charset in listOf(StandardCharsets.UTF_8, java.nio.charset.Charset.forName("GB18030"))) {
            val decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            try {
                var text = decoder.decode(ByteBuffer.wrap(content)).toString()
                if (text.startsWith('﻿')) text = text.substring(1)
                return text
            } catch (_: java.nio.charset.CharacterCodingException) { }
        }
        return null
    }

    /** Python csv.reader 语义：引号内换行属于同一记录，CRLF 规范为 LF，空行仍产生空记录。 */
    private fun parseCsv(text: String): List<List<String>> = parseDelimited(text, ',')

    internal fun parseDelimited(text: String, delimiter: Char, strict: Boolean = false): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var index = 0
        while (index < text.length) {
            val cells = mutableListOf<String>()
            val field = StringBuilder()
            var quoted = false
            var afterQuote = false
            var finished = false
            while (index < text.length && !finished) {
                val char = text[index]
                if (quoted) {
                    if (char == '"') {
                        if (index + 1 < text.length && text[index + 1] == '"') {
                            field.append('"'); index += 2; continue
                        }
                        quoted = false; afterQuote = true; index++; continue
                    }
                    field.append(char); index++; continue
                }
                if (strict && afterQuote && char != delimiter && char != '\n' && char != '\r')
                    throw StatementDocumentFailure("statement_unreadable")
                when (char) {
                    '"' -> {
                        if (strict && field.isNotEmpty()) field.append(char) else quoted = true
                        index++
                    }
                    delimiter -> { cells += field.toString(); field.clear(); afterQuote = false; index++ }
                    '\n' -> { cells += field.toString(); index++; finished = true }
                    '\r' -> {
                        if (index + 1 < text.length && text[index + 1] == '\n') {
                            cells += field.toString(); index += 2; finished = true
                        } else if (strict) { cells += field.toString(); index++; finished = true }
                        else { field.append(char); index++ }
                    }
                    else -> { field.append(char); index++ }
                }
            }
            if (strict && quoted) throw StatementDocumentFailure("statement_unreadable")
            if (!finished) cells += field.toString()
            records += cells
        }
        return records
    }

    private fun collect(records: List<List<String>>, requestedSource: String?, tableKey: String,
        padTrailing: Boolean): CashStatementDocument {
        var source = requestedSource
        var header: List<String>? = null
        val metadata = mutableListOf<String>()
        val rows = mutableListOf<RawCashRow>()
        var parsedBytes = 0L
        for ((position, raw) in records.withIndex()) {
            if (position + 1 > MAX_PHYSICAL_ROWS) fail("statement_rows_too_large")
            val cells = raw.map { it }.toMutableList()
            if (cells.isNotEmpty() && cells[0].startsWith('﻿')) cells[0] = cells[0].substring(1)
            if (header == null) {
                if (position + 1 > 50 || metadata.sumOf { it.length } > 65_536) fail("statement_header_not_found")
                val matching = required.filterValues { it.all(cells::contains) }.keys
                if (source == null && matching.size > 1) fail("statement_header_invalid")
                val detected = source ?: matching.firstOrNull()
                if (detected != null && detected in matching) {
                    if (cells.size > MAX_COLUMNS || cells.toSet().size != cells.size) fail("statement_header_invalid")
                    header = cells; source = detected
                } else {
                    val last = cells.indexOfLast { it.isNotEmpty() } + 1
                    metadata += cells.take(maxOf(last, 0)).joinToString(",")
                }
                continue
            }
            if (cells.all { it.isBlank() }) continue
            if (padTrailing && cells.size < header.size) repeat(header.size - cells.size) { cells += "" }
            if (rows.size >= MAX_ROWS) fail("statement_rows_too_large")
            parsedBytes += cells.sumOf { it.toByteArray(StandardCharsets.UTF_8).size.toLong() }
            if (parsedBytes > MAX_PARSED_BYTES) fail("statement_rows_too_large")
            rows += if (cells.size != header.size) RawCashRow(position + 1, null, "row_shape_invalid")
                else RawCashRow(position + 1, header.zip(cells).toMap())
        }
        if (header == null || source == null) fail("statement_header_not_found")
        val declarations = declarations(metadata, source, rows.size, rows.all { it.errorCode == null })
        return CashStatementDocument(source, tableKey, "platform-rows-v1", rows, declarations.first,
            declarations.second, declarations.fourth, declarations.third, rows.size)
    }

    private data class Declarations(val first: String?, val second: String?, val third: Long?,
        val fourth: CashSourceCoverage)

    private fun declarations(lines: List<String>, source: String, observed: Int, usable: Boolean): Declarations {
        val text = lines.joinToString("\n")
        val countValues = Regex("(?m)^\\s*共\\s*(\\d[\\d,]*)\\s*笔(?:记录|交易)?").findAll(text).map { it.groupValues[1] }.toList()
        if (countValues.any { it.length > 12 }) fail("statement_rows_too_large")
        val countSet = countValues.map { it.replace(",", "").toLongOrNull() ?: fail("statement_rows_too_large") }.toSet()
        val count: Long? = if (countSet.size == 1) countSet.first() else null
        val accountLabels = if (source == "alipay") listOf("支付宝账户", "支付宝账号", "账号") else listOf("微信号")
        var account = unanimous(labelValues(lines, accountLabels, inline = false)) ?: null
        val label = account ?: unanimous(labelValues(lines, listOf("微信昵称"), inline = false)) ?: null
        if (account != null && Regex("[*＊•●]").containsMatchIn(account)) account = null
        val filterLabels = if (source == "wechat") listOf("导出类型") else listOf("导出交易类型", "交易类型")
        val exportType = unanimous(labelValues(lines, filterLabels, inline = true).map {
            if (it in setOf("全部", "全部交易", "所有", "全部类型", "所有交易")) "all" else it.ifEmpty { null }
        }.filterNotNull())
        val dates = mutableListOf<String?>()
        for (labelPattern in listOf("起始时间", "终止时间")) {
            val normalized = labelValues(lines, listOf(labelPattern), inline = true).map { value ->
                if (value.matches(Regex(DATE))) try {
                    normalizeStatementTimestamp(value.replace('/', '-'), source)
                } catch (_: IllegalArgumentException) { null } else null
            }
            dates += unanimous(normalized)
        }
        val complete = usable && count != null && count == observed.toLong() && exportType != null &&
            dates[0] != null && dates[1] != null &&
            OffsetDateTime.parse(dates[0]).toInstant() < OffsetDateTime.parse(dates[1]).toInstant()
        val coverage = CashSourceCoverage(dates[0], dates[1], complete,
            if (complete) "platform-declared-range-filter-count-v1" else "",
            if (exportType != null) mapOf("export_type" to exportType) else emptyMap(),
            "platform-coverage-v1", startInclusive = true, endInclusive = true)
        return Declarations(account, label, count, coverage)
    }

    private fun labelValues(lines: List<String>, labels: List<String>, inline: Boolean): List<String> {
        val values = mutableListOf<String>()
        for (line in lines) for (label in labels) {
            val prefix = if (inline) "(?:^|(?<=[\\]】,]))\\s*" else "^\\s*"
            val regex = Regex(prefix + Regex.escape(label) + "\\s*[:：]\\s*(?:\\[([^\\]]*)\\]|【([^】]*)】|([^\\r\\n,]*))")
            for (match in regex.findAll(line)) {
                val value = match.groupValues.drop(1).first { it.isNotEmpty() }
                values += pythonTrim(value)
            }
        }
        return values
    }

    private fun unanimous(values: List<Any?>): String? {
        val distinct = values.toSet()
        return if (distinct.size == 1) distinct.first()?.toString() else null
    }

    private fun pythonTrim(value: String): String {
        var start = 0; var end = value.length
        while (start < end && value[start].code in pythonWhitespace) start++
        while (end > start && value[end - 1].code in pythonWhitespace) end--
        return value.substring(start, end)
    }
}
