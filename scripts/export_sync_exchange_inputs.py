"""Export original exchange integration inputs without precomputing native results."""
from __future__ import annotations

import argparse
import ast
import json
from pathlib import Path


def export(output):
    root = Path(__file__).resolve().parents[1]
    tree = ast.parse((root / "tests/integration/test_sync_exchange_sqlite.py").read_text())
    raw = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == "RawCcxtClient")
    rows = {method.name: ast.literal_eval(next(node.value for node in method.body if isinstance(node, ast.Return))) for method in raw.body if isinstance(method, ast.FunctionDef)}
    klass = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == "TestExchangeSyncSQLite")
    method = next(node for node in klass.body if isinstance(node, ast.FunctionDef) and node.name == "test_ledger_events_are_persisted_idempotently")
    events = next(node.value for node in method.body if isinstance(node, ast.Assign) and any(isinstance(target, ast.Name) and target.id == "events" for target in node.targets))
    class Time(ast.NodeTransformer):
        def visit_Name(self, node): return ast.copy_location(ast.Constant("2026-07-26T00:00:00Z"), node) if node.id == "at" else node
    ledger = ast.literal_eval(Time().visit(events))
    output.write_text(json.dumps(dict(trades=json.loads((root / "tests/fixtures/ccxt_trades.json").read_text()), raw_trades=rows["fetch_my_trades"], raw_ledger=rows["fetch_ledger"], ledger_events=ledger), indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    export(parser.parse_args().output)
