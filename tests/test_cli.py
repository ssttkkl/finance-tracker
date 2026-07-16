import csv
import tempfile
from pathlib import Path

import pytest

from ft import cli


@pytest.fixture
def tmp_env():
    d = Path(tempfile.mkdtemp())
    from ft import models
    import ft.snapshot as snapshot_mod

    old_ft = models.FT_DIR
    old_records = models.RECORDS_DIR
    old_accounts = models.ACCOUNTS_PATH
    old_snapshot = snapshot_mod.SNAPSHOT_PATH
    models.FT_DIR = d
    models.RECORDS_DIR = d / "records"
    models.ACCOUNTS_PATH = d / "accounts.yaml"
    snapshot_mod.SNAPSHOT_PATH = d / "snapshot.yaml"
    models.ACCOUNTS_PATH.write_text(
        "accounts:\n"
        "  - name: IBKR\n"
        "    type: security\n"
        "    currency: USD\n",
        encoding="utf-8",
    )

    yield d

    snapshot_mod.SNAPSHOT_PATH = old_snapshot
    models.FT_DIR = old_ft
    models.RECORDS_DIR = old_records
    models.ACCOUNTS_PATH = old_accounts
    import shutil
    shutil.rmtree(d, ignore_errors=True)


def test_append_accepts_multiple_files(monkeypatch):
    called = {}

    def fake_append(files):
        called["files"] = files

    monkeypatch.setattr("ft.append.do_append", fake_append)
    cli.main(["append", "a.csv", "b.csv"])
    assert called["files"] == ["a.csv", "b.csv"]


def test_reconcile_month_dispatch(monkeypatch):
    called = {}

    def fake_reconcile(*, month=None, date_from=None, date_to=None):
        called["args"] = (month, date_from, date_to)

    monkeypatch.setattr("ft.reconcile.do_reconcile", fake_reconcile)
    cli.main(["reconcile", "--month", "2026-06"])
    assert called["args"] == ("2026-06", None, None)


def test_reconcile_range_dispatch(monkeypatch):
    called = {}

    def fake_reconcile(*, month=None, date_from=None, date_to=None):
        called["args"] = (month, date_from, date_to)

    monkeypatch.setattr("ft.reconcile.do_reconcile", fake_reconcile)
    cli.main(["reconcile", "--from", "2026-06-01", "--to", "2026-06-30"])
    assert called["args"] == (None, "2026-06-01", "2026-06-30")


def test_reconcile_rejects_month_plus_range():
    with pytest.raises(SystemExit):
        cli.main(["reconcile", "--month", "2026-06", "--from", "2026-06-01"])


def test_stock_checkin_accepts_fractional_shares(monkeypatch):
    called = {}

    def fake_checkin_ticker(*args):
        called["args"] = args

    monkeypatch.setattr("ft.stock.do_checkin_ticker", fake_checkin_ticker)
    cli.main([
        "stock", "checkin",
        "--account", "Polymarket",
        "--ticker", "pm:test:no",
        "--shares", "323.5",
        "--avg-cost", "0.92",
    ])
    assert called["args"][1] == 323.5


def test_cli_stock_currency_help_has_no_hardcoded_choices(capsys):
    with pytest.raises(SystemExit):
        cli.main(["stock", "buy", "--help"])
    out = capsys.readouterr().out
    assert "--currency" in out
    assert "{CNY,USD,HKD}" not in out
    assert "{CNY, USD, HKD}" not in out


def test_cli_stock_buy_accepts_configured_non_builtin_currency(tmp_env):
    from ft import models

    models.ACCOUNTS_PATH.write_text(
        "accounts:\n"
        "  - name: Kraken\n"
        "    type: crypto\n"
        "    currency: USDT\n"
        "    base_currencies: [USDT, USDG]\n"
        "    active: true\n",
        encoding="utf-8",
    )

    cli.main([
        "stock", "deposit", "--amount", "10", "--account", "Kraken",
        "--currency", "usdg", "--date", "2026-06-30",
    ])

    with (models.RECORDS_DIR / "security" / "2026-06-30.csv").open(encoding="utf-8") as f:
        row = next(csv.DictReader(f))
    assert row["currency"] == "USDG"
    assert row["to_ticker"] == "usdg"


