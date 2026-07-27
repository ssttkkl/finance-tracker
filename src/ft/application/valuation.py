"""Application service for real-time asset quotes (no FX, no ledger writes)."""
from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal
from typing import Protocol, Sequence

from ft.domain.valuation import (
    AssetKind,
    AssetRef,
    ProviderTick,
    QuoteBatchResult,
    QuoteResult,
    QuoteStatus,
    ValuationError,
    compute_market_value,
    identity_kind_mismatch,
    is_finite_decimal,
    make_asset_ref,
    quote_freshness,
    validate_quantity,
)


class UnsupportedQuote(Exception):
    """Provider cannot route this identity."""


class QuoteProvider(Protocol):
    def raw_quote(self, identity: str, kind: AssetKind) -> ProviderTick | None:
        """Return a tick, None for soft miss, or raise UnsupportedQuote."""


class ValuationService:
    def __init__(self, provider: QuoteProvider, *, clock=None):
        self._provider = provider
        self._clock = clock or (lambda: datetime.now(timezone.utc))

    def quote(self, ref: AssetRef | None = None, *, identity: str = "", kind=None, quantity=None) -> QuoteResult:
        asset = ref if ref is not None else make_asset_ref(identity, kind, quantity)
        return self._quote_one(asset)

    def quote_many(self, refs: Sequence[AssetRef]) -> QuoteBatchResult:
        validated = []
        for ref in refs:
            if not isinstance(ref, AssetRef):
                raise ValuationError("valuation.invalid_ref")
            # re-validate quantities/identities for fail-closed batch
            make_asset_ref(ref.identity, ref.kind, ref.quantity)
            validated.append(ref)
        return QuoteBatchResult(tuple(self._quote_one(ref) for ref in validated))

    def _quote_one(self, ref: AssetRef) -> QuoteResult:
        identity = ref.identity.strip()
        quantity = ref.quantity
        if identity_kind_mismatch(identity, ref.kind):
            return QuoteResult(
                identity=identity,
                kind=ref.kind,
                status=QuoteStatus.UNSUPPORTED,
                quantity=quantity,
                reason="identity_kind_mismatch",
            )
        if ref.kind is AssetKind.CASH:
            price = Decimal("1")
            currency = identity.upper() if len(identity) == 3 else identity.upper()
            observed = self._clock()
            market_value = compute_market_value(price, quantity) if quantity is not None else None
            return QuoteResult(
                identity=identity,
                kind=ref.kind,
                status=QuoteStatus.COMPLETE,
                unit_price=price,
                quote_currency=currency,
                observed_at=observed,
                market_value=market_value,
                quantity=quantity,
                reason="ok",
                provider="cash",
            )
        try:
            tick = self._provider.raw_quote(identity, ref.kind)
        except UnsupportedQuote:
            return QuoteResult(
                identity=identity,
                kind=ref.kind,
                status=QuoteStatus.UNSUPPORTED,
                quantity=quantity,
                reason="unsupported_identity",
            )
        except Exception:
            return QuoteResult(
                identity=identity,
                kind=ref.kind,
                status=QuoteStatus.PARTIAL,
                quantity=quantity,
                reason="provider_error",
            )
        if tick is None:
            return QuoteResult(
                identity=identity,
                kind=ref.kind,
                status=QuoteStatus.PARTIAL,
                quantity=quantity,
                reason="empty_provider_response",
            )
        price = Decimal(str(tick.price))
        if not price.is_finite():
            return QuoteResult(
                identity=identity,
                kind=ref.kind,
                status=QuoteStatus.PARTIAL,
                quantity=quantity,
                reason="non_finite_price",
                provider=tick.provider,
            )
        status = quote_freshness(tick.observed_at, now=self._clock(), kind=ref.kind)
        if status is QuoteStatus.PARTIAL:
            return QuoteResult(
                identity=identity,
                kind=ref.kind,
                status=QuoteStatus.PARTIAL,
                quantity=quantity,
                reason="stale_quote",
                provider=tick.provider,
                observed_at=tick.observed_at,
            )
        reason = "stale_quote" if status is QuoteStatus.STALE else "ok"
        market_value = compute_market_value(price, quantity) if quantity is not None else None
        return QuoteResult(
            identity=identity,
            kind=ref.kind,
            status=status,
            unit_price=price,
            quote_currency=(tick.quote_currency or "").upper() or None,
            observed_at=tick.observed_at,
            market_value=market_value,
            quantity=quantity,
            reason=reason,
            provider=tick.provider,
        )
