"""Export anonymous record-detail HTTP oracle cases into an explicitly isolated test database."""
from __future__ import annotations

import argparse
from contextvars import ContextVar
from datetime import datetime, timezone
from hashlib import sha256
import json
from pathlib import Path
from urllib.parse import urlparse

from alembic import command
from alembic.config import Config
from fastapi.testclient import TestClient
from sqlalchemy import inspect

from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational.models import (
    AccountModel, CashCategoryModel, CashCategoryStateModel, CashEntryMemberModel, CashEntryModel,
    CashIssueCandidateModel, CashProjectionDatasetModel, CashProjectionMemberModel, CashProjectionModel,
    CashProjectionStateModel, CashRelationCandidateModel, CashSourceBindingModel, CashTransactionComponentModel,
    CashTransactionIssueModel, CashTransactionModel, TransactionRelationModel, UserModel, UserSessionModel,
    WorkspaceMembershipModel, WorkspaceModel,
)
from ft.application.access import AccessService
from ft.web.app import WorkspaceServices, create_app

NOW = datetime(2026, 1, 1, tzinfo=timezone.utc)


def _session(session, token, user, workspace, *, expired=False):
    session.add(UserSessionModel(id="session-" + token, user_id=user,
        token_digest=sha256(("synthetic-" + token).encode()).hexdigest(), active_workspace_id=workspace,
        expires_at=datetime(2020 if expired else 2099, 1, 1, tzinfo=timezone.utc), created_at=NOW))


def _fact(session, identifier, workspace, **changes):
    values = dict(id=identifier, workspace_id=workspace, state="posted", record_origin="import",
        row_revision=1, cash_granularity="atomic", occurred_at=datetime(2026, 7, 1, 8, tzinfo=timezone.utc),
        amount=None, currency="CNY", counterparty="", counterparty_account="", counterparty_account_attrs=[],
        note="", category_id=None, record_type="consumption", record_subtype="not_applicable", record_id="",
        source_type=None, source_payload=None, relation_metadata=None, manual_overrides={}, created_at=NOW)
    values.update(changes)
    session.add(CashTransactionModel(**values))


def _component(session, identifier, parent, account, amount, ordinal=0):
    session.add(CashTransactionComponentModel(id=identifier, workspace_id=parent_workspace(parent),
        cash_transaction_id=parent, account_id=account, amount=amount, currency="CNY", ordinal=ordinal,
        label="", source_key="", metadata_json={}, created_at=NOW))


_parent_workspace = {}


def parent_workspace(identifier):
    return _parent_workspace[int(identifier)]


def _entry(session, identifier, root, workspace, *, members=(), **changes):
    values = dict(id=identifier, workspace_id=workspace, root_transaction_id=root, revision=1, category_id=None,
        note="", counterparty="", counterparty_account="", counterparty_account_attrs=[], manual_fields=[],
        split_inherit=None)
    values.update(changes)
    session.add(CashEntryModel(**values))
    session.flush()
    for transaction, values_json, manual in members:
        session.add(CashEntryMemberModel(workspace_id=workspace, transaction_id=transaction, entry_id=identifier,
            standalone_values=values_json, standalone_manual_fields=manual))


def _relation(session, identifier, workspace, primary, secondary, kind, state, *, components, anchor=None,
        group=None, applied="0", subtype=""):
    session.add(TransactionRelationModel(id=identifier, workspace_id=workspace, kind=kind, subtype=subtype,
        primary_transaction_id=primary, secondary_transaction_id=secondary, anchor_transaction_id=anchor or primary,
        primary_component_id=components[0] if components else None,
        secondary_component_id=components[1] if len(components) > 1 else None,
        anchor_component_id=components[0] if components else None,
        primary_role=None if state != "confirmed" else ("expense" if kind != "transfer_pair" else "outgoing"),
        secondary_role=None if state != "confirmed" else ("refund" if kind == "refund_offset" else
            "expense" if kind == "payment_mirror" else "incoming"),
        state=state, active=True, row_revision=1, decision_group_id=group, decision_source="rule", evidence={},
        applied_amount=applied, rule_id="fixture.v1", created_by="system", created_at=NOW, decided_by="",
        decision_reason=""))


