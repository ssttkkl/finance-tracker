package com.finance.tracker.backend

import kotlinx.serialization.json.*

internal object InvestmentDocument {
    fun read(source: String, filename: String, bytes: ByteArray, password: String? = null): List<Map<String, JsonElement>> {
        val name = filename.substringAfterLast('/')
        val suffix = name.lastIndexOf('.').takeIf { it > 0 }?.let { pythonCashLower(name.substring(it)) }.orEmpty()
        fun text() = if (suffix in setOf(".txt", ".text")) decodeText(bytes).replace("\r\n", "\n").replace('\r', '\n')
            else PdfProcesses.client.extractText(bytes, password)
        return when (source) {
            "dfzq" -> {
                val lines = text().split(Regex("\r\n|[\n\r\u000b\u000c\u001c-\u001e\u0085\u2028\u2029]"))
                try { DfzqImporter.parse(lines) }
                catch (failure: IndexOutOfBoundsException) { throw IllegalArgumentException("list index out of range", failure) }
            }
            "usmart-hk", "usmart_hk" -> UsmartImporter.parse(text())
            "ibkr" -> IbkrImporter.parse(bytes).let { statement -> statement.transactions.map { row ->
                IbkrRow(JsonObject(row + mapOf("_ibkr_base_currency" to JsonPrimitive(statement.baseCurrency), "_ibkr_ending_cash" to JsonPrimitive(statement.endingCash))))
            } }
            "schwab" -> SchwabImporter.parse(bytes).let { statement -> statement.transactions.map { row ->
                SchwabRow(JsonObject(row + ("_schwab_ending_cash" to JsonPrimitive(statement.endingCash))))
            } }
            else -> throw IllegalArgumentException("不支持的数据源：$source")
        }
    }
    fun decodeText(bytes: ByteArray): String = buildString(bytes.size) {
        var i = 0
        fun byte(index: Int) = bytes[index].toInt() and 255
        while (i < bytes.size) {
            val first = byte(i)
            if (first < 128) { append(first.toChar()); i++; continue }
            val size = when (first) { in 0xc2..0xdf -> 2; in 0xe0..0xef -> 3; in 0xf0..0xf4 -> 4; else -> 0 }
            if (size == 0) { append('\uFFFD'); i++; continue }
            var consumed = 1
            var code = first and (127 shr size)
            while (consumed < size && i + consumed < bytes.size) {
                val next = byte(i + consumed)
                val range = if (consumed == 1) when (first) { 0xe0 -> 0xa0..0xbf; 0xed -> 0x80..0x9f; 0xf0 -> 0x90..0xbf; 0xf4 -> 0x80..0x8f; else -> 0x80..0xbf } else 0x80..0xbf
                if (next !in range) break
                code = (code shl 6) or (next and 63); consumed++
            }
            if (consumed == size) appendCodePoint(code) else append('\uFFFD')
            i += consumed
        }
    }
    fun sourceType(source: String): String = when (source) {
        "dfzq" -> "dfzq_pdf"; "ibkr" -> "ibkr_csv"; "schwab" -> "schwab_csv"; "usmart-hk", "usmart_hk" -> "usmart_hk_pdf"
        else -> throw IllegalArgumentException("不支持导入该投资数据源：$source")
    }
    fun identity(source: String, row: Map<String, JsonElement>): String = when (source) {
        "dfzq" -> DfzqImporter.identity(row); "ibkr" -> IbkrImporter.identity(row); "schwab" -> SchwabImporter.identity(row)
        "usmart-hk", "usmart_hk" -> UsmartImporter.identity(row); else -> throw IllegalArgumentException("不支持导入该投资数据源：$source")
    }
    fun event(source: String, row: Map<String, JsonElement>, account: String, currency: String): JsonObject = when (source) {
        "dfzq" -> DfzqImporter.event(row, account, currency); "ibkr" -> IbkrImporter.event(row, account, currency); "schwab" -> SchwabImporter.event(row, account, currency)
        "usmart-hk", "usmart_hk" -> UsmartImporter.event(row, account, currency); else -> throw IllegalArgumentException("不支持导入该投资数据源：$source")
    }
    fun currency(source: String, supplied: String?, rows: List<Map<String, JsonElement>>): String {
        if (source == "ibkr") {
            val base = rows.firstOrNull()?.get("_ibkr_base_currency")?.takeIf(::pythonTruthy)?.let(::pythonString)?.let(::valuationStrip).orEmpty()
            require(!supplied.isNullOrEmpty() || base.isNotEmpty()) { "导入 IBKR 账单时必须通过 --currency 指定币种，或确保 CSV 中存在 总结.基础货币" }
            return valuationUpper(supplied?.takeIf { it.isNotEmpty() } ?: base)
        }
        return valuationUpper(supplied?.takeIf { it.isNotEmpty() } ?: if (source in setOf("schwab", "usmart-hk", "usmart_hk")) "USD" else "CNY")
    }
    fun sourcePayload(source: String, row: Map<String, JsonElement>): JsonObject {
        val payload = row["_source_payload"]
        require(payload is JsonObject && payload.isNotEmpty()) { "投资导入器未提供完整来源行快照" }
        if (source in setOf("dfzq", "ibkr", "usmart-hk", "usmart_hk")) {
            val units = payload["原始文本单元"]
            require(payload.keys == setOf("原始文本单元") && units is JsonArray && units.isNotEmpty()) { "投资来源行快照必须只包含原始文本单元" }
            require(units.all { it is JsonPrimitive && it.isString }) { "投资来源行快照包含非文本单元" }
        }
        require("_source_payload" !in payload) { "投资来源行快照包含编排字段" }
        fun finite(value: JsonElement): Boolean = when (value) {
            is JsonObject -> value.values.all(::finite); is JsonArray -> value.all(::finite)
            is JsonPrimitive -> value == JsonNull || value.isString || value.booleanOrNull != null || value.content.toBigDecimalOrNull() != null
        }
        require(finite(payload)) { "投资来源行快照不是可持久化的 JSON 数据" }
        return payload
    }
}
