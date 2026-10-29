"""Export manual transfer events and exact snapshot changes from Python."""
from __future__ import annotations

import argparse
from copy import deepcopy
from decimal import Decimal
import json
from pathlib import Path

from ft.application.cashflow import _apply_transfer_snapshot, _transfer_event
from ft.domain.accounts import AccountDTO

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "compose/backend/src/test/resources/python-cash-transfer-rules.json"


def generate():
    events, snapshots = [], []
    types = ("cash", "loan", "lend", "security", "crypto")
    for kind in types:
        for other in types:
            for currency in ("CNY", "USD"):
                for outgoing in (False, True):
                    for note in ("", "指定备注"):
                        row = dict(date="2026-10-24 04:30:00", amount="123.4500", name="A", type=kind,
                                   counterpart="B", counterpart_type=other, currency="CNY",
                                   counterpart_currency=currency, outgoing=outgoing, note=note)
                        expected = _transfer_event(row["date"], Decimal(row["amount"]),
                                                   AccountDTO("A", kind), AccountDTO("B", other),
                                                   "CNY", currency, "out" if outgoing else "in", note)
                        row["expected"] = {k: format(v, "f") if isinstance(v, Decimal) else v for k, v in expected.items()}
                        events.append(row)
    values = ("0", "-0.00", "1.2300", "99999999999999999999", "-99999999999999999999",
              "0.000000000000000001", "NaN", "bad", "0.0000000000000000001")
    for kind in types:
        for current in values:
            for amount in ("0.00", "-0.00", "1.000000000000000001", "-1"):
                bucket = "security" if kind in {"security", "crypto"} else kind
                pocket = dict(currency="HKD", extra=True, positions={"usd": dict(
                    shares=current, total_cost=current, cost_currency="HKD", untouched=3)}) if bucket == "security" else {"USD": current, "EUR": "7"}
                snapshot = {"unknown": [1], "accounts": {bucket: {"A": pocket}}}
                row = dict(type=kind, amount=amount, snapshot=snapshot)
                expected = deepcopy(snapshot)
                try:
                    _apply_transfer_snapshot(expected, AccountDTO("A", kind), "USD", Decimal(amount))
                    row["expected"] = expected
                except ValueError as error:
                    row["error"] = str(error)
                snapshots.append(row)
    for currency in ("ΟΣʰ", "İAB", "𐐀AB"):
        for amount in ("-1.20", "0.00", "-0.00", "0E2147483648", "-0E2147483648"):
            for kind in types:
                row = dict(date="2026-10-24 04:30:00", amount=amount, name="A", type=kind,
                           counterpart="B", counterpart_type="cash", currency=currency,
                           counterpart_currency="CNY", outgoing=True, note="")
                expected = _transfer_event(row["date"], Decimal(amount), AccountDTO("A", kind),
                                           AccountDTO("B", "cash"), currency, "CNY", "out", "")
                row["expected"] = {k: format(v, "f") if isinstance(v, Decimal) else v for k, v in expected.items()}
                events.append(row)
    unicode_snapshot = {"accounts": {"security": {"A": {"positions": {
        "οςʰ": {"shares": "5", "total_cost": "6", "cost_currency": "ΟΣʰ"}}}}}}
    changed = deepcopy(unicode_snapshot)
    _apply_transfer_snapshot(changed, AccountDTO("A", "security"), "ΟΣʰ", Decimal("1"))
    snapshots.append(dict(type="security", currency="ΟΣʰ", amount="1", snapshot=unicode_snapshot, expected=changed))
    return json.dumps(dict(events=events, snapshots=snapshots), ensure_ascii=False, indent=2) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    content = generate()
    if args.check:
        if OUTPUT.read_text() != content:
            raise RuntimeError("Cash transfer rules oracle is stale")
    else:
        OUTPUT.write_text(content)
    data = json.loads(content)
    print(f"{len(data['events'])} transfer events, {len(data['snapshots'])} snapshot cases")


if __name__ == "__main__":
    main()
