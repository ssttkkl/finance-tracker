"""Capture anonymous original funding test inputs before the first service call."""
from __future__ import annotations

import argparse
from hashlib import sha256
import json
from pathlib import Path
from unittest.mock import patch

from sqlalchemy import select

from export_cash_import_operation_contracts import connect
from ft.adapters.relational.models import AccountModel, CashTransactionModel, InvestmentEventModel
from ft.adapters.relational.uow import ensure_workspace
from ft.application.cash_investment_funding_relations import CashInvestmentFundingRelationService
from tests import test_cash_investment_funding_relations as original


class InputCaptured(BaseException):
    pass


def groups():
    result = {}
    for name, function in vars(original).items():
        if not name.startswith("test_") or not callable(function):
            continue
        if name == "test_complete_cash_draft_is_not_a_funding_candidate_until_posted":
            for record_type in ("investment_out", "transfer_out"):
                result[name + "-" + record_type] = (function, {"record_type": record_type})
        else:
            arguments = {"monkeypatch": None} if "competing_workspace_delete" in name else {}
            result[name] = (function, arguments)
    return result


def export(config: Path, output: Path):
    repository = Path(__file__).resolve().parents[1]
    urls = json.loads(config.read_text())
    cases = groups()
    if set(urls) != set(cases) or len(set(urls.values())) != len(urls):
        raise ValueError("Supply a distinct explicit empty database for every original input")
    if not output.is_absolute() or output.resolve().is_relative_to(repository):
        raise ValueError("Output must be outside the repository")
    output.mkdir(parents=True, exist_ok=False)
    def captured(*args, **kwargs):
        raise InputCaptured()
    for name, (function, arguments) in cases.items():
        engine, sessions, jdbc = connect(urls[name])
        try:
            ensure_workspace(sessions, "funding")
            with sessions.begin() as session:
                session.add_all([
                    AccountModel(workspace_id="funding", name="Bank", type="cash", active=True, metadata_json={}),
                    AccountModel(workspace_id="funding", name="Broker", type="security", active=True, metadata_json={}),
                ])
            with patch.object(CashInvestmentFundingRelationService, "__init__", captured):
                try:
                    function((sessions, AccountModel, CashTransactionModel, InvestmentEventModel), **arguments)
                except InputCaptured:
                    pass
                else:
                    raise AssertionError("Original test did not reach its first service construction: " + name)
            with sessions() as session:
                accounts = {row.name: row.id for row in session.scalars(select(AccountModel))}
            target = output / name
            target.mkdir()
            (target / "database.json").write_text(json.dumps({"jdbc_url": jdbc, "workspace": "funding", "accounts": accounts}, indent=2) + "\n")
        finally:
            engine.dispose()
    source = Path(original.__file__)
    (output / "provenance.json").write_text(json.dumps({"source": str(source.relative_to(repository)),
        "sha256": sha256(source.read_bytes()).hexdigest(), "groups": list(cases),
        "capture_boundary": "first real application service construction; no result is substituted"}, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--databases", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    export(args.databases, args.output)
