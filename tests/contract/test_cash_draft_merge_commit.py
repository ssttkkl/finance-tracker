"""身份合并只保留目标；字段、当前问题、双向引用及源退出一起提交。"""
from copy import deepcopy
from decimal import Decimal

import pytest
from sqlalchemy import select

from ft.adapters.relational.cash_sources import CashSourceRepository
from ft.adapters.relational.models import (CashEntryModel, CashEntryMemberModel, CashIssueCandidateModel, CashMutationReceiptModel, CashRelationCandidateModel,
    CashSourceBindingModel, CashTransactionComponentModel, CashTransactionIssueModel, CashTransactionModel, TransactionRelationModel)
from ft.application.cash_import_operations import _wire
from tests.contract.test_cash_draft_merge_plan import _pair, _merge, _choice
from tests.contract.test_cash_import_commit import configured, _plan, _commit as _import_commit, _results
from tests.contract.test_cash_import_operations import _file
from tests.contract.test_cash_mutation_commit import _service, _commit, _snapshot
from tests.contract.test_cash_mutation_business_plan import _preview
from tests.contract.test_cash_record_maintenance import _service as _records
from tests.contract.test_cash_relation_storage import _relation
from tests.contract.test_cash_source_issue_resolution import _issue
from tests.contract.test_parsed_cash_import_context import context_runtime


def _business(runtime):
    models = (CashTransactionModel, CashTransactionComponentModel, CashSourceBindingModel, CashTransactionIssueModel,
        CashIssueCandidateModel, TransactionRelationModel, CashRelationCandidateModel, CashEntryModel, CashEntryMemberModel)
    with runtime.sessions() as session:
        return {model.__tablename__: _wire([{column.name: deepcopy(getattr(row, column.name)) for column in model.__table__.columns}
            for row in session.scalars(select(model).where(model.workspace_id == runtime.workspace_id).order_by(*model.__table__.primary_key.columns))]) for model in models}


@pytest.mark.parametrize("draft_target", [False, True])
def test_merge_keeps_target_identity_balance_and_permanent_replay(context_runtime, draft_target):
    runtime = context_runtime
    source, target = _pair(runtime, draft_target=draft_target)
    service, before_balance = _service(runtime), _snapshot(runtime)
    identity = next(issue for issue in _records(runtime).read(source)["issues"] if issue["code"] == "source_identity_ambiguous")
    assert "merge_into" in identity["allowed_resolutions"]
    view = _merge(service, runtime, source, target, patch={"note": ""})
    assert view["can_commit"]
    receipt = _commit(service, view)
    assert receipt["status"] == "completed" and receipt["summary"]["merged_draft"] == 1
    assert receipt["retained_transaction_id"] == target and receipt["summary"]["resolved_issues"] == 1
    current = _records(runtime).read(target)
    assert current["state"] == ("draft" if draft_target else "posted") and current["entry"]["fields"]["note"] == ""
    assert _snapshot(runtime) == before_balance
    with pytest.raises(ValueError) as error:
        _records(runtime).read(source)
    assert error.value.code == "record_not_found"
    with runtime.sessions() as session:
        assert session.get(CashTransactionModel, int(source)).deleted_at is not None
        assert not CashSourceRepository(session, runtime.workspace_id).bindings_for(source)
        assert CashSourceRepository(session, runtime.workspace_id).bindings_for(target)
        assert 'note' in session.get(CashEntryModel, current['entry']['entry_id']).manual_fields
    result, = service.results(receipt["receipt_id"], result_kind="row")["items"]
    assert result["item_ref"] == source and result["transaction_id"] == target and result["outcome"] == "merged_draft"
    service._staging._entries.clear()
    assert _commit(service, view) == receipt
    assert service.resolve(view["plan_id"], idempotency_key="synthetic-mutation-confirm")["receipt"] == receipt
    assert {ref["transaction_id"] for ref in service.results(receipt["receipt_id"])["projection_refs"]} == {target}
    if not draft_target:
        importer, repeated = _plan(runtime, (_file(letter="c"),))
        assert _results(importer, _import_commit(importer, repeated, "reimport-after-merge"), "row")[0]["transaction_id"] == target
        assert _records(runtime).read(target)["entry"]["fields"]["note"] == ""


