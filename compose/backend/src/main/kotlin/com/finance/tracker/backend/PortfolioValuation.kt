package com.finance.tracker.backend

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

internal val portfolioMath = MathContext(28, RoundingMode.HALF_EVEN)
internal fun portfolioNumber(value: JsonElement?, field: String) = InvestmentDecimal.exact(value ?: JsonPrimitive(0), field).number
internal fun portfolioText(value: JsonElement?) = pythonString(value?.takeIf(::pythonTruthy) ?: JsonPrimitive(""))
internal fun portfolioMoney(value: BigDecimal?, negativeZero: Boolean = false): JsonElement = value?.let { JsonPrimitive((if (negativeZero && it.signum() == 0) "-" else "") + it.toPlainString()) } ?: JsonNull
internal fun portfolioTimeText(time: QuoteTime): String {
    val local = time.local
    return java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").format(local) +
        (if (local.nano / 1000 == 0) "" else "." + (local.nano / 1000).toString().padStart(6, '0')) +
        (time.sourceOffsetMicros?.let { micros ->
            val absolute = kotlin.math.abs(micros); val seconds = absolute / 1000000
            (if (micros < 0) "-" else "+") + (seconds / 3600).toString().padStart(2, '0') + ":" + (seconds / 60 % 60).toString().padStart(2, '0') +
                (if (seconds % 60 == 0L && absolute % 1000000 == 0L) "" else ":" + (seconds % 60).toString().padStart(2, '0')) +
                (if (absolute % 1000000 == 0L) "" else "." + (absolute % 1000000).toString().padStart(6, '0'))
        } ?: time.offset?.id?.let { if (it == "Z") "+00:00" else it } ?: "")
}
internal fun <T : Any> portfolioPoll(queue: LinkedBlockingQueue<T>, deadline: Double?, monotonic: () -> Double): T? {
    if (deadline == null) return queue.take()
    val remaining = deadline - monotonic()
    return if (remaining <= 0) null else queue.poll((remaining * 1_000_000_000).toLong(), TimeUnit.NANOSECONDS)
}
internal fun portfolioThread(action: () -> Unit) = Thread(action, "portfolio-quote").apply { isDaemon = true; start() }

