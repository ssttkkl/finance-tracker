"""Tests for stock trading module"""
import csv
import sys
import tempfile
import time
from pathlib import Path

import pandas as pd
import pytest


@pytest.fixture
def tmp_env():
    """Setup temp .ft environment"""
    d = Path(tempfile.mkdtemp())
    records_dir = d / "records"

    from ft import models
    import ft.snapshot as snapshot_mod
    import ft.stock as stock
    old_snapshot_path = snapshot_mod.SNAPSHOT_PATH
    old_ft = models.FT_DIR
    old_records = models.RECORDS_DIR
    old_accounts_path = models.ACCOUNTS_PATH
    models.FT_DIR = d
    models.RECORDS_DIR = records_dir
    models.ACCOUNTS_PATH = d / "accounts.yaml"
    snapshot_mod.SNAPSHOT_PATH = d / "snapshot.yaml"

    yield d

    snapshot_mod.SNAPSHOT_PATH = old_snapshot_path
    models.FT_DIR = old_ft
    models.RECORDS_DIR = old_records
    models.ACCOUNTS_PATH = old_accounts_path
    import shutil
    shutil.rmtree(d, ignore_errors=True)


def test_snapshot_empty(tmp_env):
    """Loading non-existent path returns default dict"""
    from ft.snapshot import DEFAULT
    from ft.stock import load_snapshot

    snap = load_snapshot()
    assert snap == DEFAULT


def test_snapshot_roundtrip(tmp_env):
    """Save then load returns same data"""
    from ft.stock import load_snapshot, save_snapshot

    data = {
        "updated_at": "2026-06-12",
        "accounts": {
            "IBKR": {
                "currency": "USD",
                "cash": 10000.0,
                "positions": {
                    "nvda.us": {"shares": 45, "avg_cost": 224.14},
                },
            }
        },
    }
    save_snapshot(data)
    loaded = load_snapshot()
    assert loaded == data


