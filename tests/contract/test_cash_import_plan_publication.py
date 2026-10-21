"""不可变计划按会话修订发布，分页只读取同一个完整计划。"""
from copy import deepcopy
from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import func, select

from ft.adapters.relational.models import CashTransactionModel, UserModel, WorkspaceMembershipModel
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.application.cash_import_staging import ImportSessionForbidden
from ft.application.parsed_import_staging import InMemoryParsedImportStore
from tests.contract.test_parsed_cash_import_context import USER, _document, _upload, context_runtime, _consume


def _build(*, session, files, preview_input, previous_plan, plan_id):
    items = [{"preview_item_id": f"item_{i}", "confirmation_status": "pending" if i % 2 else "confirmed",
        "anticipated_state": "draft" if i % 2 else "posted", "display": {"amount": None if i % 2 else "-20.00"},
        "statistics_eligible": not i % 2, "member_refs": [{"row_ref": f"f:r{i}"}], "attention_reasons": [],
        "detail_available": True} for i in range(121)]
    return {"items": items, "summary": {"create_posted": 61, "create_draft": 60},
        "file_results": [], "can_commit": True,
        "processing_details": [{"row_ref": "skipped:r1", "planned_action": "skip", "code": "row_unreadable"}],
        "item_details": {item["preview_item_id"]: {"members": item["member_refs"], "field_diffs": [],
            "issues": [], "relation_choices": [], "relation_effects": []} for item in items},
        "operations": [{"operation_ref": "server_only", "private_source": "not-a-public-plan-field"}]}


def _service(runtime, *, builder=_build, store=None, user_id=USER):
    from ft.application.cash_import_plans import CashImportPlanService
    return CashImportPlanService(RelationalUnitOfWork(runtime.sessions, runtime.workspace_id),
        store or InMemoryParsedImportStore(), user_id=user_id, builder=builder, parser=lambda upload: _document())


def test_plan_publication_increments_revision_keeps_ttl_and_never_writes_ledger(context_runtime):
    service = _service(context_runtime)
    created = service.create((_upload(),))
    with context_runtime.sessions() as session:
        before = session.scalar(select(func.count()).select_from(CashTransactionModel))
    plan = service.preview(created.session_id, expected_session_revision=created.revision)
    assert plan["session_revision"] == 2 and plan["expires_at"] == created.expires_at.isoformat()
    assert len(plan["items"]) == 50 and plan["item_counts"] == {"confirmed": 61, "pending": 60}
    assert plan["pages"]["items"] and "operations" not in plan
    current = service.get(created.session_id).session
    assert current.revision == 2 and current.plan["plan_id"] == plan["plan_id"]
    assert current.files == created.files and current.preview_input == {"account_selections": [], "row_choices": [], "relation_choices": []}
    with context_runtime.sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == before


def test_mutating_return_values_or_builder_inputs_cannot_change_published_or_parsed_data(context_runtime):
    def builder(**kwargs):
        kwargs["files"][0].document.rows[0].fields["note"] = "污染输入"
        kwargs["preview_input"]["row_choices"].append({"row_ref": "fake", "action": "apply"})
        return _build(**kwargs)
    service = _service(context_runtime, builder=builder)
    created = service.create((_upload(),))
    plan = service.preview(created.session_id, expected_session_revision=1)
    plan["summary"]["create_posted"] = 0
    plan["items"][0]["display"]["amount"] = "0"
    detail = service.item(plan["plan_id"], "item_0")
    detail["members"].clear()
    restored = service.read_plan(plan["plan_id"])
    assert restored["summary"]["create_posted"] == 61 and restored["items"][0]["display"]["amount"] == "-20.00"
    assert service.item(plan["plan_id"], "item_0")["members"]
    current = service.get(created.session_id).session
    assert current.files == created.files and current.preview_input["row_choices"] == []


