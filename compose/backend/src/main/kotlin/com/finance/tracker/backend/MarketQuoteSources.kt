package com.finance.tracker.backend

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.serialization.json.*

internal interface TimedQuoteProvider : QuoteProvider {
    fun rawQuote(identity: String, kind: AssetKind, timeout: Double?): ProviderTick?
    override fun rawQuote(identity: String, kind: AssetKind) = rawQuote(identity, kind, null)
}
internal fun marketObject(value: JsonElement?): JsonObject = if (value == null || !pythonTruthy(value)) buildJsonObject {} else value.jsonObject
internal fun marketDecimal(value: Any?): String? {
    val text = when (value) {
        null, JsonNull -> return null
        is JsonElement -> pythonString(value)
        is String -> value
        is BigDecimal -> value.toString()
        is Double -> pythonFloatText(value)
        is Float -> pythonFloatText(value.toDouble())
        is Byte, is Short, is Int, is Long, is java.math.BigInteger -> value.toString()
        else -> return null
    }
    val r = try { valuationFiniteRepresentation(text) } catch (_: ArithmeticException) { null } ?: return null
    return (if (r.negative) "-" else "") + r.coefficient + "E" + r.exponent
}
internal fun quotedMarketDecimal(value: JsonElement?): String? {
    val text = if (value == null || !pythonTruthy(value)) "" else valuationStrip(pythonString(value)).replace(",", "")
    val normalized = buildString { text.codePoints().forEach { code ->
        val zero = pythonIntegerDigitStarts.firstOrNull { code in it until it + 10 }
        if (zero != null) append(('0'.code + code - zero).toChar()) else appendCodePoint(code)
    } }
    return Regex("[-+]?[0-9]+(?:\\.[0-9]+)?").find(normalized)?.value?.let(::marketDecimal)
}
internal fun marketEpoch(value: JsonElement?): QuoteTime? = try {
    if (value == null || value == JsonNull) null else {
        val seconds = pythonIntegerValue(value).longValueExact()
        val instant = Instant.ofEpochSecond(seconds)
        if (instant.atOffset(ZoneOffset.UTC).year !in 1..9999) null else QuoteTime.from(instant)
    }
} catch (_: Exception) { null }
internal fun currencyForSecuritySymbol(symbol: String): String {
    val upper = valuationUpper(symbol)
    return when {
        upper.endsWith(".HK") -> "HKD"
        upper.endsWith(".SS") || upper.endsWith(".SZ") -> "CNY"
        upper.endsWith(".TW") -> "TWD"
        upper.endsWith(".T") -> "JPY"
        upper.endsWith(".KS") || upper.endsWith(".KQ") -> "KRW"
        else -> "USD"
    }
}
internal fun securityMarketZone(symbol: String): ZoneId = when {
    valuationUpper(symbol).endsWith(".HK") -> ZoneId.of("Asia/Hong_Kong")
    valuationUpper(symbol).let { it.endsWith(".SS") || it.endsWith(".SZ") } -> ZoneId.of("Asia/Shanghai")
    valuationUpper(symbol).let { it.endsWith(".T") || it.endsWith(".JP") } -> ZoneId.of("Asia/Tokyo")
    valuationUpper(symbol).let { it.endsWith(".KS") || it.endsWith(".KQ") } -> ZoneId.of("Asia/Seoul")
    valuationUpper(symbol).let { it.endsWith(".TW") || it.endsWith(".TWO") } -> ZoneId.of("Asia/Taipei")
    else -> ZoneId.of("America/New_York")
}
internal fun quoteSessionForSymbol(symbol: String, at: QuoteTime?): String {
    if (at == null) return "unknown"
    val upper = valuationUpper(symbol)
    val regular: List<Pair<Int, Int>>; val post: Pair<Int, Int>?; val us: Boolean
    when {
        upper.endsWith(".HK") -> { regular = listOf(570 to 720, 780 to 960); post = 960 to 1200; us = false }
        upper.endsWith(".SS") || upper.endsWith(".SZ") -> { regular = listOf(570 to 690, 780 to 900); post = null; us = false }
        upper.endsWith(".T") || upper.endsWith(".JP") -> { regular = listOf(540 to 690, 750 to 900); post = null; us = false }
        upper.endsWith(".KS") || upper.endsWith(".KQ") -> { regular = listOf(540 to 930); post = 940 to 1080; us = false }
        upper.endsWith(".TW") || upper.endsWith(".TWO") -> { regular = listOf(540 to 810); post = 840 to 870; us = false }
        '.' in upper -> return "unknown"
        else -> { regular = listOf(570 to 960); post = 960 to 1200; us = true }
    }
    val local = at.instant().atZone(securityMarketZone(symbol)); val minute = local.hour * 60 + local.minute
    fun within(window: Pair<Int, Int>) = minute >= window.first && minute < window.second
    return when {
        us && minute in 240 until 570 -> "pre_market"
        regular.any(::within) -> "regular"
        post != null && within(post) -> "post_market"
        us && (minute >= 1200 || minute < 240) -> "overnight"
        else -> "unknown"
    }
}
internal fun securitySymbol(identity: String) = ledgerSecuritySymbol(identity) ?: throw UnsupportedQuote(identity)
internal fun usSecuritySymbol(identity: String): String? {
    val raw = valuationStrip(identity); val lower = pythonCashLower(raw)
    if (raw.isEmpty() || '.' in lower && !lower.endsWith(".us")) return null
    return securitySymbol(raw).takeIf { '.' !in it }
}
private fun fallbackTimeout(value: Double?) = value?.takeUnless { it == 0.0 } ?: 2.5