def test_record_trade_writes_csv(tmp_env):
    """Trade creates CSV in security/ dir"""
    from ft.stock import record_trade

    row = record_trade(
        date="2026-06-12 10:00:00",
        action="BUY",
        ticker="nvda.us",
        shares=10,
        price=100.0,
        amount=-1000.0,
        commission=0.35,
        currency="USD",
        account_name="IBKR",
        note="test buy",
    )

    security_dir = tmp_env / "records" / "security"
    day_csv = security_dir / "2026-06-12.csv"
    assert day_csv.exists()

    with open(day_csv, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 1
    r = rows[0]
    assert r["action"] == "BUY"
    assert r["ticker"] == "nvda.us"
    assert float(r["shares"]) == 10
    assert float(r["price"]) == 100.0
    assert float(r["amount"]) == -1000.0
    assert float(r["commission"]) == 0.35
    assert r["currency"] == "USD"
    assert r["account_name"] == "IBKR"
    assert r["note"] == "test buy"
    # Verify returned row matches
    assert row["action"] == "BUY"
    assert row["ticker"] == "nvda.us"


def test_record_trade_sorts(tmp_env):
    """Multiple trades sorted by date"""
    from ft.stock import record_trade

    record_trade(
        date="2026-06-12 14:00:00", action="SELL", ticker="aapl",
        shares=5, price=200.0, amount=1000.0, commission=0.5,
        currency="USD", account_name="IBKR", note="sell 1",
    )
    record_trade(
        date="2026-06-12 09:00:00", action="BUY", ticker="aapl",
        shares=10, price=190.0, amount=-1900.0, commission=0.5,
        currency="USD", account_name="IBKR", note="buy 1",
    )

    day_csv = tmp_env / "records" / "security" / "2026-06-12.csv"
    assert day_csv.exists()

    with open(day_csv, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 2
    # Earlier date should come first
    assert rows[0]["date"] == "2026-06-12 09:00:00"
    assert rows[1]["date"] == "2026-06-12 14:00:00"


def test_do_buy_updates_snapshot(tmp_env):
    """Buy adds shares and subtracts cash"""
    from ft.stock import do_buy, load_snapshot

    # First, init a position
    do_buy(
        ticker="nvda.us", shares=45, price=224.14,
        commission=0.35, currency="USD", account_name="IBKR",
        note="initial buy", date="2026-06-12",
    )

    snap = load_snapshot()
    acct = snap["accounts"]["security"]["IBKR"]
    assert acct["cash"] == pytest.approx(-10086.65)  # -45*224.14 - 0.35

    pos = acct["positions"]["nvda.us"]
    assert pos["shares"] == 45
    assert pos["avg_cost"] == pytest.approx(224.14)

    # Second buy at different price
    do_buy(
        ticker="nvda.us", shares=10, price=230.0,
        commission=0.35, currency="USD", account_name="IBKR",
        note="second buy", date="2026-06-13",
    )

    snap = load_snapshot()
    acct = snap["accounts"]["security"]["IBKR"]
    # cash: -10086.65 + (-10*230 - 0.35) = -10086.65 - 2300.35 = -12387.0
    assert acct["cash"] == pytest.approx(-12387.0)

    pos = acct["positions"]["nvda.us"]
    assert pos["shares"] == 55
    # weighted avg is rounded to 2 decimals inside stock.py
    expected_avg = 225.21
    assert pos["avg_cost"] == pytest.approx(expected_avg)


def test_do_sell_updates_snapshot(tmp_env):
    """Sell removes shares and keeps avg_cost"""
    from ft.stock import do_buy, do_sell, load_snapshot

    # Setup: buy 55 shares at ~225.205
    do_buy(ticker="nvda.us", shares=55, price=225.205,
           commission=0.0, currency="USD", account_name="IBKR",
           date="2026-06-12")

    snap = load_snapshot()
    assert snap["accounts"]["security"]["IBKR"]["positions"]["nvda.us"]["shares"] == 55

    # Sell 10 shares at 250
    do_sell(ticker="nvda.us", shares=10, price=250.0,
            commission=0.35, currency="USD", account_name="IBKR",
            date="2026-06-13")

    snap = load_snapshot()
    acct = snap["accounts"]["security"]["IBKR"]
    pos = acct["positions"]["nvda.us"]
    assert pos["shares"] == 45
    assert pos["avg_cost"] == pytest.approx(219.71)

    # Cash: -(55*225.205) = -12386.275 (buy) + (10*250 - 0.35) = 2499.65 (sell)
    assert acct["cash"] == pytest.approx(-12386.275 + 2499.65)

    # Sell all remaining
    do_sell(ticker="nvda.us", shares=45, price=260.0,
            commission=0.35, currency="USD", account_name="IBKR",
            date="2026-06-14")

    snap = load_snapshot()
    acct = snap["accounts"]["security"]["IBKR"]
    # Position should be removed (or empty)
    assert "nvda.us" not in acct.get("positions", {})


def test_do_deposit_withdraw(tmp_env):
    """Deposit and withdraw update cash correctly"""
    from ft.stock import do_deposit, do_withdraw, load_snapshot

    do_deposit(amount=10000.0, currency="USD", account_name="IBKR",
               note="deposit", date="2026-06-12")

    snap = load_snapshot()
    assert snap["accounts"]["security"]["IBKR"]["cash"] == pytest.approx(10000.0)

    do_withdraw(amount=3000.0, currency="USD", account_name="IBKR",
                note="withdraw", date="2026-06-13")

    snap = load_snapshot()
    assert snap["accounts"]["security"]["IBKR"]["cash"] == pytest.approx(7000.0)


def test_do_dividend(tmp_env):
    """Dividend adds cash with no position change"""
    from ft.stock import do_dividend, do_checkin_ticker, load_snapshot

    do_checkin_ticker(ticker="aapl", shares=50, avg_cost=150.0,
            currency="USD", account_name="IBKR", date="2026-06-01")

    do_dividend(ticker="aapl", amount=25.0, currency="USD",
                account_name="IBKR", note="dividend", date="2026-06-15")

    snap = load_snapshot()
    acct = snap["accounts"]["security"]["IBKR"]
    assert acct["cash"] == pytest.approx(25.0)
    # Position unchanged
    assert acct["positions"]["aapl"]["shares"] == 50


def test_do_checkin_ticker(tmp_env):
    """Checkin ticker overwrites position"""
    from ft.stock import do_buy, do_checkin_ticker, load_snapshot

    do_buy(ticker="nvda.us", shares=50, price=200.0,
           commission=0.5, currency="USD", account_name="IBKR",
           date="2026-06-12 09:00:00")

    # Checkin: overwrite position
    do_checkin_ticker(ticker="nvda.us", shares=55, avg_cost=210.0,
                      currency="USD", account_name="IBKR",
                      note="checkin", date="2026-06-12 10:00:00")

    snap = load_snapshot()
    pos = snap["accounts"]["security"]["IBKR"]["positions"]["nvda.us"]
    assert pos["shares"] == 55
    assert pos["avg_cost"] == 210.0



def test_do_list_shows_fractional_shares(tmp_env, monkeypatch, capsys):
    """Portfolio list preserves fractional Polymarket shares."""
    from ft.stock import do_checkin_ticker, do_list

    do_checkin_ticker(ticker="pm:test:no", shares=323.5, avg_cost=0.92,
                      currency="USD", account_name="Polymarket",
                      note="test", date="2026-06-12 10:00:00")
    do_checkin_ticker(ticker="pm:other:no", shares=16.7, avg_cost=0.86,
                      currency="USD", account_name="Polymarket",
                      note="test", date="2026-06-12 10:01:00")
    monkeypatch.setattr("ft.stock._fetch_prices", lambda tickers: {
        "pm:test:no": 0.92,
        "pm:other:no": 0.86,
    })

    do_list()
    out = capsys.readouterr().out
    assert "323.5" in out
    assert "16.7" in out


def test_do_checkin_cash(tmp_env):
    """Checkin cash overwrites cash balance"""
    from ft.stock import do_deposit, do_checkin_cash, load_snapshot

    do_deposit(amount=5000.0, currency="USD", account_name="IBKR",
               note="deposit", date="2026-06-10")

    do_checkin_cash(cash=12345.67, account_name="IBKR",
                    note="reconcile", date="2026-06-12")

    snap = load_snapshot()
    assert snap["accounts"]["security"]["IBKR"]["cash"] == pytest.approx(12345.67)


def test_fetch_prices_single_hk_series(monkeypatch):
    """HK single-ticker downloads should return the last close."""
    from ft.stock import _fetch_prices

    def fake_download(tickers, period=None, progress=None, auto_adjust=False):
        assert tickers == ["0700.HK"]
        return pd.DataFrame(
            {
                "Open": [310.0, 320.0],
                "Close": [321.5, 322.8],
            },
            index=pd.Index(["2026-06-12", "2026-06-13"]),
        )

    fake_yf = type("FakeYF", (), {"download": staticmethod(fake_download)})
    monkeypatch.setitem(sys.modules, "yfinance", fake_yf)
    monkeypatch.setattr(time, "sleep", lambda *_: None)

    prices = _fetch_prices(["00700.hk"])
    assert prices == {"00700.hk": pytest.approx(322.8)}


def test_fetch_prices_multi_ticker_dataframe(monkeypatch):
    """Multiple tickers should still extract each close correctly."""
    from ft.stock import _fetch_prices

    cols = pd.MultiIndex.from_tuples(
        [("Close", "AAPL"), ("Close", "MSFT")]
    )
    data = pd.DataFrame(
        [[195.0, 430.0], [196.5, 431.2]],
        columns=cols,
        index=pd.Index(["2026-06-12", "2026-06-13"]),
    )

    def fake_download(tickers, period=None, progress=False, auto_adjust=False):
        assert tickers == ["AAPL", "MSFT"]
        return data

    fake_yf = type("FakeYF", (), {"download": staticmethod(fake_download)})
    monkeypatch.setitem(sys.modules, "yfinance", fake_yf)
    monkeypatch.setattr(time, "sleep", lambda *_: None)

    prices = _fetch_prices(["aapl.us", "msft.us"])
    assert prices == {
        "aapl.us": pytest.approx(196.5),
        "msft.us": pytest.approx(431.2),
    }


def test_fetch_prices_polymarket_outcome_token(monkeypatch):
    """Polymarket outcome token tickers should resolve via gamma API."""
    from ft.stock import _fetch_prices

    class FakeResponse:
        def __init__(self, payload: bytes):
            self.payload = payload

        def read(self):
            return self.payload

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, tb):
            return False

    def fake_urlopen(req, timeout=15):
        url = req.full_url if hasattr(req, "full_url") else req
        assert "gamma-api.polymarket.com/markets" in url
        assert "slug=new-rhianna-album-before-gta-vi-926" in url
        payload = b"[{\"slug\":\"new-rhianna-album-before-gta-vi-926\",\"outcomes\":[\"Yes\",\"No\"],\"outcomePrices\":[\"0.525\",\"0.475\"],\"bestBid\":0.52,\"bestAsk\":0.53}]"
        return FakeResponse(payload)

    monkeypatch.setattr("urllib.request.urlopen", fake_urlopen)
    monkeypatch.setitem(sys.modules, "yfinance", None)

    prices = _fetch_prices([
        "pm:new-rhianna-album-before-gta-vi-926:yes",
        "pm:new-rhianna-album-before-gta-vi-926:no",
    ])
    assert prices == {
        "pm:new-rhianna-album-before-gta-vi-926:yes": pytest.approx(0.525),
        "pm:new-rhianna-album-before-gta-vi-926:no": pytest.approx(0.475),
    }


def test_fetch_prices_polymarket_stringified_lists(monkeypatch):
    """Gamma sometimes returns list fields as JSON strings; we should parse them."""
    from ft.stock import _fetch_prices

    class FakeResponse:
        def __init__(self, payload: bytes):
            self.payload = payload

        def read(self):
            return self.payload

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, tb):
            return False

    def fake_urlopen(req, timeout=15):
        payload = b"[{\"slug\":\"will-russia-capture-kostyantynivka-by-june-30-382-954-769\",\"outcomes\":\"[\\\"Yes\\\",\\\"No\\\"]\",\"outcomePrices\":\"[\\\"0.08\\\",\\\"0.92\\\"]\",\"lastTradePrice\":0.085}]"
        return FakeResponse(payload)

    monkeypatch.setattr("urllib.request.urlopen", fake_urlopen)
    monkeypatch.setitem(sys.modules, "yfinance", None)

    prices = _fetch_prices(["pm:will-russia-capture-kostyantynivka-by-june-30-382-954-769:no"])
    assert prices == {
        "pm:will-russia-capture-kostyantynivka-by-june-30-382-954-769:no": pytest.approx(0.92),
    }