def seed(sessions):
    with sessions.begin() as session:
        session.add_all([WorkspaceModel(id="detail-a", name="详情工作区", created_at=NOW),
                         WorkspaceModel(id="detail-b", name="隔离工作区", created_at=NOW)])
        session.add_all([UserModel(id=name, email=name + "@example.invalid", password_hash="unused-anonymous-fixture",
            created_at=NOW) for name in ("admin", "editor", "viewer", "outsider")])
        session.flush()
        session.add_all([WorkspaceMembershipModel(workspace_id="detail-a", user_id=user, role=role, created_at=NOW)
            for user, role in (("admin", "admin"), ("editor", "editor"), ("viewer", "viewer"))])
        for token, user, workspace, expired in (
                ("admin", "admin", "detail-a", False), ("editor", "editor", "detail-a", False),
                ("viewer", "viewer", "detail-a", False), ("outsider", "outsider", "detail-a", False),
                ("expired", "admin", "detail-a", True), ("other-workspace", "admin", "detail-b", False)):
            _session(session, token, user, workspace, expired=expired)
        session.add(CashCategoryStateModel(workspace_id="detail-a", revision=3, updated_at=NOW))
        session.add_all([CashCategoryModel(id=identifier, workspace_id="detail-a", parent_id=None,
            parent_scope_key="__root__", name=name, normalized_name=name, description=None,
            category_path=f"/{identifier}/", depth=1, sort_order=order, revision=1, created_at=NOW, updated_at=NOW)
            for identifier, name, order in (("food", "餐饮", 1), ("salary", "工资", 2))])
        session.add_all([AccountModel(id=identifier, workspace_id=workspace, name=name, type=type, active=active,
            currencies=currencies, revision=1, created_at=NOW, updated_at=NOW)
            for identifier, workspace, name, type, active, currencies in (
                (101, "detail-a", "日常账户", "cash", True, ["CNY", "USD"]),
                (102, "detail-a", "信用账户", "loan", True, ["CNY"]),
                (103, "detail-a", "投资账户", "security", True, ["CNY"]),
                (104, "detail-a", "停用账户", "cash", False, ["CNY"]),
                (105, "detail-b", "隔离账户", "cash", True, ["CNY"]))])
        session.flush()
        at = lambda day: datetime(2026, 7, day, 9, tzinfo=timezone.utc)

        _fact(session, 1001, "detail-a", amount="12.50", account_id=101, category_id="food", counterparty="咖啡店",
            note="原备注", occurred_at=at(3), row_revision=3, source_type="wechat", record_id="wechat_1",
            source_payload={"备注": "咖啡", "交易单号": "W1"}, manual_overrides={"note": True})
        _fact(session, 1002, "detail-a", amount="-12.50", account_id=101, category_id="food", counterparty="咖啡店",
            note="退款", occurred_at=at(3), record_type="refund")
        _fact(session, 1003, "detail-a", amount="100", account_id=101, category_id="salary", counterparty="公司",
            note="奖金", occurred_at=at(2), record_origin="manual", record_type="income")
        _fact(session, 1004, "detail-a", amount="50", account_id=101, counterparty="转账", note="",
            occurred_at=at(4), record_origin="manual_transfer", manual_transfer_id="mt-1",
            record_type="transfer_out", record_subtype="ordinary_transfer")
        _fact(session, 1005, "detail-a", amount="50", account_id=102, counterparty="转账", note="",
            occurred_at=at(4), record_origin="manual_transfer", manual_transfer_id="mt-1",
            record_type="transfer_in", record_subtype="ordinary_transfer")
        _fact(session, 1006, "detail-a", state="draft", amount=None, account_id=None, category_id=None,
            counterparty="", note="", occurred_at=None, record_type=None, record_subtype=None,
            source_type="wechat", record_id="wechat_6", source_payload={"备注": "账单新备注", "交易单号": "P6"})
        _fact(session, 1007, "detail-a", state="draft", amount="20", account_id=101, category_id="food",
            counterparty="咖啡店", note="候选", occurred_at=at(5), source_type="wechat", record_id="wechat_7")
        _fact(session, 1008, "detail-a", amount="5", account_id=101, category_id="food", counterparty="超市",
            note="待确认合并", occurred_at=at(6))
        _fact(session, 1009, "detail-a", amount="5", account_id=101, category_id="food", counterparty="超市",
            note="待确认合并", occurred_at=at(6))
        _fact(session, 1010, "detail-a", amount="1", account_id=101, category_id="food", counterparty="已删除",
            note="", occurred_at=at(7), deleted_at=at(8), deleted_by="tester", delete_reason="fixture")
        _fact(session, 1011, "detail-b", amount="1", account_id=105, counterparty="隔离", note="", occurred_at=at(7))
        _fact(session, 1012, "detail-a", amount="2", account_id=101, category_id="food", counterparty="未投影",
            note="", occurred_at=at(8))
        _fact(session, 1013, "detail-a", state="draft", amount=None, account_id=None, counterparty="", note="",
            occurred_at=None, record_type=None, record_subtype=None, source_type="alipay", record_id="alipay_13")
        _fact(session, 1014, "detail-a", state="draft", amount="30", account_id=101, category_id="food",
            counterparty="咖啡店", note="旧备注", occurred_at=at(9), source_type="alipay", record_id="alipay_14")
        _fact(session, 1015, "detail-a", state="draft", amount=None, account_id=None, counterparty="", note="",
            occurred_at=None, record_type=None, record_subtype=None, source_type="wechat", record_id="wechat_15")
        _fact(session, 1016, "detail-a", amount="9", account_id=101, category_id="food", counterparty="冲突",
            note="", occurred_at=at(10), source_type="wechat", record_id="wechat_16")
        _fact(session, 1017, "detail-a", state="draft", amount=None, account_id=None, counterparty="", note="",
            occurred_at=None, record_type=None, record_subtype=None, source_type="wechat", record_id="wechat_17")
        _fact(session, 1018, "detail-a", amount="7", account_id=101, category_id="food", counterparty="覆盖候选",
            note="", occurred_at=at(11), source_type="wechat", record_id="wechat_18")
        session.flush()
        _parent_workspace.update({1001: "detail-a", 1002: "detail-a", 1003: "detail-a", 1004: "detail-a",
            1005: "detail-a", 1006: "detail-a", 1007: "detail-a", 1008: "detail-a", 1009: "detail-a",
            1010: "detail-a", 1011: "detail-b", 1012: "detail-a", 1013: "detail-a", 1014: "detail-a",
            1015: "detail-a", 1016: "detail-a", 1017: "detail-a", 1018: "detail-a"})

        _component(session, 2001, 1001, 101, "12.50")
        _component(session, 2002, 1002, 101, "-12.50")
        _component(session, 2003, 1003, 101, "100")
        _component(session, 2004, 1004, 101, "50")
        _component(session, 2005, 1005, 102, "50")
        _component(session, 2006, 1007, 101, "20")
        _component(session, 2007, 1008, 101, "5")
        _component(session, 2008, 1009, 101, "5")
        _component(session, 2009, 1010, 101, "1")
        _component(session, 2010, 1011, 105, "1")
        _component(session, 2011, 1012, 101, "2")
        _component(session, 2012, 1014, 101, "30")
        _component(session, 2013, 1015, 101, "9")
        _component(session, 2014, 1016, 101, "9")
        _component(session, 2017, 1018, 101, "7")

        _entry(session, "entry-1001", 1001, "detail-a", members=[(1001, {}, ["note"])],
            revision=4, category_id="food", note="公共备注", counterparty="咖啡店", counterparty_account="尾号1234",
            counterparty_account_attrs=["尾号"], manual_fields=["note", "category_id"])
        _entry(session, "entry-1002", 1002, "detail-a", members=[(1002, {}, [])])
        _entry(session, "entry-1003", 1003, "detail-a", members=[(1003, {}, [])], revision=2, note="公共奖金")
        _entry(session, "entry-1004", 1004, "detail-a", members=[(1004, {}, []), (1005, {}, [])],
            revision=3, note="公共转账")
        _entry(session, "entry-1008", 1008, "detail-a", members=[(1008, {}, [])])

        _relation(session, 3001, "detail-a", 1001, 1002, "payment_mirror", "confirmed",
            components=(2001, 2002), applied="12.50")
        _relation(session, 3002, "detail-a", 1008, 1009, "payment_mirror", "draft",
            components=(2007, 2008), group="group-1")
        session.flush()
        session.add(CashRelationCandidateModel(id=3101, workspace_id="detail-a", relation_id=3002,
            candidate_transaction_id=1003, candidate_component_id=2003, expected_row_revision=1, evidence={},
            validity="valid"))

        session.add(CashTransactionIssueModel(id=6001, workspace_id="detail-a", transaction_id=1006,
            issue_key="source-update", code="source_update_pending", field_path=None,
            pending_source_payload={"备注": "账单新备注", "交易单号": "P6"},
            pending_fields={"note": "账单新备注", "category_id": "food"}, origin={}, revision=2,
            created_at=NOW, updated_at=NOW))
        session.add(CashTransactionIssueModel(id=6002, workspace_id="detail-a", transaction_id=1006,
            issue_key="identity", code="source_identity_ambiguous", field_path=None,
            pending_source_payload=None, pending_fields=None, origin={}, revision=1, created_at=NOW, updated_at=NOW))
        session.add(CashTransactionIssueModel(id=6003, workspace_id="detail-a", transaction_id=1013,
            issue_key="source-update", code="source_update_pending", field_path=None,
            pending_source_payload={"备注": "坏分类"}, pending_fields={"category_id": "missing-category"},
            origin={}, revision=1, created_at=NOW, updated_at=NOW))
        session.add(CashTransactionIssueModel(id=6005, workspace_id="detail-a", transaction_id=1007,
            issue_key="empty-pending", code="source_update_pending", field_path=None,
            pending_source_payload=None, pending_fields=[], origin={}, revision=1, created_at=NOW, updated_at=NOW))
        session.add(CashTransactionIssueModel(id=6004, workspace_id="detail-a", transaction_id=1014,
            issue_key="source-update", code="source_update_pending", field_path=None,
            pending_source_payload={"备注": "好备注"}, pending_fields={"note": "好备注"}, origin={}, revision=1,
            created_at=NOW, updated_at=NOW))
        session.add(CashTransactionIssueModel(id=6015, workspace_id="detail-a", transaction_id=1015,
            issue_key="identity", code="source_identity_ambiguous", field_path=None,
            pending_source_payload=None, pending_fields=None, origin={}, revision=1, created_at=NOW, updated_at=NOW))
        session.add(CashTransactionIssueModel(id=6017, workspace_id="detail-a", transaction_id=1017,
            issue_key="identity", code="source_identity_ambiguous", field_path=None,
            pending_source_payload=None, pending_fields=None, origin={}, revision=1, created_at=NOW, updated_at=NOW))
        session.flush()
        session.add(CashIssueCandidateModel(id=6101, workspace_id="detail-a", issue_id=6002,
            candidate_transaction_id=1007, expected_row_revision=2))
        session.add(CashIssueCandidateModel(id=6115, workspace_id="detail-a", issue_id=6015,
            candidate_transaction_id=1016, expected_row_revision=1))
        session.add(CashIssueCandidateModel(id=6117, workspace_id="detail-a", issue_id=6017,
            candidate_transaction_id=1018, expected_row_revision=1))

        coverage = {"start": None, "end": None, "complete": False, "evidence": "", "filters": {},
            "start_inclusive": True, "end_inclusive": False, "time_precision": "second",
            "strategy_version": "source-coverage-v1", "document_key": None, "page_total": None,
            "page_fingerprints": {}}
        binding = lambda identifier, transaction, key, evidence=None, stored=None: CashSourceBindingModel(
            id=identifier, workspace_id="detail-a", transaction_id=transaction, source_type="wechat",
            source_scope_kind="account", source_scope_key="scope-1", identity_kind="provider_id", identity_key=key,
            identity_strength="provider", stable_evidence_key=evidence, strategy_version="wechat-v1",
            coverage={} if stored is None else stored, revision=1, created_at=NOW, updated_at=NOW)
        session.add_all([binding(7001, 1006, "provider-6"), binding(7002, 1007, "provider-7"),
            binding(7015, 1015, "provider-15", "evidence-9"), binding(7016, 1016, "provider-16", "evidence-9"),
            binding(7017, 1017, "provider-17", None, coverage), binding(7018, 1018, "provider-18", None, coverage)])

        session.add(CashProjectionDatasetModel(id="ds1", workspace_id="detail-a", state="active", source_revision=4,
            source_digest="0" * 64, rules_version="projection-v1", created_at=NOW, published_at=NOW))
        session.flush()
        session.add(CashProjectionStateModel(workspace_id="detail-a", active_dataset_id="ds1", projection_version=7,
            source_revision=4, rules_version="projection-v1", availability="ready", last_build_status="succeeded",
            projection_count=2, member_count=2, updated_at=NOW))
        projection = lambda identifier, root, attention: CashProjectionModel(id=identifier, workspace_id="detail-a",
            dataset_id="ds1", projection_id=f"cash:{root}", entry_id=f"entry-{root}", root_cash_transaction_id=root,
            state="posted", statistics_eligible=True, attention_count=attention, sort_at=at(3),
            economic_type="expense", net_amount="12.50", currency="CNY", occurred_at=at(3), account_id=101,
            counterparty="咖啡店", category_id="food", note="", source_type="wechat", record_id=f"wechat_{root}",
            visible=True, member_count=1, accepted_relation_count=0, built_projection_version=7, created_at=NOW,
            updated_at=NOW)
        session.add_all([projection(5001, 1001, 0), projection(5008, 1008, 1)])
        session.flush()
        session.add_all([CashProjectionMemberModel(id=5101, workspace_id="detail-a", dataset_id="ds1",
            projection_row_id=5001, cash_transaction_id=1001, roles_json=["root"], ordinal=0),
            CashProjectionMemberModel(id=5108, workspace_id="detail-a", dataset_id="ds1",
            projection_row_id=5008, cash_transaction_id=1008, roles_json=["root"], ordinal=0)])


