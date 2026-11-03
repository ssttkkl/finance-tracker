"""Export real Python CCXT loopback requests and complete parsed outcomes."""
from __future__ import annotations

import argparse
from copy import deepcopy
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
from threading import Thread
from urllib.parse import urlsplit

import ccxt
from ft.adapters.connectors.ccxt_exchange import CcxtExchangeConnector

STAMP = 1700000000123
OPTIONS = dict(apiKey="synthetic-key", secret="c3ludGhldGljLXNlY3JldA==", password="synthetic-pass", enableRateLimit=False, timeout=2000)
MARKET = dict(id="XBTUSD", altname="XBTUSD", symbol="BTC/USD", base="BTC", quote="USD", baseId="XXBT", quoteId="ZUSD", type="spot", spot=True, swap=False, future=False, option=False, margin=False, contract=False, linear=None, inverse=None, precision=dict(amount=8, price=2), limits={}, info={})
CURRENCIES = dict(BTC=dict(id="XXBT", code="BTC"), USD=dict(id="ZUSD", code="USD"))
TRADE = dict(ordertxid="o", pair="XBTUSD", time=1700000000, type="buy", ordertype="limit", price="100.123456789012345678", vol="2", cost="200.246913578024691356", fee="0.001", extra=dict(flag=True, absent=None, numbers=[1, 2.5]))
LEDGER = dict(refid="r", time=1700000000, type="deposit", asset="XXBT", amount="2", fee="0.1", balance="5")


def kraken(trades=None, ledger=None):
    return {"/0/private/TradesHistory": [dict(error=[], result=dict(trades=trades or {}, count=100))],
            "/0/private/Ledgers": [dict(error=[], result=dict(ledger=ledger or {}, count=100))]}


def scenarios():
    yield dict(name="kraken-complete", provider="kraken", mode="connector", responses=kraken({"t0": TRADE}, {"l0": LEDGER}))
    pages = kraken({f"t{i}": TRADE for i in range(50)})
    pages["/0/private/Ledgers"] = [dict(error=[], result=dict(ledger={f"l{i}": LEDGER for i in range(50)}, count=51)), dict(error=[], result=dict(ledger={"l50": LEDGER}, count=51))]
    yield dict(name="kraken-original-pagination", provider="kraken", mode="connector", since="1699999999999", responses=pages)
    cold = kraken({"t0": TRADE})
    cold["/0/public/Assets"] = [dict(error=[], result=dict(XXBT=dict(aclass="currency", altname="XBT", decimals=8, display_decimals=8), ZUSD=dict(aclass="currency", altname="USD", decimals=2, display_decimals=2)))]
    cold["/0/public/AssetPairs"] = [dict(error=[], result=dict(XBTUSD=dict(altname="XBTUSD", wsname="XBT/USD", base="XXBT", quote="ZUSD", pair_decimals=2, lot_decimals=8, lot_multiplier=1, fees=[[0, 0.26]], fees_maker=[[0, 0.16]], leverage_buy=[], leverage_sell=[], ordermin="0.0001", status="online")))]
    yield dict(name="kraken-cold-markets", provider="kraken", mode="trades", cold=True, responses=cold)
    yield dict(name="kraken-ledger-since", provider="kraken", mode="ledger", since="1699999999999", params=dict(ofs=50), responses=kraken(ledger={"l0": LEDGER}))
    okx = {"/api/v5/trade/fills-history": [dict(code="0", data=[])], "/api/v5/account/bills": [dict(code="0", data=[dict(billId="123", ccy="USD", ts="1700000000000", fee="0", balChg="2", bal="5", type="1")])]}
    yield dict(name="okx-numeric-type-rejected", provider="okx", mode="connector", responses=okx)
    yield dict(name="okx-ledger-since-local", provider="okx", mode="ledger", since="1699999999999", responses=okx)
    yield dict(name="okx-trades-since", provider="okx", mode="trades", since=str(STAMP), responses=okx)
    yield dict(name="binance-default-spot-trades", provider="binance", mode="trades", responses={})
    yield dict(name="binance-default-spot-ledger", provider="binance", mode="ledger", responses={})
    yield dict(name="binance-cold-default-spot", provider="binance", mode="trades", cold=True, responses={"/api/v3/exchangeInfo": [dict(timezone="UTC", serverTime=STAMP, rateLimits=[], exchangeFilters=[], symbols=[])], "/api/v3/margin/allPairs": [[]], "/api/v3/margin/isolated/allPairs": [[]]})
    yield dict(name="binance-signature-request", provider="binance", mode="signed_request", responses={"/api/v3/myTrades": [[]]})
    yield dict(name="kraken-authentication-error", provider="kraken", mode="connector", responses={"/0/private/TradesHistory": [dict(error=["EAPI:Invalid key"])]})
    yield dict(name="kraken-http-503", provider="kraken", mode="trades", status=503, responses={"/0/private/TradesHistory": [dict(error=[])]})


