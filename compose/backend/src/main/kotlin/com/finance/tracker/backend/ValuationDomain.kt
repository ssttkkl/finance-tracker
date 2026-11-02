package com.finance.tracker.backend

import com.finance.tracker.business.domain.DecimalRepresentation
import com.finance.tracker.business.domain.LedgerDecimal
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonPrimitive

internal class ValuationError(val code: String, val details: Map<String, Any?> = emptyMap()) : IllegalArgumentException(code)
internal enum class AssetKind(val value: String) { SECURITY("security"), CRYPTO("crypto"), PREDICTION_MARKET("prediction_market"), CASH("cash") }
internal enum class QuoteStatus(val value: String) { COMPLETE("complete"), STALE("stale"), PARTIAL("partial"), UNSUPPORTED("unsupported") }
internal enum class FxStatus(val value: String) { COMPLETE("complete"), PARTIAL("partial"), NOT_APPLICABLE("not_applicable") }
internal data class QuoteTime(val local: LocalDateTime, val offset: ZoneOffset?, val sourceOffsetMicros: Long? = null) {
    fun instant(): Instant = sourceOffsetMicros?.let { local.toInstant(ZoneOffset.UTC).minusNanos(it * 1000) } ?: local.toInstant(offset ?: ZoneOffset.UTC)
    companion object {
        fun from(value: Instant) = QuoteTime(LocalDateTime.ofInstant(value, ZoneOffset.UTC), ZoneOffset.UTC)
        fun parse(value: String): QuoteTime = try {
            val date = OffsetDateTime.parse(value); QuoteTime(date.toLocalDateTime(), date.offset)
        } catch (_: java.time.format.DateTimeParseException) { QuoteTime(LocalDateTime.parse(value), null) }
    }
}
internal data class AssetRef(val identity: String, val kind: AssetKind, val quantity: String? = null)
internal data class ProviderTick(val price: String, val quoteCurrency: String?, val observedAt: QuoteTime?,
    val provider: String?, val quoteSession: String? = "unknown")
internal data class QuoteResult(val identity: String, val kind: AssetKind, val status: QuoteStatus,
    val unitPrice: String? = null, val quoteCurrency: String? = null, val observedAt: QuoteTime? = null,
    val quoteSession: String? = null, val marketValue: String? = null, val quantity: String? = null,
    val reason: String = "ok", val provider: String? = null)
internal data class QuoteBatchResult(val results: List<QuoteResult>) {
    val completeCount get() = results.count { it.status == QuoteStatus.COMPLETE }
    val failedCount get() = results.count { it.status == QuoteStatus.PARTIAL || it.status == QuoteStatus.UNSUPPORTED }
}
internal val valuationFiat = setOf("USD", "HKD", "CNY", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "NZD", "SGD")
internal val valuationCrypto = mapOf("btc" to "bitcoin", "eth" to "ethereum", "usdt" to "tether", "usdc" to "usd-coin",
    "sol" to "solana", "bnb" to "binancecoin", "xrp" to "ripple", "doge" to "dogecoin", "ada" to "cardano")
internal fun valuationUpper(value: String): String = buildString {
    value.codePoints().forEach { append(pythonUpperCase[it] ?: String(Character.toChars(it))) }
}
private fun valuationCharacter(code: Int, ranges: IntArray): Boolean {
    var low = 0; var high = ranges.size / 2 - 1
    while (low <= high) {
        val middle = (low + high) / 2
        when { code < ranges[middle * 2] -> high = middle - 1; code > ranges[middle * 2 + 1] -> low = middle + 1; else -> return true }
    }
    return false
}
private fun valuationDigit(code: Int) = code in pythonNondecimalDigits || pythonIntegerDigitStarts.any { code in it until it + 10 }
internal fun valuationStrip(value: String) = value.trim { it.code in pythonWhitespace }
internal fun parseAssetKind(value: String?): AssetKind = AssetKind.entries.firstOrNull { it.value == pythonCashLower(valuationStrip(value ?: "None")) }
    ?: throw ValuationError("valuation.invalid_kind", mapOf("kind" to value))
internal fun validateValuationIdentity(identity: String): String = valuationStrip(identity).takeIf { it.isNotEmpty() }
    ?: throw ValuationError("valuation.invalid_identity")
internal fun validateValuationQuantity(quantity: String?): String? {
    if (quantity == null) return null
    val amount = CashAmount.parseOrNull(quantity) ?: throw ValuationError("valuation.invalid_quantity")
    if (amount.representation.negative && amount.representation.coefficient != "0") throw ValuationError("valuation.invalid_quantity")
    return amount.toString()
}
internal fun validateDisplayCurrency(currency: String?): String? {
    if (currency == null) return null
    val text = valuationUpper(valuationStrip(currency)).let { if (it == "RMB") "CNY" else it }
    if (text !in valuationFiat) throw ValuationError("valuation.invalid_display_currency", mapOf("currency" to currency))
    return text
}
internal fun makeAssetRef(identity: String, kind: String?, quantity: String? = null) = AssetRef(
    validateValuationIdentity(identity), parseAssetKind(kind), validateValuationQuantity(quantity))
