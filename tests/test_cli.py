from decimal import Decimal

import pytest

from ft import cli


def _install_bundle(monkeypatch, bundle):
    monkeypatch.setattr("ft.config.StorageSettings.load", lambda: object())
    monkeypatch.setattr("ft.cli.build_services", lambda _settings: bundle)


def test_cli_direct_statement_import_dispatches_without_intermediate_csv(monkeypatch, tmp_path):
    from ft.domain.application import OperationResult

    source = tmp_path / "statement.csv"
    source.write_text("raw", encoding="utf-8")
    calls = []

    class Importer:
        def import_statement(self, command):
            calls.append(command)
            return OperationResult(ok=True, count=2, details={"duplicate": False})

    _install_bundle(monkeypatch, type("Bundle", (), {"statement_import": Importer()})())
    cli.main([
        "import", str(source), "--source", "alipay",
        "--currency", "CNY",
    ])

    assert calls[0].source_path == str(source)
    assert calls[0].source == "alipay"
    assert not hasattr(calls[0], "account") or getattr(calls[0], "account", None) is None


def test_cli_ibkr_leaves_currency_unset_for_statement_base_currency(monkeypatch, tmp_path):
    from ft.domain.accounts import AccountDTO
    from ft.domain.application import OperationResult

    source = tmp_path / "statement.csv"
    source.write_text("raw", encoding="utf-8")
    calls = []

    class Accounts:
        def find(self, name):
            assert name == "IBKR"
            return AccountDTO("IBKR", "security", active=True)

    class Uow:
        def __enter__(self):
            self.accounts = Accounts()
            return self

        def __exit__(self, *_args):
            return False

        def rollback(self):
            pass

    class Service:
        def __init__(self, _uow):
            pass

        def import_statement(self, **kwargs):
            calls.append(kwargs)
            return OperationResult(ok=True, count=1, details={"duplicate": False, "batch_id": "batch"})

    monkeypatch.setattr("ft.cli._runtime_services", lambda: type("Bundle", (), {"uow": Uow()})())
    monkeypatch.setattr("ft.application.investment_import.InvestmentImportService", Service)

    cli.main(["import", str(source), "--source", "ibkr", "--account", "IBKR"])

    assert calls[0]["currency"] is None


def test_cli_usmart_hk_requires_security_account_and_keeps_currency_optional(monkeypatch, tmp_path):
    from ft.domain.accounts import AccountDTO

    source = tmp_path / "statement.txt"
    source.write_text("fixture", encoding="utf-8")

    class Accounts:
        def find(self, _name):
            return AccountDTO("现金", "cash", active=True)

    class Uow:
        def __enter__(self):
            self.accounts = Accounts()
            return self
        def __exit__(self, *_args): return False
        def rollback(self): pass

    monkeypatch.setattr("ft.cli._runtime_services", lambda: type("Bundle", (), {"uow": Uow()})())
    with pytest.raises(SystemExit) as exc:
        cli.main(["import", str(source), "--source", "usmart-hk", "--account", "现金"])
    assert exc.value.code == 1


def test_cli_usmart_hk_pdf_reports_missing_pdf_tool(monkeypatch, tmp_path):
    source = tmp_path / "statement.pdf"
    source.write_bytes(b"not-a-real-pdf")
    monkeypatch.setattr(
        "ft.importers.usmart_hk.check_external_tools",
        lambda: {"qpdf": None, "mutool": "1.27"},
    )
    with pytest.raises(SystemExit) as exc:
        cli.main(["import", str(source), "--source", "usmart-hk", "--account", "盈立证券"])
    assert exc.value.code == 1


def test_cli_reads_encrypted_statement_password_from_file(monkeypatch, tmp_path):
    from ft.domain.application import OperationResult

    source = tmp_path / "statement.pdf"
    password_file = tmp_path / "password.txt"
    source.write_bytes(b"pdf")
    password_file.write_text("top-secret\nignored", encoding="utf-8")
    calls = []

    class Importer:
        def import_statement(self, command):
            calls.append(command)
            return OperationResult(ok=True, count=1, details={"duplicate": False})

    _install_bundle(monkeypatch, type("Bundle", (), {"statement_import": Importer()})())
    cli.main([
        "import", str(source), "--source", "icbc",
        "--password-file", str(password_file),
    ])

    assert calls[0].password == "top-secret"


