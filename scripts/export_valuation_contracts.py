"""Export actual Python valuation outputs, provider calls and Unicode classification data."""
from __future__ import annotations

import argparse
from dataclasses import asdict, is_dataclass
from datetime import datetime, timedelta, timezone
from decimal import Decimal, localcontext
from enum import Enum
from hashlib import sha256
import unicodedata
import json
from pathlib import Path
from unittest.mock import patch

from ft.application.valuation import UnsupportedQuote, ValuationService
from ft.domain import valuation as domain


def normalize(value):
    if isinstance(value, Decimal):
        if not value.is_finite():
            return str(value)
        parts = value.as_tuple()
        return {"negative": bool(parts.sign), "coefficient": "".join(map(str, parts.digits)), "exponent": str(parts.exponent)}
    if isinstance(value, Enum):
        return value.value
    if isinstance(value, datetime):
        return value.isoformat()
    if is_dataclass(value):
        return normalize(asdict(value))
    if isinstance(value, dict):
        return {str(key): normalize(item) for key, item in value.items()}
    if isinstance(value, (tuple, list)):
        return [normalize(item) for item in value]
    return value


def timestamp(value):
    return datetime.fromisoformat(value) if value is not None else None


def reference(value):
    if value is None:
        return None
    return domain.AssetRef(value["identity"], domain.AssetKind(value["kind"]),
        Decimal(value["quantity"]) if value.get("quantity") is not None else None)


def run(case):
    calls = []
    monotonic_calls = []
    args = case["args"]
    operation = case["operation"]
    now = timestamp(args.get("now", "2026-07-25T00:00:00+00:00"))
    ticks = args.get("ticks", {})
    def outcome(identity):
        item = ticks.get(identity)
        if item is None:
            return None
        if item == "unsupported":
            raise UnsupportedQuote(identity)
        if item == "error":
            raise RuntimeError("SYNTHETIC_PROVIDER_SECRET")
        price = Decimal(item["price"])
        return domain.ProviderTick(price, item.get("currency", "USD"), timestamp(item.get("observed_at")),
            item.get("provider", "oracle"), item.get("session", "unknown"))
    def raw_quote(self, identity, kind):
        calls.append(["single", identity, kind.value])
        return outcome(identity)
    def raw_quote_at(self, identity, kind, *, at):
        calls.append(["historical", identity, kind.value, at.isoformat()])
        return outcome(identity)
    def raw_quote_many(self, refs, *, timeout=None):
        calls.append(["batch", normalize(refs), timeout])
        if args.get("batch_error"):
            raise RuntimeError("SYNTHETIC_PROVIDER_SECRET")
        result = {}
        for identity in args.get("outcome_keys", list(ticks)):
            try:
                result[identity] = outcome(identity)
            except Exception as failure:
                result[identity] = failure
        return result
    methods = {"raw_quote": raw_quote}
    if args.get("batch"):
        methods["raw_quote_many"] = raw_quote_many
    if args.get("historical"):
        methods["raw_quote_at"] = raw_quote_at
    provider = type("OracleProvider", (), methods)()
    values = args.get("monotonic", [0.0])
    def monotonic():
        index = len(monotonic_calls)
        value = values[min(index, len(values) - 1)]
        monotonic_calls.append(value)
        return value
    try:
        with localcontext() as arithmetic, patch("ft.application.valuation.time.monotonic", monotonic):
            arithmetic.prec = args.get("precision", 28)
            service = ValuationService(provider, clock=lambda: now)
            if operation == "make_ref":
                value = domain.make_asset_ref(args.get("identity"), args.get("kind"), args.get("quantity"))
            elif operation == "kind":
                value = domain.parse_asset_kind(args.get("value"))
            elif operation == "display_currency":
                value = domain.validate_display_currency(args.get("value"))
            elif operation == "identity":
                value = domain.validate_identity(args.get("value"))
            elif operation == "finite":
                value = domain.is_finite_decimal(args.get("value"))
            elif operation == "product":
                value = domain.compute_market_value(Decimal(args["price"]), Decimal(args["quantity"]))
            elif operation == "freshness":
                value = domain.quote_freshness(timestamp(args["observed_at"]), now=now, kind=domain.AssetKind(args["kind"]))
            elif operation == "infer":
                value = domain.infer_asset_kind(args["identity"], cash_tickers=set(args.get("cash", [])),
                    configured_currencies=set(args.get("configured", [])))
            elif operation == "mismatch":
                value = domain.identity_kind_mismatch(args["identity"], domain.AssetKind(args["kind"]))
            elif operation == "symbol":
                value = domain.ledger_security_to_yfinance(args["identity"])
            elif operation == "prediction":
                value = domain.parse_prediction_market_identity(args["identity"])
            elif operation == "quote":
                value = service.quote(reference(args["ref"])) if "ref" in args else service.quote(identity=args.get("identity", ""), kind=args.get("kind"), quantity=args.get("quantity"))
            elif operation == "batch":
                value = service.quote_many([reference(item) for item in args["refs"]], timeout=args.get("timeout"))
            elif operation == "historical":
                value = service.quote_at(reference(args["ref"]), at=timestamp(args["at"]))
            else:
                raise AssertionError(operation)
        result = {"value": normalize(value)}
    except Exception as failure:
        result = {"error_type": type(failure).__name__, "message": str(failure)}
        if isinstance(failure, domain.ValuationError):
            result.update(code=failure.code, details=normalize(failure.details))
    return {**case, "expected": result, "calls": calls, "monotonic_calls": monotonic_calls}


