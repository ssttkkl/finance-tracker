"""Export anonymous transactional source writes, issues and certified empty pages."""
from __future__ import annotations

import argparse
from dataclasses import asdict, replace
import json
from pathlib import Path

from sqlalchemy import select

from ft.adapters.relational.cash_sources import CashSourceRepository, SourceBindingConflict
from ft.adapters.relational.cash_source_documents import CashSourceDocumentRepository
from ft.adapters.relational.models import (CashSourceBindingModel, CashTransactionModel,
    CashTransactionIssueModel, CashIssueCandidateModel, CashSourceEmptyPageModel, WorkspaceModel)
from ft.application.cash_import_operations import _wire
from ft.application.parsed_import_staging import InMemoryParsedImportStore, ParsedImportFile, _json_bytes
from ft.domain.cash_source_matching import SourceOccurrence, SourceCoverage
from ft.domain.cash_source_scope import WECHAT_DEFAULT_SCOPE
from ft.adapters.cash_statement_rows import ParsedCashDocument, ParsedCashRow

from export_cash_import_operation_contracts import NOW, WORKSPACE, connect, seed


def state(session):
    bindings = [{key: getattr(row, key) for key in ("transaction_id", "source_type", "source_scope_kind",
        "source_scope_key", "identity_kind", "identity_key", "identity_strength", "stable_evidence_key",
        "strategy_version", "coverage", "revision")} for row in session.scalars(select(CashSourceBindingModel).where(
            CashSourceBindingModel.workspace_id == WORKSPACE, CashSourceBindingModel.transaction_id >= 7601))]
    issues = []
    for row in session.scalars(select(CashTransactionIssueModel).where(
        CashTransactionIssueModel.workspace_id == WORKSPACE, CashTransactionIssueModel.transaction_id >= 7601)):
        value = {key: getattr(row, key) for key in ("transaction_id", "issue_key", "code", "field_path",
            "pending_source_payload", "pending_fields", "origin", "revision")}
        value["candidates"] = sorted([[item.candidate_transaction_id, item.expected_row_revision] for item in session.scalars(
            select(CashIssueCandidateModel).where(CashIssueCandidateModel.workspace_id == WORKSPACE, CashIssueCandidateModel.issue_id == row.id))])
        issues.append(value)
    pages = [{key: getattr(row, key) for key in ("source_type", "parser_version", "document_key", "page_number", "coverage")}
        for row in session.scalars(select(CashSourceEmptyPageModel).where(CashSourceEmptyPageModel.workspace_id == WORKSPACE))]
    return {"bindings": sorted(bindings, key=lambda row: (row["transaction_id"], row["source_scope_kind"], row["source_scope_key"], row["identity_kind"], row["identity_key"])),
        "issues": sorted(issues, key=lambda row: (row["transaction_id"], row["issue_key"])),
        "pages": sorted(pages, key=lambda row: (row["source_type"], row["parser_version"], row["document_key"], row["page_number"]))}


