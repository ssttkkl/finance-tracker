"""Export anonymous Yahoo chart fixtures through the installed original Python SDK and adapters."""
from __future__ import annotations

import argparse
from copy import deepcopy
from datetime import datetime, timezone
from hashlib import sha256
from importlib.metadata import distribution, version
import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
import warnings

from export_valuation_contracts import normalize
from ft.adapters.market_data import SecurityQuoteProvider


def epoch(hour, minute=0):
    return int(datetime(2026, 8, 12, hour, minute, tzinfo=timezone.utc).timestamp())


def payload(symbol="AAPL", prices=None, times=None, zone="America/New_York", start=None, end=None):
    prices = [100, 101] if prices is None else prices
    times = [epoch(14), epoch(15)] if times is None else times
    start, end = epoch(13, 30) if start is None else start, epoch(20) if end is None else end
    period = {"start": start, "end": end, "gmtoffset": -14400, "timezone": "EDT"}
    return {"chart": {"result": [{"meta": {
        "currency": "USD", "symbol": symbol, "instrumentType": "EQUITY", "exchangeName": "NMS",
        "exchangeTimezoneName": zone, "timezone": "EDT", "gmtoffset": -14400, "regularMarketPrice": 101,
        "regularMarketTime": times[-1], "priceHint": 2, "validRanges": ["1d", "5d"],
        "tradingPeriods": [[period]], "currentTradingPeriod": {"regular": dict(period)}},
        "timestamp": times, "indicators": {
            "quote": [{"open": list(prices), "high": list(prices), "low": list(prices), "close": list(prices), "volume": [1] * len(prices)}],
            "adjclose": [{"adjclose": [1] * len(prices)}]}}], "error": None}}


def cases():
    result = []
    def add(name, bodies, *, history=None):
        result.append({"name": name, "payloads": bodies, "symbols": list(bodies), "history": history})
    add("raw_close_not_adjusted", {"AAPL": payload()})
    add("unordered_bars", {"AAPL": payload(prices=[102, 100, 101], times=[epoch(16), epoch(14), epoch(15)])})
    add("skip_missing_last_close", {"AAPL": payload(prices=[100, None])})
    add("all_close_missing", {"AAPL": payload(prices=[None, None])})
    add("integer_prices_keep_sdk_float_representation", {"AAPL": payload(prices=[100, 101])})
    add("precise_source_numbers_follow_sdk_float", {"AAPL": payload(prices=[12.123456789012345, 101.12345678901234])})
    add("numeric_strings_follow_sdk_float", {"AAPL": payload(prices=["100.00", "101.2500"])})
    add("drop_unrequested_postmarket", {"AAPL": payload(prices=[100, 999], times=[epoch(19, 55), epoch(20)])})
    add("drop_unrequested_premarket", {"AAPL": payload(prices=[999, 101], times=[epoch(13, 20), epoch(14)])})
    add("bar_overlaps_regular_start", {"AAPL": payload(prices=[99, 101], times=[epoch(13, 29), epoch(14)])})
    add("early_close_period", {"AAPL": payload(prices=[100, 999], times=[epoch(16, 55), epoch(17)], end=epoch(17))})
    add("same_interval_live_bar", {"AAPL": payload(prices=[100, 101], times=[epoch(14), epoch(14, 4)])})
    add("duplicate_bar_timestamp", {"AAPL": payload(prices=[100, 101], times=[epoch(14), epoch(14)])})
    add("two_symbols", {"AAPL": payload(), "MSFT": payload("MSFT", prices=[200.5, 201.75])})
    bad = payload()
    bad["chart"]["result"][0]["meta"].pop("tradingPeriods")
    add("missing_sdk_trading_periods", {"AAPL": bad})
    bad = payload()
    bad["chart"]["error"] = {"code": "Not Found", "description": "fixture only"}
    add("chart_error_rejects_otherwise_valid_bars", {"AAPL": bad})
    add("history_date_only_exact_boundary", {"AAPL": payload(prices=[100, 101], times=[epoch(14), epoch(15)])},
        history="2026-08-12T14:42:31.371430+00:00")
    add("history_first_bar_fallback", {"AAPL": payload(prices=[100, 101], times=[epoch(14), epoch(15)])},
        history="2026-08-12T13:42:31.371430+00:00")
    add("history_missing_last_bar", {"AAPL": payload(prices=[100, None], times=[epoch(14), epoch(15)])},
        history="2026-08-12T15:42:31.371430+00:00")
    add("sdk_nan_close_is_missing", {"AAPL": payload(prices=[100, "NaN"])})
    for missing in [0, None]:
        body = payload(prices=[100, missing])
        bar = body["chart"]["result"][0]
        bar["indicators"]["quote"][0]["volume"][-1] = 0
        bar["indicators"]["adjclose"][0]["adjclose"][-1] = missing
        add(f"empty_trailing_bar_{missing}", {"AAPL": body})
        if missing == 0:
            add("empty_historical_bar", {"AAPL": body}, history="2026-08-12T15:42:31.371430+00:00")
    body = payload(prices=[100, 0])
    body["chart"]["result"][0]["indicators"]["adjclose"][0]["adjclose"][-1] = 0
    add("zero_close_with_volume_remains_valid", {"AAPL": body})
    body = deepcopy(body)
    body["chart"]["result"][0]["indicators"]["quote"][0]["volume"][-1] = 0
    body["chart"]["result"][0]["indicators"]["quote"][0]["high"][-1] = 1
    add("zero_close_with_nonzero_high_remains_valid", {"AAPL": body})
    body = payload(prices=[100, 0], times=[epoch(14), epoch(14, 4)])
    body["chart"]["result"][0]["indicators"]["quote"][0]["volume"][-1] = 0
    body["chart"]["result"][0]["indicators"]["adjclose"][0]["adjclose"][-1] = 0
    add("merged_live_bar_keeps_previous_nonzero_ohlc", {"AAPL": body})
    add("historical_nan_close_stays_missing", {"AAPL": payload(prices=[100, "NaN"])}, history="2026-08-12T15:42:31.371430+00:00")
    return result


