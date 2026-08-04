"""Base-currency cost semantics (012): base tickers are face-only, no cost basis."""
from decimal import Decimal

from ft.domain.investment_projection import (
    DEFAULT_BASE_TICKERS,
    apply_investment_event,
    normalize_base_tickers,
)


def _snap(name="A"):
    return {"accounts": {"security": {name: {"currency": "USD", "positions": {}}}}}


def test_normalize_empty_uses_default_including_stables():
    bases = normalize_base_tickers(None)
    assert "usd" in bases and "usdt" in bases and bases is DEFAULT_BASE_TICKERS or "usdt" in bases


def test_hkd_usd_fx_with_account_bases_no_conflict():
    snap = _snap("盈立")
    bases = normalize_base_tickers(["USD", "HKD"])
    apply_investment_event(
        snap,
        {
            "date": "2026-06-01", "record_type": "deposit", "currency": "HKD",
            "account_name": "盈立", "to_ticker": "hkd", "to_amount": "5000",
            "from_ticker": "", "from_amount": "0", "commission": "0",
        },
        default_currency="USD",
        base_tickers=bases,
    )
    apply_investment_event(
        snap,
        {
            "date": "2026-06-16", "record_type": "swap", "currency": "HKD",
            "account_name": "盈立", "from_ticker": "hkd", "from_amount": "3161.18",
            "to_ticker": "usd", "to_amount": "402.32", "commission": "0",
        },
        default_currency="USD",
        base_tickers=bases,
    )
    pos = snap["accounts"]["security"]["盈立"]["positions"]
    assert Decimal(pos["hkd"]["shares"]) == Decimal("1838.82")
    assert Decimal(pos["hkd"]["total_cost"]) == Decimal("1838.82")  # face
    assert pos["hkd"]["cost_currency"] == "HKD"
    assert Decimal(pos["usd"]["shares"]) == Decimal("402.32")
    assert Decimal(pos["usd"]["total_cost"]) == Decimal("402.32")
    assert pos["usd"]["cost_currency"] == "USD"


def test_equity_buy_accumulates_cost_in_event_currency():
    snap = _snap("IBKR")
    bases = normalize_base_tickers(["USD"])
    apply_investment_event(
        snap,
        {
            "date": "2026-06-01", "record_type": "deposit", "currency": "USD",
            "account_name": "IBKR", "to_ticker": "usd", "to_amount": "10000",
            "from_ticker": "", "from_amount": "0", "commission": "0",
        },
        default_currency="USD", base_tickers=bases,
    )
    apply_investment_event(
        snap,
        {
            "date": "2026-06-02", "record_type": "swap", "currency": "USD",
            "account_name": "IBKR", "from_ticker": "usd", "from_amount": "1990.40",
            "to_ticker": "mrvl.us", "to_amount": "10", "commission": "3.92",
            "commission_asset": "usd",
        },
        default_currency="USD", base_tickers=bases,
    )
    pos = snap["accounts"]["security"]["IBKR"]["positions"]
    assert Decimal(pos["mrvl.us"]["shares"]) == Decimal("10")
    # equity cost = released from cash face + commission on from (usd)
    assert Decimal(pos["mrvl.us"]["total_cost"]) == Decimal("1994.32")
    assert pos["mrvl.us"]["cost_currency"] == "USD"
    assert Decimal(pos["usd"]["shares"]) == Decimal("10000") - Decimal("1990.40") - Decimal("3.92")
    assert Decimal(pos["usd"]["total_cost"]) == Decimal(pos["usd"]["shares"])


def test_usdt_base_crypto_buy():
    snap = _snap("Binance")
    bases = normalize_base_tickers(["USDT"])
    apply_investment_event(
        snap,
        {
            "date": "2026-06-01", "record_type": "deposit", "currency": "USDT",
            "account_name": "Binance", "to_ticker": "usdt", "to_amount": "5000",
            "from_ticker": "", "from_amount": "0", "commission": "0",
        },
        default_currency="USDT", base_tickers=bases,
    )
    apply_investment_event(
        snap,
        {
            "date": "2026-06-02", "record_type": "swap", "currency": "USDT",
            "account_name": "Binance", "from_ticker": "usdt", "from_amount": "5000",
            "to_ticker": "btc", "to_amount": "0.1", "commission": "0",
        },
        default_currency="USDT", base_tickers=bases,
    )
    pos = snap["accounts"]["security"]["Binance"]["positions"]
    assert Decimal(pos["usdt"]["shares"]) == Decimal("0")
    assert Decimal(pos["btc"]["shares"]) == Decimal("0.1")
    assert Decimal(pos["btc"]["total_cost"]) == Decimal("5000")
    assert pos["btc"]["cost_currency"] == "USDT"


