from datetime import datetime
from decimal import Decimal
from zoneinfo import ZoneInfo

import pytest

from ft.repositories.wealth import AccountFact, ValuationFact, WealthSourceItem


class FakeWealthFacts:
    def __init__(self) -> None:
        tz = ZoneInfo("Asia/Shanghai")
        self._valuations = (
            ValuationFact("w", "o1", "cash_account", "cash", "boundary_checkin", Decimal("100"), "CNY", "currency", datetime(2026, 7, 1, tzinfo=tz), datetime(2026, 7, 1, tzinfo=tz), "m:1", "r1", "trusted_checkin"),
            ValuationFact("w", "o2", "cash_account", "cash", "boundary_checkin", Decimal("125"), "CNY", "currency", datetime(2026, 8, 1, tzinfo=tz), datetime(2026, 8, 1, tzinfo=tz), "m:2", "r2", "trusted_checkin"),
        )

    def accounts(self): return (AccountFact("w", "cash", "cash", {}),)
    def valuations(self, *, starts_at, ends_at): return tuple(v for v in self._valuations if starts_at <= v.as_of <= ends_at)
    def lifecycle_events(self): return ()
    def capture_source_manifest(self): return "source-r2", (WealthSourceItem("valuation", "o2", "r2", "d"),)


def test_breakdown_uses_natural_month_boundaries_and_source_revision() -> None:
    from ft.application.wealth import WealthChangeService
    from ft.domain.wealth import WealthChangeQuery

    result = WealthChangeService(FakeWealthFacts()).breakdown(WealthChangeQuery("2026-07"))
    assert result.opening_net_worth == Decimal("100")
    assert result.closing_net_worth == Decimal("125")
    assert result.source_revision == "source-r2"


def test_breakdown_rejects_unconstructible_month() -> None:
    from ft.application.wealth import WealthChangeService
    from ft.domain.wealth import WealthChangeQuery, WealthError

    with pytest.raises(WealthError, match="wealth.report_not_constructible"):
        WealthChangeService(FakeWealthFacts()).breakdown(WealthChangeQuery("2026-06"))


def test_breakdown_fails_closed_but_returns_known_coverage() -> None:
    from ft.application.wealth import WealthChangeService
    from ft.domain.wealth import WealthChangeQuery, WealthStatus

    class PartialFacts(FakeWealthFacts):
        def accounts(self):
            return (*super().accounts(), AccountFact("w", "option", "option", {}))

    result = WealthChangeService(PartialFacts()).breakdown(WealthChangeQuery("2026-07"))
    assert result.status is WealthStatus.UNSUPPORTED
    assert result.opening_net_worth is None
    assert result.known_opening_net_worth == Decimal("100")
