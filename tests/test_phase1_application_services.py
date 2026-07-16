from pathlib import Path

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
