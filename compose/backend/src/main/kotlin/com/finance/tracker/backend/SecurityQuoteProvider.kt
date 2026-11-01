package com.finance.tracker.backend

import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

internal data class SecurityClosePoint(val price: JsonElement?, val observedAt: QuoteTime?)
internal data class SecurityCloseFrame(val columns: Map<String, List<SecurityClosePoint>>, val flat: Boolean = false)
internal data class SecurityDownloadRequest(val symbols: List<String>, val range: String? = "1d",
    val start: LocalDate? = null, val end: LocalDate? = null, val interval: String = "5m",
    val timeout: Double? = 4.0, val progress: Boolean = false, val autoAdjust: Boolean = false, val threads: Boolean = false)
internal fun interface SecurityDownloader { fun download(request: SecurityDownloadRequest): SecurityCloseFrame? }

internal fun securityDownloadedTicks(symbols: List<String>, frame: SecurityCloseFrame?): Map<String, ProviderTick?> {
    fun tick(symbol: String, points: List<SecurityClosePoint>, flat: Boolean): ProviderTick? {
        val selected = if (flat) points.lastOrNull() else points.lastOrNull { point ->
            point.price != null && point.price != JsonNull && !(point.price is JsonPrimitive && !point.price.isString &&
                point.price.content.toDoubleOrNull()?.isNaN() == true)
        }
        if (selected == null) return null
        val price = marketDecimal(selected.price) ?: return null
        val at = selected.observedAt?.takeIf { it.offset != null }?.let { QuoteTime.from(it.instant()) }
        return ProviderTick(price, currencyForSecuritySymbol(symbol), at, "yfinance", quoteSessionForSymbol(symbol, at))
    }
    if (frame == null || frame.columns.values.all { it.isEmpty() }) return symbols.associateWith { null }
    if (frame.flat) return mapOf(symbols.first() to tick(symbols.first(), frame.columns.values.firstOrNull().orEmpty(), true))
    return symbols.associateWith { symbol -> tick(symbol, frame.columns[symbol].orEmpty(), false) }
}
internal fun historicalSecurityPrice(symbol: String, at: QuoteTime, downloader: SecurityDownloader): String? = try {
    val frame = downloader.download(SecurityDownloadRequest(listOf(symbol), range = null,
        start = at.local.toLocalDate().minusDays(3), end = at.local.toLocalDate().plusDays(1), interval = "1h"))
    val points = frame?.columns?.get(symbol) ?: frame?.columns?.values?.firstOrNull()
    if (points.isNullOrEmpty() || points.any { it.observedAt == null || it.observedAt.offset == null }) null else {
        val candidates = points.filter { it.observedAt!!.instant() <= at.instant() }
        marketDecimal((candidates.lastOrNull() ?: points.first()).price)
    }
} catch (_: Exception) { null }

internal class YahooSecurityDownloader(private val fetcher: QuoteJsonFetcher = JvmQuoteHttpFetcher()) : SecurityDownloader {
    override fun download(request: SecurityDownloadRequest): SecurityCloseFrame {
        require(!request.autoAdjust && !request.progress && !request.threads)
        val columns = linkedMapOf<String, List<SecurityClosePoint>>()
        for (symbol in request.symbols) {
            val params = if (request.start != null && request.end != null) {
                val zone = securityMarketZone(symbol)
                "period1=" + request.start.atStartOfDay(zone).toEpochSecond() + "&period2=" + request.end.atStartOfDay(zone).toEpochSecond()
            } else "range=" + marketUrlQuote(request.range ?: "1d")
            val url = "https://query2.finance.yahoo.com/v8/finance/chart/" + marketUrlQuote(symbol) + "?" +
                params + "&interval=" + marketUrlQuote(request.interval) + "&includePrePost=false&events=div%2Csplits%2CcapitalGains"
            try {
                val payload = fetcher.fetch(url, request.timeout ?: 4.0).jsonObject
                val chart = marketObject(payload["chart"])
                if (chart["error"]?.let(::pythonTruthy) == true || "status_code" in payload) continue
                val result = (chart["result"] as? JsonArray)?.firstOrNull()?.jsonObject ?: continue
                columns[symbol] = securityChartPoints(result, request, symbol)
            } catch (_: Exception) { }
        }
        return SecurityCloseFrame(columns)
    }
}

