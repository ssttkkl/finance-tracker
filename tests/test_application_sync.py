from pathlib import Path

import pytest


class FakeConnector:
    def __init__(self, rows):
        self.rows = [dict(row) for row in rows]
        self.calls = []

    def fetch(self, command, *, credentials, mapping):
        self.calls.append((command, credentials, mapping))
        return [dict(row) for row in self.rows]


class FakeRegistry:
    def __init__(self, connector):
        self.connector = connector
        self.providers = []

    def get_connector(self, provider):
        self.providers.append(provider)
        return self.connector


class FakeSecrets:
    def __init__(self):
        self.calls = []

    def get_secret(self, provider, account=None):
        self.calls.append((provider, account))
        return {"token": provider}


class FakeMappings:
    def __init__(self):
        self.calls = []

    def get_mapping(self, name):
        self.calls.append(name)
        return {"name": name}


class FakeEvents:
    def __init__(self, external_ids=(), exact_rows=()):
        self.external_ids = set(external_ids)
        self.exact_rows = set(exact_rows)
        self.appended = None
        self.validated = []

    def validate_destination(self, provider, account):
        self.validated.append((provider, account))

    def existing_identities(self, provider, account):
        return set(self.external_ids), set(self.exact_rows)

    def append_events(self, rows):
        self.appended = [dict(row) for row in rows]
        return len(rows)


class FakeChanges:
    def __init__(self):
        self.staged = 0

    def stage(self):
        self.staged += 1


def _row(note, date="2026-06-01"):
    return {
        "date": date, "action": "deposit", "from_ticker": "",
        "to_ticker": "usd", "from_amount": "0", "to_amount": "1",
        "price": "1", "commission": "0", "commission_asset": "",
        "currency": "USD", "account_name": "Account", "note": note,
    }


@pytest.mark.parametrize("provider", ["kraken", "okx", "binance", "coinbase", "bybit"])
def test_exchange_providers_use_connector_secrets_mapping_and_repository(provider):
    from ft.application.sync import ConnectorSyncService
    from ft.domain.sync import ConnectorSyncCommand

    connector = FakeConnector([_row(f"{provider} tid:1")])
    registry = FakeRegistry(connector)
    secrets = FakeSecrets()
    mappings = FakeMappings()
    events = FakeEvents()
    changes = FakeChanges()
    service = ConnectorSyncService(registry, secrets, mappings, events, changes)

    result = service.sync(ConnectorSyncCommand(provider, "Account"))

    assert result.new_count == 1
    assert events.appended[0]["note"] == f"{provider} tid:1"
    assert events.validated == [(provider, "Account")]
    assert secrets.calls == [(provider, "Account")]
    assert mappings.calls == [f"sync:{provider}"]
    assert changes.staged == 1


def test_sync_deduplicates_external_ids_and_exact_rows_before_dry_run():
    from ft.application.sync import ConnectorSyncService, row_identity
    from ft.domain.sync import ConnectorSyncCommand

    duplicate_id = _row("kraken tid:old")
    duplicate_exact = _row("no external id")
    new = _row("kraken lid:new", date="2026-06-02")
    events = FakeEvents(
        external_ids={"tid:old"}, exact_rows={row_identity(duplicate_exact)},
    )
    changes = FakeChanges()
    service = ConnectorSyncService(
        FakeRegistry(FakeConnector([duplicate_id, duplicate_exact, new, new])),
        FakeSecrets(), FakeMappings(), events, changes,
    )

    result = service.sync(ConnectorSyncCommand("kraken", "Account", dry_run=True))

    assert result.fetched_count == 4
    assert result.new_count == 1
    assert result.skipped_count == 3
    assert events.appended is None
    assert changes.staged == 0


def test_sync_export_returns_payload_without_mutation():
    from ft.application.sync import ConnectorSyncService
    from ft.domain.sync import ConnectorSyncCommand

    events = FakeEvents()
    changes = FakeChanges()
    service = ConnectorSyncService(
        FakeRegistry(FakeConnector([_row("okx tid:1")])),
        FakeSecrets(), FakeMappings(), events, changes,
    )

    result = service.sync(ConnectorSyncCommand("okx", "Account", export=True))

    assert result.export.rows[0]["note"] == "okx tid:1"
    assert events.appended is None
    assert changes.staged == 0


def test_polymarket_explicit_wallet_does_not_read_secret_store():
    from ft.application.sync import ConnectorSyncService
    from ft.domain.sync import ConnectorSyncCommand

    secrets = FakeSecrets()
    connector = FakeConnector([_row("polymarket id:1 tx:0x1")])
    service = ConnectorSyncService(
        FakeRegistry(connector), secrets, FakeMappings(), FakeEvents(), FakeChanges(),
    )

    service.sync(ConnectorSyncCommand(
        "polymarket", "Account", proxy_wallet="0x" + "1" * 40, dry_run=True,
    ))

    assert secrets.calls == []
    assert connector.calls[0][1] == {}


def test_sync_application_imports_do_not_touch_home(monkeypatch):
    def fail_home():
        raise AssertionError("sync application import touched home")

    monkeypatch.setattr(Path, "home", fail_home)
    import ft.application.sync
    import ft.domain.sync

    assert ft.application.sync.ConnectorSyncService


def test_cli_sync_routes_all_options_through_service(monkeypatch, tmp_path, capsys):
    from ft import cli
    from ft.domain.application import ExportPayload
    from ft.domain.sync import ConnectorSyncResultDTO

    calls = []

    class Sync:
        def sync(self, command):
            calls.append(command)
            return ConnectorSyncResultDTO(
                provider=command.provider, account=command.account,
                fetched_count=2, new_count=1, skipped_count=1,
                export=ExportPayload((_row("kraken tid:1"),), fieldnames=tuple(_row("").keys())) if command.export else None,
            )

    bundle = type("Bundle", (), {"connector_sync": Sync()})()
    monkeypatch.setattr("ft.cli.build_local_services", lambda _root: bundle)
    monkeypatch.setattr("ft.cli.write_csv_export", lambda payload, output: calls.append(output))
    output = tmp_path / "sync.csv"

    cli.main([
        "stock", "sync", "kraken", "--account", "Account",
        "--since", "2026-06-01", "--symbol", "BTC/USD", "--dry-run",
        "-o", str(output),
    ])

    command = calls[0]
    assert command.provider == "kraken"
    assert command.since == "2026-06-01"
    assert command.symbols == ("BTC/USD",)
    assert command.dry_run is True
    assert calls[1] == str(output)
    assert "新增行: 1" in capsys.readouterr().out