def test_full_repreview_replaces_plan_and_rejects_stale_revision_or_previous_id(context_runtime):
    from ft.application.cash_import_plans import CashImportPlanError
    service = _service(context_runtime)
    created = service.create((_upload(),))
    old = service.preview(created.session_id, expected_session_revision=1)
    for revision, previous in ((1, old["plan_id"]), (2, None), (2, "another")):
        with pytest.raises(CashImportPlanError, match="plan_changed"):
            service.preview(created.session_id, expected_session_revision=revision, previous_plan_id=previous)
    choices = [{"row_ref": "f:r1", "action": "keep_existing"}]
    new = service.preview(created.session_id, expected_session_revision=2, previous_plan_id=old["plan_id"], row_choices=choices)
    assert new["plan_id"] != old["plan_id"] and new["session_revision"] == 3
    assert service.get(created.session_id).session.preview_input["row_choices"] == choices
    with pytest.raises(CashImportPlanError, match="plan_changed"):
        service.read_plan(old["plan_id"])


def test_items_and_processing_details_are_paged_with_plan_and_filter_bound_cursors(context_runtime):
    from ft.application.cash_import_plans import CashImportPlanError
    service = _service(context_runtime)
    created = service.create((_upload(),))
    plan = service.preview(created.session_id, expected_session_revision=1)
    cursor, ids = None, []
    while True:
        page = service.items(plan["plan_id"], cursor=cursor, limit=17, confirmation_status="pending")
        assert all(item["confirmation_status"] == "pending" for item in page["items"])
        ids.extend(item["preview_item_id"] for item in page["items"])
        cursor = page["next_cursor"]
        if cursor is None:
            break
    assert len(ids) == len(set(ids)) == 60
    assert service.processing_details(plan["plan_id"])["items"][0]["code"] == "row_unreadable"
    cursor = service.items(plan["plan_id"], limit=1)["next_cursor"]
    with pytest.raises(CashImportPlanError, match="plan_cursor_invalid"):
        service.items(plan["plan_id"], cursor=cursor, confirmation_status="pending")
    with pytest.raises(CashImportPlanError, match="plan_cursor_invalid"):
        service.processing_details(plan["plan_id"], cursor=cursor)
    for limit in (0, 201, True):
        with pytest.raises(CashImportPlanError, match="plan_page_invalid"):
            service.items(plan["plan_id"], limit=limit)


def test_file_change_cancel_and_expiration_invalidate_the_current_plan(context_runtime):
    from ft.application.cash_import_plans import CashImportPlanError
    clock = [datetime(2026, 10, 21, tzinfo=timezone.utc)]
    service = _service(context_runtime, store=InMemoryParsedImportStore(clock=lambda: clock[0]))
    created = service.create((_upload(),))
    plan = service.preview(created.session_id, expected_session_revision=1)
    service.remove_file(created.session_id, created.files[0].file_ref, expected_revision=2)
    with pytest.raises(CashImportPlanError, match="plan_changed"):
        service.read_plan(plan["plan_id"])
    other = service.create((_upload(),))
    cancelled = service.preview(other.session_id, expected_session_revision=1)
    service.cancel(other.session_id)
    with pytest.raises(CashImportPlanError, match="plan_changed"):
        service.read_plan(cancelled["plan_id"])
    final = service.create((_upload(),))
    expired = service.preview(final.session_id, expected_session_revision=1)
    clock[0] += timedelta(seconds=1801)
    with pytest.raises(CashImportPlanError, match="plan_expired"):
        service.read_plan(expired["plan_id"])


def test_consumed_context_is_checked_before_preview_or_plan_access(context_runtime):
    from ft.adapters.relational.cash_mutations import CashMutationConflict
    service = _service(context_runtime)
    created = service.create((_upload(),))
    plan = service.preview(created.session_id, expected_session_revision=1)
    receipt = _consume(context_runtime, created.session_id)
    service._staging.cancel(created.session_id, workspace_id=context_runtime.workspace_id, user_id=USER, expected_revision=2)
    for operation in (lambda: service.preview(created.session_id, expected_session_revision=2, previous_plan_id=plan["plan_id"]),
        lambda: service.read_plan(plan["plan_id"])):
        with pytest.raises(CashMutationConflict) as error:
            operation()
        assert error.value.receipt_id == receipt["receipt_id"]