def test_cli_rejects_inline_statement_password():
    with pytest.raises(SystemExit) as exc:
        cli.main([
            "import", "statement.pdf", "--source", "icbc",
            "--password", "top-secret",
        ])
    assert exc.value.code == 2


def test_cli_rejects_import_account_flag():
    with pytest.raises(SystemExit) as exc:
        cli.main([
            "import", "statement.csv", "--source", "alipay",
            "--account", "Cash",
        ])
    assert exc.value.code == 2


def test_cli_help_excludes_removed_local_storage_commands(capsys):
    removed = {"verify", "commit", "status", "reset", "append", "reconcile", "migrate"}
    with pytest.raises(SystemExit) as exc:
        cli.main(["--help"])
    assert exc.value.code == 0
    command_line = next(
        line for line in capsys.readouterr().out.splitlines()
        if line.strip().startswith("{")
    )
    assert all(command not in command_line for command in removed)


@pytest.mark.parametrize(
    "command", ["verify", "commit", "status", "reset", "append", "reconcile", "migrate"],
)
def test_removed_local_storage_command_is_unknown(command):
    with pytest.raises(SystemExit) as exc:
        cli.main([command, "--help"])
    assert exc.value.code == 2


def test_stock_help_excludes_append_and_sync(capsys):
    with pytest.raises(SystemExit) as exc:
        cli.main(["stock", "--help"])
    assert exc.value.code == 0
    output = capsys.readouterr().out
    assert "append" not in output
    assert "sync" not in output


def test_cli_add_checkin_transfer_dispatch_to_postgres_services(monkeypatch):
    calls = []
    account = type("Account", (), {"name": "Cash"})()

    class Cashflow:
        def add_manual_transaction(self, **kwargs):
            calls.append(("add", kwargs))
            return type("Result", (), {"ok": True, "row": {"currency": "CNY"}})()

        def checkin_balance(self, **kwargs):
            calls.append(("checkin", kwargs))
            return type("Result", (), {
                "ok": True, "row": {"currency": "CNY"}, "details": {"day": "2026-07-17"},
            })()

    class Transfers:
        def transfer(self, **kwargs):
            calls.append(("transfer", kwargs))
            return type("Result", (), {"ok": True, "details": {
                "amount": kwargs["amount"], "to_amount": kwargs["amount"],
                "date": kwargs["date"], "warning": "",
                "from_currency": kwargs["from_currency"], "to_currency": kwargs["to_currency"],
            }})()

    bundle = type("Bundle", (), {"cashflow": Cashflow(), "transfers": Transfers()})()
    _install_bundle(monkeypatch, bundle)
    cli.main(["add", "--amount", "1.23", "--counterparty", "Shop", "--account", "Cash", "--currency", "CNY"])
    cli.main(["checkin", "Cash", "--balance", "9.99", "--currency", "CNY", "--date", "2026-07-17"])
    cli.main(["transfer", "--from", "Cash", "--from-currency", "CNY", "--to", "Card", "--to-currency", "CNY", "--amount", "2.50"])

    assert [item[0] for item in calls] == ["add", "checkin", "transfer"]
    assert calls[0][1]["amount"] == Decimal("1.23")


