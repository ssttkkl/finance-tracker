"""Unit tests for investment event replay logic.

Constitution III: Test-first - validate apply_investment_event() behavior.
"""
import pytest
from decimal import Decimal

from ft.domain.investment_projection import apply_investment_event


def test_cash_to_cash_swap_keeps_each_fiat_pocket_in_its_native_cost_currency():
    snapshot = {"accounts": {"security": {"盈立证券": {"currency": "USD", "positions": {}}}}}
    apply_investment_event(snapshot, {
        "date": "2026-06-01", "action": "deposit", "account_name": "盈立证券",
        "currency": "HKD", "to_ticker": "hkd", "to_amount": "5181.74",
    }, default_currency="USD")
    apply_investment_event(snapshot, {
        "date": "2026-06-16", "action": "swap", "account_name": "盈立证券",
        "currency": "HKD", "from_ticker": "hkd", "from_amount": "3161.18",
        "to_ticker": "usd", "to_amount": "402.32", "commission": "0",
    }, default_currency="USD")
    apply_investment_event(snapshot, {
        "date": "2026-06-17", "action": "deposit", "account_name": "盈立证券",
        "currency": "USD", "to_ticker": "usd", "to_amount": "10",
    }, default_currency="USD")

    positions = snapshot["accounts"]["security"]["盈立证券"]["positions"]
    assert Decimal(positions["hkd"]["shares"]) == Decimal("2020.56")
    assert Decimal(positions["hkd"]["total_cost"]) == Decimal("2020.56")
    assert positions["hkd"]["cost_currency"] == "HKD"
    assert Decimal(positions["usd"]["shares"]) == Decimal("412.32")
    assert Decimal(positions["usd"]["total_cost"]) == Decimal("412.32")
    assert positions["usd"]["cost_currency"] == "USD"


def test_swap_buy_cash_to_ticker():
    """SWAP from cash to ticker (buy) should increase position and decrease cash."""
    snapshot = {
        "accounts": {
            "security": {
                "broker": {
                    "currency": "CNY",
                    "positions": {
                        "cny": {"shares": "10000.00", "total_cost": "10000.00", "cost_currency": "CNY"}
                    }
                }
            }
        }
    }

    event = {
        "date": "2026-06-12 00:00:00",
        "action": "swap",
        "account_name": "broker",
        "from_ticker": "cny",
        "from_amount": "1251.00",
        "to_ticker": "600000.sh",
        "to_amount": "100",
        "price": "12.50",
        "commission": "1.00",
        "commission_asset": "cny",
        "currency": "CNY",
    }

    apply_investment_event(snapshot, event, default_currency="CNY")

    positions = snapshot["accounts"]["security"]["broker"]["positions"]
    # Commission deducted from source (cny): 10000 - 1251 - 1 = 8748
    assert Decimal(positions["cny"]["shares"]) == Decimal("8748.00")
    assert Decimal(positions["600000.sh"]["shares"]) == Decimal("100")
    # Target cost includes commission when commission_asset == from_ticker
    assert Decimal(positions["600000.sh"]["total_cost"]) == Decimal("1252.00")  # 1251 + 1 commission


def test_swap_sell_ticker_to_cash():
    """SWAP from ticker to cash (sell) should decrease position and increase cash."""
    snapshot = {
        "accounts": {
            "security": {
                "broker": {
                    "currency": "CNY",
                    "positions": {
                        "cny": {"shares": "8749.00", "total_cost": "8749.00", "cost_currency": "CNY"},
                        "600000.sh": {"shares": "100", "total_cost": "1251.00", "cost_currency": "CNY"}
                    }
                }
            }
        }
    }

    event = {
        "date": "2026-06-15 00:00:00",
        "action": "swap",
        "account_name": "broker",
        "from_ticker": "600000.sh",
        "from_amount": "50",
        "to_ticker": "cny",
        "to_amount": "650.00",
        "price": "13.00",
        "commission": "1.00",
        "commission_asset": "cny",
        "currency": "CNY",
    }

    apply_investment_event(snapshot, event, default_currency="CNY")

    positions = snapshot["accounts"]["security"]["broker"]["positions"]
    # Released cost: 1251 * 50 / 100 = 625.50
    assert Decimal(positions["600000.sh"]["shares"]) == Decimal("50")
    assert Decimal(positions["600000.sh"]["total_cost"]) == Decimal("625.50")  # 1251 - 625.5
    # Cash: 8749 + 650 - 1 = 9398 (proceeds minus commission)
    assert Decimal(positions["cny"]["shares"]) == Decimal("9398.00")


