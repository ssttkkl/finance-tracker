"""Pure exact arithmetic for wealth attribution; this module has no adapter imports."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, timedelta
from decimal import Decimal, InvalidOperation, localcontext
import base64
import json
from typing import Mapping

from ft.domain.wealth import AttributionComponent, ComponentKind, CoverageDisposition, ImmutableEvidenceRef, WealthError, WealthStatus, canonical_bytes, canonical_digest, decimal_value


ZERO = Decimal("0")
_SUPPORTED_ASSET_TYPES = frozenset({"cash", "bank_deposit", "liability", "security", "crypto"})
_SUPPORTED_EVENT_KINDS = frozenset({
    "salary", "expense", "refund", "interest", "liability_interest", "external_cashflow",
    "dividend", "fee", "investment_return", "transfer", "investment_funding", "portfolio_flow",
    "explained_other_adjustment", "liability_revaluation", "fx_impact",
})


@dataclass(frozen=True)
class WealthEvent:
    """A normalized formal event; amount already has CNY net-worth sign semantics."""
    kind: str
    amount: Decimal


@dataclass(frozen=True)
class WealthIdentity:
    opening: Decimal
    closing: Decimal
    external_cashflow: Decimal
    investment_return: Decimal
    fx_impact: Decimal
    liability_revaluation: Decimal
    explained_other_adjustment: Decimal
    unexplained_adjustment: Decimal
    explained_ratio: Decimal

    @property
    def components(self) -> Mapping[ComponentKind, Decimal]:
        return {
            ComponentKind.EXTERNAL_CASHFLOW: self.external_cashflow,
            ComponentKind.INVESTMENT_RETURN: self.investment_return,
            ComponentKind.FX_IMPACT: self.fx_impact,
            ComponentKind.LIABILITY_REVALUATION: self.liability_revaluation,
            ComponentKind.EXPLAINED_OTHER_ADJUSTMENT: self.explained_other_adjustment,
            ComponentKind.UNEXPLAINED_ADJUSTMENT: self.unexplained_adjustment,
        }


@dataclass(frozen=True)
class ValuationCandidate:
    value: Decimal
    as_of: datetime
    source: str  # checkin or replay


@dataclass(frozen=True)
class SelectedValuation:
    value: Decimal
    warning: str | None = None


@dataclass(frozen=True)
class CoverageEvaluation:
    status: WealthStatus
    complete_values_available: bool
    coverage_fingerprint: str


@dataclass(frozen=True)
class KnownIdentity:
    known_opening: Decimal
    known_closing: Decimal
    known_unexplained_adjustment: Decimal
    excluded_coverage_adjustment: Decimal


@dataclass(frozen=True)
class LifecycleEvent:
    kind: str
    effective_at: datetime


@dataclass(frozen=True)
class DailyPoint:
    local_date: date
    opening: Decimal | None
    closing: Decimal | None
    components: tuple[Decimal | None, ...]
    status: WealthStatus
    coverage_fingerprint: str
    investment_return_rate: Decimal | None = None
    source_revision: str = ""
    build_revision: str | None = None
    warnings: tuple[str, ...] = ()
    component_refs: tuple[AttributionComponent, ...] = ()


@dataclass(frozen=True)
class EvidenceItem:
    evidence_identity: str
    source_identity: str
    source_revision: str
    occurred_at: datetime
    evidence_kind: str
    contribution: Decimal | None
    scope_fold_identity: str
    safe_metadata: Mapping[str, str] | None = None


@dataclass(frozen=True)
class EvidencePage:
    items: tuple[EvidenceItem, ...]
    next_cursor: str | None
    ordering_version: str = "v1"


@dataclass(frozen=True)
class AggregatedPoint:
    opening: Decimal | None
    closing: Decimal | None
    components: tuple[Decimal | None, ...]
    status: WealthStatus
    coverage_fingerprint: str
    investment_return_rate: Decimal | None = None
    local_date: date | None = None
    source_revision: str = ""
    build_revision: str | None = None
    warnings: tuple[str, ...] = ()
    component_refs: tuple[AttributionComponent, ...] = ()


def calculate_identity(
    *, opening: Decimal, closing: Decimal, events: tuple[WealthEvent, ...] = (),
    fx_impact: Decimal = ZERO, liability_revaluation: Decimal = ZERO,
    explained_other_adjustment: Decimal = ZERO,
) -> WealthIdentity:
    """Close the six-term identity without rounding any intermediate value."""
    opening, closing = decimal_value(opening), decimal_value(closing)
    external, investment = ZERO, ZERO
    for event in events:
        amount = decimal_value(event.amount)
        if event.kind in {"salary", "expense", "refund", "interest", "liability_interest", "external_cashflow"}:
            external += amount
        elif event.kind in {"dividend", "fee", "investment_return"}:
            investment += amount
        elif event.kind in {"transfer", "investment_funding", "portfolio_flow"}:
            # These are internal movements at workspace scope, never a second contribution.
            continue
        elif event.kind == "explained_other_adjustment":
            explained_other_adjustment += amount
        elif event.kind == "liability_revaluation":
            liability_revaluation += amount
        elif event.kind == "fx_impact":
            fx_impact += amount
        else:
            # Unknown formal events cannot safely become explained amounts.
            raise WealthError("wealth.unsupported_event")
    fx_impact = decimal_value(fx_impact)
    liability_revaluation = decimal_value(liability_revaluation)
    explained_other_adjustment = decimal_value(explained_other_adjustment)
    explained = external + investment + fx_impact + liability_revaluation + explained_other_adjustment
    unexplained = closing - opening - explained
    denominator = max(abs(closing - opening), sum(abs(value) for value in (
        external, investment, fx_impact, liability_revaluation, explained_other_adjustment,
    )), Decimal("1"))
    ratio = max(ZERO, min(Decimal("1"), Decimal("1") - abs(unexplained) / denominator))
    return WealthIdentity(
        opening, closing, external, investment, fx_impact, liability_revaluation,
        explained_other_adjustment, unexplained, ratio,
    )


def is_supported_wealth_input(asset_type: str, event_kind: str) -> bool:
    return asset_type in _SUPPORTED_ASSET_TYPES and event_kind in _SUPPORTED_EVENT_KINDS


def decompose_foreign_investment(
    *, opening_value: Decimal, closing_value: Decimal,
    flows: tuple[tuple[Decimal, Decimal], ...], opening_fx: Decimal, closing_fx: Decimal,
) -> tuple[Decimal, Decimal]:
    """Return local-market return then FX impact in the mandated fixed order."""
    opening_value, closing_value = decimal_value(opening_value), decimal_value(closing_value)
    opening_fx, closing_fx = decimal_value(opening_fx), decimal_value(closing_fx)
    normalized_flows = tuple((decimal_value(value), decimal_value(rate)) for value, rate in flows)
    investment_return = (closing_value - opening_value - sum((value for value, _ in normalized_flows), ZERO)) * closing_fx
    fx_impact = opening_value * (closing_fx - opening_fx) + sum(
        (value * (closing_fx - rate) for value, rate in normalized_flows), ZERO
    )
    return investment_return, fx_impact


def decompose_foreign_cash_fx(
    *, opening_balance: Decimal, flows: tuple[tuple[Decimal, Decimal], ...],
    opening_fx: Decimal, closing_fx: Decimal,
) -> Decimal:
    """FX-only impact for foreign cash or liabilities (negative balances stay negative)."""
    opening_balance, opening_fx, closing_fx = (
        decimal_value(opening_balance), decimal_value(opening_fx), decimal_value(closing_fx)
    )
    return opening_balance * (closing_fx - opening_fx) + sum(
        (decimal_value(value) * (closing_fx - decimal_value(rate)) for value, rate in flows), ZERO
    )


def valuation_freshness(observed_at: datetime, boundary_at: datetime, *, asset_kind: str) -> WealthStatus:
    """Map trustworthy historical quote age to the public coverage status."""
    if observed_at.tzinfo is None or boundary_at.tzinfo is None:
        raise ValueError("valuation timestamps must be timezone-aware")
    age = boundary_at - observed_at
    if age < timedelta(0):
        age = timedelta(0)
    freshness, maximum = (
        (timedelta(hours=24), timedelta(days=7)) if asset_kind == "crypto"
        else (timedelta(days=5), timedelta(days=30))
    )
    if age > maximum:
        return WealthStatus.PARTIAL
    if age > freshness:
        return WealthStatus.STALE
    return WealthStatus.COMPLETE


def select_boundary_valuation(
    candidates: tuple[ValuationCandidate, ...], *, boundary_at: datetime,
) -> SelectedValuation:
    """Prefer an exact trusted check-in, while surfacing material replay disagreement."""
    exact = tuple(candidate for candidate in candidates if candidate.as_of == boundary_at)
    checkins = tuple(candidate for candidate in exact if candidate.source == "checkin")
    replays = tuple(candidate for candidate in exact if candidate.source == "replay")
    if checkins:
        selected = checkins[-1]
        if replays:
            replay = replays[-1]
            threshold = max(Decimal("10"), abs(selected.value) * Decimal("0.001"))
            if abs(selected.value - replay.value) > threshold:
                return SelectedValuation(selected.value, "VALUATION_CONFLICT")
        return SelectedValuation(selected.value)
    if replays:
        return SelectedValuation(replays[-1].value)
    raise ValueError("boundary valuation is unavailable")


def evaluate_coverage(dispositions: Mapping[str, CoverageDisposition]) -> CoverageEvaluation:
    """Use the complete expected universe; never quietly turn a gap into a zero."""
    applicable = {key: value for key, value in dispositions.items() if value is not CoverageDisposition.NOT_APPLICABLE}
    supported = [value for value in applicable.values() if value is CoverageDisposition.SUPPORTED]
    if not supported:
        raise WealthError("wealth.report_not_constructible")
    if any(value is CoverageDisposition.UNSUPPORTED for value in applicable.values()):
        status = WealthStatus.UNSUPPORTED
    elif any(value in {CoverageDisposition.MISSING, CoverageDisposition.UNVALUED} for value in applicable.values()):
        status = WealthStatus.PARTIAL
    else:
        status = WealthStatus.COMPLETE
    return CoverageEvaluation(status, status is WealthStatus.COMPLETE, canonical_digest(
        tuple(sorted((identity, value.value) for identity, value in dispositions.items()))
    ))


def calculate_known_identity(
    *, opening: Decimal, closing: Decimal, components: tuple[Decimal, ...],
    excluded_coverage_adjustment: Decimal,
) -> KnownIdentity:
    opening, closing = decimal_value(opening), decimal_value(closing)
    excluded_coverage_adjustment = decimal_value(excluded_coverage_adjustment)
    known_unexplained = closing - opening - sum((decimal_value(item) for item in components), ZERO) - excluded_coverage_adjustment
    return KnownIdentity(opening, closing, known_unexplained, excluded_coverage_adjustment)


def account_applicable(events: tuple[LifecycleEvent, ...], at: datetime) -> bool:
    """Evaluate append-only lifecycle facts; invalid transitions fail closed."""
    active = False
    for event in sorted(events, key=lambda item: item.effective_at):
        if event.effective_at > at:
            break
        if event.kind in {"opened", "reactivated"}:
            if active:
                continue
            active = True
        elif event.kind == "closed":
            if not active:
                continue
            active = False
        else:
            raise ValueError("invalid lifecycle event")
    return active


def coverage_changed(before: CoverageDisposition, after: CoverageDisposition) -> bool:
    """Lifecycle non-applicability is outside the comparable coverage universe."""
    if CoverageDisposition.NOT_APPLICABLE in {before, after}:
        return False
    return before is not after


def aggregate_daily_points(points: tuple[DailyPoint, ...]) -> AggregatedPoint:
    """Aggregate canonical daily results only; no values are interpolated across a gap."""
    if not points:
        raise ValueError("cannot aggregate zero daily points")
    ordered = tuple(sorted(points, key=lambda point: point.local_date))
    status = max(point.status for point in ordered)
    continuous_coverage = len({point.coverage_fingerprint for point in ordered}) == 1
    complete = continuous_coverage and all(
        point.opening is not None and point.closing is not None and all(item is not None for item in point.components)
        for point in ordered
    )
    width = len(ordered[0].components)
    source_revision = canonical_digest(tuple(
        (point.local_date.isoformat(), point.source_revision) for point in ordered
    ))
    return_rate = linked_return(tuple(point.investment_return_rate for point in ordered))
    if not complete:
        return AggregatedPoint(
            ordered[0].opening, ordered[-1].closing, tuple(None for _ in range(width)),
            max(status, WealthStatus.PARTIAL), canonical_digest(tuple(point.coverage_fingerprint for point in ordered)),
            None, ordered[0].local_date, source_revision, None,
            tuple(sorted({warning for point in ordered for warning in point.warnings})),
        )
    return AggregatedPoint(
        ordered[0].opening, ordered[-1].closing,
        tuple(sum((point.components[index] for point in ordered), ZERO) for index in range(width)),
        status, ordered[0].coverage_fingerprint, return_rate, ordered[0].local_date, source_revision, None,
        tuple(sorted({warning for point in ordered for warning in point.warnings})),
    )


def dietz_time_weight(flow_at: datetime, day_start: datetime, day_end: datetime) -> Decimal:
    """Return ``(day_end - flow_time) / day_length`` for Modified Dietz capital.

    Time weights are exact rationals from second counts.  They may exceed the
    18-digit display scale used for published money fields; callers must keep
    them unrounded until the final rate division.
    """
    length = Decimal((day_end - day_start).total_seconds())
    if length <= ZERO:
        raise ValueError("invalid day interval for Dietz weight")
    remaining = Decimal((day_end - flow_at).total_seconds())
    if remaining < ZERO:
        remaining = ZERO
    elif remaining > length:
        remaining = length
    return remaining / length


def _dietz_weight(value: object) -> Decimal:
    """Accept exact high-scale time weights without forcing money-field scale limits."""
    try:
        weight = value if isinstance(value, Decimal) else Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError) as exc:
        raise WealthError("wealth.invalid_decimal", field="weight") from exc
    if not weight.is_finite():
        raise WealthError("wealth.invalid_decimal", field="weight")
    return weight


def modified_dietz(
    opening_value: Decimal, closing_value: Decimal, flows: tuple[tuple[Decimal, Decimal], ...],
) -> Decimal | None:
    """Return the exact local-currency daily Modified Dietz return or an honest gap."""
    opening_value, closing_value = decimal_value(opening_value), decimal_value(closing_value)
    normalized = tuple((decimal_value(amount), _dietz_weight(weight)) for amount, weight in flows)
    capital = opening_value + sum((amount * weight for amount, weight in normalized), ZERO)
    if capital <= ZERO:
        return None
    gain = closing_value - opening_value - sum((amount for amount, _ in normalized), ZERO)
    # Keep an exact zero at exponent 0 so linked products never absorb 0E+N pollution.
    return ZERO if gain == ZERO else gain / capital


def weighted_modified_dietz(
    currency_buckets: tuple[tuple[Decimal, Decimal, Decimal, tuple[tuple[Decimal, Decimal], ...]], ...],
) -> Decimal | None:
    """Combine local Modified Dietz rates with fixed day-start FX capital weights.

    Each bucket is ``(opening_local, closing_local, day_start_fx, flows)`` where
    ``flows`` are ``(local_amount, time_weight)``.  Local rates exclude FX; only
    the day-start FX is used to weight capital across currencies.  Any material
    exposure with non-positive capital, missing rate, or non-positive total
    weighted capital fails closed to ``None``.

    The published rate equals ``Σ(gain_c × day_start_fx_c) / Σ(capital_c × day_start_fx_c)``,
    which is algebraically identical to the weighted local rates and avoids an
    intermediate ``rate × capital`` product that would lose Decimal precision.
    """
    weighted_gain = ZERO
    total_capital = ZERO
    saw_bucket = False
    for opening_local, closing_local, day_start_fx, flows in currency_buckets:
        opening = decimal_value(opening_local)
        closing = decimal_value(closing_local)
        fx = decimal_value(day_start_fx)
        if fx <= ZERO:
            return None
        normalized = tuple((decimal_value(amount), _dietz_weight(weight)) for amount, weight in flows)
        capital = opening + sum((amount * weight for amount, weight in normalized), ZERO)
        material = opening != ZERO or closing != ZERO or any(amount != ZERO for amount, _ in normalized)
        if material and capital <= ZERO:
            return None
        if capital <= ZERO:
            continue
        saw_bucket = True
        gain = closing - opening - sum((amount for amount, _ in normalized), ZERO)
        weighted_gain += gain * fx
        total_capital += capital * fx
    if not saw_bucket or total_capital <= ZERO:
        return None
    return ZERO if weighted_gain == ZERO else weighted_gain / total_capital


def linked_return(rates: tuple[Decimal | None, ...]) -> Decimal | None:
    if any(rate is None for rate in rates):
        return None
    if not rates:
        return ZERO
    normalized = tuple(
        ZERO if rate == ZERO else (rate if isinstance(rate, Decimal) else decimal_value(rate))
        for rate in rates
    )
    # A single daily rate must remain bit-identical after aggregation; re-entering
    # ``1 + r`` under the default Decimal precision would truncate exact divisions.
    if len(normalized) == 1:
        return normalized[0]
    result = Decimal("1")
    with localcontext() as ctx:
        # Multi-day products need headroom beyond the default 28 digits so exact
        # intermediate rates are not silently rounded before the final ``- 1``.
        ctx.prec = max(ctx.prec, 80)
        for rate in normalized:
            result *= Decimal("1") + rate
        product = result - Decimal("1")
    return ZERO if product == ZERO else product


@dataclass(frozen=True)
class PortfolioBucket:
    """One owned investment bucket valued in a single local currency for a day."""

    opening_local: Decimal
    closing_local: Decimal
    opening_fx: Decimal
    closing_fx: Decimal
    flows: tuple[tuple[Decimal, Decimal], ...] = ()  # local units, FX at flow time
    is_cash_or_liability: bool = False


def attribute_complete_day(
    *,
    opening: Decimal,
    closing: Decimal,
    external_events: tuple[WealthEvent, ...] = (),
    portfolio_buckets: tuple[PortfolioBucket, ...] = (),
    liability_revaluation: Decimal = ZERO,
    explained_other_adjustment: Decimal = ZERO,
) -> WealthIdentity:
    """Apply the single canonical daily attribution algorithm.

    Foreign investment buckets use the fixed-order boundary formula with
    flow-weighted FX. Dividends and fees are not added a second time; they are
    absorbed by the boundary values. Direct external funding is an external
    cashflow for the workspace and a portfolio ``Fi`` for the investment bucket.
    """
    investment_return = ZERO
    fx_impact = ZERO
    for bucket in portfolio_buckets:
        opening_local = decimal_value(bucket.opening_local)
        closing_local = decimal_value(bucket.closing_local)
        opening_fx = decimal_value(bucket.opening_fx)
        closing_fx = decimal_value(bucket.closing_fx)
        flows = tuple((decimal_value(amount), decimal_value(rate)) for amount, rate in bucket.flows)
        if bucket.is_cash_or_liability:
            fx_impact += decompose_foreign_cash_fx(
                opening_balance=opening_local, flows=flows, opening_fx=opening_fx, closing_fx=closing_fx,
            )
            continue
        if opening_fx == Decimal("1") and closing_fx == Decimal("1") and all(rate == Decimal("1") for _amount, rate in flows):
            # CNY investment: market return is the residual after portfolio boundary flows.
            investment_return += closing_local - opening_local - sum((amount for amount, _rate in flows), ZERO)
            continue
        local_return, local_fx = decompose_foreign_investment(
            opening_value=opening_local, closing_value=closing_local, flows=flows,
            opening_fx=opening_fx, closing_fx=closing_fx,
        )
        investment_return += local_return
        fx_impact += local_fx
    events = tuple(external_events)
    if investment_return:
        events = events + (WealthEvent("investment_return", investment_return),)
    return calculate_identity(
        opening=opening, closing=closing, events=events, fx_impact=fx_impact,
        liability_revaluation=liability_revaluation, explained_other_adjustment=explained_other_adjustment,
    )


def project_daily_point(*, local_date: str, source_revision: str, boundaries, cashflows, valuations, lifecycle) -> DailyPoint:
    """Project one canonical daily point from formal boundary values and flows."""
    complete = all(start is not None and end is not None for start, end in boundaries.values())
    # Coverage represents the applicable identity/disposition universe, not
    # the date or source revision that happened to produce a point.
    fingerprint = canonical_digest(tuple(sorted((identity, "supported") for identity in boundaries)))
    if not complete:
        return DailyPoint(date.fromisoformat(local_date), None, None, (None,) * 6, WealthStatus.PARTIAL, fingerprint)
    opening = sum((decimal_value(start) for start, _end in boundaries.values()), ZERO)
    closing = sum((decimal_value(end) for _start, end in boundaries.values()), ZERO)
    identity = attribute_complete_day(
        opening=opening, closing=closing,
        external_events=tuple(WealthEvent("external_cashflow", decimal_value(value)) for value in cashflows),
    )
    return DailyPoint(
        date.fromisoformat(local_date), opening, closing, tuple(identity.components.values()),
        WealthStatus.COMPLETE, fingerprint, source_revision=source_revision,
    )


def project_daily_range(date_from: str, date_to: str, source_revision: str, boundary_series, cashflow_series):
    start, end = date.fromisoformat(date_from), date.fromisoformat(date_to)
    result = []
    for index in range((end - start).days):
        boundaries = {key: values[index] for key, values in boundary_series.items()}
        result.append(project_daily_point(local_date=(start + timedelta(days=index)).isoformat(), source_revision=source_revision, boundaries=boundaries, cashflows=cashflow_series[index], valuations=(), lifecycle=()))
    return tuple(result)


def build_component(
    workspace_id: str, period_start: str, period_end: str, granularity: str,
    kind: ComponentKind, grouping_key: str, amount: Decimal | None, status: WealthStatus,
    source_revision: str, *, calculation_version: str = "wealth-attribution-v0.1",
    valuation_policy_version: str = "valuation-v0.1",
) -> AttributionComponent:
    component_key = canonical_digest({
        "workspace": workspace_id, "start": period_start, "end": period_end,
        "granularity": granularity, "kind": kind, "group": grouping_key,
    })
    result_revision = canonical_digest({
        "calculation": calculation_version, "valuation": valuation_policy_version,
        "source": source_revision, "amount": amount, "status": status,
    })
    component_id = canonical_digest({"key": component_key, "result": result_revision})
    evidence_id = canonical_digest({"component": component_id, "result": result_revision, "ordering": "v1"})
    return AttributionComponent(
        component_key, component_id, result_revision, kind, status, amount,
        ImmutableEvidenceRef(component_id, result_revision, evidence_id),
    )


def _evidence_order(item: EvidenceItem) -> tuple[str, str, str, str]:
    return (item.occurred_at.isoformat(), item.source_identity, item.evidence_kind, item.evidence_identity)


def _encode_cursor(component_id: str, result_revision: str, last: EvidenceItem) -> str:
    return base64.urlsafe_b64encode(canonical_bytes({
        "component": component_id, "result": result_revision, "ordering": "v1", "last": _evidence_order(last),
    })).decode("ascii")


def _decode_cursor(cursor: str) -> dict:
    try:
        return json.loads(base64.urlsafe_b64decode(cursor.encode("ascii")))
    except (ValueError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise WealthError("wealth.evidence_cursor_invalid") from exc


def page_evidence(
    component_id: str, result_revision: str, evidence: tuple[EvidenceItem, ...], *, cursor: str | None = None,
    limit: int = 100,
) -> EvidencePage:
    if limit <= 0:
        raise WealthError("wealth.evidence_cursor_invalid")
    folded: dict[str, EvidenceItem] = {}
    for item in evidence:
        current = folded.get(item.scope_fold_identity)
        if current is None:
            folded[item.scope_fold_identity] = item
            continue
        contribution = None if current.contribution is None and item.contribution is None else (current.contribution or ZERO) + (item.contribution or ZERO)
        canonical = min(current, item, key=_evidence_order)
        folded[item.scope_fold_identity] = EvidenceItem(
            canonical.evidence_identity, canonical.source_identity, canonical.source_revision, canonical.occurred_at,
            canonical.evidence_kind, contribution, canonical.scope_fold_identity, canonical.safe_metadata,
        )
    ordered = tuple(sorted(folded.values(), key=_evidence_order))
    if cursor:
        decoded = _decode_cursor(cursor)
        if decoded.get("component") != component_id or decoded.get("result") != result_revision or decoded.get("ordering") != "v1":
            raise WealthError("wealth.evidence_cursor_invalid")
        last = tuple(decoded.get("last", ()))
        ordered = tuple(item for item in ordered if _evidence_order(item) > last)
    selected = ordered[:limit]
    next_cursor = _encode_cursor(component_id, result_revision, selected[-1]) if len(ordered) > limit else None
    return EvidencePage(selected, next_cursor)
