from datetime import date, datetime, timezone
from decimal import Decimal

import pytest


def test_wealth_domain_values_are_immutable_and_canonical() -> None:
    from ft.domain.wealth import (
        ComponentKind,
        WealthChangeQuery,
        WealthError,
        WealthSeriesQuery,
        WealthStatus,
        canonical_bytes,
        canonical_digest,
    )

    assert WealthStatus.COMPLETE < WealthStatus.STALE < WealthStatus.PARTIAL < WealthStatus.UNSUPPORTED
    assert tuple(ComponentKind) == (
        ComponentKind.EXTERNAL_CASHFLOW,
        ComponentKind.INVESTMENT_RETURN,
        ComponentKind.FX_IMPACT,
        ComponentKind.LIABILITY_REVALUATION,
        ComponentKind.EXPLAINED_OTHER_ADJUSTMENT,
        ComponentKind.UNEXPLAINED_ADJUSTMENT,
    )
    assert WealthChangeQuery("2026-07").month == "2026-07"
    assert WealthSeriesQuery(date(2026, 7, 1), date(2026, 7, 2), "day").granularity == "day"
    payload = {"at": datetime(2026, 7, 1, tzinfo=timezone.utc), "amount": Decimal("1.2300")}
    assert canonical_bytes(payload) == b'{"amount":"1.2300","at":"2026-07-01T00:00:00+00:00"}'
    assert canonical_digest(payload) == canonical_digest(payload)
    with pytest.raises(WealthError, match="wealth.invalid_month"):
        WealthChangeQuery("2026-7")


@pytest.mark.parametrize("value", ["NaN", "Infinity", "1e999"])
def test_wealth_decimal_validation_fails_closed(value: str) -> None:
    from ft.domain.wealth import WealthError, decimal_value

    with pytest.raises(WealthError, match="wealth.invalid_decimal"):
        decimal_value(value)