def test_swap_crypto_to_crypto():
    """SWAP from crypto to crypto should transfer cost basis."""
    snapshot = {
        "accounts": {
            "security": {  # apply_investment_event always uses "security" key
                "binance": {
                    "currency": "USDT",
                    "positions": {
                        "usdt": {"shares": "5000.00", "total_cost": "5000.00", "cost_currency": "USDT"},
                        "btc": {"shares": "0.1", "total_cost": "5000.00", "cost_currency": "USDT"}
                    }
                }
            }
        }
    }

    event = {
        "date": "2026-06-20 00:00:00",
        "action": "swap",
        "account_name": "binance",
        "from_ticker": "btc",
        "from_amount": "0.05",
        "to_ticker": "eth",
        "to_amount": "1.5",
        "commission": "0",
        "currency": "USDT",
    }

    apply_investment_event(snapshot, event, default_currency="USDT")

    positions = snapshot["accounts"]["security"]["binance"]["positions"]
    # Released cost: 5000 * 0.05 / 0.1 = 2500
    assert Decimal(positions["btc"]["shares"]) == Decimal("0.05")
    assert Decimal(positions["btc"]["total_cost"]) == Decimal("2500.00")
    assert Decimal(positions["eth"]["shares"]) == Decimal("1.5")
    assert Decimal(positions["eth"]["total_cost"]) == Decimal("2500.00")  # transferred cost


def test_swap_with_third_party_commission():
    """SWAP with commission in third asset (e.g., BNB) should deduct from that position."""
    snapshot = {
        "accounts": {
            "security": {  # apply_investment_event always uses "security" key
                "binance": {
                    "currency": "USDT",
                    "positions": {
                        "usdt": {"shares": "5000.00", "total_cost": "5000.00", "cost_currency": "USDT"},
                        "btc": {"shares": "0", "total_cost": "0", "cost_currency": "USDT"},
                        "bnb": {"shares": "10", "total_cost": "3000.00", "cost_currency": "USDT"}
                    }
                }
            }
        }
    }

    event = {
        "date": "2026-06-20 00:00:00",
        "action": "swap",
        "account_name": "binance",
        "from_ticker": "usdt",
        "from_amount": "5000.00",
        "to_ticker": "btc",
        "to_amount": "0.1",
        "commission": "0.01",
        "commission_asset": "bnb",
        "currency": "USDT",
    }

    apply_investment_event(snapshot, event, default_currency="USDT")

    positions = snapshot["accounts"]["security"]["binance"]["positions"]
    assert Decimal(positions["usdt"]["shares"]) == Decimal("0")
    assert Decimal(positions["btc"]["shares"]) == Decimal("0.1")
    # Target is cash (usdt->btc), so target_cost = target_amount = 0.1 (in BTC shares, but cost is USDT equivalent)
    # Actually for non-cash target: target_cost = released + 0 (commission not from source)
    assert Decimal(positions["btc"]["total_cost"]) == Decimal("5000.00")
    assert Decimal(positions["bnb"]["shares"]) == Decimal("9.99")  # 10 - 0.01
    assert Decimal(positions["bnb"]["total_cost"]) == Decimal("2999.99")  # 3000 - 0.01


