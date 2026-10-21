"""共享歧义候选不随失败流水数重复膨胀，来源资格仍逐笔保持。"""
from dataclasses import replace
import json
import pytest
from sqlalchemy import select

from ft.application.cash_import_business_plan import CashImportBusinessPlanBuilder
from ft.application.cash_import_retry_sources import apply_retry_proofs, build_retry_proofs
from ft.application.cash_import_source_plan import CashImportSourcePlanner, PreparedCashSourcePlan
from ft.application.cash_import_source_rows import source_batch_rows
from ft.domain.cash_source_batch import resolve_source_batch
from ft.domain.cash_source_matching import SourceRecord
from tests.contract.test_cash_import_commit import configured, _plan, _commit, _results
from tests.contract.test_cash_import_operations import _selections
from tests.contract.test_cash_import_retry import _no_number_file, _retry
from tests.contract.test_parsed_cash_import_context import context_runtime


def _prepared(files, count, *, other_scope=False):
    rows = source_batch_rows(files)
    sample = next(iter(rows.values()))
    records = tuple(SourceRecord(str(10000 + index), sample.source_type,
        "other-scope" if other_scope and index % 2 else sample.source_account_key,
        {**sample.fields, "state": "posted", "account_id": "101", "components": []}) for index in range(count))
    return PreparedCashSourcePlan(rows, records, resolve_source_batch(rows, records), ())


def _units(prepared):
    return {reference: {"row_refs": [reference], "source_ref": reference, "operation_ref": f"op_{index}"}
        for index, reference in enumerate(prepared.rows)}


def _candidates(value, pools):
    return pools[value["candidate_set_ref"]] if value.get("candidate_set_ref") else value["candidate_ids"]


def test_many_failed_pending_units_share_one_private_candidate_collection(context_runtime, monkeypatch):
    sizes = []
    for count in (32, 128):
        files = (replace(_no_number_file(letter="b", count=count), is_subset=True),)
        prepared = _prepared(files, count)
        monkeypatch.setattr(CashImportSourcePlanner, "prepare", lambda *_args, **_kwargs: prepared)
        units = _units(prepared)
        with context_runtime.sessions() as session:
            proofs = build_retry_proofs(session, context_runtime.workspace_id, files, units, prepared,
                {unit["operation_ref"] for unit in units.values()})
        expected = {record.transaction_id for record in prepared.records}
        for unit in units.values():
            assert set(_candidates(unit["resolution"], proofs.get("candidate_sets", {}))) == expected
        references = sum(len(unit["resolution"]["candidate_ids"]) for unit in units.values())
        references += sum(len(values) for values in proofs.get("candidate_sets", {}).values())
        assert references == count
        sizes.append(len(json.dumps({"units": units, "source_proofs": proofs})))
    assert sizes[1] < 5 * sizes[0]


def test_changed_proof_reuses_current_groups_through_frozen_plan(context_runtime, monkeypatch):
    sizes = []
    for count in (32, 128):
        files = (replace(_no_number_file(letter="b", count=count), is_subset=True),)
        original = _prepared(files, 0)
        monkeypatch.setattr(CashImportSourcePlanner, "prepare", lambda *_args, **_kwargs: original)
        units = _units(original)
        with context_runtime.sessions() as session:
            proofs = build_retry_proofs(session, context_runtime.workspace_id, files, units, original,
                {unit["operation_ref"] for unit in units.values()})
            current = _prepared(files, count)
            monkeypatch.setattr(CashImportSourcePlanner, "prepare", lambda *_args, **_kwargs: current)
            result = apply_retry_proofs(session, context_runtime.workspace_id, files,
                {"units": units, "source_proofs": proofs}, {})
            expected = {record.transaction_id for record in current.records}
            groups = {group.group_ref: group for group in result.plan.groups}
            for row in result.plan.rows:
                candidates = groups[row.group_ref].candidate_ids if row.group_ref else row.candidate_ids
                assert row.action == "pending" and set(candidates) == expected
            references = sum(len(row.candidate_ids) for row in result.plan.rows)
            references += sum(len(group.candidate_ids) for group in result.plan.groups)
            assert references == count
            plan = CashImportBusinessPlanBuilder(context_runtime.workspace_id)(session=session, files=files,
                preview_input={"account_selections": _selections(files), "row_choices": [], "relation_choices": []},
                previous_plan=None, plan_id="synthetic-dense-candidates", source_plan=result)
        assert len(plan["operations"]) == count
        assert all(operation["planned_action"] == "create_draft" for operation in plan["operations"])
        for operation in plan["operations"]:
            assert set(_candidates(operation, plan.get("source_candidate_sets", {}))) == expected
        references = sum(len(operation["candidate_ids"]) for operation in plan["operations"])
        references += sum(len(values) for values in plan.get("source_candidate_sets", {}).values())
        assert references == count
        sizes.append(len(json.dumps(plan)))
    assert sizes[1] < 5 * sizes[0]