def test_viewer_cannot_publish_and_cross_user_cannot_read_plan(context_runtime):
    from ft.application.cash_import_context import CashImportPermissionDenied
    service = _service(context_runtime)
    created = service.create((_upload(),))
    plan = service.preview(created.session_id, expected_session_revision=1)
    other_user = "00000000-0000-4000-8000-000000000002"
    with context_runtime.sessions.begin() as session:
        session.scalar(select(WorkspaceMembershipModel).where(WorkspaceMembershipModel.user_id == USER)).role = "viewer"
        session.add(UserModel(id=other_user, email="another-plan@example.test", password_hash="synthetic"))
        session.flush()
        session.add(WorkspaceMembershipModel(user_id=other_user, workspace_id=context_runtime.workspace_id, role="editor"))
    with pytest.raises(CashImportPermissionDenied):
        service.preview(created.session_id, expected_session_revision=2, previous_plan_id=plan["plan_id"])
    with pytest.raises(ImportSessionForbidden):
        _service(context_runtime, store=service._staging, user_id=other_user).read_plan(plan["plan_id"])


def test_builder_failure_preserves_previous_immutable_plan(context_runtime):
    service = _service(context_runtime)
    created = service.create((_upload(),))
    old = service.preview(created.session_id, expected_session_revision=1)
    original = deepcopy(service.get(created.session_id).session)
    def broken(**_kwargs):
        raise RuntimeError("synthetic builder failure")
    service._builder = broken
    with pytest.raises(RuntimeError, match="synthetic builder failure"):
        service.preview(created.session_id, expected_session_revision=2, previous_plan_id=old["plan_id"])
    assert service.get(created.session_id).session == original


@pytest.mark.parametrize("preview_patch", [
    {"row_choices": [{"row_ref": "a:r1", "action": []}]},
    {"row_choices": [{"row_ref": "a:r1", "action": "apply", "patch": []}]},
    {"row_choices": [{"row_ref": "a:r1", "action": "apply"}] * 2},
    {"account_selections": [{"source_account_ref": "a", "account_id": "1", "expected_account_revision": True}]},
    {"account_selections": [{"source_account_ref": "a", "account_id": None, "expected_account_revision": 1}]},
    {"relation_choices": [{"suggestion_id": "a", "action": "accept", "operation_graph": {}}]},
])
def test_invalid_selection_structure_cannot_publish_or_increase_revision(context_runtime, preview_patch):
    from ft.application.cash_import_plans import CashImportPlanError
    service = _service(context_runtime)
    created = service.create((_upload(),))
    with pytest.raises(CashImportPlanError, match="plan_input_invalid"):
        service.preview(created.session_id, expected_session_revision=1, **preview_patch)
    assert service.get(created.session_id).session.revision == 1


def test_public_item_rows_cannot_leak_server_execution_fields(context_runtime):
    def builder(**kwargs):
        result = _build(**kwargs)
        for item in result["items"]:
            item["private_operation"] = {"technical": "server-only"}
        return result
    service = _service(context_runtime, builder=builder)
    created = service.create((_upload(),))
    plan = service.preview(created.session_id, expected_session_revision=1)
    assert all("private_operation" not in item for item in plan["items"])
    assert all("private_operation" not in item for item in service.items(plan["plan_id"])["items"])


@pytest.mark.parametrize("malformed", [{"processing_details": None}, {"item_details": []},
    {"file_results": None}, {"item_details": {"item_0": {"members": None}}}])
def test_unreadable_builder_output_preserves_old_plan_before_publication(context_runtime, malformed):
    from ft.application.cash_import_plans import CashImportPlanError
    service = _service(context_runtime)
    created = service.create((_upload(),))
    first = service.preview(created.session_id, expected_session_revision=1)
    before = service.get(created.session_id).session
    service._builder = lambda **kwargs: dict(_build(**kwargs), **malformed)
    with pytest.raises(CashImportPlanError, match="plan_build_failed"):
        service.preview(created.session_id, expected_session_revision=2, previous_plan_id=first["plan_id"])
    assert service.get(created.session_id).session == before


def test_processing_details_cursor_starts_at_first_undelivered_item(context_runtime):
    def builder(**kwargs):
        result = _build(**kwargs)
        result["processing_details"] = [{"row_ref": f"skip:r{i}", "code": "row_unreadable"} for i in range(121)]
        return result
    service = _service(context_runtime, builder=builder)
    created = service.create((_upload(),))
    plan = service.preview(created.session_id, expected_session_revision=1)
    cursor, refs = plan["pages"]["processing_details"], []
    while True:
        page = service.processing_details(plan["plan_id"], cursor=cursor)
        refs.extend(item["row_ref"] for item in page["items"])
        cursor = page["next_cursor"]
        if cursor is None:
            break
    assert refs == [f"skip:r{i}" for i in range(121)]


