from pathlib import Path
from decimal import Decimal
import csv

import pytest
import yaml


def test_domain_imports_do_not_touch_home_or_ft_globals(monkeypatch, tmp_path):
    blocked_home = tmp_path / "blocked-home"

    def fail_home():
        raise AssertionError("domain import should not ask for Path.home()")

    monkeypatch.setattr(Path, "home", fail_home)

    import ft.domain.accounts as domain_accounts
    import ft.domain.money as domain_money

    assert domain_accounts.AccountType.CASH.value == "cash"
    assert domain_money.Money("1.20", "CNY").amount == domain_money.Decimal("1.20")
    assert not blocked_home.exists()


def test_application_and_local_csv_imports_do_not_touch_home(monkeypatch):
    def fail_home():
        raise AssertionError("Path.home invoked during service import")

    monkeypatch.setattr(Path, "home", fail_home)

    import ft.adapters.local_csv
    import ft.application.cashflow
    import ft.application.reconcile

    assert ft.adapters.local_csv.LocalCsvUnitOfWork
    assert ft.application.cashflow.CashflowService
    assert ft.application.reconcile.ReconcileService


def test_account_service_writes_only_to_injected_ledger(tmp_path, monkeypatch):
    home = tmp_path / "home"
    ledger = tmp_path / "ledger"
    monkeypatch.setenv("HOME", str(home))

    from ft.adapters.local_csv.accounts import LocalCsvUnitOfWork
    from ft.application.accounts import AccountService

    result = AccountService(LocalCsvUnitOfWork(ledger)).create_account(
        "测试账户", "cash", "CNY"
    )

    assert result.ok is True
    assert result.account.name == "测试账户"
    assert (ledger / "accounts.yaml").exists()
    assert not (home / ".ft").exists()


def test_account_service_returns_structured_duplicate_and_invalid_errors(tmp_path):
    from ft.adapters.local_csv.accounts import LocalCsvUnitOfWork
    from ft.application.accounts import AccountService

    service = AccountService(LocalCsvUnitOfWork(tmp_path))
    first = service.create_account("测试账户", "cash", "CNY")
    duplicate = service.create_account("测试账户", "loan", "CNY")
    invalid = service.create_account("坏账户", "bogus", "CNY")

    assert first.ok is True
    assert duplicate.ok is False
    assert duplicate.error.code == "account.duplicate"
    assert invalid.ok is False
    assert invalid.error.code == "account.invalid_type"


def test_cli_acct_add_reaches_account_service(monkeypatch, capsys):
    from ft import cli
    from ft.domain.accounts import AccountDTO, AccountResult

    calls = {}

    class SpyService:
        def __init__(self, uow):
            calls["uow"] = uow

        def create_account(self, name, type_, currency):
            calls["args"] = (name, type_, currency)
            return AccountResult.success(
                AccountDTO(name=name, type="cash", currency="CNY", active=True)
            )

    monkeypatch.setattr("ft.acct.AccountService", SpyService)

    cli.main(["acct", "add", "  测试账户  ", "--type", "cash", "--currency", "CNY"])

    assert calls["args"] == ("测试账户", "cash", "CNY")
    assert "已添加账户" in capsys.readouterr().out


def test_local_csv_uow_commit_and_rollback(tmp_path):
    from ft.adapters.local_csv.accounts import LocalCsvUnitOfWork
    from ft.domain.accounts import AccountDTO

    with LocalCsvUnitOfWork(tmp_path) as uow:
        uow.accounts.add(AccountDTO(name="提交账户", type="cash", currency="CNY"))
        uow.commit()

    data = yaml.safe_load((tmp_path / "accounts.yaml").read_text(encoding="utf-8"))
    assert any(a["name"] == "提交账户" for a in data["accounts"])

    try:
        with LocalCsvUnitOfWork(tmp_path) as uow:
            uow.accounts.add(AccountDTO(name="回滚账户", type="cash", currency="CNY"))
            raise RuntimeError("boom")
    except RuntimeError:
        pass

    data = yaml.safe_load((tmp_path / "accounts.yaml").read_text(encoding="utf-8"))
    assert not any(a["name"] == "回滚账户" for a in data["accounts"])


def _seed_accounts(ledger: Path) -> None:
    ledger.mkdir(parents=True, exist_ok=True)
    (ledger / "accounts.yaml").write_text(
        "accounts:\n"
        "  - name: Cash\n"
        "    type: cash\n"
        "    currency: CNY\n"
        "    active: true\n"
        "  - name: Card\n"
        "    type: loan\n"
        "    currency: CNY\n"
        "    active: true\n",
        encoding="utf-8",
    )