def cases():
    result = []
    def add(operation, **args):
        result.append({"name": operation + "-" + str(len(result)), "operation": operation, "args": args})
    for value in ["cash", " SECURITY ", "crypto", "prediction_market", None, "", "other", "\u0085CASH\u001c"]:
        add("kind", value=value)
    for value in ["USD", "rmb", " hKd ", None, "", "TWD", "KRW", "EUR", "\u0085cny\u3000"]:
        add("display_currency", value=value)
    for value in [None, "", "  ", "\u0085 \u001c", "\u0085 aapl.us \u3000", "pm:a:yes"]:
        add("identity", value=value)
    for quantity in [None, "0", "-0.00", "2", "-1", "NaN", "Infinity", "1E-19", "100000000000000000000", "\uff11_\uff12.\uff13\uff10", "0E+2147483648"]:
        add("make_ref", identity="aapl.us", kind="security", quantity=quantity)
    for value in ["NaN", "-NaN12", "sNaN", "inf", "Infinity", "invalid", None, "1_000", "\uff11.\uff12", "0E+2147483648"]:
        add("finite", value=value)
    for price, quantity in [("1", "10"), ("1.00", "-0.00"), ("-0.00", "-0"), ("1", "0E+2147483648"),
            ("1", "0E-19"), ("1E-18", "1E-18"), ("99999999999999999999.123456789012345678", "9.876543210123456789"),
            ("1234567890123456789.123456789", "9876543210123456789.123456789"),
            ("1E-19", "1"), ("NaN", "1"), ("1", "100000000000000000000")]:
        for precision in (28, 80):
            add("product", price=price, quantity=quantity, precision=precision)
    now = datetime(2026, 7, 25, tzinfo=timezone.utc)
    for kind in domain.AssetKind:
        for seconds in [-1, 0, 86400, 86400.000001, 5*86400, 5*86400+0.000001, 7*86400, 7*86400+0.000001, 30*86400, 30*86400+0.000001]:
            add("freshness", kind=kind.value, observed_at=(now-timedelta(seconds=seconds)).isoformat())
        add("freshness", kind=kind.value, observed_at="2026-07-24T00:00:00")
        add("freshness", kind=kind.value, observed_at="2026-07-25T08:00:00+08:00", now="2026-07-25T00:00:00")
    for identity in ["", "usd", "rmb", "btc", "eth", "pm:election:yes", "AAPL.US", "XYZ"]:
        add("infer", identity=identity, cash=["USD"], configured=["XYZ", "BTC"])
        for kind in domain.AssetKind:
            add("mismatch", identity=identity, kind=kind.value)
    for identity in ["aapl.us", "00700.hk", "foo.hk", "00000.hk", "\uff10\uff17\uff10\uff10.hk", "00\u00b2.hk",
            "600519.sh", "000001.sz", ".sh", ".us", ".hk", "2330.tw", "2330.two", "7203.jp", "005930.kr", "111.kq",
            "BRK.B", "abc", "123", "a1", "\u00bd", "\u2163", "abc\u00b2", "btc", "pm:a:yes", "", "  xyz  ", "x.y.z"]:
        add("symbol", identity=identity)
    for identity in ["pm:a:yes", " PM:a:b:NO ", "pm::yes", "pm:a:maybe", "pm:a::yes", "abc"]:
        add("prediction", identity=identity)
    def ref(identity="aapl.us", kind="security", quantity="2"):
        return {"identity": identity, "kind": kind, "quantity": quantity}
    for tick in [None, "unsupported", "error", {"price": "NaN"}, {"price": "Infinity"},
            {"price": "-5", "observed_at": "2026-07-25T00:00:00+00:00"},
            {"price": "5.00", "currency": "usd", "observed_at": "2026-07-25T00:00:00+00:00", "session": "post_market"},
            {"price": "5", "observed_at": "2026-07-19T00:00:00+00:00"},
            {"price": "5", "observed_at": "2026-06-01T00:00:00+00:00"},
            {"price": "5", "observed_at": None, "currency": None, "session": None},
            {"price": "5", "observed_at": "2026-07-25T00:00:00"}]:
        add("quote", ref=ref(), ticks={"aapl.us": tick})
    add("quote", identity="usd", kind="cash", quantity="3")
    add("quote", ref=ref(" aapl.us ", quantity="-1"), ticks={"aapl.us":{"price":"5"}})
    add("quote", ref=ref("btc"), ticks={})
    add("quote", ref=ref("usd", "cash", "NaN"))
    for historical in (False, True):
        for outcome in (None, "unsupported", "error", {"price": "99.5"}, {"price":"NaN"}):
            add("historical", ref=ref(quantity="10"), at="2026-07-24T12:00:00+00:00", historical=historical, ticks={"aapl.us":outcome})
    add("historical", ref=ref(" usd ", "cash", "10"), at="2026-07-24T12:00:00+08:00")
    add("historical", ref=None, at="2026-07-24T12:00:00")
    add("historical", ref=ref(), at="2026-07-24T12:00:00")
    for batch in (False, True):
        for timeout, monotonic in [(None,[0.0]),(0,[0.0]),(1,[0,0.25,1.0,1.5,2.0]),(-1,[0.0])]:
            add("batch", batch=batch, refs=[ref("ok"),ref("bad"),ref("err"),ref("none"),ref("btc")],
                ticks={"ok":{"price":"5"}, "bad":"unsupported", "err":"error"}, timeout=timeout, monotonic=monotonic)
    add("batch", batch=True, refs=[ref("ok",quantity="1"),ref("ok",quantity="2")], ticks={"ok":{"price":"5"}})
    add("batch", batch=True, refs=[ref("btc"),ref("ok")], batch_error=True)
    add("batch", batch=True, refs=[ref("ok"),None], ticks={"ok":{"price":"5"}})
    add("batch", batch=True, refs=[ref("ok"),ref("bad",quantity="NaN")])
    add("batch", batch=True, refs=[ref("btc")], ticks={})
    add("batch", batch=True, refs=[])
    return result


