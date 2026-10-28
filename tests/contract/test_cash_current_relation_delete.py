"""删除按父端点和当前候选清理，不破坏其余合法关系。"""
from copy import deepcopy
from decimal import Decimal

import pytest
from sqlalchemy import select

from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.cash_sources import CashSourceRepository
from ft.adapters.relational.models import (CashIssueCandidateModel, CashSourceBindingModel, CashTransactionComponentModel,
    CashTransactionIssueModel, CashTransactionModel, TransactionRelationModel)
from ft.application.cash_ledger import CashLedgerCommandService
from ft.application.cash_projections import CashProjectionService
from tests.contract.test_cash_current_relation_plan import _facts, _rows, _start
from tests.contract.test_cash_import_commit import configured, _plan, _commit, _results
from tests.contract.test_cash_record_maintenance import _service as _records
from tests.contract.test_cash_relation_context import _add
from tests.contract.test_parsed_cash_import_context import context_runtime


def _delete(runtime, identifier, operation="record"):
    commands = CashLedgerCommandService(runtime.sessions, runtime.workspace_id)
    if operation == "record":
        return commands.delete_record(str(identifier))
    detail = _records(runtime).read(str(identifier))
    return commands.delete_projections([detail["projection_ref"]["projection_id"]],
        projection_version=detail["projection_ref"]["projection_version"])


@pytest.mark.parametrize("operation", ["record", "projection"])
@pytest.mark.parametrize("active", [True, False])
def test_delete_parent_only_draft_cleans_its_current_relation(context_runtime, operation, active):
    from sqlalchemy import delete
    runtime = context_runtime
    _facts(runtime)
    with runtime.sessions.begin() as session:
        parent = session.get(CashTransactionModel, 1101)
        parent.state, parent.account_id, parent.amount, parent.currency = "draft", None, None, None
        parent.occurred_at, parent.record_type, parent.record_subtype = None, None, None
        session.execute(delete(CashTransactionComponentModel).where(CashTransactionComponentModel.cash_transaction_id == 1101))
        CurrentCashRelationRepository(session, runtime.workspace_id).add({"kind": "payment_mirror", "active": active,
            "primary_transaction_id": 1101, "anchor_transaction_id": 1101})
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    assert _delete(runtime, 1101, operation)["deleted"]
    with runtime.sessions() as session:
        parent = session.get(CashTransactionModel, 1101)
        assert parent is not None and parent.deleted_at is not None
        assert parent.delete_reason == ("delete_all" if operation == "projection" else "delete_current_dissolve")
        assert not list(session.scalars(select(TransactionRelationModel).where(TransactionRelationModel.workspace_id == runtime.workspace_id)))


def test_deleting_one_member_keeps_unrelated_confirmed_edges_in_the_old_closure(context_runtime):
    runtime = context_runtime
    _facts(runtime)
    with runtime.sessions.begin() as session:
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        _add(repo, 1101, 1102, state="confirmed")
        retained = _add(repo, 1102, 1103, kind="refund_offset", state="confirmed")
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    _delete(runtime, 1101)
    row, = _rows(runtime)
    assert row["id"] == retained["id"] and row["active"] and row["state"] == "confirmed"
    remaining = [_records(runtime).read(str(identifier)) for identifier in (1102, 1103)]
    assert remaining[0]["projection_ref"]["projection_id"] == remaining[1]["projection_ref"]["projection_id"]
    assert all(item["confirmation_status"] == "confirmed" for item in remaining)


def test_deleting_a_joint_draft_member_clears_the_complete_unconfirmed_group(context_runtime):
    runtime = context_runtime
    _, mirrors = _start(runtime, joint=True)
    with runtime.sessions.begin() as session:
        retained = session.scalar(select(TransactionRelationModel).where(TransactionRelationModel.primary_transaction_id == 1102,
            TransactionRelationModel.secondary_transaction_id == 1104))
        retained.decision_group_id = "joint"
    _delete(runtime, 1101)
    rows = _rows(runtime)
    assert not {row["id"] for row in rows}.intersection(row["id"] for row in mirrors)
    retained, = rows
    assert retained["kind"] == "refund_offset" and retained["state"] == "confirmed"
    assert _records(runtime).read("1103")["confirmation_status"] == "confirmed"


