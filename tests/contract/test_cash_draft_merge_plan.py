"""身份草稿合并先形成完整只读计划，不靠写入后回滚来预览。"""
import pytest
from decimal import Decimal
from sqlalchemy import delete, select

from ft.adapters.relational.models import (CashIssueCandidateModel, CashRelationCandidateModel, CashSourceBindingModel,
    CashTransactionComponentModel, CashTransactionModel, TransactionRelationModel)
from ft.adapters.relational.cash_sources import CashSourceRepository
from ft.application.cash_projections import CashProjectionService
from tests.contract.test_cash_import_commit import configured, _plan, _commit as _import_commit, _results
from tests.contract.test_cash_import_operations import _file
from tests.contract.test_cash_independent_identity import _bindings
from tests.contract.test_cash_mutation_business_plan import _posted, _preview, _members, _plan_data
from tests.contract.test_cash_mutation_commit import _service, _snapshot
from tests.contract.test_cash_record_maintenance import _draft, _complete, _service as _records
from tests.contract.test_parsed_cash_import_context import context_runtime
from tests.contract.test_cash_relation_storage import _relation
from tests.contract.test_cash_source_issue_resolution import _issue
from tests.contract.test_cash_import_business_plan import _existing_mirror


def _pair(runtime, *, draft_target=False, mirror_target=False):
    if mirror_target:
        with runtime.sessions.begin() as session:
            _existing_mirror(session, runtime)
        target = "1003"
        CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    else:
        target = _draft(runtime) if draft_target else _posted(runtime)
    records = _records(runtime)
    if draft_target:
        before = records.read(target)
        records.save_draft(target, expected_revision=before["row_revision"], patch=_complete(before),
            idempotency_key="complete-merge-target")
    with runtime.sessions.begin() as session:
        session.get(CashTransactionModel, int(target)).record_id = "synthetic-merge-target"
        session.execute(delete(CashSourceBindingModel).where(CashSourceBindingModel.workspace_id == runtime.workspace_id,
            CashSourceBindingModel.transaction_id == int(target)))
    importer, view = _plan(runtime, (_file(letter="b"),), mapped=not draft_target)
    result, = _results(importer, _import_commit(importer, view, "create-merge-source"), "row")
    source = result["transaction_id"]
    assert source is not None, result
    if draft_target:
        with runtime.sessions.begin() as session:
            CashSourceRepository(session, runtime.workspace_id).save_issue(source, "synthetic:draft-target",
                code="source_identity_ambiguous", candidates={int(target): session.get(CashTransactionModel, int(target)).row_revision})
    assert source != target and records.read(source)["state"] == "draft"
    assert any(issue["code"] == "source_identity_ambiguous" for issue in records.read(source)["issues"])
    return source, target


def _choice(runtime, source, target):
    records = _records(runtime)
    issue = next(issue for issue in records.read(source)["issues"] if issue["code"] == "source_identity_ambiguous")
    return {"issue_id": issue["issue_id"], "expected_issue_revision": issue["revision"], "resolution": "merge_into",
        "target_transaction_id": target, "expected_target_revision": records.read(target)["row_revision"]}


def _merge(service, runtime, source, target, *, patch=None, choice=None):
    return _preview(service, runtime, source, intent="resolve_issue", patch=patch,
        issue_resolutions=[choice or _choice(runtime, source, target)])


def _current(runtime, source, target):
    records = _records(runtime)
    return (records.read(source), records.read(target), _bindings(runtime, source), _bindings(runtime, target), _snapshot(runtime))


@pytest.mark.parametrize("draft_target", [False, True])
def test_merge_preview_keeps_only_target_without_writing_current_state(context_runtime, draft_target):
    runtime = context_runtime
    source, target = _pair(runtime, draft_target=draft_target)
    before, service = _current(runtime, source, target), _service(runtime)
    view = _merge(service, runtime, source, target)
    assert view["summary"]["merge_draft"] == 1
    item, = view["items"]
    assert item["member_refs"] == [{"transaction_id": target}]
    assert item["anticipated_state"] == ("draft" if draft_target else "posted")
    assert set(_members(service, view)) == {target}
    assert _members(service, view)[target]["allowed_actions"] == (["save_draft"] if draft_target else ["edit_record"])
    assert _current(runtime, source, target) == before
    assert service.read_plan(view["plan_id"]) == view


