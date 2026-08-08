"""Application service for real-time asset quotes (no FX, no ledger writes)."""
from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal
import time
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

    def raw_quote_many(
        self, refs: Sequence[AssetRef], *, timeout: float | None = None,
    ) -> dict[str, ProviderTick | BaseException | None]:
        """Return raw batch outcomes indexed by the supplied asset identities."""


class ValuationService:
    def __init__(self, provider: QuoteProvider, *, clock=None):
        self._provider = provider
        self._clock = clock or (lambda: datetime.now(timezone.utc))

    def quote(self, ref: AssetRef | None = None, *, identity: str = "", kind=None, quantity=None) -> QuoteResult:
        asset = ref if ref is not None else make_asset_ref(identity, kind, quantity)
        return self._quote_one(asset)

    def quote_at(self, ref: AssetRef, *, at: datetime) -> QuoteResult:
        """Read a quote at a historical boundary without falling back to now."""
        if not isinstance(ref, AssetRef):
            raise ValuationError("valuation.invalid_ref")
        if at.tzinfo is None:
            raise ValuationError("valuation.invalid_observed_at")
        make_asset_ref(ref.identity, ref.kind, ref.quantity)
        if identity_kind_mismatch(ref.identity, ref.kind):
            return QuoteResult(
                identity=ref.identity, kind=ref.kind, status=QuoteStatus.UNSUPPORTED,
                quantity=ref.quantity, reason="identity_kind_mismatch",
            )
        if ref.kind is AssetKind.CASH:
            market_value = compute_market_value(Decimal("1"), ref.quantity) if ref.quantity is not None else None
            return QuoteResult(
                identity=ref.identity, kind=ref.kind, status=QuoteStatus.COMPLETE,
                unit_price=Decimal("1"), quote_currency=ref.identity.upper(),
                observed_at=at, market_value=market_value,
                quantity=ref.quantity, reason="ok", provider="cash",
            )
        raw_quote_at = getattr(self._provider, "raw_quote_at", None)
        if not callable(raw_quote_at):
            return QuoteResult(
                identity=ref.identity, kind=ref.kind, status=QuoteStatus.PARTIAL,
                quantity=ref.quantity, reason="historical_quote_unavailable",
            )
        try:
            outcome = raw_quote_at(ref.identity, ref.kind, at=at)
        except UnsupportedQuote:
            return QuoteResult(
                identity=ref.identity, kind=ref.kind, status=QuoteStatus.UNSUPPORTED,
                quantity=ref.quantity, reason="unsupported_identity",
            )
        except Exception:
            return QuoteResult(
                identity=ref.identity, kind=ref.kind, status=QuoteStatus.PARTIAL,
                quantity=ref.quantity, reason="historical_provider_error",
            )
        if outcome is None:
            return QuoteResult(
                identity=ref.identity, kind=ref.kind, status=QuoteStatus.PARTIAL,
                quantity=ref.quantity, reason="historical_quote_unavailable",
            )
        price = Decimal(str(outcome.price))
        if not price.is_finite():
            return QuoteResult(
                identity=ref.identity, kind=ref.kind, status=QuoteStatus.PARTIAL,
                quantity=ref.quantity, reason="non_finite_price", provider=outcome.provider,
            )
        market_value = compute_market_value(price, ref.quantity) if ref.quantity is not None else None
        return QuoteResult(
            identity=ref.identity, kind=ref.kind, status=QuoteStatus.COMPLETE,
            unit_price=price, quote_currency=(outcome.quote_currency or "").upper() or None,
            observed_at=outcome.observed_at or at,
            market_value=market_value, quantity=ref.quantity,
            reason="ok", provider=outcome.provider,
        )

    def quote_many(
        self, refs: Sequence[AssetRef], *, timeout: float | None = None,
    ) -> QuoteBatchResult:
        validated = []
        for ref in refs:
            if not isinstance(ref, AssetRef):
                raise ValuationError("valuation.invalid_ref")
            # re-validate quantities/identities for fail-closed batch
            make_asset_ref(ref.identity, ref.kind, ref.quantity)
            validated.append(ref)
        deadline = None if timeout is None else time.monotonic() + max(timeout, 0)
        precomputed = {
            index: self._quote_one(ref)
            for index, ref in enumerate(validated)
            if identity_kind_mismatch(ref.identity, ref.kind)
        }
        routable = [
            (index, ref) for index, ref in enumerate(validated)
            if index not in precomputed
        ]
        if not routable:
            return QuoteBatchResult(tuple(precomputed[index] for index in range(len(validated))))
        raw_quote_many = getattr(self._provider, "raw_quote_many", None)
        if callable(raw_quote_many):
            remaining = None if deadline is None else max(deadline - time.monotonic(), 0)
            if remaining is not None and remaining <= 0:
                return QuoteBatchResult(tuple(
                    precomputed.get(index, self._deadline_result(ref))
                    for index, ref in enumerate(validated)
                ))
            try:
                outcomes = raw_quote_many([ref for _, ref in routable], timeout=remaining)
            except Exception:
                return QuoteBatchResult(tuple(
                    precomputed.get(index, self._provider_error_result(ref))
                    for index, ref in enumerate(validated)
                ))
            return QuoteBatchResult(tuple(
                precomputed.get(index, self._quote_outcome(ref, outcomes.get(ref.identity)))
                for index, ref in enumerate(validated)
            ))

        results = []
        for index, ref in enumerate(validated):
            if index in precomputed:
                results.append(precomputed[index])
                continue
            if deadline is not None and time.monotonic() >= deadline:
                results.append(self._deadline_result(ref))
            else:
                results.append(self._quote_one(ref))
        return QuoteBatchResult(tuple(results))

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
        except Exception as exc:
            return self._quote_outcome(ref, exc)
        return self._quote_outcome(ref, tick)

    def _quote_outcome(self, ref: AssetRef, outcome: ProviderTick | BaseException | None) -> QuoteResult:
        identity = ref.identity.strip()
        quantity = ref.quantity
        if isinstance(outcome, UnsupportedQuote):
            return QuoteResult(
                identity=identity,
                kind=ref.kind,
                status=QuoteStatus.UNSUPPORTED,
                quantity=quantity,
                reason="unsupported_identity",
            )
        if isinstance(outcome, BaseException):
            return QuoteResult(
                identity=identity,
                kind=ref.kind,
                status=QuoteStatus.PARTIAL,
                quantity=quantity,
                reason="provider_error",
            )
        if outcome is None:
            return QuoteResult(
                identity=identity,
                kind=ref.kind,
                status=QuoteStatus.PARTIAL,
                quantity=quantity,
                reason="empty_provider_response",
            )
        tick = outcome
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

    @staticmethod
    def _deadline_result(ref: AssetRef) -> QuoteResult:
        return QuoteResult(
            identity=ref.identity, kind=ref.kind, status=QuoteStatus.PARTIAL,
            quantity=ref.quantity, reason="query_deadline_exceeded",
        )

    @staticmethod
    def _provider_error_result(ref: AssetRef) -> QuoteResult:
        return QuoteResult(
            identity=ref.identity, kind=ref.kind, status=QuoteStatus.PARTIAL,
            quantity=ref.quantity, reason="provider_error",
        )
