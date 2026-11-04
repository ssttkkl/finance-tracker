package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashSourceCoverage
import com.finance.tracker.business.contracts.CashStatementDocument
import com.finance.tracker.business.contracts.RawCashRow
import kotlin.time.Instant

/** Bank-native table declarations and coverage after the workbook/text decoding boundary. */
object CashBankStatementTextReader {
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
            parsedBytes += cells.sumOf { platformStatementUtf8Size(it) }
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
            if (cells.all { platformStatementCellBlank(it) }) continue
            if (rows.size >= MAX_ROWS) fail("statement_rows_too_large")
            if (padTrailing && cells.size < header.size) repeat(header.size - cells.size) { cells += "" }
            rows += if (cells.size == header.size) RawCashRow(position, header.zip(cells).toMap())
                else RawCashRow(position, null, "row_shape_invalid")
        }
        if (header == null) fail("statement_header_not_found")
        return Collected(metadata, rows)
    }

    fun readCcb(records: List<List<String>>): CashStatementDocument {
        val collected = collect(records, "ccb_debit", padTrailing = true)
        val cells = collected.metadata.flatten()
        val titles = cells.map { it.replace(platformStatementPattern("\\s+"), "") }.filter { it.matches(platformStatementPattern("中国建设银行.*交易明细")) }
        if (titles.isEmpty()) fail("statement_source_unsupported")
        fun declared(label: String): List<String> = cells.flatMap { cell ->
            platformStatementPattern("(?:^|\\s)" + Regex.escape(label) + "\\s*[:：]\\s*(\\S*)").findAll(cell).map { it.groupValues[1] }.toList()
        }
        val identity = identity(declared("卡号/账号"))
        val title = oneString(titles)
        val coverage = coverage("ccb_debit", listOf(declared("起始日期"), declared("结束日期")), collected.rows,
            identity.first, title == "中国建设银行个人活期账户全部交易明细")
        return CashStatementDocument("ccb_debit", "sheet:0", "bank-table-rows-v1", collected.rows, identity.first,
            identity.second, coverage, null, collected.rows.size)
    }

    fun readAsia(text: String): CashStatementDocument {
        val records = parseCashStatementDelimited(text, '\t', strict = true)
        val collected = collect(records, "icbc_asia", padTrailing = false)
        val declarations = linkedMapOf<String, MutableList<List<String>>>()
        for (row in collected.metadata) {
            if (row.isEmpty()) continue
            val label = row[0].bankTrim().trimEnd(':', '：')
            declarations.getOrPut(label) { mutableListOf() } += row.drop(1).filter { !platformStatementCellBlank(it) }.map { it.bankTrim() }
        }
        fun scalar(vararg labels: String): List<String> = labels.flatMap { label ->
            declarations[label].orEmpty().map { it.joinToString(" ") }
        }
        if (oneString(scalar("銀行名稱", "银行名称")) !in setOf("工銀亞洲", "工银亚洲")) fail("statement_source_unsupported")
        var ownerValues = scalar("銀行賬號", "银行账号")
        if (ownerValues.none { it.isNotEmpty() }) ownerValues = scalar("下掛賬戶", "下挂账户")
        val identity = identity(ownerValues)
        val currency = oneString(scalar("幣種", "币种").mapNotNull { CURRENCIES[pythonCashUpper(it)] })
        val counts = scalar("總筆數", "总笔数", "交易筆數", "交易笔数")
        val count = oneLong(counts.map { if (it.matches(platformStatementPattern("[0-9]{1,6}"))) it.toLong() else null })
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
        val value = oneString(values.map { it.bankTrim() })
        val label = value?.takeIf { it.matches(platformStatementPattern("[0-9*＊]{4,128}")) }
        val owner = value?.takeIf { it.matches(platformStatementPattern("[0-9]{8,128}")) }
        return owner to label
    }

    private fun coverage(source: String, dates: List<List<String>>, rows: List<RawCashRow>, owner: String?,
        complete: Boolean, currency: String? = null): CashSourceCoverage {
        val startText = oneString(dates.firstOrNull().orEmpty())?.let(::bankDate)
        val endText = oneString(dates.getOrNull(1) ?: emptyList())?.let(::bankDate)?.let { Instant.fromEpochSeconds(it.epochSeconds + 86400L) }
        val sequenceKey = if (source == "ccb_debit") "序号" else "序號"
        val verified = complete && owner != null && startText != null && endText != null && startText < endText &&
            rows.all { row -> row.errorCode == null && row.payload?.get(sequenceKey)?.bankTrim() == (rows.indexOf(row) + 1).toString() }
        val filters = linkedMapOf("export_type" to "all")
        if (source == "icbc_asia" && currency != null) filters["currency"] = currency
        return CashSourceCoverage(
            startText?.let { coverageUtcText(it) },
            endText?.let { coverageUtcText(it) },
            verified, if (verified) "bank-declared-range-sequence-v1" else "", filters,
            "bank-table-coverage-v1", startInclusive = true, endInclusive = false,
            timePrecision = if (source == "ccb_debit") "day" else "second",
        )
    }

    private fun bankDate(value: String): Instant? {
        val text = value.bankTrim()
        val date = when {
            text.matches(platformStatementPattern("[0-9]{8}")) -> "${text.take(4)}-${text.substring(4, 6)}-${text.substring(6, 8)}"
            text.matches(platformStatementPattern("[0-9]{4}-[0-9]{2}-[0-9]{2}")) -> text
            else -> return null
        }
        return try { Instant.parse(date + "T00:00:00Z") } catch (_: IllegalArgumentException) { null }
    }

    private fun oneString(values: List<String?>): String? {
        val distinct = values.toSet()
        return if (distinct.size == 1) distinct.first() else null
    }

    private fun oneLong(values: List<Long?>): Long? {
        val distinct = values.toSet()
        return if (distinct.size == 1) distinct.first() else null
    }

    private fun coverageUtcText(local: Instant): String {
        // The original bank coverage contract uses Shanghai for both bank formats.
        val second = local.epochSeconds - StatementSourceZoneData.offsetSeconds("wechat", local.epochSeconds)
        val clock = second.mod(86400L)
        return formatBankCoverageDay(second.floorDiv(86400L)) + "T" +
            (clock / 3600).toString().padStart(2, '0') + ":" +
            (clock / 60 % 60).toString().padStart(2, '0') + ":" +
            (clock % 60).toString().padStart(2, '0') + "+00:00"
    }
    private fun String.bankTrim(): String = trim { it.code != 0x85 && it.code in pythonWhitespace }
}

/** Existing BOM-gated ICBC Asia UTF-16 TSV path, with the original replacement decoder semantics. */
fun readIcbcAsiaStatementDocument(content: ByteArray, source: String? = null): CashStatementDocument {
    if (content.isEmpty()) throw StatementDocumentFailure("statement_input_invalid")
    if (source != null && source != "icbc_asia" || content.size < 2 ||
        !((content[0] == 0xff.toByte() && content[1] == 0xfe.toByte()) ||
            (content[0] == 0xfe.toByte() && content[1] == 0xff.toByte())))
        throw StatementDocumentFailure("statement_source_unsupported")
    return CashBankStatementTextReader.readAsia(decodeBankStatementUtf16(content))
}
