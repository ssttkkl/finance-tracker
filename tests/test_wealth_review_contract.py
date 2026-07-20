"""Review-remediation contracts for public canonical wealth values."""
from __future__ import annotations

from datetime import date
from decimal import Decimal


def test_public_status_and_result_envelopes_are_canonical_and_complete() -> None:
    from ft.application.wealth import WealthChangeBreakdown, WealthSeries
    from ft.domain.wealth import WealthStatus, canonical_bytes
    from ft.domain.wealth_calculation import AggregatedPoint

    assert WealthStatus.COMPLETE.value == "complete"
    assert canonical_bytes({"status": WealthStatus.STALE}) == b'{"status":"stale"}'
    point = AggregatedPoint(
        Decimal("10"), Decimal("12"), (Decimal("2"),) * 6, WealthStatus.COMPLETE, "coverage",
    )
    series = WealthSeries((point,), "source", build_revision="build")
    breakdown = WealthChangeBreakdown(
        Decimal("10"), Decimal("12"), Decimal("2"), Decimal("0"), Decimal("0"),
        Decimal("0"), Decimal("0"), Decimal("0"), Decimal("1"), "source", build_revision="build",
    )
    assert series.calculation_version == "wealth-attribution-v0.1"
    assert series.valuation_policy_version == "valuation-v0.1"
    assert series.build_revision == "build"
    assert breakdown.net_worth_change == Decimal("2")
    assert breakdown.valuation_policy_version == "valuation-v0.1"
    assert breakdown.components == ()
    assert breakdown.known_components == ()
    assert breakdown.warnings == ()
    assert breakdown.data_freshness is not None


def test_breakdown_reuses_the_monthly_daily_projection_without_a_second_formula() -> None:
    from ft.application.wealth import WealthChangeService
    from ft.domain.wealth import WealthChangeQuery, WealthStatus
    from ft.domain.wealth_calculation import DailyPoint

    class DailyOnlyFacts:
        def daily_points(self, *, date_from, date_to):
            assert (date_from, date_to) == (date(2026, 7, 1), date(2026, 8, 1))
            return (
                DailyPoint(date(2026, 7, 1), Decimal("100"), Decimal("112"),
                           (Decimal("10"), Decimal("2"), Decimal("0"), Decimal("0"), Decimal("0"), Decimal("0")),
                           WealthStatus.COMPLETE, "coverage", source_revision="source-a"),
            )

    breakdown = WealthChangeService(DailyOnlyFacts()).breakdown(WealthChangeQuery("2026-07"))
    assert breakdown.opening_net_worth == Decimal("100")
    assert breakdown.closing_net_worth == Decimal("112")
    assert breakdown.external_cashflow == Decimal("10")
    assert breakdown.investment_return == Decimal("2")
    assert breakdown.source_revision


def test_lifecycle_noops_are_idempotent_and_unknown_events_fail_closed() -> None:
    from datetime import datetime, timezone
    import pytest
    from ft.domain.wealth import WealthError
    from ft.domain.wealth_calculation import LifecycleEvent, WealthEvent, account_applicable, calculate_identity

    events = (
        LifecycleEvent("opened", datetime(2026, 7, 1, tzinfo=timezone.utc)),
        LifecycleEvent("opened", datetime(2026, 7, 1, tzinfo=timezone.utc)),
        LifecycleEvent("closed", datetime(2026, 7, 2, tzinfo=timezone.utc)),
        LifecycleEvent("closed", datetime(2026, 7, 2, tzinfo=timezone.utc)),
    )
    assert not account_applicable(events, datetime(2026, 7, 3, tzinfo=timezone.utc))
    with pytest.raises(WealthError, match="wealth.unsupported_event"):
        calculate_identity(opening=Decimal("1"), closing=Decimal("1"), events=(WealthEvent("not-a-rule", Decimal("0")),))


def test_coverage_fingerprint_is_not_a_date_or_source_revision_hash() -> None:
    from ft.domain.wealth_calculation import project_daily_point

    first = project_daily_point(
        local_date="2026-07-01", source_revision="old", boundaries={"cash": (Decimal("1"), Decimal("2"))},
        cashflows=(), valuations=(), lifecycle=(),
    )
    second = project_daily_point(
        local_date="2026-07-02", source_revision="new", boundaries={"cash": (Decimal("2"), Decimal("3"))},
        cashflows=(), valuations=(), lifecycle=(),
    )
    assert first.coverage_fingerprint == second.coverage_fingerprint