def test_extract_polymarket_proxy_wallet_from_profile_html():
    """Profile HTML payload exposes the proxy wallet used by data-api."""
    from ft.polymarket_sync import extract_proxy_wallet

    dummy_proxy = "0x" + "1" * 40
    html = r'{\"proxyAddress\":\"' + dummy_proxy + r'\"}'
    assert extract_proxy_wallet(html) == dummy_proxy


def test_polymarket_activity_trade_to_stock_row_maps_yes_no():
    """A TRADE activity row should become one ft stock CSV row."""
    from ft.polymarket_sync import activity_to_stock_row

    row = activity_to_stock_row({
        "timestamp": 1782785769,
        "type": "TRADE",
        "side": "BUY",
        "slug": "will-test-market-close-yes",
        "outcome": "No",
        "size": 12.3456,
        "price": 0.91,
        "usdcSize": 11.234496,
        "transactionHash": "0xabc123",
    }, account_name="Polymarket")

    assert row == {
        "date": "2026-06-30 10:16:09",
        "action": "BUY",
        "ticker": "pm:will-test-market-close-yes:no",
        "shares": "12.3456",
        "price": "0.91",
        "amount": "-11.234496",
        "commission": "0",
        "currency": "USD",
        "account_name": "Polymarket",
        "note": "polymarket tx:0xabc123",
    }


def test_polymarket_activity_trade_rejects_unknown_outcome():
    """Unknown outcome labels must fail loudly rather than being silently imported."""
    from ft.polymarket_sync import activity_to_stock_row

    with pytest.raises(ValueError, match="unsupported Polymarket outcome"):
        activity_to_stock_row({
            "timestamp": 1782785769,
            "type": "TRADE",
            "side": "BUY",
            "slug": "multi-outcome-market",
            "outcome": "Alice",
            "size": 1,
            "price": 0.5,
            "usdcSize": 0.5,
            "transactionHash": "0xabc",
        }, account_name="Polymarket")


def test_filter_new_polymarket_rows_dedupes_by_transaction_hash(tmp_env):
    """Incremental sync should skip rows whose tx hash is already recorded."""
    import csv
    from ft import stock
    from ft.polymarket_sync import filter_new_rows

    stock.record_trade(
        date="2026-06-30 10:16:09",
        action="BUY",
        ticker="pm:old-market:no",
        shares=1,
        price=0.9,
        amount=-0.9,
        commission=0,
        currency="USD",
        account_name="Polymarket",
        note="polymarket tx:0xexisting",
    )

    rows = [
        {
            "date": "2026-06-30 10:16:09", "action": "BUY", "ticker": "pm:old-market:no",
            "shares": "1", "price": "0.9", "amount": "-0.9", "commission": "0",
            "currency": "USD", "account_name": "Polymarket", "note": "polymarket tx:0xexisting",
        },
        {
            "date": "2026-07-01 11:00:00", "action": "SELL", "ticker": "pm:new-market:yes",
            "shares": "2", "price": "0.8", "amount": "1.6", "commission": "0",
            "currency": "USD", "account_name": "Polymarket", "note": "polymarket tx:0xnew",
        },
    ]

    assert filter_new_rows(rows)[0]["note"] == "polymarket tx:0xnew"