def test_merge_preserves_issue_instances_bodies_candidates_and_same_key_collision(context_runtime):
    runtime = context_runtime
    source, target = _pair(runtime)
    first = _issue(runtime, source, key="same:key", fields={"counterparty": "源待采用名称"}, payload={"备注": "源待采用原文"})
    second = _issue(runtime, target, key="same:key", fields={"counterparty": "目标待采用名称"}, payload={"备注": "目标待采用原文"})
    with runtime.sessions.begin() as session:
        repository = CashSourceRepository(session, runtime.workspace_id)
        for index, (identifier, choice) in enumerate(((source, first), (target, second))):
            issue = session.get(CashTransactionIssueModel, int(choice["issue_id"]))
            repository.save_issue(identifier, issue.issue_key, code=issue.code, pending_fields=issue.pending_fields,
                pending_source_payload=issue.pending_source_payload, origin={"file_digest": "d" * 64, "position": index + 1},
                candidates={1001: session.get(CashTransactionModel, 1001).row_revision})
        before = {row.id: (row.pending_fields, row.pending_source_payload, row.origin) for row in session.scalars(
            select(CashTransactionIssueModel).where(CashTransactionIssueModel.id.in_((int(first["issue_id"]), int(second["issue_id"])))))}
    service = _service(runtime)
    receipt = _commit(service, _merge(service, runtime, source, target))
    assert receipt["status"] == "completed"
    with runtime.sessions() as session:
        rows = list(session.scalars(select(CashTransactionIssueModel).where(CashTransactionIssueModel.id.in_(before))))
        assert {row.id: (row.pending_fields, row.pending_source_payload, row.origin) for row in rows} == before
        assert {row.transaction_id for row in rows} == {int(target)}
        assert len({row.issue_key for row in rows}) == 2 and all(len(row.issue_key) <= 128 for row in rows)
        assert session.get(CashTransactionIssueModel, int(second["issue_id"])).issue_key == "same:key"
        assert {row.issue_id for row in session.scalars(select(CashIssueCandidateModel))} == set(before)


def _references(runtime, source):
    with runtime.sessions.begin() as session:
        source_part = session.scalar(select(CashTransactionComponentModel.id).where(CashTransactionComponentModel.cash_transaction_id == int(source)))
        first = _relation(runtime.workspace_id, int(source), 1001, primary_component_id=source_part,
            anchor_component_id=source_part, primary_role="expense", decision_group_id="merge:joint", decision_source="manual")
        second = _relation(runtime.workspace_id, 1001, 1002, decision_group_id="merge:joint", decision_source="manual")
        reverse = _relation(runtime.workspace_id, 1003, None, kind="refund_offset")
        session.add_all((first, second, reverse))
        session.flush()
        session.add(CashRelationCandidateModel(workspace_id=runtime.workspace_id, relation_id=first.id,
            candidate_transaction_id=1002, candidate_component_id=1002, expected_row_revision=1,
            evidence={"proof": "forward"}, validity="valid"))
        session.add(CashRelationCandidateModel(workspace_id=runtime.workspace_id, relation_id=reverse.id,
            candidate_transaction_id=int(source), candidate_component_id=source_part, expected_row_revision=1,
            evidence={"proof": "reverse"}, validity="valid"))
        issue = CashSourceRepository(session, runtime.workspace_id).save_issue(1002, "reverse:identity",
            code="source_update_pending", pending_fields={"counterparty": "当前待采用"}, candidates={int(source): 1})
        return first.id, second.id, reverse.id, issue.id


def test_merge_moves_both_directions_and_preserves_complete_manual_group(context_runtime):
    runtime = context_runtime
    source, target = _pair(runtime)
    first, second, reverse, issue_id = _references(runtime, source)
    service = _service(runtime)
    receipt = _commit(service, _merge(service, runtime, source, target))
    assert receipt["status"] == "completed"
    with runtime.sessions() as session:
        target_part = session.scalar(select(CashTransactionComponentModel.id).where(CashTransactionComponentModel.cash_transaction_id == int(target)))
        moved = session.get(TransactionRelationModel, first)
        assert moved.primary_transaction_id == moved.anchor_transaction_id == int(target)
        assert moved.primary_component_id == moved.anchor_component_id == target_part
        assert moved.state == "draft" and moved.decision_source == "manual"
        assert session.get(TransactionRelationModel, second).decision_group_id == moved.decision_group_id == "merge:joint"
        candidate = session.scalar(select(CashRelationCandidateModel).where(CashRelationCandidateModel.relation_id == reverse))
        assert candidate.candidate_transaction_id == int(target) and candidate.candidate_component_id == target_part
        assert candidate.validity == "stale" and candidate.evidence == {"proof": "reverse"}
        candidate = session.scalar(select(CashIssueCandidateModel).where(CashIssueCandidateModel.issue_id == issue_id))
        assert candidate.candidate_transaction_id == int(target)
        assert candidate.expected_row_revision != session.get(CashTransactionModel, int(target)).row_revision
        assert not session.scalar(select(CashIssueCandidateModel.id).where(CashIssueCandidateModel.candidate_transaction_id == int(source)))
        assert not session.scalar(select(CashRelationCandidateModel.id).where(CashRelationCandidateModel.candidate_transaction_id == int(source)))


