"""Export bounded new-row batches, document filtering and frozen candidate checks."""
from __future__ import annotations

import argparse
from copy import deepcopy
from dataclasses import asdict, replace
import json
from pathlib import Path
from types import SimpleNamespace

from sqlalchemy import select, text

from export_cash_import_operation_contracts import NOW, WORKSPACE, connect, seed
from export_cash_record_writer_contracts import state as row_state
from export_cash_source_write_contracts import state as source_state
from ft.adapters.cash_statement_rows import ParsedCashDocument, ParsedCashRow
from ft.adapters.relational.cash_import_new_rows import CashImportNewRowsRepository
from ft.adapters.relational.cash_sources import CashSourceRepository, SourceBindingConflict
from ft.adapters.relational.models import AccountModel, CashTransactionModel
from ft.application.cash_import_commit import CashImportCommandService
from ft.application.cash_import_operations import _wire
from ft.application.cash_import_source_plan import CashImportSourcePlanner
from ft.application.cash_source_candidate_sets import frozen_source_candidate_sets
from ft.application.parsed_import_staging import InMemoryParsedImportStore, ParsedImportFile
from ft.domain.cash_source_matching import SourceOccurrence, SourceCoverage, _digest


class Commands(CashImportCommandService):
    def __init__(self):
        self._workspace_id = WORKSPACE


def batch(count):
    operations, sources = [], {}
    for index in range(count):
        parts = [{"ordinal": 0, "account_id": "9201", "amount": "-1.25", "currency": "CNY"}]
        if index % 2:
            parts = [{"ordinal": ordinal, "account_id": account, "amount": amount, "currency": "CNY"}
                for ordinal, account, amount in ((0, "9201", "-1.00"), (1, "9204", "-0.25"))]
        values = {"state": "posted", "source_type": "alipay", "record_id": f"BATCH-{index}", "amount": "-1.25", "currency": "CNY",
            "occurred_at": NOW.isoformat(), "account_id": "9201" if len(parts) == 1 else None, "cash_granularity": "atomic" if len(parts) == 1 else "aggregate",
            "counterparty": "synthetic batch", "note": f"row-{index}", "category_id": None, "counterparty_account": "", "counterparty_account_attrs": [],
            "record_type": "consumption", "record_subtype": "not_applicable", "source_payload": {"fixture": index}, "components": parts}
        ref = f"row-{index}"
        sources[ref] = SourceOccurrence("alipay", "batch-owner", "b" * 64, "batch-v1", "table", index + 1, values, provider_id=f"BATCH-{index}")
        operation = {"operation_ref": f"op-{index}", "planned_action": "create_posted", "transaction_id": None, "current": None,
            "values": values, "manual_fields": ["note"] if index == 1 else [], "source_explanation": {"note": f"evidence-{index}"},
            "pending_fields": {}, "issues": [], "source_updates": [{"row_ref": ref, "bind": True, "uncertain": False}],
            "preview_components": [{"ordinal": part["ordinal"], "component_id": str(2**64 + index * 3 + part["ordinal"])} for part in parts]}
        if index == 2: operation["entry_edit"] = {"patch": {"note": "public batch note"}, "revision": None}
        operations.append(operation)
    return operations, sources


def document(fingerprint="a", parser="doc-v1", rows=1, skipped=False):
    coverage = SourceCoverage(document_key="f" * 64, page_total=2, page_fingerprints={"1": fingerprint * 64, "2": "b" * 64})
    values = {"amount": "-5.00", "currency": "CNY", "record_type": "consumption", "record_subtype": "not_applicable",
        "occurred_at": NOW.isoformat(), "account_id": "9201", "counterparty": "synthetic", "source_payload": {}}
    parsed = tuple(ParsedCashRow(index + 1, values, "", f"DOC-{index}", document_position=f"p1:t0:r{index + 1}") for index in range(rows))
    doc = ParsedCashDocument("alipay", "table", parser, parsed, "document-owner", "synthetic", coverage, rows, rows)
    return ParsedImportFile(file_ref="f_" + "d" * 32, digest="d" * 64, status="parsed", document=doc, client_file_ref="doc", filename="synthetic.pdf", size=100, source_type="alipay")