def test_stock_append_preserves_transfer_style_security_rows(tmp_env):
    """Appending stock rows on a day with security transfer audit rows must not crash or drop audit fields."""
    from ft import models
    from ft.stock import do_append, verify_security, CSV_FIELDS

    models.ACCOUNTS_PATH.write_text(
        "accounts:\n"
        "  - name: Polymarket\n"
        "    type: security\n"
        "    currency: USD\n"
        "    active: true\n"
        "  - name: 东方证券\n"
        "    type: security\n"
        "    currency: CNY\n"
        "    active: true\n",
        encoding="utf-8",
    )
    security_dir = models.RECORDS_DIR / "security"
    security_dir.mkdir(parents=True, exist_ok=True)
    day_path = security_dir / "2026-06-30.csv"
    day_path.write_text(
        "date,amount,currency,counterparty,description,category,account_name,source,bill_source,transfer_account\n"
        "2026-06-30 09:00:00,735.29,USD,,购汇入金,transfer_in,Polymarket,手动,,东方证券\n",
        encoding="utf-8",
    )

    input_csv = tmp_env / "pm.csv"
    with input_csv.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        writer.writeheader()
        writer.writerow({
            "date": "2026-06-30 10:16:09", "action": "BUY", "ticker": "pm:test:no",
            "shares": "2", "price": "0.8", "amount": "-1.6", "commission": "0",
            "currency": "USD", "account_name": "Polymarket", "note": "polymarket tx:0xnew",
        })

    assert do_append(input_csv) is True

    with day_path.open(encoding="utf-8") as f:
        reader = csv.DictReader(f)
        fieldnames = reader.fieldnames or []
        rows = list(reader)
    assert "transfer_account" in fieldnames
    assert any(r.get("transfer_account") == "东方证券" for r in rows)
    assert any(r.get("ticker") == "pm:test:no" for r in rows)
    ok, lines = verify_security()
    assert ok, "\n".join(lines)


def test_filter_new_polymarket_rows_keeps_distinct_fills_with_same_tx(tmp_env):
    """One Polymarket tx can contain multiple distinct fills; only exact duplicates should collapse."""
    from ft.polymarket_sync import filter_new_rows

    rows = [
        {
            "date": "2026-06-30 10:00:00", "action": "BUY", "ticker": "pm:market-a:yes",
            "shares": "10", "price": "0.5", "amount": "-5", "commission": "0",
            "currency": "USD", "account_name": "Polymarket", "note": "polymarket tx:0xabc123",
        },
        {
            "date": "2026-06-30 10:00:00", "action": "BUY", "ticker": "pm:market-b:no",
            "shares": "8", "price": "0.25", "amount": "-2", "commission": "0",
            "currency": "USD", "account_name": "Polymarket", "note": "polymarket tx:0xabc123",
        },
        {
            "date": "2026-06-30 10:00:00", "action": "BUY", "ticker": "pm:market-b:no",
            "shares": "8", "price": "0.25", "amount": "-2", "commission": "0",
            "currency": "USD", "account_name": "Polymarket", "note": "polymarket tx:0xabc123",
        },
    ]

    new_rows = filter_new_rows(rows)

    assert [row["ticker"] for row in new_rows] == ["pm:market-a:yes", "pm:market-b:no"]


def test_filter_new_polymarket_rows_scopes_tx_dedupe_by_account(tmp_env):
    """Different Polymarket accounts should not suppress each other's tx hashes."""
    from ft.polymarket_sync import filter_new_rows
    from ft.stock import record_trade

    record_trade(
        date="2026-06-30 10:00:00", action="BUY", ticker="pm:market-a:yes",
        shares=1, price=0.5, amount=-0.5, commission=0,
        currency="USD", account_name="PolymarketA", note="polymarket tx:0xshared",
    )
    rows_for_b = [{
        "date": "2026-06-30 10:00:00", "action": "BUY", "ticker": "pm:market-a:yes",
        "shares": "1", "price": "0.5", "amount": "-0.5", "commission": "0",
        "currency": "USD", "account_name": "PolymarketB", "note": "polymarket tx:0xshared",
    }]
    rows_for_a = [dict(rows_for_b[0], account_name="PolymarketA")]

    assert filter_new_rows(rows_for_b, account_name="PolymarketB") == rows_for_b
    assert filter_new_rows(rows_for_a, account_name="PolymarketA") == []


def test_sync_polymarket_custom_account_uses_account_scoped_rows(tmp_env, monkeypatch):
    """Custom account sync should validate, convert rows, and dedupe only within that account."""
    from ft.accounts import save_accounts
    from ft.polymarket_sync import sync_polymarket
    from ft.stock import record_trade
    from ft import models

    save_accounts([
        {"name": "Polymarket", "type": "security", "currency": "USD", "active": True},
        {"name": "Polymarket Alt", "type": "security", "currency": "USD", "active": True},
    ], models.ACCOUNTS_PATH)
    record_trade(
        date="2026-06-30 10:00:00", action="BUY", ticker="pm:test-market:yes",
        shares=1, price=0.5, amount=-0.5, commission=0,
        currency="USD", account_name="Polymarket", note="polymarket tx:0xabc123",
    )
    monkeypatch.setattr("ft.polymarket_sync.fetch_activity", lambda *_args, **_kwargs: [{
        "timestamp": 1782785769,
        "type": "TRADE",
        "side": "BUY",
        "slug": "test-market",
        "outcome": "Yes",
        "size": "2",
        "price": "0.5",
        "usdcSize": "1",
        "transactionHash": "0xabc123",
    }])

    rows = sync_polymarket(proxy_wallet="0x" + "1" * 40, account_name="Polymarket Alt", dry_run=True)

    assert len(rows) == 1
    assert rows[0]["account_name"] == "Polymarket Alt"
    assert rows[0]["note"] == "polymarket tx:0xabc123"


