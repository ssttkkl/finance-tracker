"""Export source-plan read/planning/session-view results from a Python-seeded isolated database."""
from __future__ import annotations

import argparse
from dataclasses import asdict, replace
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import json
from pathlib import Path
from urllib.parse import urlparse

from alembic import command
from alembic.config import Config
from sqlalchemy import inspect

from ft.adapters.cash_statement_rows import ParsedCashDocument, ParsedCashRow
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational.cash_source_documents import CashSourceDocumentRepository
from ft.adapters.relational.cash_sources import CashSourceRepository
from ft.adapters.relational.models import (
    AccountModel, CashSourceBindingModel, CashSourceEmptyPageModel, CashTransactionComponentModel,
    CashTransactionModel, WorkspaceModel,
)
from ft.application.cash_import_account_mappings import CashImportAccountMappings
from ft.application.cash_import_funding import scan_cash_funding
from ft.application.cash_import_operations import _wire
from ft.application.cash_import_session_view import cash_import_session_view
from ft.application.cash_import_source_plan import CashImportSourcePlanner
from ft.application.cash_import_source_rows import source_batch_rows
from ft.application.parsed_import_staging import ParsedImportFile, ParsedImportSession
from ft.domain.cash_source_matching import SourceCoverage, SourceOccurrence, _digest
from ft.domain.cash_source_scope import WECHAT_DEFAULT_SCOPE

NOW = datetime(2026, 10, 1, 9, 0, tzinfo=timezone.utc)
WORKSPACE = "source-plan-workspace"
DOCUMENT = "d" * 64
EMPTY_FINGERPRINT = "f" * 64
PAGE_FINGERPRINT = "e" * 64


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


def coverage(document: bool = False):
    return SourceCoverage(start="2026-10-01", end="2026-10-04", complete=document, evidence="fixture",
        filters={"account": "fixture"}, strategy_version="fixture-v1",
        document_key=DOCUMENT if document else None, page_total=2 if document else None,
        page_fingerprints={"1": PAGE_FINGERPRINT, "2": EMPTY_FINGERPRINT} if document else {})


def seed(sessions):
    with sessions.begin() as session:
        session.add(WorkspaceModel(id=WORKSPACE, name="来源规划工作区", created_at=NOW))
        session.flush()
        session.add_all([AccountModel(id=identifier, workspace_id=WORKSPACE, name=f"合成账户{identifier}",
            type="cash", active=True, currencies=["CNY"], metadata_json={}, revision=1, created_at=NOW, updated_at=NOW)
            for identifier in (9101, 9102)])
        session.flush()
        for transaction_id, source, record, origin, occurred, amount in (
            (7001, "wechat", "wechat_ORDER-1", "import", datetime(2026, 10, 1, 10, 0, tzinfo=timezone.utc), "12.30"),
            (7002, "alipay", "ALI-1", "import", datetime(2026, 10, 2, 10, 0, tzinfo=timezone.utc), "-3.00"),
            (7003, "wechat", "wechat_ ORDER-2 ", "legacy", datetime(2026, 10, 3, 10, 0, tzinfo=timezone.utc), "9.00"),
        ):
            session.add(CashTransactionModel(id=transaction_id, workspace_id=WORKSPACE, account_id=9101, state="posted",
                record_origin=origin, cash_granularity="atomic", source_type=source, record_id=record,
                source_payload={"交易单号": record.removeprefix("wechat_")} if source == "wechat" else {"订单号": "ALI-1"},
                occurred_at=occurred, amount=amount, currency="CNY", counterparty="商户", counterparty_account="",
                counterparty_account_attrs=[], note="", record_type="consumption", record_subtype="not_applicable",
                row_revision=1, created_at=NOW))
        session.flush()
        session.add(CashTransactionComponentModel(id=9001, workspace_id=WORKSPACE, cash_transaction_id=7001,
            account_id=9101, amount="12.30", currency="CNY", ordinal=0, label="", source_key="", metadata_json={}, created_at=NOW))
        session.flush()
        document_scope = _digest("source-document-v1", "wechat", "fixture-v1", DOCUMENT)
        row = SourceOccurrence("wechat", WECHAT_DEFAULT_SCOPE, "a" * 64, "fixture-v1", "fixture-table", 1, {},
            coverage=False, provider_id="ORDER-1")
        document_row = replace(row, coverage=coverage(True), document_position="p1:t1:r1")
        for binding in (
            (7001, "wechat", "account", WECHAT_DEFAULT_SCOPE, "provider_id", _digest("ORDER-1"), None, asdict(coverage())),
            (7001, "wechat", "file_local", row.file_scope_key, "file_occurrence", row.occurrence_key, None, asdict(coverage())),
            (7001, "wechat", "file_local", document_scope, "file_occurrence",
             document_row.document_occurrence_key, None, asdict(coverage(True))),
        ):
            session.add(CashSourceBindingModel(workspace_id=WORKSPACE, transaction_id=binding[0], source_type=binding[1],
                source_scope_kind=binding[2], source_scope_key=binding[3], identity_kind=binding[4],
                identity_key=binding[5], identity_strength="validated",
                stable_evidence_key=binding[6], strategy_version="fixture-v1", coverage=binding[7],
                revision=1, created_at=NOW, updated_at=NOW))
        session.add(CashSourceEmptyPageModel(workspace_id=WORKSPACE, source_type="wechat", parser_version="fixture-v1",
            document_key=DOCUMENT, page_number=2, coverage=asdict(replace(coverage(True), complete=False, evidence="",
                page_fingerprints={"2": EMPTY_FINGERPRINT}))))
    return sessions


