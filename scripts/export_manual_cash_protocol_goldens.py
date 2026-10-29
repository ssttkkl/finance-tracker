"""Export anonymous manual-cash strict request and direct application boundaries."""
from __future__ import annotations

import argparse
import base64
from copy import deepcopy
import json
from pathlib import Path

from ft.application.cash_ledger import _manual_create_payload
from ft.web.cash_import_protocol import strict_json
from ft.web.cash_manual_protocol import ManualCashRecordInput

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "compose/backend/src/test/resources/python-manual-cash-protocol.json"
BASE = {"amount": "-10", "currency": "CNY", "occurred_at": "2026-08-04T12:00:00+00:00"}
PART = {"account_name": "fixture", "amount": "-10", "label": "现金"}


def strings(value, path=""):
    if isinstance(value, str):
        return {path: repr(value)}
    if isinstance(value, dict):
        return {key: item for field, child in value.items() for key, item in strings(child, f"{path}/{field}").items()}
    if isinstance(value, list):
        return {key: item for index, child in enumerate(value) for key, item in strings(child, f"{path}/{index}").items()}
    return {}


def generate():
    result = []

    def add_bytes(label, raw, application=None):
        accepted, payload, representations = False, None, {}
        try:
            if len(raw) > 65536:
                raise ValueError("budget")
            payload = strict_json(raw, ManualCashRecordInput).model_dump(exclude_unset=True)
            accepted, representations = True, strings(payload)
        except (ValueError, RecursionError):
            pass
        case = {"label": label, "bytes": base64.b64encode(raw).decode(), "accepted": accepted}
        if accepted:
            case.update(payload=payload, string_reprs=representations)
        if application is not None:
            try:
                _manual_create_payload(application)
                case["application_accepted"] = True
            except ValueError:
                case["application_accepted"] = False
        result.append(case)

    def add(label, data):
        add_bytes(label, json.dumps(data, ensure_ascii=True, separators=(",", ":")).encode(), data)

    add("minimal", BASE)
    values = [None, False, True, 0, -1, 1.0, 1e20, "", "fixture", [], {}, ["cash"], [1]]
    for field in ["account_name", "amount", "currency", "occurred_at", "counterparty", "counterparty_account",
                  "counterparty_account_attrs", "note", "category_id", "category", "record_type", "record_subtype", "components"]:
        for index, value in enumerate(values):
            add(f"field-{field}-{index}", {**BASE, field: value})
    for field in ["account_name", "account_id", "amount", "currency", "label"]:
        for index, value in enumerate(values):
            add(f"component-{field}-{index}", {**BASE, "components": [{**PART, field: value}]})
    for field in ["relation_metadata", "manual_overrides", "state", "source_type", "record_id", "source_payload",
                  "workspace_id", "row_revision", "deleted_at", "id", "account_type", "component_allocation"]:
        add(f"private-{field}", {**BASE, field: {"private-input": "forged"}})
    for field in ["metadata_json", "source_key", "component_id", "id", "ordinal", "account_type"]:
        add(f"component-private-{field}", {**BASE, "components": [{**PART, field: "forged"}]})
    for field in BASE:
        data = deepcopy(BASE)
        data.pop(field)
        add(f"missing-{field}", data)
    for count in [0, 1, 64, 65]:
        add(f"components-count-{count}", {**BASE, "components": [PART] * count})
    for text in ["", "{}", "[]", "null", '{"amount":"1","amount":"2"}', '{"amount":NaN}', '{"amount":1e999}',
                 '{"components":[{"amount":1,"amount":2}]}', '{"amount":1,}', '{"amount":"\\x01"}']:
        add_bytes(f"syntax-{len(result)}", text.encode())
    for token in ["-0", "9" * 4300, "9" * 4301]:
        raw = json.dumps(BASE).replace('"-10"', token).encode()
        add_bytes(f"integer-{len(token)}-{token[:2]}", raw)
    for text in ["现金🦝", "\ud800", "\udc00", "\ud83d\ude00"]:
        data = {**BASE, "note": text}
        add(f"escaped-unicode-{len(result)}", data)
        for codec in ["utf-8", "utf-16-le", "utf-16-be", "utf-32-le", "utf-32-be"]:
            raw = json.dumps(data, ensure_ascii=False).encode(codec, errors="surrogatepass")
            add_bytes(f"raw-unicode-{len(result)}-{codec}", raw)
    for codec in ["utf-8-sig", "utf-16", "utf-16-le", "utf-16-be", "utf-32", "utf-32-le", "utf-32-be"]:
        add_bytes(f"encoding-{codec}", json.dumps(BASE).encode(codec))
    add_bytes("invalid-encoding", b'{"note":"\xff"}')
    add_bytes("deep-json", b"[" * 2000 + b"0" + b"]" * 2000)
    minimal = json.dumps(BASE).encode()
    add_bytes("exact-byte-budget", minimal + b" " * (65536 - len(minimal)))
    add_bytes("over-byte-budget", minimal + b" " * (65537 - len(minimal)))
    return (json.dumps(result, ensure_ascii=True, indent=2) + "\n").encode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    expected = generate()
    if args.check:
        if OUTPUT.read_bytes() != expected:
            raise SystemExit("manual cash protocol goldens differ")
    else:
        OUTPUT.write_bytes(expected)
    print(f"{len(json.loads(expected))} anonymous manual-cash protocol cases")


if __name__ == "__main__":
    main()