def test_convert_is_explicit_export_and_does_not_build_runtime_bundle(monkeypatch, tmp_path):
    from ft.domain.application import ExportPayload

    output = tmp_path / "out.csv"
    seen = []
    monkeypatch.setattr(
        "ft.cli._statement_export",
        lambda command: seen.append(command) or ExportPayload(
            ({"amount": "1.20"},), fieldnames=("amount",),
        ),
    )
    monkeypatch.setattr(
        "ft.cli.build_services",
        lambda _settings: (_ for _ in ()).throw(AssertionError("runtime bundle built")),
    )

    cli.main([
        "convert", "statement.csv", "--source", "alipay",
        "--output", str(output),
    ])

    assert seen[0].source == "alipay"
    assert not hasattr(seen[0], "account") or getattr(seen[0], "account", None) is None
    assert output.read_text(encoding="utf-8").splitlines() == ["amount", "1.20"]


def test_stock_convert_is_explicit_export_and_does_not_build_runtime_bundle(monkeypatch, tmp_path):
    from ft.domain.application import ExportPayload

    output = tmp_path / "stock.csv"
    monkeypatch.setattr(
        "ft.cli._statement_export",
        lambda _command: ExportPayload(({"action": "deposit"},), fieldnames=("action",)),
    )
    monkeypatch.setattr(
        "ft.cli._runtime_services",
        lambda: (_ for _ in ()).throw(AssertionError("runtime bundle built")),
    )

    cli.main([
        "stock", "convert", "statement.pdf", "--source", "dfzq",
        "--output", str(output),
    ])

    assert output.read_text(encoding="utf-8").splitlines() == ["action", "deposit"]


def test_cli_help_does_not_require_database(monkeypatch):
    monkeypatch.delenv("FT_DATABASE_URL", raising=False)
    monkeypatch.delenv("FT_WORKSPACE_ID", raising=False)
    with pytest.raises(SystemExit) as exc:
        cli.main(["--help"])
    assert exc.value.code == 0


def test_web_refuses_an_unmigrated_selected_database_without_implicit_migration(tmp_path, monkeypatch):
    from ft.adapters.relational.runtime import StorageError
    from ft.web.app import create_runtime_app

    database = tmp_path / "unmigrated.db"
    database.touch()
    monkeypatch.setenv("FT_DATABASE_URL", f"sqlite+pysqlite:///{database}")
    monkeypatch.setenv("FT_WORKSPACE_ID", "default")

    with pytest.raises(StorageError, match="storage.schema"):
        create_runtime_app()

    assert database.stat().st_size == 0


def test_cli_commit_time_storage_error_is_controlled_and_nonzero(monkeypatch, capsys):
    from ft.adapters.relational.runtime import StorageError

    calls = []

    class Accounts:
        def create_account(self, *_args):
            calls.append("create")
            raise StorageError(
                "storage.readonly",
                "postgresql+psycopg://user:secret@host/finance?sslkey=hidden",
            )

    _install_bundle(monkeypatch, type("Bundle", (), {"accounts": Accounts()})())

    with pytest.raises(SystemExit) as status:
        cli.main(["acct", "add", "Cash", "--type", "cash", "--currency", "CNY"])

    assert status.value.code == 1
    error = capsys.readouterr().err
    assert "storage.readonly" in error
    assert "secret" not in error
    assert "sslkey" not in error
    assert calls == ["create"]


@pytest.mark.parametrize("argv", [
    ["acct", "add", "Cash", "--type", "cash", "--currency", "CNY"],
    ["acct", "rename", "Cash", "Wallet"],
    ["acct", "delete", "Cash"],
    ["acct", "activate", "Cash"],
    ["acct", "deactivate", "Cash"],
])
def test_rejected_account_writes_exit_nonzero(monkeypatch, argv):
    from ft.domain.accounts import AccountResult

    class Accounts:
        def create_account(self, *_args):
            return AccountResult.fail("account.rejected", "rejected")

        def rename_account(self, *_args):
            return AccountResult.fail("account.rejected", "rejected")

        def delete_account(self, *_args):
            return AccountResult.fail("account.rejected", "rejected")

        def set_active(self, *_args):
            return AccountResult.fail("account.rejected", "rejected")

    _install_bundle(monkeypatch, type("Bundle", (), {"accounts": Accounts()})())

    with pytest.raises(SystemExit) as exc:
        cli.main(argv)

    assert exc.value.code == 1