def _read_rows(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as f:
        return list(csv.DictReader(f))


def _assert_numeric_scalar(value, expected: str) -> None:
    assert isinstance(value, int | float)
    assert Decimal(str(value)) == Decimal(expected)


def test_manual_add_service_writes_records_and_snapshot_only_to_injected_ledger(tmp_path, monkeypatch):
    home = tmp_path / "home"
    ledger = tmp_path / "ledger"
    monkeypatch.setenv("HOME", str(home))
    _seed_accounts(ledger)

    from ft.adapters.local_csv import LocalCsvUnitOfWork
    from ft.application.cashflow import CashflowService

    result = CashflowService(LocalCsvUnitOfWork(ledger)).add_manual_transaction(
        amount=Decimal("-12.34"),
        counterparty="Coffee",
        account_name="Cash",
        date="2026-07-16 08:00:00",
    )

    assert result.ok is True
    rows = _read_rows(ledger / "records" / "cash" / "2026-07.csv")
    assert rows[0]["amount"] == "-12.34"
    snap = yaml.safe_load((ledger / "snapshot.yaml").read_text(encoding="utf-8"))
    _assert_numeric_scalar(snap["accounts"]["cash"]["Cash"]["CNY"], "-12.34")
    assert not (home / ".ft").exists()


def test_checkin_service_sets_balance_and_returns_structured_missing_account(tmp_path, monkeypatch):
    home = tmp_path / "home"
    ledger = tmp_path / "ledger"
    monkeypatch.setenv("HOME", str(home))
    _seed_accounts(ledger)

    from ft.adapters.local_csv import LocalCsvUnitOfWork
    from ft.application.cashflow import CashflowService

    service = CashflowService(LocalCsvUnitOfWork(ledger))
    result = service.checkin_balance(
        account_name="Cash",
        balance=Decimal("100.50"),
        date="2026-07-16",
    )
    missing = service.checkin_balance(
        account_name="Missing",
        balance=Decimal("1"),
        date="2026-07-16",
    )

    assert result.ok is True
    rows = _read_rows(ledger / "records" / "cash" / "2026-07.csv")
    assert rows[0]["category"] == "checkin"
    snap = yaml.safe_load((ledger / "snapshot.yaml").read_text(encoding="utf-8"))
    _assert_numeric_scalar(snap["accounts"]["cash"]["Cash"]["CNY"], "100.50")
    assert missing.ok is False
    assert missing.error.code == "account.not_found"
    assert not (home / ".ft").exists()


def test_transfer_service_writes_two_locked_rows_and_snapshot(tmp_path, monkeypatch):
    home = tmp_path / "home"
    ledger = tmp_path / "ledger"
    monkeypatch.setenv("HOME", str(home))
    _seed_accounts(ledger)

    from ft.adapters.local_csv import LocalCsvUnitOfWork
    from ft.application.cashflow import TransferService

    result = TransferService(LocalCsvUnitOfWork(ledger)).transfer(
        from_name="Cash",
        to_name="Card",
        amount=Decimal("20"),
        date="2026-07-16",
        time_str="09:30:00",
    )

    assert result.ok is True
    cash_rows = _read_rows(ledger / "records" / "cash" / "2026-07.csv")
    loan_rows = _read_rows(ledger / "records" / "loan" / "2026-07.csv")
    assert cash_rows[0]["category"] == "transfer_out"
    assert cash_rows[0]["locked"] == "1"
    assert loan_rows[0]["category"] == "transfer_in"
    snap = yaml.safe_load((ledger / "snapshot.yaml").read_text(encoding="utf-8"))
    assert snap["accounts"]["cash"]["Cash"]["CNY"] == -20
    assert snap["accounts"]["loan"]["Card"]["CNY"] == 20
    assert not (home / ".ft").exists()


def test_transfer_service_merges_cash_rows_into_existing_monthly_ledger(tmp_path):
    ledger = tmp_path / "ledger"
    _seed_accounts(ledger)
    cash_file = ledger / "records" / "cash" / "2026-07.csv"
    loan_file = ledger / "records" / "loan" / "2026-07.csv"
    cash_file.parent.mkdir(parents=True)
    loan_file.parent.mkdir(parents=True)
    from ft.schema import CASH_CSV_FIELDS
    with cash_file.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=CASH_CSV_FIELDS)
        writer.writeheader()
        writer.writerow({
            "date": "2026-07-16 14:00:00", "amount": "-1", "currency": "CNY",
            "counterparty": "", "description": "existing", "category": "expense",
            "account_name": "Cash", "source": "manual", "bill_source": "",
            "transfer_account": "", "locked": "",
        })
    with loan_file.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=CASH_CSV_FIELDS)
        writer.writeheader()
        writer.writerow({
            "date": "2026-07-16 14:00:00", "amount": "1", "currency": "CNY",
            "counterparty": "", "description": "existing", "category": "income",
            "account_name": "Card", "source": "manual", "bill_source": "",
            "transfer_account": "", "locked": "",
        })

    from ft.adapters.local_csv import LocalCsvUnitOfWork
    from ft.application.cashflow import TransferService

    assert TransferService(LocalCsvUnitOfWork(ledger)).transfer(
        from_name="Cash", to_name="Card", amount=Decimal("20"),
        date="2026-07-16", time_str="09:30:00",
    ).ok is True

    rows = _read_rows(cash_file)
    assert [row["date"] for row in rows] == ["2026-07-16 09:30:00", "2026-07-16 14:00:00"]
    assert rows[0]["category"] == "transfer_out"
    loan_rows = _read_rows(loan_file)
    assert [row["date"] for row in loan_rows] == ["2026-07-16 09:30:00", "2026-07-16 14:00:00"]
    assert loan_rows[0]["category"] == "transfer_in"
    assert not (ledger / "records" / "cash" / "2026-07-16.csv").exists()
    assert not (ledger / "records" / "loan" / "2026-07-16.csv").exists()


@pytest.mark.parametrize("account_type", ("cash", "loan", "lend"))
def test_cashflow_service_rejects_legacy_daily_cash_ledger(tmp_path, account_type):
    ledger = tmp_path / "ledger"
    _seed_accounts(ledger)
    legacy_file = ledger / "records" / account_type / "2026-07-16.csv"
    legacy_file.parent.mkdir(parents=True)
    from ft.schema import CASH_CSV_FIELDS
    with legacy_file.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=CASH_CSV_FIELDS)
        writer.writeheader()
        writer.writerow({
            "date": "2026-07-16 09:00:00", "amount": "1", "currency": "CNY",
            "counterparty": "", "description": "legacy", "category": "income",
            "account_name": "Cash", "source": "manual", "bill_source": "",
            "transfer_account": "", "locked": "",
        })

    from ft.adapters.local_csv import LocalCsvUnitOfWork
    from ft.application.cashflow import CashflowService

    with pytest.raises(ValueError, match="legacy daily cash ledger"):
        CashflowService(LocalCsvUnitOfWork(ledger)).add_manual_transaction(
            amount=Decimal("1"), counterparty="manual", account_name="Cash",
            date="2026-07-16 10:00:00",
        )

    assert legacy_file.exists()
    assert not (ledger / "records" / "cash" / "2026-07.csv").exists()