def test_stock_append_rejects_non_security_account(tmp_env):
    """Stock imports must not write records/security for cash/loan/lend accounts."""
    from ft.accounts import save_accounts
    from ft.stock import CSV_FIELDS, do_append
    from ft import models

    save_accounts([
        {"name": "Polymarket", "type": "cash", "currency": "USD", "active": True},
    ], models.ACCOUNTS_PATH)
    csv_path = tmp_env / "pm_cash_account.csv"
    with csv_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        writer.writeheader()
        writer.writerow({
            "date": "2026-06-30 10:00:00", "action": "BUY", "ticker": "pm:test:yes",
            "shares": "1", "price": "0.5", "amount": "-0.5", "commission": "0",
            "currency": "USD", "account_name": "Polymarket", "note": "polymarket tx:0xabc123",
        })

    assert do_append(csv_path) is False
    assert not (models.RECORDS_DIR / "security" / "2026-06-30.csv").exists()


def test_stock_append_routes_same_name_by_currency_and_security_type(tmp_env):
    """Stock imports should validate the account matching both name and currency."""
    from ft.accounts import save_accounts
    from ft.stock import CSV_FIELDS, do_append
    from ft import models

    save_accounts([
        {"name": "Broker", "type": "cash", "currency": "CNY", "active": True},
        {"name": "Broker", "type": "security", "currency": "USD", "active": True},
    ], models.ACCOUNTS_PATH)
    csv_path = tmp_env / "broker_usd_stock.csv"
    with csv_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        writer.writeheader()
        writer.writerow({
            "date": "2026-06-30 10:00:00", "action": "BUY", "ticker": "nvda.us",
            "shares": "1", "price": "100", "amount": "-100", "commission": "0",
            "currency": "USD", "account_name": "Broker", "note": "usd security account",
        })

    assert do_append(csv_path) is True
    assert (models.RECORDS_DIR / "security" / "2026-06-30.csv").exists()


def test_sync_polymarket_rejects_non_security_account_before_network(tmp_env, monkeypatch):
    """sync polymarket should validate target account type before fetching Activity."""
    from ft.accounts import save_accounts
    from ft.polymarket_sync import sync_polymarket
    from ft import models

    save_accounts([
        {"name": "Polymarket", "type": "cash", "currency": "USD", "active": True},
    ], models.ACCOUNTS_PATH)
    monkeypatch.setattr("ft.polymarket_sync.fetch_activity", lambda *_args, **_kwargs: pytest.fail("network should not be called"))

    with pytest.raises(ValueError, match="不是 security 类型"):
        sync_polymarket(proxy_wallet="0x" + "1" * 40, dry_run=True)


def test_polymarket_sync_rejects_invalid_pagination_without_network(monkeypatch):
    """Invalid pagination arguments should fail before any API request."""
    from ft.polymarket_sync import fetch_activity

    monkeypatch.setattr("ft.polymarket_sync._request_json", lambda *_: pytest.fail("network should not be called"))
    with pytest.raises(ValueError, match="limit must be positive"):
        fetch_activity("0x" + "1" * 40, limit=0)
    with pytest.raises(ValueError, match="max_pages must be positive"):
        fetch_activity("0x" + "1" * 40, max_pages=0)


def test_polymarket_activity_rejects_non_dict_and_nan():
    """Payload items and numeric values must be valid before conversion."""
    from ft.polymarket_sync import activity_to_stock_row

    with pytest.raises(ValueError, match="activity item must be object"):
        activity_to_stock_row("not-a-dict")
    with pytest.raises(ValueError, match="invalid numeric value"):
        activity_to_stock_row({
            "timestamp": 1782785769,
            "type": "TRADE",
            "side": "BUY",
            "slug": "test-market",
            "outcome": "Yes",
            "size": "NaN",
            "price": "0.5",
            "usdcSize": "NaN",
            "transactionHash": "0xabc",
        })


def test_cli_sync_polymarket_errors_exit_nonzero(tmp_env, capsys):
    """CLI automation must see sync validation failures as non-zero exits."""
    from ft.cli import main
    from ft import models

    models.ACCOUNTS_PATH.write_text(
        "accounts:\n"
        "  - name: Polymarket\n"
        "    type: security\n"
        "    currency: USD\n"
        "    active: true\n",
        encoding="utf-8",
    )

    with pytest.raises(SystemExit) as excinfo:
        main(["stock", "sync", "polymarket"])
    assert excinfo.value.code == 1
    assert "必须指定 wallet 或 proxy_wallet" in capsys.readouterr().out


def test_transfer_to_security_preserves_existing_stock_rows(tmp_env):
    """Transfer writes to security records must preserve same-day stock audit columns."""
    from ft import models
    from ft.transfer import do_transfer
    from ft.stock import CSV_FIELDS

    models.ACCOUNTS_PATH.write_text(
        "accounts:\n"
        "  - name: 现金\n"
        "    type: cash\n"
        "    currency: CNY\n"
        "    active: true\n"
        "  - name: Polymarket\n"
        "    type: security\n"
        "    currency: USD\n"
        "    active: true\n",
        encoding="utf-8",
    )
    security_dir = models.RECORDS_DIR / "security"
    security_dir.mkdir(parents=True, exist_ok=True)
    day_path = security_dir / "2026-06-30.csv"
    with day_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=CSV_FIELDS + ["transfer_account"])
        writer.writeheader()
        writer.writerow({
            "date": "2026-06-30 10:16:09", "action": "BUY", "ticker": "pm:test:no",
            "shares": "2", "price": "0.8", "amount": "-1.6", "commission": "0",
            "currency": "USD", "account_name": "Polymarket", "note": "polymarket tx:0xnew",
        })

    do_transfer("现金", "Polymarket", 100, to_amount=10, date="2026-06-30", time_str="11:00:00")

    with day_path.open(encoding="utf-8") as f:
        reader = csv.DictReader(f)
        rows = list(reader)
    assert any(r.get("ticker") == "pm:test:no" and r.get("action") == "BUY" for r in rows)
    assert any(r.get("transfer_account") == "现金" and r.get("category") == "transfer_in" for r in rows)