@pytest.mark.parametrize("hide_in_patch", [False, True])
def test_original_financial_conflict_is_rejected_before_patch(context_runtime, hide_in_patch):
    runtime = context_runtime
    source, target = _pair(runtime)
    records = _records(runtime)
    detail = records.read(source)
    component = detail["fields"]["components"][0]
    records.save_draft(source, expected_revision=detail["row_revision"], patch={"amount": "-99", "components": [{
        "component_id": component["component_id"], "ordinal": 0, "account_id": "101", "amount": "-99", "currency": "CNY"}]},
        idempotency_key="conflicting-source-finance")
    before = _current(runtime, source, target)
    with pytest.raises(ValueError) as error:
        _merge(_service(runtime), runtime, source, target, patch={"amount": "-12.5"} if hide_in_patch else {})
    assert error.value.code == "fixed_field_conflict" and "amount" in error.value.fields
    assert _current(runtime, source, target) == before


def test_unknown_source_finances_do_not_clear_known_target(context_runtime):
    runtime = context_runtime
    source, target = _pair(runtime)
    with runtime.sessions.begin() as session:
        parent = session.get(CashTransactionModel, int(source))
        parent.amount, parent.currency, parent.occurred_at, parent.account_id = None, None, None, None
        part = session.scalar(select(CashTransactionComponentModel).where(CashTransactionComponentModel.cash_transaction_id == int(source)))
        part.amount, part.currency, part.account_id = None, None, None
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    before, service = _current(runtime, source, target), _service(runtime)
    view = _merge(service, runtime, source, target)
    fields = _members(service, view)[target]["fields"]
    for field in ("amount", "currency", "occurred_at", "account_id", "components"):
        assert fields[field] == before[1]["fields"][field]
    assert _current(runtime, source, target) == before


@pytest.mark.parametrize("explicit", [False, True])
def test_both_human_notes_including_cleared_source_need_explicit_value(context_runtime, explicit):
    runtime = context_runtime
    source, target = _pair(runtime)
    with runtime.sessions.begin() as session:
        for identifier, note in ((source, ""), (target, "目标人工备注")):
            from ft.adapters.relational.cash_entries import CashEntryRepository
            entries = CashEntryRepository(session, runtime.workspace_id)
            entry = entries.current(int(identifier))
            entries.patch(int(identifier), {'note': note}, expected_revision=entry.revision)
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    before, service = _current(runtime, source, target), _service(runtime)
    if explicit:
        view = _merge(service, runtime, source, target, patch={"note": ""})
        assert view['items'][0]['display']['note'] == ""
    else:
        with pytest.raises(ValueError) as error:
            _merge(service, runtime, source, target)
        assert error.value.code == "source_resolution_conflict" and "note" in error.value.fields
    assert _current(runtime, source, target) == before


def test_target_change_rejects_merge_preview_and_keeps_original_context(context_runtime):
    runtime = context_runtime
    source, target = _pair(runtime)
    service, choice = _service(runtime), _choice(runtime, source, target)
    with runtime.sessions.begin() as session:
        session.get(CashTransactionModel, int(target)).row_revision += 1
    before = _current(runtime, source, target)
    with pytest.raises(ValueError) as error:
        _merge(service, runtime, source, target, choice=choice)
    assert error.value.code == "record_changed" and not service._staging._entries
    assert _current(runtime, source, target) == before


