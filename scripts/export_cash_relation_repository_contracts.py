"""Export relation repository read/write contracts from a Python-seeded isolated database."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
from decimal import Decimal
import json
from pathlib import Path
from urllib.parse import urlparse

from alembic import command
from alembic.config import Config
from sqlalchemy import inspect, select

from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational.models import (
    AccountAliasModel, AccountModel, CashRelationCandidateModel, CashTransactionComponentModel,
    CashTransactionModel, TransactionRelationModel, WorkspaceModel,
)
from ft.adapters.relational.repositories import RelationalRelationRepository

NOW = datetime(2026, 10, 1, 9, 0, tzinfo=timezone.utc)
WORKSPACE = "relation-workspace"


def connect(url: str):
    root = Path(__file__).resolve().parents[1]
    if url.startswith("sqlite+pysqlite:///"):
        path = Path(url.removeprefix("sqlite+pysqlite:///"))
        if not path.is_absolute() or path.exists():
            raise ValueError("use a new absolute SQLite test path")
        path.parent.mkdir(parents=True, exist_ok=True)
        jdbc = "jdbc:sqlite:" + str(path)
    else:
        parsed = urlparse(url)
        if parsed.scheme != "postgresql+psycopg" or not parsed.path.endswith("_test"):
            raise ValueError("PostgreSQL must target a dedicated _test database")
        jdbc = f"jdbc:postgresql://{parsed.hostname}:{parsed.port or 5432}{parsed.path}"
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    engine = create_relational_engine(url)
    if inspect(engine).get_table_names():
        raise ValueError("test database must be empty")
    with engine.begin() as connection:
        config.attributes["connection"] = connection
        command.upgrade(config, "head")
    return engine, create_session_factory(engine), jdbc


def seed(sessions):
    with sessions.begin() as session:
        session.add(WorkspaceModel(id=WORKSPACE, name="关系仓库工作区", created_at=NOW)); session.flush()
        session.add_all([
            AccountModel(id=9301, workspace_id=WORKSPACE, name="现金", type="cash", active=True,
                currencies=["CNY"], metadata_json={}, revision=1, created_at=NOW, updated_at=NOW),
            AccountModel(id=9302, workspace_id=WORKSPACE, name="工资卡", type="cash", active=True,
                currencies=["CNY"], metadata_json={}, revision=1, created_at=NOW, updated_at=NOW),
        ]); session.flush()
        rows = (
            (93001, 9301, "alipay", "REL-1", "consumption", "-100.00", "atomic", (("94001", "-100.00"),)),
            (93002, 9301, "wechat", "REL-2", "refund", "30.00", "atomic", (("94002", "30.00"),)),
            (93003, 9302, "ccb_debit", "REL-3", "transfer_out", "-50.00", "atomic", (("94003", "-50.00"),)),
            (93004, 9302, "icbc_debit", "REL-4", "transfer_in", "50.00", "atomic", (("94004", "50.00"),)),
            (93005, 9301, "alipay", "REL-5", "consumption", "-20.00", "atomic", (("94005", "-20.00"),)),
            (93006, 9302, "alipay", "REL-6", "consumption", "-20.00", "aggregate",
                (("94006", "-12.00"), ("94007", "-8.00"))),
            (93007, 9302, "icbc_debit", "REL-7", "consumption", "-30.00", "atomic", (("94008", "-30.00"),)),
        )
        for transaction_id, account, source, record, record_type, amount, granularity, parts in rows:
            session.add(CashTransactionModel(id=transaction_id, workspace_id=WORKSPACE,
                account_id=None if granularity == "aggregate" else account,
                state="posted", record_origin="import", cash_granularity=granularity, source_type=source,
                record_id=record, source_payload=({"订单号": "REL-1", "交易状态": "交易成功", "交易单号": "TXN-1"}
                    if transaction_id == 93001 else {}), occurred_at=NOW, amount=amount, currency="CNY",
                counterparty="商户", counterparty_account="", counterparty_account_attrs=[], note="",
                record_type=record_type, row_revision=1, created_at=NOW,
                record_subtype="ordinary_transfer" if record_type.startswith("transfer_") else "not_applicable"))
        session.flush()
        for transaction_id, _account, _source, _record, _type, _amount, _granularity, parts in rows:
            for ordinal, (component_id, amount) in enumerate(parts):
                session.add(CashTransactionComponentModel(id=int(component_id), workspace_id=WORKSPACE,
                    cash_transaction_id=transaction_id, account_id=9301, amount=amount, currency="CNY",
                    ordinal=ordinal, label="", source_key="", metadata_json={}, created_at=NOW))
        session.flush()
        session.add(TransactionRelationModel(id=9001, workspace_id=WORKSPACE, kind="payment_mirror", subtype="",
            primary_transaction_id=93001, secondary_transaction_id=93005,
            primary_component_id=94001, secondary_component_id=94005, anchor_transaction_id=93001,
            anchor_component_id=94001, primary_role="expense", secondary_role="expense", state="confirmed",
            active=True, row_revision=1, decision_source="rule", evidence={}, applied_amount=Decimal("20.00"),
            rule_id="mirror.seed.v1", created_by="system", created_at=NOW, decided_by="",
            decided_at=None, decision_reason=""))
        session.flush()
        session.add_all([
            AccountAliasModel(workspace_id=WORKSPACE, alias_type="card_tail", alias_value="1234",
                account_id=9301, created_at=NOW, updated_at=NOW),
            AccountAliasModel(workspace_id=WORKSPACE, alias_type="account_identifier",
                alias_value="622202123456", account_id=9302, created_at=NOW, updated_at=NOW),
        ])
        session.add(CashRelationCandidateModel(workspace_id=WORKSPACE, relation_id=9001,
            candidate_transaction_id=93007, candidate_component_id=94008, expected_row_revision=1,
            evidence={}, validity="valid"))
    return sessions


def norm(value, labels):
    if isinstance(value, Decimal):
        return format(Decimal(str(value)).normalize(), "f")
    if isinstance(value, datetime):
        return value.isoformat() if value == NOW else "<now>"
    if isinstance(value, dict):
        result = {key: norm(item, labels) for key, item in value.items()}
        if isinstance(result.get("id"), (int, str)) and result["id"] in labels:
            result["id"] = labels[result["id"]]
        return result
    if isinstance(value, (list, tuple)):
        return [norm(item, labels) for item in value]
    return value


def export(database_url: str, output: Path) -> None:
    engine, sessions, jdbc = connect(database_url)
    labels = {}
    cases = {}
    try:
        sessions = seed(sessions)
        with sessions.begin() as session:
            repo = RelationalRelationRepository(session, WORKSPACE)

            def added(label, value):
                labels[value] = label
                return value

            def capture(call):
                try:
                    return norm(call(), labels)
                except Exception as error:  # noqa: BLE001 - golden records public failure text
                    return {"error": f"{type(error).__name__}: {error}"}

            cases["list_all"] = capture(repo.list_active)
            cases["list_kind"] = capture(lambda: repo.list_active(kind="payment_mirror"))
            cases["list_accepted"] = capture(lambda: repo.list_active(status="accepted"))
            cases["list_superseded"] = capture(lambda: repo.list_active(status="superseded"))
            cases["get"] = capture(lambda: repo.get(9001))
            cases["get_missing"] = capture(lambda: repo.get(999999))
            cases["find"] = capture(lambda: repo.find_by_business_key(kind="payment_mirror",
                fact_a=93001, fact_b=93005))
            cases["find_reversed"] = capture(lambda: repo.find_by_business_key(
                kind="payment_mirror", fact_a=94005, fact_b=94001, component_a=94005, component_b=94001))
            cases["find_missing"] = capture(lambda: repo.find_by_business_key(
                kind="transfer_pair", fact_a=93003, fact_b=93004))
            cases["for_facts"] = capture(lambda: repo.list_for_facts([93001]))
            cases["for_components"] = capture(lambda: repo.list_for_components([94002, 94001]))

            refund_id = added("added_1", repo.add({"kind": "refund_offset", "primary_fact_id": "93001",
                "secondary_fact_id": "93002", "status": "accepted", "rule_id": "refund.seed.v1",
                "applied_amount": "10.00", "created_by": "system", "anchor_fact_id": "93001"}))
            mirror_id = added("added_2", repo.add({"kind": "payment_mirror", "primary_fact_id": "93002",
                "secondary_fact_id": "93004", "status": "accepted", "rule_id": "mirror.seed.v1"}))
            open_id = added("added_3", repo.add({"kind": "transfer_pair", "primary_fact_id": "93003",
                "status": "pending_review", "rule_id": "transfer.open.v1", "anchor_fact_id": "93003",
                "candidate_fact_ids": [94004, 94008], "subtype": "ordinary_transfer"}))
            cases["added"] = [capture(lambda: repo.get(refund_id)), capture(lambda: repo.get(mirror_id)),
                capture(lambda: repo.get(open_id))]
            cases["add_errors"] = [
                capture(lambda: repo.add({"kind": "refund_offset", "primary_fact_id": "93001",
                    "secondary_fact_id": "93002", "status": "accepted", "applied_amount": "-1.00"})),
                capture(lambda: repo.add({"kind": "refund_offset", "primary_fact_id": "93005",
                    "secondary_fact_id": "93002", "status": "accepted", "applied_amount": "999.00"})),
                capture(lambda: repo.add({"kind": "refund_offset", "primary_fact_id": "93006",
                    "secondary_fact_id": "93002", "status": "accepted"})),
                capture(lambda: repo.add({"kind": "refund_offset", "primary_fact_id": "93001",
                    "secondary_fact_id": "93002", "secondary_component_id": 999999, "status": "accepted"})),
            ]
            cases["open_leg"] = capture(lambda: repo.find_open_leg(kind="transfer_pair",
                anchor_fact_id=93003, subtype="ordinary_transfer"))
            cases["bind"] = capture(lambda: repo.bind_other_leg(open_id, other_fact_id=93004,
                status="accepted", decided_by="user", decision_reason="人工配对"))
            cases["bind_candidates"] = capture(lambda: repo.update_open_leg_candidates(open_id, [94008]))
            cases["reject"] = capture(lambda: repo.update_status(refund_id, status="rejected",
                decided_by="user", decision_reason="人工拒绝"))
            cases["supersede"] = capture(lambda: repo.update_status(9001, status="superseded"))
            cases["batch"] = capture(lambda: repo.update_status_batch([mirror_id], status="accepted",
                decided_by="system"))
            cases["batch_superseded"] = capture(lambda: repo.update_status_batch([mirror_id],
                status="superseded"))
            cases["update_kind"] = capture(lambda: repo.update_kind(mirror_id, kind="refund_offset",
                subtype="p2p_return", actor="user", reason="改类"))
            cases["final_active"] = capture(repo.list_active)
            rows = session.scalars(select(TransactionRelationModel).order_by(TransactionRelationModel.id)).all()
            cases["final_rows"] = sorted([norm({key: getattr(row, key) for key in (
                "id", "kind", "subtype", "primary_transaction_id", "secondary_transaction_id",
                "primary_component_id", "secondary_component_id", "anchor_transaction_id",
                "anchor_component_id", "primary_role", "secondary_role", "state", "active",
                "row_revision", "decision_source", "applied_amount", "rule_id", "created_by",
                "decided_by", "decision_reason")}, labels) for row in rows], key=lambda row: str(row["id"]))
            cases["final_candidates"] = sorted(
                [labels.get(row.relation_id, row.relation_id), row.candidate_component_id] for row in
                session.scalars(select(CashRelationCandidateModel)).all())
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