@pytest.mark.parametrize("reader", ("report", "snapshot", "reconcile"))
def test_raw_cash_record_readers_reject_legacy_daily_ledger(tmp_path, reader):
    records_dir = tmp_path / "records"
    legacy_file = records_dir / "cash" / "2026-07-16.csv"
    legacy_file.parent.mkdir(parents=True)
    legacy_file.write_text("date,amount\n2026-07-16 09:00:00,1\n", encoding="utf-8")

    with pytest.raises(ValueError, match="legacy daily cash ledger"):
        if reader == "report":
            from ft.report import _read_records
            _read_records(records_dir)
        elif reader == "snapshot":
            from ft.snapshot import rebuild_snapshot_from_records
            rebuild_snapshot_from_records(records_dir, snapshot_path=tmp_path / "snapshot.yaml")
        else:
            from ft.reconcile import _load_entries
            _load_entries(records_dir)


def test_report_networth_rejects_legacy_daily_cash_ledger(tmp_path):
    records_dir = tmp_path / "records"
    legacy_file = records_dir / "cash" / "2026-07-16.csv"
    legacy_file.parent.mkdir(parents=True)
    legacy_file.write_text("date,amount\n2026-07-16 09:00:00,1\n", encoding="utf-8")

    from ft.report import report_networth

    with pytest.raises(ValueError, match="legacy daily cash ledger"):
        report_networth(records_dir)


def test_transfer_service_writes_unified_investment_deposit_for_security_account(tmp_path, monkeypatch):
    home = tmp_path / "home"
    monkeypatch.setenv("HOME", str(home))
    ledger = tmp_path / "ledger"
    ledger.mkdir()
    (ledger / "accounts.yaml").write_text(
        "accounts:\n"
        "  - name: Cash\n"
        "    type: cash\n"
        "    currency: CNY\n"
        "    active: true\n"
        "  - name: Brokerage\n"
        "    type: security\n"
        "    currency: USD\n"
        "    base_currencies: [USD]\n"
        "    active: true\n",
        encoding="utf-8",
    )

    from ft.adapters.local_csv import LocalCsvUnitOfWork
    from ft.application.cashflow import TransferService
    from ft.schema import CSV_FIELDS

    result = TransferService(LocalCsvUnitOfWork(ledger)).transfer(
        from_name="Cash", to_name="Brokerage", amount=Decimal("70"),
        to_amount=Decimal("10"), date="2026-07-16", time_str="09:30:00",
    )

    assert result.ok is True
    cash_rows = _read_rows(ledger / "records" / "cash" / "2026-07.csv")
    assert cash_rows[0]["category"] == "transfer_out"
    assert cash_rows[0]["locked"] == "1"
    assert cash_rows[0]["transfer_account"] == "Brokerage"
    security_path = ledger / "records" / "security" / "2026-07-16.csv"
    with security_path.open(encoding="utf-8") as f:
        reader = csv.DictReader(f)
        assert reader.fieldnames == CSV_FIELDS
        security_rows = list(reader)
    assert security_rows == [{
        "date": "2026-07-16 09:30:00", "action": "deposit",
        "from_ticker": "", "to_ticker": "usd", "from_amount": "0",
        "to_amount": "10", "price": "1", "commission": "0",
        "commission_asset": "", "currency": "USD", "account_name": "Brokerage",
        "note": "transfer from:Cash",
    }]
    snapshot = yaml.safe_load((ledger / "snapshot.yaml").read_text(encoding="utf-8"))
    brokerage = snapshot["accounts"]["security"]["Brokerage"]
    assert brokerage["currency"] == "USD"
    assert brokerage["positions"]["usd"] == {
        "shares": 10.0,
        "total_cost": 10.0,
        "cost_currency": "USD",
    }
    assert not (home / ".ft").exists()


def test_transfer_service_writes_unified_investment_withdrawal_for_security_account(tmp_path):
    ledger = tmp_path / "ledger"
    ledger.mkdir()
    (ledger / "accounts.yaml").write_text(
        "accounts:\n"
        "  - name: Brokerage\n"
        "    type: security\n"
        "    currency: USD\n"
        "    base_currencies: [USD]\n"
        "    active: true\n"
        "  - name: Cash\n"
        "    type: cash\n"
        "    currency: CNY\n"
        "    active: true\n",
        encoding="utf-8",
    )
    (ledger / "snapshot.yaml").write_text(
        "accounts:\n"
        "  security:\n"
        "    Brokerage:\n"
        "      currency: USD\n"
        "      positions:\n"
        "        usd:\n"
        "          shares: 10\n"
        "          total_cost: 10\n"
        "          cost_currency: USD\n"
        "  cash: {}\n"
        "  loan: {}\n"
        "  lend: {}\n",
        encoding="utf-8",
    )

    from ft.adapters.local_csv import LocalCsvUnitOfWork
    from ft.application.cashflow import TransferService
    from ft.schema import CSV_FIELDS

    result = TransferService(LocalCsvUnitOfWork(ledger)).transfer(
        from_name="Brokerage", to_name="Cash", amount=Decimal("10"),
        to_amount=Decimal("70"), date="2026-07-16", time_str="10:00:00",
    )

    assert result.ok is True
    with (ledger / "records" / "security" / "2026-07-16.csv").open(encoding="utf-8") as f:
        reader = csv.DictReader(f)
        assert reader.fieldnames == CSV_FIELDS
        assert list(reader) == [{
            "date": "2026-07-16 10:00:00", "action": "withdraw",
            "from_ticker": "usd", "to_ticker": "", "from_amount": "10",
            "to_amount": "0", "price": "1", "commission": "0",
            "commission_asset": "", "currency": "USD", "account_name": "Brokerage",
            "note": "transfer to:Cash",
        }]
    cash_rows = _read_rows(ledger / "records" / "cash" / "2026-07.csv")
    assert cash_rows[0]["category"] == "transfer_in"
    assert cash_rows[0]["locked"] == "1"
    assert cash_rows[0]["transfer_account"] == "Brokerage"
    snapshot = yaml.safe_load((ledger / "snapshot.yaml").read_text(encoding="utf-8"))
    assert snapshot["accounts"]["security"]["Brokerage"]["positions"]["usd"]["shares"] == 0.0
    assert snapshot["accounts"]["cash"]["Cash"]["CNY"] == 70.0


