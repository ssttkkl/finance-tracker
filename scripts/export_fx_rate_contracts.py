"""Export anonymous actual Python FX outputs and requests without external I/O."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
from decimal import Decimal
from hashlib import sha256
import json
from pathlib import Path
from unittest.mock import patch

from export_valuation_contracts import normalize
from ft.adapters import fx_rates as fx

NOW = datetime(2026, 7, 25, 1, 2, 3, tzinfo=timezone.utc)


class FrozenDateTime(datetime):
    @classmethod
    def now(cls, tz=None):
        return NOW.astimezone(tz) if tz else NOW.replace(tzinfo=None)


def cases():
    result = []
    def add(op, **args):
        result.append({"name": f"{op}_{len(result):03d}", "operation": op, "args": args})
    for value in [None, False, 0, 12.5, [], {}, "", " x ", "not-a-date-long", "2025-12-19 06:30:57",
        "2026-07-25T00:01:00+14:00", "2026-99-99 invalid", "20260725", "20260725T000100+14:00",
        "2026W305", "2026-W30", "2026-W30-5", "2026-W305", "0000-00-00", "😀😀😀😀-xx-x-long", "\u001c20260725\u001f"]:
        add("day", value=value)
    for value in ["2026-07-25T00:01:00+14:00", "2026-07-25T23:01:00-12:00", "2026-07-25T01:02:03"]:
        add("day", value=value, datetime=True)
    for day, base, quote in [("2026-07-25-extra", "usd", "cny"), (None, "USD", "CNY"), ("", "USD", "CNY"),
        (False,"USD","CNY"), (123456789012,"usd","cny"), ("2026",None,"USD"), ("2026",123,"456"),
        ("2026"," US","CNY"), ("2026","ßa","EUR"), ("2026","😀ab","EUR"), ("2026","ＡＢＣ","EUR"),
        ("2026","USD","USD"), ("2026"," usd ","CNY"), ("2026",True,"USD"), ("😀😀😀😀😀😀😀😀😀😀x","usd","cny")]:
        add("mid", day=day, base=base, quote=quote, rate="7.100")
    for value in [None, False, True, 0, -0.0, -1, 1, 12.1234567890123456, "7.1000", "-0.0", "NaN", "sNaN", "Infinity",
        "-Infinity", "bad", "", [], {}, "1_000.00", "７.２", "1e1000", "1e-1000", "0.000000000000000001"]:
        add("fetch", day="2026-07-25", base="usd", quote="cny", payloads=[{"rates":{"CNY":value}}])
    for payload in [None, {}, [], False, "text", {"rates":[]}, {"rates":None}, {"rates":{"USD":"7"}}, {"rates":3}]:
        add("fetch", day="2026-07-25", base="USD", quote="CNY", payloads=[payload, {"rates":{"CNY":"7.2"}}])
    for count in range(1,7):
        add("fetch", day="2026-03-02", base="USD", quote="CNY", payloads=["HTTP_ERROR"]*count+[{"rates":{"CNY":"7.20"}}])
    for day in ["invalid", "20260230", "20260725", "2026-W30-5", "0001-01-01"]:
        add("fetch", day=day, base="USD", quote="CNY", payloads=["HTTP_ERROR"]*7)
    add("fetch", day="invalid",base="USD",quote="usd",payloads=[])
    for cash, loan, market in [("144.61","159.40","1.1051"),("144.61","2240","1.1051"),("3","1","0.1"),
        ("7.000","1.000","7.000"),("1","1","1"),("0","1","1"),("-1","1","1"),("1","0","1"),
        ("1","-1","1"),("1","1",None),("1","1","0"),("1","1","-1"),("1","1","NaN"),("NaN","1","1"),
        ("1","NaN","1"),("1","1","sNaN"),("Infinity","1","1"),("1","Infinity","1"),
        ("Infinity","Infinity","1"),("1","1","Infinity"),("1","Infinity","Infinity"),
        ("123456789012345678901234567890.123456789","987654321098765432109876543210.987654321","1.1234567890123456789")]:
        add("error",cash=cash,loan=loan,market=market,base="CNY",quote="HKD")
    add("error",cash="1",loan="2",market="1",base="usd",quote="USD")
    add("error",cash="1",loan="2",market="1",base="ßa",quote="SSA")
    return result


def run(case):
    args = case["args"]; calls = []
    def fetch(url):
        index = len(calls); calls.append(url)
        body = args["payloads"][min(index, len(args["payloads"])-1)]
        if body == "HTTP_ERROR":
            raise OSError("SYNTHETIC_FX_SECRET")
        return body
    def injected(day, base, quote):
        calls.append([day, base, quote]); return Decimal(args["rate"])
    fx.clear_rate_cache()
    with patch.object(fx, "datetime", FrozenDateTime), patch.object(fx, "_json_get", fetch):
        try:
            op = case["operation"]
            if op == "day":
                value = FrozenDateTime.fromisoformat(args["value"]) if args.get("datetime") else args["value"]
                value = fx.business_day_utc(value)
            elif op == "mid":
                value = fx.get_mid_rate(args["day"],args["base"],args["quote"],fetcher=injected)
            elif op == "fetch":
                value = fx._fetch_frankfurter(args["day"],args["base"],args["quote"])
            else:
                value = fx.rate_error(Decimal(args["cash"]),Decimal(args["loan"]),args["base"],args["quote"],
                    Decimal(args["market"]) if args["market"] is not None else None)
            expected = normalize(value)
        except Exception:
            expected = {"error":"error"}
    return {**case,"expected":expected,"calls":calls}


def export(output):
    if output.exists():
        raise ValueError("output must be a new external file")
    root = Path(__file__).resolve().parents[1]
    if output.resolve().is_relative_to(root):
        raise ValueError("output must be outside the repository")
    paths = ["src/ft/adapters/fx_rates.py","tests/test_fx_rates.py","tests/unit/adapters/test_fx_rate_provider.py"]
    data = {"sources_sha256":{p:sha256((root/p).read_bytes()).hexdigest() for p in paths},"cases":[run(c) for c in cases()]}
    output.write_text(json.dumps(data,ensure_ascii=False,indent=2)+"\n")
    print(f"Exported {len(data['cases'])} actual FX cases")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(); parser.add_argument("--output",type=Path,required=True)
    export(parser.parse_args().output)
