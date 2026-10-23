"""维护提交前的局部依赖、消费隔离及结果未知恢复。"""
from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import select

from ft.adapters.relational.cash_mutations import CashMutationConflict
from ft.adapters.relational.cash_sources import CashSourceRepository
from ft.adapters.relational.models import (AccountModel, CashTransactionIssueModel, CashTransactionModel,
    TransactionRelationModel, UserModel, WorkspaceMembershipModel)
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.application.cash_mutation_plans import CashMutationPlanError
from ft.application.cash_projections import CashProjectionService
from ft.application.parsed_import_staging import InMemoryParsedImportStore
from tests.contract.test_cash_import_commit import configured, _plan, _commit as _import_commit
from tests.contract.test_cash_import_operations import _file
from tests.contract.test_cash_mutation_business_plan import _posted, _preview
from tests.contract.test_cash_mutation_commit import _category_plan, _commit, _service
from tests.contract.test_cash_record_maintenance import _draft, _complete, _service as _records
from tests.contract.test_parsed_cash_import_context import USER, context_runtime


@pytest.mark.parametrize("change", ["parent", "issue", "relation", "members"])
def test_current_dependency_change_rejects_before_writes_without_consuming_context(context_runtime, change):
    runtime = context_runtime
    if change in {"relation", "members"}:
        service, view, relation_id, _category = _category_plan(runtime)
        identifier = "1003"
    else:
        identifier = _posted(runtime)
        with runtime.sessions.begin() as session:
            issue = CashSourceRepository(session, runtime.workspace_id).save_issue(identifier, "source:pending", code="source_update_pending",
                pending_fields={"counterparty": "旧待采用"})
            issue_id = issue.id
        service = _service(runtime)
        view = _preview(service, runtime, identifier, patch={"note": "过时决定"})
    before = _records(runtime).read(identifier)
    with runtime.sessions.begin() as session:
        if change == "parent":
            session.get(CashTransactionModel, int(identifier)).note = "后来保存的备注"
        elif change == "issue":
            CashSourceRepository(session, runtime.workspace_id).save_issue(identifier, "source:pending", code="source_update_pending",
                pending_fields={"counterparty": "新待采用"})
        elif change == "relation":
            session.get(TransactionRelationModel, relation_id).decision_reason = "后来修改的决定"
        else:
            session.get(TransactionRelationModel, relation_id).active = False
    if change == "members":
        CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    current = _records(runtime).read(identifier)
    if change != "parent":
        assert current["row_revision"] == before["row_revision"]
    with pytest.raises(CashMutationConflict) as captured:
        _commit(service, view)
    assert captured.value.code in {"record_changed", "relation_context_changed"}
    assert _records(runtime).read(identifier) == current
    assert service.read_plan(view["plan_id"]) == view
    if change == "issue":
        with runtime.sessions() as session:
            assert session.get(CashTransactionIssueModel, issue_id).pending_fields == {"counterparty": "新待采用"}


def test_unrelated_import_and_account_rename_do_not_invalidate_formal_note_edit(context_runtime):
    runtime, identifier = context_runtime, _posted(context_runtime)
    service = _service(runtime)
    view = _preview(service, runtime, identifier, patch={"note": "仍可保存"})
    importer, other = _plan(runtime, (_file(letter="b", provider="unrelated", amount="-7"),))
    _import_commit(importer, other, "synthetic-unrelated-import")
    with runtime.sessions.begin() as session:
        session.get(AccountModel, 101).name = "后来重命名的账户"
    receipt = _commit(service, view)
    assert receipt["summary"]["updated_posted"] == 1 and _records(runtime).read(identifier)["fields"]["note"] == "仍可保存"


@pytest.mark.parametrize("draft", [False, True])
def test_account_disabled_after_preview_blocks_only_draft_finalization(context_runtime, draft):
    runtime = context_runtime
    identifier = _draft(runtime) if draft else _posted(runtime)
    before = _records(runtime).read(identifier)
    service = _service(runtime)
    view = _preview(service, runtime, identifier, intent="finalize_draft" if draft else "edit_record",
        patch=_complete(before) if draft else {"note": "已用账户仍可编辑"})
    with runtime.sessions.begin() as session:
        session.get(AccountModel, 101).active = False
    if draft:
        with pytest.raises(ValueError) as captured:
            _commit(service, view)
        assert getattr(captured.value, "code", None) == "account_unavailable"
        assert _records(runtime).read(identifier) == before and service.read_plan(view["plan_id"]) == view
    else:
        assert _commit(service, view)["summary"]["updated_posted"] == 1


def test_permanent_context_consumption_and_idempotency_are_checked_before_body(context_runtime, monkeypatch):
    runtime, identifier = context_runtime, _posted(context_runtime)
    service = _service(runtime)
    view = _preview(service, runtime, identifier, patch={"note": "只保存一次"})
    receipt = _commit(service, view)
    next_view = _preview(service, runtime, identifier, patch={"note": "新计划"})
    def forbidden(*args, **kwargs):
        pytest.fail("permanent consumption must precede staging reads")
    monkeypatch.setattr(service._staging, "get", forbidden)
    assert _commit(service, view) == receipt
    with pytest.raises(CashMutationConflict, match="edit_context_committed"):
        _commit(service, view, "synthetic-other-key")
    with pytest.raises(CashMutationConflict, match="idempotency_conflict"):
        _commit(service, next_view)
    with pytest.raises(CashMutationConflict, match="idempotency_conflict"):
        service.resolve(next_view["plan_id"], idempotency_key="synthetic-mutation-confirm")
    assert service.resolve(view["plan_id"], idempotency_key="synthetic-other-key")["receipt"] == receipt


