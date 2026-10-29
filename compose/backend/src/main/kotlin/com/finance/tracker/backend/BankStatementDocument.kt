package com.finance.tracker.backend

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.WorkbookFactory

/** 建行借记卡 XLS 与工银亚洲 UTF-16 TSV 的一次读取。 */
internal object BankStatementDocumentReader {
    private val OLE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
    private val CCB_REQUIRED = setOf("序号", "交易日期", "交易金额", "账户余额")
    private val ASIA_HEADERS = listOf("序號", "交易時間", "", "起息日期", "業務類型", "摘要", "收入金額", "支出金額",
        "餘額", "對方賬號", "對方戶名", "憑證號", "匯率", "備註", "交易場所")
    private val CURRENCIES = mapOf("HKD" to "HKD", "港幣" to "HKD", "港币" to "HKD", "USD" to "USD", "美元" to "USD",
        "美金" to "USD", "CNY" to "CNY", "RMB" to "CNY", "人民幣" to "CNY", "人民币" to "CNY", "JPY" to "JPY",
        "日圓" to "JPY", "日元" to "JPY", "EUR" to "EUR", "歐元" to "EUR", "欧元" to "EUR")
    private const val MAX_ROWS = 100_000
    private const val MAX_PHYSICAL_ROWS = 200_000
    private const val MAX_COLUMNS = 128
    private const val MAX_PARSED_BYTES = 25 * 1024 * 1024

    fun readBankDocument(content: ByteArray, source: String? = null): CashStatementDocument {
        if (content.isEmpty()) fail("statement_input_invalid")
        val detected = when {
            content.size >= OLE.size && OLE.indices.all { content[it] == OLE[it] } -> "ccb_debit"
            content.size >= 2 && ((content[0] == 0xFF.toByte() && content[1] == 0xFE.toByte()) ||
                (content[0] == 0xFE.toByte() && content[1] == 0xFF.toByte())) -> "icbc_asia"
            else -> null
        }
        if (detected == null || source != null && source != detected) fail("statement_source_unsupported")
        return try {
            if (detected == "ccb_debit") readCcb(content) else readAsia(content)
        } catch (failure: StatementDocumentFailure) { throw failure }
        catch (_: Exception) { fail("statement_unreadable") }
    }

    private fun fail(code: String): Nothing = throw StatementDocumentFailure(code)

    private data class Collected(val metadata: List<List<String>>, val rows: List<RawCashRow>)

    private fun collect(records: List<List<String>>, source: String, padTrailing: Boolean): Collected {
        var header: List<String>? = null
        val metadata = mutableListOf<List<String>>()
        val rows = mutableListOf<RawCashRow>()
        var parsedBytes = 0L
        for ((index, raw) in records.withIndex()) {
            val position = index + 1
            if (position > MAX_PHYSICAL_ROWS) fail("statement_rows_too_large")
            val cells = raw.toMutableList()
            if (cells.size > MAX_COLUMNS) fail("statement_rows_too_large")
            parsedBytes += cells.sumOf { it.toByteArray(StandardCharsets.UTF_8).size.toLong() }
            if (parsedBytes > MAX_PARSED_BYTES) fail("statement_rows_too_large")
            if (header == null) {
                if (position > 50 || parsedBytes > 65_536) fail("statement_header_not_found")
                val isHeader = if (source == "ccb_debit") CCB_REQUIRED.all(cells::contains) else cells.take(1) == listOf("序號")
                if (isHeader) {
                    if (cells.toSet().size != cells.size || source == "ccb_debit" && cells.any { it.isEmpty() } ||
                        source == "icbc_asia" && cells != ASIA_HEADERS) fail("statement_header_invalid")
                    header = cells
                } else metadata += cells
                continue
            }
            if (cells.all { it.isBlank() }) continue
            if (rows.size >= MAX_ROWS) fail("statement_rows_too_large")
            if (padTrailing && cells.size < header.size) repeat(header.size - cells.size) { cells += "" }
            rows += if (cells.size == header.size) RawCashRow(position, header.zip(cells).toMap())
                else RawCashRow(position, null, "row_shape_invalid")
        }
        if (header == null) fail("statement_header_not_found")
        return Collected(metadata, rows)
    }