def _candidate_references(runtime):
    _facts(runtime)
    with runtime.sessions.begin() as session:
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        relation = repo.add({"kind": "payment_mirror", "primary_transaction_id": 1102,
            "primary_component_id": 6102, "anchor_transaction_id": 1102, "anchor_component_id": 6102})
        repo.set_candidates(relation["id"], [{"candidate_transaction_id": identifier, "candidate_component_id": identifier + 5000,
            "expected_row_revision": session.get(CashTransactionModel, identifier).row_revision,
            "evidence": {"primary_original_expense": identifier == 1105}} for identifier in (1101, 1105)],
            expected_revision=relation["row_revision"])
        issue = CashSourceRepository(session, runtime.workspace_id).save_issue(1105, "identity:delete", code="source_identity_ambiguous",
            pending_source_payload={"备注": "保留当前原文"}, candidates={identifier: session.get(CashTransactionModel, identifier).row_revision
                for identifier in (1101, 1102)})
        issue_before = {key: deepcopy(getattr(issue, key)) for key in ("id", "revision", "code", "origin", "pending_source_payload", "pending_fields")}
        CashSourceRepository(session, runtime.workspace_id).save_issue(1101, "source:delete-own", code="source_update_pending",
            pending_source_payload={"备注": "目标自身未采用原文"})
        from ft.domain.cash_source_matching import provider_identity_key
        session.add(CashSourceBindingModel(workspace_id=runtime.workspace_id, transaction_id=1101,
            source_type="current-relation-fixture", source_scope_kind="account", source_scope_key="synthetic-deletion",
            identity_kind="provider_id", identity_key=provider_identity_key("synthetic-delete-1101"),
            identity_strength="provider", strategy_version="synthetic-deletion"))
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    return issue_before


@pytest.mark.parametrize("operation", ["record", "projection"])
def test_deleting_only_a_candidate_keeps_other_proofs_and_advances_current_revisions(context_runtime, operation):
    runtime = context_runtime
    issue_before = _candidate_references(runtime)
    before, = _rows(runtime)
    _delete(runtime, 1101, operation)
    after, = _rows(runtime)
    assert after["id"] == before["id"] and after["row_revision"] > before["row_revision"] and after["state"] == "draft"
    assert after["candidates"] == [candidate for candidate in before["candidates"] if candidate["candidate_transaction_id"] == 1105]
    with runtime.sessions() as session:
        issue = session.get(CashTransactionIssueModel, issue_before["id"])
        assert issue.revision > issue_before["revision"]
        assert all(getattr(issue, key) == value for key, value in issue_before.items() if key != "revision")
        assert list(session.scalars(select(CashIssueCandidateModel.candidate_transaction_id).where(
            CashIssueCandidateModel.issue_id == issue.id))) == [1102]
        assert not list(session.scalars(select(CashSourceBindingModel).where(CashSourceBindingModel.transaction_id == 1101)))
        assert not list(session.scalars(select(CashTransactionIssueModel).where(CashTransactionIssueModel.transaction_id == 1101)))


@pytest.mark.parametrize("provider", ["synthetic-deleted-provider", None])
def test_deleted_source_occurrence_is_silently_imported_as_a_new_record(context_runtime, provider):
    from tests.contract.test_cash_import_operations import _file
    runtime = context_runtime
    files = (_file(provider=provider),)
    importer, plan = _plan(runtime, files)
    identifier = _results(importer, _commit(importer, plan), "row")[0]["transaction_id"]
    before = _records(runtime).read(identifier)["fields"]
    _delete(runtime, identifier)
    with runtime.sessions() as session:
        deleted = session.get(CashTransactionModel, int(identifier))
        assert deleted is not None and deleted.deleted_at is not None and deleted.deleted_by == "web"
        assert deleted.amount == Decimal(before["amount"]) and deleted.source_payload == before["source_payload"]
        parts = list(session.scalars(select(CashTransactionComponentModel).where(
            CashTransactionComponentModel.cash_transaction_id == int(identifier))))
        assert len(parts) == len(before["components"])
        assert not list(session.scalars(select(CashSourceBindingModel).where(CashSourceBindingModel.transaction_id == int(identifier))))
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    with runtime.sessions() as session:
        from ft.adapters.relational.projections import RelationalCashProjectionRepository
        repository = RelationalCashProjectionRepository(session, runtime.workspace_id)
        assert repository.current_references({int(identifier)}, state=repository.require_ready_state_lock()) == {}
    importer, repeated = _plan(runtime, files)
    assert repeated["summary"]["create_posted"] == 1 and repeated["summary"].get("create_draft", 0) == 0
    receipt = _commit(importer, repeated, "synthetic-reimport-after-delete")
    result, = _results(importer, receipt, "row")
    assert result["outcome"] == "created_posted" and result["transaction_id"] != identifier