private data class SecurityChartBar(val at: QuoteTime, val values: List<Double>) {
    fun point(): SecurityClosePoint = SecurityClosePoint(if (values[3].isNaN()) JsonNull else JsonPrimitive(pythonFloatText(values[3])), at)
    fun merge(last: SecurityChartBar): SecurityChartBar = copy(values = listOf(
        values[0].takeUnless { it.isNaN() } ?: last.values[0],
        listOf(values[1], last.values[1]).filterNot { it.isNaN() }.maxOrNull() ?: Double.NaN,
        listOf(values[2], last.values[2]).filterNot { it.isNaN() }.minOrNull() ?: Double.NaN,
        last.values[3], last.values[4], values[5] + last.values[5]))
}
private fun securityChartPoints(result: JsonObject, request: SecurityDownloadRequest, symbol: String): List<SecurityClosePoint> {
    val meta = result.getValue("meta").jsonObject
    require(listOf("instrumentType", "currency", "exchangeTimezoneName").all { it in meta })
    val zone = java.time.ZoneId.of(meta.getValue("exchangeTimezoneName").jsonPrimitive.content)
    request.range?.let { range -> require(meta.getValue("validRanges").jsonArray.any { it.jsonPrimitive.content == range }) }
    val times = result.getValue("timestamp").jsonArray
    val quote = result.getValue("indicators").jsonObject.getValue("quote").jsonArray.first().jsonObject
    val close = quote.getValue("close").jsonArray
    require(times.size == close.size)
    val intervalSeconds = when (request.interval) { "5m" -> 300L; "1h" -> 3600L; else -> error("unsupported security interval") }
    val periods = mutableListOf<Pair<Instant, Instant>>()
    fun collectPeriods(value: JsonElement) {
        when (value) {
            is JsonArray -> value.forEach(::collectPeriods)
            is JsonObject -> {
                val start = value["start"]?.let(::marketEpoch)?.instant()
                val end = value["end"]?.let(::marketEpoch)?.instant()
                if (start != null && end != null) periods.add(start to end)
                else value["regular"]?.let(::collectPeriods)
            }
            else -> error("invalid trading period")
        }
    }
    collectPeriods(meta.getValue("tradingPeriods"))
    val adjusted = result.getValue("indicators").jsonObject["adjclose"]?.jsonArray?.firstOrNull()?.jsonObject?.get("adjclose")?.jsonArray ?: close
    val fields = listOf(quote.getValue("open").jsonArray, quote.getValue("high").jsonArray, quote.getValue("low").jsonArray,
        close, adjusted, quote.getValue("volume").jsonArray)
    require(fields.all { it.size == times.size })
    val points = times.indices.map { index ->
        val values = fields.map { values -> values[index].let { if (it == JsonNull) Double.NaN else it.jsonPrimitive.content.toDouble() } }.toMutableList()
        values[5] = values[5].takeUnless { it.isNaN() }?.toLong()?.toDouble() ?: 0.0
        SecurityChartBar(requireNotNull(marketEpoch(times[index])), values)
    }.sortedBy { it.at.instant() }.toMutableList()
    request.end?.let { end ->
        val boundary = end.atStartOfDay(securityMarketZone(symbol)).toInstant()
        if (points.lastOrNull()?.at?.instant()?.let { it >= boundary } == true) points.removeLast()
    }
    points.removeAll { point ->
        val at = point.at.instant()
        val period = periods.firstOrNull { it.first.atZone(zone).toLocalDate() == at.atZone(zone).toLocalDate() }
        period != null && (at >= period.second || at.plusSeconds(intervalSeconds) <= period.first)
    }
    if (points.size > 1) {
        val previous = points[points.lastIndex - 1]; val last = points.last()
        val firstTime = previous.at.instant(); val lastTime = last.at.instant()
        if (firstTime != lastTime && lastTime.epochSecond - firstTime.epochSecond < intervalSeconds) {
            points[points.lastIndex - 1] = previous.merge(last)
            points.removeLast()
        }
    }
    return points.distinctBy { it.at }.filter { bar -> bar.values.any { !it.isNaN() && it != 0.0 } }.map { it.point() }
}

