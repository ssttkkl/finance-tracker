import pytest


def test_ccxt_is_importable():
    """ccxt 必须已安装，交易所同步依赖它。"""
    import ccxt
    assert hasattr(ccxt, "kraken")


def _base_trade(**over):
    t = {"id": "T1", "timestamp": 1751852400000, "symbol": "BTC/USDT",
         "side": "buy", "price": 60000.0, "amount": 0.05, "cost": 3000.0,
         "fee": None}
    t.update(over)
    return t


def test_trade_to_rows_usdt_buy():
    from ft.exchange_sync import trade_to_rows
    rows = trade_to_rows(_base_trade(), account_name="币安", provider="kraken")
    assert len(rows) == 1
    r = rows[0]
    assert r["action"] == "BUY"
    assert r["ticker"] == "btc"
    assert r["shares"] == "0.05"
    assert r["price"] == "60000"
    assert r["amount"] == "-3000"
    assert r["commission"] == "0"
    assert r["currency"] == "USD"
    assert r["account_name"] == "币安"
    assert r["note"] == "kraken tid:T1"


def test_trade_to_rows_usdt_sell():
    from ft.exchange_sync import trade_to_rows
    rows = trade_to_rows(_base_trade(side="sell"), account_name="币安", provider="kraken")
    assert rows[0]["action"] == "SELL"
    assert rows[0]["amount"] == "3000"


def test_trade_to_rows_coin_pair_buy_makes_swap():
    from ft.exchange_sync import trade_to_rows
    t = _base_trade(symbol="ETH/BTC", side="buy", price=0.05, amount=10.0, cost=0.5)
    rows = trade_to_rows(t, account_name="币安", provider="kraken")
    assert [r["action"] for r in rows] == ["SWAP_OUT", "SWAP_IN"]
    out, inn = rows
    assert out["ticker"] == "btc" and out["shares"] == "0.5"   # 换出 quote=btc
    assert inn["ticker"] == "eth" and inn["shares"] == "10"    # 换入 base=eth
    assert out["note"] == inn["note"] == "kraken tid:T1 swap:T1"
    assert out["price"] == "" and out["amount"] == "" and out["commission"] == ""


def test_trade_to_rows_coin_pair_sell_reverses():
    from ft.exchange_sync import trade_to_rows
    t = _base_trade(symbol="ETH/BTC", side="sell", price=0.05, amount=10.0, cost=0.5)
    rows = trade_to_rows(t, account_name="币安", provider="kraken")
    out, inn = rows
    assert out["ticker"] == "eth" and out["shares"] == "10"    # 卖出 base=eth
    assert inn["ticker"] == "btc" and inn["shares"] == "0.5"   # 得到 quote=btc


def test_trade_to_rows_cash_fee_goes_to_commission():
    from ft.exchange_sync import trade_to_rows
    t = _base_trade(fee={"cost": 1.5, "currency": "USDT"})
    rows = trade_to_rows(t, account_name="币安", provider="kraken")
    assert len(rows) == 1
    assert rows[0]["commission"] == "1.5"


def test_trade_to_rows_holding_fee_makes_fee_row():
    from ft.exchange_sync import trade_to_rows
    t = _base_trade(fee={"cost": 0.001, "currency": "BNB"})
    rows = trade_to_rows(t, account_name="币安", provider="kraken")
    assert [r["action"] for r in rows] == ["BUY", "FEE"]
    fee_row = rows[1]
    assert fee_row["ticker"] == "bnb"
    assert fee_row["shares"] == "0.001"
    assert fee_row["note"] == "kraken tid:T1 fee"


def test_trade_to_rows_swap_fee_always_fee_row():
    from ft.exchange_sync import trade_to_rows
    t = _base_trade(symbol="ETH/BTC", side="buy", price=0.05, amount=10.0, cost=0.5,
                    fee={"cost": 0.01, "currency": "USDT"})
    rows = trade_to_rows(t, account_name="币安", provider="kraken")
    assert [r["action"] for r in rows] == ["SWAP_OUT", "SWAP_IN", "FEE"]
    assert rows[2]["ticker"] == "usdt"


def test_trade_to_rows_unknown_side_raises():
    from ft.exchange_sync import trade_to_rows
    with pytest.raises(ValueError, match="side"):
        trade_to_rows(_base_trade(side="transfer"), account_name="币安", provider="kraken")


def test_trade_to_rows_missing_id_raises():
    from ft.exchange_sync import trade_to_rows
    with pytest.raises(ValueError, match="id"):
        trade_to_rows(_base_trade(id=None), account_name="币安", provider="kraken")


def test_trade_to_rows_bad_symbol_raises():
    from ft.exchange_sync import trade_to_rows
    with pytest.raises(ValueError, match="symbol"):
        trade_to_rows(_base_trade(symbol="BTCUSDT"), account_name="币安", provider="kraken")


class _FakeClient:
    """Mock ccxt client: serves canned pages of my-trades."""
    def __init__(self, pages):
        self._pages = list(pages)
        self.calls = []

    def fetch_my_trades(self, symbol=None, since=None, limit=None):
        self.calls.append((symbol, since, limit))
        return self._pages.pop(0) if self._pages else []


def test_validate_crypto_account_ok(tmp_path, monkeypatch):
    from ft import accounts, exchange_sync
    monkeypatch.setattr(accounts, "find_account",
                        lambda name, currency=None: {"type": "crypto"})
    monkeypatch.setattr(exchange_sync, "find_account",
                        accounts.find_account, raising=False)
    # find_account is imported into exchange_sync; patch there:
    monkeypatch.setattr("ft.exchange_sync.find_account",
                        lambda name, currency="USD": {"type": "crypto"})
    exchange_sync.validate_crypto_account("币安")  # no raise


