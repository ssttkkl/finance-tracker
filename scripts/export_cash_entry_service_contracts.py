"""Prepare original anonymous entry-service inputs for independent Native commands."""
from __future__ import annotations

import argparse
import itertools
import json
from decimal import Decimal
from pathlib import Path
from types import SimpleNamespace

from sqlalchemy import select

from export_cash_import_original_commit_contracts import export, NOW
from export_legacy_receipt_migration_compatibility import preflight
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational.models import AccountModel, CashProjectionStateModel, CashTransactionModel, TransactionRelationModel
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.application.cash_ledger import CashLedgerCommandService
from ft.application.cash_projections import CashProjectionService
from ft.application.cashflow import TransferService
from ft.application.parsed_import_staging import InMemoryParsedImportStore
from tests.contract.test_cash_import_business_plan import _existing_mirror
from tests.contract.test_cash_import_operations import _file, _bind_existing
from tests.contract.test_cash_import_commit import _mirror_files
from tests.contract.test_cash_mutation_business_plan import _posted
from tests.contract.test_cash_current_relation_plan import _start
from tests.contract.test_cash_current_relation_delete import _candidate_references
from tests.contract.test_parsed_cash_import_context import USER
import tests.contract.test_cash_entry_editing as entries
import tests.contract.test_cash_association_split as split
import tests.contract.test_cash_current_relation_delete as deletion


METHODS = {
    "fixed_origin": (entries, "test_imported_type_is_fixed_and_origin_cannot_be_forged", "posted"),
    "workspace_delete": (entries, "test_workspace_deletion_clears_classified_entries_and_preserves_other_workspace", "posted"),
    "public_rebuild": (entries, "test_public_edits_do_not_rewrite_sources_and_survive_full_rebuild", "mirror"),
    "manual_financial": (entries, "test_manual_financial_edit_uses_same_command_and_preserves_origin", "manual"),
    "unlink": (entries, "test_unlink_requires_explicit_inheritance_and_restores_current_member_values", "mirror"),
    "entry_revision": (entries, "test_entry_revision_rejects_old_public_edit_without_changing_source_revision", "posted"),
    "manual_relation": (entries, "test_manual_origin_blocks_cash_relations_even_if_source_is_forged", "manual_relation"),
    "common_conflict": (entries, "test_conflicting_human_values_defer_only_the_relation_and_can_be_resolved", "draft_mirror"),
    "delete_single": (entries, "test_deleting_standalone_removes_its_current_entry_membership", "posted"),
    "delete_root": (entries, "test_deleting_display_root_reuses_entry_with_surviving_root", "mirror"),
    "import_public": (entries, "test_import_public_patch_preserves_source_and_protects_entry", "empty"),
    "reimport_source": (entries, "test_reimport_unprotected_public_values_match_preview_commit_and_rebuild", "empty"),
    "reimport_common": (entries, "test_reimport_combines_one_entry_public_decision", "empty"),
    "entry_http": (entries, "test_edit_entry_http_uses_public_revision_and_keeps_source_fields", "posted"),
    "manual_discovery": (entries, "test_reliable_key_lookup_and_discovery_exclude_manual_origins", "manual_discovery"),
    "investment_transfer": (entries, "test_cash_investment_manual_transfer_has_entry_but_no_single_side_financial_edit", "investment_transfer"),
    "split_hint": (split, "test_split_only_requires_actual_changed_manual_values", "current_mirror"),
    "candidate_deleted": (deletion, "test_deleting_a_prechecked_candidate_invalidates_the_old_current_relation_plan", "candidate"),
}


def scenarios():
    result = {}
    for label, (module, method, setup) in METHODS.items():
        groups = []
        for mark in getattr(getattr(module, method), "pytestmark", ()):
            if mark.name == "parametrize":
                keys = [key.strip() for key in mark.args[0].split(",")]
                groups.append([dict(zip(keys, values if len(keys) > 1 else (values,))) for values in mark.args[1]])
        for index, values in enumerate(itertools.product(*groups) if groups else [()]):
            result[f"{label}_{index}"] = dict(label=label, method=method, setup=setup,
                params={key: value for group in values for key, value in group.items()})
    return result


