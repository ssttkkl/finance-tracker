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
    models.FT_DIR = d
    models.RECORDS_DIR = records_dir
    snapshot_mod.SNAPSHOT_PATH = d / "snapshot.yaml"

    yield d

    snapshot_mod.SNAPSHOT_PATH = old_snapshot_path
    models.FT_DIR = old_ft
    models.RECORDS_DIR = old_records
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
           commission=0.0, currency="USD", account_name="IBKR",
           date="2026-06-10")

    # Checkin: overwrite position
    do_checkin_ticker(ticker="nvda.us", shares=55, avg_cost=210.0,
                      currency="USD", account_name="IBKR",
                      note="checkin", date="2026-06-12")

    snap = load_snapshot()
    pos = snap["accounts"]["security"]["IBKR"]["positions"]["nvda.us"]
    assert pos["shares"] == 55
    assert pos["avg_cost"] == 210.0


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