def test_cli_stock_configured_account_requires_explicit_currency(tmp_env, capsys):
    from ft import models

    models.ACCOUNTS_PATH.write_text(
        "accounts:\n"
        "  - name: Kraken\n"
        "    type: crypto\n"
        "    currency: USDT\n"
        "    base_currencies: [USDT, USDG]\n",
        encoding="utf-8",
    )

    with pytest.raises(SystemExit) as exc:
        cli.main([
            "stock", "deposit", "--amount", "10", "--account", "Kraken",
            "--date", "2026-06-30",
        ])

    assert exc.value.code == 1
    out = capsys.readouterr().out
    assert "currency is required" in out
    assert not (models.RECORDS_DIR / "security" / "2026-06-30.csv").exists()


def test_cli_stock_legacy_no_base_currency_falls_back_to_account_currency(tmp_env):
    from ft import models

    cli.main([
        "stock", "deposit", "--amount", "10", "--account", "IBKR",
        "--date", "2026-06-30",
    ])

    with (models.RECORDS_DIR / "security" / "2026-06-30.csv").open(encoding="utf-8") as f:
        row = next(csv.DictReader(f))
    assert row["currency"] == "USD"
    assert row["to_ticker"] == "usd"


def test_cli_stock_validation_errors_exit_nonzero(tmp_env, capsys):
    with pytest.raises(SystemExit) as exc:
        cli.main([
            "stock", "deposit", "--amount", "10", "--account", "IBKR",
            "--currency", "CNY", "--date", "2026-06-30",
        ])

    assert exc.value.code == 1
    assert "not configured" in capsys.readouterr().out


def test_cli_add_to_security_preserves_existing_stock_columns(tmp_env):
    """Top-level ft add must not corrupt mixed security CSV files."""
    from ft import models
    from ft.stock import record_trade

    record_trade(
        date="2026-06-30 09:00:00", action="swap",
        from_ticker="USD", to_ticker="nvda.us",
        from_amount=10, to_amount=1,
        price=10, commission=0, commission_asset="USD",
        currency="USD", account_name="IBKR", note="existing stock row",
    )

    cli.main([
        "add", "-a", "5", "-c", "manual cash adjustment",
        "--account", "IBKR", "--date", "2026-06-30 10:00:00",
    ])

    day_csv = models.RECORDS_DIR / "security" / "2026-06-30.csv"
    with day_csv.open(encoding="utf-8") as f:
        reader = csv.DictReader(f)
        rows = list(reader)
    fieldnames = reader.fieldnames or []
    assert "action" in fieldnames
    assert "from_ticker" in fieldnames
    assert "note" in fieldnames
    assert rows[0]["action"] == "swap"
    assert rows[0]["from_ticker"] == "usd"
    assert rows[0]["to_ticker"] == "nvda.us"
    assert rows[1]["counterparty"] == "manual cash adjustment"


def test_cli_checkin_to_security_preserves_existing_stock_columns(tmp_env):
    """Top-level ft checkin must not corrupt mixed security CSV files."""
    from ft import models
    from ft.stock import record_trade

    record_trade(
        date="2026-06-30 09:00:00", action="swap",
        from_ticker="USD", to_ticker="nvda.us",
        from_amount=10, to_amount=1,
        price=10, commission=0, commission_asset="USD",
        currency="USD", account_name="IBKR", note="existing stock row",
    )

    cli.main(["checkin", "IBKR", "--balance", "100", "--date", "2026-06-30"])

    day_csv = models.RECORDS_DIR / "security" / "2026-06-30.csv"
    with day_csv.open(encoding="utf-8") as f:
        reader = csv.DictReader(f)
        rows = list(reader)
    fieldnames = reader.fieldnames or []
    assert "action" in fieldnames
    assert "from_ticker" in fieldnames
    assert "note" in fieldnames
    stock_row = next(row for row in rows if row["action"] == "swap")
    checkin_row = next(row for row in rows if row["category"] == "checkin")
    assert stock_row["to_ticker"] == "nvda.us"
    assert checkin_row["category"] == "checkin"


def test_cli_stock_append_returns_nonzero_when_append_fails(monkeypatch):
    """ft stock append must surface do_append(False) as a non-zero CLI exit."""
    monkeypatch.setattr("ft.stock.do_append", lambda _path: False)

    with pytest.raises(SystemExit) as exc:
        cli.main(["stock", "append", "bad.csv"])

    assert exc.value.code == 1