def test_unknown_funding_placeholder_does_not_contradict_known_split(context_runtime):
    runtime = context_runtime
    source, target = _pair(runtime)
    with runtime.sessions.begin() as session:
        session.get(CashTransactionModel, int(source)).account_id = None
        session.get(CashTransactionModel, int(target)).account_id = None
        session.get(CashTransactionModel, int(target)).cash_granularity = "aggregate"
        source_part = session.scalar(select(CashTransactionComponentModel).where(CashTransactionComponentModel.cash_transaction_id == int(source)))
        target_part = session.scalar(select(CashTransactionComponentModel).where(CashTransactionComponentModel.cash_transaction_id == int(target)))
        source_part.account_id = None
        target_part.amount = Decimal("-5")
        session.add(CashTransactionComponentModel(workspace_id=runtime.workspace_id, cash_transaction_id=int(target),
            ordinal=1, account_id=102, amount=Decimal("-7.5"), currency="CNY"))
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    before, service = _current(runtime, source, target), _service(runtime)
    view = _merge(service, runtime, source, target)
    assert _members(service, view)[target]["fields"]["components"] == before[1]["fields"]["components"]
    assert _current(runtime, source, target) == before


def test_identity_candidates_show_current_data_without_updating_stored_proof(context_runtime):
    runtime = context_runtime
    source, target = _pair(runtime)
    with runtime.sessions.begin() as session:
        row = session.get(CashTransactionModel, int(target))
        row.row_revision += 1
        row.note = "当前候选备注"
        expected_revision = row.row_revision
        before = [(row.id, row.expected_row_revision) for row in session.scalars(select(CashIssueCandidateModel))]
    issue = next(issue for issue in _records(runtime).read(source)["issues"] if issue["code"] == "source_identity_ambiguous")
    candidate, = issue["candidates"]
    assert candidate["transaction_id"] == target and candidate["row_revision"] == expected_revision
    assert candidate["state"] == "posted" and candidate["display"]["note"] == "当前候选备注"
    with runtime.sessions.begin() as session:
        assert [(row.id, row.expected_row_revision) for row in session.scalars(select(CashIssueCandidateModel))] == before
        from datetime import datetime, timezone
        session.get(CashTransactionModel, int(target)).deleted_at = datetime.now(timezone.utc)
    issue = next(issue for issue in _records(runtime).read(source)["issues"] if issue["code"] == "source_identity_ambiguous")
    assert issue["candidates"] == []


def test_merge_freezes_reverse_candidates_and_full_joint_groups(context_runtime):
    runtime = context_runtime
    source, target = _pair(runtime)
    with runtime.sessions.begin() as session:
        first = _relation(runtime.workspace_id, int(source), 1001, decision_group_id="synthetic:joint")
        second = _relation(runtime.workspace_id, 1001, 1002, decision_group_id="synthetic:joint")
        reverse = _relation(runtime.workspace_id, 1003, None, kind="refund_offset")
        session.add_all((first, second, reverse))
        session.flush()
        candidate = CashRelationCandidateModel(workspace_id=runtime.workspace_id, relation_id=reverse.id,
            candidate_transaction_id=int(source), candidate_component_id=None,
            expected_row_revision=1, evidence={"proof": "original"}, validity="incomplete")
        session.add(candidate)
        session.flush()
        candidate_id, group_ids = candidate.id, {first.id, second.id}
    before, service = _current(runtime, source, target), _service(runtime)
    view = _merge(service, runtime, source, target)
    operation, = _plan_data(service, view)["operations"]
    merge = operation["merge_draft"]
    assert group_ids <= {row["id"] for row in merge["references"]["relations"]}
    assert candidate_id in {row["id"] for row in merge["references"]["relation_candidates"]}
    assert "synthetic:joint" in {row["decision_group_id"] for row in merge["relations"]}
    assert _current(runtime, source, target) == before
    with runtime.sessions.begin() as session:
        session.get(CashRelationCandidateModel, candidate_id).evidence = {"proof": "changed"}
    fresh = _merge(service, runtime, source, target)
    updated, = _plan_data(service, fresh)["operations"]
    assert updated["merge_draft"]["snapshot"] != merge["snapshot"]