@pytest.mark.parametrize("failure", ["references", "projection", "balance"])
def test_delete_failure_rolls_back_current_references_parent_balance_and_projection(context_runtime, monkeypatch, failure):
    from ft.application.cash_record_deletion import CashRecordDeletion
    from tests.contract.test_cash_mutation_commit import _snapshot
    runtime = context_runtime
    issue_before = _candidate_references(runtime)
    before, fields, balances = _rows(runtime), _records(runtime).read("1101"), _snapshot(runtime)
    with runtime.sessions() as session:
        bindings = [(row.id, row.transaction_id, row.identity_key, row.revision) for row in session.scalars(
            select(CashSourceBindingModel).where(CashSourceBindingModel.transaction_id == 1101))]
        parts = [(row.id, row.account_id, row.amount, row.currency, row.ordinal, row.label, row.source_key, row.metadata_json)
            for row in session.scalars(select(CashTransactionComponentModel).where(
                CashTransactionComponentModel.cash_transaction_id == 1101))]
    version = CashProjectionService(runtime.sessions, runtime.workspace_id).status()["projection_version"]
    owner, name = {"references": (CashRecordDeletion, "clear_references"),
        "projection": (CashProjectionService, "maintain_if_ready_in_session"),
        "balance": (CashLedgerCommandService, "_snapshot_deltas")}[failure]
    original = getattr(owner, name)
    def fail(*args, **kwargs):
        original(*args, **kwargs)
        raise RuntimeError("synthetic deletion publication failure")
    monkeypatch.setattr(owner, name, fail)
    with pytest.raises(RuntimeError):
        _delete(runtime, 1101)
    assert _rows(runtime) == before and _records(runtime).read("1101") == fields and _snapshot(runtime) == balances
    assert CashProjectionService(runtime.sessions, runtime.workspace_id).status()["projection_version"] == version
    with runtime.sessions() as session:
        issue = session.get(CashTransactionIssueModel, issue_before["id"])
        assert all(getattr(issue, key) == value for key, value in issue_before.items())
        assert set(session.scalars(select(CashIssueCandidateModel.candidate_transaction_id).where(
            CashIssueCandidateModel.issue_id == issue.id))) == {1101, 1102}
        assert [(row.id, row.transaction_id, row.identity_key, row.revision) for row in session.scalars(
            select(CashSourceBindingModel).where(CashSourceBindingModel.transaction_id == 1101))] == bindings
        assert [(row.id, row.account_id, row.amount, row.currency, row.ordinal, row.label, row.source_key, row.metadata_json)
            for row in session.scalars(select(CashTransactionComponentModel).where(
                CashTransactionComponentModel.cash_transaction_id == 1101))] == parts


def test_same_group_in_another_workspace_is_not_part_of_deletion(context_runtime):
    from ft.adapters.relational.models import WorkspaceModel
    runtime = context_runtime
    _start(runtime, joint=True)
    other = "delete-other-workspace"
    with runtime.sessions.begin() as session:
        session.add(WorkspaceModel(id=other, name="删除隔离"))
        session.flush()
        session.add(CashTransactionModel(id=2101, workspace_id=other, state="draft"))
        session.flush()
        row = CurrentCashRelationRepository(session, other).add({"kind": "payment_mirror", "decision_group_id": "joint",
            "primary_transaction_id": 2101, "anchor_transaction_id": 2101})
    _delete(runtime, 1101)
    with runtime.sessions() as session:
        assert CurrentCashRelationRepository(session, other).get(row["id"]) == row


def test_parent_only_relation_is_counted_in_projection_delete_preview(context_runtime):
    from sqlalchemy import delete
    runtime = context_runtime
    _facts(runtime)
    with runtime.sessions.begin() as session:
        parent = session.get(CashTransactionModel, 1101)
        parent.state = "draft"
        session.execute(delete(CashTransactionComponentModel).where(CashTransactionComponentModel.cash_transaction_id == 1101))
        CurrentCashRelationRepository(session, runtime.workspace_id).add({"kind": "payment_mirror",
            "primary_transaction_id": 1101, "anchor_transaction_id": 1101})
    status = CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    impact = CashLedgerCommandService(runtime.sessions, runtime.workspace_id).preview_delete_projections(["cash:1101"],
        projection_version=status["projection_version"])
    assert impact == {"projection_count": 1, "transaction_count": 1, "relation_group_count": 1}


def test_deleting_a_prechecked_candidate_invalidates_the_old_current_relation_plan(context_runtime):
    from ft.adapters.relational.cash_mutations import CashMutationConflict
    from tests.contract.test_cash_current_relation_plan import _choose, _groups
    from tests.contract.test_cash_mutation_business_plan import _preview
    from tests.contract.test_cash_mutation_commit import _service, _commit as _maintain
    runtime = context_runtime
    _candidate_references(runtime)
    service = _service(runtime)
    first = _preview(service, runtime, "1102", intent="maintain_relations")
    group, = _groups(service, first)
    candidate = next(item for item in group["candidates"] if {ref["transaction_id"] for ref in item["member_refs"]} == {"1101", "1102"})
    selected = _choose(service, runtime, first, {"suggestion_id": group["suggestion_id"],
        "action": "accept", "candidate_id": candidate["candidate_id"]}, identifier="1102")
    _delete(runtime, 1101)
    before = _rows(runtime)
    with pytest.raises(CashMutationConflict) as error:
        _maintain(service, selected)
    assert error.value.code == "relation_context_changed" and _rows(runtime) == before