def test_validate_crypto_account_wrong_type_raises(monkeypatch):
    from ft import exchange_sync
    monkeypatch.setattr("ft.exchange_sync.find_account",
                        lambda name, currency="USD": {"type": "security"})
    with pytest.raises(ValueError, match="crypto"):
        exchange_sync.validate_crypto_account("IBKR")


def test_validate_crypto_account_missing_raises(monkeypatch):
    from ft import exchange_sync
    monkeypatch.setattr("ft.exchange_sync.find_account",
                        lambda name, currency="USD": None)
    with pytest.raises(ValueError, match="未知账户"):
        exchange_sync.validate_crypto_account("nope")


def test_build_client_unknown_provider_raises():
    from ft.exchange_sync import build_client
    with pytest.raises(ValueError, match="notanexchange"):
        build_client("notanexchange", {"api_key": "K", "api_secret": "S"})


def test_fetch_trades_paginates_and_dedupes():
    from ft.exchange_sync import fetch_trades
    page1 = [{"id": "A", "timestamp": 1000}, {"id": "B", "timestamp": 2000}]
    page2 = [{"id": "B", "timestamp": 2000}, {"id": "C", "timestamp": 3000}]
    client = _FakeClient([page1, page2, []])
    trades = fetch_trades(client, since=0, symbols=["BTC/USDT"], limit=2)
    assert [t["id"] for t in trades] == ["A", "B", "C"]
    # 第二页游标应为上页最后 timestamp+1
    assert client.calls[1][1] == 2001


import csv as _csv
import tempfile as _tempfile
from pathlib import Path as _Path


@pytest.fixture
def tmp_env():
    d = _Path(_tempfile.mkdtemp())
    from ft import models
    import ft.snapshot as snapshot_mod
    olds = (models.FT_DIR, models.RECORDS_DIR, models.ACCOUNTS_PATH,
            snapshot_mod.SNAPSHOT_PATH)
    models.FT_DIR = d
    models.RECORDS_DIR = d / "records"
    models.ACCOUNTS_PATH = d / "accounts.yaml"
    snapshot_mod.SNAPSHOT_PATH = d / "snapshot.yaml"
    yield d
    (models.FT_DIR, models.RECORDS_DIR, models.ACCOUNTS_PATH,
     snapshot_mod.SNAPSHOT_PATH) = olds
    import shutil
    shutil.rmtree(d, ignore_errors=True)


def _seed_crypto_account():
    from ft.accounts import save_accounts
    from ft import models
    save_accounts([{"name": "币安", "type": "crypto", "currency": "USD", "active": True}],
                  models.ACCOUNTS_PATH)


def test_sync_exchange_end_to_end_mixed(tmp_env):
    from ft.exchange_sync import sync_exchange
    from ft.stock import load_snapshot, verify_security
    _seed_crypto_account()

    trades = [
        {"id": "T1", "timestamp": 1751852400000, "symbol": "BTC/USDT", "side": "buy",
         "price": 60000.0, "amount": 0.1, "cost": 6000.0, "fee": None},
        {"id": "T2", "timestamp": 1751856000000, "symbol": "ETH/BTC", "side": "buy",
         "price": 0.05, "amount": 1.0, "cost": 0.05,
         "fee": {"cost": 0.001, "currency": "BNB"}},
    ]
    client = _FakeClient([trades, []])

    # dry-run：不写入
    new = sync_exchange("kraken", account_name="币安", dry_run=True,
                        symbols=["BTC/USDT", "ETH/BTC"], _client=client)
    assert len(new) == 4          # BUY + (SWAP_OUT+SWAP_IN+FEE)
    assert not (tmp_env / "records" / "security").exists()

    # 真实 append
    client2 = _FakeClient([trades, []])
    sync_exchange("kraken", account_name="币安",
                  symbols=["BTC/USDT", "ETH/BTC"], _client=client2)
    snap = load_snapshot()
    acct = snap["accounts"]["security"]["币安"]
    # BTC: 买入 0.1，换出 0.05 → 0.05
    assert acct["positions"]["btc"]["shares"] == pytest.approx(0.05)
    # ETH: 换入 1.0
    assert acct["positions"]["eth"]["shares"] == pytest.approx(1.0)
    ok, _ = verify_security()
    assert ok is True


def test_sync_exchange_is_idempotent(tmp_env):
    from ft.exchange_sync import sync_exchange
    _seed_crypto_account()
    trades = [{"id": "T1", "timestamp": 1751852400000, "symbol": "BTC/USDT",
               "side": "buy", "price": 60000.0, "amount": 0.1, "cost": 6000.0, "fee": None}]
    sync_exchange("kraken", account_name="币安", _client=_FakeClient([trades, []]))
    # 再同步一次：0 新增
    new = sync_exchange("kraken", account_name="币安", _client=_FakeClient([trades, []]))
    assert new == []


def test_sync_exchange_writes_output_csv(tmp_env):
    from ft.exchange_sync import sync_exchange
    _seed_crypto_account()
    trades = [{"id": "T1", "timestamp": 1751852400000, "symbol": "BTC/USDT",
               "side": "sell", "price": 60000.0, "amount": 0.1, "cost": 6000.0, "fee": None}]
    out = tmp_env / "out.csv"
    sync_exchange("kraken", account_name="币安", output=str(out),
                  _client=_FakeClient([trades, []]))
    with out.open(encoding="utf-8") as f:
        rows = list(_csv.DictReader(f))
    assert rows[0]["action"] == "SELL"
    assert not (tmp_env / "records" / "security").exists()
