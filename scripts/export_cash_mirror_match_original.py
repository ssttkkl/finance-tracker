"""Export every pure payment-mirror matcher call from the original tests."""
from __future__ import annotations

import argparse
from dataclasses import asdict, is_dataclass
from decimal import Decimal
import inspect
import json
from pathlib import Path

from ft.application.cash_import_operations import _wire
import test_transaction_relations_payment_mirror as original


def value(item):
    if isinstance(item, (set, frozenset)):
        return sorted((value(entry) for entry in item), key=lambda entry: json.dumps(entry, sort_keys=True, default=str))
    if isinstance(item, dict):
        return {str(key): value(entry) for key, entry in sorted(item.items(), key=lambda pair: str(pair[0]))}
    if isinstance(item, (list, tuple)):
        return [value(entry) for entry in item]
    if is_dataclass(item):
        return value(asdict(item))
    if isinstance(item, Decimal):
        return str(item)
    return item


def export():
    tests = {}
    skipped = {}
    for name, function in vars(original).items():
        if not name.startswith("test_") or not callable(function):
            continue
        parameters = list(inspect.signature(function).parameters)
        if parameters:
            skipped[name] = parameters
            continue
        calls = []

        def record(kind, arguments, kwargs, result):
            calls.append({"fn": kind, "args": value(arguments), "kwargs": value(kwargs), "result": value(result)})

        def wrap(kind):
            target = getattr(original, kind)

            def run(*args, **kwargs):
                answer = target(*args, **kwargs)
                if kind != "project_balances_and_pnl":
                    record(kind, args, kwargs, answer)
                return answer

            return run

        names = ("source_group", "evaluate_payment_mirror", "match_payment_mirrors_greedy",
            "match_canonical_payment_mirrors", "project_balances_and_pnl")
        original_values = {kind: getattr(original, kind) for kind in names}
        try:
            for kind in names:
                setattr(original, kind, wrap(kind))
            function()
        finally:
            for kind, saved in original_values.items():
                setattr(original, kind, saved)
        tests[name] = calls
    return {"tests": tests, "skipped": skipped}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    content = json.dumps(export(), ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.check:
        assert args.output.read_text() == content
    else:
        args.output.write_text(content)
    print(f"exported {sum(len(v) for v in json.loads(content)['tests'].values())} calls")


if __name__ == "__main__":
    main()
