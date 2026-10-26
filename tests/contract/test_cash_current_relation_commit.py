"""维护既有配对保留关系身份，完整组原子更新并发布当前闭包。"""
import pytest
from sqlalchemy import func, select

from ft.adapters.relational.cash_mutations import CashMutationConflict
from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.models import (CashMutationReceiptModel, CashRelationCandidateModel, CashTransactionComponentModel,
    CashTransactionModel, TransactionRelationModel)
from ft.application.cash_projections import CashProjectionService
from tests.contract.test_cash_current_relation_plan import _choose, _facts, _groups, _open, _rows, _start
from tests.contract.test_cash_import_commit import configured
from tests.contract.test_cash_mutation_business_plan import _preview
from tests.contract.test_cash_mutation_commit import _commit, _service, _snapshot
from tests.contract.test_cash_record_maintenance import _service as _records
from tests.contract.test_parsed_cash_import_context import context_runtime


def _selection(service, runtime, *, action="accept", kind="payment_mirror", candidate=False, patch=None):
    first = _preview(service, runtime, "1101", intent="maintain_relations", patch=patch)
    group, = [group for group in _groups(service, first) if group["kind"] == kind]
    choice = {"suggestion_id": group["suggestion_id"], "action": action}
    if candidate:
        choice["candidate_id"] = group["candidates"][0]["candidate_id"]
    return _choose(service, runtime, first, choice, patch=patch)


@pytest.mark.parametrize("state", ["confirmed", "draft", "ignored"])
def test_confirming_an_unchanged_current_plan_preserves_all_decisions_and_proofs(context_runtime, state):
    runtime = context_runtime
    service, _ = _start(runtime, state=state)
    before, balances = _rows(runtime), _snapshot(runtime)
    parent = _records(runtime).read("1101")["row_revision"]
    plan = _preview(service, runtime, "1101", intent="maintain_relations")
    receipt = _commit(service, plan)
    assert receipt["committed"] and receipt["status"] == "no_changes"
    assert _rows(runtime) == before and _snapshot(runtime) == balances
    assert _records(runtime).read("1101")["row_revision"] == parent
    assert all(receipt["summary"][key] == 0 for key in (
        "relations_confirmed", "relations_deferred", "relations_ignored", "relations_removed", "relations_failed"))
    service._staging._entries.clear()
    assert _commit(service, plan) == receipt


def test_joint_confirmation_updates_original_ids_as_one_group_and_keeps_full_refund_visible(context_runtime):
    runtime = context_runtime
    service, mirrors = _start(runtime, joint=True)
    before, balances = _rows(runtime), _snapshot(runtime)
    plan = _selection(service, runtime)
    receipt = _commit(service, plan)
    assert receipt["summary"]["relations_confirmed"] == 1 and receipt["summary"]["relations_failed"] == 0
    after = _rows(runtime)
    assert {row["id"] for row in after} == {row["id"] for row in before}
    expected = {row["id"] for row in mirrors}
    for old, new in zip(before, after):
        if old["id"] in expected:
            assert new["state"] == "confirmed" and new["active"] and new["row_revision"] > old["row_revision"]
            assert new["decision_group_id"] == old["decision_group_id"]
            assert new["anchor_transaction_id"] == old["anchor_transaction_id"]
            assert new["anchor_component_id"] == old["anchor_component_id"]
            assert new["decision_source"] == "manual"
        else:
            assert new == old
    details = [_records(runtime).read(str(identifier)) for identifier in range(1101, 1105)]
    assert all(row["confirmation_status"] == "confirmed" for row in details)
    assert len({row["projection_ref"]["projection_id"] for row in details}) == 1
    assert _snapshot(runtime) == balances
    assert _commit(service, plan) == receipt


@pytest.mark.parametrize("state,action", [("confirmed", "remove"), ("draft", "remove"), ("draft", "defer"), ("draft", "ignore")])
def test_cleanup_of_mixed_inactive_group_never_rewrites_inactive_members(context_runtime, state, action):
    runtime = context_runtime
    service, _ = _start(runtime, state=state, inactive=True)
    before, balances = _rows(runtime), _snapshot(runtime)
    plan = _selection(service, runtime, action=action)
    receipt = _commit(service, plan)
    assert receipt["summary"]["relations_failed"] == 0
    after = _rows(runtime)
    for old, new in zip(before, after):
        if not old["active"]:
            assert old == new
        elif action == "remove":
            assert not new["active"] and new["state"] == old["state"]
        else:
            assert new["active"] and new["state"] == {"defer": "draft", "ignore": "ignored"}[action]
    assert _snapshot(runtime) == balances


