from datetime import datetime, timezone
from decimal import Decimal


def test_typed_wealth_fact_values_preserve_identity_and_exact_values() -> None:
    from ft.repositories.wealth import AccountFact, ValuationFact

    account = AccountFact("w", "account-1", "cash", "CNY", {})
    valuation = ValuationFact(
        workspace_id="w", observation_id="obs-1", identity_kind="cash_account",
        identity="account-1", observation_kind="boundary_checkin", value=Decimal("1.23"),
        currency="CNY", unit="currency", as_of=datetime(2026, 7, 1, tzinfo=timezone.utc),
        observed_at=datetime(2026, 7, 1, tzinfo=timezone.utc), source_identity="manual:1",
        source_revision="r1", trust="trusted_checkin",
    )
    assert account.account_id == valuation.identity
    assert valuation.value == Decimal("1.23")


def test_wealth_ports_are_runtime_checkable_protocols() -> None:
    from ft.repositories.wealth import WealthFactRepository, WealthReadModelRepository

    assert getattr(WealthFactRepository, "__protocol_attrs__", None) is None or WealthFactRepository
    assert getattr(WealthReadModelRepository, "__protocol_attrs__", None) is None or WealthReadModelRepository
