"""无文件维护的发布边界；本模块使用合成构建结果，不代替实际业务构建器验证。"""
from datetime import datetime, timedelta, timezone
from hashlib import sha256

import pytest
from sqlalchemy import select

from ft.adapters.relational.cash_mutations import CashMutationConflict, CashMutationRepository
from ft.adapters.relational.models import CashTransactionModel, UserModel, WorkspaceMembershipModel
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.application.parsed_import_staging import InMemoryParsedImportStore
from tests.contract.test_parsed_cash_import_context import USER, context_runtime


def _build(*, session, preview_input, previous_plan, current_record=None):
    identifier = preview_input["transaction_id"]
    items = [{"preview_item_id": f"item_{index}", "confirmation_status": "pending" if index % 2 else "confirmed",
        "anticipated_state": "posted", "display": {"amount": "0"}, "statistics_eligible": True,
        "member_refs": [{"transaction_id": identifier}], "attention_reasons": [], "detail_available": True}
        for index in range(121)]
    return {"items": items, "summary": {"update_posted": 1}, "can_commit": True,
        "operations": [{"operation_ref": "op_1", "transaction_id": identifier,
            "private_execution": "server-only", "values": {"note": preview_input["patch"].get("note", "冻结备注"), "components": []}}],
        "item_details": {item["preview_item_id"]: {"members": [{"transaction_id": identifier,
            "fields_ref": {"operation_ref": "op_1"}, "row_revision": preview_input["expected_revision"]}],
            "field_diffs": [], "issues": [], "relation_choices": [], "relation_effects": []} for item in items}}


def _service(runtime, *, builder=_build, store=None, user_id=USER):
    from ft.application.cash_mutation_plans import CashMutationPlanService
    return CashMutationPlanService(RelationalUnitOfWork(runtime.sessions, runtime.workspace_id),
        store or InMemoryParsedImportStore(), user_id=user_id, builder=builder)


def _input(runtime, **changes):
    with runtime.sessions() as session:
        revision = session.get(CashTransactionModel, 1001).row_revision
    return {"intent": "edit_record", "transaction_id": "1001", "expected_revision": revision,
        "patch": {"note": "冻结备注"}, "relation_choices": [], **changes}


def _again(runtime, plan, **changes):
    return _input(runtime, edit_context_id=plan["edit_context_id"], previous_plan_id=plan["plan_id"],
        expected_plan_revision=plan["plan_revision"], **changes)


def test_first_publication_has_no_file_keeps_current_ledger_and_only_returns_public_fields(context_runtime):
    service = _service(context_runtime)
    with context_runtime.sessions() as session:
        before = session.get(CashTransactionModel, 1001).note
    plan = service.preview(_input(context_runtime))
    assert plan["plan_revision"] == 1 and plan["plan_id"].startswith("mp_")
    assert plan["item_counts"] == {"confirmed": 61, "pending": 60} and len(plan["items"]) == 50
    assert not {"operations", "preview_input", "session_id", "session_revision", "files"} & set(plan)
    current = service._staging.get(plan["edit_context_id"], workspace_id=context_runtime.workspace_id, user_id=USER)
    assert current.workflow_kind == "mutation" and current.files == ()
    assert current.context_data == {"transaction_id": "1001", "version": 1}
    detail = service.item(plan["plan_id"], "item_0")
    assert detail["members"][0]["fields"]["note"] == "冻结备注"
    assert "server-only" not in str(plan) + str(detail)
    with context_runtime.sessions() as session:
        assert session.get(CashTransactionModel, 1001).note == before


def test_repreview_is_cas_and_old_plan_is_invalid_but_failed_input_keeps_current(context_runtime):
    from ft.application.cash_mutation_plans import CashMutationPlanError
    service = _service(context_runtime)
    old = service.preview(_input(context_runtime))
    for changes in ({"expected_plan_revision": 0}, {"previous_plan_id": None}, {"transaction_id": "1002"}):
        request = {**_again(context_runtime, old), **changes}
        with pytest.raises(CashMutationPlanError):
            service.preview(request)
        assert service.read_plan(old["plan_id"]) == old
    new = service.preview(_again(context_runtime, old, patch={"note": "新的冻结备注"}))
    assert new["plan_revision"] == 2 and new["plan_id"] != old["plan_id"]
    assert new["expires_at"] == old["expires_at"]
    assert service.current_plan(old["edit_context_id"]) == new
    with pytest.raises(CashMutationPlanError, match="plan_changed"):
        service.read_plan(old["plan_id"])
    with pytest.raises(CashMutationPlanError):
        service.preview(_again(context_runtime, old))
    detail = service.item(new["plan_id"], "item_0")
    assert detail["members"][0]["fields"]["note"] == "新的冻结备注"


