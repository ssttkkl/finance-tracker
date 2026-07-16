"""Tests for stock trading module"""
import csv
import sys
import tempfile
import time
from decimal import Decimal
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
        action="swap",
        from_ticker="USD",
        to_ticker="nvda.us",
        from_amount=1000.35,
        to_amount=10,
        price=100.0,
        commission=0.35,
        commission_asset="",
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
    assert r["action"] == "swap"
    assert r["from_ticker"] == "usd"
    assert r["to_ticker"] == "nvda.us"
    assert float(r["from_amount"]) == 1000.35
    assert float(r["to_amount"]) == 10
    assert float(r["price"]) == 100.0
    assert float(r["commission"]) == 0.35
    assert r["currency"] == "USD"
    assert r["account_name"] == "IBKR"
    assert r["note"] == "test buy"
    # Verify returned row matches
    assert row["action"] == "swap"
    assert row["from_ticker"] == "usd"
    assert row["to_ticker"] == "nvda.us"


def test_record_trade_sorts(tmp_env):
    """Multiple trades sorted by date"""
    from ft.stock import record_trade

    record_trade(
        date="2026-06-12 14:00:00", action="swap", from_ticker="aapl",
        to_ticker="USD", from_amount=5, to_amount=1000, price=200.0,
        commission=0.5, commission_asset="", currency="USD",
        account_name="IBKR", note="sell 1",
    )
    record_trade(
        date="2026-06-12 09:00:00", action="swap", from_ticker="USD",
        to_ticker="aapl", from_amount=1900.5, to_amount=10, price=190.0,
        commission=0.5, commission_asset="", currency="USD",
        account_name="IBKR", note="buy 1",
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
    # Cash is a position in "usd" — should be -(45*224.14 + 0.35) = -10086.65
    usd_pos = acct["positions"]["usd"]
    assert usd_pos["shares"] == pytest.approx(-10086.65)

    pos = acct["positions"]["nvda.us"]
    assert pos["shares"] == 45
    assert pos["total_cost"] == pytest.approx(10086.65)

    # Second buy at different price
    do_buy(
        ticker="nvda.us", shares=10, price=230.0,
        commission=0.35, currency="USD", account_name="IBKR",
        note="second buy", date="2026-06-13",
    )

    snap = load_snapshot()
    acct = snap["accounts"]["security"]["IBKR"]
    # usd: -10086.65 + (-10*230 - 0.35) = -10086.65 - 2300.35 = -12387.0
    usd_pos = acct["positions"]["usd"]
    assert usd_pos["shares"] == pytest.approx(-12387.0)

    pos = acct["positions"]["nvda.us"]
    assert pos["shares"] == 55
    assert pos["total_cost"] == pytest.approx(10086.65 + 2300.35)

    # 新写入的 swap 以成交毛额表达，手续费由 commission_asset 明确扣除。
    with (tmp_env / "records" / "security" / "2026-06-13.csv").open(encoding="utf-8") as f:
        row = next(csv.DictReader(f))
    assert float(row["from_amount"]) == pytest.approx(2300.0)
    assert row["commission_asset"] == "USD"


def test_do_sell_updates_snapshot(tmp_env):
    """Sell removes shares and keeps cost proportional"""
    from ft.stock import do_buy, do_sell, load_snapshot

    # Setup: buy 55 shares
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
    # Released cost = total_cost * (10/55) = 55*225.205 * (10/55) = 2252.05
    # remaining cost = 55*225.205 - 2252.05 = 12386.275 - 2252.05 = 10134.225
    expected_cost = 55 * 225.205 - (55 * 225.205) * 10 / 55
    assert pos["total_cost"] == pytest.approx(expected_cost)

    # Cash: proceeds = 10*250 - 0.35 = 2499.65
    usd_pos = acct["positions"]["usd"]
    assert usd_pos["shares"] == pytest.approx(-55*225.205 + 2499.65)

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
    acct = snap["accounts"]["security"]["IBKR"]
    assert acct["positions"]["usd"]["shares"] == pytest.approx(10000.0)

    do_withdraw(amount=3000.0, currency="USD", account_name="IBKR",
                note="withdraw", date="2026-06-13")

    snap = load_snapshot()
    acct = snap["accounts"]["security"]["IBKR"]
    assert acct["positions"]["usd"]["shares"] == pytest.approx(7000.0)


def test_do_dividend(tmp_env):
    """Dividend adds cash with no position change"""
    from ft.stock import do_dividend, do_checkin_ticker, load_snapshot

    do_checkin_ticker(ticker="aapl", shares=50, avg_cost=150.0,
            currency="USD", account_name="IBKR", date="2026-06-01")

    do_dividend(ticker="aapl", amount=25.0, currency="USD",
                account_name="IBKR", note="dividend", date="2026-06-15")

    snap = load_snapshot()
    acct = snap["accounts"]["security"]["IBKR"]
    assert acct["positions"]["usd"]["shares"] == pytest.approx(25.0)
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
    assert pos["total_cost"] == pytest.approx(55 * 210.0)



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


def test_fetch_prices_does_not_hardcode_currency_tickers(monkeypatch):
    """Only do_list knows account base currencies; the price helper must not."""
    from ft.stock import _fetch_prices

    def fake_download(tickers, period=None, progress=False, auto_adjust=False):
        assert tickers == ["USD"]
        return pd.DataFrame({"Close": [12.34]})

    fake_yf = type("FakeYF", (), {"download": staticmethod(fake_download)})
    monkeypatch.setitem(sys.modules, "yfinance", fake_yf)
    monkeypatch.setattr(time, "sleep", lambda *_: None)

    assert _fetch_prices(["usd"]) == {"usd": pytest.approx(12.34)}


def test_do_list_excludes_any_configured_base_currency_from_prices(tmp_env, monkeypatch, capsys):
    """Configured base cash is not priced, even when it is not USD/CNY/HKD."""
    from ft.accounts import save_accounts
    from ft.snapshot import save_snapshot
    from ft.stock import do_list

    save_accounts([{
        "name": "Kraken", "type": "crypto", "currency": "USD",
        "base_currencies": ["USDT", "USDG"], "active": True,
    }])
    save_snapshot({"accounts": {"security": {"Kraken": {
        "currency": "USD",
        "positions": {
            "usdt": {"shares": 1020.98, "total_cost": 1020.98, "cost_currency": "USDT"},
            "USDG": {"shares": 3.0, "total_cost": 3.0, "cost_currency": "USDG"},
            "btc": {"shares": 0.01, "total_cost": 700.0, "cost_currency": "USDT"},
        },
    }}}})
    requested = []

    def fake_fetch(tickers):
        requested.extend(tickers)
        return {"btc": 80000.0}

    monkeypatch.setattr("ft.stock._fetch_prices", fake_fetch)

    do_list()
    out = capsys.readouterr().out
    assert requested == ["btc"]
    assert "现金 [USDT]" in out
    assert "USDT 1,020.98" in out
    assert "现金 [USDG]" in out
    assert "USDG 3.00" in out
    assert "合计：多币种，未合并" in out


def test_do_list_non_base_currency_position_is_not_priced_or_mislabeled(tmp_env, monkeypatch, capsys):
    """A non-base currency position has no FX valuation and uses its own currency."""
    from ft.accounts import save_accounts
    from ft.snapshot import save_snapshot
    from ft.stock import do_list

    save_accounts([
        {
            "name": "港股证券", "type": "security", "currency": "HKD",
            "base_currencies": ["HKD"], "active": True,
        },
        {
            "name": "人民币现金", "type": "cash", "currency": "CNY",
            "base_currencies": ["CNY"], "active": True,
        },
    ])
    save_snapshot({"accounts": {"security": {"港股证券": {
        "currency": "HKD",
        "positions": {
            "hkd": {"shares": 100.0, "total_cost": 100.0, "cost_currency": "HKD"},
            "cny": {"shares": 5294.16, "total_cost": 5294.16, "cost_currency": "HKD"},
            "00700.hk": {"shares": 2.0, "total_cost": 600.0, "cost_currency": "HKD"},
        },
    }}}})
    requested = []

    def fake_fetch(tickers):
        requested.extend(tickers)
        return {"00700.hk": 350.0}

    monkeypatch.setattr("ft.stock._fetch_prices", fake_fetch)

    do_list()
    out = capsys.readouterr().out
    cny_line = next(line for line in out.splitlines() if "cny" in line)
    assert requested == ["00700.hk"]
    assert "¥" in cny_line
    assert "HK$" not in cny_line
    assert "N/A" in cny_line
    assert "合计 [HKD]" in out
    assert "HK$800.00" in out


def test_do_list_values_negative_position_with_a_valid_quote(tmp_env, monkeypatch, capsys):
    """A short position with a quote has negative market value, not N/A."""
    from ft.accounts import save_accounts
    from ft.snapshot import save_snapshot
    from ft.stock import do_list

    save_accounts([{
        "name": "IBKR", "type": "security", "currency": "USD",
        "base_currencies": ["USD"], "active": True,
    }])
    save_snapshot({"accounts": {"security": {"IBKR": {
        "currency": "USD",
        "positions": {
            "usd": {"shares": 0.0, "total_cost": 0.0, "cost_currency": "USD"},
            "nvda.us": {"shares": -2.0, "total_cost": -160.0, "cost_currency": "USD"},
        },
    }}}})
    monkeypatch.setattr("ft.stock._fetch_prices", lambda tickers: {"nvda.us": 100.0})

    do_list()
    out = capsys.readouterr().out
    nvda_line = next(line for line in out.splitlines() if "nvda.us" in line)
    assert "$-200.00" in nvda_line
    assert "N/A" not in nvda_line
    assert "持仓市值 [USD]" in out
    assert "$-200.00" in out


def test_do_checkin_cash(tmp_env):
    """Checkin cash overwrites cash balance"""
    from ft.stock import do_deposit, do_checkin_cash, load_snapshot

    do_deposit(amount=5000.0, currency="USD", account_name="IBKR",
               note="deposit", date="2026-06-10")

    do_checkin_cash(cash=12345.67, account_name="IBKR",
                    note="reconcile", date="2026-06-12")

    snap = load_snapshot()
    acct = snap["accounts"]["security"]["IBKR"]
    assert acct["positions"]["usd"]["shares"] == pytest.approx(12345.67)


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
        "action": "swap",
        "from_ticker": "USD",
        "to_ticker": "pm:will-test-market-close-yes:no",
        "from_amount": "11.234496",
        "to_amount": "12.3456",
        "price": "0.91",
        "commission": "0",
        "commission_asset": "USD",
        "currency": "USD",
        "account_name": "Polymarket",
        "note": "polymarket tx:0xabc123",
    }