def test_general_append_to_security_preserves_existing_stock_rows(tmp_env):
    """Generic ft append routed to security must not rewrite stock rows as transfer-only rows."""
    from ft import models
    from ft.append import do_append as generic_append
    from ft.stock import CSV_FIELDS

    models.ACCOUNTS_PATH.write_text(
        "accounts:\n"
        "  - name: Polymarket\n"
        "    type: security\n"
        "    currency: USD\n"
        "    active: true\n",
        encoding="utf-8",
    )
    security_dir = models.RECORDS_DIR / "security"
    security_dir.mkdir(parents=True, exist_ok=True)
    day_path = security_dir / "2026-06-30.csv"
    with day_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=CSV_FIELDS + ["transfer_account"])
        writer.writeheader()
        writer.writerow({
            "date": "2026-06-30 10:16:09", "action": "BUY", "ticker": "pm:test:no",
            "shares": "2", "price": "0.8", "amount": "-1.6", "commission": "0",
            "currency": "USD", "account_name": "Polymarket", "note": "polymarket tx:0xnew",
        })

    input_csv = tmp_env / "transfer.csv"
    input_csv.write_text(
        "date,amount,currency,counterparty,description,category,account_name,source,bill_source,transfer_account\n"
        "2026-06-30 11:00:00,10,USD,,manual transfer,transfer_in,Polymarket,手动,,现金\n",
        encoding="utf-8",
    )

    generic_append(str(input_csv))

    with day_path.open(encoding="utf-8") as f:
        reader = csv.DictReader(f)
        rows = list(reader)
    assert any(r.get("ticker") == "pm:test:no" and r.get("action") == "BUY" for r in rows)
    assert any(r.get("transfer_account") == "现金" and r.get("category") == "transfer_in" for r in rows)


def test_stock_append_rolls_back_if_later_day_write_fails(tmp_env, monkeypatch):
    """Multi-day stock append must not leave earlier day files when a later write fails."""
    from ft import models
    from ft.stock import do_append, CSV_FIELDS

    models.ACCOUNTS_PATH.write_text(
        "accounts:\n"
        "  - name: Polymarket\n"
        "    type: security\n"
        "    currency: USD\n"
        "    active: true\n",
        encoding="utf-8",
    )
    input_csv = tmp_env / "multi.csv"
    with input_csv.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        writer.writeheader()
        writer.writerow({
            "date": "2026-06-30 10:00:00", "action": "BUY", "ticker": "pm:first:no",
            "shares": "1", "price": "0.8", "amount": "-0.8", "commission": "0",
            "currency": "USD", "account_name": "Polymarket", "note": "first",
        })
        writer.writerow({
            "date": "2026-07-01 10:00:00", "action": "BUY", "ticker": "pm:second:no",
            "shares": "1", "price": "0.7", "amount": "-0.7", "commission": "0",
            "currency": "USD", "account_name": "Polymarket", "note": "second",
        })

    import ft.stock as stock_mod
    real_writer = stock_mod._write_security_csv
    calls = []

    def fail_on_second(path, rows):
        calls.append(path.name)
        if len(calls) == 2:
            raise RuntimeError("simulated write failure")
        return real_writer(path, rows)

    monkeypatch.setattr(stock_mod, "_write_security_csv", fail_on_second)

    with pytest.raises(RuntimeError, match="simulated write failure"):
        do_append(input_csv)
    assert not (models.RECORDS_DIR / "security" / "2026-06-30.csv").exists()
    assert not (models.RECORDS_DIR / "security" / "2026-07-01.csv").exists()


def test_direct_stock_operations_reject_non_finite_numbers(tmp_env):
    """Direct stock CLI helpers must not bypass finite numeric validation."""
    from ft.stock import do_buy, load_snapshot

    with pytest.raises(ValueError, match="not finite"):
        do_buy(ticker="nvda.us", shares=float("nan"), price=100, commission=0,
               currency="USD", account_name="IBKR", date="2026-06-30")
    assert load_snapshot()["accounts"]["security"] == {}


def test_do_buy_rejects_overflow_amount_before_mutating(tmp_env):
    """Finite inputs whose derived amount overflows must not mutate snapshot or records."""
    from ft import models
    from ft.stock import do_buy, load_snapshot

    with pytest.raises(ValueError, match="not finite"):
        do_buy(ticker="nvda.us", shares=1e308, price=1e308, commission=0,
               currency="USD", account_name="IBKR", date="2026-06-30")
    assert load_snapshot()["accounts"]["security"] == {}
    assert not (models.RECORDS_DIR / "security" / "2026-06-30.csv").exists()


def test_do_buy_rolls_back_snapshot_if_record_trade_fails(tmp_env, monkeypatch):
    """Direct stock helpers must not leave snapshot changed when CSV recording fails."""
    from ft.stock import do_buy, load_snapshot, save_snapshot

    save_snapshot({
        "updated_at": "2026-06-29",
        "accounts": {"security": {"IBKR": {"currency": "USD", "cash": 100, "positions": {}}}},
    })

    def fail_record_trade(**kwargs):
        raise RuntimeError("simulated record failure")

    monkeypatch.setattr("ft.stock.record_trade", fail_record_trade)
    with pytest.raises(RuntimeError, match="simulated record failure"):
        do_buy(ticker="nvda.us", shares=1, price=10, commission=0,
               currency="USD", account_name="IBKR", date="2026-06-30")

    snap = load_snapshot()
    assert snap["updated_at"] == "2026-06-29"
    assert snap["accounts"]["security"]["IBKR"]["cash"] == 100
    assert snap["accounts"]["security"]["IBKR"]["positions"] == {}