internal class YahooChartQuoteProvider(private val fetcher: QuoteJsonFetcher = JvmQuoteHttpFetcher()) : TimedQuoteProvider {
    override fun rawQuote(identity: String, kind: AssetKind, timeout: Double?): ProviderTick? {
        if (kind != AssetKind.SECURITY) throw UnsupportedQuote(identity)
        val symbol = securitySymbol(identity)
        return try {
            val payload = fetcher.fetch("https://query1.finance.yahoo.com/v8/finance/chart/" + marketUrlQuote(symbol) + "?range=1d&interval=1m", fallbackTimeout(timeout)).jsonObject
            val results = marketObject(payload["chart"])["result"]
            val result = if (results == null || !pythonTruthy(results)) buildJsonObject {} else marketObject(results.jsonArray.first())
            val meta = marketObject(result["meta"])
            val candidates = listOf(Triple("regularMarketTime", "regularMarketPrice", "regular"),
                Triple("preMarketTime", "preMarketPrice", "pre_market"), Triple("postMarketTime", "postMarketPrice", "post_market"))
                .mapNotNull { (time, price, session) -> marketEpoch(meta[time])?.let { at -> marketDecimal(meta[price])?.let { Triple(at, it, session) } } }
            val latest = candidates.maxByOrNull { it.first.instant() } ?: return null
            val currency = valuationUpper(meta["currency"]?.takeIf(::pythonTruthy)?.let(::pythonString) ?: currencyForSecuritySymbol(symbol))
            if (currency.isEmpty()) null else ProviderTick(latest.second, currency, latest.first, "yahoo-chart", latest.third)
        } catch (_: Exception) { null }
    }
}
internal class CboeQuoteProvider(private val fetcher: QuoteJsonFetcher = JvmQuoteHttpFetcher()) : TimedQuoteProvider {
    override fun rawQuote(identity: String, kind: AssetKind, timeout: Double?): ProviderTick? {
        if (kind != AssetKind.SECURITY) throw UnsupportedQuote(identity)
        val symbol = usSecuritySymbol(identity) ?: return null
        return try {
            val payload = fetcher.fetch("https://cdn.cboe.com/api/global/delayed_quotes/quotes/" + marketUrlQuote(symbol) + ".json", fallbackTimeout(timeout)).jsonObject
            val price = quotedMarketDecimal(marketObject(payload["data"])["current_price"]) ?: return null
            val text = payload["timestamp"]?.let(::pythonString) ?: "None"
            if (!Regex("[0-9]{4}-[0-9]{1,2}-[0-9]{1,2} [0-9]{1,2}:[0-9]{1,2}:[0-9]{1,2}").matches(text)) return null
            val date = LocalDateTime.parse(text, DateTimeFormatter.ofPattern("uuuu-M-d H:m:s").withResolverStyle(java.time.format.ResolverStyle.STRICT))
            if (date.year !in 1..9999) return null
            val at = QuoteTime(date, ZoneOffset.UTC)
            ProviderTick(price, "USD", at, "cboe-delayed", quoteSessionForSymbol(symbol, at))
        } catch (_: Exception) { null }
    }
}
internal class NasdaqQuoteProvider(private val fetcher: QuoteJsonFetcher = JvmQuoteHttpFetcher()) : TimedQuoteProvider {
    override fun rawQuote(identity: String, kind: AssetKind, timeout: Double?): ProviderTick? {
        if (kind != AssetKind.SECURITY) throw UnsupportedQuote(identity)
        val symbol = usSecuritySymbol(identity) ?: return null
        return try {
            val payload = fetcher.fetch("https://api.nasdaq.com/api/quote/" + marketUrlQuote(symbol) + "/info?assetclass=stocks", fallbackTimeout(timeout)).jsonObject
            val primary = marketObject(marketObject(payload["data"])["primaryData"])
            val price = quotedMarketDecimal(primary["lastSalePrice"]) ?: return null
            val rawDate = primary["lastTradeTimestamp"]?.let(::pythonString) ?: "None"
            val canonical = rawDate.map { if (it.code in pythonWhitespace) ' ' else it }.joinToString("").replace(Regex(" +"), " ")
            val date = LocalDate.parse(canonical, java.time.format.DateTimeFormatterBuilder().parseCaseInsensitive()
                .appendPattern("MMM d, uuuu").toFormatter(Locale.US).withResolverStyle(java.time.format.ResolverStyle.STRICT))
            if (date.year !in 1..9999) return null
            ProviderTick(price, "USD", QuoteTime(date.atStartOfDay(), ZoneOffset.UTC), "nasdaq-delayed", "unknown")
        } catch (_: Exception) { null }
    }
}
internal class FinnhubQuoteProvider(private val fetcher: QuoteJsonFetcher = JvmQuoteHttpFetcher(),
    private val apiKey: String = System.getenv("FT_FINNHUB_API_KEY").orEmpty()) : TimedQuoteProvider {
    override fun rawQuote(identity: String, kind: AssetKind, timeout: Double?): ProviderTick? {
        if (kind != AssetKind.SECURITY) throw UnsupportedQuote(identity)
        val symbol = usSecuritySymbol(identity) ?: return null
        if (apiKey.isEmpty()) return null
        return try {
            val payload = fetcher.fetch("https://finnhub.io/api/v1/quote?symbol=" + marketUrlQuote(symbol) + "&token=" + marketUrlQuote(apiKey), fallbackTimeout(timeout)).jsonObject
            val price = marketDecimal(payload["c"]) ?: return null
            val at = marketEpoch(payload["t"]) ?: return null
            ProviderTick(price, "USD", at, "finnhub", quoteSessionForSymbol(symbol, at))
        } catch (_: Exception) { null }
    }
}
internal class CashQuoteProvider(private val clock: () -> QuoteTime = { QuoteTime.from(Instant.now()) }) : QuoteProvider {
    override fun rawQuote(identity: String, kind: AssetKind): ProviderTick {
        if (kind != AssetKind.CASH) throw UnsupportedQuote(identity)
        return ProviderTick("1", valuationUpper(valuationStrip(identity)), clock(), "cash")
    }
}
internal class CryptoQuoteProvider(private val fetcher: QuoteJsonFetcher = JvmQuoteHttpFetcher(),
    private val clock: () -> QuoteTime = { QuoteTime.from(Instant.now()) }) : BatchQuoteProvider {
    override fun rawQuote(identity: String, kind: AssetKind): ProviderTick? {
        if (kind != AssetKind.CRYPTO) throw UnsupportedQuote(identity)
        val id = valuationCrypto[pythonCashLower(valuationStrip(identity))] ?: throw UnsupportedQuote(identity)
        val payload = try { fetcher.fetch("https://api.coingecko.com/api/v3/simple/price?ids=" + marketUrlQuote(id) + "&vs_currencies=usd", null) }
            catch (_: Exception) { return null }
        val price = marketDecimal(marketObject(payload.jsonObject[id])["usd"]) ?: return null
        return ProviderTick(price, "USD", clock(), "coingecko")
    }
    override fun rawQuoteMany(refs: List<AssetRef>, timeout: Double?): Map<String, Any?> {
        val ids = linkedMapOf<String, String>(); val results = linkedMapOf<String, Any?>()
        for (ref in refs) {
            val id = valuationCrypto[pythonCashLower(valuationStrip(ref.identity))]
            if (ref.kind != AssetKind.CRYPTO || id == null) results[ref.identity] = UnsupportedQuote(ref.identity) else ids[ref.identity] = id
        }
        if (ids.isEmpty()) return results
        val payload = try { fetcher.fetch("https://api.coingecko.com/api/v3/simple/price?ids=" + marketUrlQuote(ids.values.distinct().joinToString(",")) + "&vs_currencies=usd", timeout) }
            catch (_: Exception) { ids.keys.forEach { results[it] = IllegalStateException("provider_error") }; return results }
        for ((identity, id) in ids) {
            val price = marketDecimal(marketObject(payload.jsonObject[id])["usd"])
            results[identity] = price?.let { ProviderTick(it, "USD", clock(), "coingecko") }
        }
        return results
    }
}