def export(database_url, output):
    engine, sessions, jdbc = connect(database_url)
    seed(sessions)
    try:
        with sessions.begin() as session:
            session.add(AccountModel(id=9204, workspace_id=WORKSPACE, name="synthetic second cash", type="cash", active=True, currencies=["CNY"], metadata_json={}, revision=1, created_at=NOW, updated_at=NOW))
            session.add(CashTransactionModel(id=7601, workspace_id=WORKSPACE, state="posted", record_origin="import", account_id=9201, amount="-5.00", currency="CNY",
                occurred_at=NOW, source_type="alipay", record_id="", source_payload={}, cash_granularity="atomic", counterparty="synthetic", counterparty_account="",
                counterparty_account_attrs=[], note="", record_type="consumption", record_subtype="not_applicable", relation_metadata={}, row_revision=1, created_at=NOW))
        cases = []
        for name in ("below_threshold", "threshold", "mixed_parts", "over_one_batch", "uncertain", "issues", "pending", "existing_binding", "duplicate_provider", "duplicate_evidence", "document_rejected", "empty_direct", "too_many_direct"):
            with sessions() as session:
                transaction = session.begin()
                if engine.dialect.name == "postgresql":
                    for table in ("cash_transactions", "cash_transaction_components"):
                        session.execute(text(f"SELECT setval('{table}_id_seq', (SELECT max(id) FROM {table}), true)"))
                count = 19 if name == "below_threshold" else 101 if name in {"over_one_batch", "too_many_direct"} else 0 if name == "empty_direct" else 20 if name == "threshold" else 21
                operations, sources = batch(count)
                direct = name in {"document_rejected", "empty_direct", "too_many_direct"}
                if name == "uncertain":
                    for op in operations[:2]: op["source_updates"][0]["uncertain"] = True
                if name == "issues":
                    for op in operations[:2]: op["issues"] = [{"code": "source_update_pending"}]
                if name == "pending":
                    for op in operations[:2]: op["pending_fields"] = {"note": "pending"}
                if name == "existing_binding": CashSourceRepository(session, WORKSPACE).save_occurrence(7601, sources["row-0"])
                if name == "duplicate_provider": sources["row-1"] = replace(sources["row-1"], provider_id="BATCH-0")
                if name == "duplicate_evidence":
                    sources["row-0"] = replace(sources["row-0"], stable_evidence="shared-proof")
                    sources["row-1"] = replace(sources["row-1"], stable_evidence="shared-proof")
                if name == "document_rejected": sources["row-0"] = replace(sources["row-0"], coverage=document().document.coverage)
                case = {"name": name, "operations": deepcopy(operations), "sources": {key: _wire(asdict(value)) for key, value in sources.items()}, "direct": direct,
                    "seed_binding": name == "existing_binding"}
                result, error = {}, None
                try:
                    if direct:
                        saved = CashImportNewRowsRepository(session, WORKSPACE).insert([(op, [sources[item["row_ref"]] for item in op["source_updates"] if item["bind"]]) for op in operations])
                        result = dict(zip((op["operation_ref"] for op in operations), saved))
                    else: result = Commands()._new_batches(session, operations, sources)
                    session.flush()
                except (SourceBindingConflict, ValueError) as failure: error = getattr(failure, "code", str(failure))
                case["expected"] = _wire({"result": {key: {"transaction_id": value[0], "mapping": value[1], "before": value[2], "after": value[3], "touched": sorted(value[4])} for key, value in result.items()},
                    "error": error, "rows": row_state(session)})
                cases.append(case)
                transaction.rollback()
        document_cases = []
        for name in ("normal", "all_skipped_clean", "conflict_all_skipped", "conflict_partial", "needed_record", "needed_candidate", "needed_candidate_set", "other_parser"):
            with sessions() as session:
                transaction = session.begin()
                incoming = document(rows=2 if name == "conflict_partial" else 1, parser="doc-v2" if name == "other_parser" else "doc-v1")
                if name not in {"normal", "all_skipped_clean"}:
                    old = SourceOccurrence("alipay", "document-owner", "x" * 64, "doc-v1", "table", 1, {},
                        coverage=document("c").document.coverage, document_position="p1:t0:r1")
                    CashSourceRepository(session, WORKSPACE).save_occurrence(7601, old)
                files = (incoming,)
                source = CashImportSourcePlanner(session, WORKSPACE).prepare(files)
                refs = list(source.rows)
                plan = {"row_decisions": [{"row_ref": ref, "planned_action": "apply" if name == "conflict_partial" and index else "skip"} for index, ref in enumerate(refs)], "operations": [], "source_candidate_sets": {}}
                if name == "needed_record": plan["operations"] = [{"transaction_id": "7601"}]
                if name == "needed_candidate": plan["operations"] = [{"transaction_id": None, "candidate_ids": ["7601"]}]
                if name == "needed_candidate_set": plan["source_candidate_sets"] = {"fixture": ["7601"]}
                store = InMemoryParsedImportStore(clock=lambda: NOW)
                staged = store.create(workspace_id=WORKSPACE, user_id="owner", files=files)
                error = None
                try: Commands()._save_evidence(session, files, sources=source, plan=plan)
                except SourceBindingConflict as failure: error = failure.code
                document_cases.append({"name": name, "content": json.loads(store._encode(staged)), "session_id": staged.session_id, "plan": plan,
                    "seed_old": name not in {"normal", "all_skipped_clean"}, "expected": _wire({"error": error, "pages": source_state(session)["pages"]})})
                transaction.rollback()
        candidate_cases = []
        identifiers = ["7501", "7502"]
        key = _digest("cash-source-candidates-v1", identifiers)
        for name, values in (("valid", {key: identifiers}), ("bad_digest", {"a" * 64: identifiers}), ("duplicate", {_digest("cash-source-candidates-v1", ["7501", "7501"]): ["7501", "7501"]}),
            ("empty", {_digest("cash-source-candidates-v1", []): []}), ("noncanonical_id", {_digest("cash-source-candidates-v1", ["07501"]): ["07501"]})):
            error = None
            try: frozen_source_candidate_sets(values)
            except ValueError as failure: error = str(failure)
            candidate_cases.append({"name": name, "values": values, "error": error})
        output.mkdir(parents=True, exist_ok=True)
        for filename, value in (("batches.json", cases), ("documents.json", document_cases), ("candidates.json", candidate_cases)):
            (output / filename).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
        (output / "database.json").write_text(json.dumps({"jdbc_url": jdbc}) + "\n")
        print(f"exported {len(cases)} batches, {len(document_cases)} document filters, {len(candidate_cases)} candidate sets")
    finally: engine.dispose()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    export(args.database_url, args.output)
