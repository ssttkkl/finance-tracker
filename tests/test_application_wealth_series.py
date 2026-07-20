from datetime import date
from decimal import Decimal

import pytest

from ft.domain.wealth import WealthError, WealthSeriesQuery, WealthStatus
from ft.domain.wealth_calculation import DailyPoint


class FakeDailyFacts:
    def daily_points(self, *, date_from, date_to):
        return (
            DailyPoint(date(2026, 7, 1), Decimal("100"), Decimal("110"), (Decimal("1"),) * 6, WealthStatus.COMPLETE, "a"),
            DailyPoint(date(2026, 7, 2), Decimal("110"), Decimal("112"), (Decimal("2"),) * 6, WealthStatus.COMPLETE, "a"),
        )


def test_series_validates_dates_and_derives_envelope_revision() -> None:
    from ft.application.wealth import WealthChangeService

    result = WealthChangeService(FakeDailyFacts()).series(
        WealthSeriesQuery(date(2026, 7, 1), date(2026, 7, 3), "day")
    )
    assert len(result.points) == 2
    assert result.source_revision
    with pytest.raises(WealthError, match="wealth.invalid_date_range"):
        WealthChangeService(FakeDailyFacts()).series(WealthSeriesQuery(date(2026, 7, 2), date(2026, 7, 2), "day"))
    with pytest.raises(WealthError, match="wealth.range_too_large"):
        WealthChangeService(FakeDailyFacts()).series(WealthSeriesQuery(date(2025, 1, 1), date(2026, 1, 3), "day"))
