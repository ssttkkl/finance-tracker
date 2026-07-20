from datetime import datetime, timezone


def test_lifecycle_intervals_and_coverage_changes_are_deterministic() -> None:
    from ft.domain.wealth import CoverageDisposition
    from ft.domain.wealth_calculation import LifecycleEvent, account_applicable, coverage_changed

    events = (
        LifecycleEvent("opened", datetime(2026, 7, 1, tzinfo=timezone.utc)),
        LifecycleEvent("closed", datetime(2026, 7, 3, tzinfo=timezone.utc)),
        LifecycleEvent("reactivated", datetime(2026, 7, 5, tzinfo=timezone.utc)),
    )
    assert account_applicable(events, datetime(2026, 7, 2, tzinfo=timezone.utc))
    assert not account_applicable(events, datetime(2026, 7, 4, tzinfo=timezone.utc))
    assert coverage_changed(CoverageDisposition.SUPPORTED, CoverageDisposition.MISSING)
    assert not coverage_changed(CoverageDisposition.NOT_APPLICABLE, CoverageDisposition.SUPPORTED)