def test_shared_retry_candidates_keep_current_source_scope_filter(context_runtime, monkeypatch):
    files = (replace(_no_number_file(letter="b", count=8), is_subset=True),)
    original = _prepared(files, 0)
    monkeypatch.setattr(CashImportSourcePlanner, "prepare", lambda *_args, **_kwargs: original)
    units = _units(original)
    with context_runtime.sessions() as session:
        proofs = build_retry_proofs(session, context_runtime.workspace_id, files, units, original,
            {unit["operation_ref"] for unit in units.values()})
        current = _prepared(files, 8, other_scope=True)
        monkeypatch.setattr(CashImportSourcePlanner, "prepare", lambda *_args, **_kwargs: current)
        result = apply_retry_proofs(session, context_runtime.workspace_id, files, {"units": units, "source_proofs": proofs}, {})
    expected = {str(10000 + index) for index in range(0, 8, 2)}
    groups = {group.group_ref: group for group in result.plan.groups}
    for row in result.plan.rows:
        candidates = groups[row.group_ref].candidate_ids if row.group_ref else row.candidate_ids
        assert row.action == "pending" and set(candidates) == expected


def _unknown_file(letter):
    file = _no_number_file(letter=letter, count=2)
    return replace(file, document=replace(file.document, coverage=replace(file.document.coverage, complete=False, evidence="")))


@pytest.mark.parametrize("change_candidate", [False, True])
def test_frozen_shared_candidates_save_current_draft_issues(context_runtime, change_candidate):
    from ft.adapters.relational.models import CashTransactionModel, CashTransactionIssueModel, CashIssueCandidateModel
    first, first_plan = _plan(context_runtime, (_unknown_file("a"),))
    original = _commit(first, first_plan, "synthetic-source-candidates")
    identifiers = {int(row["transaction_id"]) for row in _results(first, original, "row")}
    service, plan = _plan(context_runtime, (_unknown_file("b"),))
    assert plan["summary"].get("create_draft") == 2
    if change_candidate:
        with context_runtime.sessions.begin() as session:
            session.get(CashTransactionModel, min(identifiers)).note = "当前候选的新备注"
    saved = _commit(service, plan, "synthetic-shared-pending")
    assert saved["summary"]["saved_draft"] == 2 and saved["summary"]["skipped"] == 0
    with context_runtime.sessions() as session:
        expected = {identifier: session.get(CashTransactionModel, identifier).row_revision for identifier in identifiers}
        issues = session.scalars(select(CashTransactionIssueModel).where(CashTransactionIssueModel.code == "source_identity_ambiguous")).all()
        assert len(issues) == 2
        for issue in issues:
            candidates = session.scalars(select(CashIssueCandidateModel).where(CashIssueCandidateModel.issue_id == issue.id)).all()
            assert {row.candidate_transaction_id: row.expected_row_revision for row in candidates} == expected


def test_failed_draft_save_refreshes_shared_candidates_before_other_row(context_runtime, monkeypatch):
    from ft.adapters.relational.models import CashTransactionModel, CashIssueCandidateModel
    first, old = _plan(context_runtime, (_unknown_file("a"),))
    original = _commit(first, old, "synthetic-source-candidates")
    identifiers = {int(row["transaction_id"]) for row in _results(first, original, "row")}
    service, plan = _plan(context_runtime, (_unknown_file("b"),))
    save = service._save_row
    def fail(session, operation, sources):
        result = save(session, operation, sources)
        if operation["row_refs"] == ["file_b:r1"]:
            session.get(CashTransactionModel, min(identifiers)).note = "应当回滚的候选变化"
            session.flush()
            raise RuntimeError("synthetic pending save rollback")
        return result
    monkeypatch.setattr(service, "_save_row", fail)
    saved = _commit(service, plan, "synthetic-shared-pending-fault")
    assert saved["summary"]["saved_draft"] == 1 and saved["summary"]["skipped"] == 1
    with context_runtime.sessions() as session:
        assert session.get(CashTransactionModel, min(identifiers)).note != "应当回滚的候选变化"
        assert {row.candidate_transaction_id for row in session.scalars(select(CashIssueCandidateModel))} == identifiers
    child = _retry(service, saved)
    assert [row.position for file in child.files for row in file.document.rows] == [1]
    assert service.preview(child.session_id, expected_session_revision=1)["summary"]["create_draft"] == 1