@pytest.mark.parametrize("self_edge", [False, True])
def test_merge_rejects_self_relation_or_collision_without_picking_one(context_runtime, self_edge):
    runtime = context_runtime
    source, target = _pair(runtime)
    with runtime.sessions.begin() as session:
        session.add(_relation(runtime.workspace_id, int(source), int(target) if self_edge else 1001))
        if not self_edge:
            session.add(_relation(runtime.workspace_id, int(target), 1001))
    before = _current(runtime, source, target)
    with pytest.raises(ValueError) as error:
        _merge(_service(runtime), runtime, source, target)
    assert error.value.code in {"relation_endpoint_incompatible", "relation_context_changed"}
    assert _current(runtime, source, target) == before


def test_explicit_patch_unifies_multiple_adopted_notes_while_merging(context_runtime):
    runtime = context_runtime
    source, target = _pair(runtime)
    choices = [_choice(runtime, source, target),
        _issue(runtime, source, key="source:first", fields={"note": "来源甲"}),
        _issue(runtime, source, key="source:second", fields={"note": "来源乙"})]
    service = _service(runtime)
    view = _preview(service, runtime, source, intent="resolve_issue", patch={"note": "明确统一"}, issue_resolutions=choices)
    assert view['items'][0]['display']['note'] == "明确统一"
    assert view["summary"]["resolve_issue"] == 3


def test_each_adopted_finance_is_checked_before_explicit_unification(context_runtime):
    runtime = context_runtime
    source, target = _pair(runtime)
    choices = [_choice(runtime, source, target),
        _issue(runtime, source, key="finance:first", fields={"amount": "-99"}),
        _issue(runtime, source, key="finance:second", fields={"amount": "-88"})]
    with pytest.raises(ValueError) as error:
        _preview(_service(runtime), runtime, source, intent="resolve_issue", patch={"amount": "-12.5"}, issue_resolutions=choices)
    assert error.value.code == "fixed_field_conflict" and "amount" in error.value.fields


def test_adopted_new_source_parts_remain_pending_when_target_parts_are_unknown(context_runtime):
    runtime = context_runtime
    source, target = _pair(runtime, draft_target=True)
    with runtime.sessions.begin() as session:
        for identifier in (source, target):
            session.get(CashTransactionModel, int(identifier)).account_id = None
            for part in session.scalars(select(CashTransactionComponentModel).where(CashTransactionComponentModel.cash_transaction_id == int(identifier))):
                part.account_id, part.amount, part.currency = None, None, None
    adopted_parts = [{"ordinal": 0, "account_id": "101", "amount": "-12.5", "currency": "CNY"}]
    choice = _issue(runtime, source, fields={"components": adopted_parts})
    service = _service(runtime)
    view = _preview(service, runtime, source, intent="resolve_issue", issue_resolutions=[_choice(runtime, source, target), choice])
    operation, = _plan_data(service, view)["operations"]
    assert operation["merge_draft"]["pending_fields"]["components"] == adopted_parts
    assert choice["issue_id"] in operation["resolved_issue_ids"]


@pytest.mark.parametrize("human_source,explicit", [(False, False), (True, False), (True, True)])
def test_category_merge_protects_target_group_human_decisions(context_runtime, human_source, explicit):
    from ft.application.cash_categories import CashCategoryService
    runtime = context_runtime
    source, target = _pair(runtime, mirror_target=True)
    category = CashCategoryService(runtime.sessions, runtime.workspace_id).create(name="合并来源分类")["id"]
    with runtime.sessions.begin() as session:
        source_row, other = session.get(CashTransactionModel, int(source)), session.get(CashTransactionModel, 1001)
        source_row.category_id, source_row.manual_overrides = category, {"category_id": True} if human_source else {}
        from ft.adapters.relational.cash_entries import CashEntryRepository
        entry_repository = CashEntryRepository(session, runtime.workspace_id)
        entry = entry_repository.current(other.id)
        entry_repository.patch(other.id, {'category_id': None}, expected_revision=entry.revision)
        if human_source:
            entry = entry_repository.current(source_row.id)
            entry_repository.patch(source_row.id, {'category_id': category}, expected_revision=entry.revision)
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    service = _service(runtime)
    if human_source and not explicit:
        with pytest.raises(ValueError) as error:
            _merge(service, runtime, source, target)
        assert error.value.code == "source_resolution_conflict" and "category_id" in error.value.fields
    else:
        view = _merge(service, runtime, source, target, patch={"category_id": category} if explicit else {})
        assert view['items'][0]['display']['category_id'] == (category if explicit else None)
        operations = _plan_data(service, view)["operations"]
        assert len(operations) == 1
        assert ('category_id' in operations[0].get('entry_edit', {}).get('patch', {})) == explicit