def character_source():
    def ranges(predicate):
        result, start = [], None
        for code in range(0x110001):
            matches = code < 0x110000 and predicate(chr(code))
            if matches and start is None:
                start = code
            if not matches and start is not None:
                result.extend((start,code-1)); start = None
        return result
    lines = ["package com.finance.tracker.backend", "", "// Generated by scripts/export_valuation_contracts.py using Python Unicode semantics."]
    for name, predicate in (("valuationAlphaRanges",str.isalpha),("valuationAlnumRanges",str.isalnum)):
        lines.append("internal val "+name+" = intArrayOf("+", ".join(map(str,ranges(predicate)))+")")
    return "\n".join(lines)+"\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output",required=True,type=Path)
    parser.add_argument("--character-output",type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if not args.output.is_absolute() or args.output.resolve().is_relative_to(root):
        raise ValueError("Contract output must be outside the repository")
    args.output.parent.mkdir(parents=True,exist_ok=True)
    with args.output.open("x") as stream:
        json.dump({"cases":[run(case) for case in cases()], "python_unicode_version": unicodedata.unidata_version,
            "sources_sha256": {name: sha256((root/name).read_bytes()).hexdigest() for name in
                ("src/ft/domain/valuation.py", "src/ft/application/valuation.py", "src/ft/domain/decimal.py", "src/ft/schema.py")}},
            stream,ensure_ascii=True,indent=2); stream.write("\n")
    if args.character_output:
        args.character_output.write_text(character_source())
    print("Exported",len(cases()),"actual Python valuation cases")