private fun marketIterable(value: JsonElement): List<JsonElement> = when (value) {
    is JsonArray -> value.toList()
    is JsonObject -> value.keys.map(::JsonPrimitive)
    is JsonPrimitive -> if (value.isString) value.content.codePoints().toArray().map { JsonPrimitive(String(Character.toChars(it))) }
        else throw IllegalArgumentException("market.payload_invalid")
    else -> throw IllegalArgumentException("market.payload_invalid")
}
private fun marketLength(value: JsonElement): Int = when (value) {
    is JsonArray -> value.size
    is JsonObject -> value.size
    is JsonPrimitive -> if (value.isString) value.content.codePointCount(0, value.content.length) else throw IllegalArgumentException("market.payload_invalid")
    else -> throw IllegalArgumentException("market.payload_invalid")
}
private fun marketIndex(value: JsonElement, index: Int): JsonElement = when (value) {
    is JsonArray -> value[index]
    is JsonPrimitive -> if (value.isString) {
        val points = value.content.codePoints().toArray(); JsonPrimitive(String(Character.toChars(points[index])))
    } else throw IllegalArgumentException("market.payload_invalid")
    else -> throw IllegalArgumentException("market.payload_invalid")
}
internal class PredictionMarketQuoteProvider(private val fetcher: QuoteJsonFetcher = JvmQuoteHttpFetcher(),
    private val clock: () -> QuoteTime = { QuoteTime.from(Instant.now()) }, maxInFlight: Int = 4,
    private val monotonic: () -> Double = { System.nanoTime() / 1_000_000_000.0 }) : TimedQuoteProvider, BatchQuoteProvider {
    private val maximum = maxOf(maxInFlight, 1)
    override fun rawQuote(identity: String, kind: AssetKind, timeout: Double?): ProviderTick? {
        if (kind != AssetKind.PREDICTION_MARKET) throw UnsupportedQuote(identity)
        val (slug, side) = parsePredictionMarketIdentity(identity) ?: throw UnsupportedQuote(identity)
        val deadline = timeout?.let { monotonic() + maxOf(it, 0.0) }
        val market = loadMarket(slug, deadline) ?: return null
        var outcomes = market["outcomes"] ?: JsonArray(emptyList())
        var prices = market["outcomePrices"] ?: JsonArray(emptyList())
        if (outcomes is JsonPrimitive && outcomes.isString) outcomes = categoryDeleteBody(outcomes.content)
        if (prices is JsonPrimitive && prices.isString) prices = categoryDeleteBody(prices.content)
        val normalized = marketIterable(outcomes).map { pythonCashLower(pythonString(it)) }
        val found = normalized.indexOf(side); val index = if (found >= 0) found else if (side == "yes") 0 else 1
        if (index >= marketLength(prices)) return null
        val price = marketDecimal(marketIndex(prices, index)) ?: return null
        return ProviderTick(price, "USD", clock(), "polymarket")
    }
    private fun loadMarket(slug: String, deadline: Double?): JsonObject? {
        fun remaining() = deadline?.let { maxOf(it - monotonic(), 0.0) }
        val payload = try {
            val timeout = remaining()
            if (timeout != null && timeout <= 0) return null
            fetcher.fetch("https://gamma-api.polymarket.com/markets?slug=" + marketUrlQuote(slug), timeout)
        } catch (_: Exception) { JsonNull }
        val markets: JsonElement = when (payload) {
            is JsonArray -> payload
            is JsonObject -> payload["data"]?.takeIf(::pythonTruthy) ?: payload["markets"]?.takeIf(::pythonTruthy) ?: JsonArray(listOf(payload))
            else -> JsonArray(emptyList())
        }
        marketIterable(markets).firstOrNull { it is JsonObject && it["slug"] == JsonPrimitive(slug) }?.let { return it.jsonObject }
        val timeout = remaining()
        if (timeout != null && timeout <= 0) return null
        val search = try {
            fetcher.fetch("https://gamma-api.polymarket.com/public-search?q=" + marketUrlQuote(slug.replace('-', ' ')), timeout)
        } catch (_: Exception) { return null }
        val events = if (search is JsonObject) search["events"]?.takeIf(::pythonTruthy) ?: search["data"]?.takeIf(::pythonTruthy) ?: JsonArray(emptyList())
            else search.takeIf(::pythonTruthy) ?: JsonArray(emptyList())
        for (event in (events as? JsonArray).orEmpty()) {
            if (event !is JsonObject) continue
            for (candidate in marketIterable(event["markets"] ?: JsonArray(emptyList())))
                if (candidate is JsonObject && candidate["slug"] == JsonPrimitive(slug)) return candidate
        }
        return null
    }
    private data class Completion(val index: Int, val ref: AssetRef, val value: Any?)
    override fun rawQuoteMany(refs: List<AssetRef>, timeout: Double?): Map<String, Any?> {
        val deadline = timeout?.let { monotonic() + maxOf(it, 0.0) }
        val pending = java.util.ArrayDeque(refs.withIndex().toList())
        val active = linkedMapOf<Int, AssetRef>(); val results = linkedMapOf<String, Any?>()
        val completed = LinkedBlockingQueue<Completion>()
        while (pending.isNotEmpty() || active.isNotEmpty()) {
            while (pending.isNotEmpty() && active.size < maximum) {
                if (deadline != null && monotonic() >= deadline) break
                val (index, ref) = pending.removeFirst()
                if (ref.kind != AssetKind.PREDICTION_MARKET) { results[ref.identity] = UnsupportedQuote(ref.identity); continue }
                val remaining = deadline?.let { maxOf(it - monotonic(), 0.0) }
                active[index] = ref
                marketDaemon("market-prediction") {
                    val value = try { rawQuote(ref.identity, ref.kind, remaining) } catch (failure: Exception) { failure }
                    completed.offer(Completion(index, ref, value))
                }
            }
            if (active.isEmpty()) break
            val remaining = deadline?.let { maxOf(it - monotonic(), 0.0) }
            if (remaining != null && remaining <= 0) break
            val next = marketPoll(completed, remaining) ?: break
            active.remove(next.index); results[next.ref.identity] = next.value
        }
        for ((_, ref) in pending) if (ref.identity !in results) results[ref.identity] = null
        for (ref in active.values) if (ref.identity !in results) results[ref.identity] = null
        return results
    }
}

