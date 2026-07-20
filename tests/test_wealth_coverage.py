from decimal import Decimal

import pytest


def test_coverage_status_is_worst_and_complete_values_fail_closed() -> None:
    from ft.domain.wealth import CoverageDisposition, WealthError, WealthStatus
    from ft.domain.wealth_calculation import evaluate_coverage

    result = evaluate_coverage({"cash": CoverageDisposition.SUPPORTED, "option": CoverageDisposition.UNSUPPORTED})
    assert result.status is WealthStatus.UNSUPPORTED
    assert result.complete_values_available is False
    assert result.coverage_fingerprint
    with pytest.raises(WealthError, match="wealth.report_not_constructible"):
        evaluate_coverage({"cash": CoverageDisposition.MISSING})


def test_known_identity_keeps_excluded_adjustment_separate() -> None:
    from ft.domain.wealth_calculation import calculate_known_identity

    result = calculate_known_identity(
        opening=Decimal("100"), closing=Decimal("105"), components=(Decimal("2"),),
        excluded_coverage_adjustment=Decimal("3"),
    )
    assert result.known_unexplained_adjustment == Decimal("0")


def test_supported_asset_and_event_whitelist_fails_closed() -> None:
    from ft.domain.wealth_calculation import is_supported_wealth_input

    assert is_supported_wealth_input("cash", "salary")
    assert not is_supported_wealth_input("option", "exercise")