def test_deposit_increases_cash():
    """DEPOSIT action should increase cash position."""
    snapshot = {
        "accounts": {
            "security": {
                "broker": {
                    "currency": "CNY",
                    "positions": {
                        "cny": {"shares": "1000.00", "total_cost": "1000.00", "cost_currency": "CNY"}
                    }
                }
            }
        }
    }

    event = {
        "date": "2026-06-10 00:00:00",
        "action": "deposit",
        "account_name": "broker",
        "to_ticker": "cny",
        "to_amount": "10000.00",
        "currency": "CNY",
    }

    apply_investment_event(snapshot, event, default_currency="CNY")

    positions = snapshot["accounts"]["security"]["broker"]["positions"]
    assert Decimal(positions["cny"]["shares"]) == Decimal("11000.00")
    assert Decimal(positions["cny"]["total_cost"]) == Decimal("11000.00")


def test_withdraw_decreases_cash():
    """WITHDRAW action should decrease cash position."""
    snapshot = {
        "accounts": {
            "security": {
                "broker": {
                    "currency": "CNY",
                    "positions": {
                        "cny": {"shares": "10000.00", "total_cost": "10000.00", "cost_currency": "CNY"}
                    }
                }
            }
        }
    }

    event = {
        "date": "2026-06-15 00:00:00",
        "action": "withdraw",
        "account_name": "broker",
        "from_ticker": "cny",
        "from_amount": "5000.00",
        "currency": "CNY",
    }

    apply_investment_event(snapshot, event, default_currency="CNY")

    positions = snapshot["accounts"]["security"]["broker"]["positions"]
    assert Decimal(positions["cny"]["shares"]) == Decimal("5000.00")
    assert Decimal(positions["cny"]["total_cost"]) == Decimal("5000.00")


def test_dividend_increases_cash_no_cost():
    """DIVIDEND action should increase cash but not cost for cash dividends."""
    snapshot = {
        "accounts": {
            "security": {
                "broker": {
                    "currency": "CNY",
                    "positions": {
                        "cny": {"shares": "8749.00", "total_cost": "8749.00", "cost_currency": "CNY"},
                        "600000.sh": {"shares": "100", "total_cost": "1251.00", "cost_currency": "CNY"}
                    }
                }
            }
        }
    }

    event = {
        "date": "2026-06-20 00:00:00",
        "action": "dividend",
        "account_name": "broker",
        "from_ticker": "600000.sh",  # Source for audit
        "to_ticker": "cny",
        "to_amount": "120.00",
        "currency": "CNY",
    }

    apply_investment_event(snapshot, event, default_currency="CNY")

    positions = snapshot["accounts"]["security"]["broker"]["positions"]
    assert Decimal(positions["cny"]["shares"]) == Decimal("8869.00")  # 8749 + 120
    assert Decimal(positions["cny"]["total_cost"]) == Decimal("8869.00")  # Same (cost = amount for cash)
    # Stock position unchanged
    assert Decimal(positions["600000.sh"]["shares"]) == Decimal("100")
    assert Decimal(positions["600000.sh"]["total_cost"]) == Decimal("1251.00")


def test_checkin_replaces_position():
    """CHECKIN action should replace (not add to) position."""
    snapshot = {
        "accounts": {
            "security": {
                "broker": {
                    "currency": "CNY",
                    "positions": {
                        "cny": {"shares": "8000.00", "total_cost": "8000.00", "cost_currency": "CNY"},
                        "600000.sh": {"shares": "90", "total_cost": "1100.00", "cost_currency": "CNY"}
                    }
                }
            }
        }
    }

    event = {
        "date": "2026-06-30 00:00:00",
        "action": "checkin",
        "account_name": "broker",
        "to_ticker": "600000.sh",
        "to_amount": "100",
        "price": "12.50",
        "currency": "CNY",
    }

    apply_investment_event(snapshot, event, default_currency="CNY")

    positions = snapshot["accounts"]["security"]["broker"]["positions"]
    # Position replaced, not added
    assert Decimal(positions["600000.sh"]["shares"]) == Decimal("100")
    assert Decimal(positions["600000.sh"]["total_cost"]) == Decimal("1250.00")  # 100 * 12.50


