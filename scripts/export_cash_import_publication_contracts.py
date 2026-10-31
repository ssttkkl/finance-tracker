"""Prepare isolated source ledger fixtures for native import-plan publication assertions."""
from __future__ import annotations

import argparse
from pathlib import Path

from export_cash_import_original_commit_contracts import export


SCENARIOS = ("publication", "immutable", "repreview", "paging", "absolute", "invalidation", "consumed",
    "permission", "builder_failure", "invalid_input", "private_items", "malformed", "processing_cursor",
    "private_nested", "safe_issues", "invalid_scope", "swept", "no_code")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--scenario", required=True, choices=SCENARIOS)
    args = parser.parse_args()
    # Publication assertions construct the original synthetic builder output in Kotlin.
    # Only the original anonymous ledger, editor and authentication sessions come from Python.
    export(args.database_url, args.output / args.scenario, "formal")


if __name__ == "__main__":
    main()
