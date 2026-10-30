"""Export relation planning service results from a Python-seeded isolated database."""
from __future__ import annotations

import argparse
from dataclasses import asdict
from datetime import datetime, timezone
from decimal import Decimal
import json
from pathlib import Path
from urllib.parse import urlparse

from alembic import command
from alembic.config import Config
from sqlalchemy import delete, inspect

from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational.models import CashRelationCandidateModel, TransactionRelationModel
from ft.adapters.relational.repositories import (
    RelationalAccountAliasRepository, RelationalCashflowRepository, RelationalRelationRepository,
)
from ft.application.cash_import_operations import _wire
from ft.application.relations import RelationService, relation_proposal_key, serialize_import_relation_plan
from scripts.export_cash_relation_repository_contracts import NOW, WORKSPACE, connect, seed

FACT_KEYS = ("id", "amount", "currency", "account_id", "account_name", "account_type", "occurred_at",
    "counterparty", "counterparty_account", "counterparty_account_attrs", "payment_method", "note",
    "record_type", "record_subtype", "bill_source", "source", "record_id", "source_identity",
    "parent_id", "component_ordinal", "cash_granularity", "raw_payload", "relation_metadata")


class RealUoW:
    def __init__(self, session):
        self.workspace_id = WORKSPACE
        self.cashflows = RelationalCashflowRepository(session, WORKSPACE)
        self.relations = RelationalRelationRepository(session, WORKSPACE)
        self.account_aliases = RelationalAccountAliasRepository(session, WORKSPACE)


def fact_case(fact):
    data = asdict(fact)
    data["amount"] = format(fact.signed_amount, "f")
    data["account_id"] = "" if data["account_id"] is None else str(data["account_id"])
    data["parent_id"] = None if data["parent_id"] is None else str(data["parent_id"])
    return {key: _wire(data[key]) for key in FACT_KEYS if key in data}


def proposal_case(proposal, facts):
    return {"key": relation_proposal_key(proposal, facts), "body": _wire(asdict(proposal))}


def plan_case(plan):
    return {
        "facts": [fact_case(item) for item in plan.facts],
        "proposals": [proposal_case(item, plan.facts) for item in plan.proposals],
        "context_digest": plan.context_digest,
        "external_context_digest": plan.external_context_digest,
    }


def preview_row():
    return {"record_id": "PREVIEW-1", "amount": "-15.00", "currency": "CNY",
        "occurred_at": "2026-10-05T10:00:00+00:00", "record_type": "consumption",
        "record_subtype": "not_applicable", "counterparty": "预览商户", "note": "预览",
        "bill_source": "alipay", "source_type": "alipay", "account_id": 9301,
        "account_name": "现金", "account_type": "cash", "components": []}


def export(database_url: str, output: Path) -> None:
    engine, sessions, jdbc = connect(database_url)
    cases = {}
    try:
        sessions = seed(sessions)
        with sessions.begin() as session:
            session.execute(delete(CashRelationCandidateModel).where(
                CashRelationCandidateModel.workspace_id == WORKSPACE))
            session.execute(delete(TransactionRelationModel).where(
                TransactionRelationModel.workspace_id == WORKSPACE))
        with sessions() as session:
            uow = RealUoW(session)
            service = RelationService(uow)
            plan = service.plan_in_uow(uow)
            preview = service.plan_in_uow(uow, preview_rows=[preview_row()], seed_ids=["93001"])
            external = service.external_context_digest_in_uow(uow)
            cached = serialize_import_relation_plan(plan)
            results = []
            for cached_plan, expected in ((cached, None), (cached, plan.context_digest),
                    (dict(cached, external_context_digest="0" * 64), None)):
                try:
                    service.validate_cached_import_plan_context_in_uow(uow, cached_plan,
                        expected_plan_digest=expected)
                    results.append(None)
                except ValueError as error:
                    results.append(str(error))
            cases = {"plan": plan_case(plan), "preview": plan_case(preview),
                "external_digest": external, "validation": results}
        output.mkdir(parents=True, exist_ok=True)
        (output / "expected.json").write_text(json.dumps({"cases": cases}, ensure_ascii=False,
            indent=2, default=str) + "\n")
        (output / "database.json").write_text(json.dumps({"jdbc_url": jdbc}) + "\n")
    finally:
        engine.dispose()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    export(args.database_url, args.output)
