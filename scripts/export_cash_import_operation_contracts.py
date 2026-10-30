"""Export cash import operation planning results from a Python-seeded isolated database."""
from __future__ import annotations

import argparse
from dataclasses import asdict
from datetime import datetime, timezone
import json
from pathlib import Path
from urllib.parse import urlparse

from alembic import command
from alembic.config import Config
from sqlalchemy import inspect

from ft.adapters.cash_statement_rows import ParsedCashDocument, ParsedCashRow
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational.models import (
    AccountModel, CashCategoryModel, CashEntryMemberModel, CashEntryModel, CashSourceBindingModel,
    CashTransactionComponentModel, CashTransactionIssueModel, CashTransactionModel, WorkspaceModel,
)
from ft.application.cash_import_funding import scan_cash_funding
from ft.application.cash_import_operations import CashImportOperationPlanner
from ft.application.cash_import_operations import _wire
from ft.application.parsed_import_staging import ParsedImportFile
from ft.domain.cash_source_matching import SourceCoverage, _digest
from ft.domain.cash_source_scope import WECHAT_DEFAULT_SCOPE, source_account_key

NOW = datetime(2026, 10, 1, 9, 0, tzinfo=timezone.utc)
WORKSPACE = "operation-workspace"
OWNER = "owner"


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
        session.add(WorkspaceModel(id=WORKSPACE, name="操作规划工作区", created_at=NOW)); session.flush()
        session.add_all([
            AccountModel(id=9201, workspace_id=WORKSPACE, name="合成现金", type="cash", active=True,
                currencies=["CNY"], metadata_json={}, revision=1, created_at=NOW, updated_at=NOW),
            AccountModel(id=9202, workspace_id=WORKSPACE, name="合成美元", type="cash", active=True,
                currencies=["USD"], metadata_json={}, revision=2, created_at=NOW, updated_at=NOW),
            CashCategoryModel(id="op-food", workspace_id=WORKSPACE, parent_id=None, parent_scope_key="__root__",
                name="餐饮", normalized_name="餐饮", description=None, category_path="/op-food/", depth=1,
                sort_order=1, revision=1, created_at=NOW, updated_at=NOW),
        ]); session.flush()
        for transaction_id, source, record, state, amount, note, payload in (
            (7501, "alipay", "ALI-1", "posted", "-12.30", "", {"订单号": "ALI-1", "付款方式": "支付宝余额"}),
            (7502, "wechat", "wechat_W1", "draft", "-9.00", "旧备注", {"交易单号": "W1", "付款方式": "零钱"}),
        ):
            session.add(CashTransactionModel(id=transaction_id, workspace_id=WORKSPACE, account_id=9201, state=state,
                record_origin="import", cash_granularity="atomic", source_type=source, record_id=record,
                source_payload=payload, occurred_at=datetime(2026, 10, 1 if transaction_id == 7501 else 3, 10, 0,
                    tzinfo=timezone.utc), amount=amount, currency="CNY", counterparty="商户",
                counterparty_account="", counterparty_account_attrs=[], note=note, record_type="consumption",
                record_subtype="not_applicable", row_revision=1, created_at=NOW))
        session.flush()
        session.add(CashTransactionComponentModel(id=9501, workspace_id=WORKSPACE, cash_transaction_id=7501,
            account_id=9201, amount="-12.30", currency="CNY", ordinal=0, label="", source_key="",
            metadata_json={}, created_at=NOW))
        for transaction_id, scope, provider in ((7501, OWNER, "ALI-1"), (7502, WECHAT_DEFAULT_SCOPE, "W1")):
            session.add(CashSourceBindingModel(workspace_id=WORKSPACE, transaction_id=transaction_id,
                source_type="alipay" if transaction_id == 7501 else "wechat", source_scope_kind="account",
                source_scope_key=scope, identity_kind="provider_id", identity_key=_digest(provider),
                identity_strength="provider", stable_evidence_key=None, strategy_version="source-coverage-v1",
                coverage=asdict(SourceCoverage()), revision=1, created_at=NOW, updated_at=NOW))
        session.add(CashTransactionIssueModel(id=5501, workspace_id=WORKSPACE, transaction_id=7501,
            issue_key="relation:sg1", code="relation_pending", field_path=None, pending_source_payload=None,
            pending_fields=None, origin={}, revision=1, created_at=NOW, updated_at=NOW))
        session.add(CashEntryModel(id="entry-7501", workspace_id=WORKSPACE, root_transaction_id=7501,
            revision=1, category_id=None, note="", counterparty="商户", counterparty_account="",
            counterparty_account_attrs=[], manual_fields=[], split_inherit=None))
        session.add(CashEntryModel(id="entry-7502", workspace_id=WORKSPACE, root_transaction_id=7502,
            revision=1, category_id=None, note="旧备注", counterparty="商户", counterparty_account="",
            counterparty_account_attrs=[], manual_fields=[], split_inherit=None)); session.flush()
        session.add(CashEntryMemberModel(workspace_id=WORKSPACE, transaction_id=7501, entry_id="entry-7501",
            standalone_values={}, standalone_manual_fields=[]))
        session.add(CashEntryMemberModel(workspace_id=WORKSPACE, transaction_id=7502, entry_id="entry-7502",
            standalone_values={}, standalone_manual_fields=[]))
    return sessions