internal class PortfolioValuation(private val valuation: ValuationService, private val fx: FxRateProvider?,
    private val budget: Double?, private val monotonic: () -> Double, private val clock: () -> QuoteTime) {
    private data class Key(val kind: AssetKind, val identity: String)
    fun get(repository: PortfolioRepository, displayCurrency: String?, period: String, timezone: String?, onUpdate: ((JsonObject) -> Unit)?): JsonObject {
        val display = validateDisplayCurrency(displayCurrency); val now = clock()
        require(now.offset != null) { "portfolio clock must be timezone-aware" }
        portfolioPeriodBounds(now, period, timezone)
        val deadline = budget?.let { monotonic() + it }
        val raw = repository.loadPortfolio()
        val base = PortfolioQueryService(object : PortfolioRepository { override fun loadPortfolio() = raw }).getHoldings()
        val configured = raw["configured_currencies"]?.jsonArray.orEmpty().map { portfolioCurrencyUpper(it) }.toSet()
        val requests = linkedMapOf<Key, AssetRef>()
        val kinds = linkedMapOf<Pair<String, String>, Pair<AssetKind?, Key?>>()
        for (accountValue in base.getValue("accounts").jsonArray) {
            val account = accountValue.jsonObject; val name = account.getValue("name").jsonPrimitive.content
            val allowed = raw["base_currencies"]?.jsonObject?.get(name)?.jsonArray.orEmpty().map { portfolioCurrencyUpper(it) }.toSet().ifEmpty { configured }
            for (value in account.getValue("positions").jsonArray) {
                val position = value.jsonObject; val ticker = position.getValue("ticker").jsonPrimitive.content
                val kind = inferAssetKind(ticker, allowed, configured - allowed)
                val key = kind?.takeUnless { it == AssetKind.CASH }?.let { Key(it, pythonCashLower(valuationStrip(ticker))) }
                if (key != null) requests.putIfAbsent(key, AssetRef(ticker, kind, position.getValue("shares").jsonPrimitive.content))
                kinds[name to ticker] = kind to key
            }
        }
        val context = PortfolioPeriodContext(raw, now, period, timezone)
        val historyRequests = context.historyRequests(base)
        var history = if (onUpdate == null) PortfolioHistorical(valuation, if (deadline == null || monotonic() < deadline) historyRequests else emptyList(), deadline, monotonic) else null
        val quotes = quoteRequests(requests, deadline)
        val usdCache = mutableMapOf<String, BigDecimal?>()
        val accounts = base.getValue("accounts").jsonArray.map { accountValue ->
            val account = accountValue.jsonObject; val name = account.getValue("name").jsonPrimitive.content
            JsonObject(account + ("positions" to JsonArray(account.getValue("positions").jsonArray.map { value ->
                val position = value.jsonObject; val ticker = position.getValue("ticker").jsonPrimitive.content
                val cost = position.getValue("total_cost").jsonPrimitive.content.toBigDecimal()
                val costCurrency = position.getValue("cost_currency").jsonPrimitive.content
                val (kind, key) = kinds.getValue(name to ticker)
                val quote = when (kind) {
                    null -> QuoteResult(ticker, AssetKind.SECURITY, QuoteStatus.UNSUPPORTED, reason = "unsupported_identity")
                    AssetKind.CASH -> QuoteResult(ticker, kind, QuoteStatus.COMPLETE, unitPrice = "1", quoteCurrency = valuationUpper(ticker))
                    else -> quotes.getValue(key!!)
                }
                val price = if (quote.status in setOf(QuoteStatus.COMPLETE, QuoteStatus.STALE)) quote.unitPrice else null
                val marketText = if (kind == AssetKind.CASH) position.getValue("shares").jsonPrimitive.content else price?.let { FinanceDecimal.parse(it).multiply(FinanceDecimal.parse(position.getValue("shares")), 28).fixed() }
                val market = marketText?.toBigDecimal(); val negativeMarket = marketText?.startsWith("-") == true
                val profit = if (market != null && costCurrency.isNotEmpty() && !quote.quoteCurrency.isNullOrEmpty() && valuationUpper(costCurrency) == valuationUpper(quote.quoteCurrency)) market.subtract(cost, portfolioMath) else null
                val converted = display?.let { convert(market, quote.quoteCurrency, it, deadline) }
                val usd = if (display == "USD" && converted?.value != null) converted.value else if (market == null || quote.quoteCurrency.isNullOrEmpty()) null else {
                    val currency = valuationUpper(quote.quoteCurrency)
                    if (currency == "USD") market else {
                        if (currency !in usdCache) usdCache[currency] = rate(currency, "USD", deadline)
                        usdCache[currency]?.let { market.multiply(it, portfolioMath) }
                    }
                }
                JsonObject(position + buildJsonObject {
                    put("current_price", price?.let { JsonPrimitive(FinanceDecimal.parse(it).fixed()) } ?: JsonNull)
                    put("market_value", portfolioMoney(market, negativeMarket)); put("profit", portfolioMoney(profit, negativeMarket && !position.getValue("total_cost").jsonPrimitive.content.startsWith("-") && cost.signum() == 0))
                    put("quote_status", quote.status.value); put("quote_reason", quote.reason)
                    put("quote_currency", quote.quoteCurrency?.let(::JsonPrimitive) ?: JsonNull)
                    put("quote_observed_at", quote.observedAt?.let { JsonPrimitive(portfolioTimeText(it)) } ?: JsonNull)
                    put("quote_session", quote.quoteSession?.let(::JsonPrimitive) ?: JsonNull)
                    put("display_currency", display?.let(::JsonPrimitive) ?: JsonNull); put("display_market_value", portfolioMoney(converted?.value, negativeMarket))
                    put("fx_rate", portfolioMoney(converted?.rate)); put("fx_status", converted?.status?.let(::JsonPrimitive) ?: JsonNull); put("fx_reason", converted?.reason?.let(::JsonPrimitive) ?: JsonNull)
                    put("usd_market_value", portfolioMoney(usd, negativeMarket))
                })
            })))
        }
        val current = JsonObject(base + ("accounts" to JsonArray(accounts)) + totals(accounts, display))
        if (onUpdate != null) try { onUpdate(current) } catch (_: Exception) { /* Original callback failures do not abort the query. */ }
        if (history == null) history = PortfolioHistorical(valuation, if (deadline == null || monotonic() < deadline) historyRequests else emptyList(), deadline, monotonic)
        return context.finish(current, history, deadline, monotonic)
    }
    private data class Conversion(val value: BigDecimal?, val rate: BigDecimal?, val status: String, val reason: String)
    private fun convert(value: BigDecimal?, currency: String?, display: String, deadline: Double?): Conversion {
        if (value == null || currency.isNullOrEmpty()) return Conversion(null, null, "not_applicable", "currency_unspecified")
        val base = valuationUpper(currency)
        if (base == display) return Conversion(value, BigDecimal.ONE, "complete", "ok")
        val rate = rate(base, display, deadline) ?: return Conversion(null, null, "partial", "fx_unavailable")
        return Conversion(value.multiply(rate, portfolioMath), rate, "complete", "ok")
    }
    private fun rate(base: String, quote: String, deadline: Double?): BigDecimal? {
        if (fx == null) return null
        val text = if (deadline == null) try { fx.getMid(base, quote) } catch (_: ValuationError) { null } else {
            if (deadline <= monotonic()) return null
            val queue = LinkedBlockingQueue<Pair<String?, Unit>>()
            portfolioThread { val result = try { fx.getMid(base, quote) } catch (_: Exception) { null }; queue.put(result to Unit) }
            portfolioPoll(queue, deadline, monotonic)?.first
        } ?: return null
        val value = try { InvestmentDecimal.exact(text, "fx_rate").number } catch (_: IllegalArgumentException) { return null }
        return value.takeIf { it.signum() > 0 }
    }
    private fun quoteRequests(requests: Map<Key, AssetRef>, deadline: Double?): Map<Key, QuoteResult> {
        val groups = requests.entries.groupBy { it.key.kind }.values
        val queue = LinkedBlockingQueue<Pair<List<Map.Entry<Key, AssetRef>>, QuoteBatchResult?>>()
        var count = 0
        for (group in groups) {
            if (deadline != null && deadline <= monotonic()) break
            count++
            portfolioThread {
                val result = try { valuation.quoteMany(group.map { it.value }, deadline?.let { maxOf(it - monotonic(), 0.0) }) } catch (_: Exception) { null }
                queue.put(group to result)
            }
        }
        val results = mutableMapOf<Key, QuoteResult>()
        repeat(count) {
            val completed = portfolioPoll(queue, deadline, monotonic) ?: return@repeat
            val (group, batch) = completed
            if (batch == null) for ((key, ref) in group) results[key] = QuoteResult(ref.identity, ref.kind, QuoteStatus.PARTIAL, quantity = ref.quantity, reason = "provider_error")
            else { check(batch.results.size == group.size); group.zip(batch.results).forEach { (entry, result) -> results[entry.key] = result } }
        }
        for ((key, ref) in requests) results.putIfAbsent(key, QuoteResult(ref.identity, ref.kind, QuoteStatus.PARTIAL, quantity = ref.quantity, reason = "query_deadline_exceeded"))
        return results
    }
    private fun totals(accounts: List<JsonObject>, display: String?): JsonObject {
        val values = mutableListOf<BigDecimal>(); val profits = mutableListOf<BigDecimal>(); val currencies = mutableSetOf<String>()
        var complete = true
        for (account in accounts) for (raw in account.getValue("positions").jsonArray) {
            val p = raw.jsonObject
            val value = p[if (display != null) "display_market_value" else "market_value"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.toBigDecimal()
            if (value == null) { complete = false; break }
            values += value; currencies += portfolioText(p[if (display != null) "display_currency" else "quote_currency"].takeIf(::pythonTruthy) ?: p["cost_currency"])
            val profit = p["profit"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.toBigDecimal()
            if (!p.getValue("is_cash").jsonPrimitive.boolean && profit != null) {
                val mv = p["market_value"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.toBigDecimal()
                profits += if (display != null && mv != null && mv.signum() != 0) profit.multiply(value.divide(mv, portfolioMath), portfolioMath) else profit
            }
        }
        val market = if (complete && values.isNotEmpty() && currencies.size <= 1) values.fold(BigDecimal.ZERO) { a, b -> a.add(b, portfolioMath) } else null
        val profit = market?.let { profits.fold(BigDecimal.ZERO) { a, b -> a.add(b, portfolioMath) } }
        return buildJsonObject { put("total_market_value", portfolioMoney(market)); put("total_profit", portfolioMoney(profit)); put("total_profit_rate", portfolioMoney(if (market != null && market.signum() != 0) profit!!.divide(market, portfolioMath) else null, market != null && market.signum() < 0 && profit?.signum() == 0)) }
    }
}