internal class CompositeQuoteProvider(
    private val security: QuoteProvider = SecurityQuoteProvider(),
    private val crypto: QuoteProvider = CryptoQuoteProvider(),
    private val predictionMarket: QuoteProvider = PredictionMarketQuoteProvider(),
    private val cash: QuoteProvider = CashQuoteProvider()) : BatchQuoteProvider, HistoricalQuoteProvider {
    private fun provider(kind: AssetKind): QuoteProvider = when (kind) {
        AssetKind.SECURITY -> security; AssetKind.CRYPTO -> crypto; AssetKind.PREDICTION_MARKET -> predictionMarket; AssetKind.CASH -> cash
    }
    override fun rawQuote(identity: String, kind: AssetKind) = provider(kind).rawQuote(identity, kind)
    override fun rawQuoteAt(identity: String, kind: AssetKind, at: QuoteTime): ProviderTick? {
        if (kind == AssetKind.CASH) return cash.rawQuote(identity, kind)
        val selected = provider(kind)
        if (selected !is HistoricalQuoteProvider) throw UnsupportedQuote(identity)
        return selected.rawQuoteAt(identity, kind, at)
    }
    override fun rawQuoteMany(refs: List<AssetRef>, timeout: Double?): Map<String, Any?> {
        val results = linkedMapOf<String, Any?>()
        for ((kind, group) in refs.groupBy { it.kind }) {
            val selected = provider(kind)
            if (selected is BatchQuoteProvider) results.putAll(selected.rawQuoteMany(group, timeout))
            else for (ref in group) results[ref.identity] = try { selected.rawQuote(ref.identity, ref.kind) } catch (failure: Exception) { failure }
        }
        return results
    }
}
internal class MarketDataProvider(private val composite: QuoteProvider = CompositeQuoteProvider()) {
    fun getPrices(tickers: List<String>, quoteCurrency: String): Map<String, String> {
        val service = ValuationService(composite); val prices = linkedMapOf<String, String>()
        for (ticker in tickers) {
            val kind = inferAssetKind(ticker) ?: AssetKind.SECURITY
            val result = service.quote(AssetRef(ticker, kind))
            if (result.status in setOf(QuoteStatus.COMPLETE, QuoteStatus.STALE) && result.unitPrice != null) prices[ticker] = result.unitPrice
        }
        return prices
    }
}
internal fun fetchMarketPrices(tickers: List<String>): Map<String, String> = MarketDataProvider().getPrices(tickers, "USD")

