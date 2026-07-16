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


def _base_ledger(**over):
    entry = {
        "id": "L1",
        "timestamp": 1751852400000,
        "type": "transaction",
        "direction": "in",
        "currency": "USD",
        "amount": 2980.0,
        "fee": {"cost": 3.0, "currency": "USD"},
    }
    entry.update(over)
    return entry


def test_trade_to_rows_usdt_buy():
    from ft.exchange_sync import trade_to_rows
    rows = trade_to_rows(_base_trade(), account_name="币安", provider="kraken")
    assert len(rows) == 1
    r = rows[0]
    assert r["action"] == "swap"
    assert r["from_ticker"] == "usdt"
    assert r["to_ticker"] == "btc"
    assert r["from_amount"] == "3000"
    assert r["to_amount"] == "0.05"
    assert r["price"] == "60000"
    assert r["commission"] == "0"
    assert r["currency"] == "USD"
    assert r["account_name"] == "币安"
    assert r["note"] == "kraken tid:T1"


def test_trade_to_rows_usdt_sell():
    from ft.exchange_sync import trade_to_rows
    rows = trade_to_rows(_base_trade(side="sell"), account_name="币安", provider="kraken")
    assert len(rows) == 1
    r = rows[0]
    assert r["action"] == "swap"
    assert r["from_ticker"] == "btc"
    assert r["to_ticker"] == "usdt"
    assert r["from_amount"] == "0.05"
    assert r["to_amount"] == "3000"


def test_trade_to_rows_coin_pair_buy_makes_swap():
    from ft.exchange_sync import trade_to_rows
    t = _base_trade(symbol="ETH/BTC", side="buy", price=0.05, amount=10.0, cost=0.5)
    rows = trade_to_rows(t, account_name="币安", provider="kraken")
    assert len(rows) == 1
    r = rows[0]
    assert r["action"] == "swap"
    # buy ETH/BTC: pay btc, receive eth
    assert r["from_ticker"] == "btc"
    assert r["from_amount"] == "0.5"
    assert r["to_ticker"] == "eth"
    assert r["to_amount"] == "10"
    assert r["note"] == "kraken tid:T1"


def test_trade_to_rows_coin_pair_sell_reverses():
    from ft.exchange_sync import trade_to_rows
    t = _base_trade(symbol="ETH/BTC", side="sell", price=0.05, amount=10.0, cost=0.5)
    rows = trade_to_rows(t, account_name="币安", provider="kraken")
    assert len(rows) == 1
    r = rows[0]
    assert r["action"] == "swap"
    # sell ETH/BTC: pay eth, receive btc
    assert r["from_ticker"] == "eth"
    assert r["from_amount"] == "10"
    assert r["to_ticker"] == "btc"
    assert r["to_amount"] == "0.5"


def test_trade_to_rows_fee_embedded():
    from ft.exchange_sync import trade_to_rows
    t = _base_trade(fee={"cost": 1.5, "currency": "USDT"})
    rows = trade_to_rows(t, account_name="币安", provider="kraken")
    assert len(rows) == 1
    assert rows[0]["commission"] == "1.5"
    assert rows[0]["commission_asset"] == "usdt"


def test_trade_to_rows_non_cash_fee_embedded():
    from ft.exchange_sync import trade_to_rows
    t = _base_trade(fee={"cost": 0.001, "currency": "BNB"})
    rows = trade_to_rows(t, account_name="币安", provider="kraken")
    assert len(rows) == 1
    r = rows[0]
    assert r["action"] == "swap"
    assert r["commission"] == "0.001"
    assert r["commission_asset"] == "bnb"


def test_trade_to_rows_swap_fee_embedded():
    from ft.exchange_sync import trade_to_rows
    t = _base_trade(symbol="ETH/BTC", side="buy", price=0.05, amount=10.0, cost=0.5,
                    fee={"cost": 0.01, "currency": "USDT"})
    rows = trade_to_rows(t, account_name="币安", provider="kraken")
    assert len(rows) == 1
    r = rows[0]
    assert r["action"] == "swap"
    assert r["commission"] == "0.01"
    assert r["commission_asset"] == "usdt"


def test_trade_to_rows_no_fee():
    from ft.exchange_sync import trade_to_rows
    t = _base_trade(fee=None)
    rows = trade_to_rows(t, account_name="币安", provider="kraken")
    assert rows[0]["commission"] == "0"
    assert rows[0]["commission_asset"] == ""


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