def test_second_joint_member_failure_rolls_back_the_complete_relation_unit(context_runtime, monkeypatch):
    runtime = context_runtime
    service, mirrors = _start(runtime, joint=True)
    before, balances = _rows(runtime), _snapshot(runtime)
    plan = _selection(service, runtime)
    original, attempted = CurrentCashRelationRepository.replace, []
    def fail(self, relation_id, changes, *, expected_revision):
        result = original(self, relation_id, changes, expected_revision=expected_revision)
        attempted.append(relation_id)
        if len(attempted) == 2:
            raise RuntimeError("synthetic private second relation failure")
        return result
    monkeypatch.setattr(CurrentCashRelationRepository, "replace", fail)
    from tests.contract.test_cash_import_commit import _assert_atomic_failure
    _assert_atomic_failure(context_runtime, lambda: _commit(service, plan))


@pytest.mark.parametrize("failure", ["projection", "receipt"])
def test_publication_failure_rolls_back_current_relations_and_keeps_plan_retryable(context_runtime, monkeypatch, failure):
    from ft.adapters.relational.cash_mutations import CashMutationRepository
    runtime = context_runtime
    service, _ = _start(runtime, joint=True)
    before, balances = _rows(runtime), _snapshot(runtime)
    plan = _selection(service, runtime)
    with runtime.sessions() as session:
        count = session.scalar(select(func.count()).select_from(CashMutationReceiptModel))
    if failure == "projection":
        original = CashProjectionService.maintain_in_session
        def fail(*args, **kwargs):
            original(*args, **kwargs)
            raise RuntimeError("synthetic publication failure after write")
        monkeypatch.setattr(CashProjectionService, "maintain_in_session", fail)
    else:
        original = CashMutationRepository.create
        def fail(*args, **kwargs):
            original(*args, **kwargs)
            raise RuntimeError("synthetic receipt failure after write")
        monkeypatch.setattr(CashMutationRepository, "create", fail)
    with pytest.raises(RuntimeError):
        _commit(service, plan)
    assert _rows(runtime) == before and _snapshot(runtime) == balances
    with runtime.sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashMutationReceiptModel)) == count
    assert service.read_plan(plan["plan_id"])["plan_id"] == plan["plan_id"]
    monkeypatch.undo()
    assert _commit(service, plan)["summary"]["relations_confirmed"] == 1


def test_candidate_evidence_concurrency_refuses_old_commit_before_any_writes(context_runtime):
    runtime = context_runtime
    service, relation = _open(runtime)
    plan = _selection(service, runtime, candidate=True)
    parent = _records(runtime).read("1101")["row_revision"]
    with runtime.sessions.begin() as session:
        row = session.scalar(select(CashRelationCandidateModel).where(CashRelationCandidateModel.relation_id == relation))
        row.evidence = {"primary_original_expense": True}
    before = _rows(runtime)
    assert _records(runtime).read("1101")["row_revision"] == parent
    with pytest.raises(CashMutationConflict):
        _commit(service, plan)
    assert _rows(runtime) == before


def test_same_command_note_patch_can_confirm_the_prechecked_candidate_without_refreshing_proof(context_runtime):
    runtime = context_runtime
    service, relation = _open(runtime)
    plan = _selection(service, runtime, candidate=True, patch={"note": "本次明确备注"})
    receipt = _commit(service, plan)
    assert receipt["summary"]["updated_posted"] == receipt["summary"]["relations_confirmed"] == 1
    assert _records(runtime).read("1101")["entry"]["fields"]["note"] == "本次明确备注"
    with runtime.sessions() as session:
        row = session.get(TransactionRelationModel, relation)
        assert row.state == "confirmed" and row.active
        assert not list(session.scalars(select(CashRelationCandidateModel).where(CashRelationCandidateModel.relation_id == relation)))