def export(output):
    result = []
    for original in scenarios():
        case = deepcopy(original); calls = []; queues = deepcopy(case["responses"])
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_GET(self): self.respond()
            def do_POST(self): self.respond()
            def respond(self):
                path = urlsplit(self.path).path
                body = self.rfile.read(int(self.headers.get("Content-Length", "0"))).decode() or None
                headers = {key.lower(): value for key, value in self.headers.items() if key.lower() in {"api-key", "api-sign", "x-mbx-apikey", "ok-access-key", "ok-access-sign", "ok-access-timestamp", "ok-access-passphrase"}}
                calls.append(dict(path=self.path, method=self.command, body=body, headers=headers))
                queue = queues.get(path)
                if not queue:
                    status = 500; payload = dict(unexpected_endpoint=path)
                else:
                    status = case.get("status", 200); payload = queue.pop(0)
                data = json.dumps(payload, separators=(",", ":")).encode(); self.send_response(status); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = Thread(target=server.serve_forever, daemon=True); thread.start()
        client = getattr(ccxt, case["provider"])(deepcopy(OPTIONS))
        client.nonce = lambda: STAMP; client.milliseconds = lambda: STAMP
        client.incrementing_nonce = lambda: STAMP
        base = f"http://127.0.0.1:{server.server_port}"
        api = client.urls["api"]
        client.urls["api"] = {key: base + ("/api/v3" if case["provider"] == "binance" else "") for key in api} if isinstance(api, dict) else base
        # Only the original spot market load is relevant to the cold Binance route.
        if case["provider"] == "binance":
            client.options["fetchMarkets"] = dict(types=["spot"]); client.has["fetchCurrencies"] = False
        if not case.get("cold"): client.set_markets([deepcopy(MARKET)], deepcopy(CURRENCIES))
        try:
            since = int(case["since"]) if case.get("since") else None
            if case["mode"] == "connector":
                fetched = CcxtExchangeConnector(provider=case["provider"], credentials={}, _client=client).fetch_trades(since=case.get("since"))
                expected = dict(events=[{key: value.isoformat().replace("+00:00", "Z") if isinstance(value, datetime) else value for key, value in event.items()} for event in fetched.events], next_cursor=fetched.next_cursor, raw_count=fetched.raw_count)
            elif case["mode"] == "trades": expected = client.fetch_my_trades(None, since, 500)
            elif case["mode"] == "ledger": expected = client.fetch_ledger(None, since, 500, case.get("params", {}))
            else: expected = client.private_get_mytrades(dict(symbol="BTCUSDT", limit=500))
            case["expected"] = expected
        except Exception as error:
            case["expected"] = dict(error=type(error).__name__, message=str(error).replace(base, "<origin>"))
        finally:
            client.session.close(); server.shutdown(); server.server_close(); thread.join()
        case["calls"] = calls; result.append(case)
    output.write_text(json.dumps(dict(version=ccxt.__version__, market=MARKET, currencies=CURRENCIES, cases=result), ensure_ascii=True, indent=2) + "\n")
    print(f"{len(result)} real Python SDK loopback cases; ccxt {ccxt.__version__}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    export(parser.parse_args().output)
