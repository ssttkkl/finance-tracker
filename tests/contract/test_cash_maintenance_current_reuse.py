"""同调用复用不能跨 Session、过期、删除或错误身份授信。"""
from datetime import datetime, timezone

import pytest
from sqlalchemy import delete, select, update

from ft.adapters.relational.models import CashSourceBindingModel, CashTransactionComponentModel, CashTransactionModel, TransactionRelationModel
from ft.application.cash_record_operations import CashRecordMaintenanceError, CashRecordOperationPlanner
from tests.contract.test_cash_import_commit import configured
from tests.contract.test_parsed_cash_import_context import context_runtime


@pytest.mark.parametrize("condition", ["current", "expired", "rolled_back", "other_record"])
def test_supplied_record_is_only_reused_while_current_in_this_session(context_runtime, condition):
    runtime = context_runtime
    with runtime.sessions() as session:
        cached = session.get(CashTransactionModel, 1002 if condition == "other_record" else 1001)
        if condition == "expired":
            session.expire(cached)
        if condition == "rolled_back":
            cached.note = "不应复用的未提交内容"
            session.rollback()
        session.execute(update(CashTransactionModel).where(CashTransactionModel.id == 1001).values(
            note="当前已读内容"), execution_options={"synchronize_session": False})
        if condition == "current":
            session.refresh(cached)
        found = CashRecordOperationPlanner(session, runtime.workspace_id).record(1001, current_record=cached)
        assert found.id == 1001 and found.note == "当前已读内容"


def test_object_from_another_session_cannot_override_the_current_record(context_runtime):
    runtime = context_runtime
    with runtime.sessions() as first:
        old = first.get(CashTransactionModel, 1001)
        old_note = old.note
        with runtime.sessions.begin() as second:
            second.execute(update(CashTransactionModel).where(CashTransactionModel.id == 1001).values(note="其他会话的新值"))
        with runtime.sessions() as current:
            actual = CashRecordOperationPlanner(current, runtime.workspace_id).record(1001, current_record=old)
            assert actual.note == "其他会话的新值" and old.note == old_note


@pytest.mark.parametrize("deleted", ["soft", "pending"])
def test_deleted_supplied_object_is_not_reused(context_runtime, deleted):
    runtime = context_runtime
    with runtime.sessions() as session:
        row = session.get(CashTransactionModel, 1001)
        if deleted == "soft":
            row.deleted_at = datetime.now(timezone.utc)
        else:
            session.delete(row)
        with pytest.raises(CashRecordMaintenanceError, match="record_not_found"):
            CashRecordOperationPlanner(session, runtime.workspace_id).record(1001, current_record=row)


def test_supplied_record_does_not_bypass_workspace_scope(context_runtime):
    runtime = context_runtime
    with runtime.sessions() as session:
        row = session.get(CashTransactionModel, 1001)
        with pytest.raises(CashRecordMaintenanceError, match="record_not_found"):
            CashRecordOperationPlanner(session, "foreign-workspace").record(1001, current_record=row)


@pytest.mark.parametrize("root_bound", [False, True])
def test_reused_source_snapshot_matches_fresh_scope_for_additional_closure_members(context_runtime, root_bound):
    from ft.adapters.relational.cash_sources import CashSourceRepository
    from ft.application.cash_import_business_plan import CashImportBusinessPlanBuilder
    from ft.application.cash_relation_context import CashRelationGraphService
    from ft.domain.cash_source_matching import SourceOccurrence
    from tests.contract.test_cash_import_business_plan import _existing_mirror
    runtime = context_runtime
    with runtime.sessions.begin() as session:
        _existing_mirror(session, runtime)
        if not root_bound:
            session.execute(delete(CashSourceBindingModel).where(CashSourceBindingModel.transaction_id == 1003))
        CashSourceRepository(session, runtime.workspace_id).save_occurrence(1001, SourceOccurrence(
            source_type="icbc_debit", source_account_key="synthetic-bank-scope", file_digest="b" * 64,
            parser_version="fixture-v1", table_key="bank", position=1, fields={}))
    with runtime.sessions() as session:
        builder = CashImportBusinessPlanBuilder(runtime.workspace_id)
        context = CashRelationGraphService(session, runtime.workspace_id).load({1003})
        assert context.transaction_ids == frozenset({1001, 1003})
        target = CashRecordOperationPlanner(session, runtime.workspace_id).record(1003)
        scopes = builder._source_scopes(session, {1003})
        fresh = builder._snapshot(session, context, [], {}, [])
        reused = builder._snapshot(session, context, [], {}, [], current_records={"1003": target},
            source_scope_snapshot=(frozenset({1003}), scopes))
        assert reused.values == fresh.values and reused.facts == fresh.facts and reused.components == fresh.components
        assert reused.values["1001"]["source_scope_key"] == "synthetic-bank-scope"
        assert bool(scopes) is root_bound


def test_posted_type_change_keeps_component_identity_and_still_removes_invalid_relation(context_runtime):
    from ft.application.cash_projections import CashProjectionService
    from tests.contract.test_cash_import_business_plan import _existing_mirror
    from tests.contract.test_cash_mutation_business_plan import _preview
    from tests.contract.test_cash_mutation_commit import _service, _commit
    from tests.contract.test_cash_record_maintenance import _service as records
    runtime = context_runtime
    with runtime.sessions.begin() as session:
        _, relation_id = _existing_mirror(session, runtime)
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    before = records(runtime).read("1003")["fields"]["components"]
    service = _service(runtime)
    receipt = _commit(service, _preview(service, runtime, "1003", patch={
        "record_type": "transfer_out", "record_subtype": "ordinary_transfer"}))
    assert receipt["summary"]["relations_removed"] == 1
    assert records(runtime).read("1003")["fields"]["components"] == before
    with runtime.sessions() as session:
        assert not session.get(TransactionRelationModel, relation_id).active


def test_component_change_without_parent_revision_still_invalidates_confirmation(context_runtime):
    from ft.adapters.relational.cash_mutations import CashMutationConflict
    from tests.contract.test_cash_mutation_business_plan import _posted, _preview
    from tests.contract.test_cash_mutation_commit import _service, _commit
    runtime, identifier = context_runtime, _posted(context_runtime)
    service = _service(runtime)
    view = _preview(service, runtime, identifier, patch={"note": "不应保存的旧预览"})
    with runtime.sessions.begin() as session:
        parent = session.get(CashTransactionModel, int(identifier))
        revision, note = parent.row_revision, parent.note
        session.execute(update(CashTransactionComponentModel).where(
            CashTransactionComponentModel.cash_transaction_id == int(identifier)).values(ordinal=1))
        assert parent.row_revision == revision
    with pytest.raises(CashMutationConflict, match="record_changed"):
        _commit(service, view)
    with runtime.sessions() as session:
        parent = session.get(CashTransactionModel, int(identifier))
        assert parent.note == note and parent.row_revision == revision
        assert session.scalar(select(CashTransactionComponentModel.ordinal).where(
            CashTransactionComponentModel.cash_transaction_id == int(identifier))) == 1