def test_pages_and_details_remain_frozen_and_plan_filter_bound(context_runtime):
    from ft.application.cash_import_plans import CashImportPlanError
    service = _service(context_runtime)
    plan = service.preview(_input(context_runtime))
    with context_runtime.sessions.begin() as session:
        session.get(CashTransactionModel, 1001).note = "后来的当前备注"
    ids, cursor = [], None
    while True:
        page = service.items(plan["plan_id"], cursor=cursor, limit=17, confirmation_status="pending")
        ids.extend(item["preview_item_id"] for item in page["items"])
        cursor = page["next_cursor"]
        if cursor is None:
            break
    assert len(ids) == len(set(ids)) == 60
    assert service.item(plan["plan_id"], "item_0")["members"][0]["fields"]["note"] == "冻结备注"
    cursor = service.items(plan["plan_id"], limit=1)["next_cursor"]
    with pytest.raises(CashImportPlanError, match="plan_cursor_invalid"):
        service.items(plan["plan_id"], cursor=cursor, confirmation_status="pending")
    plan["summary"].clear()
    assert service.read_plan(plan["plan_id"])["summary"] == {"update_posted": 1}


@pytest.mark.parametrize("changes", [{"expected_revision": True}, {"transaction_id": "1" * 4301},
    {"intent": "unknown"}, {"operations": []}, {"patch": []},
    {"relation_choices": [{"suggestion_id": "r1", "action": "arbitrary"}]}])
def test_invalid_maintenance_input_never_invokes_builder_or_creates_context(context_runtime, changes):
    service = _service(context_runtime, builder=lambda **_kwargs: pytest.fail("invalid input reached builder"))
    with pytest.raises(ValueError):
        service.preview(_input(context_runtime, **changes))
    assert not service._staging._entries


def test_stale_parent_and_cross_workspace_record_cannot_create_plan(context_runtime):
    service = _service(context_runtime, builder=lambda **_kwargs: pytest.fail("stale or foreign record reached builder"))
    for changes in ({"expected_revision": 999}, {"transaction_id": "2001"}):
        with pytest.raises(ValueError):
            service.preview(_input(context_runtime, **changes))
    assert not service._staging._entries


def test_failed_builder_or_plan_budget_keeps_previous_immutable_state(context_runtime):
    from ft.application.parsed_import_staging import ParsedImportDataError
    service = _service(context_runtime)
    old = service.preview(_input(context_runtime))
    def broken(**_kwargs):
        raise RuntimeError("synthetic private builder failure")
    service._builder = broken
    with pytest.raises(RuntimeError):
        service.preview(_again(context_runtime, old))
    assert service.read_plan(old["plan_id"]) == old
    service._builder = _build
    service._staging._max_bytes = len(service._staging._entries[old["edit_context_id"]]) + 128
    with pytest.raises(ParsedImportDataError):
        service.preview(_again(context_runtime, old, patch={"note": "超预算正文" * 5000}))
    assert service.read_plan(old["plan_id"]) == old


def test_expiry_cancel_and_live_membership_gate_the_current_plan(context_runtime):
    from ft.application.cash_mutation_plans import CashMutationPlanError
    from ft.application.access import PermissionDenied
    now = [datetime(2026, 10, 21, tzinfo=timezone.utc)]
    service = _service(context_runtime, store=InMemoryParsedImportStore(ttl_seconds=60, clock=lambda: now[0]))
    plan = service.preview(_input(context_runtime))
    with context_runtime.sessions.begin() as session:
        member = session.scalar(select(WorkspaceMembershipModel).where(WorkspaceMembershipModel.user_id == USER))
        member.role = "viewer"
    assert service.read_plan(plan["plan_id"]) == plan
    with pytest.raises(PermissionDenied):
        service.preview(_again(context_runtime, plan))
    with context_runtime.sessions.begin() as session:
        session.scalar(select(WorkspaceMembershipModel).where(WorkspaceMembershipModel.user_id == USER)).role = "editor"
    now[0] += timedelta(seconds=30)
    new = service.preview(_again(context_runtime, plan))
    assert new["expires_at"] == plan["expires_at"]
    service.cancel(new["edit_context_id"], expected_plan_revision=2)
    with pytest.raises(CashMutationPlanError, match="plan_changed"):
        service.read_plan(new["plan_id"])
    another = service.preview(_input(context_runtime))
    now[0] += timedelta(seconds=60)
    with pytest.raises(CashMutationPlanError, match="plan_expired"):
        service.read_plan(another["plan_id"])