def export(database_url: str, output: Path):
    engine, sessions, jdbc = connect(database_url)
    try:
        seed(sessions)
        with sessions.begin() as session:
            session.add(WorkspaceModel(id="source-foreign", name="foreign", created_at=NOW)); session.flush()
            for identifier, source, status, workspace in ((7601, "alipay", "posted", WORKSPACE),
                (7602, "alipay", "draft", WORKSPACE), (7603, "alipay", "posted", WORKSPACE),
                (7604, "wechat", "posted", WORKSPACE), (7605, "alipay", "draft", "source-foreign")):
                session.add(CashTransactionModel(id=identifier, workspace_id=workspace, state=status, record_origin="import",
                    account_id=9201 if workspace == WORKSPACE else None, cash_granularity="atomic", occurred_at=NOW,
                    amount="-5.00", currency="CNY", counterparty="synthetic", counterparty_account="",
                    counterparty_account_attrs=[], note="", record_type="consumption", record_subtype="not_applicable",
                    record_id="", source_type=source, source_payload={}, relation_metadata={}, row_revision=1, created_at=NOW))
        row = SourceOccurrence("alipay", "owner", "a" * 64, "fixture-v1", "table", 1, {}, provider_id="P1")
        proof = SourceCoverage(document_key="f" * 64, page_total=3, page_fingerprints={"1": "a" * 64, "2": "b" * 64, "3": "c" * 64})
        docrow = replace(row, coverage=proof, document_position="p2:t0:r1")

        def bind(identifier=7601, value=row, **options):
            return {"action": "bind", "target": identifier, "row": _wire(asdict(value)), **options}

        def issue(**changes):
            return {"action": "issue", "target": 7601, "issue_key": "identity", "code": "source_identity_ambiguous", **changes}

        def parent(identifier=7601, **changes):
            return {"action": "parent", "target": identifier, "changes": changes}

        def file(coverage=proof, rows=(), subset=False, **changes):
            document = ParsedCashDocument("alipay", "table", "fixture-v1", tuple(rows), "owner", "synthetic", coverage,
                len(rows), len(rows))
            return replace(ParsedImportFile("f_synthetic", "client", "synthetic.csv", "a" * 64, 100, "alipay", "parsed", document,
                is_subset=subset), **changes)

        def empty(*files):
            return {"action": "empty", "files": json.loads(_json_bytes(tuple(files)))}

        occupied = ParsedCashRow(1, {}, "", None, (), None, "candidate", None, "p2:t0:r1", {})
        default_wechat = replace(row, source_type="wechat", source_account_key=WECHAT_DEFAULT_SCOPE, provider_id="000123")
        cases = [
            ("binding_idempotent", [bind(), bind()]),
            ("binding_update_alias", [bind(), bind(value=replace(row, file_digest="b" * 64))]),
            ("binding_reject_active_owner", [bind(), bind(7603)]),
            ("binding_deleted_owner_reused", [bind(), parent(deleted_at=NOW.isoformat()), bind(7603)]),
            ("binding_reject_scope", [bind(), bind(value=replace(row, source_account_key="other", file_digest="b" * 64))]),
            ("binding_reject_provider", [bind(), bind(value=replace(row, provider_id="other", file_digest="b" * 64))]),
            ("binding_reject_evidence", [bind(value=replace(row, stable_evidence="one")), bind(value=replace(row, stable_evidence="two"))]),
            ("binding_reject_posted_uncertain", [bind(uncertain=True)]),
            ("binding_draft_uncertain", [bind(7602, uncertain=True)]),
            ("binding_reject_source", [bind(7604)]),
            ("binding_reject_target", [bind(999999)]),
            ("binding_reject_foreign", [bind(7605)]),
            ("binding_reject_bool", [bind(True)]),
            ("binding_reject_zero", [bind("0")]),
            ("binding_first_new", [bind(first_binding=True)]),
            ("binding_legacy_wechat", [parent(7604, record_id="wechat_000123", source_payload={"交易单号": "000123"}), bind(7604, default_wechat)]),
            ("binding_reject_legacy_named", [parent(7604, record_id="wechat_000123", source_payload={"交易单号": "000123"}), bind(7604, replace(default_wechat, source_account_key="named"))]),
            ("binding_compact_page", [bind(value=docrow)]),
            ("binding_deleted_page_reused", [bind(value=docrow), parent(deleted_at=NOW.isoformat()), bind(7603, docrow)]),
            ("binding_reject_other_position", [bind(value=docrow), bind(value=replace(docrow, document_position="p3:t0:r1", file_digest="b" * 64))]),
            ("binding_reject_page_conflict", [bind(value=docrow), bind(7603, replace(docrow, position=2, document_position="p2:t0:r2", coverage=replace(proof, page_fingerprints={"2": "d" * 64})))]),
            ("issue_idempotent", [issue(candidates={7602: 1}), issue(candidates={7602: 1})]),
            ("issue_numeric_equal", [issue(pending_fields={"count": 1}), issue(pending_fields={"count": 1.0})]),
            ("issue_empty_origin", [issue(origin=[])]),
            ("issue_change_both_once", [issue(candidates={7602: 1}), issue(pending_fields={"note": "new"}, candidates={7603: 1})]),
            ("issue_candidate_only", [issue(candidates={7602: 1}), issue(candidates={7603: 1}), issue(candidates={})]),
            ("issue_payload", [issue(code="source_update_pending", pending_fields={"note": "new"}, pending_source_payload={"raw": "value"}, origin={"file_digest": "a" * 64})]),
            ("issue_reject_stale", [issue(candidates={7602: 1}), parent(7602, row_revision=2), issue(candidates={7602: 1})]),
            ("issue_reject_self", [issue(candidates={"07601": 1})]),
            ("issue_reject_bool_revision", [issue(candidates={7602: True})]),
            ("issue_reject_foreign", [issue(candidates={7605: 1})]),
            ("issue_reject_reason", [issue(code="SELECT secret")]),
            ("issue_reject_origin", [issue(origin={"path": "/secret"})]),
            ("issue_reject_fields", [issue(pending_fields=[])]),
            ("issue_clear", [issue(candidates={7602: 1}), {"action": "clear", "target": 7601, "issue_key": "identity"}]),
            ("empty_idempotent", [empty(file()), empty(file())]),
            ("empty_occupied", [empty(file(rows=(occupied,)))]),
            ("empty_skipped_occupied", [empty(file(rows=(replace(occupied, disposition="skipped"),)))]),
            ("empty_subset", [empty(file(subset=True))]),
            ("empty_unlocated", [empty(file(rows=(replace(occupied, document_position=None),)))]),
            ("empty_count_mismatch", [empty(file(document=replace(file().document, observed_count=1)))]),
            ("empty_reject_page", [empty(file()), empty(file(replace(proof, page_fingerprints={"2": "d" * 64})))]),
            ("empty_reject_declaration", [empty(file()), empty(file(replace(proof, end="2026-10-02T00:00:00Z")))]),
            ("empty_binding_conflict", [empty(file()), bind(value=replace(docrow, coverage=replace(proof, page_fingerprints={"2": "d" * 64})))]),
        ]
        exported = []
        for name, actions in cases:
            with sessions() as session:
                transaction = session.begin()
                repo = CashSourceRepository(session, WORKSPACE)
                result = {"name": name, "actions": actions}
                try:
                    for action in actions:
                        kind = action["action"]
                        if kind == "bind":
                            raw = action["row"]
                            occurrence = SourceOccurrence(**{**raw, "coverage": SourceCoverage(**raw["coverage"])})
                            repo.save_occurrence(action["target"], occurrence, uncertain=action.get("uncertain", False), first_binding=action.get("first_binding", False))
                        elif kind == "issue":
                            repo.save_issue(action["target"], **{key: value for key, value in action.items() if key not in {"action", "target"}})
                        elif kind == "clear":
                            repo.clear_issue(action["target"], action["issue_key"])
                        elif kind == "parent":
                            target = session.get(CashTransactionModel, action["target"])
                            for key, value in action["changes"].items():
                                if key == "deleted_at":
                                    from datetime import datetime
                                    value = datetime.fromisoformat(value)
                                setattr(target, key, value)
                            session.flush()
                        elif kind == "empty":
                            from ft.application.parsed_import_staging import _restore_file
                            CashSourceDocumentRepository(session, WORKSPACE).save_empty_pages(tuple(_restore_file(item) for item in action["files"]))
                except SourceBindingConflict as error:
                    result["error"] = error.code
                result["expected"] = state(session)
                exported.append(result)
                transaction.rollback()
        output.mkdir(parents=True, exist_ok=True)
        (output / "database.json").write_text(json.dumps({"jdbc_url": jdbc}) + "\n")
        (output / "cases.json").write_text(json.dumps(_wire(exported), ensure_ascii=False, indent=2) + "\n")
        store = InMemoryParsedImportStore(clock=lambda: NOW)
        current = store.create(workspace_id=WORKSPACE, user_id="owner", files=(file(),))
        (output / "input-session.json").write_text(json.dumps({"session_id": current.session_id, "content": json.loads(store._encode(current))}) + "\n")
    finally:
        engine.dispose()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    export(args.database_url, args.output)
