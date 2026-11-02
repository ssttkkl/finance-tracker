package com.finance.tracker.backend

import com.finance.tracker.business.domain.LedgerDecimal
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*

internal class IbkrRow(private val fields: JsonObject) : Map<String, JsonElement> by fields

internal data class IbkrStatement(val transactions: List<IbkrRow>, val baseCurrency: String, val endingCash: String,
    val beginningCash: String, val period: String, val whenGenerated: String)

internal object IbkrImporter {
    private val currencies = setOf("USD", "HKD", "CNY", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "NZD", "SGD")
    private val actions = setOf("买", "卖", "存款", "股息", "外国预扣税", "借方利息", "外汇交易组成部分")
    private val decimalFields = setOf("qty", "price", "gross", "commission", "net", "amount", "shares", "fee")
    private val context = MathContext(28, RoundingMode.HALF_EVEN)
    private fun text(row: Map<String, JsonElement>, key: String, default: String = ""): String = row[key]?.takeUnless { it == JsonNull }?.let(::pythonString) ?: default
    private fun value(row: Map<String, JsonElement>, key: String, default: String = "0"): String {
        val raw = row[key] ?: return default
        if (!pythonTruthy(raw)) return default
        val s = pythonString(raw)
        if (row is IbkrRow && key in decimalFields && s.toBigDecimalOrNull()?.signum() == 0) return default
        return if (!raw.jsonPrimitive.isString && s.toBigDecimalOrNull()?.signum() == 0) default else s
    }
    private fun decimal(raw: String): String {
        var nonfinite = false
        val rep = LedgerDecimal.parseRepresentationOrNull(raw) { if (it == LedgerDecimal.InputError.FINITE) nonfinite = true }
        if (rep == null) {
            if (!nonfinite) throw ArithmeticException("invalid decimal operation")
            return FinanceDecimal.parse(raw).text
        }
        val number = BigDecimal(rep.coefficient + "E" + rep.exponent)
        return (if (rep.negative) "-" else "") + number.toString()
    }
    private fun cell(raw: String): String? {
        val stripped = valuationStrip(raw).replace(",", "")
        if (stripped.isEmpty() || stripped == "-") return null
        return try { decimal(stripped) } catch (_: ArithmeticException) { throw IllegalArgumentException("invalid decimal in IBKR CSV: ${pythonStringRepr(raw)}") }
    }
    private fun abs(raw: String): String {
        val d = decimal(raw); val finite = d.toBigDecimalOrNull()
        if (finite != null) {
            val magnitude = finite.abs()
            val exponent = -magnitude.scale().toLong()
            if (magnitude.signum() == 0) return BigDecimal.ZERO.setScale(-exponent.coerceIn(-1000026L, 999999L).toInt()).toString()
            val adjusted = exponent + magnitude.precision() - 1
            if (adjusted > 999999L) throw ArithmeticException("decimal overflow")
            val targetExponent = maxOf(exponent, adjusted - 27, -1000026L)
            val rounded = magnitude.setScale(-targetExponent.toInt(), RoundingMode.HALF_EVEN)
            if (rounded.signum() != 0 && rounded.precision().toLong() - rounded.scale() - 1 > 999999L) throw ArithmeticException("decimal overflow")
            return rounded.round(context).toString()
        }
        if (d.removePrefix("-").startsWith("sNaN")) throw ArithmeticException("invalid decimal operation")
        return d.removePrefix("-")
    }
    private fun fmt(raw: String): String {
        val d = decimal(raw); val number = d.toBigDecimalOrNull() ?: return d
        val result = (if (number.signum() == 0 && d.startsWith('-')) "-" else "") + number.toPlainString()
        return if ('.' in result) result.trimEnd('0').trimEnd('.').ifEmpty { "0" } else result
    }
    private fun absolute(row: Map<String, JsonElement>, key: String) = fmt(abs(value(row, key)))
    private fun payload(cells: List<String>) = buildJsonObject { put("原始文本单元", JsonArray(cells.map(::JsonPrimitive))) }
    private fun equity(code: String, currency: String = "", default: String = "us"): String {
        val token = valuationStrip(code); require(token.isNotEmpty()) { "empty equity ticker" }
        val lower = pythonCashLower(token)
        if ('.' in token || valuationUpper(token) in currencies) return lower
        val suffix = when (valuationUpper(valuationStrip(currency))) {
            "HKD" -> "hk"
            "CNY" -> when (lower.first()) { '5', '6' -> "sh"; '0', '1', '2', '3' -> "sz"; else -> "cn" }
            "USD" -> "us"
            else -> default
        }
        return "$lower.$suffix"
    }
    private fun parseTicker(code: String): String {
        if (code.isEmpty() || code == "-") return ""
        return try { equity(code) } catch (_: IllegalArgumentException) { pythonCashLower(code) }
    }
    private fun equity(row: Map<String, JsonElement>): String {
        val code = valuationStrip(text(row, "code")); if (code.isEmpty() || code == "-") return ""
        if ('.' in code && valuationUpper(code.substringAfter('.')) in currencies) return pythonCashLower(code)
        return equity(code, value(row, "price_currency", value(row, "currency", "USD")))
    }
    internal fun csv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>(); var i = 0
        while (i < text.length) {
            val row = mutableListOf<String>(); val field = StringBuilder(); var quoted = false; var beginning = true; var touched = false; var points = 0
            fun append(char: Char) {
                if (!char.isLowSurrogate() || field.isEmpty() || !field.last().isHighSurrogate()) points++
                if (points > 131072) throw IllegalArgumentException("field larger than field limit (131072)")
                field.append(char)
            }
            while (i < text.length) {
                val char = text[i++]
                if (quoted) {
                    if (char == '"') {
                        if (i < text.length && text[i] == '"') { append('"'); i++ } else quoted = false
                    } else append(char)
                    continue
                }
                if (char == '\r' || char == '\n') {
                    if (char == '\r' && i < text.length && text[i] == '\n') i++
                    break
                }
                touched = true
                if (char == ',') { row += field.toString(); field.clear(); points = 0; beginning = true }
                else if (char == '"' && beginning) { quoted = true; beginning = false }
                else { append(char); beginning = false }
            }
            if (touched || row.isNotEmpty() || field.isNotEmpty()) row += field.toString()
            rows += row
        }
        return rows
    }
    fun parse(bytes: ByteArray): IbkrStatement {
        val content = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val flows = mutableListOf<IbkrRow>(); var base = ""; var ending = "0"; var beginning = "0"; var period = ""; var generated = ""; var date = ""; var endingPayload: JsonObject? = null
        for (row in csv(content)) {
            if (row.isEmpty()) continue
            val section = valuationStrip(row[0]); val kind = row.getOrNull(1)?.let(::valuationStrip).orEmpty()
            if (section == "Statement" && kind == "Data" && row.size >= 4) {
                when (row[2]) { "Period" -> period = row[3]; "WhenGenerated" -> generated = row[3] }; continue
            }
            if (section == "总结" && kind == "Data" && row.size >= 4) {
                when (row[2]) { "基础货币" -> base = valuationUpper(valuationStrip(row[3])); "期初现金" -> beginning = cell(row[3]) ?: "0"; "期末现金" -> { ending = cell(row[3]) ?: "0"; endingPayload = payload(row) } }; continue
            }
            if (section != "Transaction History" || kind != "Data") continue
            require(row.size >= 13) { "IBKR Transaction History row has ${row.size} columns, need 13" }
            val action = valuationStrip(row[5]); require(action in actions) { "unknown IBKR transaction type: ${pythonStringRepr(action)}" }
            val whenText = valuationStrip(row[2]); val code = valuationStrip(row[6]); val qty = cell(row[7]); val price = cell(row[8]); val gross = cell(row[10]); val fee = cell(row[11]); val net = cell(row[12])
            if (pythonStringOrder(whenText, date) > 0) date = whenText
            flows += buildJsonObject {
                put("date", whenText); put("account", valuationStrip(row[3])); put("note", valuationStrip(row[4])); put("action", action); put("code", code.takeUnless { it == "-" }.orEmpty())
                put("qty", qty ?: "0"); put("qty_raw", qty?.let(::JsonPrimitive) ?: JsonNull); put("price", price ?: "0"); put("price_raw", price?.let(::JsonPrimitive) ?: JsonNull)
                put("price_currency", valuationStrip(row[9]).takeUnless { it == "-" }.orEmpty()); put("gross", gross ?: "0"); put("commission", fee ?: "0"); put("commission_raw", fee?.let(::JsonPrimitive) ?: JsonNull)
                put("net", net ?: "0"); put("net_raw", net?.let(::JsonPrimitive) ?: JsonNull); put("amount", net ?: "0"); put("shares", qty?.let(::abs) ?: "0"); put("fee", fee?.let(::abs) ?: "0"); put("ticker", parseTicker(code)); put("_source_payload", payload(row))
            }.let(::IbkrRow)
        }
        require(flows.isNotEmpty() || base.isNotEmpty()) { "IBKR CSV missing Transaction History / 总结 sections" }
        val checkinDate = date.ifEmpty { generated.take(10) }.ifEmpty { "1970-01-01" }
        require(endingPayload != null) { "IBKR CSV missing 总结.期末现金 source row" }
        flows += buildJsonObject {
            put("date", checkinDate); put("account", ""); put("note", "总结.期末现金"); put("action", "CHECKIN"); put("code", ""); put("qty", "0"); put("qty_raw", JsonNull)
            put("price", "1"); put("price_raw", "1"); put("price_currency", base); put("gross", ending); put("commission", "0"); put("commission_raw", JsonNull); put("net", ending); put("net_raw", ending)
            put("amount", ending); put("shares", "0"); put("fee", "0"); put("ticker", ""); put("_source_payload", endingPayload)
        }.let(::IbkrRow)
        return IbkrStatement(flows, base, ending, beginning, period, generated)
    }
    fun identity(row: Map<String, JsonElement>): String {
        val date = text(row, "date").take(10).replace("-", ""); val action = text(row, "action")
        if (action == "CHECKIN") return "ibkr:$date:checkin:cash:${fmt(value(row, "amount"))}:0"
        val code = value(row, "code", "cash"); val qty = when { action in setOf("买", "卖") -> absolute(row, "qty"); row["qty_raw"] == null || row["qty_raw"] == JsonNull -> "0"; else -> fmt(text(row, "qty")) }
        val net = row["net_raw"]?.takeUnless { it == JsonNull }?.let(::pythonString) ?: value(row, "net")
        val commission = row["commission_raw"]?.takeUnless { it == JsonNull }?.let(::pythonString) ?: "0"
        return "ibkr:$date:$action:$code:$qty:${fmt(net)}:${fmt(commission)}"
    }
    fun event(row: Map<String, JsonElement>, account: String, currency: String = "USD"): JsonObject {
        val action = text(row, "action"); val cash = pythonCashLower(currency)
        val base = linkedMapOf<String, JsonElement>("date" to row.getValue("date"), "account_name" to JsonPrimitive(account), "currency" to JsonPrimitive(valuationUpper(currency)), "note" to (row["note"]?.takeIf(::pythonTruthy) ?: row["description"]?.takeIf(::pythonTruthy) ?: JsonPrimitive("")))
        fun result(type: String, subtype: String, from: String = "", fromAmount: String = "0", to: String = "", toAmount: String = "0", price: String = "1", fee: String = "0", feeAsset: String = ""): JsonObject = buildJsonObject {
            base.forEach { (key, value) -> put(key, value) }; put("record_type", type); put("record_subtype", subtype); put("from_ticker", from); put("from_amount", fromAmount); put("to_ticker", to); put("to_amount", toAmount); put("price", price); put("commission", fee); put("commission_asset", feeAsset)
        }
        return when (action) {
            "买", "卖" -> {
                val gross = absolute(row, "gross"); val qty = absolute(row, "qty"); val fee = absolute(row, "commission"); val code = equity(row); val price = absolute(row, "price")
                val feeAsset = if (FinanceDecimal.parse(fee).zero()) "" else cash
                if (action == "买") result("trade", "security", cash, gross, code, qty, price, fee, feeAsset) else result("trade", "security", code, qty, cash, gross, price, fee, feeAsset)
            }
            "存款" -> result("funding", "external", to = cash, toAmount = absolute(row, "net"))
            "股息" -> result("income", "dividend_cash", from = equity(row), to = cash, toAmount = absolute(row, "net"))
            "外国预扣税", "借方利息" -> result("expense", if (action == "外国预扣税") "tax" else "interest", cash, absolute(row, "net"))
            "CHECKIN" -> {
                base["note"] = JsonPrimitive("")
                val amount = value(row, "amount", value(row, "net"))
                result("snapshot", "cash", to = cash, toAmount = fmt(abs(amount)))
            }
            "外汇交易组成部分" -> {
                val parsed = decimal(value(row, "net"))
                val finite = parsed.toBigDecimalOrNull() ?: throw ArithmeticException("invalid decimal operation")
                val net = finite.setScale(18, RoundingMode.HALF_UP)
                if (net.precision() > 28) throw ArithmeticException("invalid decimal operation")
                val amount = fmt(net.abs(context).toString()); val fxCash = cash.ifEmpty { "usd" }
                if (net.signum() >= 0) result("adjustment", "fx_net", to = fxCash, toAmount = amount) else result("adjustment", "fx_net", fxCash, amount)
            }
            else -> throw IllegalArgumentException("unsupported IBKR action: $action")
        }
    }
}