def test_fee_reduces_base_cash_like_withdraw():
    snap = _snap("A")
    bases = normalize_base_tickers(["USD"])
    apply_investment_event(
        snap,
        {
            "date": "2026-06-01", "record_type": "deposit", "currency": "USD",
            "account_name": "A", "to_ticker": "usd", "to_amount": "100",
            "from_ticker": "", "from_amount": "0", "commission": "0",
        },
        default_currency="USD", base_tickers=bases,
    )
    apply_investment_event(
        snap,
        {
            "date": "2026-06-02", "record_type": "fee", "currency": "USD",
            "account_name": "A", "from_ticker": "usd", "from_amount": "0.12",
            "to_ticker": "", "to_amount": "0", "commission": "0",
            "note": "融资利息",
        },
        default_currency="USD", base_tickers=bases,
    )
    pos = snap["accounts"]["security"]["A"]["positions"]
    assert Decimal(pos["usd"]["shares"]) == Decimal("99.88")


def test_dividend_increases_cash():
    snap = _snap("A")
    bases = normalize_base_tickers(["USD"])
    apply_investment_event(
        snap,
        {
            "date": "2026-06-01", "record_type": "dividend", "currency": "USD",
            "account_name": "A", "to_ticker": "usd", "to_amount": "12.77",
            "from_ticker": "", "from_amount": "0", "commission": "0",
            "note": "红利入账",
        },
        default_currency="USD", base_tickers=bases,
    )
    pos = snap["accounts"]["security"]["A"]["positions"]
    assert Decimal(pos["usd"]["shares"]) == Decimal("12.77")


def test_fee_refund_increases_cash():
    snap = _snap("A")
    bases = normalize_base_tickers(["USD"])
    apply_investment_event(
        snap,
        {
            "date": "2026-06-01", "record_type": "deposit", "currency": "USD",
            "account_name": "A", "to_ticker": "usd", "to_amount": "10",
            "from_ticker": "", "from_amount": "0", "commission": "0",
        },
        default_currency="USD", base_tickers=bases,
    )
    apply_investment_event(
        snap,
        {
            "date": "2026-06-02", "record_type": "fee", "currency": "USD",
            "account_name": "A", "from_ticker": "", "from_amount": "0",
            "to_ticker": "usd", "to_amount": "0.27", "commission": "0",
            "note": "Refund tax of TQQQ.US",
        },
        default_currency="USD", base_tickers=bases,
    )
    pos = snap["accounts"]["security"]["A"]["positions"]
    assert Decimal(pos["usd"]["shares"]) == Decimal("10.27")


def test_ipo_debit_and_refund_are_signed_cash_like_fee():
    from ft.domain.investment_projection import apply_investment_event

    snap = {"accounts": {"security": {"A": {"currency": "HKD", "positions": {
        "hkd": {"shares": "10000", "total_cost": "10000", "cost_currency": "HKD"},
    }}}}}
    apply_investment_event(
        snap,
        {"record_type": "ipo", "account_name": "A", "currency": "HKD", "date": "2026-05-29",
         "from_ticker": "hkd", "from_amount": "5181.74", "to_ticker": "", "to_amount": "0"},
        default_currency="HKD",
        base_tickers={"hkd", "usd"},
    )
    from decimal import Decimal
    assert Decimal(snap["accounts"]["security"]["A"]["positions"]["hkd"]["shares"]) == Decimal("4818.26")
    apply_investment_event(
        snap,
        {"record_type": "ipo", "account_name": "A", "currency": "HKD", "date": "2026-06-01",
         "from_ticker": "", "from_amount": "0", "to_ticker": "hkd", "to_amount": "5181.74"},
        default_currency="HKD",
        base_tickers={"hkd", "usd"},
    )
    assert Decimal(snap["accounts"]["security"]["A"]["positions"]["hkd"]["shares"]) == Decimal("10000")