def coverage():
    return SourceCoverage(strategy_version="source-coverage-v1")


def _row(position, provider, occurred, amount, method, error=None):
    fields = {"amount": amount, "currency": "CNY", "occurred_at": occurred, "record_type": "consumption",
        "record_subtype": "not_applicable", "counterparty": "商户", "counterparty_account": "",
        "counterparty_account_attrs": [], "note": "", "source_payload": {"订单号": provider, "付款方式": method}}
    return ParsedCashRow(position, fields, method, provider, (), error, "candidate", None, None, {})


def files():
    alipay = ParsedCashDocument("alipay", "fixture:0", "fixture-v1", (
        _row(1, "ALI-1", "2026-10-01T10:00:00+00:00", "-12.30", "支付宝余额"),
        _row(2, "NEW-1", "2026-10-02T10:00:00+00:00", "-5.00", "支付宝余额"),
        _row(3, "BAD-1", "2026-10-05T10:00:00+00:00", "bad", "支付宝余额", error="amount_unreadable"),
    ), OWNER, "合成支付宝", coverage(), 3, 3)
    wechat = ParsedCashDocument("wechat", "fixture:0", "fixture-v1", (
        _row(1, "W1", "2026-10-03T10:00:00+00:00", "-9.00", "零钱"),
    ), WECHAT_DEFAULT_SCOPE, "合成微信", coverage(), 1, 1)
    return [
        ParsedImportFile("f_a" + "0" * 31, "client-a", "支付宝账单.csv", "a" * 64, 100, "alipay", "parsed", alipay),
        ParsedImportFile("f_w" + "0" * 31, "client-w", "微信账单.csv", "w" * 64, 100, "wechat", "parsed", wechat),
    ]


def export(database_url: str, output: Path) -> None:
    engine, sessions, jdbc = connect(database_url)
    try:
        sessions = seed(sessions)
        prepared_files = files()
        scan = scan_cash_funding(prepared_files)
        selections = [{"source_account_ref": group.source_account_ref, "account_id": "9201",
            "expected_account_revision": 1} for group in scan.groups]
        rows = {
            "f_a" + "0" * 31 + ":r1": {"action": "keep_existing"},
            "f_a" + "0" * 31 + ":r2": {"action": "apply", "patch": {"note": "人工备注", "category_id": "op-food"}},
            "f_w" + "0" * 31 + ":r1": {"action": "apply", "patch": {"note": "新备注"}},
        }
        choices = [{"row_ref": ref, **value} for ref, value in rows.items()]
        deferrences = [{"row_ref": "f_a" + "0" * 31 + ":r1", "action": "defer", "patch": {"note": "延后"}}]
        with sessions() as session:
            prepared = CashImportOperationPlanner(session, WORKSPACE).prepare(prepared_files,
                account_selections=selections, row_choices=choices)
            deferred = CashImportOperationPlanner(session, WORKSPACE).prepare(prepared_files,
                account_selections=selections, row_choices=deferrences)
        expected = {
            "files": [{"file_ref": item.file_ref, "client_file_ref": item.client_file_ref, "filename": item.filename,
                       "digest": item.digest, "size": item.size, "source_type": item.source_type,
                       "status": item.status, "source_scope_label": item.source_scope_label} for item in prepared_files],
            "groups": [{"source_account_ref": group.source_account_ref, "source_type": group.source_type} for group in scan.groups],
            "choices": choices,
            "rows": _wire(list(prepared.rows)),
            "operations": _wire(list(prepared.operations)),
            "candidate_sets": {key: list(value) for key, value in prepared.candidate_sets.items()},
            "deferred": {"choices": deferrences, "rows": _wire(list(deferred.rows)),
                "operations": _wire(list(deferred.operations)),
                "candidate_sets": {key: list(value) for key, value in deferred.candidate_sets.items()}},
        }
        output.mkdir(parents=True, exist_ok=True)
        (output / "expected.json").write_text(json.dumps(expected, ensure_ascii=False, indent=2) + "\n")
        (output / "database.json").write_text(json.dumps({"jdbc_url": jdbc}) + "\n")
    finally:
        engine.dispose()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    export(args.database_url, args.output)
