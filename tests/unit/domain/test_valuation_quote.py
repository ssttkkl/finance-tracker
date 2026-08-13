"""Domain tests for real-time valuation helpers."""
from datetime import datetime, timedelta, timezone
from decimal import Decimal

import pytest

from ft.domain.valuation import (
    AssetKind,
    QuoteStatus,
    ValuationError,
    compute_market_value,
    infer_asset_kind,
    ledger_security_to_yfinance,
    make_asset_ref,
    quote_freshness,
)


def test_cash_asset_ref_and_market_value():
    ref = make_asset_ref("usd", "cash", quantity="10")
    assert ref.kind is AssetKind.CASH
    assert compute_market_value(Decimal("1"), ref.quantity) == Decimal("10")


def test_freshness_security_and_crypto():
    now = datetime(2026, 7, 25, tzinfo=timezone.utc)
    assert quote_freshness(now - timedelta(days=4), now=now, kind=AssetKind.SECURITY) is QuoteStatus.COMPLETE
    assert quote_freshness(now - timedelta(days=6), now=now, kind=AssetKind.SECURITY) is QuoteStatus.STALE
    assert quote_freshness(now - timedelta(days=31), now=now, kind=AssetKind.SECURITY) is QuoteStatus.PARTIAL
    assert quote_freshness(now - timedelta(hours=12), now=now, kind=AssetKind.CRYPTO) is QuoteStatus.COMPLETE
    assert quote_freshness(now - timedelta(hours=36), now=now, kind=AssetKind.CRYPTO) is QuoteStatus.STALE
    assert quote_freshness(now - timedelta(days=8), now=now, kind=AssetKind.CRYPTO) is QuoteStatus.PARTIAL


def test_invalid_quantity_fail_closed():
    with pytest.raises(ValuationError) as exc:
        make_asset_ref("aapl.us", "security", quantity="NaN")
    assert exc.value.code == "valuation.invalid_quantity"


def test_symbol_map_and_kind_infer():
    assert ledger_security_to_yfinance("aapl.us") == "AAPL"
    assert ledger_security_to_yfinance("00700.hk") == "0700.HK"
    assert ledger_security_to_yfinance("600519.sh") == "600519.SS"
    assert ledger_security_to_yfinance("2330.tw") == "2330.TW"
    assert ledger_security_to_yfinance("7203.jp") == "7203.T"
    assert ledger_security_to_yfinance("005930.ks") == "005930.KS"
    assert infer_asset_kind("usd", cash_tickers={"USD"}) is AssetKind.CASH
    assert infer_asset_kind("btc") is AssetKind.CRYPTO
    assert infer_asset_kind("pm:election:yes") is AssetKind.PREDICTION_MARKET
    assert infer_asset_kind("aapl.us") is AssetKind.SECURITY
