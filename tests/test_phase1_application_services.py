from pathlib import Path
from decimal import Decimal
import csv

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
    rows = _read_rows(ledger / "records" / "cash" / "2026-07-16.csv")
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
    rows = _read_rows(ledger / "records" / "cash" / "2026-07-16.csv")
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
    cash_rows = _read_rows(ledger / "records" / "cash" / "2026-07-16.csv")
    loan_rows = _read_rows(ledger / "records" / "loan" / "2026-07-16.csv")
    assert cash_rows[0]["category"] == "transfer_out"
    assert cash_rows[0]["locked"] == "1"
    assert loan_rows[0]["category"] == "transfer_in"
    snap = yaml.safe_load((ledger / "snapshot.yaml").read_text(encoding="utf-8"))
    assert snap["accounts"]["cash"]["Cash"]["CNY"] == -20
    assert snap["accounts"]["loan"]["Card"]["CNY"] == 20
    assert not (home / ".ft").exists()


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
    (cash_dir / "2026-07-16.csv").write_text(
        header
        + "2026-07-16 10:00:00,-50,CNY,,主动还款,expense,Cash,bank,手机银行,,\n",
        encoding="utf-8",
    )
    (loan_dir / "2026-07-16.csv").write_text(
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
    cash_rows = _read_rows(cash_dir / "2026-07-16.csv")
    loan_rows = _read_rows(loan_dir / "2026-07-16.csv")
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