def test_transfer_service_stores_crypto_event_in_security_ledger(tmp_path):
    ledger = tmp_path / "ledger"
    ledger.mkdir()
    (ledger / "accounts.yaml").write_text(
        "accounts:\n"
        "  - name: Cash\n"
        "    type: cash\n"
        "    currency: CNY\n"
        "    active: true\n"
        "  - name: Kraken\n"
        "    type: crypto\n"
        "    currency: USD\n"
        "    base_currencies: [USD]\n"
        "    active: true\n",
        encoding="utf-8",
    )

    from ft.adapters.local_csv import LocalCsvUnitOfWork
    from ft.application.cashflow import TransferService
    from ft.schema import CSV_FIELDS

    result = TransferService(LocalCsvUnitOfWork(ledger)).transfer(
        from_name="Cash", to_name="Kraken", amount=Decimal("70"),
        to_amount=Decimal("10"), date="2026-07-16", time_str="09:30:00",
    )

    assert result.ok is True
    security_path = ledger / "records" / "security" / "2026-07-16.csv"
    with security_path.open(encoding="utf-8") as f:
        reader = csv.DictReader(f)
        assert reader.fieldnames == CSV_FIELDS
        assert list(reader)[0]["account_name"] == "Kraken"
    assert not (ledger / "records" / "crypto").exists()


def test_investment_repository_rejects_legacy_crypto_record_directory(tmp_path):
    ledger = tmp_path / "ledger"
    crypto_dir = ledger / "records" / "crypto"
    crypto_dir.mkdir(parents=True)
    legacy_file = crypto_dir / "2026-07-16.csv"
    legacy_file.write_text(
        "date,action,from_ticker,to_ticker,from_amount,to_amount,price,commission,commission_asset,currency,account_name,note\n"
        "2026-07-16 09:00:00,deposit,,usd,0,10,1,0,,USD,Kraken,legacy location\n",
        encoding="utf-8",
    )

    from ft.adapters.local_csv import LocalCsvUnitOfWork

    with LocalCsvUnitOfWork(ledger) as uow:
        with pytest.raises(ValueError, match="legacy crypto ledger"):
            uow.investments.list()

    assert legacy_file.exists()


def test_investment_repository_reads_only_canonical_security_events(tmp_path):
    ledger = tmp_path / "ledger"
    security_dir = ledger / "records" / "security"
    security_dir.mkdir(parents=True)
    (security_dir / "2026-07-16.csv").write_text(
        "date,action,from_ticker,to_ticker,from_amount,to_amount,price,commission,commission_asset,currency,account_name,note\n"
        "2026-07-16 09:00:00,deposit,,usd,0,10,1,0,,USD,Kraken,canonical event\n",
        encoding="utf-8",
    )

    from ft.adapters.local_csv import LocalCsvUnitOfWork
    from ft.schema import CSV_FIELDS

    with LocalCsvUnitOfWork(ledger) as uow:
        events = uow.investments.list()

    assert events == [{
        "date": "2026-07-16 09:00:00",
        "action": "deposit",
        "from_ticker": "",
        "to_ticker": "usd",
        "from_amount": "0",
        "to_amount": "10",
        "price": "1",
        "commission": "0",
        "commission_asset": "",
        "currency": "USD",
        "account_name": "Kraken",
        "note": "canonical event",
        "_record_type": "security",
        "_record_file": str(security_dir / "2026-07-16.csv"),
    }]
    assert set(events[0]) == {*CSV_FIELDS, "_record_type", "_record_file"}


def test_transfer_service_rejects_same_name_investment_accounts(tmp_path):
    ledger = tmp_path / "ledger"
    ledger.mkdir()
    (ledger / "accounts.yaml").write_text(
        "accounts:\n"
        "  - name: Cash\n"
        "    type: cash\n"
        "    currency: CNY\n"
        "    active: true\n"
        "  - name: Venue\n"
        "    type: security\n"
        "    currency: USD\n"
        "    base_currencies: [USD]\n"
        "    active: true\n"
        "  - name: Venue\n"
        "    type: crypto\n"
        "    currency: HKD\n"
        "    base_currencies: [HKD]\n"
        "    active: true\n",
        encoding="utf-8",
    )

    from ft.adapters.local_csv import LocalCsvUnitOfWork
    from ft.application.cashflow import TransferService

    result = TransferService(LocalCsvUnitOfWork(ledger)).transfer(
        from_name="Cash", to_name="Venue", amount=Decimal("70"),
        to_amount=Decimal("10"), to_currency="USD", date="2026-07-16",
    )

    assert result.ok is False
    assert result.error.code == "transfer.ambiguous_investment_account"
    assert result.error.details == {"account_name": "Venue"}
    assert not (ledger / "records").exists()
    assert not (ledger / "snapshot.yaml").exists()