def test_existing_confirmed_group_keeps_legally_deactivated_member(context_runtime):
    runtime = context_runtime
    source, target = _pair(runtime, mirror_target=True)
    with runtime.sessions.begin() as session:
        current = session.scalar(select(TransactionRelationModel).where(TransactionRelationModel.primary_transaction_id == int(target)))
        current.decision_group_id = "synthetic:old-confirmed"
        session.add(_relation(runtime.workspace_id, 1001, 1002, state="confirmed", active=False,
            primary_component_id=1001, secondary_component_id=1002, anchor_component_id=1001,
            primary_role="expense", secondary_role="expense", decision_group_id="synthetic:old-confirmed"))
    before, service = _current(runtime, source, target), _service(runtime)
    view = _merge(service, runtime, source, target)
    operation, = _plan_data(service, view)["operations"]
    rows = [row for row in operation["merge_draft"]["relations"] if row["decision_group_id"] == "synthetic:old-confirmed"]
    assert {(row["state"], row["active"]) for row in rows} == {("confirmed", False), ("confirmed", True)}
    assert _current(runtime, source, target) == before


def test_equal_split_parts_do_not_get_arbitrary_mapping(context_runtime):
    runtime = context_runtime
    source, target = _pair(runtime)
    with runtime.sessions.begin() as session:
        first_part = None
        for identifier in (source, target):
            parent = session.get(CashTransactionModel, int(identifier))
            parent.account_id, parent.cash_granularity = None, "aggregate"
            part = session.scalar(select(CashTransactionComponentModel).where(CashTransactionComponentModel.cash_transaction_id == int(identifier)))
            part.amount = Decimal("-6.25")
            if identifier == source:
                first_part = part.id
            session.add(CashTransactionComponentModel(workspace_id=runtime.workspace_id, cash_transaction_id=int(identifier),
                ordinal=1, account_id=part.account_id, amount=Decimal("-6.25"), currency="CNY"))
        session.add(_relation(runtime.workspace_id, int(source), 1001, primary_component_id=first_part,
            anchor_component_id=first_part, primary_role="expense"))
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    before, service = _current(runtime, source, target), _service(runtime)
    view = _merge(service, runtime, source, target)
    operation, = _plan_data(service, view)["operations"]
    merge = operation["merge_draft"]
    assert all(value is None for value in merge["component_map"].values())
    assert len(merge["pending_fields"]["components"]) == 2
    relation, = merge["relations"]
    assert relation["primary_transaction_id"] == relation["anchor_transaction_id"] == int(target)
    assert relation["primary_component_id"] is relation["anchor_component_id"] is relation["primary_role"] is None
    assert _current(runtime, source, target) == before


def test_target_draft_component_removal_also_returns_its_relations_to_parent_anchor(context_runtime):
    runtime = context_runtime
    source, target = _pair(runtime, draft_target=True)
    with runtime.sessions.begin() as session:
        component = session.scalar(select(CashTransactionComponentModel).where(CashTransactionComponentModel.cash_transaction_id == int(target)))
        session.add(_relation(runtime.workspace_id, int(target), 1001, primary_component_id=component.id,
            anchor_component_id=component.id, primary_role="expense"))
    before, service = _current(runtime, source, target), _service(runtime)
    view = _merge(service, runtime, source, target, patch={"components": []})
    operation, = _plan_data(service, view)["operations"]
    relation, = operation["merge_draft"]["relations"]
    assert relation["primary_transaction_id"] == relation["anchor_transaction_id"] == int(target)
    assert relation["primary_component_id"] is relation["anchor_component_id"] is relation["primary_role"] is None
    assert _current(runtime, source, target) == before
