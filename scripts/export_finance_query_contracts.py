"""Export anonymous actual Python finance application results and dependency call order."""
from __future__ import annotations

import argparse
from copy import deepcopy
from datetime import datetime, timezone
from decimal import Decimal
from hashlib import sha256
import json
from pathlib import Path

from ft.application.queries import FinanceQueryService
from ft.application.valuation import UnsupportedQuote, ValuationService
from ft.domain.accounts import AccountDTO
from ft.domain.queries import FlowDTO
from ft.domain.valuation import ProviderTick
from ft.web.serialization import json_value

NOW = datetime(2026, 7, 25, tzinfo=timezone.utc)


def cases():
    values = []
    def add(name, args, operation="accounts"):
        values.append({"name": name, "operation": operation, "args": args})
    for amount in [None, 0, -0.0, 12.34, True, False, "-0.000", "0E+10", "1.23000", "1e25", "bad", [], {}, "NaN12", "-Infinity", "sNaN"]:
        add(f"cash_value_{len(values)}", {"accounts": [{"name": "Cash", "type": "cash"}], "snapshot": {"accounts": {"cash": {"Cash": {"USD": amount}}}}})
    for bucket in [{}, None, 12.5, "-0.00", {"USD": "1.00", "CNY": "2", "JPY": "3.0"}]:
        add(f"cash_bucket_{len(values)}", {"accounts": [{"name": "Cash", "type": "cash", "active": False}], "snapshot": {"accounts": {"cash": {"Cash": bucket}}}})
    add("cash_loan_lend_order", {"accounts": [{"name": "Lend", "type": "lend"}, {"name": "Cash", "type": "cash"}, {"name": "Loan", "type": "loan"}],
        "snapshot": {"accounts": {"cash": {"Cash": {"USD": "1.00", "CNY": "2.000"}}, "loan": {"Loan": {"JPY": "-3.0"}}, "lend": {"Lend": {"HKD": "4"}}}}})
    add("python_currency_key_order", {"accounts": [{"name": "Cash", "type": "cash"}], "snapshot": {"accounts": {"cash": {"Cash": {"😀": "1", "\ue000": "2", "𐀀": "3"}}}}})
    def security(**changes):
        base = {"accounts": [{"name": "Broker", "type": "security", "currencies": ["USD", "HKD"]}],
            "snapshot": {"accounts": {"security": {"Broker": {"currency": "USD", "positions": {
                "usd": {"shares": "10.00", "total_cost": "10.00"}, "aapl.us": {"shares": "2.000", "total_cost": "6.00"}, "missing.us": {"shares": "3", "total_cost": "9"}}}}}},
            "ticks": {"aapl.us": {"price": "5.00", "currency": "USD", "at": "2026-07-25T00:00:00+00:00"}}, "valuation": True}
        base.update(changes)
        return base
    add("security_current_and_cost_fallback", security())
    for tick in [None, "unsupported", "error", {"price": "5", "currency": "HKD", "at": "2026-07-25T00:00:00+00:00"},
        {"price": "5", "currency": "USD", "at": "2026-07-17T00:00:00+00:00"},
        {"price": "5", "currency": "USD", "at": "2026-05-01T00:00:00+00:00"},
        {"price": "5", "currency": "USD", "at": None}, {"price": "NaN", "currency": "USD"}]:
        add(f"security_tick_{len(values)}", security(ticks={"aapl.us": tick}))
    for shares in ["0.000", "-0.000", "-2.00", "0.000000000000000001", "100000000000000000000", "NaN", "Infinity"]:
        args = security()
        args["snapshot"]["accounts"]["security"]["Broker"]["positions"]["aapl.us"]["shares"] = shares
        add(f"security_quantity_{len(values)}", args)
    for operation in ["accounts", "report"]:
        args = security()
        args["snapshot"]["accounts"]["security"]["Broker"]["positions"] = {"aapl.us": {"shares": "12345678901234567890.123456789012345678", "total_cost": "1"}}
        args["ticks"]["aapl.us"]["price"] = "1.123456789012345678"
        add(f"valuation_precision_{operation}", args, operation)
    args = security()
    args["snapshot"]["accounts"]["security"]["Broker"]["positions"] = {"HKD": {"shares": "5", "total_cost": "99"}, "eur": {"shares": "2", "total_cost": "7"}, "btc": {"shares": "1", "total_cost": "8"}, "pm:fixture:yes": {"shares": "1", "total_cost": "9"}}
    add("configured_cash_and_other_asset_kinds", args)
    for account_type in ["crypto", "security"]:
        args = security();args["accounts"][0]["type"] = account_type
        args["snapshot"]["accounts"]["crypto"] = {"Broker": {"positions": {"btc": {"shares": "99", "total_cost": "999"}}}}
        add(f"original_security_bucket_for_{account_type}", args)
    for config in [[], ["HKD"], ["cny", "USD"]]:
        args = security();args["accounts"][0]["currencies"] = config;args["snapshot"]["accounts"]["security"]["Broker"].pop("currency")
        add(f"configured_quote_{len(values)}", args)
    for prices in [{}, {"aapl.us": "5.00"}, {"aapl.us": "0"}]:
        add(f"legacy_price_map_{len(values)}", security(valuation=False, prices=prices))
    rows = [
        {"occurred_at": "2026-06-01", "account_name": "Cash", "currency": "CNY", "record_type": "consumption", "amount": "-100", "note": "old"},
        {"occurred_at": "2026-06-02", "account_name": "Cash", "currency": "CNY", "record_type": "other", "amount": "0", "note": "余额校准1000"},
        {"occurred_at": "2026-06-03", "account_name": "Cash", "currency": "CNY", "record_type": "consumption", "amount": "-12.50", "note": "meal"},
        {"occurred_at": "2026-06-04", "account_name": "Cash", "currency": "CNY", "record_type": "income", "amount": "20", "note": "gift"},
        {"occurred_at": "2026-05-01", "account_name": "Cash", "currency": "CNY", "record_type": "transfer_out", "amount": "-5", "note": "Broker"},
        {"occurred_at": "2026-06-05", "account_name": "Inactive", "currency": "USD", "record_type": "income", "amount": "10.00"},
        {"occurred_at": "2026-06-05", "account_name": "Inactive", "currency": "USD", "record_type": "fee", "amount": "-11"},
        {"occurred_at": "2026-06-06", "account_name": "Cash", "currency": "CNY", "record_type": "income", "amount": "999", "state": "draft"},
        {"occurred_at": "2026-06-06", "account_name": "Cash", "currency": "CNY", "record_type": "income", "amount": "999", "deleted_at": "2026-07-01"},
        {"occurred_at": "2026-06-06", "account_name": "Cash", "currency": "CNY", "record_type": "income", "amount": "999", "deleted": True}]
    for month in [None, "2026-06", "2026-05", "bad", ""]:
        add(f"legacy_report_{month}", {"accounts": [{"name": "Cash", "type": "cash"}, {"name": "Inactive", "type": "cash", "active": False}],
            "snapshot": {"accounts": {"cash": {"Cash": {"CNY": "12.34"}}}}, "rows": rows, "month": month}, "report")
    for kind in ["result", "value_error", "internal_error"]:
        args = security(reporter=kind, month="2026-07")
        add(f"production_report_order_{kind}", args, "report")
    add("legacy_flow_top_ten_stable_ties", {"accounts": [], "snapshot": {}, "rows": [
        {"occurred_at": f"2026-06-{index:02}", "record_type": "transfer_out", "amount": "-5.00", "note": str(index), "currency": "USD"}
        for index in range(1, 14)]}, "report")
    for field, invalid in [("account_name", None), ("account_name", True), ("account_name", 1), ("account_name", {}), ("account_name", []),
        ("currency", None), ("currency", 1), ("currency", {})]:
        row = {"occurred_at": "2026-06-01", "account_name": "Cash", "currency": "CNY", "record_type": "income", "amount": "20"}
        row[field] = invalid
        add(f"legacy_internal_text_error_{len(values)}", {"accounts": [{"name": "Cash", "type": "cash"}], "snapshot": {}, "rows": [row]}, "report")
    return values


