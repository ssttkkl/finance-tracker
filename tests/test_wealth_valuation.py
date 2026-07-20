from datetime import datetime, timedelta, timezone
from decimal import Decimal


def test_foreign_investment_fx_decomposition_closes_exactly() -> None:
    from ft.domain.wealth_calculation import decompose_foreign_investment

    investment, fx = decompose_foreign_investment(
        opening_value=Decimal("100"), closing_value=Decimal("130"),
        flows=((Decimal("20"), Decimal("7.1")),), opening_fx=Decimal("7.0"), closing_fx=Decimal("7.2"),
    )
    assert (investment, fx) == (Decimal("72.0"), Decimal("22.0"))


def test_quote_freshness_and_maximum_age_fail_closed() -> None:
    from ft.domain.wealth_calculation import valuation_freshness
    from ft.domain.wealth import WealthStatus

    as_of = datetime(2026, 7, 10, tzinfo=timezone.utc)
    assert valuation_freshness(as_of - timedelta(days=4), as_of, asset_kind="security") is WealthStatus.COMPLETE
    assert valuation_freshness(as_of - timedelta(days=6), as_of, asset_kind="security") is WealthStatus.STALE
    assert valuation_freshness(as_of - timedelta(days=31), as_of, asset_kind="security") is WealthStatus.PARTIAL
    assert valuation_freshness(as_of - timedelta(hours=12), as_of, asset_kind="crypto") is WealthStatus.COMPLETE
    assert valuation_freshness(as_of - timedelta(hours=36), as_of, asset_kind="crypto") is WealthStatus.STALE
    assert valuation_freshness(as_of - timedelta(days=8), as_of, asset_kind="crypto") is WealthStatus.PARTIAL


def test_foreign_cash_and_liability_fx_have_no_investment_return() -> None:
    from ft.domain.wealth_calculation import decompose_foreign_cash_fx

    assert decompose_foreign_cash_fx(
        opening_balance=Decimal("-100"), flows=((Decimal("20"), Decimal("7.1")),),
        opening_fx=Decimal("7.0"), closing_fx=Decimal("7.2"),
    ) == Decimal("-18.0")


def test_boundary_checkin_wins_and_reports_material_replay_conflict() -> None:
    from ft.domain.wealth_calculation import ValuationCandidate, select_boundary_valuation

    at = datetime(2026, 7, 1, tzinfo=timezone.utc)
    selected = select_boundary_valuation(
        (ValuationCandidate(Decimal("100"), at, "replay"), ValuationCandidate(Decimal("112"), at, "checkin")),
        boundary_at=at,
    )
    assert selected.value == Decimal("112")
    assert selected.warning == "VALUATION_CONFLICT"