def test_ledger_to_rows_transaction_in_deposit():
    from ft.exchange_sync import ledger_to_rows
    rows = ledger_to_rows(_base_ledger(), account_name="币安", provider="kraken")
    assert len(rows) == 1
    r = rows[0]
    assert r["date"] == "2025-07-07 09:40:00"
    assert r["action"] == "deposit"
    assert r["from_ticker"] == ""
    assert r["to_ticker"] == "usd"
    assert r["from_amount"] == "0"
    assert r["to_amount"] == "2980"
    assert r["price"] == "1"
    assert r["commission"] == "3"
    assert r["commission_asset"] == "usd"
    assert r["currency"] == "USD"
    assert r["account_name"] == "币安"
    assert r["note"] == "kraken lid:L1 type:transaction"


def test_ledger_to_rows_transaction_out_withdraw():
    from ft.exchange_sync import ledger_to_rows
    rows = ledger_to_rows(
        _base_ledger(id="L2", direction="out", amount=100.25,
                     fee={"cost": 1.25, "currency": "USDT"}, currency="USDT"),
        account_name="币安",
        provider="kraken",
    )
    r = rows[0]
    assert r["action"] == "withdraw"
    assert r["from_ticker"] == "usdt"
    assert r["to_ticker"] == ""
    assert r["from_amount"] == "100.25"
    assert r["to_amount"] == "0"
    assert r["commission"] == "1.25"
    assert r["commission_asset"] == "usdt"
    assert r["note"] == "kraken lid:L2 type:transaction"


@pytest.mark.parametrize("typ", ["transaction", "transfer", "derivativescrossexchangetransfer"])
@pytest.mark.parametrize(
    ("direction", "action", "from_ticker", "to_ticker", "from_amount", "to_amount"),
    [
        ("in", "deposit", "", "usdt", "0", "42.5"),
        ("out", "withdraw", "usdt", "", "42.5", "0"),
    ],
)
def test_ledger_to_rows_transfer_like_types_follow_direction(
    typ, direction, action, from_ticker, to_ticker, from_amount, to_amount
):
    from ft.exchange_sync import ledger_to_rows
    rows = ledger_to_rows(
        _base_ledger(
            id=f"L-{typ}-{direction}",
            type=typ,
            direction=direction,
            currency="USDT",
            amount=42.5,
            fee={"cost": 0, "currency": "USDT"},
        ),
        account_name="币安",
        provider="kraken",
    )
    assert len(rows) == 1
    r = rows[0]
    assert r["action"] == action
    assert r["from_ticker"] == from_ticker
    assert r["to_ticker"] == to_ticker
    assert r["from_amount"] == from_amount
    assert r["to_amount"] == to_amount
    assert r["commission"] == "0"
    assert r["commission_asset"] == ""
    assert r["note"] == f"kraken lid:L-{typ}-{direction} type:{typ}"


@pytest.mark.parametrize("typ", ["reward", "staking"])
def test_ledger_to_rows_income_types_are_dividend_with_net_amount_and_audit_fee(typ):
    from ft.exchange_sync import ledger_to_rows
    rows = ledger_to_rows(
        _base_ledger(
            id=f"L-{typ}",
            type=typ,
            direction="in",
            currency="USDG",
            amount="1.2345",
            fee={"cost": "0.0005", "currency": "USDG"},
        ),
        account_name="币安",
        provider="kraken",
    )
    assert len(rows) == 1
    r = rows[0]
    assert r["action"] == "dividend"
    assert r["from_ticker"] == ""
    assert r["to_ticker"] == "usdg"
    assert r["from_amount"] == "0"
    # CCXT ledger amount is treated as net credited amount; commission is audit-only.
    assert r["to_amount"] == "1.2345"
    assert r["price"] == "1"
    assert r["commission"] == "0.0005"
    assert r["commission_asset"] == "usdg"
    assert r["note"] == f"kraken lid:L-{typ} type:{typ}"


@pytest.mark.parametrize("typ", ["reward", "staking"])
def test_ledger_to_rows_income_types_reject_non_in_direction(typ):
    from ft.exchange_sync import ledger_to_rows
    with pytest.raises(ValueError, match=rf"ledger.*L-{typ}.*{typ}.*out.*USDG"):
        ledger_to_rows(
            _base_ledger(id=f"L-{typ}", type=typ, direction="out", currency="USDG"),
            account_name="币安",
            provider="kraken",
        )


def test_ledger_to_rows_trade_entries_are_ignored():
    from ft.exchange_sync import ledger_to_rows
    assert ledger_to_rows(
        _base_ledger(type="trade", direction=None),
        account_name="币安",
        provider="kraken",
    ) == []


def test_ledger_to_rows_unknown_balance_affecting_type_raises():
    from ft.exchange_sync import ledger_to_rows
    with pytest.raises(ValueError, match="ledger.*L9.*adjustment.*BTC"):
        ledger_to_rows(
            _base_ledger(id="L9", type="adjustment", direction="in", currency="BTC"),
            account_name="币安",
            provider="kraken",
        )


