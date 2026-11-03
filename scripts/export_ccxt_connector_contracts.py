"""Export original CCXT mapping, pagination and external failure outcomes."""
from __future__ import annotations

import argparse
from copy import deepcopy
from datetime import datetime
import json
from pathlib import Path
from unittest.mock import patch

from ft.adapters.connectors.ccxt_exchange import CcxtExchangeConnector


def export(output):
    fixture = json.loads(Path("tests/fixtures/ccxt_trades.json").read_text())
    base = fixture[0]
    ledger = dict(id="entry", timestamp=1000, currency="USD", amount="2", info={"type": "deposit"})
    inputs = [dict(trades=[fixture]), dict(trades=[[]]), dict(trades=[[fixture[0]], [fixture[1]], []], limit=1)]
    for key, values in {
        "id": [None, "", "  ", 0, False, " x ", []],
        "symbol": ["NOSLASH", "XBT/USDT", "XDG/USD", "xbtc/usd", "/USD", "BTC/", "A/B/C", None],
        "side": ["SELL", " buy ", "unknown", None, True],
        "amount": [None, "invalid", "NaN", "Infinity", "-0", "-2", "1_000", "１２.３", True, [], "1.23456789012345678901234567895", "1e-1000027"],
        "price": [None, "Infinity", "-2", "0.12345678901234567890123456789"],
        "cost": [None, "-2", "-0", "1.23456789012345678901234567895"],
        "fee": [None, {}, [], {"cost": None}, {"cost": "bad"}, {"cost": "-1"}, {"cost": "0", "currency": "XBT"},
            {"cost": "0.1"}, {"cost": "0.1", "currency": None}],
        "timestamp": [None, "bad", "1.5", [], True, -1, 1.9, "١٦٩٠٠٠٠٠٠٠٠٠٠", "1_690_000_000_001",
            -62135596800000, 253402300799999, 253402300800000, 123456789012345, -1234567890123],
    }.items():
        for value in values:
            inputs.append(dict(trades=[[dict(base, **{key: value})]]))
        row = dict(base); row.pop(key, None); inputs.append(dict(trades=[[row]]))
    for key, values in {
        "id": [None, "", "  ", 0], "currency": [None, "", "XBT", "XDG"],
        "amount": [None, "bad", "NaN", "-1", "-0", "1.23456789012345678901234567895"],
        "info": [None, {}, {"type": "1"}, {"type": "trade"}, {"type": " reward "}],
        "fee": [None, {}, [], {"cost": "bad"}, {"cost": "-1"}, {"cost": 0}, {"cost": "0.1"},
            {"cost": "0.1", "currency": None}, {"cost": "0.1", "currency": "XBT"}],
        "timestamp": [None, "bad", -1, 1.9, 253402300799999],
    }.items():
        for value in values: inputs.append(dict(ledger=[[dict(ledger, **{key: value})]]))
        row = dict(ledger); row.pop(key, None); inputs.append(dict(ledger=[[row]]))
    for kind in ["deposit", "withdrawal", "staking", "reward", "credit", "rollover", "transfer", "derivativescrossexchangetransfer", "trade", "unknown"]:
        inputs.append(dict(ledger=[[dict(ledger, info={"type": kind})]]))
    for since in ["", "0", "-1", "bad", "1_000", "١٠٠٠"]: inputs.append(dict(trades=[[base]], since=since))
    first = [dict(ledger, id=f"first-{i}", timestamp=i) for i in range(50)]
    inputs += [dict(provider="kraken", ledger=[first, [dict(ledger, id="last", timestamp=100)]]),
        dict(provider="kraken", trades=[[dict(base, id=str(i)) for i in range(50)], [base]]),
        dict(provider="kraken", ledger=[first, first]),
        dict(trades=[[base], [base]], limit=1),
        dict(ledger=[[ledger], [ledger]], limit=1),
        dict(trades=[[base, base]], ledger=[[dict(ledger, id=base["id"]), dict(ledger, id="ignored", info={"type": "trade"}, timestamp=1800000000000)]]),
        dict(trades=[[dict(base, timestamp=1001), dict(base, id="earlier", timestamp=1000)]], ledger=[[dict(ledger, fee={"cost": "0.1", "currency": "USD"})]]),
        dict(trades=[[dict(base, cost=None, price="0.12345678901234567890123456789", amount="12345678901234567890123456789")]])]
    for method in ["trades", "ledger"]:
        for name, message in [("Exception", "401 Unauthorized"), ("AuthenticationError", "denied"), ("ExchangeError", "400 Bad Request"),
            ("ExchangeError", "invalid request"), ("NetworkError", "timeout"), ("ArgumentsRequired", "symbol required"), ("NotSupported", "not supported")]:
            inputs.append(dict(failure=dict(method=method, type=name, message=message)))
    cases = []
    for case in inputs:
        calls = []; waits = []; trade_pages = deepcopy(case.get("trades", [[]])); ledger_pages = deepcopy(case.get("ledger", [[]]))
        class Client:
            def fetch_my_trades(self, symbol=None, since=None, limit=None):
                calls.append(["trades", since, limit, {}]); return self.page("trades", trade_pages)
            def fetch_ledger(self, code=None, since=None, limit=None, params=None):
                calls.append(["ledger", since, limit, params]); return self.page("ledger", ledger_pages)
            def page(self, method, pages):
                failure = case.get("failure", {})
                if failure.get("method") == method:
                    raise type(failure["type"], (Exception,), {})(failure["message"])
                return pages.pop(0) if pages else []
        try:
            with patch("ft.adapters.connectors.ccxt_exchange.time.sleep", side_effect=lambda seconds: waits.append(seconds * 1000)):
                result = CcxtExchangeConnector(provider=case.get("provider", "binance"), credentials={}, page_limit=case.get("limit", 500), _client=Client()).fetch_trades(since=case.get("since"))
            expected = dict(events=[{k: v.isoformat().replace("+00:00", "Z") if isinstance(v, datetime) else v for k, v in event.items()} for event in result.events], next_cursor=result.next_cursor, raw_count=result.raw_count)
        except Exception as error:
            expected = dict(error=type(error).__name__, message=str(error))
        cases.append(dict(input=case, expected=expected, calls=calls, waits=waits))
    output.write_text(json.dumps(cases, ensure_ascii=True, indent=2) + "\n")
    print(len(cases), "original CCXT scenarios")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    export(parser.parse_args().output)
