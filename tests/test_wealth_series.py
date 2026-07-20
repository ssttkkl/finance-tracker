from datetime import date
from decimal import Decimal


def test_daily_points_aggregate_without_a_second_formula() -> None:
    from ft.domain.wealth import WealthStatus
    from ft.domain.wealth_calculation import DailyPoint, aggregate_daily_points

    points = (
        DailyPoint(date(2026, 7, 1), Decimal("100"), Decimal("110"), (Decimal("10"),) * 6, WealthStatus.COMPLETE, "a"),
        DailyPoint(date(2026, 7, 2), Decimal("110"), Decimal("115"), (Decimal("5"),) * 6, WealthStatus.STALE, "a"),
    )
    result = aggregate_daily_points(points)
    assert result.opening == Decimal("100")
    assert result.closing == Decimal("115")
    assert result.components == (Decimal("15"),) * 6
    assert result.status is WealthStatus.STALE