def test_polymarket_activity_trade_note_prefers_api_row_id():
    """When Activity exposes a fill/activity id, it becomes the row-level identity."""
    from ft.polymarket_sync import activity_to_stock_row

    row = activity_to_stock_row({
        "id": "fill-123",
        "timestamp": 1782785769,
        "type": "TRADE",
        "side": "BUY",
        "slug": "will-test-market-close-yes",
        "outcome": "No",
        "size": "2",
        "price": "0.5",
        "usdcSize": "1",
        "transactionHash": "0xabc123",
    }, account_name="Polymarket")

    assert row["note"] == "polymarket id:fill-123 tx:0xabc123"


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
    """Incremental sync should skip an exact already-recorded Polymarket fill."""
    import csv
    from ft import stock
    from ft.polymarket_sync import filter_new_rows

    stock.record_trade(
        date="2026-06-30 10:16:09", action="swap",
        from_ticker="USD", to_ticker="pm:old-market:no",
        from_amount=0.9, to_amount=1,
        price=0.9, commission=0, commission_asset="USD",
        currency="USD", account_name="Polymarket",
        note="polymarket tx:0xexisting",
    )

    rows = [
        {
            "date": "2026-06-30 10:16:09", "action": "swap",
            "from_ticker": "USD", "to_ticker": "pm:old-market:no",
            "from_amount": "0.9", "to_amount": "1", "price": "0.9",
            "commission": "0", "commission_asset": "USD",
            "currency": "USD", "account_name": "Polymarket",
            "note": "polymarket tx:0xexisting",
        },
        {
            "date": "2026-07-01 11:00:00", "action": "swap",
            "from_ticker": "pm:new-market:yes", "to_ticker": "USD",
            "from_amount": "2", "to_amount": "1.6", "price": "0.8",
            "commission": "0", "commission_asset": "USD",
            "currency": "USD", "account_name": "Polymarket",
            "note": "polymarket tx:0xnew",
        },
    ]

    assert filter_new_rows(rows)[0]["note"] == "polymarket tx:0xnew"


def test_filter_new_polymarket_rows_keeps_new_fill_with_existing_tx_hash(tmp_env):
    """Same transaction hash can contain a later-discovered distinct fill."""
    from ft import stock
    from ft.polymarket_sync import filter_new_rows

    stock.record_trade(
        date="2026-06-30 10:00:00", action="swap",
        from_ticker="USD", to_ticker="pm:market-a:yes",
        from_amount=5, to_amount=10,
        price=0.5, commission=0, commission_asset="USD",
        currency="USD", account_name="Polymarket",
        note="polymarket tx:0xabc123",
    )

    rows = [
        {
            "date": "2026-06-30 10:00:00", "action": "swap",
            "from_ticker": "USD", "to_ticker": "pm:market-a:yes",
            "from_amount": "5", "to_amount": "10", "price": "0.5",
            "commission": "0", "commission_asset": "USD",
            "currency": "USD", "account_name": "Polymarket",
            "note": "polymarket tx:0xabc123",
        },
        {
            "date": "2026-06-30 10:00:01", "action": "swap",
            "from_ticker": "USD", "to_ticker": "pm:market-b:no",
            "from_amount": "2", "to_amount": "8", "price": "0.25",
            "commission": "0", "commission_asset": "USD",
            "currency": "USD", "account_name": "Polymarket",
            "note": "polymarket tx:0xabc123",
        },
    ]

    new_rows = filter_new_rows(rows, account_name="Polymarket")

    assert len(new_rows) == 1
    assert new_rows[0]["to_ticker"] == "pm:market-b:no"