    private fun readCcb(content: ByteArray): CashStatementDocument {
        if (content.size % 512 != 0) fail("statement_unreadable")
        return WorkbookFactory.create(ByteArrayInputStream(content)).use { workbook ->
            if (workbook.numberOfSheets == 0) fail("statement_unreadable")
            val sheet = workbook.getSheetAt(0)
            if (sheet.lastRowNum + 1 > MAX_PHYSICAL_ROWS || sheet.lastRowNum >= 0 &&
                (0..sheet.lastRowNum).any { (sheet.getRow(it)?.lastCellNum?.toInt() ?: 0) > MAX_COLUMNS })
                fail("statement_rows_too_large")
            val records = (0..sheet.lastRowNum).map { rowIndex ->
                val row = sheet.getRow(rowIndex)
                if (row == null) emptyList() else (0 until maxOf(0, row.lastCellNum.toInt())).map { column ->
                    cellText(row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL))
                }
            }
            val collected = collect(records, "ccb_debit", padTrailing = true)
            val cells = collected.metadata.flatten()
            val titles = cells.map { it.replace(Regex("\\s+"), "") }.filter { it.matches(Regex("中国建设银行.*交易明细")) }
            if (titles.isEmpty()) fail("statement_source_unsupported")
            fun declared(label: String): List<String> = cells.flatMap { cell ->
                Regex("(?:^|\\s)" + Regex.escape(label) + "\\s*[:：]\\s*(\\S*)").findAll(cell).map { it.groupValues[1] }.toList()
            }
            val identity = identity(declared("卡号/账号"))
            val title = oneString(titles)
            val coverage = coverage("ccb_debit", listOf(declared("起始日期"), declared("结束日期")), collected.rows,
                identity.first, title == "中国建设银行个人活期账户全部交易明细")
            CashStatementDocument("ccb_debit", "sheet:0", "bank-table-rows-v1", collected.rows, identity.first,
                identity.second, coverage, null, collected.rows.size)
        }
    }

    private fun readAsia(content: ByteArray): CashStatementDocument {
        val text = try { String(content, StandardCharsets.UTF_16) } catch (_: Exception) { fail("statement_encoding_unsupported") }
        val records = text.split(Regex("\\r?\\n")).map { it.split('\t') }
        val collected = collect(records, "icbc_asia", padTrailing = false)
        val declarations = linkedMapOf<String, MutableList<List<String>>>()
        for (row in collected.metadata) {
            if (row.isEmpty()) continue
            val label = row[0].trim().trimEnd(':', '：')
            declarations.getOrPut(label) { mutableListOf() } += row.drop(1).filter { it.isNotBlank() }.map { it.trim() }
        }
        fun scalar(vararg labels: String): List<String> = labels.flatMap { label ->
            declarations[label].orEmpty().map { it.joinToString(" ") }
        }
        if (oneString(scalar("銀行名稱", "银行名称")) !in setOf("工銀亞洲", "工银亚洲")) fail("statement_source_unsupported")
        var ownerValues = scalar("銀行賬號", "银行账号")
        if (ownerValues.none { it.isNotEmpty() }) ownerValues = scalar("下掛賬戶", "下挂账户")
        val identity = identity(ownerValues)
        val currency = oneString(scalar("幣種", "币种").mapNotNull { CURRENCIES[it.uppercase()] })
        val counts = scalar("總筆數", "总笔数", "交易筆數", "交易笔数")
        val count = oneLong(counts.map { if (it.matches(Regex("[0-9]{1,6}"))) it.toLong() else null })
        val ranges = declarations["日期"].orEmpty()
        val validRanges = ranges.filter { it.size == 3 && it[1] in setOf("-", "至", "到") }
        val dates = listOf(validRanges.map { it[0] }, validRanges.map { it[2] })
        val coverage = coverage("icbc_asia", dates, collected.rows, identity.first,
            currency != null && count != null && count == collected.rows.size.toLong() && ranges.isNotEmpty() &&
                validRanges.size == ranges.size, currency)
        return CashStatementDocument("icbc_asia", "csv:0", "bank-table-rows-v1", collected.rows, identity.first,
            identity.second, coverage, count, collected.rows.size, currency)
    }

    private fun identity(values: List<String>): Pair<String?, String?> {
        val value = oneString(values.map { it.trim() })
        val label = value?.takeIf { it.matches(Regex("[0-9*＊]{4,128}")) }
        val owner = value?.takeIf { it.matches(Regex("[0-9]{8,128}")) }
        return owner to label
    }

    private fun coverage(source: String, dates: List<List<String>>, rows: List<RawCashRow>, owner: String?,
        complete: Boolean, currency: String? = null): CashSourceCoverage {
        val startText = oneString(dates.firstOrNull().orEmpty())?.let(::bankDate)
        val endText = oneString(dates.getOrNull(1) ?: emptyList())?.let(::bankDate)?.plusDays(1)
        val sequenceKey = if (source == "ccb_debit") "序号" else "序號"
        val verified = complete && owner != null && startText != null && endText != null && startText.isBefore(endText) &&
            rows.all { row -> row.errorCode == null && row.payload?.get(sequenceKey)?.trim() == (rows.indexOf(row) + 1).toString() }
        val filters = linkedMapOf("export_type" to "all")
        if (source == "icbc_asia" && currency != null) filters["currency"] = currency
        return CashSourceCoverage(
            startText?.let { manualInstantText(it.atZone(ZoneId.of("Asia/Shanghai")).toInstant()) },
            endText?.let { manualInstantText(it.atZone(ZoneId.of("Asia/Shanghai")).toInstant()) },
            verified, if (verified) "bank-declared-range-sequence-v1" else "", filters,
            "bank-table-coverage-v1",
        )
    }

    private fun bankDate(value: String): LocalDateTime? {
        val text = value.trim()
        return try {
            when {
                text.matches(Regex("[0-9]{8}")) -> LocalDateTime.of(text.substring(0, 4).toInt(),
                    text.substring(4, 6).toInt(), text.substring(6, 8).toInt(), 0, 0)
                text.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) ->
                    LocalDateTime.of(text.substring(0, 4).toInt(), text.substring(5, 7).toInt(),
                        text.substring(8, 10).toInt(), 0, 0)
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun oneString(values: List<String?>): String? {
        val distinct = values.toSet()
        return if (distinct.size == 1) distinct.first() else null
    }

    private fun oneLong(values: List<Long?>): Long? {
        val distinct = values.toSet()
        return if (distinct.size == 1) distinct.first() else null
    }

    private fun cellText(cell: Cell?): String {
        if (cell == null) return ""
        return when (cell.cellType) {
            CellType.BLANK -> ""
            CellType.STRING -> cell.stringCellValue
            CellType.BOOLEAN -> if (cell.booleanCellValue) "True" else "False"
            CellType.NUMERIC ->
                if (DateUtil.isCellDateFormatted(cell))
                    cell.localDateTimeCellValue.format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"))
                else if (cell.numericCellValue.isFinite() && cell.numericCellValue == Math.rint(cell.numericCellValue))
                    cell.numericCellValue.toLong().toString()
                else cell.numericCellValue.toString()
            CellType.FORMULA -> "=" + cell.cellFormula
            else -> ""
        }
    }
}
