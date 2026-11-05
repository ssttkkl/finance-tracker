"""Generate public OpenAPI metadata from preserved contracts and Kotlin-owned additions."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
from types import SimpleNamespace

from ft.web.app import create_app


def document():
    services = SimpleNamespace(parsed_import_context=lambda: None)
    app = create_app(services, mutation_service=services, category_service=services,
        classification_service=services, investment_service=services, portfolio_service=services,
        portfolio_refresh=services, access_service=services, operations_service=services)
    result = app.openapi()
    additions = json.loads((Path(__file__).resolve().parents[1] / "compose/backend/openapi-additions.json").read_text())
    for path, operations in additions["paths"].items():
        if path in result["paths"]:
            raise ValueError(f"Kotlin addition duplicates preserved path: {path}")
        result["paths"][path] = operations
    for section, definitions in additions["components"].items():
        target = result["components"].setdefault(section, {})
        if target.keys() & definitions.keys():
            raise ValueError(f"Kotlin addition duplicates preserved component: {section}")
        target.update(definitions)
    # Receipts reuse the Kotlin-owned fact-version field; legacy financial models stay untouched.
    schemas = result["components"]["schemas"]
    receipt_properties = schemas["MutationReceiptView"]["properties"]
    if "ledger_seq" in receipt_properties:
        raise ValueError("Preserved receipt already defines the Kotlin-owned ledger sequence")
    receipt_properties["ledger_seq"] = dict(schemas["LedgerVersion"]["properties"]["ledger_seq"])
    signatures = []
    for path, operations in result["paths"].items():
        for method in operations:
            if method in {"get", "post", "put", "patch", "delete", "head", "options", "trace"}:
                signatures.append((method, re.sub(r"\{[^}]+\}", "{}", path)))
    if len(signatures) != len(set(signatures)):
        raise ValueError("Ambiguous original operation paths")
    if app.docs_url is not None or app.redoc_url is not None:
        raise ValueError("Review documentation entry-point changes explicitly")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1] /
        "compose/backend/src/main/resources/openapi-contract.json")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    result = document()
    content = json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2) + "\n"
    if args.check:
        if not args.output.is_file() or args.output.read_text() != content:
            raise SystemExit("OpenAPI contract differs; regenerate and review before delivery")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content)
    operations = sum(len(value) for value in result["paths"].values())
    print(f"verified public contract: {operations} operations, {len(result['paths'])} paths")


if __name__ == "__main__":
    main()
