package com.finance.tracker.backend

import com.finance.tracker.business.domain.LedgerDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.serialization.json.*

internal fun interface FxMidFetcher { fun fetch(day: String, base: String, quote: String): String? }
private fun fxTake(value: String, length: Int): String = value.codePoints().toArray().take(length).toIntArray().let { String(it, 0, it.size) }
private fun fxText(value: JsonElement?): String = if (value == null || !pythonTruthy(value)) "" else pythonString(value)

internal class FxRates(private val jsonFetcher: QuoteJsonFetcher = JvmQuoteHttpFetcher(userAgent = "ft-fx/1.0"),
    private val clock: () -> Instant = Instant::now) {
    private data class Key(val day: String, val base: String, val quote: String)
    private val cache = mutableMapOf<Key, String?>()
    fun today(): String = clock().atOffset(ZoneOffset.UTC).toLocalDate().toString()
    fun businessDayUtc(value: Any?): String {
        val instant = when (value) {
            is Instant -> value
            is OffsetDateTime -> value.toInstant()
            is ZonedDateTime -> value.toInstant()
            is LocalDateTime -> value.toInstant(ZoneOffset.UTC)
            is QuoteTime -> value.instant()
            else -> null
        }
        if (instant != null) return instant.atOffset(ZoneOffset.UTC).toLocalDate().toString()
        val text = valuationStrip(when (value) { is JsonElement -> fxText(value); null -> ""; else -> value.toString() })
        if (text.isEmpty()) return today()
        val points = text.codePoints().toArray()
        if (points.size >= 10 && points[4] == '-'.code && points[7] == '-'.code) return fxTake(text, 10)
        return try { pythonCashTimestamp(text.replace("Z", "+00:00"), allowNaive = true).atOffset(ZoneOffset.UTC).toLocalDate().toString() }
        catch (_: InvalidCashValue) { if (points.size >= 10) fxTake(text, 10) else today() }
    }
    fun getMidRate(day: String, base: String, quote: String, fetcher: FxMidFetcher? = null): String? =
        getMidRate(JsonPrimitive(day), JsonPrimitive(base), JsonPrimitive(quote), fetcher)
    fun getMidRate(day: JsonElement?, base: JsonElement?, quote: JsonElement?, fetcher: FxMidFetcher? = null): String? {
        val d = fxTake(fxText(day), 10); val b = valuationUpper(fxText(base)); val q = valuationUpper(fxText(quote))
        if (d.isEmpty() || b.codePointCount(0, b.length) != 3 || q.codePointCount(0, q.length) != 3) return null
        if (b == q) return "1"
        val key = Key(d, b, q)
        if (fetcher == null) synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val result = if (fetcher == null) fetchFrankfurter(d, b, q) else fetcher.fetch(d, b, q)
        if (fetcher == null) synchronized(cache) { cache[key] = result }
        return result
    }
    fun clearCache() { synchronized(cache) { cache.clear() } }
    fun fetchFrankfurter(day: String, base: String, quote: String): String? {
        val b = valuationUpper(base); val q = valuationUpper(quote)
        if (b == q) return "1"
        fun fetch(date: String): JsonElement? = try {
            jsonFetcher.fetch("https://api.frankfurter.dev/v1/$date?base=$b&symbols=$q", 8.0).takeUnless { it == JsonNull }
        } catch (_: Exception) { null }
        var payload = fetch(day)
        if (payload == null) {
            val date = try {
                require(Regex("(?:[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{8}|[0-9]{4}(?:-W[0-9]{2}(?:-[0-9])?|W[0-9]{2}[0-9]?))").matches(day))
                pythonCashTimestamp(day, allowNaive = true).atOffset(ZoneOffset.UTC).toLocalDate()
            } catch (_: InvalidCashValue) { return null } catch (_: IllegalArgumentException) { return null }
            for (back in 1L..5L) {
                val previous = date.minusDays(back); if (previous.year !in 1..9999) throw ArithmeticException("date value out of range")
                payload = fetch(previous.toString()); if (payload != null) break
            }
        }
        val rates = (payload as? JsonObject)?.get("rates") as? JsonObject ?: return null
        val raw = rates[q] ?: return null
        val representation = LedgerDecimal.parseRepresentationOrNull(pythonString(raw)) ?: return null
        if (representation.negative || representation.coefficient == "0") return null
        return representation.coefficient + "E" + representation.exponent
    }
    companion object { val process by lazy { FxRates() } }
}

internal class FxRateProvider(private val fetcher: FxMidFetcher? = null, private val rates: FxRates = FxRates.process) {
    fun getMid(base: String, quote: String, day: String? = null): String? = rates.getMidRate(day?.takeIf { it.isNotEmpty() } ?: rates.today(), base, quote, fetcher)
}

internal fun fxRateError(cashAbs: String, loanAbs: String, cashCurrency: String, loanCurrency: String, marketQuotePerCash: String?): String? {
    if (marketQuotePerCash == null) return null
    fun number(value: String): FinanceDecimal {
        valuationFiniteRepresentation(value)
        return FinanceDecimal.parse(value)
    }
    val cash = number(cashAbs); if (cash <= FinanceDecimal.ZERO) return null
    val loan = number(loanAbs); if (loan <= FinanceDecimal.ZERO) return null
    if (valuationUpper(cashCurrency) == valuationUpper(loanCurrency)) return null
    val context = MathContext(28, RoundingMode.HALF_EVEN)
    fun divide(left: String, right: String): String {
        val a = FinanceDecimal.parse(left); val b = FinanceDecimal.parse(right)
        val ai = a.text.endsWith("Infinity"); val bi = b.text.endsWith("Infinity")
        if (ai && bi) throw ArithmeticException("invalid decimal division")
        if (ai) return "Infinity"
        if (bi) return "0E-1000026"
        return a.text.toBigDecimal().divide(b.text.toBigDecimal(), context).toString()
    }
    val implied = divide(loan.text, cash.text)
    val market = number(marketQuotePerCash); if (market <= FinanceDecimal.ZERO) return null
    val ratio = divide(implied, market.text)
    if (ratio == "Infinity") return ratio
    return ratio.toBigDecimal().subtract(java.math.BigDecimal.ONE, context).abs(context).toString()
}