def test_do_buy_rolls_back_csv_if_recording_partially_writes_then_fails(tmp_env, monkeypatch):
    """Direct stock rollback must restore both snapshot and same-day CSV on write failures."""
    from ft import models
    from ft.stock import do_buy, load_snapshot, save_snapshot

    security_dir = models.RECORDS_DIR / "security"
    security_dir.mkdir(parents=True, exist_ok=True)
    day_path = security_dir / "2026-06-30.csv"
    original_csv = (
        "date,action,ticker,shares,price,amount,commission,currency,account_name,note\n"
        "2026-06-30 09:00:00,DEPOSIT,,0,0,100,0,USD,IBKR,initial\n"
    )
    day_path.write_text(original_csv, encoding="utf-8")
    save_snapshot({
        "updated_at": "2026-06-29",
        "accounts": {"security": {"IBKR": {"currency": "USD", "cash": 100, "positions": {}}}},
    })

    def corrupt_then_fail(path, rows):
        path.write_text("corrupted partial write\n", encoding="utf-8")
        raise RuntimeError("simulated csv failure")

    monkeypatch.setattr("ft.stock._write_security_csv", corrupt_then_fail)
    with pytest.raises(RuntimeError, match="simulated csv failure"):
        do_buy(ticker="nvda.us", shares=1, price=10, commission=0,
               currency="USD", account_name="IBKR", date="2026-06-30")

    assert day_path.read_text(encoding="utf-8") == original_csv
    snap = load_snapshot()
    assert snap["updated_at"] == "2026-06-29"
    assert snap["accounts"]["security"]["IBKR"]["cash"] == 100
    assert snap["accounts"]["security"]["IBKR"]["positions"] == {}


def test_stock_append_rejects_derived_overflow_before_writing(tmp_env):
    """Stock append must reject finite raw fields whose replay-derived value overflows."""
    from ft import models
    from ft.stock import do_append, load_snapshot

    (tmp_env / "accounts.yaml").write_text(
        "accounts:\n"
        "  - name: IBKR\n"
        "    type: security\n"
        "    currency: USD\n",
        encoding="utf-8",
    )
    csv_path = tmp_env / "overflow.csv"
    csv_path.write_text(
        "date,action,ticker,shares,price,amount,commission,currency,account_name,note\n"
        "2026-06-30 10:00:00,BUY,nvda.us,1e308,1e308,-1,0,USD,IBKR,overflow\n",
        encoding="utf-8",
    )

    assert do_append(csv_path) is False
    assert load_snapshot()["accounts"]["security"] == {}
    assert not (models.RECORDS_DIR / "security" / "2026-06-30.csv").exists()


def test_checkin_ticker_rejects_derived_overflow_before_mutating(tmp_env):
    """CHECKIN ticker must reject shares*avg_cost overflow before snapshot/CSV writes."""
    from ft import models
    from ft.stock import do_checkin_ticker, load_snapshot

    with pytest.raises(ValueError, match="not finite"):
        do_checkin_ticker(ticker="nvda.us", shares=1e308, avg_cost=1e308,
                          currency="USD", account_name="IBKR", date="2026-06-30")
    assert load_snapshot()["accounts"]["security"] == {}
    assert not (models.RECORDS_DIR / "security" / "2026-06-30.csv").exists()


def test_stock_append_rejects_cumulative_overflow_before_writing(tmp_env):
    """Individually finite rows must not overflow cumulative replay state."""
    from ft import models
    from ft.stock import do_append, load_snapshot

    models.ACCOUNTS_PATH.write_text(
        "accounts:\n"
        "  - name: IBKR\n"
        "    type: security\n"
        "    currency: USD\n",
        encoding="utf-8",
    )
    csv_path = tmp_env / "cumulative_overflow.csv"
    csv_path.write_text(
        "date,action,ticker,shares,price,amount,commission,currency,account_name,note\n"
        "2026-06-30 10:00:00,BUY,nvda.us,1e308,1,-1e308,0,USD,IBKR,first\n"
        "2026-06-30 10:00:01,BUY,nvda.us,1e308,1,-1e308,0,USD,IBKR,second\n",
        encoding="utf-8",
    )

    assert do_append(csv_path) is False
    assert load_snapshot()["accounts"]["security"] == {}
    assert not (models.RECORDS_DIR / "security" / "2026-06-30.csv").exists()


def test_direct_do_buy_rejects_cumulative_overflow_without_second_write(tmp_env):
    """Second individually finite BUY must be rejected if it overflows existing position state."""
    from ft import models
    from ft.stock import do_buy, load_snapshot

    do_buy(ticker="nvda.us", shares=1e308, price=1, commission=0,
           currency="USD", account_name="IBKR", date="2026-06-30 10:00:00")
    with pytest.raises(ValueError, match="not finite"):
        do_buy(ticker="nvda.us", shares=1e308, price=1, commission=0,
               currency="USD", account_name="IBKR", date="2026-06-30 10:00:01")

    snap = load_snapshot()
    pos = snap["accounts"]["security"]["IBKR"]["positions"]["nvda.us"]
    assert pos["shares"] == 1e308
    assert pos["avg_cost"] == 1.0
    rows = (models.RECORDS_DIR / "security" / "2026-06-30.csv").read_text(encoding="utf-8").splitlines()
    assert len(rows) == 2  # header + first BUY only


def test_direct_deposit_rejects_cumulative_cash_overflow_without_write(tmp_env):
    """Cash operations must reject cumulative overflow before snapshot/CSV writes."""
    from ft import models
    from ft.stock import do_deposit, load_snapshot

    do_deposit(amount=1e308, currency="USD", account_name="IBKR", date="2026-06-30 10:00:00")
    with pytest.raises(ValueError, match="not finite"):
        do_deposit(amount=1e308, currency="USD", account_name="IBKR", date="2026-06-30 10:00:01")

    snap = load_snapshot()
    assert snap["accounts"]["security"]["IBKR"]["cash"] == 1e308
    rows = (models.RECORDS_DIR / "security" / "2026-06-30.csv").read_text(encoding="utf-8").splitlines()
    assert len(rows) == 2  # header + first DEPOSIT only