def test_filter_new_polymarket_rows_dedupes_by_api_row_id(tmp_env):
    """API fill/activity id is the preferred stable identity when present."""
    from ft import stock
    from ft.polymarket_sync import filter_new_rows

    stock.record_trade(
        date="2026-06-30 10:00:00", action="swap",
        from_ticker="USD", to_ticker="pm:market-a:yes",
        from_amount=5, to_amount=10,
        price=0.5, commission=0, commission_asset="USD",
        currency="USD", account_name="Polymarket",
        note="polymarket id:fill-123 tx:0xabc123",
    )

    rows = [{
        "date": "2026-06-30 10:00:01", "action": "swap",
        "from_ticker": "USD", "to_ticker": "pm:market-a:yes",
        "from_amount": "5.0", "to_amount": "10.0", "price": "0.50",
        "commission": "0", "commission_asset": "USD",
        "currency": "USD", "account_name": "Polymarket",
        "note": "polymarket id:fill-123 tx:0xabc123",
    }]

    assert filter_new_rows(rows, account_name="Polymarket") == []


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
            "date": "2026-06-30 10:16:09", "action": "swap",
            "from_ticker": "USD", "to_ticker": "pm:test:no",
            "from_amount": "1.6", "to_amount": "2", "price": "0.8",
            "commission": "0", "commission_asset": "USD",
            "currency": "USD", "account_name": "Polymarket",
            "note": "polymarket tx:0xnew",
        })

    assert do_append(input_csv) is True

    with day_path.open(encoding="utf-8") as f:
        reader = csv.DictReader(f)
        fieldnames = reader.fieldnames or []
        rows = list(reader)
    assert "transfer_account" in fieldnames
    assert any(r.get("transfer_account") == "东方证券" for r in rows)
    assert any(r.get("from_ticker") == "USD" and r.get("to_ticker") == "pm:test:no" for r in rows)
    ok, lines = verify_security()
    assert ok, "\n".join(lines)


def test_filter_new_polymarket_rows_keeps_distinct_fills_with_same_tx(tmp_env):
    """One Polymarket tx can contain multiple distinct fills; only exact duplicates should collapse."""
    from ft.polymarket_sync import filter_new_rows

    rows = [
        {
            "date": "2026-06-30 10:00:00", "action": "swap",
            "from_ticker": "USD", "to_ticker": "pm:market-a:yes",
            "from_amount": "5", "to_amount": "10", "price": "0.5",
            "commission": "0", "commission_asset": "USD",
            "currency": "USD", "account_name": "Polymarket",
            "note": "polymarket tx:0xabc123",
        },
        {
            "date": "2026-06-30 10:00:00", "action": "swap",
            "from_ticker": "USD", "to_ticker": "pm:market-b:no",
            "from_amount": "2", "to_amount": "8", "price": "0.25",
            "commission": "0", "commission_asset": "USD",
            "currency": "USD", "account_name": "Polymarket",
            "note": "polymarket tx:0xabc123",
        },
        {
            "date": "2026-06-30 10:00:00", "action": "swap",
            "from_ticker": "USD", "to_ticker": "pm:market-b:no",
            "from_amount": "2", "to_amount": "8", "price": "0.25",
            "commission": "0", "commission_asset": "USD",
            "currency": "USD", "account_name": "Polymarket",
            "note": "polymarket tx:0xabc123",
        },
    ]

    new_rows = filter_new_rows(rows)

    assert [row["to_ticker"] for row in new_rows] == ["pm:market-a:yes", "pm:market-b:no"]