@pytest.mark.parametrize("stage", ["row", "bindings", "issues", "relations", "candidates", "source_exit"])
def test_merge_unit_failure_restores_all_current_facts(context_runtime, monkeypatch, stage):
    from ft.application.cash_record_writer import CashRecordWriter
    from ft.application.cash_draft_merge_writer import CashDraftMergeWriter
    runtime = context_runtime
    source, target = _pair(runtime)
    _references(runtime, source)
    _issue(runtime, source, fields={"note": "不能丢失的来源正文"})
    service = _service(runtime)
    view = _merge(service, runtime, source, target, patch={"note": "未提交正文"})
    before, balance = _business(runtime), _snapshot(runtime)
    owner, name = (CashRecordWriter, "save") if stage == "row" else (CashSourceRepository, "transfer_draft_bindings") if stage == "bindings" else (
        CashDraftMergeWriter, {"issues": "_move_issues", "relations": "_move_relations", "candidates": "_move_candidates", "source_exit": "_retire_source"}[stage])
    original = getattr(owner, name)
    def failed(*args, **kwargs):
        original(*args, **kwargs)
        raise RuntimeError("synthetic merge unit failure after write")
    monkeypatch.setattr(owner, name, failed)
    from tests.contract.test_cash_import_commit import _assert_atomic_failure
    _assert_atomic_failure(context_runtime, lambda: _commit(service, view))


@pytest.mark.parametrize("stage", ["projection", "receipt"])
def test_merge_publication_failure_rolls_back_everything_and_keeps_plan(context_runtime, monkeypatch, stage):
    from ft.application.cash_commit_outcome import CashCommitFailure
    from ft.adapters.relational.cash_mutations import CashMutationRepository
    from ft.application.cash_projections import CashProjectionService
    runtime = context_runtime
    source, target = _pair(runtime)
    service = _service(runtime)
    view = _merge(service, runtime, source, target, patch={"note": "外层不能部分保存"})
    before, balance = _business(runtime), _snapshot(runtime)
    owner, name = (CashProjectionService, "maintain_in_session") if stage == "projection" else (CashMutationRepository, "create")
    original = getattr(owner, name)
    def failed(*args, **kwargs):
        original(*args, **kwargs)
        raise RuntimeError("synthetic publication failure after write")
    with monkeypatch.context() as patch:
        patch.setattr(owner, name, failed)
        with pytest.raises(CashCommitFailure, match="^commit_failed$") as failure:
            _commit(service, view)
        assert failure.value.commit_state == "not_committed"
        assert "synthetic publication" not in str(failure.value)
    assert _business(runtime) == before and _snapshot(runtime) == balance
    assert service.read_plan(view["plan_id"]) == view
    with runtime.sessions() as session:
        assert session.scalar(select(CashMutationReceiptModel.id).where(CashMutationReceiptModel.plan_id == view["plan_id"])) is None
    assert _commit(service, view)["retained_transaction_id"] == target