def test_transfer_service_rejects_same_name_and_currency_investment_account(tmp_path):
    ledger = tmp_path / "ledger"
    ledger.mkdir()
    (ledger / "accounts.yaml").write_text(
        "accounts:\n"
        "  - name: Cash\n"
        "    type: cash\n"
        "    currency: CNY\n"
        "    active: true\n"
        "  - name: Venue\n"
        "    type: security\n"
        "    currency: USD\n"
        "    base_currencies: [USD]\n"
        "    active: true\n"
        "  - name: Venue\n"
        "    type: crypto\n"
        "    currency: USD\n"
        "    base_currencies: [USD]\n"
        "    active: true\n",
        encoding="utf-8",
    )

    from ft.adapters.local_csv import LocalCsvUnitOfWork
    from ft.application.cashflow import TransferService

    result = TransferService(LocalCsvUnitOfWork(ledger)).transfer(
        from_name="Cash", to_name="Venue", amount=Decimal("70"),
        to_amount=Decimal("10"), to_currency="USD", date="2026-07-16",
    )

    assert result.ok is False
    assert result.error.code == "transfer.ambiguous_investment_account"
    assert result.error.details == {"account_name": "Venue"}
    assert not (ledger / "records").exists()
    assert not (ledger / "snapshot.yaml").exists()


@pytest.mark.parametrize("duplicate_type, duplicate_currency", (
    ("crypto", "HKD"),
    ("crypto", "USD"),
))
def test_account_service_rejects_duplicate_investment_display_name(
    tmp_path, duplicate_type, duplicate_currency,
):
    from ft.adapters.local_csv import LocalCsvUnitOfWork
    from ft.application.accounts import AccountService

    service = AccountService(LocalCsvUnitOfWork(tmp_path))
    first = service.create_account("Venue", "security", "USD")
    before = (tmp_path / "accounts.yaml").read_bytes()
    duplicate = service.create_account("Venue", duplicate_type, duplicate_currency)

    assert first.ok is True
    assert duplicate.ok is False
    assert duplicate.error.code == "account.duplicate_investment_name"
    assert duplicate.error.details == {"name": "Venue"}
    assert (tmp_path / "accounts.yaml").read_bytes() == before


def test_account_service_rename_rejects_duplicate_investment_display_name(tmp_path):
    from ft.adapters.local_csv import LocalCsvUnitOfWork
    from ft.application.accounts import AccountService

    service = AccountService(LocalCsvUnitOfWork(tmp_path))
    assert service.create_account("Venue", "security", "USD").ok is True
    assert service.create_account("Kraken", "crypto", "HKD").ok is True
    before = (tmp_path / "accounts.yaml").read_bytes()

    result = service.rename_account("Kraken", "Venue", "HKD")

    assert result.ok is False
    assert result.error.code == "account.duplicate_investment_name"
    assert result.error.details == {"name": "Venue"}
    assert (tmp_path / "accounts.yaml").read_bytes() == before


def test_transfer_service_rejects_legacy_security_transfer_header(tmp_path):
    ledger = tmp_path / "ledger"
    (ledger / "records" / "security").mkdir(parents=True)
    (ledger / "accounts.yaml").write_text(
        "accounts:\n"
        "  - name: Cash\n"
        "    type: cash\n"
        "    currency: CNY\n"
        "    active: true\n"
        "  - name: Brokerage\n"
        "    type: security\n"
        "    currency: USD\n"
        "    base_currencies: [USD]\n"
        "    active: true\n",
        encoding="utf-8",
    )
    security_file = ledger / "records" / "security" / "2026-07-16.csv"
    legacy_content = (
        "date,amount,currency,counterparty,description,category,account_name,source,bill_source,transfer_account,locked\n"
        "2026-07-16 09:00:00,10,USD,,legacy,transfer_in,Brokerage,manual,,Cash,1\n"
    )
    security_file.write_text(legacy_content, encoding="utf-8")

    from ft.adapters.local_csv import LocalCsvUnitOfWork
    from ft.application.cashflow import TransferService

    with pytest.raises(ValueError, match="invalid security CSV schema"):
        TransferService(LocalCsvUnitOfWork(ledger)).transfer(
            from_name="Cash",
            to_name="Brokerage",
            amount=Decimal("70"),
            to_amount=Decimal("10"),
            date="2026-07-16",
        )

    assert security_file.read_text(encoding="utf-8") == legacy_content
    assert not (ledger / "records" / "cash" / "2026-07-16.csv").exists()
    assert not (ledger / "snapshot.yaml").exists()


def test_transfer_service_rolls_back_all_files_when_security_write_fails(tmp_path, monkeypatch):
    ledger = tmp_path / "ledger"
    cash_dir = ledger / "records" / "cash"
    security_dir = ledger / "records" / "security"
    cash_dir.mkdir(parents=True)
    security_dir.mkdir(parents=True)
    (ledger / "accounts.yaml").write_text(
        "accounts:\n"
        "  - name: Cash\n"
        "    type: cash\n"
        "    currency: CNY\n"
        "    active: true\n"
        "  - name: Brokerage\n"
        "    type: security\n"
        "    currency: USD\n"
        "    base_currencies: [USD]\n"
        "    active: true\n",
        encoding="utf-8",
    )
    cash_file = cash_dir / "2026-07.csv"
    security_file = security_dir / "2026-07-16.csv"
    snapshot_file = ledger / "snapshot.yaml"
    from ft.schema import CASH_CSV_FIELDS

    cash_file.write_text(",".join(CASH_CSV_FIELDS) + "\n", encoding="utf-8")
    security_file.write_text(
        "date,action,from_ticker,to_ticker,from_amount,to_amount,price,commission,commission_asset,currency,account_name,note\n",
        encoding="utf-8",
    )
    snapshot_file.write_text("accounts:\n  cash: {}\n  loan: {}\n  lend: {}\n  security: {}\n", encoding="utf-8")
    baseline = {path: path.read_bytes() for path in (cash_file, security_file, snapshot_file)}

    def fail_security_write(*_args, **_kwargs):
        assert cash_file.read_bytes() != baseline[cash_file]
        raise OSError("security write failed")

    monkeypatch.setattr("ft.stock._write_security_csv", fail_security_write)

    from ft.adapters.local_csv import LocalCsvUnitOfWork
    from ft.application.cashflow import TransferService

    with pytest.raises(OSError, match="security write failed"):
        TransferService(LocalCsvUnitOfWork(ledger)).transfer(
            from_name="Cash", to_name="Brokerage", amount=Decimal("70"),
            to_amount=Decimal("10"), date="2026-07-16",
        )

    assert {path: path.read_bytes() for path in baseline} == baseline