def test_cli_stock_sync_polymarket_dispatches_nested_subcommand(monkeypatch):
    """Polymarket sync should be ft stock sync polymarket, leaving room for future sync providers."""
    called = {}

    def fake_sync_polymarket(**kwargs):
        called.update(kwargs)

    monkeypatch.setattr("ft.polymarket_sync.sync_polymarket", fake_sync_polymarket)

    cli.main([
        "stock", "sync", "polymarket",
        "--proxy-wallet", "0x" + "1" * 40,
        "--account", "Polymarket Alt",
        "--dry-run",
        "--limit", "123",
        "--max-pages", "2",
        "-o", "/tmp/polymarket.csv",
    ])

    assert called == {
        "wallet": None,
        "proxy_wallet": "0x" + "1" * 40,
        "account_name": "Polymarket Alt",
        "dry_run": True,
        "output": "/tmp/polymarket.csv",
        "limit": 123,
        "max_pages": 2,
    }


def test_cli_stock_sync_polymarket_old_hyphenated_command_is_removed():
    """The old ft stock sync-polymarket spelling should not remain as a parallel public command."""
    with pytest.raises(SystemExit):
        cli.main(["stock", "sync-polymarket", "--dry-run"])


def test_stock_sync_exchange_dispatches_to_sync_exchange(monkeypatch, capsys):
    """`ft stock sync kraken` 应调用 exchange_sync.sync_exchange 并透传参数。"""
    import sys
    from ft import cli

    captured = {}

    def fake_sync_exchange(provider, account_name, since=None, dry_run=False,
                           output=None, symbols=None):
        captured.update(provider=provider, account_name=account_name,
                        since=since, dry_run=dry_run, output=output, symbols=symbols)
        return []

    monkeypatch.setattr("ft.exchange_sync.sync_exchange", fake_sync_exchange)
    monkeypatch.setattr(sys, "argv", [
        "ft", "stock", "sync", "kraken", "--account", "币安",
        "--dry-run", "--since", "2026-01-01", "--symbol", "BTC/USDT",
    ])
    cli.main()

    assert captured["provider"] == "kraken"
    assert captured["account_name"] == "币安"
    assert captured["dry_run"] is True
    assert captured["since"] == "2026-01-01"
    assert captured["symbols"] == ["BTC/USDT"]


def test_stock_sync_polymarket_still_dispatches(monkeypatch):
    """polymarket 分支零回归：仍调用 sync_polymarket。"""
    import sys
    from ft import cli

    called = {}
    monkeypatch.setattr("ft.polymarket_sync.sync_polymarket",
                        lambda **kw: called.update(kw) or [])
    monkeypatch.setattr(sys, "argv", [
        "ft", "stock", "sync", "polymarket", "--wallet", "0xabc", "--dry-run",
    ])
    cli.main()
    assert called["wallet"] == "0xabc"
    assert called["dry_run"] is True


def test_polymarket_sync_reads_proxy_wallet_from_credentials(tmp_env, monkeypatch):
    import yaml
    from ft import polymarket_sync

    proxy_wallet = "0x" + "1" * 40
    (tmp_env / "credentials.yaml").write_text(
        yaml.safe_dump({"polymarket": {"proxy_wallet": proxy_wallet.upper()}}),
        encoding="utf-8",
    )
    monkeypatch.setattr(polymarket_sync, "validate_security_account", lambda *args, **kwargs: None)
    called = {}

    def fake_fetch_activity(proxy_wallet_arg, limit=500, max_pages=None):
        called["proxy_wallet"] = proxy_wallet_arg
        return []

    monkeypatch.setattr(polymarket_sync, "fetch_activity", fake_fetch_activity)
    rows = polymarket_sync.sync_polymarket(dry_run=True)
    assert rows == []
    assert called["proxy_wallet"] == proxy_wallet


def test_polymarket_sync_reads_wallet_from_credentials_and_resolves(tmp_env, monkeypatch):
    import yaml
    from ft import polymarket_sync

    wallet = "0x" + "2" * 40
    proxy_wallet = "0x" + "3" * 40
    (tmp_env / "credentials.yaml").write_text(
        yaml.safe_dump({"polymarket": {"wallet": wallet}}),
        encoding="utf-8",
    )
    monkeypatch.setattr(polymarket_sync, "validate_security_account", lambda *args, **kwargs: None)
    monkeypatch.setattr(polymarket_sync, "resolve_proxy_wallet", lambda wallet_arg: proxy_wallet)
    called = {}

    def fake_fetch_activity(proxy_wallet_arg, limit=500, max_pages=None):
        called["proxy_wallet"] = proxy_wallet_arg
        return []

    monkeypatch.setattr(polymarket_sync, "fetch_activity", fake_fetch_activity)
    rows = polymarket_sync.sync_polymarket(dry_run=True)
    assert rows == []
    assert called["proxy_wallet"] == proxy_wallet
