package com.finance.tracker.backend

import java.time.Instant

internal class UnsupportedQuote(identity: String = "") : RuntimeException(identity)
internal interface QuoteProvider { fun rawQuote(identity: String, kind: AssetKind): ProviderTick? }
internal interface BatchQuoteProvider : QuoteProvider {
    fun rawQuoteMany(refs: List<AssetRef>, timeout: Double? = null): Map<String, Any?>
}
internal interface HistoricalQuoteProvider : QuoteProvider {
    fun rawQuoteAt(identity: String, kind: AssetKind, at: QuoteTime): ProviderTick?
}

internal class ValuationService(private val provider: QuoteProvider,
    private val clock: () -> QuoteTime = { QuoteTime.from(Instant.now()) },
    private val monotonic: () -> Double = { System.nanoTime() / 1_000_000_000.0 }) {
    fun quote(ref: AssetRef? = null, identity: String = "", kind: String? = null, quantity: String? = null,
        precision: Int = 28): QuoteResult = quoteOne(ref ?: makeAssetRef(identity, kind, quantity), precision)

    fun quoteAt(ref: AssetRef?, at: QuoteTime, precision: Int = 28): QuoteResult {
        if (ref == null) throw ValuationError("valuation.invalid_ref")
        if (at.offset == null) throw ValuationError("valuation.invalid_observed_at")
        makeAssetRef(ref.identity, ref.kind.value, ref.quantity)
        if (identityKindMismatch(ref.identity, ref.kind)) return QuoteResult(ref.identity, ref.kind, QuoteStatus.UNSUPPORTED,
            quantity = ref.quantity, reason = "identity_kind_mismatch")
        if (ref.kind == AssetKind.CASH) return QuoteResult(ref.identity, ref.kind, QuoteStatus.COMPLETE, unitPrice = "1",
            quoteCurrency = valuationUpper(ref.identity), observedAt = at,
            marketValue = ref.quantity?.let { computeMarketValue("1", it, precision) }, quantity = ref.quantity, provider = "cash")
        if (provider !is HistoricalQuoteProvider) return QuoteResult(ref.identity, ref.kind, QuoteStatus.PARTIAL,
            quantity = ref.quantity, reason = "historical_quote_unavailable")
        val outcome = try { provider.rawQuoteAt(ref.identity, ref.kind, at) }
        catch (_: UnsupportedQuote) { return QuoteResult(ref.identity, ref.kind, QuoteStatus.UNSUPPORTED,
            quantity = ref.quantity, reason = "unsupported_identity") }
        catch (_: Exception) { return QuoteResult(ref.identity, ref.kind, QuoteStatus.PARTIAL,
            quantity = ref.quantity, reason = "historical_provider_error") }
        if (outcome == null) return QuoteResult(ref.identity, ref.kind, QuoteStatus.PARTIAL, quantity = ref.quantity,
            reason = "historical_quote_unavailable")
        if (valuationFiniteRepresentation(outcome.price) == null) return QuoteResult(ref.identity, ref.kind, QuoteStatus.PARTIAL,
            quantity = ref.quantity, reason = "non_finite_price", provider = outcome.provider)
        return QuoteResult(ref.identity, ref.kind, QuoteStatus.COMPLETE, unitPrice = outcome.price,
            quoteCurrency = valuationUpper(outcome.quoteCurrency.orEmpty()).ifEmpty { null },
            observedAt = outcome.observedAt ?: at, marketValue = ref.quantity?.let { computeMarketValue(outcome.price, it, precision) },
            quantity = ref.quantity, provider = outcome.provider, quoteSession = outcome.quoteSession.orEmpty().ifEmpty { "unknown" })
    }
    fun quoteMany(refs: List<AssetRef?>, timeout: Double? = null, precision: Int = 28): QuoteBatchResult {
        val validated = refs.map { ref ->
            if (ref == null) throw ValuationError("valuation.invalid_ref")
            makeAssetRef(ref.identity, ref.kind.value, ref.quantity)
            ref
        }
        val deadline = timeout?.let { monotonic() + maxOf(it, 0.0) }
        val precomputed = validated.mapIndexedNotNull { index, ref ->
            if (identityKindMismatch(ref.identity, ref.kind)) index to quoteOne(ref, precision) else null
        }.toMap()
        val routable = validated.filterIndexed { index, _ -> index !in precomputed }
        if (routable.isEmpty()) return QuoteBatchResult(validated.indices.map { precomputed.getValue(it) })
        if (provider is BatchQuoteProvider) {
            val remaining = deadline?.let { maxOf(it - monotonic(), 0.0) }
            if (remaining != null && remaining <= 0) return QuoteBatchResult(validated.mapIndexed { index, ref -> precomputed[index] ?: deadlineResult(ref) })
            val outcomes = try { provider.rawQuoteMany(routable, remaining) }
            catch (_: Exception) { return QuoteBatchResult(validated.mapIndexed { index, ref -> precomputed[index] ?: providerError(ref) }) }
            return QuoteBatchResult(validated.mapIndexed { index, ref ->
                // Python dict.get evaluates its fallback even for a precomputed identity.
                val result = quoteOutcome(ref, outcomes[ref.identity], precision)
                precomputed[index] ?: result
            })
        }
        return QuoteBatchResult(validated.mapIndexed { index, ref ->
            precomputed[index] ?: if (deadline != null && monotonic() >= deadline) deadlineResult(ref) else quoteOne(ref, precision)
        })
    }
    private fun quoteOne(ref: AssetRef, precision: Int): QuoteResult {
        val identity = valuationStrip(ref.identity)
        if (identityKindMismatch(identity, ref.kind)) return QuoteResult(identity, ref.kind, QuoteStatus.UNSUPPORTED,
            quantity = ref.quantity, reason = "identity_kind_mismatch")
        if (ref.kind == AssetKind.CASH) return QuoteResult(identity, ref.kind, QuoteStatus.COMPLETE, unitPrice = "1",
            quoteCurrency = valuationUpper(identity), observedAt = clock(),
            marketValue = ref.quantity?.let { computeMarketValue("1", it, precision) }, quantity = ref.quantity, provider = "cash")
        val outcome = try { provider.rawQuote(identity, ref.kind) } catch (failure: Exception) { failure }
        return quoteOutcome(ref, outcome, precision)
    }
    private fun quoteOutcome(ref: AssetRef, outcome: Any?, precision: Int): QuoteResult {
        val identity = valuationStrip(ref.identity)
        if (outcome is UnsupportedQuote) return QuoteResult(identity, ref.kind, QuoteStatus.UNSUPPORTED, quantity = ref.quantity, reason = "unsupported_identity")
        if (outcome is Throwable) return QuoteResult(identity, ref.kind, QuoteStatus.PARTIAL, quantity = ref.quantity, reason = "provider_error")
        if (outcome == null) return QuoteResult(identity, ref.kind, QuoteStatus.PARTIAL, quantity = ref.quantity, reason = "empty_provider_response")
        val tick = outcome as ProviderTick
        if (valuationFiniteRepresentation(tick.price) == null) return QuoteResult(identity, ref.kind, QuoteStatus.PARTIAL,
            quantity = ref.quantity, reason = "non_finite_price", provider = tick.provider)
        val status = tick.observedAt?.let { quoteFreshness(it, clock(), ref.kind) } ?: QuoteStatus.COMPLETE
        if (status == QuoteStatus.PARTIAL) return QuoteResult(identity, ref.kind, status, quantity = ref.quantity, reason = "stale_quote",
            provider = tick.provider, observedAt = tick.observedAt, quoteSession = tick.quoteSession.orEmpty().ifEmpty { "unknown" })
        val reason = when { tick.observedAt == null -> "quote_time_unknown"; status == QuoteStatus.STALE -> "stale_quote"; else -> "ok" }
        return QuoteResult(identity, ref.kind, status, unitPrice = tick.price,
            quoteCurrency = valuationUpper(tick.quoteCurrency.orEmpty()).ifEmpty { null }, observedAt = tick.observedAt,
            marketValue = ref.quantity?.let { computeMarketValue(tick.price, it, precision) }, quantity = ref.quantity, reason = reason,
            provider = tick.provider, quoteSession = tick.quoteSession.orEmpty().ifEmpty { "unknown" })
    }
    private fun deadlineResult(ref: AssetRef) = QuoteResult(ref.identity, ref.kind, QuoteStatus.PARTIAL,
        quantity = ref.quantity, reason = "query_deadline_exceeded")
    private fun providerError(ref: AssetRef) = QuoteResult(ref.identity, ref.kind, QuoteStatus.PARTIAL,
        quantity = ref.quantity, reason = "provider_error")
}