def run(case):
    args = case["args"]; calls = []
    class Accounts:
        def list_accounts(self):
            calls.append(["accounts"])
            return [AccountDTO(x["name"], x["type"], x.get("active", True), tuple(x.get("currencies", []))) for x in args.get("accounts", [])]
    class Snapshot:
        def load_snapshot(self):
            calls.append(["snapshot"])
            return deepcopy(args.get("snapshot", {}))
    class Transactions:
        def list_transactions(self, *, month=None):
            calls.append(["transactions", month])
            rows = [dict(row) for row in args.get("rows", []) if not month or row.get("occurred_at", "").startswith(month)]
            return sorted(rows, key=lambda row: row.get("occurred_at", ""), reverse=True)
    class Provider:
        def raw_quote(self, identity, kind):
            calls.append(["quote", identity, kind.value])
            tick = args.get("ticks", {}).get(identity)
            if tick == "unsupported": raise UnsupportedQuote(identity)
            if tick == "error": raise RuntimeError("SYNTHETIC_PROVIDER_SECRET")
            if tick is None: return None
            at = datetime.fromisoformat(tick["at"]) if tick.get("at") else None
            return ProviderTick(Decimal(str(tick["price"])), tick.get("currency", "USD"), at, "fixture")
    class Market:
        def get_prices(self, tickers, *, quote_currency):
            calls.append(["prices", tickers, quote_currency])
            return {key: Decimal(value) for key, value in args.get("prices", {}).items()}
    def reporter(*, month=None):
        calls.append(["reporter", month])
        if args["reporter"] == "value_error": raise ValueError("SYNTHETIC_INVALID_MONTH")
        if args["reporter"] == "internal_error": raise RuntimeError("SYNTHETIC_READER_ERROR")
        return {"income": {"USD": Decimal("20.000")}, "expenses": {"CNY": Decimal("5.00")}, "flows": (FlowDTO("投资", "CNY", Decimal("2.00")),)}
    service = FinanceQueryService(accounts=Accounts(), snapshots=Snapshot(), transactions=Transactions(),
        valuation=ValuationService(Provider(), clock=lambda: NOW) if args.get("valuation") else None,
        market_data=Market() if "prices" in args else None, cash_reporter=reporter if "reporter" in args else None)
    try:
        value = service.list_accounts() if case["operation"] == "accounts" else service.report(month=args.get("month"))
        output = {"value": json_value(value)}
    except Exception as failure:
        output = {"error": "value" if isinstance(failure, ValueError) else "internal"}
    return {**case, "expected": output, "calls": calls}


if __name__ == "__main__":
    parser = argparse.ArgumentParser();parser.add_argument("--output", type=Path, required=True);args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if not args.output.is_absolute() or args.output.resolve().is_relative_to(root):raise ValueError("Use an external anonymous output")
    values = [run(case) for case in cases()]
    sources = ["src/ft/application/queries.py", "src/ft/application/valuation.py", "src/ft/domain/queries.py", "src/ft/web/serialization.py"]
    with args.output.open("x") as stream:
        json.dump({"cases": values, "sources_sha256": {p: sha256((root / p).read_bytes()).hexdigest() for p in sources}}, stream, indent=2)
        stream.write("\n")
    print("Exported", len(values), "actual original finance query results and dependency calls")