def test_checkin_cash():
    """CHECKIN for cash should replace cash balance."""
    snapshot = {
        "accounts": {
            "security": {
                "broker": {
                    "currency": "CNY",
                    "positions": {
                        "cny": {"shares": "8749.00", "total_cost": "8749.00", "cost_currency": "CNY"}
                    }
                }
            }
        }
    }

    event = {
        "date": "2026-06-30 00:00:00",
        "action": "checkin",
        "account_name": "broker",
        "to_ticker": "cny",
        "to_amount": "9000.00",
        "price": "1",
        "currency": "CNY",
    }

    apply_investment_event(snapshot, event, default_currency="CNY")

    positions = snapshot["accounts"]["security"]["broker"]["positions"]
    assert Decimal(positions["cny"]["shares"]) == Decimal("9000.00")
    assert Decimal(positions["cny"]["total_cost"]) == Decimal("9000.00")


def test_event_creates_account_if_missing():
    """Events should create account structure if it doesn't exist."""
    snapshot = {"accounts": {}}

    event = {
        "date": "2026-06-12 00:00:00",
        "action": "deposit",
        "account_name": "new_broker",
        "to_ticker": "usd",
        "to_amount": "1000.00",
        "currency": "USD",
    }

    apply_investment_event(snapshot, event, default_currency="USD")

    assert "security" in snapshot["accounts"]
    assert "new_broker" in snapshot["accounts"]["security"]
    positions = snapshot["accounts"]["security"]["new_broker"]["positions"]
    assert Decimal(positions["usd"]["shares"]) == Decimal("1000.00")


def test_swap_allows_sell_without_prior_position():
    """Statement imports may sell shares not opened in the same file; allow soft start."""
    snapshot = {
        "accounts": {
            "security": {
                "broker": {
                    "currency": "CNY",
                    "positions": {
                        "600000.sh": {"shares": "50", "total_cost": "625.00", "cost_currency": "CNY"}
                    }
                }
            }
        }
    }

    event = {
        "date": "2026-06-15 00:00:00",
        "action": "swap",
        "account_name": "broker",
        "from_ticker": "600000.sh",
        "from_amount": "100",  # More than available
        "to_ticker": "cny",
        "to_amount": "1300.00",
        "commission": "0",
        "currency": "CNY",
    }

    apply_investment_event(snapshot, event, default_currency="CNY")
    pos = snapshot["accounts"]["security"]["broker"]["positions"]["600000.sh"]
    assert Decimal(str(pos["shares"])) == Decimal("-50")


def test_swap_zero_shares_and_zero_cost_edge():
    """Zero-quantity swap is a no-op that still creates structure."""
    snapshot = {"accounts": {}}
    event = {
        "date": "2026-01-01 00:00:00",
        "action": "swap",
        "account_name": "broker",
        "from_ticker": "cny",
        "from_amount": "0",
        "to_ticker": "600000.sh",
        "to_amount": "0",
        "commission": "0",
        "currency": "CNY",
    }
    apply_investment_event(snapshot, event, default_currency="CNY")
    pos = snapshot["accounts"]["security"]["broker"]["positions"]
    assert Decimal(pos["600000.sh"]["shares"]) == Decimal("0")
    assert Decimal(pos["cny"]["shares"]) == Decimal("0")


def test_deposit_creates_missing_cash_position():
    snapshot = {"accounts": {}}
    apply_investment_event(
        snapshot,
        {
            "date": "2026-01-01 00:00:00",
            "action": "deposit",
            "account_name": "broker",
            "to_ticker": "usd",
            "to_amount": "100",
            "currency": "USD",
        },
        default_currency="USD",
    )
    assert Decimal(snapshot["accounts"]["security"]["broker"]["positions"]["usd"]["shares"]) == Decimal("100")


def test_transfer_is_auditable_noop_for_positions():
    snapshot = {"accounts": {"security": {"exchange": {"currency": "USD", "positions": {
        "usd": {"shares": "100", "total_cost": "100", "cost_currency": "USD"},
    }}}}}
    apply_investment_event(snapshot, {
        "date": "2026-07-26", "action": "transfer", "account_name": "exchange",
        "from_ticker": "usd", "from_amount": "20", "currency": "USD",
    }, default_currency="USD")
    assert snapshot["accounts"]["security"]["exchange"]["positions"]["usd"]["shares"] == "100"
