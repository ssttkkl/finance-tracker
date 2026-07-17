import csv
from decimal import Decimal

import yaml

from ft.schema import CASH_CSV_FIELDS, CSV_FIELDS
from test_postgres_adapter import _database


def _write_csv(path, fields, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def _ledger_fixture(root):
    root.mkdir()
    (root / "accounts.yaml").write_text(yaml.safe_dump({"accounts": [
        {"name": "Cash", "type": "cash", "currency": "CNY", "active": True},
        {
            "name": "Broker", "type": "security", "currency": "USD",
            "active": True, "base_currencies": ["USD"],
        },
    ]}, allow_unicode=True, sort_keys=False), encoding="utf-8")
    _write_csv(root / "records" / "cash" / "2026-07.csv", CASH_CSV_FIELDS, [{
        "record_id": "cash-1", "date": "2026-07-01 09:00:00", "amount": "1000.00",
        "currency": "CNY", "counterparty": "Employer", "description": "Salary",
        "category": "income", "account_name": "Cash", "source": "fixture",
        "bill_source": "bank", "transfer_account": "", "locked": "",
        "offset_group": "", "offset_role": "", "offset_strength": "",
        "offset_source": "", "offset_rule_hint": "", "offset_match_type": "",
        "proposed_action": "",
    }, {
        "record_id": "cash-2", "date": "2026-07-02 10:00:00", "amount": "-20.50",
        "currency": "CNY", "counterparty": "Cafe", "description": "Coffee",
        "category": "expense", "account_name": "Cash", "source": "fixture",
        "bill_source": "wallet", "transfer_account": "", "locked": "",
        "offset_group": "", "offset_role": "", "offset_strength": "",
        "offset_source": "", "offset_rule_hint": "", "offset_match_type": "",
        "proposed_action": "",
    }])
    _write_csv(root / "records" / "security" / "2026-07-03.csv", CSV_FIELDS, [{
        "date": "2026-07-03 11:00:00", "action": "deposit", "from_ticker": "",
        "to_ticker": "usd", "from_amount": "0", "to_amount": "100",
        "price": "1", "commission": "0", "commission_asset": "",
        "currency": "USD", "account_name": "Broker", "note": "seed",
    }])
    snapshot = {
        "updated_at": "2026-07-03",
        "accounts": {
            "cash": {"Cash": {"CNY": 979.5}},
            "loan": {}, "lend": {},
            "security": {"Broker": {
                "currency": "USD",
                "positions": {"usd": {"shares": 100, "total_cost": 100, "cost_currency": "USD"}},
            }},
        },
    }
    (root / "snapshot.yaml").write_text(
        yaml.safe_dump(snapshot, allow_unicode=True, sort_keys=False), encoding="utf-8"
    )
    return root


def _service(tmp_path):
    from ft.adapters.local_migration import LocalMigrationSource
    from ft.adapters.postgres.migration import PostgresMigrationTarget
    from ft.application.migration import MigrationService

    sessions, _ = _database()
    ledger = _ledger_fixture(tmp_path / "ledger")
    source = LocalMigrationSource(ledger)
    target = PostgresMigrationTarget(sessions, "workspace-a")
    return MigrationService(source, target), source, target, sessions


def test_inspect_reports_deterministic_source_inventory(tmp_path):
    service, _, _, _ = _service(tmp_path)

    first = service.inspect()
    second = service.inspect()

    assert first == second
    assert first.account_count == 2
    assert first.cash_transaction_count == 2
    assert first.investment_event_count == 1
    assert first.raw_file_count == 4
    assert first.source_digest.startswith("sha256:")


def test_import_is_atomic_idempotent_and_preserves_raw_provenance(tmp_path):
    service, _, target, _ = _service(tmp_path)

    first = service.import_ledger()
    second = service.import_ledger()

    assert first.imported is True
    assert first.counts == {"accounts": 2, "cash_transactions": 2, "investment_events": 1}
    assert second.imported is False
    assert second.batch_id == first.batch_id
    loaded = target.load()
    assert len(loaded.accounts) == 2
    assert len(loaded.cashflows) == 2
    assert len(loaded.investments) == 1
    assert target.raw_record_count(first.batch_id) == 6


def test_shadow_comparison_covers_balances_summaries_portfolio_and_net_worth(tmp_path):
    service, _, target, sessions = _service(tmp_path)
    service.import_ledger()

    report = service.verify()

    assert report.ok is True
    assert report.findings == ()
    assert report.checks == {
        "accounts": True,
        "cash_transactions": True,
        "investment_events": True,
        "snapshot": True,
        "account_balances": True,
        "cashflow_summary": True,
        "portfolio": True,
        "net_worth_projection": True,
    }

    from sqlalchemy import select
    from ft.adapters.postgres.models import CashTransactionModel
    with sessions.begin() as session:
        row = session.scalar(select(CashTransactionModel).where(
            CashTransactionModel.workspace_id == "workspace-a",
            CashTransactionModel.record_id == "cash-2",
        ))
        row.amount = Decimal("-99")

    mismatch = service.verify()
    assert mismatch.ok is False
    assert mismatch.checks["cash_transactions"] is False
    assert mismatch.checks["cashflow_summary"] is False
    assert {finding.component for finding in mismatch.findings} >= {
        "cash_transactions", "cashflow_summary"
    }


def test_export_round_trips_to_local_migration_source(tmp_path):
    service, source, _, _ = _service(tmp_path)
    service.import_ledger()
    destination = tmp_path / "exported"

    result = service.export(destination)

    from ft.adapters.local_migration import LocalMigrationSource
    exported = LocalMigrationSource(destination).load()
    original = source.load()
    assert result.account_count == 2
    assert exported.accounts == original.accounts
    assert exported.cashflows == original.cashflows
    assert exported.investments == original.investments
    assert exported.snapshot == original.snapshot