def test_transfer_service_rolls_back_all_files_when_snapshot_write_fails(tmp_path, monkeypatch):
    ledger = tmp_path / "ledger"
    cash_dir = ledger / "records" / "cash"
    security_dir = ledger / "records" / "security"
    cash_dir.mkdir(parents=True)
    security_dir.mkdir(parents=True)
    (ledger / "accounts.yaml").write_text(
        "accounts:\n"
        "  - name: Cash\n"
        "    type: cash\n"
        "    currency: CNY\n"
        "    active: true\n"
        "  - name: Brokerage\n"
        "    type: security\n"
        "    currency: USD\n"
        "    base_currencies: [USD]\n"
        "    active: true\n",
        encoding="utf-8",
    )
    from ft.schema import CASH_CSV_FIELDS

    cash_file = cash_dir / "2026-07.csv"
    security_file = security_dir / "2026-07-16.csv"
    snapshot_file = ledger / "snapshot.yaml"
    cash_file.write_text(",".join(CASH_CSV_FIELDS) + "\n", encoding="utf-8")
    security_file.write_text(
        "date,action,from_ticker,to_ticker,from_amount,to_amount,price,commission,commission_asset,currency,account_name,note\n",
        encoding="utf-8",
    )
    snapshot_file.write_text("accounts:\n  cash: {}\n  loan: {}\n  lend: {}\n  security: {}\n", encoding="utf-8")
    baseline = {path: path.read_bytes() for path in (cash_file, security_file, snapshot_file)}

    def fail_snapshot_write(_self):
        assert cash_file.read_bytes() != baseline[cash_file]
        assert security_file.read_bytes() != baseline[security_file]
        raise OSError("snapshot write failed")

    monkeypatch.setattr(
        "ft.adapters.local_csv.accounts._BufferedSnapshotRepository.commit",
        fail_snapshot_write,
    )

    from ft.adapters.local_csv import LocalCsvUnitOfWork
    from ft.application.cashflow import TransferService

    with pytest.raises(OSError, match="snapshot write failed"):
        TransferService(LocalCsvUnitOfWork(ledger)).transfer(
            from_name="Cash", to_name="Brokerage", amount=Decimal("70"),
            to_amount=Decimal("10"), date="2026-07-16",
        )

    assert {path: path.read_bytes() for path in baseline} == baseline


def test_local_csv_uow_retries_all_writes_after_snapshot_failure(tmp_path, monkeypatch):
    from ft.adapters.local_csv import LocalCsvUnitOfWork

    uow = LocalCsvUnitOfWork(tmp_path)
    with uow:
        uow.cashflows.add("cash", {
            "date": "2026-07-16 10:00:00", "amount": Decimal("12.50"),
            "currency": "CNY", "counterparty": "", "description": "retry",
            "category": "income", "account_name": "Cash", "source": "manual",
            "bill_source": "", "transfer_account": "", "locked": "",
        })
        snapshot = uow.snapshot.load()
        uow.snapshot.update_balance(snapshot, "Cash", "cash", "CNY", Decimal("12.50"))
        uow.snapshot.save(snapshot)

        original_commit = uow.snapshot.commit
        calls = 0

        def fail_once():
            nonlocal calls
            calls += 1
            if calls == 1:
                raise OSError("snapshot write failed")
            original_commit()

        monkeypatch.setattr(uow.snapshot, "commit", fail_once)

        with pytest.raises(OSError, match="snapshot write failed"):
            uow.commit()
        uow.commit()

    rows = _read_rows(tmp_path / "records" / "cash" / "2026-07.csv")
    assert rows[0]["amount"] == "12.50"
    snapshot = yaml.safe_load((tmp_path / "snapshot.yaml").read_text(encoding="utf-8"))
    assert snapshot["accounts"]["cash"]["Cash"]["CNY"] == 12.5


def test_local_csv_uow_attempts_all_restores_after_restore_failure(tmp_path, monkeypatch):
    from ft.adapters.local_csv import LocalCsvUnitOfWork
    from ft.schema import CASH_CSV_FIELDS, CSV_FIELDS

    cash_file = tmp_path / "records" / "cash" / "2026-07.csv"
    security_file = tmp_path / "records" / "security" / "2026-07-16.csv"
    snapshot_file = tmp_path / "snapshot.yaml"
    for path, header in ((cash_file, CASH_CSV_FIELDS), (security_file, CSV_FIELDS)):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(",".join(header) + "\n", encoding="utf-8")
    snapshot_file.write_text(
        "accounts:\n  cash: {}\n  loan: {}\n  lend: {}\n  security: {}\n",
        encoding="utf-8",
    )
    baseline = {path: path.read_bytes() for path in (cash_file, security_file, snapshot_file)}

    uow = LocalCsvUnitOfWork(tmp_path)
    with uow:
        uow.cashflows.add("cash", {
            "date": "2026-07-16 10:00:00", "amount": Decimal("12.50"),
            "currency": "CNY", "counterparty": "", "description": "restore",
            "category": "income", "account_name": "Cash", "source": "manual",
            "bill_source": "", "transfer_account": "", "locked": "",
        })
        uow.investments.add("security", {
            "date": "2026-07-16 10:00:00", "action": "deposit",
            "from_ticker": "", "to_ticker": "usd", "from_amount": "0",
            "to_amount": "10", "price": "1", "commission": "0",
            "commission_asset": "", "currency": "USD", "account_name": "Brokerage",
            "note": "restore",
        })
        snap = uow.snapshot.load()
        uow.snapshot.update_balance(snap, "Cash", "cash", "CNY", Decimal("12.50"))
        uow.snapshot.save(snap)
        monkeypatch.setattr(uow, "_affected_paths", lambda: [cash_file, security_file, snapshot_file])
        def fail_snapshot_commit():
            assert cash_file.read_bytes() != baseline[cash_file]
            assert security_file.read_bytes() != baseline[security_file]
            raise OSError("snapshot write failed")

        monkeypatch.setattr(uow.snapshot, "commit", fail_snapshot_commit)
        original_write_bytes = Path.write_bytes
        restored = []

        def fail_cash_restore(path, content):
            restored.append(path)
            if path == cash_file:
                raise OSError("cash restore failed")
            return original_write_bytes(path, content)

        monkeypatch.setattr(Path, "write_bytes", fail_cash_restore)

        with pytest.raises(OSError, match="snapshot write failed") as excinfo:
            uow.commit()

    assert restored == [cash_file, security_file, snapshot_file]
    assert security_file.read_bytes() == baseline[security_file]
    assert snapshot_file.read_bytes() == baseline[snapshot_file]
    assert "rollback restoration failures" in excinfo.value.__notes__[0]
    assert str(cash_file) in excinfo.value.__notes__[0]
    assert "cash restore failed" in excinfo.value.__notes__[0]