internal fun valuationFiniteRepresentation(value: String): DecimalRepresentation? {
    var nonFinite = false
    val result = LedgerDecimal.parseRepresentationOrNull(value) { error ->
        if (error == LedgerDecimal.InputError.FINITE) nonFinite = true
    }
    if (result == null && !nonFinite) throw ArithmeticException("invalid decimal")
    return result
}
internal fun computeMarketValue(unitPrice: String, quantity: String, precision: Int = 28): String {
    require(precision > 0)
    val left = exactCashAmount(unitPrice, "unit_price").representation
    val right = exactCashAmount(quantity, "quantity").representation
    var coefficient = (BigInteger(left.coefficient) * BigInteger(right.coefficient)).toString()
    var exponent = left.exponent + right.exponent
    if (coefficient == "0") exponent = exponent.coerceIn(-999999L - precision + 1, 999999L)
    else if (coefficient.length > precision) {
        val rest = coefficient.substring(precision)
        var rounded = BigInteger(coefficient.take(precision))
        if (rest.first() > '5' || rest.first() == '5' && (rest.drop(1).any { it != '0' } || rounded.testBit(0))) rounded += BigInteger.ONE
        exponent += coefficient.length - precision
        if (rounded.toString().length > precision) { rounded /= BigInteger.TEN; exponent++ }
        coefficient = rounded.toString()
    }
    return (if (left.negative != right.negative) "-" else "") + coefficient + "E" + exponent
}
internal fun isFiniteValuationDecimal(value: String): Boolean = try { valuationFiniteRepresentation(value) != null } catch (_: ArithmeticException) { false }
internal fun quoteFreshness(observedAt: QuoteTime, now: QuoteTime = QuoteTime.from(Instant.now()), kind: AssetKind): QuoteStatus {
    if (kind == AssetKind.CASH) return QuoteStatus.COMPLETE
    if (observedAt.offset == null) throw ValuationError("valuation.invalid_observed_at")
    val age = Duration.between(observedAt.instant(), now.instant()).coerceAtLeast(Duration.ZERO)
    val freshness = if (kind == AssetKind.CRYPTO) Duration.ofHours(24) else Duration.ofDays(5)
    val maximum = if (kind == AssetKind.CRYPTO) Duration.ofDays(7) else Duration.ofDays(30)
    return when { age > maximum -> QuoteStatus.PARTIAL; age > freshness -> QuoteStatus.STALE; else -> QuoteStatus.COMPLETE }
}
internal fun inferAssetKind(identity: String, cashTickers: Set<String> = emptySet(), configuredCurrencies: Set<String> = emptySet()): AssetKind? {
    val raw = validateValuationIdentity(identity); val upper = valuationUpper(raw); val lower = pythonCashLower(raw)
    return when {
        upper in cashTickers.map { valuationUpper(it) } || upper in valuationFiat -> AssetKind.CASH
        upper in configuredCurrencies.map { valuationUpper(it) } -> null
        lower.startsWith("pm:") -> AssetKind.PREDICTION_MARKET
        lower in valuationCrypto -> AssetKind.CRYPTO
        else -> AssetKind.SECURITY
    }
}
internal fun identityKindMismatch(identity: String, kind: AssetKind): Boolean {
    val lower = pythonCashLower(valuationStrip(identity))
    return when (kind) {
        AssetKind.CASH -> lower.startsWith("pm:") || '.' in lower && lower.substringAfterLast('.') in setOf("us", "hk", "sh", "sz", "tw", "jp", "t", "ks", "kq")
        AssetKind.PREDICTION_MARKET -> !lower.startsWith("pm:")
        AssetKind.CRYPTO -> lower.startsWith("pm:") || '.' in lower
        AssetKind.SECURITY -> lower.startsWith("pm:") || lower in valuationCrypto
    }
}
internal fun ledgerSecuritySymbol(identity: String): String? {
    val token = valuationStrip(identity); if (token.isEmpty()) return null
    val lower = pythonCashLower(token); if (lower.startsWith("pm:") || lower in valuationCrypto) return null
    if ('.' in lower) {
        val head = lower.substringBefore('.'); val tail = lower.substringAfter('.')
        when (tail) {
            "us" -> return head.takeIf { it.isNotEmpty() }?.let(::valuationUpper)
            "hk" -> {
                val digits = buildString { head.codePoints().forEach { code ->
                    if (valuationDigit(code)) appendCodePoint(code)
                } }
                if (digits.isEmpty()) return head.takeIf { it.isNotEmpty() }?.let(::valuationUpper)?.plus(".HK")
                return pythonIntegerValue(JsonPrimitive(digits)).toString().padStart(4, '0') + ".HK"
            }
            "sh" -> return valuationUpper(head) + ".SS"
            "sz", "ss" -> return valuationUpper(head) + "." + valuationUpper(tail)
            "tw", "two" -> return valuationUpper(head) + ".TW"
            "jp", "t" -> return valuationUpper(head) + ".T"
            "kr", "ks" -> return valuationUpper(head) + ".KS"
            "kq" -> return valuationUpper(head) + ".KQ"
        }
    }
    val points = lower.codePoints().toArray()
    return if (points.all { valuationCharacter(it, valuationAlphaRanges) } || points.all { valuationCharacter(it, valuationAlnumRanges) } && !points.all(::valuationDigit)) valuationUpper(token) else null
}
internal fun parsePredictionMarketIdentity(identity: String): Pair<String, String>? {
    val parts = pythonCashLower(valuationStrip(identity)).split(':')
    if (parts.size < 3 || parts.first() != "pm" || parts.last() !in setOf("yes", "no")) return null
    val slug = parts.subList(1, parts.size - 1).joinToString(":")
    return if (slug.isNotEmpty()) slug to parts.last() else null
}
