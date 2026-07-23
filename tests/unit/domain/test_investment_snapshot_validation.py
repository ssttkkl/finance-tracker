"""Unit tests for investment snapshot validation.

Constitution III: Test-first - these tests MUST fail before implementation.
"""
import pytest
from decimal import Decimal

from ft.domain.investment_validation import validate_investment_snapshot


def test_validate_accepts_finite_positive_values():
    """Valid snapshot with positive finite values should pass."""
    snapshot = {
        "accounts": {
            "security": {
                "东方证券": {
                    "currency": "CNY",
                    "positions": {
                        "cny": {"shares": "10000.00", "total_cost": "10000.00", "cost_currency": "CNY"},
                        "600000.sh": {"shares": "100", "total_cost": "1250.00", "cost_currency": "CNY"},
                    }
                }
            }
        }
    }
    # Should not raise
    validate_investment_snapshot(snapshot)


def test_validate_accepts_negative_shares():
    """Short positions (negative shares) should be allowed."""
    snapshot = {
        "accounts": {
            "security": {
                "broker": {
                    "currency": "USD",
                    "positions": {
                        "tsla": {"shares": "-10", "total_cost": "-2500.00", "cost_currency": "USD"},
                    }
                }
            }
        }
    }
    # Should not raise
    validate_investment_snapshot(snapshot)


def test_validate_accepts_zero_values():
    """Zero shares and cost should be allowed."""
    snapshot = {
        "accounts": {
            "security": {
                "broker": {
                    "currency": "USD",
                    "positions": {
                        "tsla": {"shares": "0", "total_cost": "0", "cost_currency": "USD"},
                    }
                }
            }
        }
    }
    # Should not raise
    validate_investment_snapshot(snapshot)


def test_validate_rejects_nan_shares():
    """NaN shares should be rejected."""
    snapshot = {
        "accounts": {
            "security": {
                "broker": {
                    "currency": "USD",
                    "positions": {
                        "tsla": {"shares": str(Decimal("NaN")), "total_cost": "2500.00", "cost_currency": "USD"},
                    }
                }
            }
        }
    }
    with pytest.raises(ValueError, match="non-finite shares"):
        validate_investment_snapshot(snapshot)


def test_validate_rejects_infinity_shares():
    """Infinity shares should be rejected."""
    snapshot = {
        "accounts": {
            "security": {
                "broker": {
                    "currency": "USD",
                    "positions": {
                        "tsla": {"shares": str(Decimal("Infinity")), "total_cost": "2500.00", "cost_currency": "USD"},
                    }
                }
            }
        }
    }
    with pytest.raises(ValueError, match="non-finite shares"):
        validate_investment_snapshot(snapshot)


def test_validate_rejects_negative_infinity_shares():
    """-Infinity shares should be rejected."""
    snapshot = {
        "accounts": {
            "security": {
                "broker": {
                    "currency": "USD",
                    "positions": {
                        "tsla": {"shares": str(Decimal("-Infinity")), "total_cost": "2500.00", "cost_currency": "USD"},
                    }
                }
            }
        }
    }
    with pytest.raises(ValueError, match="non-finite shares"):
        validate_investment_snapshot(snapshot)


def test_validate_rejects_nan_total_cost():
    """NaN total_cost should be rejected."""
    snapshot = {
        "accounts": {
            "security": {
                "broker": {
                    "currency": "USD",
                    "positions": {
                        "tsla": {"shares": "10", "total_cost": str(Decimal("NaN")), "cost_currency": "USD"},
                    }
                }
            }
        }
    }
    with pytest.raises(ValueError, match="non-finite total_cost"):
        validate_investment_snapshot(snapshot)


def test_validate_rejects_infinity_total_cost():
    """Infinity total_cost should be rejected."""
    snapshot = {
        "accounts": {
            "security": {
                "broker": {
                    "currency": "USD",
                    "positions": {
                        "tsla": {"shares": "10", "total_cost": str(Decimal("Infinity")), "cost_currency": "USD"},
                    }
                }
            }
        }
    }
    with pytest.raises(ValueError, match="non-finite total_cost"):
        validate_investment_snapshot(snapshot)


def test_validate_empty_snapshot():
    """Empty snapshot should pass."""
    snapshot = {"accounts": {}}
    # Should not raise
    validate_investment_snapshot(snapshot)


def test_validate_crypto_accounts():
    """Crypto accounts should be validated with same rules."""
    snapshot = {
        "accounts": {
            "crypto": {
                "binance": {
                    "currency": "USDT",
                    "positions": {
                        "btc": {"shares": "0.12345678", "total_cost": "5000.00", "cost_currency": "USDT"},
                        "usdt": {"shares": "10000.00", "total_cost": "10000.00", "cost_currency": "USDT"},
                    }
                }
            }
        }
    }
    # Should not raise
    validate_investment_snapshot(snapshot)


def test_validate_crypto_rejects_nan():
    """Crypto positions with NaN should be rejected."""
    snapshot = {
        "accounts": {
            "crypto": {
                "binance": {
                    "currency": "USDT",
                    "positions": {
                        "btc": {"shares": str(Decimal("NaN")), "total_cost": "5000.00", "cost_currency": "USDT"},
                    }
                }
            }
        }
    }
    with pytest.raises(ValueError, match="non-finite shares"):
        validate_investment_snapshot(snapshot)


def test_validate_includes_account_and_ticker_in_error():
    """Error messages should include account name and ticker for debugging."""
    snapshot = {
        "accounts": {
            "security": {
                "东方证券": {
                    "currency": "CNY",
                    "positions": {
                        "600000.sh": {"shares": str(Decimal("NaN")), "total_cost": "1250.00", "cost_currency": "CNY"},
                    }
                }
            }
        }
    }
    with pytest.raises(ValueError) as exc_info:
        validate_investment_snapshot(snapshot)

    error_msg = str(exc_info.value)
    assert "600000.sh" in error_msg
    assert "东方证券" in error_msg