def test_field_save_failure_blocks_the_dependent_relation_group(context_runtime, monkeypatch):
    from ft.application.cash_record_writer import CashRecordWriter
    runtime = context_runtime
    service, _ = _start(runtime, joint=True)
    plan = _selection(service, runtime, patch={"note": "不能部分保存"})
    before = _rows(runtime)
    original = CashRecordWriter.save
    def fail(*args, **kwargs):
        original(*args, **kwargs)
        raise RuntimeError("synthetic field save failure")
    monkeypatch.setattr(CashRecordWriter, "save", fail)
    from tests.contract.test_cash_import_commit import _assert_atomic_failure
    _assert_atomic_failure(context_runtime, lambda: _commit(service, plan))


def _colliding_draft_candidates(runtime):
    from decimal import Decimal
    _facts(runtime)
    with runtime.sessions.begin() as session:
        parent = session.get(CashTransactionModel, 1101)
        parent.state, parent.account_id, parent.cash_granularity = "draft", None, "aggregate"
        session.get(CashTransactionComponentModel, 6101).amount = Decimal("-40")
        session.add(CashTransactionComponentModel(id=7101, workspace_id=runtime.workspace_id, cash_transaction_id=1101,
            account_id=102, amount=Decimal("-60"), currency="CNY", ordinal=1))
        session.flush()
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        relation = repo.add({"kind": "payment_mirror", "primary_transaction_id": 1102,
            "primary_component_id": 6102, "anchor_transaction_id": 1102, "anchor_component_id": 6102})
        repo.set_candidates(relation["id"], [{"candidate_transaction_id": 1101, "candidate_component_id": part,
            "expected_row_revision": parent.row_revision, "evidence": {"primary_original_expense": part == 6101}}
            for part in (6101, 7101)], expected_revision=relation["row_revision"])
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    return _service(runtime)


@pytest.mark.parametrize("action", ["remove", "ignore"])
def test_component_removal_and_explicit_candidate_cleanup_share_one_atomic_unit(context_runtime, action):
    runtime = context_runtime
    service = _colliding_draft_candidates(runtime)
    plan = _selection(service, runtime, action=action, patch={"components": []})
    receipt = _commit(service, plan)
    assert receipt["summary"]["saved_draft"] == 1 and receipt["summary"]["skipped"] == 0
    assert receipt["summary"]["relations_" + {"remove": "removed", "ignore": "ignored"}[action]] == 1
    assert receipt["summary"]["relations_failed"] == 0
    assert _records(runtime).read("1101")["fields"]["components"] == []
    relation, = _rows(runtime)
    assert not relation["candidates"]


def test_coupled_candidate_cleanup_failure_preserves_draft_parts_and_different_evidence(context_runtime, monkeypatch):
    runtime = context_runtime
    service = _colliding_draft_candidates(runtime)
    before, fields = _rows(runtime), _records(runtime).read("1101")["fields"]
    plan = _selection(service, runtime, action="ignore", patch={"components": []})
    original = CurrentCashRelationRepository.replace
    def fail(*args, **kwargs):
        original(*args, **kwargs)
        raise RuntimeError("synthetic failure after candidate cleanup")
    monkeypatch.setattr(CurrentCashRelationRepository, "replace", fail)
    from tests.contract.test_cash_import_commit import _assert_atomic_failure
    _assert_atomic_failure(context_runtime, lambda: _commit(service, plan))


def test_defer_does_not_discard_distinct_candidate_proofs_to_allow_component_removal(context_runtime):
    runtime = context_runtime
    service = _colliding_draft_candidates(runtime)
    before, fields = _rows(runtime), _records(runtime).read("1101")["fields"]
    from tests.contract.test_cash_import_commit import _assert_atomic_failure
    _assert_atomic_failure(context_runtime, lambda: _commit(service, _selection(service, runtime, action="defer", patch={"components": []})))


def test_failed_candidate_only_record_blocks_the_related_current_group(context_runtime, monkeypatch):
    from ft.application.cash_record_writer import CashRecordWriter
    runtime = context_runtime
    service = _colliding_draft_candidates(runtime)
    before = _rows(runtime)
    plan = _selection(service, runtime, action="ignore", patch={"note": "候选侧字段失败"})
    def fail(*args, **kwargs):
        raise RuntimeError("synthetic candidate-only field failure")
    monkeypatch.setattr(CashRecordWriter, "save", fail)
    from tests.contract.test_cash_import_commit import _assert_atomic_failure
    _assert_atomic_failure(context_runtime, lambda: _commit(service, plan))


