package com.finance.tracker.backend

import com.finance.tracker.business.domain.LedgerDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlinx.serialization.json.*

internal data class FinanceQueryAccount(val name: String, val type: String, val active: Boolean = true, val currencies: List<String> = emptyList())
internal class FinanceDecimal private constructor(val text: String) : Comparable<FinanceDecimal> {
    private val negative get() = text.startsWith('-')
    private val unsigned get() = text.removePrefix("-")
    private val nan get() = unsigned.startsWith("NaN") || unsigned.startsWith("sNaN")
    private val infinity get() = unsigned == "Infinity"
    private fun signal() { if (unsigned.startsWith("sNaN")) throw ArithmeticException("invalid decimal operation") }
    fun zero(): Boolean { signal(); return !nan && !infinity && text.toBigDecimal().signum() == 0 }
    fun fixed(): String = if (nan || infinity) text else (if (negative && text.toBigDecimal().signum() == 0) "-" else "") + text.toBigDecimal().toPlainString()
    fun abs(): FinanceDecimal { signal(); return FinanceDecimal(unsigned) }
    override fun compareTo(other: FinanceDecimal): Int {
        if (nan || other.nan) throw ArithmeticException("invalid decimal comparison")
        if (infinity || other.infinity) return if (infinity && other.infinity) (-negative.compareTo(other.negative)) else
            if (infinity) { if (negative) -1 else 1 } else if (other.negative) 1 else -1
        return text.toBigDecimal().compareTo(other.text.toBigDecimal())
    }
    fun add(other: FinanceDecimal, precision: Int): FinanceDecimal {
        signal(); other.signal()
        if (nan || other.nan) return if (nan) this else other
        if (infinity || other.infinity) {
            if (infinity && other.infinity && negative != other.negative) throw ArithmeticException("invalid decimal addition")
            return if (infinity) this else other
        }
        val sum = text.toBigDecimal().add(other.text.toBigDecimal(), MathContext(precision, RoundingMode.HALF_EVEN))
        return FinanceDecimal((if (sum.signum() == 0 && negative && other.negative) "-" else "") + sum.toString())
    }
    fun multiply(other: FinanceDecimal, precision: Int): FinanceDecimal {
        signal(); other.signal()
        if (nan || other.nan) return if (nan) this else other
        if (infinity || other.infinity) {
            if ((!infinity && zero()) || (!other.infinity && other.zero())) throw ArithmeticException("invalid decimal multiplication")
            return FinanceDecimal((if (negative != other.negative) "-" else "") + "Infinity")
        }
        val product = text.toBigDecimal().multiply(other.text.toBigDecimal(), MathContext(precision, RoundingMode.HALF_EVEN))
        return FinanceDecimal((if (product.signum() == 0 && negative != other.negative) "-" else "") + product.toString())
    }
    companion object {
        val ZERO = FinanceDecimal("0")
        fun parse(value: JsonElement?): FinanceDecimal {
            val raw = if (value == null || !pythonTruthy(value)) "0" else pythonString(value)
            var nonFinite = false
            val rep = LedgerDecimal.parseRepresentationOrNull(raw) { if (it == LedgerDecimal.InputError.FINITE) nonFinite = true }
            if (rep != null) return FinanceDecimal((if (rep.negative) "-" else "") + rep.coefficient + "E" + rep.exponent)
            if (!nonFinite) return ZERO
            val token = buildString { valuationStrip(raw).replace("_", "").codePoints().forEach { code ->
                val start = pythonIntegerDigitStarts.firstOrNull { code in it until it + 10 }
                if (start == null) appendCodePoint(code) else append(('0'.code + code - start).toChar())
            } }.lowercase()
            val negative = token.startsWith('-'); val unsigned = token.removePrefix("-").removePrefix("+")
            val special = when { unsigned.startsWith("snan") -> "sNaN" + unsigned.removePrefix("snan").trimStart('0')
                unsigned.startsWith("nan") -> "NaN" + unsigned.removePrefix("nan").trimStart('0'); else -> "Infinity" }
            return FinanceDecimal((if (negative) "-" else "") + special)
        }
        fun parse(value: String) = parse(JsonPrimitive(value))
    }
}
private fun financeLegacyStrip(value: JsonElement?): String {
    val supplied = value ?: JsonPrimitive("")
    check(supplied is JsonPrimitive && supplied.isString) { "legacy report text field must be a string" }
    return valuationStrip(supplied.content)
}
private fun financeObject(value: JsonElement?) = value?.jsonObject ?: JsonObject(emptyMap())
private fun financeMoneyMap(values: Map<String, FinanceDecimal>) = buildJsonObject { values.forEach { (key, amount) -> put(key, amount.fixed()) } }

