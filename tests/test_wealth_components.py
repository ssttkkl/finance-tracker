from decimal import Decimal


def test_component_identity_is_stable_by_period_and_versioned_by_result() -> None:
    from ft.domain.wealth import ComponentKind, WealthStatus
    from ft.domain.wealth_calculation import build_component

    first = build_component("w", "2026-07-01", "2026-08-01", "month", ComponentKind.EXTERNAL_CASHFLOW, "all", Decimal("1"), WealthStatus.COMPLETE, "source-a")
    second = build_component("w", "2026-07-01", "2026-08-01", "month", ComponentKind.EXTERNAL_CASHFLOW, "all", Decimal("1"), WealthStatus.COMPLETE, "source-b")
    assert first.component_key == second.component_key
    assert first.result_revision != second.result_revision
    assert first.component_id != second.component_id