internal fun marketDaemon(name: String, block: () -> Unit) = Thread(block, name).apply { isDaemon = true; start() }
internal fun <T> marketPoll(queue: LinkedBlockingQueue<T>, remaining: Double?): T? =
    if (remaining == null) queue.take() else queue.poll((remaining.coerceAtLeast(0.0) * 1_000_000_000).toLong(), TimeUnit.NANOSECONDS)

internal class SecurityQuoteProvider(private val download: ((Any, Double?) -> Any?)? = null,
    private val downloadHistory: ((String, QuoteTime) -> Any?)? = null,
    fallbacks: List<TimedQuoteProvider>? = null, private val downloader: SecurityDownloader = YahooSecurityDownloader(),
    primaryTimeoutSeconds: Double = 0.25, fallbackTimeoutSeconds: Double = 2.5, maxFallbackInFlight: Int = 8,
    private val clock: () -> QuoteTime = { QuoteTime.from(Instant.now()) },
    private val monotonic: () -> Double = { System.nanoTime() / 1_000_000_000.0 }) : BatchQuoteProvider, HistoricalQuoteProvider {
    private val sources = fallbacks ?: if (download != null) emptyList() else
        listOf(YahooChartQuoteProvider(), CboeQuoteProvider(), NasdaqQuoteProvider(), FinnhubQuoteProvider())
    private val primaryTimeout = maxOf(primaryTimeoutSeconds, 0.1)
    private val fallbackTimeout = maxOf(fallbackTimeoutSeconds, 0.1)
    private val maxFallback = maxOf(maxFallbackInFlight, 1)

    override fun rawQuote(identity: String, kind: AssetKind): ProviderTick? {
        if (kind != AssetKind.SECURITY) throw UnsupportedQuote(identity)
        val symbol = securitySymbol(identity)
        if (download == null) return securityDownloadedTicks(listOf(symbol), try {
            downloader.download(SecurityDownloadRequest(listOf(symbol)))
        } catch (_: Exception) { null })[symbol]
        val outcome = download.invoke(symbol, null)
        if (outcome is ProviderTick) return outcome
        return marketDecimal(outcome)?.let { ProviderTick(it, currencyForSecuritySymbol(symbol), null, "yfinance", "unknown") }
    }
    override fun rawQuoteAt(identity: String, kind: AssetKind, at: QuoteTime): ProviderTick? {
        if (kind != AssetKind.SECURITY) throw UnsupportedQuote(identity)
        val symbol = securitySymbol(identity)
        if (at.offset == null) throw IllegalArgumentException("historical quote boundary must be timezone-aware")
        val value = if (downloadHistory != null) downloadHistory.invoke(symbol, at) else historicalSecurityPrice(symbol, at, downloader)
        return marketDecimal(value)?.let { ProviderTick(it, currencyForSecuritySymbol(symbol), at, "yfinance-history") }
    }
    private fun downloadMany(symbols: List<String>, timeout: Double?): Map<String, ProviderTick?> {
        if (download == null) return securityDownloadedTicks(symbols, downloader.download(SecurityDownloadRequest(symbols, timeout = timeout)))
        val payload = download.invoke(symbols, timeout)
        fun coerce(symbol: String, value: Any?): ProviderTick? = if (value is ProviderTick) value else
            marketDecimal(value)?.let { ProviderTick(it, currencyForSecuritySymbol(symbol), null, "yfinance", "unknown") }
        if (payload is Map<*, *>) return symbols.associateWith { coerce(it, payload[it]) }
        if (symbols.size == 1) return mapOf(symbols.single() to coerce(symbols.single(), payload))
        return symbols.associateWith { null }
    }
    private data class DownloadOutcome(val values: Map<String, ProviderTick?>? = null, val failure: Exception? = null)
    private fun boundedDownload(symbols: List<String>, timeout: Double?): Map<String, ProviderTick?> {
        if (timeout == null) return downloadMany(symbols, null)
        val complete = LinkedBlockingQueue<DownloadOutcome>(1)
        marketDaemon("market-primary") {
            val result = try { DownloadOutcome(values = downloadMany(symbols, timeout)) } catch (failure: Exception) { DownloadOutcome(failure = failure) }
            complete.offer(result)
        }
        val result = marketPoll(complete, maxOf(timeout, 0.0)) ?: throw java.util.concurrent.TimeoutException("primary_quote_timeout")
        result.failure?.let { throw it }
        return result.values!!
    }
    override fun rawQuoteMany(refs: List<AssetRef>, timeout: Double?): Map<String, Any?> {
        val symbols = linkedMapOf<String, String>(); val results = linkedMapOf<String, Any?>()
        for (ref in refs) {
            if (ref.kind != AssetKind.SECURITY) { results[ref.identity] = UnsupportedQuote(ref.identity); continue }
            try { symbols[ref.identity] = securitySymbol(ref.identity) } catch (failure: UnsupportedQuote) { results[ref.identity] = failure }
        }
        if (symbols.isEmpty()) return results
        val requestedAt = timeout?.let { monotonic() }
        val primaryBudget = if (timeout == null) primaryTimeout else minOf(primaryTimeout, timeout)
        try {
            val prices = boundedDownload(symbols.values.distinct(), primaryBudget)
            for ((identity, symbol) in symbols) results[identity] = prices[symbol]
        } catch (_: Exception) { symbols.keys.forEach { results[it] = IllegalStateException("provider_error") } }
        val missing = refs.filter { it.identity in symbols && (results[it.identity] == null || results[it.identity] is Exception) }
        if (missing.isNotEmpty() && sources.isNotEmpty()) {
            val remaining = if (requestedAt == null) null else maxOf(timeout!! - (monotonic() - requestedAt), 0.0)
            for ((identity, tick) in fallbackMissing(missing, remaining)) if (tick != null) results[identity] = tick
        }
        return results
    }
    private data class FallbackOutcome(val identity: String, val tick: ProviderTick?)
    private fun fallbackMissing(refs: List<AssetRef>, timeout: Double?): Map<String, ProviderTick?> {
        val deadline = timeout?.let { monotonic() + maxOf(it, 0.0) }
        val pending = java.util.ArrayDeque(refs); val completed = LinkedBlockingQueue<FallbackOutcome>()
        val resolved = linkedMapOf<String, ProviderTick?>(); var active = 0
        while (pending.isNotEmpty() || active > 0) {
            while (pending.isNotEmpty() && active < maxFallback) {
                if (deadline != null && monotonic() >= deadline) break
                val ref = pending.removeFirst()
                marketDaemon("market-fallback") {
                    var tick: ProviderTick? = null
                    for (source in sources) {
                        val remaining = deadline?.let { maxOf(it - monotonic(), 0.0) }
                        if (remaining != null && remaining <= 0) break
                        val budget = if (remaining == null) fallbackTimeout else minOf(fallbackTimeout, remaining)
                        val candidate = try { source.rawQuote(ref.identity, ref.kind, budget) } catch (_: Exception) { null }
                        if (candidate != null) { tick = candidate; break }
                    }
                    completed.offer(FallbackOutcome(ref.identity, tick))
                }
                active++
            }
            if (active == 0) break
            val remaining = deadline?.let { maxOf(it - monotonic(), 0.0) }
            if (remaining != null && remaining <= 0) break
            val outcome = marketPoll(completed, remaining) ?: break
            active--; resolved[outcome.identity] = outcome.tick
        }
        return resolved
    }
}

