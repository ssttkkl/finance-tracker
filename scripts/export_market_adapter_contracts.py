"""Export actual Python market adapter results and requests from anonymous inputs."""
from __future__ import annotations

import argparse
from datetime import datetime
from hashlib import sha256
import json
from pathlib import Path
from unittest.mock import patch

from export_valuation_contracts import normalize
from ft.adapters import market_data as market
from ft.application.valuation import UnsupportedQuote
from ft.domain.valuation import AssetKind, AssetRef

NOW = datetime.fromisoformat("2026-08-12T15:42:31+00:00")


def normal(value):
    if isinstance(value, Exception):
        return {"error": "unsupported" if isinstance(value, UnsupportedQuote) else "provider_error"}
    if isinstance(value, dict):
        return {key: normal(item) for key, item in value.items()}
    return normalize(value)


def cases():
    result = []
    def add(provider, payloads, identity="aapl.us", kind="security", **kwargs):
        result.append({"name": f"{provider}_{len(result):03d}", "provider": provider, "payloads": payloads,
            "identity": identity, "kind": kind, **kwargs})
    prices = [None, 0, -0.0, 1, 1.25, "100.00", "NaN", "Infinity", "-1.25", "1e3", " $1,234.50 ", True, {}, []]
    for price in prices:
        add("yahoo", [{"chart": {"result": [{"meta": {"regularMarketPrice": price, "regularMarketTime": 1786546800, "currency": "usd"}}]}}])
        add("cboe", [{"data": {"current_price": price}, "timestamp": "2026-08-12 15:00:00"}])
        add("nasdaq", [{"data": {"primaryData": {"lastSalePrice": price, "lastTradeTimestamp": "Aug 12, 2026"}}}])
        add("finnhub", [{"c": price, "t": 1786546800}], key="anonymous test/key+only")
        add("crypto", [{"bitcoin": {"usd": price}}], "btc", "crypto")
    for stamp in [None, 0, 1.5, "1786546800", "1786546800.5", True, "bad", 253402300800, -62135596801, "１２３"]:
        add("finnhub", [{"c": "101.00", "t": stamp}], key="anonymous")
    for stamp in ["0000-01-01 00:00:00", "2026-8-2 1:2:3", "2026-02-30 00:00:00", "2026-08-12 15:00:00Z", None]:
        add("cboe", [{"data": {"current_price": " $1,000.25 "}, "timestamp": stamp}])
    for stamp in ["aug 12, 2026", "AUG  2, 2026", "Aug 02, 2026", "Aug\t2, 2026", "Aug 12, 0000", "Aug 12, 2026 15:00", None]:
        add("nasdaq", [{"data": {"primaryData": {"lastSalePrice": "$101.20", "lastTradeTimestamp": stamp}}}])
    for provider in ["yahoo", "cboe", "nasdaq", "finnhub"]:
        for identity, kind in [("00700.hk", "security"), ("bad?symbol", "security"), ("btc", "crypto")]:
            add(provider, [{}], identity, kind, key="anonymous")
        for body in [None, [], "text", {"data": 1}, {"chart": {"result": []}}]:
            add(provider, [body], key="anonymous")
        add(provider, ["HTTP_ERROR"], key="anonymous")
    add("finnhub", [{}], key="")
    for meta in [
        {"regularMarketPrice": 100, "regularMarketTime": 1786546800, "postMarketPrice": 101, "postMarketTime": 1786564800},
        {"regularMarketPrice": 100, "regularMarketTime": 1786546800, "postMarketPrice": 101, "postMarketTime": 1786546800},
        {"preMarketPrice": "1.200", "preMarketTime": "1786536000", "currency": "hkd"},
        {"regularMarketPrice": 100, "regularMarketTime": 1786546800, "currency": ""}]:
        add("yahoo", [{"chart": {"result": [{"meta": meta}]}}])
    for body in [None, [], "text", {"bitcoin": 3}, {"bitcoin": None}, "HTTP_ERROR"]:
        add("crypto", [body], " BTC ", "crypto")
    for identity in ["btc", "eth", "sol", "usdt", "usdc", "bnb", "xrp", "doge", "ada", "unknown"]:
        add("crypto", [{}], identity, "crypto")
    refs = [{"identity": x, "kind": k} for x, k in [(" BTC ", "crypto"), ("btc", "crypto"), ("eth", "crypto"), ("usd", "cash"), ("unknown", "crypto")]]
    for body in [{"bitcoin": {"usd": "100.00"}}, "HTTP_ERROR", None]:
        add("crypto", [body], operation="batch", refs=refs, timeout=0.5)
    add("cash", [], " usd ", "cash")
    add("cash", [], "美元", "cash")
    add("cash", [], "btc", "crypto")
    for outcomes, values in [(["Yes", "No"], ["0.60", "0.40"]), ('["No", "Yes"]', '["0.4", "0.6"]'),
        (["a", "b"], [1, 0]), ([], []), ([], [1]), (None, [0.5]), ([], None), (1, [0.5]),
        ([], "not-json"), ({"yes": 1}, ["1.000"]), ([], ["NaN", "0.2"]), ([], [-0.0, 1.25])]:
        for side in ["yes", "no"]:
            add("prediction", [[{"slug": "fixture-event", "outcomes": outcomes, "outcomePrices": values}]], f"pm:fixture-event:{side}", "prediction_market")
    item = {"slug": "fixture-event", "outcomes": ["Yes", "No"], "outcomePrices": ["0.6", "0.4"]}
    for body in [{"data": [item]}, {"markets": [item]}, item, [], None, {"data": 1}]:
        add("prediction", [body, {"events": [{"markets": [item]}]}], "pm:fixture-event:yes", "prediction_market", timeout=0.5)
    add("prediction", ["HTTP_ERROR", "HTTP_ERROR"], "pm:fixture-event:yes", "prediction_market")
    add("prediction", [[item]], "pm:fixture-event:yes", "prediction_market", timeout=0)
    add("prediction", [], "bad", "prediction_market")
    add("prediction", [], "aapl.us", "security")
    return result