internal class FinanceQueryApplication(private val accounts: () -> List<FinanceQueryAccount>, private val snapshot: () -> JsonObject,
    private val transactions: (String?) -> List<JsonObject> = { emptyList() }, private val valuation: ValuationService? = null,
    private val marketData: ((List<String>, String) -> Map<String, String>)? = null, private val cashReporter: ((String?) -> JsonObject)? = null) {
    fun listAccounts(precision: Int = 28): JsonObject {
        val state = snapshot()
        return buildJsonObject { put("accounts", JsonArray(accounts().flatMap { account -> balances(account, state, precision).map { (currency, balance) ->
            buildJsonObject { put("name", account.name); put("type", account.type); put("currency", currency); put("active", account.active); put("balance", balance.fixed()) }
        } })) }
    }
    private fun balances(account: FinanceQueryAccount, snapshot: JsonObject, precision: Int): List<Pair<String, FinanceDecimal>> {
        val buckets = financeObject(snapshot["accounts"])
        if (account.type in setOf("cash", "loan", "lend")) {
            val bucket = financeObject(buckets[account.type])[account.name] ?: JsonObject(emptyMap())
            return if (bucket is JsonObject) bucket.keys.sortedWith(::pythonStringOrder).map { it to FinanceDecimal.parse(bucket[it]) }
                else listOf("CNY" to FinanceDecimal.parse(bucket))
        }
        val book = financeObject(financeObject(buckets["security"])[account.name])
        val positions = financeObject(book["positions"])
        val currency = valuationUpper(book["currency"]?.takeIf(::pythonTruthy)?.let(::pythonString) ?: account.currencies.firstOrNull() ?: "CNY")
        val currencyTicker = pythonCashLower(currency)
        val bases = account.currencies.map(::valuationUpper).toMutableSet().also { if (currency.isNotEmpty()) it.add(currency) }
        var total = FinanceDecimal.ZERO
        if (valuation != null) {
            for ((ticker, raw) in positions) {
                val position = raw.jsonObject; val shares = FinanceDecimal.parse(position["shares"])
                if (shares.zero()) continue
                if (pythonCashLower(ticker) == currencyTicker || valuationUpper(ticker) in bases) { total = total.add(shares, precision); continue }
                val kind = inferAssetKind(ticker, bases, bases)
                if (kind == null) { total = total.add(FinanceDecimal.parse(position["total_cost"]), precision); continue }
                val result = valuation.quote(AssetRef(ticker, kind, shares.text), precision = precision)
                val usable = result.status in setOf(QuoteStatus.COMPLETE, QuoteStatus.STALE) && valuationUpper(result.quoteCurrency.orEmpty()) == currency
                val value = when {
                    usable && result.marketValue != null -> FinanceDecimal.parse(result.marketValue)
                    usable && result.unitPrice != null -> shares.multiply(FinanceDecimal.parse(result.unitPrice), precision)
                    else -> FinanceDecimal.parse(position["total_cost"])
                }
                total = total.add(value, precision)
            }
        } else {
            val tickers = mutableListOf<String>()
            for ((ticker, raw) in positions) {
                val shares = FinanceDecimal.parse(raw.jsonObject["shares"])
                if (pythonCashLower(ticker) == currencyTicker) total = total.add(shares, precision)
                else if (!shares.zero()) tickers.add(ticker)
            }
            val prices = if (tickers.isNotEmpty()) marketData?.invoke(tickers, currency).orEmpty() else emptyMap()
            for (ticker in tickers) {
                val position = positions.getValue(ticker).jsonObject
                val value = prices[ticker]?.let { FinanceDecimal.parse(position["shares"]).multiply(FinanceDecimal.parse(it), precision) }
                    ?: FinanceDecimal.parse(position["total_cost"])
                total = total.add(value, precision)
            }
        }
        return listOf(currency to total)
    }
    fun report(month: String? = null): JsonObject {
        if (cashReporter != null) {
            val accountList = listAccounts(80); val values = cashReporter.invoke(month)
            check(values.keys.all { it in setOf("income", "expenses", "flows") })
            return buildJsonObject {
                put("accounts", accountList); put("expenses", values["expenses"] ?: JsonObject(emptyMap()))
                put("income", values["income"] ?: JsonObject(emptyMap())); put("flows", values["flows"] ?: JsonArray(emptyList()))
            }
        }
        fun live(row: JsonObject) = (row["state"] ?: JsonPrimitive("posted")) == JsonPrimitive("posted") &&
            !pythonTruthy(row["deleted_at"]) && !pythonTruthy(row["deleted"])
        val monthly = transactions(month).filter(::live); val all = transactions(null).filter(::live)
        val active = accounts().filter { it.active }.map { it.name }.toSet()
        val grouped = linkedMapOf<Pair<String, String>, MutableList<JsonObject>>()
        for (row in monthly) {
            val name = financeLegacyStrip(row["account_name"])
            val currency = financeLegacyStrip(row["currency"]).ifEmpty { "CNY" }
            if (name.isNotEmpty()) grouped.getOrPut(name to currency) { mutableListOf() }.add(row)
        }
        val expenses = linkedMapOf<String, FinanceDecimal>(); val income = linkedMapOf<String, FinanceDecimal>()
        for ((key, rows) in grouped) {
            if (key.first !in active) continue
            fun date(row: JsonObject) = row["occurred_at"]?.takeIf(::pythonTruthy) ?: row["date"]?.takeIf(::pythonTruthy) ?: JsonPrimitive("")
            val ordered = rows.sortedWith { a, b -> pythonStringOrder(pythonString(date(a)), pythonString(date(b))) }
            val boundary = ordered.indexOfLast { row -> (row["record_type"]?.takeIf(::pythonTruthy) ?: JsonPrimitive("other")) == JsonPrimitive("other") &&
                FinanceDecimal.parse(row["amount"]).zero() && "余额校准" in pythonString(row["note"]?.takeIf(::pythonTruthy) ?: JsonPrimitive("")) }
            for (row in ordered.drop(boundary + 1)) if (row["record_type"] in listOf(JsonPrimitive("consumption"), JsonPrimitive("fee")) && FinanceDecimal.parse(row["amount"]) < FinanceDecimal.ZERO)
                expenses[key.second] = (expenses[key.second] ?: FinanceDecimal.ZERO).add(FinanceDecimal.parse(row["amount"]).abs(), 80)
        }
        for (row in monthly) if (row["record_type"] in listOf(JsonPrimitive("income"), JsonPrimitive("refund"), JsonPrimitive("interest")) && FinanceDecimal.parse(row["amount"]) > FinanceDecimal.ZERO) {
            val currency = pythonString(row["currency"]?.takeIf(::pythonTruthy) ?: JsonPrimitive("CNY"))
            income[currency] = (income[currency] ?: FinanceDecimal.ZERO).add(FinanceDecimal.parse(row["amount"]), 80)
        }
        val flows = linkedMapOf<Pair<JsonElement, String>, FinanceDecimal>()
        for (row in all) if (row["record_type"] == JsonPrimitive("transfer_out")) {
            val key = (row["note"] ?: JsonPrimitive("")) to pythonString(row["currency"]?.takeIf(::pythonTruthy) ?: JsonPrimitive("CNY"))
            flows[key] = (flows[key] ?: FinanceDecimal.ZERO).add(FinanceDecimal.parse(row["amount"]).abs(), 80)
        }
        val flowRows = flows.entries.sortedWith { a, b -> b.value.compareTo(a.value) }.take(10).map { (key, amount) ->
            buildJsonObject { put("note", key.first); put("currency", key.second); put("amount", amount.fixed()) }
        }
        return buildJsonObject { put("accounts", listAccounts(80)); put("expenses", financeMoneyMap(expenses)); put("income", financeMoneyMap(income)); put("flows", JsonArray(flowRows)) }
    }
}
