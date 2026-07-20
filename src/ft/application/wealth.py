"""Transport-neutral wealth change orchestration over typed formal-fact ports."""
from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import date, datetime, timedelta
from decimal import Decimal
from zoneinfo import ZoneInfo

from ft.domain.wealth import AttributionComponent, CoverageDisposition, WealthChangeQuery, WealthError, WealthStatus
from ft.domain.wealth_calculation import AggregatedPoint, WealthIdentity, aggregate_daily_points, calculate_identity, evaluate_coverage, is_supported_wealth_input, page_evidence
from ft.domain.wealth import canonical_digest


SHANGHAI = ZoneInfo("Asia/Shanghai")


@dataclass(frozen=True)
class WealthChangeBreakdown:
    opening_net_worth: Decimal | None
    closing_net_worth: Decimal | None
    external_cashflow: Decimal | None
    investment_return: Decimal | None
    fx_impact: Decimal | None
    liability_revaluation: Decimal | None
    explained_other_adjustment: Decimal | None
    unexplained_adjustment: Decimal | None
    explained_ratio: Decimal | None
    source_revision: str
    calculation_version: str = "wealth-attribution-v0.1"
    valuation_policy_version: str = "valuation-v0.1"
    status: WealthStatus = WealthStatus.COMPLETE
    known_opening_net_worth: Decimal | None = None
    known_closing_net_worth: Decimal | None = None
    coverage_fingerprint: str = ""
    investment_return_rate: Decimal | None = None
    coverage: object | None = None
    known_components: tuple[AttributionComponent, ...] = ()
    known_unexplained_adjustment: Decimal | None = None
    excluded_coverage_adjustment: Decimal | None = None
    excluded_items: tuple[object, ...] = ()
    components: tuple[AttributionComponent, ...] = ()
    data_freshness: object | None = ()
    warnings: tuple[str, ...] = ()
    build_revision: str | None = None

    @property
    def net_worth_change(self) -> Decimal | None:
        if self.opening_net_worth is None or self.closing_net_worth is None:
            return None
        return self.closing_net_worth - self.opening_net_worth


@dataclass(frozen=True)
class WealthSeries:
    points: tuple[AggregatedPoint, ...]
    source_revision: str
    calculation_version: str = "wealth-attribution-v0.1"
    valuation_policy_version: str = "valuation-v0.1"
    build_revision: str | None = None


@dataclass(frozen=True)
class BuildResult:
    source_watermark: str
    build_revision: str