def export(output: Path, cache: Path):
    import yfinance as yf
    from yfinance.data import YfData
    yf.set_tz_cache_location(str(cache))
    results = []
    for case in cases():
        calls = []
        def get(self, *args, **kwargs):
            url = kwargs.get("url") or args[0]
            symbol = url.rsplit("/", 1)[-1]
            body = deepcopy(case["payloads"].get(symbol, {"chart": {"result": None, "error": {"code": "Not Found"}}}))
            calls.append({"url": url, "params": deepcopy(kwargs.get("params"))})
            return SimpleNamespace(status_code=200, text=json.dumps(body), json=lambda: deepcopy(body))
        with warnings.catch_warnings(), patch.object(YfData, "get", get), patch.object(YfData, "cache_get", get):
            warnings.simplefilter("ignore")
            if case["history"]:
                at = datetime.fromisoformat(case["history"])
                value = SecurityQuoteProvider._yfinance_price_at(case["symbols"][0], at)
            else:
                value = SecurityQuoteProvider._yfinance_quotes(case["symbols"], timeout=4.0)
        results.append({**case, "expected": normalize(value), "sdk_requests": calls})
    root = Path(__file__).resolve().parents[1]
    sdk = distribution("yfinance")
    sources = {name: sha256((root / name).read_bytes()).hexdigest() for name in
        ("src/ft/adapters/market_data.py", "src/ft/domain/valuation.py", "src/ft/application/valuation.py")}
    sdk_sources = {name: sha256(Path(sdk.locate_file(name)).read_bytes()).hexdigest() for name in
        ("yfinance/scrapers/history.py", "yfinance/utils.py", "yfinance/multi.py")}
    with output.open("x") as stream:
        json.dump({"cases": results, "sources_sha256": sources, "sdk_sources_sha256": sdk_sources,
            "yfinance_version": version("yfinance"), "pandas_version": version("pandas")}, stream, ensure_ascii=True, indent=2)
        stream.write("\n")
    print("Exported", len(results), "actual original SDK chart cases with no external HTTP")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cache", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if not args.output.is_absolute() or args.output.resolve().is_relative_to(root) or not args.cache.is_absolute() or args.cache.resolve().is_relative_to(root):
        raise ValueError("Use external output and dedicated anonymous SDK cache")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.cache.mkdir(parents=True, exist_ok=False)
    export(args.output, args.cache)

