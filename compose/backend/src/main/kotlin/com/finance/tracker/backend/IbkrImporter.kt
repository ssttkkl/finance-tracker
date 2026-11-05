package com.finance.tracker.backend

import java.math.MathContext
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*

internal class IbkrRow(private val fields: JsonObject) : Map<String, JsonElement> by fields

internal data class IbkrStatement(val transactions: List<IbkrRow>, val baseCurrency: String, val endingCash: String,
    val beginningCash: String, val period: String, val whenGenerated: String)

internal object IbkrImporter {
    private val currencies = BrokerText.currencies
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

    private fun cell(raw: String): String? {
        val stripped = valuationStrip(raw).replace(",", "")
        if (stripped.isEmpty() || stripped == "-") return null
        return try { BrokerText.decimal(stripped) } catch (_: ArithmeticException) { throw IllegalArgumentException("invalid decimal in IBKR CSV: ${pythonStringRepr(raw)}") }
    }


    private fun absolute(row: Map<String, JsonElement>, key: String) = BrokerText.fmt(BrokerText.abs(value(row, key)))
    private fun payload(cells: List<String>) = buildJsonObject { put("原始文本单元", JsonArray(cells.map(::JsonPrimitive))) }

    private fun parseTicker(code: String): String {
        if (code.isEmpty() || code == "-") return ""
        return try { BrokerText.equity(code) } catch (_: IllegalArgumentException) { pythonCashLower(code) }
    }
    private fun equity(row: Map<String, JsonElement>): String {
        val code = valuationStrip(text(row, "code")); if (code.isEmpty() || code == "-") return ""
        if ('.' in code && valuationUpper(code.substringAfter('.')) in currencies) return pythonCashLower(code)
        return BrokerText.equity(code, value(row, "price_currency", value(row, "currency", "USD")))
    }

    fun parse(bytes: ByteArray): IbkrStatement {
        val content = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val flows = mutableListOf<IbkrRow>(); var base = ""; var ending = "0"; var beginning = "0"; var period = ""; var generated = ""; var date = ""; var endingPayload: JsonObject? = null
        for (row in BrokerText.csv(content)) {
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
                put("net", net ?: "0"); put("net_raw", net?.let(::JsonPrimitive) ?: JsonNull); put("amount", net ?: "0"); put("shares", qty?.let(BrokerText::abs) ?: "0"); put("fee", fee?.let(BrokerText::abs) ?: "0"); put("ticker", parseTicker(code)); put("_source_payload", payload(row))
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
        if (action == "CHECKIN") return "ibkr:$date:checkin:cash:${BrokerText.fmt(value(row, "amount"))}:0"
        val code = value(row, "code", "cash"); val qty = when { action in setOf("买", "卖") -> absolute(row, "qty"); row["qty_raw"] == null || row["qty_raw"] == JsonNull -> "0"; else -> BrokerText.fmt(text(row, "qty")) }
        val net = row["net_raw"]?.takeUnless { it == JsonNull }?.let(::pythonString) ?: value(row, "net")
        val commission = row["commission_raw"]?.takeUnless { it == JsonNull }?.let(::pythonString) ?: "0"
        return "ibkr:$date:$action:$code:$qty:${BrokerText.fmt(net)}:${BrokerText.fmt(commission)}"
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
                result("snapshot", "cash", to = cash, toAmount = BrokerText.fmt(BrokerText.abs(amount)))
            }
            "外汇交易组成部分" -> {
                val parsed = BrokerText.decimal(value(row, "net"))
                val finite = parsed.toBigDecimalOrNull() ?: throw ArithmeticException("invalid decimal operation")
                val net = finite.setScale(18, RoundingMode.HALF_UP)
                if (net.precision() > 28) throw ArithmeticException("invalid decimal operation")
                val amount = BrokerText.fmt(net.abs(context).toString()); val fxCash = cash.ifEmpty { "usd" }
                if (net.signum() >= 0) result("adjustment", "fx_net", to = fxCash, toAmount = amount) else result("adjustment", "fx_net", fxCash, amount)
            }
            else -> throw IllegalArgumentException("unsupported IBKR action: $action")
        }
    }
}
