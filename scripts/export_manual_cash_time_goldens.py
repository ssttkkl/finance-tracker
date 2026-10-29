"""Export repository ISO dates and UTC normalization for manual cash creation."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from export_cash_time_goldens import content
from ft.adapters.relational.repositories import _parse_timestamp

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "compose/backend/src/test/resources/python-manual-cash-timestamps.json"


def generate():
    values = {row["value"] for row in json.loads(content())}
    values.update(["", " \n", "wrong", "2026-08-04", "20260804", "2026-W32-2", "2026W322",
                   "2026-W32", "2026W32", "0001-01-01", "9999-12-31", "2025-02-29",
                   " 2026-08-04T12:34:56.123456789 "])
    rows = []
    for value in sorted(values):
        try:
            row = {"value": value, "utc": _parse_timestamp(value).isoformat().replace("+00:00", "Z")}
        except ValueError as error:
            row = {"value": value, "error": str(error)}
        except OverflowError as error:
            row = {"value": value, "overflow": str(error)}
        rows.append(row)
    return json.dumps(rows, ensure_ascii=False, indent=2) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    data = generate()
    if args.check:
        if OUTPUT.read_text() != data:
            raise RuntimeError("Manual cash timestamp oracle is stale")
    else:
        OUTPUT.write_text(data)
    print(f"{len(json.loads(data))} manual cash timestamp cases")


if __name__ == "__main__":
    main()
