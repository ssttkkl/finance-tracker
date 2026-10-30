"""Export complete batch results while executing the original functional assertions."""
from __future__ import annotations

import argparse
from dataclasses import asdict
import importlib
import itertools
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tests"))
from export_cash_source_match_goldens import cov_export, record_export, row_export
import ft.domain.cash_source_batch as domain


def export() -> str:
    source = importlib.import_module("test_cash_source_batch")
    original = domain.resolve_source_batch
    captured = []
    current = ""

    def capture(rows, records, **kwargs):
        result = original(rows, records, **kwargs)
        captured.append({"source": current, "rows": [
            {"ref": ref, **row_export(row), "stable_evidence": row.stable_evidence}
            for ref, row in rows.items()], "records": [record_export(record) for record in records],
            "file_metadata": rows.file_metadata,
            "document_evidence": [{"source_type": proof.source_type,
                "parser_version": proof.parser_version, "coverage": cov_export(proof.coverage)}
                for proof in rows.document_evidence],
            "expected": asdict(result)})
        return result

    domain.resolve_source_batch = capture
    try:
        for name, function in vars(source).items():
            if not name.startswith("test_") or name.startswith("test_large_"):
                continue
            parameters = [mark for mark in getattr(function, "pytestmark", []) if mark.name == "parametrize"]
            choices = [mark.args[1] for mark in parameters]
            for index, combination in enumerate(itertools.product(*choices)):
                current = f"{name}[{index}]"
                arguments = {}
                for mark, value in zip(parameters, combination):
                    names = mark.args[0].split(",")
                    arguments.update(zip(names, [value] if len(names) == 1 else value))
                function(**arguments)
    finally:
        domain.resolve_source_batch = original
    return json.dumps(captured, ensure_ascii=False, indent=2) + "\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    content = export()
    if args.check:
        if args.output.read_text() != content:
            raise SystemExit("batch golden is stale")
    else:
        args.output.write_text(content)