class WealthChangeService:
    def __init__(self, facts) -> None:
        self._facts = facts

    def breakdown(self, query: WealthChangeQuery) -> WealthChangeBreakdown:
        year, month = map(int, query.month.split("-"))
        start = datetime(year, month, 1, tzinfo=SHANGHAI)
        end = datetime(year + 1, 1, 1, tzinfo=SHANGHAI) if month == 12 else datetime(year, month + 1, 1, tzinfo=SHANGHAI)
        # The published daily projection is the sole report algorithm.  A
        # natural-month breakdown aggregates exactly those immutable points;
        # the legacy typed-fact path below remains only for callers that have
        # not built a read model yet.
        if hasattr(self._facts, "daily_points"):
            daily = tuple(self._facts.daily_points(date_from=start.date(), date_to=end.date()))
            if daily:
                point = self._with_period_components(
                    aggregate_daily_points(daily), start.date(), end.date(), "month"
                )
                source_revision = canonical_digest(tuple(
                    (item.local_date.isoformat(), item.source_revision) for item in daily
                ))
                values = point.components
                ratio_denominator = max(abs(point.closing - point.opening), sum((abs(value) for value in values[:5]), Decimal("0")), Decimal("1")) if (
                    point.opening is not None and point.closing is not None and all(value is not None for value in values)
                ) else None
                return WealthChangeBreakdown(
                    point.opening, point.closing, values[0], values[1], values[2], values[3],
                    values[4], values[5],
                    None if ratio_denominator is None else max(Decimal("0"), min(Decimal("1"), Decimal("1") - abs(values[5]) / ratio_denominator)),
                    source_revision, status=point.status, coverage_fingerprint=point.coverage_fingerprint,
                    investment_return_rate=point.investment_return_rate,
                    components=point.component_refs,
                    build_revision=point.build_revision,
                )
        valuations = self._facts.valuations(starts_at=start, ends_at=end)
        account_rows = tuple(self._facts.accounts())
        accounts = {account.account_id for account in account_rows}
        opening_by_identity = {row.identity: row for row in valuations if row.as_of == start and row.identity in accounts}
        closing_by_identity = {row.identity: row for row in valuations if row.as_of == end and row.identity in accounts}
        common = sorted(set(opening_by_identity) & set(closing_by_identity))
        if not common:
            raise WealthError("wealth.report_not_constructible")
        dispositions = {
            account.account_id: (
                CoverageDisposition.SUPPORTED if account.account_id in common
                else CoverageDisposition.UNSUPPORTED if not is_supported_wealth_input(account.account_type, "salary")
                else CoverageDisposition.MISSING
            ) for account in account_rows
        }
        coverage = evaluate_coverage(dispositions)
        identity: WealthIdentity = calculate_identity(
            opening=sum((opening_by_identity[item].value for item in common), Decimal("0")),
            closing=sum((closing_by_identity[item].value for item in common), Decimal("0")),
        )
        source_revision, _items = self._facts.capture_source_manifest()
        complete = coverage.complete_values_available
        return WealthChangeBreakdown(
            identity.opening if complete else None, identity.closing if complete else None,
            identity.external_cashflow if complete else None, identity.investment_return if complete else None,
            identity.fx_impact if complete else None, identity.liability_revaluation if complete else None,
            identity.explained_other_adjustment if complete else None, identity.unexplained_adjustment if complete else None,
            identity.explained_ratio if complete else None, source_revision,
            status=coverage.status, known_opening_net_worth=identity.opening,
            known_closing_net_worth=identity.closing, coverage_fingerprint=coverage.coverage_fingerprint,
        )

    def series(self, query) -> WealthSeries:
        if query.granularity not in {"day", "week", "month"}:
            raise WealthError("wealth.invalid_granularity")
        if query.date_to <= query.date_from:
            raise WealthError("wealth.invalid_date_range")
        if query.date_to - query.date_from > timedelta(days=366):
            raise WealthError("wealth.range_too_large")
        if not hasattr(self._facts, "daily_points"):
            raise WealthError("wealth.report_not_constructible")
        daily = tuple(self._facts.daily_points(date_from=query.date_from, date_to=query.date_to))
        points = self._group_daily(daily, query.granularity, query.date_from, query.date_to)
        return WealthSeries(points, canonical_digest(tuple(
            (point.local_date.isoformat(), point.source_revision) for point in daily
        )))

    def evidence(self, component_id: str, result_revision: str, *, cursor: str | None = None, limit: int = 100):
        if not hasattr(self._facts, "component_evidence"):
            raise WealthError("wealth.component_not_found")
        evidence = self._facts.component_evidence(component_id, result_revision)
        if evidence is None:
            raise WealthError("wealth.component_not_found")
        return page_evidence(component_id, result_revision, tuple(evidence), cursor=cursor, limit=limit)

    def rebuild(self, *, affected_from: str, source_watermark: str | None = None) -> BuildResult:
        if not hasattr(self._facts, "capture_source_manifest"):
            raise WealthError("wealth.build_incomplete")
        captured, _items = self._facts.capture_source_manifest()
        if source_watermark is not None and source_watermark != captured:
            raise WealthError("wealth.source_changed")
        if hasattr(self._facts, "store_source_manifest"):
            try:
                self._facts.store_source_manifest(captured, _items)
            except Exception as exc:
                raise WealthError("wealth.build_incomplete") from exc
        try:
            rows = self._facts.build_daily_results(captured, affected_from) if hasattr(self._facts, "build_daily_results") else ()
        except Exception as exc:
            # The application boundary deliberately exposes no underlying payload/driver text.
            raise WealthError("wealth.build_incomplete") from exc
        build_revision = canonical_digest({"source": captured, "affected_from": affected_from})
        has_read_model = all(hasattr(self._facts, name) for name in ("store_daily_result", "create_generation", "index_generation_day", "publish_generation"))
        if has_read_model:
            try:
                if hasattr(self._facts, "store_daily_results"):
                    self._facts.store_daily_results(rows, captured)
                else:
                    for local_date, digest, payload in rows:
                        self._facts.store_daily_result(digest, local_date, captured, payload)
                dates = tuple(row[0] for row in rows)
                if not dates:
                    raise WealthError("wealth.build_incomplete")
                self._facts.create_generation(build_revision, captured, captured, min(dates), max(dates))
                if hasattr(self._facts, "index_generation_days"):
                    self._facts.index_generation_days(build_revision, rows)
                else:
                    for local_date, digest, _payload in rows:
                        self._facts.index_generation_day(build_revision, local_date, digest)
                if hasattr(self._facts, "source_is_current") and not self._facts.source_is_current(captured):
                    raise WealthError("wealth.source_changed")
                self._facts.publish_generation(build_revision)
            except WealthError:
                raise
            except ValueError as exc:
                raise WealthError(str(exc) if str(exc).startswith("wealth.") else "wealth.build_incomplete") from exc
        if not has_read_model and hasattr(self._facts, "source_is_current") and not self._facts.source_is_current(captured):
            raise WealthError("wealth.source_changed")
        if hasattr(self._facts, "publish_build"):
            try:
                self._facts.publish_build(build_revision, rows)
            except WealthError:
                raise
            except Exception as exc:
                raise WealthError("wealth.build_stale") from exc
        return BuildResult(captured, build_revision)

    def _with_period_components(self, point: AggregatedPoint, start: date, end: date, granularity: str) -> AggregatedPoint:
        """Attach the immutable period components produced by the published projection.

        The relational runtime persists exactly these deterministic identities at
        rebuild time.  A non-persistent test double may omit the optional port,
        but it never causes a second financial calculation.
        """
        if not hasattr(self._facts, "period_components"):
            return point
        return replace(point, component_refs=tuple(self._facts.period_components(
            period_start=start, period_end=end, granularity=granularity,
            amounts=point.components, status=point.status, source_revision=point.source_revision,
        )))

    def _group_daily(self, daily, granularity: str, range_start: date | None = None, range_end: date | None = None) -> tuple[AggregatedPoint, ...]:
        if granularity == "day":
            return tuple(self._with_period_components(
                aggregate_daily_points((point,)), point.local_date, point.local_date + timedelta(days=1), "day"
            ) for point in daily)
        buckets: dict[object, list] = {}
        for point in daily:
            key = point.local_date.isocalendar()[:2] if granularity == "week" else (point.local_date.year, point.local_date.month)
            buckets.setdefault(key, []).append(point)
        result = []
        for _key, values in sorted(buckets.items()):
            point = aggregate_daily_points(tuple(values))
            start, end = values[0].local_date, values[-1].local_date + timedelta(days=1)
            if granularity == "month":
                natural_start = date(values[0].local_date.year, values[0].local_date.month, 1)
                natural_end = date(natural_start.year + 1, 1, 1) if natural_start.month == 12 else date(natural_start.year, natural_start.month + 1, 1)
                start = max(natural_start, range_start or natural_start)
                end = min(natural_end, range_end or natural_end)
            elif granularity == "week":
                natural_start = values[0].local_date - timedelta(days=values[0].local_date.weekday())
                natural_end = natural_start + timedelta(days=7)
                start = max(natural_start, range_start or natural_start)
                end = min(natural_end, range_end or natural_end)
            result.append(self._with_period_components(point, start, end, granularity))
        return tuple(result)
