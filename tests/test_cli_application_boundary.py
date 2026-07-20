import ast
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def _imports(tree):
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            yield from (alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom):
            prefix = "." * node.level
            yield prefix + (node.module or "")


def test_cli_does_not_call_legacy_business_entry_points():
    path = ROOT / "src" / "ft" / "cli.py"
    tree = ast.parse(path.read_text(encoding="utf-8"))
    forbidden_calls = {
        "report_networth", "report_expense", "report_income", "report_flow",
        "list_txns", "do_append", "do_convert", "do_buy", "do_sell",
        "do_swap", "do_deposit", "do_withdraw", "do_dividend",
        "do_checkin_ticker", "do_checkin_cash", "do_list", "sync_exchange",
        "sync_polymarket", "continue_reconcile", "abort_reconcile",
        "do_reconcile", "git_do_commit", "verify_security",
        "rebuild_snapshot_from_records",
    }
    called = {
        node.func.id
        for node in ast.walk(tree)
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
    }
    assert called.isdisjoint(forbidden_calls), sorted(called & forbidden_calls)
    assert "subprocess" not in set(_imports(tree))
    assert "csv" not in set(_imports(tree))


def test_application_package_has_no_local_adapter_or_terminal_dependencies():
    forbidden_imports = {
        "csv", "subprocess", "yaml",
        "ft.models", "ft.snapshot", "ft.stock", "ft.report", "ft.convert",
        "ft.reconcile", "ft.exchange_sync", "ft.polymarket_sync",
        "ft.credentials", "ft.mapping",
    }
    for path in sorted((ROOT / "src" / "ft" / "application").glob("*.py")):
        tree = ast.parse(path.read_text(encoding="utf-8"))
        imports = {name.lstrip(".") for name in _imports(tree)}
        assert imports.isdisjoint(forbidden_imports), (
            path.name, sorted(imports & forbidden_imports)
        )
        terminal_calls = {
            node.func.id
            for node in ast.walk(tree)
            if isinstance(node, ast.Call)
            and isinstance(node.func, ast.Name)
            and node.func.id in {"print", "input"}
        }
        assert not terminal_calls, (path.name, sorted(terminal_calls))
        assert "ledger_root" not in path.read_text(encoding="utf-8"), path.name


def test_cli_runtime_commands_build_one_postgres_bundle(monkeypatch, capsys):
    from ft import cli
    from ft.domain.queries import AccountListDTO, FinanceReportDTO

    calls = []
    settings = object()

    class Queries:
        def report(self, *, month=None):
            calls.append(("report", month))
            return FinanceReportDTO(accounts=AccountListDTO(()))

    bundle = type("Bundle", (), {"queries": Queries()})()
    monkeypatch.setattr("ft.config.StorageSettings.load", lambda: calls.append(("settings",)) or settings)
    monkeypatch.setattr("ft.cli.build_services", lambda value: calls.append(("bundle", value)) or bundle)

    cli.main(["report", "--month", "2026-07"])

    assert calls == [("settings",), ("bundle", settings), ("report", "2026-07")]


def test_cli_account_and_cash_commands_use_injected_services(monkeypatch):
    from decimal import Decimal
    from ft import cli

    calls = []

    class Accounts:
        def create_account(self, name, type_, currency=None):
            calls.append(("account", name, type_, currency))
            account = type("Account", (), {"name": name})()
            return type("Result", (), {"ok": True, "account": account})()

    class Cashflow:
        def add_manual_transaction(self, **kwargs):
            calls.append(("cash", kwargs))
            return type("Result", (), {"ok": True, "row": {"currency": kwargs["currency"]}})()

    bundle = type("Bundle", (), {"accounts": Accounts(), "cashflow": Cashflow()})()
    monkeypatch.setattr("ft.config.StorageSettings.load", lambda: object())
    monkeypatch.setattr("ft.cli.build_services", lambda _settings: bundle)

    cli.main(["acct", "add", "Cash", "--type", "cash", "--currency", "CNY"])
    cli.main(["add", "--amount", "1.20", "--counterparty", "Seed", "--account", "Cash", "--currency", "CNY"])

    assert calls[0] == ("account", "Cash", "cash", "CNY")
    assert calls[1][0] == "cash"
    assert calls[1][1]["amount"] == Decimal("1.20")