def run(case):
    requests = []
    def fetch(url, *, timeout=None):
        index = len(requests)
        requests.append({"url": url, "timeout": timeout})
        body = case["payloads"][min(index, len(case["payloads"]) - 1)]
        if body == "HTTP_ERROR":
            raise OSError("SYNTHETIC_PROVIDER_SECRET")
        return body
    providers = {
        "yahoo": lambda: market.YahooChartQuoteProvider(fetch_json=fetch),
        "cboe": lambda: market.CboeQuoteProvider(fetch_json=fetch),
        "nasdaq": lambda: market.NasdaqQuoteProvider(fetch_json=fetch),
        "finnhub": lambda: market.FinnhubQuoteProvider(fetch_json=fetch, api_key=case.get("key", "")),
        "crypto": lambda: market.CryptoQuoteProvider(fetch_json=fetch, clock=lambda: NOW),
        "prediction": lambda: market.PredictionMarketQuoteProvider(fetch_json=fetch, clock=lambda: NOW),
        "cash": market.CashQuoteProvider,
    }
    provider = providers[case["provider"]]()
    with patch.object(market, "_now", lambda: NOW), patch.object(market.time, "monotonic", lambda: 0):
        try:
            if case.get("operation") == "batch":
                value = provider.raw_quote_many([AssetRef(r["identity"], AssetKind(r["kind"])) for r in case["refs"]], timeout=case.get("timeout"))
            elif case["provider"] in ("crypto", "cash"):
                value = provider.raw_quote(case["identity"], AssetKind(case["kind"]))
            else:
                value = provider.raw_quote(case["identity"], AssetKind(case["kind"]), timeout=case.get("timeout"))
        except Exception as failure:
            value = failure
    return {**case, "expected": normal(value), "requests": requests}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if not args.output.is_absolute() or args.output.resolve().is_relative_to(root):
        raise ValueError("Use an external anonymous output")
    values = [run(case) for case in cases()]
    with args.output.open("x") as stream:
        json.dump({"cases": values, "sources_sha256": {"src/ft/adapters/market_data.py": sha256((root / "src/ft/adapters/market_data.py").read_bytes()).hexdigest()}}, stream, indent=2)
        stream.write("\n")
    print("Exported", len(values), "actual original adapter cases")