def test_snapshot_update_balance_uses_explicit_account_type_with_duplicate_names(tmp_path):
    from ft.adapters.local_csv import LocalCsvUnitOfWork

    with LocalCsvUnitOfWork(tmp_path) as uow:
        snap = {
            "accounts": {
                "cash": {"Shared": {"CNY": "10.00"}},
                "loan": {"Shared": {"CNY": "100.00"}},
                "lend": {},
                "security": {},
            }
        }
        uow.snapshot.update_balance(snap, "Shared", "loan", "CNY", Decimal("5.25"))

    assert snap["accounts"]["cash"]["Shared"]["CNY"] == "10.00"
    _assert_numeric_scalar(snap["accounts"]["loan"]["Shared"]["CNY"], "105.25")


def test_manual_add_service_does_not_update_duplicate_name_wrong_bucket(tmp_path):
    ledger = tmp_path / "ledger"
    ledger.mkdir(parents=True)
    (ledger / "accounts.yaml").write_text(
        "accounts:\n"
        "  - name: Shared\n"
        "    type: cash\n"
        "    currency: CNY\n"
        "    active: true\n"
        "  - name: Shared\n"
        "    type: loan\n"
        "    currency: CNY\n"
        "    active: true\n",
        encoding="utf-8",
    )
    (ledger / "snapshot.yaml").write_text(
        "accounts:\n"
        "  cash:\n"
        "    Shared:\n"
        "      CNY: '10.00'\n"
        "  loan:\n"
        "    Shared:\n"
        "      CNY: '100.00'\n"
        "  lend: {}\n"
        "  security: {}\n"
        "updated_at: ''\n",
        encoding="utf-8",
    )

    from ft.adapters.local_csv import LocalCsvUnitOfWork
    from ft.application.cashflow import CashflowService

    result = CashflowService(LocalCsvUnitOfWork(ledger)).add_manual_transaction(
        amount=Decimal("2.25"),
        counterparty="Refund",
        account_name="Shared",
        date="2026-07-16 12:00:00",
    )

    assert result.ok is True
    snap = yaml.safe_load((ledger / "snapshot.yaml").read_text(encoding="utf-8"))
    _assert_numeric_scalar(snap["accounts"]["cash"]["Shared"]["CNY"], "12.25")
    assert snap["accounts"]["loan"]["Shared"]["CNY"] == "100.00"


def test_cashflow_service_rejects_manual_writes_to_investment_accounts(tmp_path):
    ledger = tmp_path / "ledger"
    ledger.mkdir()
    (ledger / "accounts.yaml").write_text(
        "accounts:\n"
        "  - name: Brokerage\n"
        "    type: security\n"
        "    currency: USD\n"
        "    base_currencies: [USD]\n"
        "    active: true\n",
        encoding="utf-8",
    )

    from ft.adapters.local_csv import LocalCsvUnitOfWork
    from ft.application.cashflow import CashflowService

    service = CashflowService(LocalCsvUnitOfWork(ledger))
    manual = service.add_manual_transaction(
        amount=Decimal("10"), counterparty="manual", account_name="Brokerage",
        date="2026-07-16 10:00:00",
    )
    checkin = service.checkin_balance(
        account_name="Brokerage", balance=Decimal("10"), date="2026-07-16",
    )

    (ledger / "accounts.yaml").write_text(
        (ledger / "accounts.yaml").read_text(encoding="utf-8")
        + "  - name: Kraken\n"
        "    type: crypto\n"
        "    currency: USD\n"
        "    base_currencies: [USD]\n"
        "    active: true\n",
        encoding="utf-8",
    )
    crypto_manual = service.add_manual_transaction(
        amount=Decimal("10"), counterparty="manual", account_name="Kraken",
        date="2026-07-16 10:00:00",
    )
    crypto_checkin = service.checkin_balance(
        account_name="Kraken", balance=Decimal("10"), date="2026-07-16",
    )

    for result, account_type in (
        (manual, "security"),
        (checkin, "security"),
        (crypto_manual, "crypto"),
        (crypto_checkin, "crypto"),
    ):
        assert result.ok is False
        assert result.error.code == "cashflow.unsupported_account_type"
        assert result.error.details == {"account_type": account_type}
    assert not (ledger / "records").exists()
    assert not (ledger / "snapshot.yaml").exists()