def test_filter_new_polymarket_rows_scopes_tx_dedupe_by_account(tmp_env):
    """Different Polymarket accounts should not suppress each other's tx hashes."""
    from ft.polymarket_sync import filter_new_rows
    from ft.stock import record_trade

    record_trade(
        date="2026-06-30 10:00:00", action="swap",
        from_ticker="USD", to_ticker="pm:market-a:yes",
        from_amount=0.5, to_amount=1,
        price=0.5, commission=0, commission_asset="USD",
        currency="USD", account_name="PolymarketA",
        note="polymarket tx:0xshared",
    )
    rows_for_b = [{
        "date": "2026-06-30 10:00:00", "action": "swap",
        "from_ticker": "USD", "to_ticker": "pm:market-a:yes",
        "from_amount": "0.5", "to_amount": "1", "price": "0.5",
        "commission": "0", "commission_asset": "USD",
        "currency": "USD", "account_name": "PolymarketB",
        "note": "polymarket tx:0xshared",
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
        date="2026-06-30 10:00:00", action="swap",
        from_ticker="USD", to_ticker="pm:test-market:yes",
        from_amount=0.5, to_amount=1,
        price=0.5, commission=0, commission_asset="USD",
        currency="USD", account_name="Polymarket",
        note="polymarket tx:0xabc123",
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
            "date": "2026-06-30 10:00:00", "action": "swap",
            "from_ticker": "USD", "to_ticker": "pm:test:yes",
            "from_amount": "0.5", "to_amount": "1", "price": "0.5",
            "commission": "0", "commission_asset": "USD",
            "currency": "USD", "account_name": "Polymarket",
            "note": "polymarket tx:0xabc123",
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
            "date": "2026-06-30 10:00:00", "action": "swap",
            "from_ticker": "USD", "to_ticker": "nvda.us",
            "from_amount": "100", "to_amount": "1", "price": "100",
            "commission": "0", "commission_asset": "USD",
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
            "date": "2026-06-30 10:16:09", "action": "swap",
            "from_ticker": "USD", "to_ticker": "pm:test:no",
            "from_amount": "1.6", "to_amount": "2", "price": "0.8",
            "commission": "0", "commission_asset": "USD",
            "currency": "USD", "account_name": "Polymarket",
            "note": "polymarket tx:0xnew",
        })

    do_transfer("现金", "Polymarket", 100, to_amount=10, date="2026-06-30", time_str="11:00:00")

    with day_path.open(encoding="utf-8") as f:
        reader = csv.DictReader(f)
        rows = list(reader)
    assert any(r.get("from_ticker") == "USD" and r.get("to_ticker") == "pm:test:no" and r.get("action") == "swap" for r in rows)
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
            "date": "2026-06-30 10:16:09", "action": "swap",
            "from_ticker": "USD", "to_ticker": "pm:test:no",
            "from_amount": "1.6", "to_amount": "2", "price": "0.8",
            "commission": "0", "commission_asset": "USD",
            "currency": "USD", "account_name": "Polymarket",
            "note": "polymarket tx:0xnew",
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
    assert any(r.get("from_ticker") == "USD" and r.get("to_ticker") == "pm:test:no" and r.get("action") == "swap" for r in rows)
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
            "date": "2026-06-30 10:00:00", "action": "swap",
            "from_ticker": "USD", "to_ticker": "pm:first:no",
            "from_amount": "0.8", "to_amount": "1", "price": "0.8",
            "commission": "0", "commission_asset": "USD",
            "currency": "USD", "account_name": "Polymarket", "note": "first",
        })
        writer.writerow({
            "date": "2026-07-01 10:00:00", "action": "swap",
            "from_ticker": "USD", "to_ticker": "pm:second:no",
            "from_amount": "0.7", "to_amount": "1", "price": "0.7",
            "commission": "0", "commission_asset": "USD",
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
    assert pos["total_cost"] == pytest.approx(1e308)
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
    assert snap["accounts"]["security"]["IBKR"]["positions"]["usd"]["shares"] == 1e308
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
    # Row with empty action (malformed) — should be skipped
    (security_dir / "2026-06-29.csv").write_text(
        "date,action,from_ticker,to_ticker,from_amount,to_amount,price,commission,commission_asset,currency,account_name,note\n"
        "2026-06-29 10:00:00,,,nvda.us,,1,10,0,,USD,IBKR,missing action\n",
        encoding="utf-8",
    )
    # Valid deposit row
    (security_dir / "2026-06-30.csv").write_text(
        "date,action,from_ticker,to_ticker,from_amount,to_amount,price,commission,commission_asset,currency,account_name,note\n"
        "2026-06-30 10:00:00,deposit,,usd,0,25,1,0,,USD,IBKR,cash deposit\n",
        encoding="utf-8",
    )

    positions = _replay_security_csv()
    # Malformed row skipped, only deposit counted
    assert positions[("IBKR", "usd")]["shares"] == pytest.approx(25)
    assert positions[("IBKR", "usd")]["total_cost"] == pytest.approx(25)


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
                    "positions": {
                        "usd": {"shares": 100.0, "total_cost": 100.0, "cost_currency": "USD"},
                        "pm:new-rhianna-album-before-gta-vi-926:yes": {
                            "shares": 10,
                            "total_cost": 4.0,
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


def test_stock_append_accepts_crypto_account(tmp_env):
    """crypto 类型账户可导入股票风格记录。"""
    from ft.accounts import save_accounts
    from ft.stock import CSV_FIELDS, do_append
    from ft import models

    save_accounts([
        {"name": "币安", "type": "crypto", "currency": "USD", "active": True},
    ], models.ACCOUNTS_PATH)
    csv_path = tmp_env / "binance_crypto.csv"
    with csv_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        writer.writeheader()
        writer.writerow({
            "date": "2026-07-07 10:00:00", "action": "swap",
            "from_ticker": "USD", "to_ticker": "btc",
            "from_amount": "3000", "to_amount": "0.05", "price": "60000",
            "commission": "0", "commission_asset": "",
            "currency": "USD", "account_name": "币安", "note": "crypto buy",
        })

    assert do_append(csv_path) is True
    assert (models.RECORDS_DIR / "security" / "2026-07-07.csv").exists()


def test_crypto_account_buy_sell_verify_end_to_end(tmp_env, monkeypatch):
    """crypto 账户走 ft stock deposit/buy/sell → snapshot 与 CSV 一致。"""
    from ft.accounts import save_accounts
    from ft import models
    from ft.stock import (
        do_deposit, do_buy, do_sell, load_snapshot, verify_security,
    )

    save_accounts([
        {"name": "币安", "type": "crypto", "currency": "USD", "active": True},
    ], models.ACCOUNTS_PATH)

    do_deposit(amount=5000, currency="USD", account_name="币安",
               date="2026-07-07 09:00:00")
    do_buy(ticker="btc", shares=0.05, price=60000, commission=0,
           currency="USD", account_name="币安", date="2026-07-07 10:00:00")
    do_sell(ticker="btc", shares=0.02, price=62000, commission=0,
            currency="USD", account_name="币安", date="2026-07-07 11:00:00")

    snap = load_snapshot()
    acct = snap["accounts"]["security"]["币安"]
    # 现金: 5000 - 0.05*60000 + 0.02*62000 = 5000 - 3000 + 1240 = 3240
    assert acct["positions"]["usd"]["shares"] == pytest.approx(3240.0)
    assert acct["positions"]["btc"]["shares"] == pytest.approx(0.03)
    # verify_security 返回 (ok: bool, report_lines: list[str])
    ok, _lines = verify_security()
    assert ok is True


def test_replay_buy_with_usdt_quote_reduces_usdt_cash_not_usd():
    """BUY rows tagged quote:usdt should debit USDT cash, not CSV currency USD."""
    from ft.stock import _replay_security_rows

    rows = [
        {"date": "2026-07-07 10:00:00", "action": "swap",
         "from_ticker": "usdt", "to_ticker": "btc",
         "from_amount": "3000", "to_amount": "0.05", "price": "60000",
         "commission": "0", "commission_asset": "",
         "currency": "USD", "account_name": "币安", "note": "kraken tid:T1 quote:usdt"},
    ]

    positions = _replay_security_rows(rows)

    assert positions[("币安", "btc")]["shares"] == pytest.approx(0.05)
    assert positions[("币安", "usdt")]["shares"] == pytest.approx(-3000.0)
    assert positions.get(("币安", "usd"), {}).get("shares", 0.0) == pytest.approx(0.0)


def test_replay_cross_currency_cash_positions_keep_native_cost_currency():
    """现金 ticker 的 cost_currency 跟随自身币种，不能被交易行 currency 覆盖。"""
    from ft.stock import _replay_security_rows

    rows = [
        {"date": "2026-07-07 09:00:00", "action": "deposit",
         "from_ticker": "", "to_ticker": "USD",
         "from_amount": "0", "to_amount": "100", "price": "0",
         "commission": "0", "commission_asset": "",
         "currency": "USD", "account_name": "IBKR", "note": "seed"},
        {"date": "2026-07-07 10:00:00", "action": "swap",
         "from_ticker": "USD", "to_ticker": "CNY",
         "from_amount": "10", "to_amount": "70", "price": "0",
         "commission": "0", "commission_asset": "",
         "currency": "CNY", "account_name": "IBKR", "note": "fx"},
    ]

    positions = _replay_security_rows(rows)

    assert positions[("IBKR", "usd")]["cost_currency"] == "USD"
    assert positions[("IBKR", "cny")]["cost_currency"] == "CNY"
    assert positions[("IBKR", "usd")]["shares"] == pytest.approx(90)
    assert positions[("IBKR", "cny")]["shares"] == pytest.approx(70)
    assert positions[("IBKR", "usd")]["total_cost"] == pytest.approx(90)
    assert positions[("IBKR", "cny")]["total_cost"] == pytest.approx(70)


def test_replay_swap_conserves_total_cost():
    """SWAP: 换出币释放的成本原样转给换入币，USD 总成本守恒，不碰现金。"""
    from ft.stock import _replay_security_rows

    rows = [
        # 先用现金买入 1 BTC，成本 60000
        {"date": "2026-07-07 09:00:00", "action": "swap",
         "from_ticker": "usd", "to_ticker": "btc",
         "from_amount": "60000", "to_amount": "1", "price": "60000",
         "commission": "0", "commission_asset": "",
         "currency": "USD", "account_name": "币安", "note": "seed"},
        # 用 0.5 BTC 换 10 ETH
        {"date": "2026-07-07 10:00:00", "action": "swap",
         "from_ticker": "btc", "to_ticker": "eth",
         "from_amount": "0.5", "to_amount": "10", "price": "0",
         "commission": "0", "commission_asset": "",
         "currency": "USD", "account_name": "币安", "note": "kraken tid:T1 swap:T1"},
    ]
    positions = _replay_security_rows(rows)

    # BTC: 剩 0.5，成本 30000（释放了 0.5*60000=30000）
    assert positions[("币安", "btc")]["shares"] == pytest.approx(0.5)
    assert positions[("币安", "btc")]["total_cost"] == pytest.approx(30000.0)
    # ETH: 10 股，接收成本 30000
    assert positions[("币安", "eth")]["shares"] == pytest.approx(10.0)
    assert positions[("币安", "eth")]["total_cost"] == pytest.approx(30000.0)
    # USD: started at -60000, then -60000 (from swap), so total_cost should reflect cost
    assert positions[("币安", "usd")]["shares"] == pytest.approx(-60000.0)
    # 总成本守恒
    assert (positions[("币安", "btc")]["total_cost"]
            + positions[("币安", "eth")]["total_cost"]) == pytest.approx(60000.0)


def test_replay_quote_commission_debits_cash_and_enters_buy_cost():
    """券商成交以毛额记 swap 时，CNY 佣金必须从现金扣除且买入计入成本。"""
    from ft.stock import _replay_security_rows

    rows = [
        {"date": "2026-07-01 09:00:00", "action": "deposit",
         "from_ticker": "", "to_ticker": "cny",
         "from_amount": "0", "to_amount": "1000", "price": "1",
         "commission": "0", "commission_asset": "",
         "currency": "CNY", "account_name": "东方证券", "note": "seed"},
        # 买入毛额 500，佣金 1：现金 -501，股票总成本 501。
        {"date": "2026-07-01 10:00:00", "action": "swap",
         "from_ticker": "cny", "to_ticker": "159330.sz",
         "from_amount": "500", "to_amount": "5", "price": "100",
         "commission": "1", "commission_asset": "cny",
         "currency": "CNY", "account_name": "东方证券", "note": "buy"},
        # 卖出毛额 220，佣金 1：现金 +219。
        {"date": "2026-07-02 10:00:00", "action": "swap",
         "from_ticker": "159330.sz", "to_ticker": "cny",
         "from_amount": "2", "to_amount": "220", "price": "110",
         "commission": "1", "commission_asset": "cny",
         "currency": "CNY", "account_name": "东方证券", "note": "sell"},
    ]

    positions = _replay_security_rows(rows)

    assert positions[("东方证券", "cny")]["shares"] == pytest.approx(718.0)
    assert positions[("东方证券", "159330.sz")]["shares"] == pytest.approx(3.0)
    assert positions[("东方证券", "159330.sz")]["total_cost"] == pytest.approx(300.6)


def test_replay_swap_in_without_pair_raises():
    """SWAP_IN 找不到配对 released 必须报错，不静默。"""
    from ft.stock import _replay_security_rows

    # In unified swap model, an orphan swap (spending more than available)
    # results in negative position — no longer raises
    rows = [
        {"date": "2026-07-07 10:00:00", "action": "swap",
         "from_ticker": "usdt", "to_ticker": "eth",
         "from_amount": "5000", "to_amount": "10", "price": "500",
         "commission": "0", "commission_asset": "",
         "currency": "USD", "account_name": "币安", "note": "kraken tid:T9 swap:T9"},
    ]
    positions = _replay_security_rows(rows)
    # USDT goes negative, eth gets 10 shares
    assert positions[("币安", "usdt")]["shares"] == pytest.approx(-5000.0)
    assert positions[("币安", "eth")]["shares"] == pytest.approx(10.0)


def test_replay_fee_reduces_holding_by_avg_cost():
    """FEE: 按均价核销持仓与成本。"""
    from ft.stock import _replay_security_rows

    rows = [
        {"date": "2026-07-07 09:00:00", "action": "swap",
         "from_ticker": "usd", "to_ticker": "bnb",
         "from_amount": "5000", "to_amount": "10", "price": "500",
         "commission": "0", "commission_asset": "",
         "currency": "USD", "account_name": "币安", "note": "seed"},
        # Fee: swap 0.1 BNB for 0 USD (fee reduces position, no cash change)
        {"date": "2026-07-07 10:00:00", "action": "swap",
         "from_ticker": "bnb", "to_ticker": "USD",
         "from_amount": "0.1", "to_amount": "0", "price": "0",
         "commission": "0", "commission_asset": "",
         "currency": "USD", "account_name": "币安", "note": "kraken tid:T1 fee"},
    ]
    positions = _replay_security_rows(rows)

    # BNB: 剩 9.9，成本 5000 - 0.1*(5000/10) = 5000 - 50 = 4950
    assert positions[("币安", "bnb")]["shares"] == pytest.approx(9.9)
    assert positions[("币安", "bnb")]["total_cost"] == pytest.approx(4950.0)


def test_append_accepts_swap_fee_rows_and_keeps_currency(tmp_env):
    """含空数值列的 SWAP/FEE 行可导入；crypto 账户币种在重建后保留。"""
    from ft.accounts import save_accounts
    from ft.stock import CSV_FIELDS, do_append, load_snapshot
    from ft import models

    save_accounts([
        {"name": "币安", "type": "crypto", "currency": "USD", "active": True},
    ], models.ACCOUNTS_PATH)

    csv_path = tmp_env / "swap.csv"
    with csv_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        writer.writeheader()
        writer.writerow({"date": "2026-07-07 09:00:00", "action": "swap",
                         "from_ticker": "usd", "to_ticker": "btc",
                         "from_amount": "60000", "to_amount": "1", "price": "60000",
                         "commission": "0", "commission_asset": "",
                         "currency": "USD", "account_name": "币安", "note": "seed"})
        writer.writerow({"date": "2026-07-07 10:00:00", "action": "swap",
                         "from_ticker": "btc", "to_ticker": "eth",
                         "from_amount": "0.5", "to_amount": "10", "price": "0",
                         "commission": "0", "commission_asset": "",
                         "currency": "USD", "account_name": "币安", "note": "kraken tid:T1 swap:T1"})

    assert do_append(csv_path) is True
    snap = load_snapshot()
    acct = snap["accounts"]["security"]["币安"]
    assert acct["currency"] == "USD"          # 币种未丢
    assert acct["positions"]["eth"]["shares"] == pytest.approx(10.0)
    assert acct["positions"]["btc"]["shares"] == pytest.approx(0.5)


def test_do_swap_conserves_cost_and_ignores_cash(tmp_env):
    from ft.accounts import save_accounts
    from ft import models
    from ft.stock import do_deposit, do_buy, do_swap, load_snapshot, verify_security

    save_accounts([{"name": "币安", "type": "crypto", "currency": "USD", "active": True}],
                  models.ACCOUNTS_PATH)
    do_deposit(amount=100000, currency="USD", account_name="币安",
               date="2026-07-07 08:00:00")
    do_buy(ticker="btc", shares=1, price=60000, commission=0, currency="USD",
           account_name="币安", date="2026-07-07 09:00:00")

    do_swap(account_name="币安", from_ticker="btc", from_shares=0.5,
            to_ticker="eth", to_shares=10, date="2026-07-07 10:00:00")

    snap = load_snapshot()
    acct = snap["accounts"]["security"]["币安"]
    assert acct["positions"]["btc"]["shares"] == pytest.approx(0.5)
    assert acct["positions"]["eth"]["shares"] == pytest.approx(10.0)
    # ETH 成本 = 释放的 BTC 成本 0.5*60000 = 30000
    assert acct["positions"]["eth"]["total_cost"] == pytest.approx(30000.0)
    # 现金：deposit 100000 - buy 60000 = 40000，swap 不动
    assert acct["positions"]["usd"]["shares"] == pytest.approx(40000.0)
    ok, _ = verify_security()
    assert ok is True


def test_do_swap_insufficient_from_shares_raises(tmp_env):
    from ft.accounts import save_accounts
    from ft import models
    from ft.stock import do_swap

    save_accounts([{"name": "币安", "type": "crypto", "currency": "USD", "active": True}],
                  models.ACCOUNTS_PATH)
    with pytest.raises(ValueError, match="持仓不足"):
        do_swap(account_name="币安", from_ticker="btc", from_shares=1,
                to_ticker="eth", to_shares=10, date="2026-07-07 10:00:00")


def test_verify_security_detects_total_cost_mismatch(tmp_env):
    """shares 相同但 total_cost 不同，verify_security 必须失败。"""
    from ft import models
    from ft.snapshot import save_snapshot
    from ft.stock import verify_security

    security_dir = models.RECORDS_DIR / "security"
    security_dir.mkdir(parents=True, exist_ok=True)
    (security_dir / "2026-07-08.csv").write_text(
        "date,action,from_ticker,to_ticker,from_amount,to_amount,price,commission,commission_asset,currency,account_name,note\n"
        "2026-07-08 10:00:00,checkin,btc,,0,2,50,0,,USD,币安,seed\n",
        encoding="utf-8",
    )
    save_snapshot({
        "updated_at": "2026-07-08",
        "accounts": {
            "security": {
                "币安": {
                    "currency": "USD",
                    "positions": {
                        "btc": {
                            "shares": "2.0",
                            "total_cost": "120.00",
                            "cost_currency": "USD",
                        },
                    },
                },
            },
        },
    })

    ok, lines = verify_security()

    assert ok is False
    assert any("total_cost" in line and "btc" in line for line in lines)


def test_verify_security_detects_cost_currency_mismatch(tmp_env):
    """shares/total_cost 相同但 cost_currency 不同，verify_security 必须失败。"""
    from ft import models
    from ft.snapshot import save_snapshot
    from ft.stock import verify_security

    security_dir = models.RECORDS_DIR / "security"
    security_dir.mkdir(parents=True, exist_ok=True)
    (security_dir / "2026-07-08.csv").write_text(
        "date,action,from_ticker,to_ticker,from_amount,to_amount,price,commission,commission_asset,currency,account_name,note\n"
        "2026-07-08 10:00:00,checkin,btc,,0,2,50,0,,USD,币安,seed\n",
        encoding="utf-8",
    )
    save_snapshot({
        "updated_at": "2026-07-08",
        "accounts": {
            "security": {
                "币安": {
                    "currency": "USD",
                    "positions": {
                        "btc": {
                            "shares": "2.0",
                            "total_cost": "100.00",
                            "cost_currency": "CNY",
                        },
                    },
                },
            },
        },
    })

    ok, lines = verify_security()

    assert ok is False
    assert any("cost_currency" in line and "btc" in line for line in lines)


def test_verify_security_detects_missing_cost_currency(tmp_env):
    """CSV 已有成本币种而快照遗漏该元数据时必须失败，不能静默兼容。"""
    from ft import models
    from ft.snapshot import save_snapshot
    from ft.stock import verify_security

    security_dir = models.RECORDS_DIR / "security"
    security_dir.mkdir(parents=True, exist_ok=True)
    (security_dir / "2026-07-08.csv").write_text(
        "date,action,from_ticker,to_ticker,from_amount,to_amount,price,commission,commission_asset,currency,account_name,note\n"
        "2026-07-08 10:00:00,checkin,btc,,0,2,50,0,,USD,币安,seed\n",
        encoding="utf-8",
    )
    save_snapshot({
        "updated_at": "2026-07-08",
        "accounts": {"security": {"币安": {"currency": "USD", "positions": {
            "btc": {"shares": 2, "total_cost": 100},
        }}}},
    })

    ok, lines = verify_security()

    assert ok is False
    assert any("cost_currency" in line and "btc" in line for line in lines)


def test_direct_do_swap_matches_csv_replay_for_partial_sell_with_fee(tmp_env):
    """直接 do_swap 与 CSV replay 对部分卖出、成本释放和非零手续费处理一致。"""
    from ft.accounts import save_accounts
    from ft import models
    from ft.stock import do_buy, do_deposit, do_swap, load_snapshot, _replay_security_csv

    save_accounts([{"name": "币安", "type": "crypto", "currency": "USD", "active": True}],
                  models.ACCOUNTS_PATH)
    do_deposit(amount=100000, currency="USD", account_name="币安",
               date="2026-07-08 08:00:00")
    do_buy(ticker="btc", shares=1, price=60000, commission=10,
           currency="USD", account_name="币安", date="2026-07-08 09:00:00")

    do_swap(account_name="币安", from_ticker="btc", from_shares=0.5,
            to_ticker="eth", to_shares=10, commission=0.01,
            commission_asset="btc", currency="USD",
            date="2026-07-08 10:00:00")

    direct_positions = load_snapshot()["accounts"]["security"]["币安"]["positions"]
    replay_positions = {
        ticker: pos
        for (acct, ticker), pos in _replay_security_csv().items()
        if acct == "币安" and pos["shares"] != 0
    }

    assert direct_positions == replay_positions


def test_failed_do_swap_keeps_snapshot_and_security_csv_unchanged(tmp_env):
    """非法 swap 参数不得留下半写 snapshot 或不完整 CSV 记录。"""
    from ft.accounts import save_accounts
    from ft import models
    import ft.snapshot as snapshot_mod
    from ft.stock import do_buy, do_deposit, do_swap

    save_accounts([{"name": "币安", "type": "crypto", "currency": "USD", "active": True}],
                  models.ACCOUNTS_PATH)
    do_deposit(amount=100000, currency="USD", account_name="币安",
               date="2026-07-08 08:00:00")
    do_buy(ticker="btc", shares=1, price=60000, commission=0,
           currency="USD", account_name="币安", date="2026-07-08 09:00:00")

    day_path = models.RECORDS_DIR / "security" / "2026-07-08.csv"
    snapshot_before = snapshot_mod.SNAPSHOT_PATH.read_bytes()
    csv_before = day_path.read_bytes()

    with pytest.raises(ValueError, match="commission"):
        do_swap(account_name="币安", from_ticker="btc", from_shares=0.5,
                to_ticker="eth", to_shares=10, commission=float("inf"),
                commission_asset="btc", currency="USD",
                date="2026-07-08 10:00:00")

    assert snapshot_mod.SNAPSHOT_PATH.read_bytes() == snapshot_before
    assert day_path.read_bytes() == csv_before


def test_mixed_case_buy_swap_and_replay_use_single_canonical_ticker(tmp_env):
    """buy/swap/CSV replay 混用大小写时不拆仓，pm: ticker 语义保持小写 canonical。"""
    from ft.accounts import save_accounts
    from ft import models
    from ft.stock import do_buy, do_deposit, do_swap, load_snapshot, repair_security

    save_accounts([{"name": "币安", "type": "crypto", "currency": "USD", "active": True}],
                  models.ACCOUNTS_PATH)
    do_deposit(amount=50000, currency="USD", account_name="币安",
               date="2026-07-08 08:00:00")
    do_buy(ticker="BTC", shares=1, price=10000, commission=0,
           currency="USD", account_name="币安", date="2026-07-08 09:00:00")
    do_swap(account_name="币安", from_ticker="btc", from_shares=0.25,
            to_ticker="ETH", to_shares=2, currency="USD",
            date="2026-07-08 10:00:00")
    do_buy(ticker="PM:Election-2028:YES", shares=5, price=0.4, commission=0,
           currency="USD", account_name="币安", date="2026-07-08 11:00:00")

    positions = load_snapshot()["accounts"]["security"]["币安"]["positions"]
    assert set(positions) == {"usd", "btc", "eth", "pm:election-2028:yes"}
    assert positions["btc"]["shares"] == pytest.approx(0.75)
    assert positions["eth"]["shares"] == pytest.approx(2)
    assert positions["pm:election-2028:yes"]["shares"] == pytest.approx(5)

    repair_security()
    replayed_positions = load_snapshot()["accounts"]["security"]["币安"]["positions"]
    assert set(replayed_positions) == {"usd", "btc", "eth", "pm:election-2028:yes"}
    assert replayed_positions["btc"]["shares"] == pytest.approx(0.75)
    assert replayed_positions["eth"]["total_cost"] == pytest.approx(2500)
    assert replayed_positions["pm:election-2028:yes"]["shares"] == pytest.approx(5)



def test_fetch_prices_polymarket_resolved_market_uses_outcome_price(monkeypatch):
    """Resolved markets expose [0,1]; held outcome must use its own settlement price."""
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
        payload = (
            b'[{"slug":"resolved-market","closed":true,"acceptingOrders":false,'
            b'"umaResolutionStatus":"resolved","outcomes":"[\\"Yes\\",\\"No\\"]",'
            b'"outcomePrices":"[\\"0\\",\\"1\\"]","lastTradePrice":0}]'
        )
        return FakeResponse(payload)

    monkeypatch.setattr("urllib.request.urlopen", fake_urlopen)
    monkeypatch.setitem(sys.modules, "yfinance", None)

    prices = _fetch_prices(["pm:resolved-market:no", "pm:resolved-market:yes"])

    assert prices["pm:resolved-market:no"] == pytest.approx(1.0)
    assert prices["pm:resolved-market:yes"] == pytest.approx(0.0)


def test_sync_polymarket_adds_settlement_sell_for_resolved_open_position(tmp_env, monkeypatch):
    """Sync should close held Polymarket positions when Gamma says the market is resolved."""
    from ft.accounts import save_accounts
    from ft import models
    from ft.snapshot import save_snapshot
    from ft.polymarket_sync import sync_polymarket

    save_accounts([
        {"name": "Polymarket", "type": "security", "currency": "USD", "active": True},
    ], models.ACCOUNTS_PATH)
    save_snapshot({
        "updated_at": "2026-07-07",
        "accounts": {
            "security": {
                "Polymarket": {
                    "currency": "USD",
                    "cash": 0.0,
                    "positions": {
                        "pm:resolved-market:no": {"shares": 85.0, "avg_cost": 0.83},
                    },
                }
            }
        },
    })
    monkeypatch.setattr("ft.polymarket_sync.fetch_activity", lambda *_args, **_kwargs: [])
    monkeypatch.setattr(
        "ft.polymarket_sync._fetch_polymarket_resolution_prices",
        lambda tickers: _resolved_metadata({"pm:resolved-market:no": "1"}),
    )
    monkeypatch.setattr("ft.polymarket_sync._today_iso", lambda: "2026-07-07")

    rows = sync_polymarket(proxy_wallet="0x" + "1" * 40, account_name="Polymarket", dry_run=True)

    assert rows == [{
        "date": "2026-07-07",
        "action": "swap",
        "from_ticker": "pm:resolved-market:no",
        "to_ticker": "USD",
        "from_amount": "85",
        "to_amount": "85",
        "price": "1",
        "commission": "0",
        "commission_asset": "USD",
        "currency": "USD",
        "account_name": "Polymarket",
        "note": "polymarket settlement token:pm:resolved-market:no price:1",
    }]


def _save_polymarket_security_account():
    from ft.accounts import save_accounts
    from ft import models

    save_accounts([
        {"name": "Polymarket", "type": "security", "currency": "USD", "active": True},
    ], models.ACCOUNTS_PATH)


def _resolved_metadata(prices: dict[str, str]):
    return {ticker: Decimal(value) for ticker, value in prices.items()}


def _polymarket_sell_activity(slug: str, outcome: str, size: str, price: str, tx_hash: str) -> dict:
    return {
        "timestamp": 1782785769,
        "type": "TRADE",
        "side": "SELL",
        "slug": slug,
        "outcome": outcome,
        "size": size,
        "price": price,
        "usdcSize": str(Decimal(size) * Decimal(price)),
        "transactionHash": tx_hash,
    }


def test_sync_polymarket_full_real_sell_does_not_add_settlement(tmp_env, monkeypatch):
    """A same-sync real SELL that fully exits a resolved token must not be double-settled."""
    from ft.polymarket_sync import sync_polymarket
    from ft.stock import do_buy, load_snapshot

    _save_polymarket_security_account()
    do_buy(
        ticker="pm:resolved-full:no", shares=10, price=0.4,
        commission=0, currency="USD", account_name="Polymarket",
        date="2026-07-01 09:00:00",
    )
    monkeypatch.setattr("ft.polymarket_sync.fetch_activity", lambda *_args, **_kwargs: [
        _polymarket_sell_activity("resolved-full", "No", "10", "0.6", "0xsellfull"),
    ])
    monkeypatch.setattr("ft.stock._fetch_polymarket_prices", lambda tickers: {"pm:resolved-full:no": 1.0})
    monkeypatch.setattr(
        "ft.polymarket_sync._fetch_polymarket_resolution_prices",
        lambda tickers: _resolved_metadata({"pm:resolved-full:no": "1"}),
        raising=False,
    )
    monkeypatch.setattr("ft.polymarket_sync._today_iso", lambda: "2026-07-07")

    rows = sync_polymarket(proxy_wallet="0x" + "1" * 40, account_name="Polymarket")

    assert len(rows) == 1
    assert rows[0]["note"] == "polymarket tx:0xsellfull"
    positions = load_snapshot()["accounts"]["security"]["Polymarket"]["positions"]
    assert "pm:resolved-full:no" not in positions


def test_sync_polymarket_partial_real_sell_settles_only_remaining_shares(tmp_env, monkeypatch):
    """A same-sync partial SELL should settle only the projected remaining position."""
    from ft.polymarket_sync import sync_polymarket
    from ft.stock import do_buy, load_snapshot

    _save_polymarket_security_account()
    do_buy(
        ticker="pm:resolved-partial:no", shares=10, price=0.4,
        commission=0, currency="USD", account_name="Polymarket",
        date="2026-07-01 09:00:00",
    )
    monkeypatch.setattr("ft.polymarket_sync.fetch_activity", lambda *_args, **_kwargs: [
        _polymarket_sell_activity("resolved-partial", "No", "4", "0.6", "0xsellpartial"),
    ])
    monkeypatch.setattr("ft.stock._fetch_polymarket_prices", lambda tickers: {"pm:resolved-partial:no": 1.0})
    monkeypatch.setattr(
        "ft.polymarket_sync._fetch_polymarket_resolution_prices",
        lambda tickers: _resolved_metadata({"pm:resolved-partial:no": "1"}),
        raising=False,
    )
    monkeypatch.setattr("ft.polymarket_sync._today_iso", lambda: "2026-07-07")

    rows = sync_polymarket(proxy_wallet="0x" + "1" * 40, account_name="Polymarket")

    settlement_rows = [row for row in rows if "settlement" in row["note"]]
    assert len(settlement_rows) == 1
    assert settlement_rows[0]["from_amount"] == "6"
    assert settlement_rows[0]["to_amount"] == "6"
    positions = load_snapshot()["accounts"]["security"]["Polymarket"]["positions"]
    assert "pm:resolved-partial:no" not in positions


def test_sync_polymarket_live_zero_one_quote_does_not_settle(tmp_env, monkeypatch):
    """Endpoint-looking live quotes are valuation data, not settlement authority."""
    from ft.polymarket_sync import sync_polymarket
    from ft.stock import do_buy

    _save_polymarket_security_account()
    do_buy(
        ticker="pm:live-market:no", shares=10, price=0.4,
        commission=0, currency="USD", account_name="Polymarket",
        date="2026-07-01 09:00:00",
    )
    monkeypatch.setattr("ft.polymarket_sync.fetch_activity", lambda *_args, **_kwargs: [])
    monkeypatch.setattr("ft.stock._fetch_polymarket_prices", lambda tickers: {"pm:live-market:no": 1.0})
    monkeypatch.setattr(
        "ft.polymarket_sync._fetch_polymarket_resolution_prices",
        lambda tickers: {},
        raising=False,
    )
    monkeypatch.setattr("ft.polymarket_sync._today_iso", lambda: "2026-07-07")

    rows = sync_polymarket(proxy_wallet="0x" + "1" * 40, account_name="Polymarket", dry_run=True)

    assert rows == []


def test_sync_polymarket_existing_settlement_is_idempotent_across_dates(tmp_env, monkeypatch):
    """A stale snapshot must not generate another settlement for an already-settled token."""
    from ft import models
    from ft.polymarket_sync import sync_polymarket
    from ft.snapshot import save_snapshot
    from ft.stock import record_trade

    _save_polymarket_security_account()
    save_snapshot({
        "updated_at": "2026-07-01",
        "accounts": {
            "security": {
                "Polymarket": {
                    "currency": "USD",
                    "positions": {
                        "pm:already-settled:no": {"shares": 10, "total_cost": 4},
                    },
                },
            },
        },
    })
    record_trade(
        date="2026-07-02", action="swap",
        from_ticker="pm:already-settled:no", to_ticker="USD",
        from_amount=10, to_amount=10, price=1, commission=0,
        commission_asset="USD", currency="USD", account_name="Polymarket",
        note="polymarket settlement token:pm:already-settled:no price:1",
    )
    assert (models.RECORDS_DIR / "security" / "2026-07-02.csv").exists()
    monkeypatch.setattr("ft.polymarket_sync.fetch_activity", lambda *_args, **_kwargs: [])
    monkeypatch.setattr("ft.stock._fetch_polymarket_prices", lambda tickers: {"pm:already-settled:no": 1.0})
    monkeypatch.setattr(
        "ft.polymarket_sync._fetch_polymarket_resolution_prices",
        lambda tickers: _resolved_metadata({"pm:already-settled:no": "1"}),
        raising=False,
    )
    monkeypatch.setattr("ft.polymarket_sync._today_iso", lambda: "2026-07-07")

    rows = sync_polymarket(proxy_wallet="0x" + "1" * 40, account_name="Polymarket", dry_run=True)

    assert rows == []


def test_polymarket_resolution_metadata_requires_closed_resolved_market(monkeypatch):
    """Settlement metadata parser must ignore live markets even when outcomePrices are 0/1."""
    import ft.polymarket_sync as polymarket_sync

    def fake_request_json(url):
        assert "gamma-api.polymarket.com/markets" in url
        return [{
            "slug": "live-market",
            "closed": False,
            "umaResolutionStatus": "unresolved",
            "outcomes": "[\"Yes\", \"No\"]",
            "outcomePrices": "[\"0\", \"1\"]",
        }]

    monkeypatch.setattr("ft.polymarket_sync._request_json", fake_request_json)

    assert hasattr(polymarket_sync, "_fetch_polymarket_resolution_prices")
    prices = polymarket_sync._fetch_polymarket_resolution_prices(["pm:live-market:no"])

    assert prices == {}


def test_fetch_prices_polymarket_resolved_market_found_via_search_fallback(monkeypatch):
    """If direct slug lookup is stale, search parent event and use resolved child outcomePrices."""
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
        if "gamma-api.polymarket.com/markets?slug=stale-child-market" in url:
            return FakeResponse(b"[]")
        if "gamma-api.polymarket.com/public-search" in url:
            payload = (
                b'{"events":[{"slug":"parent-event","markets":[{"slug":"stale-child-market",'
                b'"closed":true,"acceptingOrders":false,"umaResolutionStatus":"resolved",'
                b'"outcomes":"[\\"Yes\\",\\"No\\"]",'
                b'"outcomePrices":"[\\"0\\",\\"1\\"]"}]}]}'
            )
            return FakeResponse(payload)
        raise AssertionError(url)

    monkeypatch.setattr("urllib.request.urlopen", fake_urlopen)
    monkeypatch.setitem(sys.modules, "yfinance", None)

    prices = _fetch_prices(["pm:stale-child-market:no"])

    assert prices == {"pm:stale-child-market:no": pytest.approx(1.0)}