def test_equivalent_candidates_are_removed_before_target_parts_are_cleared(context_runtime):
    runtime = context_runtime
    source, target = _pair(runtime, draft_target=True)
    with runtime.sessions.begin() as session:
        parent = session.get(CashTransactionModel, int(target))
        parent.account_id, parent.cash_granularity = None, "aggregate"
        first = session.scalar(select(CashTransactionComponentModel).where(CashTransactionComponentModel.cash_transaction_id == int(target)))
        first.amount = Decimal("-6.25")
        second = CashTransactionComponentModel(workspace_id=runtime.workspace_id, cash_transaction_id=int(target),
            ordinal=1, account_id=101, amount=Decimal("-6.25"), currency="CNY")
        relation = _relation(runtime.workspace_id, 1001, None, kind="refund_offset")
        session.add_all((second, relation))
        session.flush()
        for part in (first, second):
            session.add(CashRelationCandidateModel(workspace_id=runtime.workspace_id, relation_id=relation.id,
                candidate_transaction_id=int(target), candidate_component_id=part.id, expected_row_revision=parent.row_revision,
                evidence={"proof": "equal"}, validity="incomplete"))
        relation_id = relation.id
    service = _service(runtime)
    receipt = _commit(service, _merge(service, runtime, source, target, patch={"components": []}))
    assert receipt["status"] == "completed"
    with runtime.sessions() as session:
        candidate, = session.scalars(select(CashRelationCandidateModel).where(CashRelationCandidateModel.relation_id == relation_id))
        assert candidate.candidate_transaction_id == int(target) and candidate.candidate_component_id is None


def test_source_issue_key_also_avoids_new_target_issue_from_same_confirmation(context_runtime):
    runtime = context_runtime
    source, target = _pair(runtime, draft_target=True)
    with runtime.sessions() as session:
        source_issues = {row.id: (row.issue_key, row.code, row.field_path, row.pending_fields, row.pending_source_payload, row.origin)
            for row in CashSourceRepository(session, runtime.workspace_id).issues_for(source) if row.code != "source_identity_ambiguous"}
    detail = _records(runtime).read(target)
    part = {**detail["fields"]["components"][0], "account_id": None}
    service = _service(runtime)
    receipt = _commit(service, _merge(service, runtime, source, target, patch={"account_id": None, "components": [part]}))
    assert receipt["status"] == "completed" and source_issues
    with runtime.sessions() as session:
        target_issues = CashSourceRepository(session, runtime.workspace_id).issues_for(target)
        for identifier, (old_key, code, path, fields, payload, origin) in source_issues.items():
            moved = session.get(CashTransactionIssueModel, identifier)
            assert moved.transaction_id == int(target) and moved.issue_key != old_key
            assert (moved.pending_fields, moved.pending_source_payload, moved.origin) == (fields, payload, origin)
            assert len([row for row in target_issues if row.code == code and row.field_path == path]) == 2


@pytest.mark.parametrize("same_evidence", [True, False])
def test_duplicate_candidates_keep_existing_target_only_when_equivalent(context_runtime, same_evidence):
    runtime = context_runtime
    source, target = _pair(runtime)
    with runtime.sessions.begin() as session:
        relation = _relation(runtime.workspace_id, 1001, None, kind="refund_offset")
        session.add(relation)
        session.flush()
        candidates = []
        for identifier in (source, target):
            part = session.scalar(select(CashTransactionComponentModel.id).where(CashTransactionComponentModel.cash_transaction_id == int(identifier)))
            candidate = CashRelationCandidateModel(workspace_id=runtime.workspace_id, relation_id=relation.id,
                candidate_transaction_id=int(identifier), candidate_component_id=part,
                expected_row_revision=session.get(CashTransactionModel, int(identifier)).row_revision,
                evidence={"proof": "same" if same_evidence or identifier == source else "different"}, validity="valid")
            session.add(candidate)
            session.flush()
            candidates.append(candidate.id)
        relation_id, target_candidate_id = relation.id, candidates[1]
    before, service = _business(runtime), _service(runtime)
    if not same_evidence:
        with pytest.raises(ValueError) as error:
            _merge(service, runtime, source, target)
        assert error.value.code == "relation_context_changed"
        assert _business(runtime) == before and not service._staging._entries
    else:
        receipt = _commit(service, _merge(service, runtime, source, target))
        assert receipt["status"] == "completed"
        with runtime.sessions() as session:
            candidate, = session.scalars(select(CashRelationCandidateModel).where(CashRelationCandidateModel.relation_id == relation_id))
            assert candidate.id == target_candidate_id and candidate.validity == "stale"
            assert candidate.expected_row_revision != session.get(CashTransactionModel, int(target)).row_revision