def test_public_details_and_nested_fields_cannot_leak_private_execution_data(context_runtime):
    def builder(**kwargs):
        result = _build(**kwargs)
        result["item_details"]["item_0"]["private_operation"] = {"secret": "server-only"}
        result["item_details"]["item_0"]["members"][0]["private_operation"] = "private-member"
        result["items"][0]["display"]["private_operation"] = "private-display"
        result["processing_details"][0]["private_operation"] = "private-processing"
        return result
    service = _service(context_runtime, builder=builder)
    created = service.create((_upload(),))
    plan = service.preview(created.session_id, expected_session_revision=1)
    assert "private_operation" not in plan["items"][0]["display"]
    detail = service.item(plan["plan_id"], "item_0")
    assert "private_operation" not in detail and "private_operation" not in detail["members"][0]
    assert "private_operation" not in service.processing_details(plan["plan_id"])["items"][0]


def test_public_issues_keep_scope_action_and_safe_message(context_runtime):
    issue = {"code": "account_missing", "message": "尚未选择账户", "field_path": "account_id",
        "blocking_scope": "row", "next_action": "edit"}
    def builder(**kwargs):
        result = _build(**kwargs)
        result["item_details"]["item_0"]["issues"] = [issue]
        result["file_results"] = [{"file_ref": "a", "issues": [dict(issue, blocking_scope="file", next_action="reselect_file")]}]
        return result
    service = _service(context_runtime, builder=builder)
    created = service.create((_upload(),))
    plan = service.preview(created.session_id, expected_session_revision=1)
    assert service.item(plan["plan_id"], "item_0")["issues"] == [issue]
    assert plan["file_results"][0]["issues"][0]["next_action"] == "reselect_file"


def test_issue_message_uses_safe_code_description_and_invalid_scope_cannot_publish(context_runtime):
    from ft.application.cash_import_plans import CashImportPlanError
    def builder(**kwargs):
        result = _build(**kwargs)
        result["item_details"]["item_0"]["issues"] = [{"code": "row_unreadable", "message": "SQL secret-provider"}]
        return result
    service = _service(context_runtime, builder=builder)
    created = service.create((_upload(),))
    plan = service.preview(created.session_id, expected_session_revision=1)
    problem = service.item(plan["plan_id"], "item_0")["issues"][0]
    assert "SQL" not in problem["message"] and problem["blocking_scope"] == "row"
    def invalid(**kwargs):
        result = _build(**kwargs)
        result["item_details"]["item_0"]["issues"] = [{"code": "row_unreadable", "blocking_scope": "database"}]
        return result
    service._builder = invalid
    with pytest.raises(CashImportPlanError, match="plan_build_failed"):
        service.preview(created.session_id, expected_session_revision=2, previous_plan_id=plan["plan_id"])
    assert service.get(created.session_id).session.revision == 2


def test_expired_plan_remains_expired_after_background_cleanup(context_runtime):
    from ft.application.cash_import_plans import CashImportPlanError
    clock = [datetime(2026, 10, 21, tzinfo=timezone.utc)]
    service = _service(context_runtime, store=InMemoryParsedImportStore(clock=lambda: clock[0]))
    created = service.create((_upload(),))
    plan = service.preview(created.session_id, expected_session_revision=1)
    clock[0] += timedelta(seconds=1801)
    service._staging.sweep_expired()
    with pytest.raises(CashImportPlanError, match="plan_expired"):
        service.read_plan(plan["plan_id"])


@pytest.mark.parametrize("extra", [{}, {"code": None}])
def test_processing_without_issue_code_drops_untrusted_problem_text(context_runtime, extra):
    def builder(**kwargs):
        result = _build(**kwargs)
        result["processing_details"] = [{"row_ref": "a:r1", "planned_action": "duplicate",
            "message": "SQL secret-provider", "blocking_scope": "request", **extra}]
        return result
    service = _service(context_runtime, builder=builder)
    created = service.create((_upload(),))
    plan = service.preview(created.session_id, expected_session_revision=1)
    item = service.processing_details(plan["plan_id"])["items"][0]
    assert "message" not in item and "blocking_scope" not in item