def test_cleanup_failure_after_commit_does_not_hide_known_success(context_runtime, monkeypatch):
    runtime, identifier = context_runtime, _posted(context_runtime)
    service = _service(runtime)
    view = _preview(service, runtime, identifier, patch={"note": "已保存"})
    def fail(*args, **kwargs):
        raise RuntimeError("synthetic temporary cleanup failure")
    monkeypatch.setattr(service._staging, "cancel", fail)
    receipt = _commit(service, view)
    assert view["edit_context_id"] in service._staging._entries
    with pytest.raises(CashMutationConflict, match="edit_context_committed"):
        service.read_plan(view["plan_id"])
    assert _commit(service, view) == receipt
    assert service.resolve(view["plan_id"], idempotency_key="synthetic-mutation-confirm")["receipt"] == receipt


@pytest.mark.parametrize("ending", ["cancel", "expire", "replace"])
def test_closed_or_replaced_plan_cannot_commit_and_resolves_authoritatively(context_runtime, ending):
    runtime, identifier = context_runtime, _posted(context_runtime)
    now = [datetime(2026, 10, 22, tzinfo=timezone.utc)]
    service = _service(runtime, store=InMemoryParsedImportStore(ttl_seconds=60, clock=lambda: now[0]))
    before = _records(runtime).read(identifier)
    view = _preview(service, runtime, identifier, patch={"note": "旧决定"})
    assert service.resolve(view["plan_id"], idempotency_key="synthetic-mutation-confirm") == {"status": "retry_original"}
    if ending == "cancel":
        service.cancel(view["edit_context_id"], expected_plan_revision=view["plan_revision"])
    elif ending == "expire":
        now[0] += timedelta(seconds=60)
    else:
        _preview(service, runtime, identifier, patch={"note": "新决定"}, edit_context_id=view["edit_context_id"],
            previous_plan_id=view["plan_id"], expected_plan_revision=view["plan_revision"])
    with pytest.raises(CashMutationPlanError):
        _commit(service, view)
    assert service.resolve(view["plan_id"], idempotency_key="synthetic-mutation-confirm") == {"status": "not_committed"}
    assert _records(runtime).read(identifier) == before


def test_resolve_returns_pending_only_for_the_independent_workspace_writer(context_runtime):
    from sqlalchemy import text
    from ft.adapters.relational.workspace_lock import lock_workspace
    runtime, identifier = context_runtime, _posted(context_runtime)
    service = _service(runtime)
    view = _preview(service, runtime, identifier, patch={"note": "稍后确认"})
    def checker():
        session = runtime.sessions()
        if session.bind.dialect.name == "postgresql":
            session.execute(text("SET LOCAL lock_timeout='0'"))
            session.execute(text("SET LOCAL statement_timeout='2s'"))
        return session
    service._uow = RelationalUnitOfWork(checker, runtime.workspace_id)
    with runtime.sessions() as writer:
        lock_workspace(writer, runtime.workspace_id)
        assert service.resolve(view["plan_id"], idempotency_key="synthetic-mutation-confirm") == {"status": "pending"}
        writer.rollback()
    assert service.resolve(view["plan_id"], idempotency_key="synthetic-mutation-confirm") == {"status": "retry_original"}
    receipt = _commit(service, view)
    assert service.resolve(view["plan_id"], idempotency_key="synthetic-mutation-confirm")["receipt"] == receipt


@pytest.mark.parametrize("code", ["storage.connect", "storage.readonly", "storage.schema"])
def test_resolve_propagates_non_lock_storage_errors(context_runtime, monkeypatch, code):
    from ft.adapters.relational.runtime import StorageError
    runtime, identifier = context_runtime, _posted(context_runtime)
    service = _service(runtime)
    view = _preview(service, runtime, identifier)
    def fail(self):
        raise StorageError(code)
    monkeypatch.setattr(RelationalUnitOfWork, "__enter__", fail)
    with pytest.raises(StorageError) as captured:
        service.resolve(view["plan_id"], idempotency_key="synthetic-storage-error")
    assert captured.value.code == code


def test_commit_and_replay_recheck_current_editor_and_private_owner(context_runtime):
    from ft.application.access import PermissionDenied
    from ft.application.cash_import_errors import ImportSessionForbidden
    runtime, identifier = context_runtime, _posted(context_runtime)
    service = _service(runtime)
    view = _preview(service, runtime, identifier, patch={"note": "私有维护"})
    other = "00000000-0000-4000-8000-000000000002"
    with runtime.sessions.begin() as session:
        session.add(UserModel(id=other, email="other-mutation@example.test", password_hash="synthetic"))
        session.flush()
        session.add(WorkspaceMembershipModel(workspace_id=runtime.workspace_id, user_id=other, role="editor"))
        session.scalar(select(WorkspaceMembershipModel).where(WorkspaceMembershipModel.user_id == USER)).role = "viewer"
    with pytest.raises(PermissionDenied):
        _commit(service, view)
    outsider = _service(runtime, store=service._staging, user_id=other)
    with pytest.raises(ImportSessionForbidden):
        _commit(outsider, view)
    with runtime.sessions.begin() as session:
        session.scalar(select(WorkspaceMembershipModel).where(WorkspaceMembershipModel.user_id == USER)).role = "editor"
    receipt = _commit(service, view)
    with pytest.raises(CashMutationPlanError, match="receipt_not_found"):
        outsider.receipt(receipt["receipt_id"])
    with runtime.sessions.begin() as session:
        session.scalar(select(WorkspaceMembershipModel).where(WorkspaceMembershipModel.user_id == USER)).role = "viewer"
    with pytest.raises(PermissionDenied):
        _commit(service, view)