class _FakeClient:
    """Mock ccxt client: serves canned pages of my-trades."""
    def __init__(self, pages, ledger_pages=None):
        self._pages = list(pages)
        self._ledger_pages = list(ledger_pages or [])
        self.calls = []
        self.ledger_calls = []

    def fetch_my_trades(self, symbol=None, since=None, limit=None):
        self.calls.append((symbol, since, limit))
        return self._pages.pop(0) if self._pages else []

    def fetch_ledger(self, code=None, since=None, limit=None):
        self.ledger_calls.append((code, since, limit))
        return self._ledger_pages.pop(0) if self._ledger_pages else []


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


def test_fetch_ledger_paginates_and_dedupes():
    from ft.exchange_sync import fetch_ledger
    page1 = [_base_ledger(id="A", timestamp=1000), _base_ledger(id="B", timestamp=2000)]
    page2 = [_base_ledger(id="B", timestamp=2000), _base_ledger(id="C", timestamp=3000)]
    client = _FakeClient([], ledger_pages=[page1, page2, []])
    entries = fetch_ledger(client, since=0, limit=2)
    assert [entry["id"] for entry in entries] == ["A", "B", "C"]
    assert client.ledger_calls[1][1] == 2001


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
    # Each trade → exactly 1 swap row
    assert len(new) == 2
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


def test_sync_exchange_includes_kraken_ledger_and_trade_without_writing_on_dry_run_or_output(tmp_env):
    from ft.exchange_sync import sync_exchange
    _seed_crypto_account()

    trades = [_base_trade(id="T1")]
    ledger = [
        _base_ledger(id="L1", direction="in", currency="USD", amount=2980.0,
                     fee={"cost": 3.0, "currency": "USD"}),
        _base_ledger(id="L2", direction="out", currency="USDT", amount=100.0,
                     fee={"cost": 1.0, "currency": "USDT"}),
        _base_ledger(id="LT", type="trade", direction=None, currency="BTC", amount=0.05),
    ]

    new = sync_exchange(
        "kraken",
        account_name="币安",
        dry_run=True,
        _client=_FakeClient([trades, []], ledger_pages=[ledger, []]),
    )
    assert [row["action"] for row in new] == ["swap", "deposit", "withdraw"]
    assert not (tmp_env / "records" / "security").exists()

    out = tmp_env / "out.csv"
    new_out = sync_exchange(
        "kraken",
        account_name="币安",
        output=str(out),
        _client=_FakeClient([trades, []], ledger_pages=[ledger, []]),
    )
    assert len(new_out) == 3
    assert out.exists()
    assert not (tmp_env / "records" / "security").exists()


def test_sync_exchange_maps_complete_kraken_fake_ledger_without_writing_on_dry_run(tmp_env):
    from ft.exchange_sync import sync_exchange
    _seed_crypto_account()

    ledger = [
        _base_ledger(id="DEP", type="transaction", direction="in", currency="USD", amount=2980,
                     fee={"cost": 3, "currency": "USD"}),
        _base_ledger(id="TRD-BTC-IN", type="trade", direction="in", currency="BTC", amount=0.01),
        _base_ledger(id="TRD-BTC-OUT", type="trade", direction="out", currency="BTC", amount=0.02),
        _base_ledger(id="TRD-ETH-IN", type="trade", direction="in", currency="ETH", amount=0.3),
        _base_ledger(id="TRD-ETH-OUT", type="trade", direction="out", currency="ETH", amount=0.4),
        _base_ledger(id="TRD-USDT-IN", type="trade", direction="in", currency="USDT", amount=10),
        _base_ledger(id="TRD-USDT-OUT", type="trade", direction="out", currency="USDT", amount=11),
        _base_ledger(id="TRD-USD-IN", type="trade", direction="in", currency="USD", amount=12),
        _base_ledger(id="TRD-USD-OUT", type="trade", direction="out", currency="USD", amount=13),
        _base_ledger(id="REWARD", type="reward", direction="in", currency="USDG", amount="1.23",
                     fee={"cost": "0", "currency": "USDG"}),
        _base_ledger(id="STAKE-USDG", type="staking", direction="in", currency="USDG", amount="2.34"),
        _base_ledger(id="STAKE-USDT", type="staking", direction="in", currency="USDT", amount="3.45"),
        _base_ledger(id="STAKE-ETH", type="staking", direction="in", currency="ETH", amount="0.0067"),
        _base_ledger(id="STAKE-BABY", type="staking", direction="in", currency="BABY", amount="8.9"),
        _base_ledger(id="TRANSFER-IN", type="transfer", direction="in", currency="USDT", amount="20"),
        _base_ledger(id="TRANSFER-OUT", type="transfer", direction="out", currency="USDT", amount="20"),
        _base_ledger(id="DERIV-IN", type="derivativescrossexchangetransfer",
                     direction="in", currency="USDT", amount="30"),
        _base_ledger(id="DERIV-OUT", type="derivativescrossexchangetransfer",
                     direction="out", currency="USDT", amount="30"),
    ]

    new = sync_exchange(
        "kraken",
        account_name="币安",
        dry_run=True,
        _client=_FakeClient([[]], ledger_pages=[ledger, []]),
    )
    assert len(new) == 10
    assert [row["action"] for row in new] == [
        "deposit",
        "dividend",
        "dividend",
        "dividend",
        "dividend",
        "dividend",
        "deposit",
        "withdraw",
        "deposit",
        "withdraw",
    ]
    assert [row["note"].split()[1] for row in new] == [
        "lid:DEP",
        "lid:REWARD",
        "lid:STAKE-USDG",
        "lid:STAKE-USDT",
        "lid:STAKE-ETH",
        "lid:STAKE-BABY",
        "lid:TRANSFER-IN",
        "lid:TRANSFER-OUT",
        "lid:DERIV-IN",
        "lid:DERIV-OUT",
    ]
    assert not (tmp_env / "records" / "security").exists()


