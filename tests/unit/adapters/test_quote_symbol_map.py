"""Symbol mapping unit tests (no network)."""
import pytest

from ft.adapters.market_data import map_security_symbol
from ft.application.valuation import UnsupportedQuote
from ft.domain.valuation import ledger_security_to_yfinance


def test_map_us_hk_cn():
    assert map_security_symbol("aapl.us") == "AAPL"
    assert map_security_symbol("0700.hk") == "0700.HK"
    assert map_security_symbol("00700.hk") == "0700.HK"
    assert map_security_symbol("600519.sh") == "600519.SS"
    assert map_security_symbol("159740.sz") == "159740.SZ"


def test_map_rejects_crypto_and_pm():
    with pytest.raises(UnsupportedQuote):
        map_security_symbol("btc")
    with pytest.raises(UnsupportedQuote):
        map_security_symbol("pm:slug:yes")
    assert ledger_security_to_yfinance("btc") is None
