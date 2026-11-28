package com.finance.tracker.data

import com.finance.tracker.business.domain.StatementDocumentFailure
import com.finance.tracker.business.domain.parseCashStatementDelimited
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

private const val CSV_LIMIT = 2 * 1024 * 1024
private val exactDecimal = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")
private val formulaPrefixes = setOf('=', '+', '-', '@', '＝', '＋', '－', '＠')

internal fun stringifyLedgerAssistantCsv(rows: JsonArray): String {
    val result = buildString {
        for (row in rows) {
            val cells = row as? JsonArray ?: throw LedgerAssistantScriptFailure("invalid_csv_rows")
            if (cells.isEmpty()) throw LedgerAssistantScriptFailure("invalid_csv_rows")
            cells.forEachIndexed { index, cell ->
                if (index > 0) append(',')
                val value = when (cell) {
                    JsonNull -> ""
                    is JsonPrimitive -> cell.content
                    else -> throw LedgerAssistantScriptFailure("invalid_csv_rows")
                }
                if (value.length > CSV_LIMIT - length) throw LedgerAssistantScriptFailure("file_too_large")
                val significant = value.trimStart()
                val needsPrefix = (significant.firstOrNull() in formulaPrefixes && !exactDecimal.matches(value)) ||
                    value.firstOrNull() in setOf('\t', '\r', '\n')
                append('"')
                if (needsPrefix) append('\'')
                for (character in value) {
                    if (character == '"') append('"')
                    append(character)
                }
                append('"')
                if (length > CSV_LIMIT) throw LedgerAssistantScriptFailure("file_too_large")
            }
            append("\r\n")
        }
    }
    if (result.encodeToByteArray().size > CSV_LIMIT) throw LedgerAssistantScriptFailure("file_too_large")
    return result
}

internal fun parseLedgerAssistantCsv(text: String): JsonArray {
    if (text.length > CSV_LIMIT || text.encodeToByteArray().size > CSV_LIMIT) {
        throw LedgerAssistantScriptFailure("file_too_large")
    }
    val records = try {
        parseCashStatementDelimited(text.removePrefix("\uFEFF"), ',', strict = true)
    } catch (_: StatementDocumentFailure) {
        throw LedgerAssistantScriptFailure("invalid_csv")
    }
    return JsonArray(records.map { row -> JsonArray(row.map(::JsonPrimitive)) })
}