def test_consumed_context_is_checked_before_its_cleared_or_missing_body(context_runtime, monkeypatch):
    service = _service(context_runtime)
    plan = service.preview(_input(context_runtime))
    with context_runtime.sessions.begin() as session:
        receipt = CashMutationRepository(session, context_runtime.workspace_id).create(user_id=USER,
            idempotency_key="synthetic-mutation-consumer", request_digest="a" * 64, operation_kind="edit_record",
            plan_id=plan["plan_id"], edit_context_digest=sha256(plan["edit_context_id"].encode()).hexdigest(),
            result_summary={"status": "completed"}, results=[])
    monkeypatch.setattr(service._staging, "get", lambda *_args, **_kwargs: pytest.fail("consumed body read"))
    for action in (lambda: service.read_plan(plan["plan_id"]), lambda: service.preview(_again(context_runtime, plan))):
        with pytest.raises(CashMutationConflict) as error:
            action()
        assert error.value.code == "edit_context_committed" and error.value.receipt_id == receipt["receipt_id"]


def test_import_cannot_read_or_confirm_mutation_context_and_commit_loader_checks_plan_revision(context_runtime):
    from ft.application.cash_import_context import CashImportContextService
    from ft.application.cash_import_commit import CashImportCommandService
    from ft.application.cash_import_plans import CashImportPlanError
    from ft.application.cash_import_staging import ImportSessionNotFound
    from ft.application.cash_mutation_plans import CashMutationPlanError
    runtime = context_runtime
    service = _service(runtime)
    plan = service.preview(_input(runtime))
    uow = RelationalUnitOfWork(runtime.sessions, runtime.workspace_id)
    with pytest.raises(ImportSessionNotFound):
        CashImportContextService(uow, service._staging, user_id=USER).get(plan["edit_context_id"])
    forged = "ip_" + plan["plan_id"][3:]
    with pytest.raises(CashImportPlanError, match="plan_expired"):
        CashImportCommandService(uow, service._staging, user_id=USER).commit(forged,
            expected_session_revision=2, idempotency_key="synthetic-wrong-workflow")
    with uow:
        current = service.current_for_commit(uow, plan["plan_id"], expected_plan_revision=1)
        assert current.workflow_kind == "mutation" and current.plan["plan_id"] == plan["plan_id"]
        with pytest.raises(CashMutationPlanError, match="plan_changed"):
            service.current_for_commit(uow, plan["plan_id"], expected_plan_revision=True)


@pytest.mark.parametrize("summary", [{"update_posted": 1, "private_error": "synthetic SELECT/private body"},
    {"update_posted": True}, {"update_posted": -1}, {"update_posted": "1"}])
def test_private_or_invalid_summary_never_publishes_or_replaces_current_plan(context_runtime, summary):
    from ft.application.cash_mutation_plans import CashMutationPlanError
    def malformed(**kwargs):
        return {**_build(**kwargs), "summary": summary}
    service = _service(context_runtime, builder=malformed)
    with pytest.raises(CashMutationPlanError, match="plan_build_failed"):
        service.preview(_input(context_runtime))
    assert not service._staging._entries
    service._builder = _build
    old = service.preview(_input(context_runtime))
    service._builder = malformed
    with pytest.raises(CashMutationPlanError, match="plan_build_failed"):
        service.preview(_again(context_runtime, old))
    assert service.read_plan(old["plan_id"]) == old


def test_another_member_cannot_read_or_cancel_owned_edit_context(context_runtime):
    from ft.application.cash_import_staging import ImportSessionForbidden
    runtime = context_runtime
    service = _service(runtime)
    plan = service.preview(_input(runtime))
    another = "00000000-0000-4000-8000-000000000002"
    with runtime.sessions.begin() as session:
        session.add(UserModel(id=another, email="other-mutation@example.test", password_hash="synthetic"))
        session.flush()
        session.add(WorkspaceMembershipModel(workspace_id=runtime.workspace_id, user_id=another, role="editor"))
    other = _service(runtime, store=service._staging, user_id=another)
    for action in (lambda: other.read_plan(plan["plan_id"]), lambda: other.current_plan(plan["edit_context_id"]),
        lambda: other.cancel(plan["edit_context_id"], expected_plan_revision=1)):
        with pytest.raises(ImportSessionForbidden):
            action()
    assert service.read_plan(plan["plan_id"]) == plan


def test_preview_rolls_back_even_accidentally_flushed_business_changes(context_runtime):
    runtime = context_runtime
    with runtime.sessions() as session:
        row = session.get(CashTransactionModel, 1001)
        before = row.note, row.row_revision
    def attempted_write(**kwargs):
        session = kwargs["session"]
        session.get(CashTransactionModel, 1001).note = "不应提交的构建器改动"
        session.flush()
        return _build(**kwargs)
    service = _service(runtime, builder=attempted_write)
    service.preview(_input(runtime))
    with runtime.sessions() as session:
        row = session.get(CashTransactionModel, 1001)
        assert (row.note, row.row_revision) == before