SCENARIOS = scenarios()


def prepare(url, output, name):
    root = Path(__file__).resolve().parents[1]
    if not output.is_absolute() or output.resolve().is_relative_to(root):
        raise ValueError("Use external output")
    preflight(url)
    directory = output / name
    if directory.exists():
        raise ValueError("Output already exists")
    export(url, directory, "formal")
    config = json.loads((directory / "database.json").read_text())
    engine = create_relational_engine(url)
    runtime = SimpleNamespace(sessions=create_session_factory(engine), workspace_id=config["workspace"])
    metadata = SCENARIOS[name]
    setup = metadata["setup"]
    identifier = None
    try:
        if setup == "posted":
            identifier = _posted(runtime)
        elif setup in {"mirror", "draft_mirror", "manual_relation"}:
            with runtime.sessions.begin() as session:
                _, relation = _existing_mirror(session, runtime)
                if setup == "draft_mirror":
                    session.get(TransactionRelationModel, relation).state = "draft"
                elif setup == "manual_relation":
                    session.get(CashTransactionModel, 1001).record_origin = "manual"
                else:
                    session.get(CashTransactionModel, 1003).note = "平台原文"
                    session.get(CashTransactionModel, 1001).note = "银行原文"
            identifier = "1003"
        elif setup == "manual":
            with runtime.sessions() as session:
                account = session.get(AccountModel, 101).name
            created = CashLedgerCommandService(runtime.sessions, runtime.workspace_id).create_record(dict(
                account_name=account, amount="-20", currency="CNY", occurred_at="2026-08-15T02:00:00+00:00", record_type="consumption"))
            identifier = str(created["record"]["id"])
        elif setup == "manual_discovery":
            with runtime.sessions.begin() as session:
                _bind_existing(session, runtime, _file())
                session.get(CashTransactionModel, 1003).record_origin = "manual"
            identifier = "1003"
        elif setup == "investment_transfer":
            with runtime.sessions.begin() as session:
                session.get(AccountModel, 103).currencies = ["CNY"]
                accounts = [session.get(AccountModel, i).name for i in (101, 103)]
            assert TransferService(RelationalUnitOfWork(runtime.sessions, runtime.workspace_id)).transfer(
                from_name=accounts[0], to_name=accounts[1], amount=Decimal("50"), from_currency="CNY", to_currency="CNY").ok
            with runtime.sessions() as session:
                identifier = str(session.scalar(select(CashTransactionModel.id).where(CashTransactionModel.record_origin == "manual_transfer")))
        elif setup == "current_mirror":
            _start(runtime, state="confirmed")
            identifier = "1101"
        elif setup == "candidate":
            _candidate_references(runtime)
            identifier = "1102"
        elif setup != "empty":
            raise AssertionError(setup)
        CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
        if metadata["label"] == "public_rebuild":
            with runtime.sessions.begin() as session:
                state = session.get(CashProjectionStateModel, runtime.workspace_id)
                state.last_build_status, state.last_error_code, state.last_error_summary = "failed", "old_build_failed", "之前构建失败"
        store = InMemoryParsedImportStore(clock=lambda: NOW)
        variants = {}
        for label, files in {"first": (_file(note="来源甲"),), "second": (_file(note="来源乙"),),
            "third": (_file(note="来源丙"),), "mirror": _mirror_files(), "default": (_file(),)}.items():
            staged = store.create(workspace_id=runtime.workspace_id, user_id=USER, files=files)
            variants[label] = dict(session_id=staged.session_id, session=json.loads(store._encode(staged)))
        (directory / "entry.json").write_text(json.dumps(dict(metadata=metadata, transaction_id=identifier, files=variants), ensure_ascii=False, indent=2) + "\n")
        (output / "scenarios.json").write_text(json.dumps(SCENARIOS, ensure_ascii=False, indent=2) + "\n")
        print("prepared original initial state", name)
    finally:
        engine.dispose()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--scenario", choices=SCENARIOS, required=True)
    args = parser.parse_args()
    prepare(args.database_url, args.output, args.scenario)