def test_pending_source_component_reference_remains_maintainable_after_move(context_runtime):
    runtime = context_runtime
    source, target = _pair(runtime)
    source_part = _records(runtime).read(source)["fields"]["components"][0]
    choice = _issue(runtime, source, fields={"components": [source_part]})
    service = _service(runtime)
    receipt = _commit(service, _merge(service, runtime, source, target))
    assert receipt["status"] == "completed"
    current = _records(runtime).read(target)
    moved = next(issue for issue in current["issues"] if issue["issue_id"] == choice["issue_id"])
    assert moved["pending_fields"]["components"] == current["fields"]["components"]
    assert "apply_source" in moved["allowed_resolutions"]
    view = _preview(service, runtime, target, intent="resolve_issue", issue_resolutions=[{
        "issue_id": moved["issue_id"], "expected_issue_revision": moved["revision"], "resolution": "apply_source"}])
    assert _commit(service, view, "resolve-moved-body")["summary"]["resolved_issues"] == 1


def test_merge_rejects_posted_target_type_change_and_preserves_group(context_runtime):
    runtime = context_runtime
    source, target = _pair(runtime, mirror_target=True)
    before, balances = _business(runtime), _snapshot(runtime)
    with pytest.raises(ValueError):
        _merge(_service(runtime), runtime, source, target,
            patch={"record_type": "transfer_out", "record_subtype": "ordinary_transfer"})
    assert _business(runtime) == before and _snapshot(runtime) == balances


def test_category_member_failure_rolls_back_earlier_target_and_prepared_candidates(context_runtime, monkeypatch):
    from ft.application.cash_categories import CashCategoryService
    from ft.application.cash_record_writer import CashRecordWriter
    runtime = context_runtime
    source, target = _pair(runtime, mirror_target=True)
    category = CashCategoryService(runtime.sessions, runtime.workspace_id).create(name="联合分类回滚")["id"]
    service = _service(runtime)
    view = _merge(service, runtime, source, target, patch={"category_id": category, "note": "不得部分保存"})
    before, balances = _business(runtime), _snapshot(runtime)
    original = CashRecordWriter.save
    def failed(self, session, operation, source_rows):
        saved = original(self, session, operation, source_rows)
        if operation["transaction_id"] == target:
            raise RuntimeError("synthetic public attribute failure after write")
        return saved
    monkeypatch.setattr(CashRecordWriter, "save", failed)
    from tests.contract.test_cash_import_commit import _assert_atomic_failure
    _assert_atomic_failure(context_runtime, lambda: _commit(service, view))


@pytest.mark.parametrize("changed", ["binding", "reverse_candidate"])
def test_merge_rejects_current_proof_change_without_parent_revision_change(context_runtime, changed):
    from ft.application.cash_import_source_rows import source_batch_rows
    runtime = context_runtime
    source, target = _pair(runtime)
    _, _, reverse, _ = _references(runtime, source)
    service = _service(runtime)
    view = _merge(service, runtime, source, target)
    revision = _records(runtime).read(source)["row_revision"]
    with runtime.sessions.begin() as session:
        if changed == "binding":
            row, = source_batch_rows((_file(letter="c"),)).values()
            CashSourceRepository(session, runtime.workspace_id).save_occurrence(source, row, uncertain=True)
        else:
            candidate = session.scalar(select(CashRelationCandidateModel).where(CashRelationCandidateModel.relation_id == reverse))
            candidate.evidence = {"proof": "current changed evidence"}
    before = _business(runtime)
    assert _records(runtime).read(source)["row_revision"] == revision
    with pytest.raises(ValueError) as error:
        _commit(service, view)
    assert error.value.code == "record_changed" and _business(runtime) == before
    assert service.read_plan(view["plan_id"]) == view


def test_merge_applies_multiple_source_decisions_with_final_patch_in_one_receipt(context_runtime):
    runtime = context_runtime
    source, target = _pair(runtime)
    choices = [_choice(runtime, source, target), _issue(runtime, source, key="adopt:first", fields={"note": "来源甲"}),
        _issue(runtime, source, key="adopt:second", fields={"note": "来源乙"})]
    service = _service(runtime)
    view = _preview(service, runtime, source, intent="resolve_issue", patch={"note": "最后明确值"}, issue_resolutions=choices)
    receipt = _commit(service, view)
    assert receipt["status"] == "completed" and receipt["summary"]["resolved_issues"] == 3
    assert _records(runtime).read(target)["entry"]["fields"]["note"] == "最后明确值"