def test_cashflow_service_rejects_any_write_when_legacy_crypto_ledger_exists(tmp_path):
    ledger = tmp_path / "ledger"
    _seed_accounts(ledger)
    crypto_dir = ledger / "records" / "crypto"
    crypto_dir.mkdir(parents=True)
    legacy = crypto_dir / "2026-07-16.csv"
    legacy.write_text(
        "date,action,from_ticker,to_ticker,from_amount,to_amount,price,commission,commission_asset,currency,account_name,note\n"
        "2026-07-16 09:00:00,deposit,,usd,0,10,1,0,,USD,Kraken,legacy location\n",
        encoding="utf-8",
    )

    from ft.adapters.local_csv import LocalCsvUnitOfWork
    from ft.application.cashflow import CashflowService

    with pytest.raises(ValueError, match="legacy crypto ledger"):
        CashflowService(LocalCsvUnitOfWork(ledger)).add_manual_transaction(
            amount=Decimal("10"), counterparty="manual", account_name="Cash",
            date="2026-07-16 10:00:00",
        )

    assert legacy.exists()
    assert not (ledger / "records" / "cash").exists()
    assert not (ledger / "snapshot.yaml").exists()


@pytest.mark.parametrize("operation", ("checkin", "transfer"))
def test_other_cashflow_writes_reject_legacy_crypto_ledger(tmp_path, operation):
    ledger = tmp_path / "ledger"
    _seed_accounts(ledger)
    crypto_dir = ledger / "records" / "crypto"
    crypto_dir.mkdir(parents=True)
    legacy = crypto_dir / "2026-07-16.csv"
    legacy.write_text(
        "date,action,from_ticker,to_ticker,from_amount,to_amount,price,commission,commission_asset,currency,account_name,note\n"
        "2026-07-16 09:00:00,deposit,,usd,0,10,1,0,,USD,Kraken,legacy location\n",
        encoding="utf-8",
    )

    from ft.adapters.local_csv import LocalCsvUnitOfWork
    from ft.application.cashflow import CashflowService, TransferService

    with pytest.raises(ValueError, match="legacy crypto ledger"):
        if operation == "checkin":
            CashflowService(LocalCsvUnitOfWork(ledger)).checkin_balance(
                account_name="Cash", balance=Decimal("10"), date="2026-07-16",
            )
        else:
            TransferService(LocalCsvUnitOfWork(ledger)).transfer(
                from_name="Cash", to_name="Card", amount=Decimal("10"), date="2026-07-16",
            )

    assert legacy.exists()
    assert not (ledger / "records" / "cash").exists()
    assert not (ledger / "records" / "loan").exists()
    assert not (ledger / "snapshot.yaml").exists()


def test_reconcile_service_uses_injected_ledger_for_matching_and_audit(tmp_path, monkeypatch):
    home = tmp_path / "home"
    ledger = tmp_path / "ledger"
    monkeypatch.setenv("HOME", str(home))
    _seed_accounts(ledger)
    cash_dir = ledger / "records" / "cash"
    loan_dir = ledger / "records" / "loan"
    cash_dir.mkdir(parents=True)
    loan_dir.mkdir(parents=True)
    header = "date,amount,currency,counterparty,description,category,account_name,source,bill_source,transfer_account,locked\n"
    (cash_dir / "2026-07.csv").write_text(
        header
        + "2026-07-16 10:00:00,-50,CNY,,主动还款,expense,Cash,bank,手机银行,,\n",
        encoding="utf-8",
    )
    (loan_dir / "2026-07.csv").write_text(
        header
        + "2026-07-16 10:05:00,50,CNY,,转帐还款,income,Card,bank,银行卡中心,,\n",
        encoding="utf-8",
    )

    from ft.adapters.local_csv import LocalCsvUnitOfWork
    from ft.application.reconcile import ReconcileService

    result = ReconcileService(LocalCsvUnitOfWork(ledger)).reconcile(
        date_from="2026-07-16",
        date_to="2026-07-16",
    )

    assert result.ok is True
    assert result.transfer_matches == 1
    assert result.audit_path and result.audit_path.is_relative_to(ledger)
    cash_rows = _read_rows(cash_dir / "2026-07.csv")
    loan_rows = _read_rows(loan_dir / "2026-07.csv")
    assert cash_rows[0]["category"] == "transfer_out"
    assert loan_rows[0]["transfer_account"] == "Cash"
    assert (ledger / "snapshot.yaml").exists()
    assert not (home / ".ft").exists()


def test_reconcile_service_repairs_security_snapshot_without_home_or_git_stage(tmp_path, monkeypatch, capsys):
    home = tmp_path / "home"
    ledger = tmp_path / "ledger"
    monkeypatch.setenv("HOME", str(home))

    def fail_home():
        raise AssertionError("Path.home invoked during reconcile")

    monkeypatch.setattr(Path, "home", fail_home)
    ledger.mkdir(parents=True)
    (ledger / "accounts.yaml").write_text(
        "accounts:\n"
        "  - name: Brokerage\n"
        "    type: security\n"
        "    currency: USD\n"
        "    base_currencies:\n"
        "      - USD\n"
        "    active: true\n",
        encoding="utf-8",
    )
    security_dir = ledger / "records" / "security"
    security_dir.mkdir(parents=True)
    (security_dir / "2026-07-16.csv").write_text(
        "date,action,from_ticker,to_ticker,from_amount,to_amount,price,commission,"
        "commission_asset,currency,account_name,note\n"
        "2026-07-16 09:00:00,deposit,,USD,0,50,1,0,,USD,Brokerage,\n",
        encoding="utf-8",
    )

    from ft.adapters.local_csv import LocalCsvUnitOfWork
    from ft.application.reconcile import ReconcileService

    result = ReconcileService(LocalCsvUnitOfWork(ledger)).reconcile(
        date_from="2026-07-16",
        date_to="2026-07-16",
    )

    assert result.ok is True
    assert result.message == "无重复项"
    snap = yaml.safe_load((ledger / "snapshot.yaml").read_text(encoding="utf-8"))
    assert snap["accounts"]["security"]["Brokerage"]["positions"]["usd"]["shares"] == 50
    assert not (ledger / ".git").exists()
    assert not (home / ".ft").exists()
    assert capsys.readouterr().out == ""