def test_sync_exchange_income_ledger_replay_uses_net_amount_and_keeps_fee_audit(tmp_env):
    from ft.exchange_sync import sync_exchange
    from ft.stock import load_snapshot
    _seed_crypto_account()

    ledger = [
        _base_ledger(
            id="REWARD-FEE",
            type="reward",
            direction="in",
            currency="USDG",
            amount="1.2345",
            fee={"cost": "0.0005", "currency": "USDG"},
        )
    ]
    rows = sync_exchange(
        "kraken",
        account_name="币安",
        _client=_FakeClient([[]], ledger_pages=[ledger, []]),
    )
    assert rows[0]["action"] == "dividend"
    assert rows[0]["to_amount"] == "1.2345"
    assert rows[0]["commission"] == "0.0005"
    assert rows[0]["commission_asset"] == "usdg"

    snap = load_snapshot()
    pos = snap["accounts"]["security"]["币安"]["positions"]["usdg"]
    assert pos["shares"] == pytest.approx(1.2345)
    assert pos["total_cost"] == pytest.approx(1.23)


def test_sync_exchange_dry_run_with_real_client_builder_does_not_touch_credentials_gitignore(
    tmp_env, monkeypatch
):
    from ft import exchange_sync
    _seed_crypto_account()

    ensure_calls = []
    monkeypatch.setattr(
        exchange_sync,
        "load_credentials",
        lambda provider: {"api_key": "K", "api_secret": "S"},
    )
    monkeypatch.setattr(
        exchange_sync,
        "build_client",
        lambda provider, creds: _FakeClient([[]], ledger_pages=[[]]),
    )
    monkeypatch.setattr(
        exchange_sync,
        "ensure_credentials_gitignored",
        lambda: ensure_calls.append(True),
    )

    assert exchange_sync.sync_exchange("kraken", account_name="币安", dry_run=True) == []
    assert ensure_calls == []


def test_sync_exchange_is_idempotent(tmp_env):
    from ft.exchange_sync import sync_exchange
    _seed_crypto_account()
    trades = [{"id": "T1", "timestamp": 1751852400000, "symbol": "BTC/USDT",
               "side": "buy", "price": 60000.0, "amount": 0.1, "cost": 6000.0, "fee": None}]
    sync_exchange("kraken", account_name="币安", _client=_FakeClient([trades, []]))
    # 再同步一次：0 新增
    new = sync_exchange("kraken", account_name="币安", _client=_FakeClient([trades, []]))
    assert new == []


def test_sync_exchange_ledger_idempotent_and_lid_not_confused_with_tid(tmp_env):
    from ft.exchange_sync import sync_exchange
    _seed_crypto_account()

    trades = [_base_trade(id="SAME")]
    ledger = [_base_ledger(id="SAME", direction="in", amount=50.0,
                           fee={"cost": 0, "currency": "USD"})]
    first = sync_exchange(
        "kraken",
        account_name="币安",
        _client=_FakeClient([trades, []], ledger_pages=[ledger, []]),
    )
    assert [row["note"] for row in first] == [
        "kraken tid:SAME",
        "kraken lid:SAME type:transaction",
    ]

    second = sync_exchange(
        "kraken",
        account_name="币安",
        _client=_FakeClient([trades, []], ledger_pages=[ledger, []]),
    )
    assert second == []


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
    assert rows[0]["action"] == "swap"
    assert rows[0]["from_ticker"] == "btc"
    assert rows[0]["to_ticker"] == "usdt"
    assert not (tmp_env / "records" / "security").exists()
