"""Export anonymous candidate-read contracts from a dedicated empty test database."""
from __future__ import annotations

import argparse
from dataclasses import asdict
from datetime import datetime
import json
from pathlib import Path
from types import SimpleNamespace

from ft.adapters.relational.models import (
    AccountModel, CashSourceBindingModel, CashTransactionComponentModel, CashTransactionModel, WorkspaceModel,
)
from ft.application.cash_import_business_plan import CashImportBusinessPlanBuilder
from ft.domain.cash_source_matching import SourceCoverage, _digest
from ft.domain.cash_source_scope import WECHAT_DEFAULT_SCOPE

from export_cash_import_operation_contracts import connect, seed, NOW, WORKSPACE, OWNER


def record(identifier, source, account, occurred, order, *, scope=None):
    row = CashTransactionModel(
        id=identifier, workspace_id=WORKSPACE, account_id=account, state="posted",
        record_origin="import", cash_granularity="atomic", source_type=source,
        record_id=f"record-{identifier}", source_payload={"商家订单号": order},
        occurred_at=datetime.fromisoformat(occurred), amount="-5.00", currency="CNY",
        counterparty="合成商户", counterparty_account="", counterparty_account_attrs=[], note="",
        record_type="consumption", record_subtype="not_applicable", row_revision=1, created_at=NOW,
    )
    part = CashTransactionComponentModel(
        id=identifier + 2000, workspace_id=WORKSPACE, cash_transaction_id=identifier,
        account_id=account, amount="-5.00", currency="CNY", ordinal=0,
        label="", source_key="", metadata_json={}, created_at=NOW,
    )
    binding = None if scope is None else CashSourceBindingModel(
        workspace_id=WORKSPACE, transaction_id=identifier, source_type=source,
        source_scope_kind="account", source_scope_key=scope,
        identity_kind="file_occurrence", identity_key=_digest(f"record-{identifier}"),
        identity_strength="file_local", stable_evidence_key=None,
        strategy_version="source-coverage-v1", coverage=asdict(SourceCoverage()),
        revision=1, created_at=NOW, updated_at=NOW,
    )
    return row, part, binding


def operation(*, date="2026-10-02T10:00:00+00:00", source="alipay", scope=OWNER,
              record_type="consumption", amount="-5.00", account="9201", explanation=None):
    return {"values": {"source_type": source, "record_type": record_type, "amount": amount,
                       "occurred_at": date, "components": [{"account_id": account}]},
            "source_scope_key": scope, "row_refs": [], "source_explanation": explanation or {}}


def export(database_url: str, output: Path):
    engine, sessions, jdbc = connect(database_url)
    try:
        seed(sessions)
        with sessions.begin() as session:
            session.add(WorkspaceModel(id="other-workspace", name="隔离工作区", created_at=NOW))
            session.flush()
            session.add(AccountModel(id=9203, workspace_id="other-workspace", name="隔离账户", type="cash",
                                     active=True, currencies=["CNY"], metadata_json={}, revision=1,
                                     created_at=NOW, updated_at=NOW))
            foreign, foreign_part, _ = record(7507, "alipay", 9203, "2027-01-01T10:00:00+00:00", "FOREIGN-ONLY")
            foreign.workspace_id = "other-workspace"
            foreign_part.workspace_id = "other-workspace"
            session.add(foreign)
            session.flush()
            session.add(foreign_part)
            for args in (
                (7503, "alipay", 9201, "2027-01-01T10:00:00+00:00", "MERCHANT-1", None),
                (7504, "alipay", 9201, "2027-01-01T10:00:00+00:00", "MERCHANT-1", "other-owner"),
                (7505, "alipay", 9202, "2027-01-01T10:00:00+00:00", "MERCHANT-1", None),
                (7506, "external-unknown", 9201, "2026-10-02T10:00:00+00:00", "OTHER-1", None),
            ):
                row, part, binding = record(*args[:5], scope=args[5])
                session.add(row)
                session.flush()
                session.add(part)
                if binding is not None:
                    session.add(binding)
        far = "2027-06-01T10:00:00+00:00"
        row_ref = "source-row"
        def from_row(explanation, scope=OWNER):
            item = operation(date=far, explanation=explanation)
            item["row_refs"] = [row_ref]
            return [item], {row_ref: {"source_type": "alipay", "account_scope_key": scope,
                                     "provider_id": "ALI-1"}}
        cases = {
            "empty": ([], {}),
            "temporal_expense": ([operation()], {}),
            "temporal_refund": ([operation(record_type="refund", amount="5.00")], {}),
            "temporal_negative_zero": ([operation(amount="-0.00")], {}),
            "bound_far": ([operation(date=far, explanation={"txn_id": "ALI-1"})], {}),
            "alipay_prefix_far": ([operation(date=far, explanation={"txn_id": "ALI-1_suffix"})], {}),
            "merchant_far": ([operation(date=far, explanation={"merchant_order_id": "MERCHANT-1"})], {}),
            "other_account_far": ([operation(date=far, account="9202", explanation={"merchant_order_id": "MERCHANT-1"})], {}),
            "foreign_workspace_far": ([operation(date=far, account="9203", explanation={"merchant_order_id": "FOREIGN-ONLY"})], {}),
            "draft_bound_far": ([operation(date=far, source="wechat", scope=WECHAT_DEFAULT_SCOPE,
                                           explanation={"txn_id": "W1"})], {}),
            "row_provider_fallback": from_row({}),
            "row_null_scope": from_row({}, scope=None),
            "row_explicit_null_txn": from_row({"txn_id": None}),
            "merchant_batch_boundary": ([operation(date=far, explanation={"merchant_order_id": f"MISS-{index}"})
                                         for index in range(100)] +
                                        [operation(date=far, explanation={"merchant_order_id": "MERCHANT-1"})], {}),
        }
        builder = CashImportBusinessPlanBuilder(WORKSPACE)
        with sessions() as session:
            results = [{"name": name, "operations": operations, "source_rows": rows,
                        "candidate_ids": sorted(builder._candidate_ids(session, operations,
                            {ref: SimpleNamespace(account_scope_key=row["account_scope_key"], provider_id=row["provider_id"])
                             for ref, row in rows.items()}))}
                       for name, (operations, rows) in cases.items()]
        output.mkdir(parents=True, exist_ok=True)
        (output / "database.json").write_text(json.dumps({"jdbc_url": jdbc}) + "\n")
        (output / "candidate-cases.json").write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")
    finally:
        engine.dispose()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    export(args.database_url, args.output)
