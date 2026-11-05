package com.finance.tracker.backend

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*

internal class SchwabRow(private val fields: JsonObject) : Map<String, JsonElement> by fields
internal data class SchwabStatement(val transactions: List<SchwabRow>, val endingCash: String, val currency: String = "USD")
internal object SchwabImporter {
    private val types = setOf("TRD", "DOI", "JRN", "WIN")
    private val decimalFields = setOf("misc_fee", "commission_col", "amount", "balance", "fee", "qty", "price")
    private val spaces = pythonWhitespace.joinToString("") { "\\x{${it.toString(16)}}" }
    private val digits = pythonIntegerDigitStarts.joinToString("") { "\\x{${it.toString(16)}}-\\x{${(it + 9).toString(16)}}" }
    private fun pattern(raw: String) = Regex(raw.replace("\\s", "[$spaces]").replace("\\S", "[^$spaces]").replace("\\d", "[$digits]"), RegexOption.IGNORE_CASE)
    private val tradePattern = pattern("^(BOT|SOLD)\\s+([+-]?\\d+(?:\\.\\d+)?)\\s+(\\S+)\\s+@\\s*(\\d+(?:\\.\\d+)?)\\s*$")
    private val cashPattern = pattern("^([^\\n]+?)\\s+[+-]?\\d+(?:\\.\\d+)?\\s+US\\$$")
    private fun text(row: Map<String, JsonElement>, key: String) = row[key]?.takeUnless { it == JsonNull }?.let(::pythonString).orEmpty()
    private fun value(row: Map<String, JsonElement>, key: String, fallback: String = "0"): String {
        val raw = row[key] ?: return fallback; if (!pythonTruthy(raw)) return fallback
        val s = pythonString(raw)
        return if (row is SchwabRow && key in decimalFields && s.toBigDecimalOrNull()?.signum() == 0) fallback else s
    }
    private fun absolute(row: Map<String, JsonElement>, key: String) = BrokerText.fmt(BrokerText.abs(value(row, key)))
    private fun number(value: String): String? {
        var s = valuationStrip(value); if (s.isEmpty() || s == "-") return null
        val negative = s.startsWith('(') && s.endsWith(')'); if (negative) s = s.substring(1, s.length - 1)
        s = valuationStrip(s.replace("$", "").replace(",", "")); if (s.isEmpty() || s == "-") return null
        val d = try { BrokerText.decimal(s) } catch (_: ArithmeticException) { throw IllegalArgumentException("invalid decimal in Schwab CSV: ${pythonStringRepr(value)}") }
        if (!negative || d.removePrefix("-").startsWith("NaN")) return d
        val result = BrokerText.abs(d)
        return if (d.startsWith('-') || d.toBigDecimalOrNull()?.signum() == 0) result else "-$result"
    }
    internal fun normalizeDate(raw: String): String {
        val s = valuationStrip(raw); if (s.isEmpty()) return s
        var date = s.substringBefore(' '); var time = if (' ' in s) s.substringAfter(' ') else ""
        date = date.replace('/', '-'); val parts = date.split('-')
        fun integer(token: String): String = try { pythonIntegerValue(JsonPrimitive(token)).toString() } catch (_: CategoryFailure) { throw IllegalArgumentException("invalid literal for int() with base 10: ${pythonStringRepr(token)}") }
        fun padded(token: String, width: Int): String { val n = integer(token); return if (n.startsWith('-')) "-" + n.drop(1).padStart(width - 1, '0') else n.padStart(width, '0') }
        if (parts.size == 3) date = "${padded(parts[0], 4)}-${padded(parts[1], 2)}-${padded(parts[2], 2)}"
        if (time.isNotEmpty()) {
            val bits = valuationStrip(time).split(':')
            if (bits.size >= 2) time = "${padded(bits[0], 2)}:${padded(bits[1], 2)}" + if (bits.size > 2) ":${padded(bits[2], 2)}" else ""
            return "$date $time"
        }
        return date
    }
    private fun label(description: String): String? {
        val match = cashPattern.matchEntire(valuationStrip(description)) ?: return null
        val label = valuationStrip(match.groupValues[1]).replace(pattern("^QUALIFIED\\s+DIVIDEND\\s+-\\s*"), "")
        return valuationUpper(label).split(pattern("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null }
    }
    fun parse(bytes: ByteArray): SchwabStatement {
        var content = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        if (content.startsWith('\uFEFF')) content = content.substring(1)
        val records = BrokerText.csv(content); require(records.isNotEmpty() && records[0].isNotEmpty()) { "Schwab CSV missing header row" }
        val headers = records[0]; require(headers.toSet().size == headers.size) { "Schwab CSV has duplicate source columns" }
        val keyMap = headers.associateBy(::valuationStrip); val required = listOf("日期", "类型", "说明", "参照号码", "杂费", "佣金", "金额", "余额")
        val missing = required.filter { it !in keyMap }; require(missing.isEmpty()) { "Schwab CSV missing columns: ${missing.joinToString(", ", "[", "]", transform = ::pythonStringRepr)}" }
        val flows = mutableListOf<JsonObject>(); var balance: String? = null; var newestDate = ""; var newestSource: JsonObject? = null
        for (cells in records.drop(1)) {
            if (cells.isEmpty()) continue
            if (cells.size <= headers.size && cells.all { valuationStrip(it).isEmpty() }) continue
            require(cells.size == headers.size) { "Schwab CSV source row has a mismatched column count" }
            val raw = headers.zip(cells).toMap(); fun cell(name: String) = valuationStrip(raw.getValue(keyMap.getValue(name)))
            val type = cell("类型"); if (type.isEmpty()) continue
            require(type in types) { "unknown Schwab transaction type: ${pythonStringRepr(type)}" }
            val date = normalizeDate(cell("日期")); val description = cell("说明"); val ref = cell("参照号码")
            val misc = number(cell("杂费")) ?: "0"; val commission = number(cell("佣金")) ?: "0"; val amount = number(cell("金额")) ?: "0"; val rowBalance = number(cell("余额")) ?: "0"
            val source = JsonObject(raw.mapValues { JsonPrimitive(it.value) })
            if (balance == null) { balance = rowBalance; newestDate = date; newestSource = source }
            val fee = FinanceDecimal.parse(BrokerText.abs(misc)).add(FinanceDecimal.parse(BrokerText.abs(commission)), 28).text.let(BrokerText::abs)
            var side = ""; var symbol = ""; var qty = "0"; var price = "0"
            if (type == "TRD") {
                val m = tradePattern.matchEntire(valuationStrip(description)); require(m != null) { "unparseable Schwab TRD description: ${pythonStringRepr(description)}" }
                side = valuationUpper(m.groupValues[1]); qty = BrokerText.abs(m.groupValues[2]); symbol = valuationStrip(m.groupValues[3]); price = BrokerText.decimal(m.groupValues[4])
            }
            flows += buildJsonObject {
                put("date", date); put("type", type); put("note", description); put("ref", ref); put("misc_fee", misc); put("commission_col", commission); put("amount", amount); put("balance", rowBalance); put("fee", fee)
                put("side", side); put("symbol", symbol); put("ticker", if (symbol.isEmpty()) "" else BrokerText.equity(symbol)); put("qty", qty); put("price", price); put("journal_kind", ""); put("_source_payload", source)
            }
        }
        require(flows.isNotEmpty()) { "Schwab CSV has no transaction rows" }
        val ending = requireNotNull(balance); val source = requireNotNull(newestSource)
        val dividends = flows.mapNotNull { row -> if (text(row, "type") == "DOI" && FinanceDecimal.parse(text(row, "amount")) > FinanceDecimal.ZERO) label(text(row, "note"))?.let { text(row, "date") to it } else null }.toSet()
        val annotated = flows.map { row ->
            if (text(row, "type") == "JRN" && FinanceDecimal.parse(text(row, "amount")) < FinanceDecimal.ZERO && label(text(row, "note"))?.let { text(row, "date") to it } in dividends) JsonObject(row + ("journal_kind" to JsonPrimitive("withholding_tax"))) else row
        }.sortedWith { a, b -> pythonStringOrder(text(a, "date"), text(b, "date")).takeUnless { it == 0 } ?: pythonStringOrder(text(a, "ref"), text(b, "ref")) }
        val checkin = buildJsonObject {
            put("date", newestDate.ifEmpty { text(annotated.last(), "date") }); put("type", "CHECKIN"); put("note", ""); put("ref", ""); put("misc_fee", "0"); put("commission_col", "0"); put("amount", ending); put("balance", ending); put("fee", "0"); put("side", ""); put("symbol", ""); put("ticker", ""); put("qty", "0"); put("price", "1"); put("journal_kind", ""); put("_source_payload", source)
        }
        return SchwabStatement((annotated + checkin).map(::SchwabRow), ending)
    }
    fun identity(row: Map<String, JsonElement>): String {
        val type = text(row, "type")
        if (type == "CHECKIN") return "schwab:${text(row, "date").take(10).replace("-", "")}:checkin:cash:${BrokerText.fmt(value(row, "amount"))}"
        val ref = valuationStrip(value(row, "ref", "")); if (ref.isNotEmpty()) return "schwab:$ref:$type"
        return "schwab:${text(row, "date").take(10).replace("-", "")}:$type:${BrokerText.fmt(value(row, "amount"))}:${BrokerText.fmt(value(row, "balance"))}"
    }
    fun event(row: Map<String, JsonElement>, account: String, currency: String = "USD"): JsonObject {
        val type = text(row, "type"); val cash = pythonCashLower(currency); val description = row["note"]?.takeIf(::pythonTruthy) ?: row["description"]?.takeIf(::pythonTruthy) ?: JsonPrimitive("")
        fun result(record: String, subtype: String, from: String = "", fromAmount: String = "0", to: String = "", toAmount: String = "0", price: String = "1", fee: String = "0", feeAsset: String = ""): JsonObject = buildJsonObject {
            put("date", row.getValue("date")); put("account_name", account); put("currency", valuationUpper(currency)); put("note", if (type == "CHECKIN") JsonPrimitive("") else description)
            put("record_type", record); put("record_subtype", subtype); put("from_ticker", from); put("from_amount", fromAmount); put("to_ticker", to); put("to_amount", toAmount); put("price", price); put("commission", fee); put("commission_asset", feeAsset)
        }
        if (type == "TRD") {
            val side = valuationUpper(value(row, "side", "")); val qty = absolute(row, "qty"); val price = absolute(row, "price"); val amount = absolute(row, "amount"); val fee = absolute(row, "fee")
            val raw = valuationStrip(value(row, "ticker", value(row, "symbol", ""))); require(raw.isNotEmpty()) { "Schwab TRD missing symbol: ${row["description"]?.let { if (it == JsonNull) "None" else pythonStringRepr(pythonString(it)) } ?: "None"}" }
            val code = BrokerText.equity(raw); val feeAsset = if (FinanceDecimal.parse(fee).zero()) "" else cash
            return when (side) { "BOT" -> result("trade", "security", cash, amount, code, qty, price, fee, feeAsset); "SOLD" -> result("trade", "security", code, qty, cash, amount, price, fee, feeAsset); else -> throw IllegalArgumentException("unsupported Schwab TRD side: ${pythonStringRepr(side)}") }
        }
        if (type == "WIN") return result("funding", "external", to = cash, toAmount = absolute(row, "amount"))
        if (type == "CHECKIN") return result("snapshot", "cash", to = cash, toAmount = absolute(row, "amount"))
        if (type in setOf("DOI", "JRN")) {
            val amount = FinanceDecimal.parse(BrokerText.decimal(value(row, "amount"))); val magnitude = absolute(row, "amount"); val desc = valuationUpper(pythonString(description))
            if (type == "DOI") return if (amount > FinanceDecimal.ZERO) result("income", if ("INTEREST" in desc) "interest" else "dividend_cash", to = cash, toAmount = magnitude) else result("expense", "interest", cash, magnitude)
            if (text(row, "journal_kind") == "withholding_tax") return result("expense", "tax", cash, magnitude)
            if (amount > FinanceDecimal.ZERO && "REFUND" in desc) return result("reversal", "funding_withdrawal", to = cash, toAmount = magnitude)
            throw IllegalArgumentException("unsupported Schwab JRN without a structured tax or refund signal")
        }
        throw IllegalArgumentException("unsupported Schwab type: $type")
    }
}