def test_direct_save_snapshot_failure_restores_previous_snapshot(tmp_env, monkeypatch):
    """If snapshot write partially fails, direct operation must restore the previous snapshot file."""
    from ft import snapshot as snapshot_mod
    from ft.stock import do_buy, load_snapshot, save_snapshot

    save_snapshot({
        "updated_at": "2026-06-29",
        "accounts": {"security": {"IBKR": {"currency": "USD", "cash": 100, "positions": {}}}},
    })
    original = snapshot_mod.SNAPSHOT_PATH.read_text(encoding="utf-8")

    def corrupt_then_fail(_snap):
        snapshot_mod.SNAPSHOT_PATH.write_text("corrupted partial snapshot\n", encoding="utf-8")
        raise RuntimeError("simulated snapshot failure")

    monkeypatch.setattr("ft.stock.save_snapshot", corrupt_then_fail)
    with pytest.raises(RuntimeError, match="simulated snapshot failure"):
        do_buy(ticker="nvda.us", shares=1, price=10, commission=0,
               currency="USD", account_name="IBKR", date="2026-06-30")

    assert snapshot_mod.SNAPSHOT_PATH.read_text(encoding="utf-8") == original
    snap = load_snapshot()
    assert snap["updated_at"] == "2026-06-29"
    assert snap["accounts"]["security"]["IBKR"]["cash"] == 100


def test_replay_skips_malformed_action_rows_but_keeps_cash_rows(tmp_env):
    """Malformed stock rows should not crash replay, while ticker-empty cash rows remain valid."""
    from ft import models
    from ft.stock import _replay_security_csv

    security_dir = models.RECORDS_DIR / "security"
    security_dir.mkdir(parents=True, exist_ok=True)
    (security_dir / "2026-06-29.csv").write_text(
        "date,action,ticker,shares,price,amount,commission,currency,note\n"
        "2026-06-29 10:00:00,BUY,nvda.us,1,10,-10,0,USD,missing account\n",
        encoding="utf-8",
    )
    (security_dir / "2026-06-30.csv").write_text(
        "date,action,ticker,shares,price,amount,commission,currency,account_name,note\n"
        "2026-06-30 10:00:00,DEPOSIT,,0,0,25,0,USD,IBKR,cash deposit\n",
        encoding="utf-8",
    )

    positions, cash = _replay_security_csv()
    assert positions == {}
    assert cash["IBKR"] == pytest.approx(25)


def test_security_balance_uses_current_market_price(tmp_env, monkeypatch):
    """Security account balances should use current market prices when available."""
    from ft.stock import load_snapshot, save_snapshot
    from ft.acct import _compute_balance

    save_snapshot({
        "updated_at": "2026-06-12",
        "accounts": {
            "security": {
                "POLY": {
                    "currency": "USD",
                    "cash": 100.0,
                    "positions": {
                        "pm:new-rhianna-album-before-gta-vi-926:yes": {
                            "shares": 10,
                            "avg_cost": 0.40,
                        },
                    },
                },
            },
        },
    })

    def fake_fetch_prices(tickers):
        assert tickers == ["pm:new-rhianna-album-before-gta-vi-926:yes"]
        return {"pm:new-rhianna-album-before-gta-vi-926:yes": 0.75}

    monkeypatch.setattr("ft.stock._fetch_prices", fake_fetch_prices)

    bal = _compute_balance("POLY", "USD")
    assert bal == pytest.approx(107.5)


def test_fetch_crypto_prices_maps_symbols_to_usd(monkeypatch):
    from ft import stock

    def fake_get(url, timeout=15):
        assert "api.coingecko.com/api/v3/simple/price" in url
        assert "vs_currencies=usd" in url
        assert "bitcoin" in url and "ethereum" in url
        return {"bitcoin": {"usd": 61000.0}, "ethereum": {"usd": 3000.0}}

    monkeypatch.setattr(stock, "_http_get_json", fake_get)
    prices = stock._fetch_crypto_prices(["btc", "eth"])
    assert prices == {"btc": pytest.approx(61000.0), "eth": pytest.approx(3000.0)}


def test_fetch_crypto_prices_unknown_symbol_ignored(monkeypatch):
    from ft import stock

    monkeypatch.setattr(stock, "_http_get_json",
                        lambda url, timeout=15: {"bitcoin": {"usd": 61000.0}})
    prices = stock._fetch_crypto_prices(["btc", "notacoin"])
    assert prices == {"btc": pytest.approx(61000.0)}


def test_fetch_crypto_prices_network_failure_returns_empty(monkeypatch):
    from ft import stock

    def boom(url, timeout=15):
        raise OSError("network down")

    monkeypatch.setattr(stock, "_http_get_json", boom)
    assert stock._fetch_crypto_prices(["btc"]) == {}


def test_fetch_crypto_prices_empty_input():
    from ft import stock
    assert stock._fetch_crypto_prices([]) == {}


def test_fetch_prices_routes_crypto_to_coingecko(monkeypatch):
    """crypto ticker 走 CoinGecko，股票 ticker 走 yfinance，互不串。"""
    from ft import stock

    called = {}

    def fake_crypto(tickers):
        called["crypto"] = list(tickers)
        return {"btc": 61000.0}

    monkeypatch.setattr(stock, "_fetch_crypto_prices", fake_crypto)

    def fake_download(tickers, period=None, progress=False, auto_adjust=False):
        assert "BTC" not in tickers  # crypto 不应流入 yfinance
        cols = pd.MultiIndex.from_tuples([("Close", "AAPL")])
        return pd.DataFrame([[195.0], [196.5]], columns=cols,
                            index=pd.Index(["2026-06-12", "2026-06-13"]))

    fake_yf = type("FakeYF", (), {"download": staticmethod(fake_download)})
    monkeypatch.setitem(sys.modules, "yfinance", fake_yf)
    monkeypatch.setattr(time, "sleep", lambda *_: None)

    prices = stock._fetch_prices(["btc", "aapl.us"])
    assert called["crypto"] == ["btc"]
    assert prices["btc"] == pytest.approx(61000.0)
    assert prices["aapl.us"] == pytest.approx(196.5)