def _row(position, provider, occurred, amount, note="", method="零钱", **changes):
    fields = {"amount": amount, "currency": "CNY", "occurred_at": occurred, "record_type": "consumption",
        "record_subtype": "not_applicable", "counterparty": "商户", "counterparty_account": "",
        "counterparty_account_attrs": [], "note": note,
        "source_payload": {"交易单号": provider, "付款方式": method}}
    return ParsedCashRow(position, fields, method, provider, (), None, "candidate", None, None, {}, **changes)


def files():
    document_a = ParsedCashDocument("wechat", "fixture:0", "fixture-v1",
        (_row(1, "ORDER-1", "2026-10-01T10:00:00+00:00", "12.30"),
         _row(2, "ORDER-2", "2026-10-03T10:00:00+00:00", "9.00")),
        WECHAT_DEFAULT_SCOPE, "合成微信", coverage(True), 2, 2)
    document_b = ParsedCashDocument("alipay", "fixture:0", "fixture-v1",
        (_row(1, "NEW-1", "2026-10-04T10:00:00+00:00", "-3.00", method="支付宝余额"),),
        "owner", "合成支付宝", SourceCoverage(), 1, 1)
    return [
        ParsedImportFile("f_a" + "0" * 31, "client-a", "微信账单.csv", "a" * 64, 100, "wechat", "parsed",
            document_a),
        ParsedImportFile("f_b" + "0" * 31, "client-b", "支付宝账单.csv", "b" * 64, 100, "alipay", "parsed",
            document_b),
    ]


def wire(value):
    if isinstance(value, Decimal):
        return format(value.normalize(), "f")
    if isinstance(value, dict):
        return {key: wire(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [wire(item) for item in value]
    return _wire(value)


def wire_records(records):
    return [{"transaction_id": record.transaction_id, "source_type": record.source_type,
             "source_account_key": record.source_account_key, "fields": wire(record.fields),
             "provider_id": record.provider_id, "provider_binding_key": record.provider_binding_key,
             "stable_evidence_binding_key": record.stable_evidence_binding_key,
             "binding_keys": sorted(record.binding_keys), "row_revision": record.row_revision,
             "coverages": [wire(asdict(item)) for item in record.coverages],
             "file_occurrences": {key: sorted(value) for key, value in record.file_occurrences.items()},
             "document_evidence": [wire(asdict(item)) for item in record.document_evidence]} for record in records]


def wire_evidence(evidence):
    return [{"source_type": item.source_type, "parser_version": item.parser_version,
             "coverage": _wire(asdict(item.coverage))} for item in evidence]


def export(database_url: str, output: Path) -> None:
    engine, sessions, jdbc = connect(database_url)
    try:
        sessions = seed(sessions)
        prepared_files = files()
        with sessions() as session:
            source_plan = CashImportSourcePlanner(session, WORKSPACE)
            rows = source_batch_rows(prepared_files)
            records = CashSourceRepository(session, WORKSPACE).records_for_file(tuple(rows.values()))
            documents = CashSourceDocumentRepository(session, WORKSPACE).read(prepared_files,
                candidate_evidence=tuple(item for record in records for item in record.document_evidence))
            prepared = source_plan.prepare(prepared_files)
            scan = scan_cash_funding(prepared_files)
            suggestions = CashImportAccountMappings(session, WORKSPACE).suggest(scan.groups)
        current = ParsedImportSession("s" * 43, WORKSPACE, "u", NOW, NOW + timedelta(minutes=30),
            3, tuple(prepared_files), {"account_selections": [{"source_account_ref": scan.groups[0].source_account_ref,
                "account_id": "9101", "expected_account_revision": 1, "ignored": True}],
                "row_choices": [{"row_ref": "f_a:r1"}], "entry_choices": [{"x": 1}], "unrelated": {"drop": True}},
            {"plan_id": "ip_previous"})
        view = cash_import_session_view(current, scan.groups, suggestions)
        expected = {
            "files": [{"file_ref": item.file_ref, "client_file_ref": item.client_file_ref, "filename": item.filename,
                       "digest": item.digest, "size": item.size, "source_type": item.source_type,
                       "status": item.status, "source_scope_label": item.source_scope_label,
                       "document": _wire(asdict(item.document)) if item.document is not None else None}
                      for item in prepared_files],
            "records": wire_records(records),
            "documents": wire_evidence(documents),
            "prepared": {"records": wire_records(prepared.records),
                         "rows": [wire(asdict(item)) for item in prepared.plan.rows],
                         "groups": [[group.group_ref, list(group.row_refs), list(group.candidate_ids)] for group in prepared.plan.groups],
                         "evidence": wire_evidence(prepared.evidence)},
            "view": _wire(view),
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
