"""Export original IBKR parsing, identity, event and replay outputs for offline parity."""
from __future__ import annotations
import argparse
import copy
import csv
from dataclasses import asdict
from decimal import Decimal
import hashlib
import io
import json
from pathlib import Path
import tempfile
from ft.importers.ibkr import parse_ibkr_csv, construct_source_identity, map_ibkr_to_investment_event
from ft.domain.investment_projection import apply_investment_event


def encode(value):
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, dict):
        return {key: encode(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [encode(item) for item in value]
    return value


def result(operation):
    try:
        return {"value": encode(operation())}
    except Exception as exc:
        return {"error": type(exc).__name__, "message": str(exc)}


def parse_output(content: str):
    with tempfile.TemporaryDirectory(prefix="ft-ibkr-oracle-") as folder:
        path = Path(folder) / "statement.csv"
        path.write_bytes(content.encode("utf-8"))
        statement = parse_ibkr_csv(path)
        return asdict(statement)


def parsed_events(content):
    statement = parse_output(content)
    return [{"identity": construct_source_identity(row), "event": map_ibkr_to_investment_event(row, "IBKR", statement['base_currency'])} for row in statement['transactions']]


def contracts():
    root = Path(__file__).resolve().parents[1]
    original = (root / "tests/fixtures/ibkr/transactions_1y_sample.csv").read_bytes().decode("utf-8")
    statement = parse_ibkr_csv(root / "tests/fixtures/ibkr/transactions_1y_sample.csv")
    snapshots = []
    snapshot = {"accounts": {"security": {}}}
    mapped = []
    for row in statement.transactions:
        event = map_ibkr_to_investment_event(row, "IBKR", statement.base_currency)
        mapped.append({"identity": construct_source_identity(row), "event": event})
        apply_investment_event(snapshot, event, default_currency=statement.base_currency)
        snapshots.append(copy.deepcopy(snapshot))
    parse_cases = [("fixture", original), ("lf", original.replace("\r\n", "\n")), ("cr", original.replace("\r\n", "\r")), ("empty", ""),
                   ("unknown_action", original.replace(",卖,GOOG,", ",未知类型,GOOG,", 1)), ("missing_summary", "Transaction History,Data,2026-01-01,A,desc,存款,-,-,-,USD,-,-,10\n"),
                   ("short", "Transaction History,Data,x"), ("summary_only", "总结,Data,基础货币,USD\n总结,Data,期末现金,0\n"),
                   ("generated_date", "Statement,Data,WhenGenerated,2026-11-01T00:00\n总结,Data,基础货币,USD\n总结,Data,期末现金,1\n"),
                   ("no_base", "总结,Data,期末现金,1\n"), ("bom", "\ufeff" + original), ("overlong_field", "A" * 131073), ("maximum_field", "A" * 131072)]
    row = ["Transaction History", "Data", "2026-01-01", "A", "description", "买", "ABC", "2", "4.5", "USD", "-9", "-1", "-10"]
    for balance in ["-0.00", "0.000", "-10.00", "1e-100", "NaN", "Infinity"]:
        parse_cases.append(("balance_" + balance, "总结,Data,基础货币,USD\n总结,Data,期末现金," + balance + "\n"))
    for field, values in {4: ["line1\r\nline2", 'said "hello",there', 'plain"quote', "", "   spaced  "],
                          6: ["00700", "600000", "USD.HKD", "MRVL.US", "-", "   "],
                          7: ["", "-", "0.000", "-0.00", "１.２", "1_000", "1,000", "123456789012345678901234567890123", "1e-1000027", "5e-1000027", "6e-1000027", "1e1000000", "bad", "NaN", "sNaN", "Infinity"],
                          11: ["0", "-0.00", "-1.0000", "NaN", "Infinity"]}.items():
        for i, value in enumerate(values):
            current = row.copy(); current[field] = value
            out = io.StringIO(newline=""); csv.writer(out).writerows([["总结", "Data", "基础货币", "USD"], ["总结", "Data", "期末现金", "10"], current])
            parse_cases.append((f"cell_{field}_{i}", out.getvalue()))
    map_cases = []
    base = {"action": "买", "date": "2026-01-02", "code": "AAPL", "qty": "2", "qty_raw": "2", "gross": "-9", "commission": "-1", "commission_raw": "-1", "price": "4.5", "net": "-10", "net_raw": "-10", "note": "source", "price_currency": "USD"}
    for action in ["买", "卖", "存款", "股息", "外国预扣税", "借方利息", "外汇交易组成部分", "CHECKIN", "unknown"]:
        for currency in ["USD", "cny", "HKD", ""]:
            row = {**base, "action": action}
            map_cases.append({"row": row, "currency": currency})
    for action in ["买", "外汇交易组成部分", "CHECKIN"]:
        for amount in ["0", "-0.00", "1e-22", "-1.5555555555555555555", "12345678901", "NaN", "sNaN", "Infinity"]:
            row = {**base, "action": action, "net": amount, "net_raw": amount, "amount": amount, "gross": amount, "qty": amount, "commission": "0"}
            map_cases.append({"row": row, "currency": "USD"})
    for key in ["amount", "net", "qty", "commission", "gross", "price"]:
        for val in [0, None, "", "-0", "bad", "1_000", "１.２"]:
            map_cases.append({"row": {**base, "action": "CHECKIN" if key == "amount" else "买", key: val}, "currency": "USD"})
    for case in map_cases:
        case['expected'] = result(lambda: map_ibkr_to_investment_event(case['row'], 'IBKR', case['currency']))
        case['identity'] = result(lambda: construct_source_identity(case['row']))
    return {"source_sha256": hashlib.sha256((root / 'src/ft/importers/ibkr.py').read_bytes()).hexdigest(), "statement": encode(asdict(statement)), "mapped": encode(mapped), "snapshots": encode(snapshots),
            "parse_cases": [{"name": name, "content": content, "expected": result(lambda: parse_output(content)), "parsed_events": None if name.startswith("cell_7_") and any(x in content for x in ["e-1000027", "e1000000"]) else result(lambda: parsed_events(content))} for name, content in parse_cases], "map_cases": map_cases}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--output', required=True, type=Path); args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if not args.output.is_absolute() or args.output.resolve().is_relative_to(root):
        raise ValueError('Use an absolute external output path')
    data = contracts(); args.output.write_text(json.dumps(data, ensure_ascii=True, indent=2) + '\n')
    print(len(data['parse_cases']), 'parse cases;', len(data['map_cases']), 'map/identity cases;', len(data['mapped']), 'fixture rows and replay snapshots')
