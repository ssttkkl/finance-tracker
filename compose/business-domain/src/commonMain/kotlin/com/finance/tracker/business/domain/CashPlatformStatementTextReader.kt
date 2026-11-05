package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashSourceCoverage
import com.finance.tracker.business.contracts.CashStatementDocument
import com.finance.tracker.business.contracts.RawCashRow
import kotlin.time.Instant

/** Parse decoded records, preserving quoted field text and the existing strict/non-strict CR behavior. */
fun parseCashStatementDelimited(text: String, delimiter: Char, strict: Boolean = false): List<List<String>> {
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

/** Source-native headers, row shapes, declarations and budgets after decoding file bytes/cells. */
object CashPlatformStatementTextReader {
    private val required = mapOf(
        "alipay" to setOf("交易时间", "收/支", "金额", "交易对方", "交易订单号"),
        "wechat" to setOf("交易时间", "收/支", "金额(元)", "交易对方", "交易单号"),
    )
    private val alipayHeaderMarkers = setOf("交易分类", "商品说明", "收/付款方式", "商家订单号")
    const val MAX_INPUT_BYTES = 100 * 1024 * 1024
    private const val MAX_PARSED_BYTES = 25 * 1024 * 1024
    private const val MAX_ROWS = 100_000
    private const val MAX_PHYSICAL_ROWS = 200_000
    private const val MAX_COLUMNS = 128
    private const val DATE = "\\d{4}[-/]\\d{2}[-/]\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:Z|[+-]\\d{2}:\\d{2})?"


    fun supportsSource(source: String): Boolean = source in required
    fun readCsvBytes(content: ByteArray, source: String? = null): CashStatementDocument {
        if (source != null && !supportsSource(source)) fail("statement_source_unsupported")
        if (content.isEmpty() || content.size > MAX_INPUT_BYTES) fail("statement_input_invalid")
        val text = decodeCashStatementText(content) ?: fail("statement_encoding_unsupported")
        return readCsv(text, source)
    }
    fun readCsv(text: String, source: String? = null): CashStatementDocument {
        if (source != null && !supportsSource(source)) fail("statement_source_unsupported")
        return collectRows(parseCashStatementDelimited(text, ','), source, "csv:0", padTrailing = false)
    }
    private fun fail(code: String): Nothing = throw StatementDocumentFailure(code)

    fun collectRows(records: List<List<String>>, requestedSource: String?, tableKey: String,
        padTrailing: Boolean): CashStatementDocument {
        if (requestedSource != null && !supportsSource(requestedSource)) fail("statement_source_unsupported")
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
                if (source == null && "alipay" !in matching && cells.count { it in alipayHeaderMarkers } >= 2)
                    fail("statement_header_invalid")
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
            if (cells.all { platformStatementCellBlank(it) }) continue
            if (padTrailing && cells.size < header.size) repeat(header.size - cells.size) { cells += "" }
            if (rows.size >= MAX_ROWS) fail("statement_rows_too_large")
            parsedBytes += cells.sumOf { platformStatementUtf8Size(it) }
            if (parsedBytes > MAX_PARSED_BYTES) fail("statement_rows_too_large")
            rows += if (cells.size != header.size) RawCashRow(position + 1, null, "row_shape_invalid")
                else RawCashRow(position + 1, header.zip(cells).toMap())
        }
        if (header == null || source == null) fail("statement_header_not_found")
        val declarations = declarations(metadata, source, rows.size, rows.all { it.errorCode == null })
        return CashStatementDocument(source, tableKey, "platform-rows-v1", rows, declarations.first,
            declarations.second, declarations.fourth, declarations.third, rows.size, sourceNickname = declarations.fifth)
    }

    private data class Declarations(val first: String?, val second: String?, val third: Long?,
        val fourth: CashSourceCoverage, val fifth: String? = null)

    private fun declarations(lines: List<String>, source: String, observed: Int, usable: Boolean): Declarations {
        val text = lines.joinToString("\n")
        val countValues = platformStatementPattern("(?m)^\\s*共\\s*(\\d[\\d,]*)\\s*笔(?:记录|交易)?").findAll(text).map { it.groupValues[1] }.toList()
        if (countValues.any { it.length > 12 }) fail("statement_rows_too_large")
        val countSet = countValues.map { it.replace(",", "").toLongOrNull() ?: fail("statement_rows_too_large") }.toSet()
        val count: Long? = if (countSet.size == 1) countSet.first() else null
        val accountLabels = if (source == "alipay") listOf("支付宝账户", "支付宝账号", "账号") else listOf("微信号")
        var account = unanimous(labelValues(lines, accountLabels, inline = false)) ?: null
        val label = account ?: unanimous(labelValues(lines, listOf("微信昵称"), inline = false)) ?: null
        if (account != null && platformStatementPattern("[*＊•●]").containsMatchIn(account)) account = null
        val filterLabels = if (source == "wechat") listOf("导出类型") else listOf("导出交易类型", "交易类型")
        val exportType = unanimous(labelValues(lines, filterLabels, inline = true).map {
            if (it in setOf("全部", "全部交易", "所有", "全部类型", "所有交易")) "all" else it.ifEmpty { null }
        }.filterNotNull())
        val dates = mutableListOf<String?>()
        for (labelPattern in listOf("起始时间", "终止时间")) {
            val normalized = labelValues(lines, listOf(labelPattern), inline = true).map { value ->
                if (value.matches(platformStatementPattern(DATE))) try {
                    normalizeStatementTimestamp(value.replace('/', '-'), source)
                } catch (_: IllegalArgumentException) { null } else null
            }
            dates += unanimous(normalized)
        }
        val complete = usable && count != null && count == observed.toLong() && exportType != null &&
            dates[0] != null && dates[1] != null &&
            Instant.parse(dates[0]!!) < Instant.parse(dates[1]!!)
        val coverage = CashSourceCoverage(dates[0], dates[1], complete,
            if (complete) "platform-declared-range-filter-count-v1" else "",
            if (exportType != null) mapOf("export_type" to exportType) else emptyMap(),
            "platform-coverage-v1", startInclusive = true, endInclusive = true)
        val nickname = if (source == "wechat") unanimous(labelValues(lines, listOf("微信昵称"), inline = false)) else null
        return Declarations(account, label, count, coverage, nickname)
    }

    private fun labelValues(lines: List<String>, labels: List<String>, inline: Boolean): List<String> {
        val values = mutableListOf<String>()
        for (line in lines) for (label in labels) {
            val prefix = if (inline) "(?:^|(?<=[\\]】,]))\\s*" else "^\\s*"
            val regex = platformStatementPattern(prefix + Regex.escape(label) + "\\s*[:：]\\s*(?:\\[([^\\]]*)\\]|【([^】]*)】|([^\\r\\n,]*))")
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

/** Kotlin/JVM's original Char.isWhitespace/SpaceChar set, independent of JS's extra BOM whitespace. */
internal fun platformStatementCellBlank(text: String): Boolean = text.all { it.code != 0x85 && it.code in pythonWhitespace }

/** Only the collector's fixed patterns; JVM default digit/space and multiline-start semantics. */
internal fun platformStatementPattern(pattern: String): Regex {
    val lineStart = "(?:^|(?<=[\\n\\u0085\\u2028\\u2029])|(?<=\\r)(?!\\n))"
    val prefix = if (pattern.startsWith("(?m)^")) lineStart + pattern.removePrefix("(?m)^") else pattern
    // Expand the collector's digit class before standalone digits: JS rejects nested classes.
    return cashSourceEffectRegex(prefix.replace("[\\d,]", "[0-9,]")
        .replace("\\S", "[^\\u0020\\u0009-\\u000d]")
        .replace("\\s", "[\\u0020\\u0009-\\u000d]").replace("\\d", "[0-9]"), unixLines = false)
}

/** Count the original JVM replacement UTF-8 budget without allocating a second copy of large cells. */
internal fun platformStatementUtf8Size(text: String): Long {
    var result = 0L
    var index = 0
    while (index < text.length) {
        val char = text[index++]
        result += when {
            char.isHighSurrogate() && index < text.length && text[index].isLowSurrogate() -> { index++; 4 }
            char.isSurrogate() -> 1
            char.code < 0x80 -> 1
            char.code < 0x800 -> 2
            else -> 3
        }
    }
    return result
}