def test_imported_type_patch_rejected_without_mutating_prechecked_relation(context_runtime):
    runtime = context_runtime
    service, _ = _start(runtime)
    view = _preview(service, runtime, "1101", intent="maintain_relations")
    group, = _groups(service, view)
    choice = {"suggestion_id": group["suggestion_id"], "action": "accept"}
    before = _rows(runtime)
    with pytest.raises(ValueError):
        _choose(service, runtime, view, choice,
            patch={"record_type": "transfer_out", "record_subtype": "ordinary_transfer"})
    assert _rows(runtime) == before
    valid = _choose(service, runtime, view, choice)
    assert _commit(service, valid)["summary"]["relations_confirmed"] == 1


@pytest.mark.parametrize("change", [{"action": "remove"}, {"candidate_id": "forged-old-candidate"}])
def test_unknown_reference_cannot_authorize_another_action_or_candidate(context_runtime, change):
    runtime = context_runtime
    service, _ = _start(runtime)
    view = _preview(service, runtime, "1101", intent="maintain_relations")
    choice = {"suggestion_id": "sg_forged_old_reference", "action": "accept"}
    with pytest.raises(ValueError) as error:
        _choose(service, runtime, view, choice | change)
    assert error.value.code == "plan_input_invalid"


@pytest.mark.parametrize("reselect", [False, True])
def test_rejected_type_patch_preserves_existing_context_without_implicit_confirmation(context_runtime, reselect):
    runtime = context_runtime
    service, _ = _start(runtime)
    view = _preview(service, runtime, "1101", intent="maintain_relations")
    group, = _groups(service, view)
    choice = {"suggestion_id": group["suggestion_id"], "action": "accept"}
    with pytest.raises(ValueError):
        _choose(service, runtime, view, choice,
            patch={"record_type": "transfer_out", "record_subtype": "ordinary_transfer"})
    current = _choose(service, runtime, view, choice) if reselect else view
    receipt = _commit(service, current)
    assert receipt["summary"]["relations_confirmed"] == int(reselect)
    row, = _rows(runtime)
    assert row["state"] == ("confirmed" if reselect else "draft")
    assert _records(runtime).read("1101")["fields"]["record_type"] == "consumption"


def test_reselecting_current_candidate_rebuilds_the_old_counterpart_as_confirmed_singleton(context_runtime):
    runtime = context_runtime
    service, rows = _start(runtime)
    with runtime.sessions.begin() as session:
        CurrentCashRelationRepository(session, runtime.workspace_id).set_candidates(rows[0]["id"], [{
            "candidate_transaction_id": 1105, "candidate_component_id": 6105,
            "expected_row_revision": session.get(CashTransactionModel, 1105).row_revision, "evidence": {}}],
            expected_revision=rows[0]["row_revision"])
    plan = _selection(service, runtime, candidate=True)
    receipt = _commit(service, plan)
    assert receipt["summary"]["relations_confirmed"] == 1
    first, old, current = [_records(runtime).read(str(identifier)) for identifier in (1101, 1102, 1105)]
    assert first["projection_ref"]["projection_id"] == current["projection_ref"]["projection_id"]
    assert old["projection_ref"]["projection_id"] != first["projection_ref"]["projection_id"]
    assert old["confirmation_status"] == "confirmed"


@pytest.mark.parametrize("action", ["defer", "ignore", "remove"])
def test_parent_only_draft_current_relation_can_be_saved_without_components(context_runtime, action):
    from sqlalchemy import delete
    runtime = context_runtime
    _facts(runtime)
    with runtime.sessions.begin() as session:
        parent = session.get(CashTransactionModel, 1101)
        parent.state, parent.amount, parent.currency, parent.account_id = "draft", None, None, None
        parent.record_type, parent.record_subtype, parent.occurred_at = None, None, None
        session.execute(delete(CashTransactionComponentModel).where(CashTransactionComponentModel.cash_transaction_id == 1101))
        CurrentCashRelationRepository(session, runtime.workspace_id).add({"kind": "payment_mirror",
            "primary_transaction_id": 1101, "anchor_transaction_id": 1101})
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    service = _service(runtime)
    before = _records(runtime).read("1101")["fields"]
    receipt = _commit(service, _selection(service, runtime, action=action))
    assert receipt["summary"]["relations_failed"] == 0
    row, = _rows(runtime)
    assert row["primary_component_id"] is None and row["anchor_component_id"] is None
    assert row["active"] is (action != "remove")
    assert row["state"] == ("ignored" if action == "ignore" else "draft")
    assert _records(runtime).read("1101")["fields"] == before
