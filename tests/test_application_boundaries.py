from dataclasses import FrozenInstanceError
from decimal import Decimal
from pathlib import Path

import pytest


def test_shared_application_dtos_are_immutable_and_decimal_safe():
    from ft.domain.application import (
        ChangeSetStatusDTO,
        ExportPayload,
        OperationResult,
        TextFinding,
    )

    finding = TextFinding(code="verification.unknown_account", message="unknown")
    payload = ExportPayload(rows=({"amount": "1.20"},), suggested_filename="rows.csv")
    status = ChangeSetStatusDTO(changed_files=("snapshot.yaml",), clean=False)
    result = OperationResult(
        ok=True,
        message="done",
        count=1,
        details={"amount": Decimal("1.20")},
    )

    assert finding.code == "verification.unknown_account"
    assert payload.rows[0]["amount"] == "1.20"
    assert status.changed_files == ("snapshot.yaml",)
    assert result.details["amount"] == Decimal("1.20")
    with pytest.raises(FrozenInstanceError):
        result.ok = False


def test_ports_are_runtime_checkable_with_hand_written_fakes():
    from ft.connectors import (
        ConnectorRegistry,
        MappingProvider,
        MarketDataProvider,
        SecretStore,
    )
    from ft.repositories.queries import (
        ChangeSetRepository,
        ReconciliationRepository,
        TransactionQueryRepository,
    )

    class FakeTransactions:
        def list_transactions(self, *, month=None, account=None, category=None):
            return []

    class FakeMarketData:
        def get_prices(self, tickers, *, quote_currency):
            return {}

    class FakeSecrets:
        def get_secret(self, provider, account=None):
            return {}

    class FakeMappings:
        def get_mapping(self, name):
            return []

    class FakeRegistry:
        def get_connector(self, provider):
            return object()

    class FakeChangeSet:
        def stage(self):
            pass

        def status(self):
            return ()

        def commit(self, message=None):
            return False

        def reset(self):
            return ()

    class FakeReconciliation:
        def state(self):
            return "idle"

        def start(self, *, month=None, date_from=None, date_to=None):
            return {}

        def continue_with_decisions(self):
            return {}

        def abort(self):
            return {}

    assert isinstance(FakeTransactions(), TransactionQueryRepository)
    assert isinstance(FakeMarketData(), MarketDataProvider)
    assert isinstance(FakeSecrets(), SecretStore)
    assert isinstance(FakeMappings(), MappingProvider)
    assert isinstance(FakeRegistry(), ConnectorRegistry)
    assert isinstance(FakeChangeSet(), ChangeSetRepository)
    assert isinstance(FakeReconciliation(), ReconciliationRepository)


def test_application_boundary_imports_do_not_touch_home(monkeypatch):
    def fail_home():
        raise AssertionError("application boundary import touched Path.home()")

    monkeypatch.setattr(Path, "home", fail_home)

    import ft.connectors
    import ft.domain.application
    import ft.repositories.queries
    import ft.runtime

    assert ft.runtime.ServiceBundle