def export(database_url: str, output: Path) -> None:
    root = Path(__file__).resolve().parents[1]
    if database_url.startswith("sqlite+pysqlite:///"):
        database = Path(database_url.removeprefix("sqlite+pysqlite:///"))
        if not database.is_absolute() or database.exists():
            raise ValueError("use a new absolute SQLite test path; existing files are never overwritten")
        database.parent.mkdir(parents=True, exist_ok=True)
        jdbc = "jdbc:sqlite:" + str(database)
    else:
        parsed = urlparse(database_url)
        if parsed.scheme != "postgresql+psycopg" or not parsed.path.endswith("_test"):
            raise ValueError("PostgreSQL must explicitly target a dedicated _test database")
        jdbc = f"jdbc:postgresql://{parsed.hostname}:{parsed.port or 5432}{parsed.path}"
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    engine = create_relational_engine(database_url)
    if inspect(engine).get_table_names():
        raise ValueError("test database must be empty")
    with engine.begin() as connection:
        config.attributes["connection"] = connection
        command.upgrade(config, "head")
    sessions = create_session_factory(engine)
    seed(sessions)
    workspace = ContextVar("golden_workspace", default=None)
    user = ContextVar("golden_user", default=None)
    service = WorkspaceServices(sessions, workspace, user_var=user)
    app = create_app(service, category_service=service, access_service=AccessService(sessions),
                     workspace_context=workspace, user_context=user, operations_service=service)
    cases = []

    def capture(identifier, token=None, headers=None):
        values = {} if headers is None else dict(headers)
        if token is not None:
            values["Authorization"] = "Bearer synthetic-" + token
        response = client.get("/api/v1/cash-records/" + identifier, headers=values)
        case = dict(method="GET", path="/api/v1/cash-records/" + identifier, headers=values,
            status=response.status_code)
        if response.headers.get("content-type", "").startswith("application/json"):
            case["body"] = response.json()
        else:
            case["text"] = response.text
        cases.append(case)

    with TestClient(app) as client:
        for identifier in ("1001", "1002", "1003", "1004", "1005", "1006", "1007", "1008", "1012", "1013",
                "1014", "1015", "1017", "01001"):
            capture(identifier, "admin")
        for identifier in ("1001", "1003", "1004", "1006", "1015"):
            capture(identifier, "editor")
        for identifier in ("1001", "1004", "1006", "1008"):
            capture(identifier, "viewer")
        capture("1001")
        capture("1001", "outsider")
        capture("1001", "expired")
        capture("1001", "other-workspace")
        for identifier in ("1010", "1011", "999999", "0", "-1", "abc", "9223372036854775808", "1.0", "+1"):
            capture(identifier, "admin")
    output.mkdir(parents=True, exist_ok=True)
    (output / "detail-cases.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n")
    (output / "database.json").write_text(json.dumps({"jdbc_url": jdbc, "schema_revision": "20261028_51"}) + "\n")
    engine.dispose()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    export(args.database_url, args.output)
