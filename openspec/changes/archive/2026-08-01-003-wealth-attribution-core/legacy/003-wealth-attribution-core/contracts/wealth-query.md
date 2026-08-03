# Wealth Query Contract

This is a transport-neutral Application Service contract. HTTP URLs, OpenAPI status codes and Web-specific fields are out of scope.

## Queries

```python
WealthChangeQuery(
    month: str,  # YYYY-MM, natural month in Asia/Shanghai
)

WealthSeriesQuery(
    date_from: date,  # inclusive
    date_to: date,    # exclusive
    granularity: Literal["day", "week", "month"],
)
```

The service supplies workspace, `currency=CNY`, `timezone=Asia/Shanghai`, `calculation_version=wealth-attribution-v0.1` and the supported valuation policy. Callers cannot submit arbitrary workspace or unknown policy versions. Series spans must be positive and no longer than 366 local days.

## Result Envelope

`WealthChangeBreakdown` and `WealthSeriesPoint` use the same canonical fields:

```text
opening_net_worth: Decimal | None
closing_net_worth: Decimal | None
net_worth_change: Decimal | None
external_cashflow: Decimal | None
investment_return: Decimal | None
investment_return_rate: Decimal | None  # series point; null when undefined
fx_impact: Decimal | None
liability_revaluation: Decimal | None
explained_other_adjustment: Decimal | None
unexplained_adjustment: Decimal | None
explained_ratio: Decimal | None         # breakdown; null for incomplete coverage
status: WealthStatus
coverage: CoverageSummary | None
coverage_fingerprint: str
known_opening_net_worth: Decimal | None
known_closing_net_worth: Decimal | None
known_components: tuple[AttributionComponent, ...]
known_unexplained_adjustment: Decimal | None
excluded_coverage_adjustment: Decimal | None
excluded_items: tuple[ExcludedItem, ...]
components: tuple[AttributionComponent, ...]
data_freshness: FreshnessSummary
warnings: tuple[Warning, ...]
```

All result values are computed from unrounded Decimal values. Canonical serialization emits non-exponent decimal strings, RFC 3339 timestamps with offsets, fixed component kind order, warning code order, and stable evidence order.

Coverage entries for cash and positions expose only the stable owned identity `(owner_account_id, identity_kind, identity)`, never display names. Account lifecycle applies to every owned identity. Missing/conflicting ownership is `unsupported` with `OWNERSHIP_MISSING` or `OWNERSHIP_CONFLICT` warning/evidence; it cannot be silently omitted, treated as `not_applicable`, or included in a complete result. Shared instrument quotes and FX pairs have no owner and do not appear as standalone coverage identities.

## Component Contract

```text
component_key: str       # stable logical period/kind/group identity
component_id: str        # immutable key + result revision identity
result_revision: str
kind: ComponentKind
status: WealthStatus
amount: Decimal | None
evidence_ref: ImmutableEvidenceRef
```

The core returns an immutable reference containing `component_id`, `result_revision` and an evidence-manifest identity. A future transport adapter may turn it into a URL; the core never does.

## Service Operations

```text
breakdown(query: WealthChangeQuery) -> WealthChangeBreakdown
series(query: WealthSeriesQuery) -> WealthSeries
evidence(component_id, result_revision, cursor=None, limit=...) -> EvidencePage
rebuild(*, affected_from, source_watermark=None) -> BuildResult
```

`series` builds/reads daily points and aggregates only those points. `breakdown` for a natural month reuses the same monthly period identity and component/evidence set as the corresponding series point. `rebuild` captures a source watermark before calculation; a changed source or stale active manifest produces a named error and cannot publish a mixed generation.

## Stable Errors

```text
wealth.invalid_month
wealth.month_unavailable
wealth.invalid_date_range
wealth.range_too_large
wealth.invalid_granularity
wealth.report_not_constructible
wealth.component_not_found
wealth.evidence_cursor_invalid
wealth.source_changed
wealth.build_stale
wealth.build_incomplete
```

Errors contain safe structured IDs/revisions but no credentials, full paths, raw payloads or vendor exception text.
