"""Export anonymous parsed-session bytes and validation boundaries from Python."""
from __future__ import annotations

import argparse
from dataclasses import replace
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tests"))
from test_parsed_import_staging import _file
from ft.application.parsed_import_staging import InMemoryParsedImportStore, _json_budget


def export():
    now = datetime(2026, 10, 21, 12, tzinfo=timezone.utc)
    store = InMemoryParsedImportStore(clock=lambda: now, ttl_seconds=60)
    original = store.create(workspace_id="workspace-a", user_id="user-a", files=(_file(),))
    original = replace(original, session_id="z" * 43)
    sessions = []
    for name, session in [
        ("plain", original),
        ("values", replace(original, plan={"values": [True, False, None, 0.95, -0.0, 10**30, "\u0000\n\"\\", "😀"]})),
        ("micros", replace(original, created_at=now+timedelta(microseconds=123000), expires_at=now+timedelta(seconds=60, microseconds=123000))),
        ("mutation", replace(original, files=(), workflow_kind="mutation", context_data={"transaction_id": "1"})),
    ]:
        sessions.append({"name": name, "body": store._encode(session).decode()})
    budgets = []
    values = [{}, [], [None, True, False, "😀", 10**30, 0.95], {"a": [None, True, False]}, [[], {}], {"说明": "\"\n"}]
    for value in values:
        for depth in (0, 63, 64, 65):
            for maximum in (1, 2, 5, 20, 100):
                budget = [maximum]
                try:
                    _json_budget(value, budget, depth=depth)
                    error = None
                except ValueError as failure:
                    error = str(failure)
                budgets.append({"value": value, "depth": depth, "maximum": maximum, "remaining": budget[0], "error": error})
    return json.dumps({"sessions": sessions, "budgets": budgets}, ensure_ascii=False, indent=2) + "\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    content = export()
    if args.check:
        if args.output.read_text() != content:
            raise SystemExit("parsed codec golden is stale")
    else:
        args.output.write_text(content)
