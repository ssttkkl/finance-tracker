"""新导入 HTTP 的计划、一次确认及持久结果恢复。"""
from dataclasses import replace
from datetime import timedelta
from decimal import Decimal

import pytest
from sqlalchemy import func, select, update

from ft.adapters.relational.models import AccountModel, CashTransactionModel, WorkspaceMembershipModel
from ft.application.cash_projections import CashProjectionService
from tests.contract.test_cash_import_operations import _file
from tests.contract.test_cash_import_session_api import _client, _upload
from tests.contract.test_parsed_cash_import_context import USER, context_runtime


@pytest.fixture(autouse=True)
def configured(context_runtime):
    with context_runtime.sessions.begin() as session:
        session.execute(update(AccountModel).where(AccountModel.workspace_id == context_runtime.workspace_id,
            AccountModel.id.in_((101, 102))).values(currencies=["CNY"]))
    CashProjectionService(context_runtime.sessions, context_runtime.workspace_id).rebuild()


def _setup(runtime, monkeypatch, *, files=None, user_id=USER, store=None):
    files = files or (_file(),)
    documents = {file.client_file_ref: file.document for file in files}
    client, store = _client(runtime, monkeypatch, parser=lambda upload: documents[upload.client_file_ref],
        user_id=user_id, store=store)
    view = _upload(client, metadata={"files": [{"client_file_ref": ref} for ref in documents]}, files=[
        ("file:" + ref, ("synthetic.csv", ("synthetic-" + ref).encode())) for ref in documents]).json()
    return client, store, view


def _input(view, **changes):
    return {"expected_session_revision": view["session_revision"], "previous_plan_id": None,
        "account_selections": [{"source_account_ref": item["source_account_ref"], "account_id": "101",
            "expected_account_revision": 1} for item in view["source_accounts"]],
        "row_choices": [], "relation_choices": [], **changes}


def _preview(client, view, **changes):
    return client.post("/api/v1/cash-import/sessions/" + view["session_id"] + "/plans", json=_input(view, **changes))


def _commit(client, plan, key="synthetic-api-confirm"):
    return client.post("/api/v1/cash-import/plans/" + plan["plan_id"] + "/commit",
        headers={"Idempotency-Key": key}, json={"expected_session_revision": plan["session_revision"]})


def _rows(count):
    file = _file()
    row = file.document.rows[0]
    return replace(file, document=replace(file.document, rows=tuple(replace(row, position=index + 1,
        provider_id=f"synthetic-order-{index}", fields={**row.fields, "amount": f"-{index + 1}"})
        for index in range(count)), declared_count=count, observed_count=count))


def test_http_preview_commit_pages_and_receipt_replay_after_temporary_cleanup(context_runtime, monkeypatch):
    client, store, view = _setup(context_runtime, monkeypatch, files=(_rows(3),))
    with context_runtime.sessions() as session:
        before = session.scalar(select(func.count()).select_from(CashTransactionModel))
    response = _preview(client, view)
    assert response.status_code == 200, response.text
    plan = response.json()
    assert plan["session_revision"] == 2 and plan["can_commit"] is True
    assert len(plan["items"]) == 3 and plan["item_counts"] == {"confirmed": 3, "pending": 0}
    assert not {"operations", "relation_groups", "source_candidate_sets", "detail_values"}.intersection(plan)
    with context_runtime.sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == before
    path = "/api/v1/cash-import/plans/" + plan["plan_id"]
    assert client.get(path).json() == plan
    page = client.get(path + "/items", params={"limit": "1"}).json()
    assert len(page["items"]) == 1 and page["next_cursor"]
    assert len(client.get(path + "/items", params={"limit": "2", "cursor": page["next_cursor"]}).json()["items"]) == 2
    detail = client.get(path + "/items/" + page["items"][0]["preview_item_id"])
    assert detail.status_code == 200 and len(detail.json()["members"]) == 1
    assert "fields_ref" not in detail.text and "preview_components" not in detail.text
    assert client.get(path + "/processing-details").json()["items"] == []
    committed = _commit(client, plan)
    assert committed.status_code == 200, committed.text
    receipt = committed.json()
    assert receipt["committed"] is True and receipt["summary"]["created_posted"] == 3
    assert not {"request_digest", "session_digest", "retry_context_id", "row_results", "affected_transaction_ids"}.intersection(receipt)
    receipt_path = "/api/v1/cash-mutations/receipts/" + receipt["receipt_id"]
    assert client.get(receipt_path).json() == receipt
    assert client.get("/api/v1/cash-mutations/receipts/by-key/synthetic-api-confirm").json() == receipt
    page = client.get(receipt_path + "/rows", params={"limit": "1"}).json()
    assert len(page["items"]) == 1 and isinstance(page["next_after"], str)
    assert len(page["projection_refs"]) == 1
    assert len(client.get(receipt_path + "/rows", params={"after": page["next_after"], "limit": "2"}).json()["items"]) == 2
    assert client.get(receipt_path + "/relations").json()["items"] == []
    store._entries.clear()
    assert _commit(client, plan).json() == receipt
    assert _commit(client, plan, "another-key").json()["error"]["code"] == "session_committed"
    resolved = client.post("/api/v1/cash-mutations/resolve", json={"plan_id": plan["plan_id"],
        "idempotency_key": "synthetic-api-confirm"})
    assert resolved.status_code == 200 and resolved.json() == {"status": "committed", "receipt": receipt}
    with context_runtime.sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == before + 3


def test_http_repreview_uses_complete_inputs_and_old_plan_cannot_commit(context_runtime, monkeypatch):
    client, _, view = _setup(context_runtime, monkeypatch)
    first = _preview(client, view).json()
    assert "plan_id" in first
    detail = client.get("/api/v1/cash-import/plans/" + first["plan_id"] + "/items/" + first["items"][0]["preview_item_id"]).json()
    row_ref = detail["members"][0]["row_ref"]
    changed = _preview(client, {**view, "session_revision": first["session_revision"]}, previous_plan_id=first["plan_id"],
        row_choices=[{"row_ref": row_ref, "action": "apply", "patch": {"note": "人工备注"}}])
    assert changed.status_code == 200, changed.text
    assert changed.json()["items"][0]["display"]["note"] == "人工备注"
    assert _preview(client, view).json()["error"]["code"] == "plan_changed"
    assert _commit(client, first).status_code == 409
    assert _commit(client, changed.json()).status_code == 200
    with context_runtime.sessions() as session:
        from ft.adapters.relational.models import CashEntryModel
        saved = session.scalar(select(CashEntryModel).where(CashEntryModel.note == "人工备注"))
        assert 'note' in saved.manual_fields
        assert session.scalar(select(CashTransactionModel).where(CashTransactionModel.note == "人工备注")) is None


def test_http_known_rollback_and_lost_commit_response_have_distinct_recovery(context_runtime, monkeypatch):
    from ft.adapters.relational.cash_mutations import CashMutationRepository
    from ft.adapters.relational.uow import RelationalUnitOfWork
    client, _, view = _setup(context_runtime, monkeypatch)
    plan = _preview(client, view).json()
    assert "plan_id" in plan
    original = CashMutationRepository.create
    def fail(self, **kwargs):
        original(self, **kwargs)
        raise RuntimeError("private SQL password /tmp/private.csv")
    with monkeypatch.context() as fault:
        fault.setattr(CashMutationRepository, "create", fail)
        response = _commit(client, plan)
    assert response.status_code == 503 and response.json()["commit_state"] == "not_committed"
    assert response.json()["error"]["code"] == "commit_failed" and "private" not in response.text
    assert client.get("/api/v1/cash-mutations/receipts/by-key/synthetic-api-confirm").status_code == 404
    assert client.post("/api/v1/cash-mutations/resolve", json={"plan_id": plan["plan_id"],
        "idempotency_key": "synthetic-api-confirm"}).json() == {"status": "retry_original"}
    publish = RelationalUnitOfWork.commit
    def lost(self):
        publish(self)
        raise OSError("private lost connection")
    with monkeypatch.context() as fault:
        fault.setattr(RelationalUnitOfWork, "commit", lost)
        response = _commit(client, plan)
    assert response.status_code == 503 and response.json()["commit_state"] == "unknown"
    assert response.json()["error"]["code"] == "commit_state_unknown" and "private" not in response.text
    resolved = client.post("/api/v1/cash-mutations/resolve", json={"plan_id": plan["plan_id"],
        "idempotency_key": "synthetic-api-confirm"}).json()
    assert resolved["status"] == "committed"
    assert _commit(client, plan).json() == resolved["receipt"]


@pytest.mark.parametrize("phase", ["replay_query", "replay_exit"])
def test_replaying_committed_key_cannot_report_not_committed_on_read_failure(context_runtime, monkeypatch, phase):
    from ft.adapters.relational.cash_mutations import CashMutationRepository
    from ft.adapters.relational.uow import RelationalUnitOfWork
    client, _, view = _setup(context_runtime, monkeypatch)
    plan = _preview(client, view).json()
    receipt = _commit(client, plan).json()
    assert receipt["committed"] is True
    original_exit = RelationalUnitOfWork.__exit__
    def fail_query(self, *args, **kwargs):
        raise OSError("private replay connection")
    def fail_exit(self, *args):
        original_exit(self, *args)
        raise OSError("private replay close")
    with monkeypatch.context() as fault:
        fault.setattr(CashMutationRepository, "replay", fail_query) if phase == "replay_query" else fault.setattr(
            RelationalUnitOfWork, "__exit__", fail_exit)
        response = _commit(client, plan)
    assert response.status_code == 503 and response.json()["commit_state"] == "unknown"
    assert "private" not in response.text
    assert _commit(client, plan).json() == receipt


def test_http_full_refund_zero_is_available_for_review_and_current_result(context_runtime, monkeypatch):
    from tests.contract.test_cash_import_commit import _refund_bundle
    client, _, view = _setup(context_runtime, monkeypatch, files=_refund_bundle())
    response = _preview(client, view)
    assert response.status_code == 200, response.text
    plan = response.json()
    assert len(plan["items"]) == 1 and Decimal(plan["items"][0]["display"]["amount"]) == 0, plan
    assert plan["items"][0]["detail_available"] is True
    receipt = _commit(client, plan).json()
    assert receipt["summary"]["created_posted"] == 4 and receipt["summary"]["relations_confirmed"] == 1
    rows = client.get("/api/v1/cash-mutations/receipts/" + receipt["receipt_id"] + "/rows").json()
    assert len(rows["items"]) == 4 and len({item["projection_id"] for item in rows["projection_refs"]}) == 1


@pytest.mark.parametrize("query", ["limit=0", "limit=201", "limit=1.0", "limit=1&limit=2", "other=private",
    "confirmation_status=invalid", "cursor=private-invalid"])
def test_http_plan_query_errors_are_safe(context_runtime, monkeypatch, query):
    client, _, view = _setup(context_runtime, monkeypatch)
    plan = _preview(client, view).json()
    assert "plan_id" in plan
    response = client.get("/api/v1/cash-import/plans/" + plan["plan_id"] + "/items?" + query)
    assert response.status_code == 422 and "private" not in response.text


def test_http_partial_result_can_retry_only_failed_rows_without_original_file(context_runtime, monkeypatch):
    from ft.adapters.relational.cash_sources import CashSourceRepository
    client, _, view = _setup(context_runtime, monkeypatch, files=(_rows(2),))
    plan = _preview(client, view).json()
    original = CashSourceRepository.save_occurrence
    def fail(self, identifier, row, **kwargs):
        result = original(self, identifier, row, **kwargs)
        if row.provider_id == "synthetic-order-0":
            raise RuntimeError("private single-row SQL failure")
        return result
    with monkeypatch.context() as fault:
        fault.setattr(CashSourceRepository, "save_occurrence", fail)
        response = _commit(client, plan)
    assert response.status_code == 200, response.text
    receipt = response.json()
    assert receipt["status"] == "partial" and receipt["summary"]["created_posted"] == 1 and receipt["summary"]["skipped"] == 1
    assert receipt["retry_context_status"] == "available" and "retry_context_id" not in receipt
    path = "/api/v1/cash-import/receipts/" + receipt["receipt_id"] + "/retry-sessions"
    rows = client.get("/api/v1/cash-mutations/receipts/" + receipt["receipt_id"] + "/rows").json()["items"]
    failed, = [item for item in rows if item["outcome"] == "skipped"]
    assert failed["code"] == "row_save_failed" and "private" not in failed["message"]
    for references in ([], ["unknown"], [next(item["item_ref"] for item in rows if item["outcome"] != "skipped")]):
        invalid = client.post(path, headers={"Idempotency-Key": "bad-retry"}, json={"row_refs": references})
        assert invalid.status_code == 422
    retry = client.post(path, headers={"Idempotency-Key": "retry-key"}, json={"row_refs": [failed["item_ref"]]})
    assert retry.status_code == 201, retry.text
    current = retry.json()
    assert current["expires_at"] == view["expires_at"] and current["files"][0]["recognized_row_count"] == 1
    assert client.post(path, headers={"Idempotency-Key": "retry-key"}, json={"row_refs": [failed["item_ref"]]}).json() == current
    assert current["last_preview_input"]["relation_choices"] == []
    next_plan = _preview(client, current, **current["last_preview_input"])
    assert next_plan.status_code == 200, next_plan.text
    final = _commit(client, next_plan.json(), "retry-confirm").json()
    assert final["summary"]["created_posted"] == 1 and final["summary"]["duplicate"] == 0
    replay = client.post(path, headers={"Idempotency-Key": "retry-key"}, json={"row_refs": [failed["item_ref"]]})
    assert replay.status_code == 409 and replay.json()["error"]["code"] == "retry_already_processed"
    assert replay.json()["receipt_id"] == final["receipt_id"]
    assert client.get("/api/v1/cash-mutations/receipts/" + receipt["receipt_id"]).json() == receipt


def test_http_relation_failure_does_not_rollback_posted_rows(context_runtime, monkeypatch):
    from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
    from tests.contract.test_cash_import_commit import _mirror_files
    client, _, view = _setup(context_runtime, monkeypatch, files=_mirror_files())
    plan = _preview(client, view).json()
    assert plan["item_counts"]["confirmed"] == 1
    original = CurrentCashRelationRepository.add
    def fail(self, values):
        original(self, values)
        raise RuntimeError("private relation SQL")
    with monkeypatch.context() as fault:
        fault.setattr(CurrentCashRelationRepository, "add", fail)
        response = _commit(client, plan)
    assert response.status_code == 200, response.text
    receipt = response.json()
    assert receipt["status"] == "partial" and receipt["summary"]["created_posted"] == 2
    assert receipt["summary"]["relations_failed"] == 1 and receipt["pending_item_count"] == 2
    assert receipt["commit_outcome"] == {"has_new_failures": True, "has_business_writes": True, "reason_codes": ["relation_save_failed"]}
    page = client.get("/api/v1/cash-mutations/receipts/" + receipt["receipt_id"] + "/relations").json()
    assert page["items"][0]["outcome"] == "failed" and "private" not in page["items"][0]["message"]
    rows = client.get("/api/v1/cash-mutations/receipts/" + receipt["receipt_id"] + "/rows").json()
    assert len({item["projection_id"] for item in rows["projection_refs"]}) == 2


def test_http_unselected_account_saves_draft_and_row_field_error_is_not_protocol_failure(context_runtime, monkeypatch):
    client, _, view = _setup(context_runtime, monkeypatch, files=(_rows(2),))
    draft = _preview(client, view, account_selections=[]).json()
    assert draft["item_counts"]["pending"] == 2 and all(not item["statistics_eligible"] for item in draft["items"])
    first = draft["items"][0]["member_refs"][0]["row_ref"]
    checked = _preview(client, {**view, "session_revision": draft["session_revision"]},
        previous_plan_id=draft["plan_id"], account_selections=[],
        row_choices=[{"row_ref": first, "action": "apply", "patch": {"manual_overrides": {"note": True}}}])
    assert checked.status_code == 200, checked.text
    assert checked.json()["summary"]["skip"] == 1 and checked.json()["summary"]["create_draft"] == 1
    receipt = _commit(client, checked.json()).json()
    assert receipt["summary"]["saved_draft"] == 1 and receipt["summary"]["skipped"] == 1


@pytest.mark.parametrize("change", [
    {"expected_session_revision": True}, {"expected_session_revision": 1.0}, {"expected_session_revision": 2 ** 63},
    {"operations": [{"private": "input"}]}, {"row_choices": [{"row_ref": "a", "action": "apply", "patch": []}]},
    {"account_selections": [{"source_account_ref": "a", "account_id": "101", "expected_account_revision": True}]},
])
def test_http_preview_structure_is_strict_and_does_not_echo_input(context_runtime, monkeypatch, change):
    client, _, view = _setup(context_runtime, monkeypatch)
    response = _preview(client, view, **change)
    assert response.status_code == 422 and "private" not in response.text


def test_http_missing_full_array_duplicate_json_and_missing_key_are_rejected(context_runtime, monkeypatch):
    import json
    client, _, view = _setup(context_runtime, monkeypatch)
    body = _input(view)
    body.pop("row_choices")
    path = "/api/v1/cash-import/sessions/" + view["session_id"] + "/plans"
    assert client.post(path, json=body).status_code == 422
    raw = json.dumps(_input(view))[:-1] + ',"expected_session_revision":1}'
    assert client.post(path, content=raw, headers={"Content-Type": "application/json"}).status_code == 422
    plan = _preview(client, view).json()
    assert client.post("/api/v1/cash-import/plans/" + plan["plan_id"] + "/commit",
        json={"expected_session_revision": plan["session_revision"]}).status_code == 422


def test_http_old_cursor_and_another_operator_cannot_read_or_commit(context_runtime, monkeypatch):
    from ft.adapters.relational.models import UserModel
    other_user = "00000000-0000-4000-8000-000000000099"
    client, store, view = _setup(context_runtime, monkeypatch, files=(_rows(3),))
    plan = _preview(client, view).json()
    path = "/api/v1/cash-import/plans/" + plan["plan_id"]
    cursor = client.get(path + "/items?limit=1").json()["next_cursor"]
    response = client.get(path + "/processing-details", params={"cursor": cursor})
    assert response.status_code == 422
    assert client.get(path + "/items", params={"cursor": cursor, "confirmation_status": "pending"}).status_code == 422
    with context_runtime.sessions.begin() as session:
        session.add(UserModel(id=other_user, email="other-plan@example.test", password_hash="synthetic"))
        session.flush()
        session.add(WorkspaceMembershipModel(workspace_id=context_runtime.workspace_id, user_id=other_user, role="editor"))
    other, _ = _client(context_runtime, monkeypatch, store=store, user_id=other_user)
    assert other.get(path).status_code == 404
    assert _commit(other, plan).status_code == 404
    receipt = _commit(client, plan).json()
    assert other.get("/api/v1/cash-mutations/receipts/" + receipt["receipt_id"]).status_code == 404
    assert other.get("/api/v1/cash-mutations/receipts/by-key/synthetic-api-confirm").status_code == 404


@pytest.mark.parametrize("cancelled", [True, False])
def test_http_expiry_and_cancellation_resolve_to_not_committed(context_runtime, monkeypatch, cancelled):
    from datetime import datetime, timezone
    from ft.application.parsed_import_staging import InMemoryParsedImportStore
    now = [datetime.now(timezone.utc)]
    client, _, view = _setup(context_runtime, monkeypatch,
        store=InMemoryParsedImportStore(clock=lambda: now[0], ttl_seconds=60))
    plan = _preview(client, view).json()
    if cancelled:
        assert client.delete("/api/v1/cash-import/sessions/" + view["session_id"]).status_code == 200
    else:
        now[0] += timedelta(seconds=61)
    path = "/api/v1/cash-import/plans/" + plan["plan_id"]
    assert client.get(path).status_code == (409 if cancelled else 410)
    assert _commit(client, plan).json()["error"]["code"] == ("plan_changed" if cancelled else "plan_expired")
    assert client.post("/api/v1/cash-mutations/resolve", json={"plan_id": plan["plan_id"],
        "idempotency_key": "synthetic-api-confirm"}).json() == {"status": "not_committed"}


def test_http_resolve_reports_pending_while_an_independent_writer_holds_lock(context_runtime, monkeypatch):
    from ft.adapters.relational.workspace_lock import lock_workspace
    client, _, view = _setup(context_runtime, monkeypatch)
    plan = _preview(client, view).json()
    body = {"plan_id": plan["plan_id"], "idempotency_key": "synthetic-api-confirm"}
    with context_runtime.sessions() as writer:
        lock_workspace(writer, context_runtime.workspace_id)
        response = client.post("/api/v1/cash-mutations/resolve", json=body)
        assert response.status_code == 200 and response.json() == {"status": "pending"}
        writer.rollback()
    assert client.post("/api/v1/cash-mutations/resolve", json=body).json() == {"status": "retry_original"}


def test_http_duplicate_only_can_confirm_and_fixed_amount_conflict_only_is_failed(context_runtime, monkeypatch):
    file = _file()
    client, _, view = _setup(context_runtime, monkeypatch, files=(file,))
    plan = _preview(client, view).json()
    assert _commit(client, plan).json()["summary"]["created_posted"] == 1
    client, _, view = _setup(context_runtime, monkeypatch, files=(file,))
    duplicate = _preview(client, view).json()
    assert duplicate["items"] == [] and duplicate["can_commit"] is True
    receipt = _commit(client, duplicate, "duplicate-confirm").json()
    assert receipt["status"] == "no_changes" and receipt["summary"]["duplicate"] == 1
    changed = replace(file, document=replace(file.document, rows=(replace(file.document.rows[0],
        fields={**file.document.rows[0].fields, "amount": "-99"}),)))
    client, _, view = _setup(context_runtime, monkeypatch, files=(changed,))
    conflict = _preview(client, view).json()
    assert conflict["items"] == [] and conflict["summary"]["skip"] == 1
    receipt = _commit(client, conflict, "conflict-confirm").json()
    assert receipt["status"] == "failed" and receipt["summary"]["skipped"] == 1
    assert receipt["commit_outcome"] == {"has_new_failures": False, "has_business_writes": False, "reason_codes": []}
    results = client.get("/api/v1/cash-mutations/receipts/" + receipt["receipt_id"] + "/rows").json()
    assert results["items"][0]["code"] == "fixed_field_conflict"


def test_http_permission_is_rechecked_for_repreview_and_replay(context_runtime, monkeypatch):
    client, _, view = _setup(context_runtime, monkeypatch)
    plan = _preview(client, view).json()
    receipt = _commit(client, plan).json()
    with context_runtime.sessions.begin() as session:
        session.scalar(select(WorkspaceMembershipModel).where(WorkspaceMembershipModel.user_id == USER,
            WorkspaceMembershipModel.workspace_id == context_runtime.workspace_id)).role = "viewer"
    assert _commit(client, plan).status_code == 403
    assert _preview(client, view).status_code == 403
    assert client.get("/api/v1/cash-mutations/receipts/" + receipt["receipt_id"]).status_code == 200


def test_http_plan_keeps_file_status_and_unknown_count_consistent_with_session(context_runtime, monkeypatch):
    def parser(upload):
        if upload.client_file_ref == "client-b":
            raise RuntimeError("private unreadable original")
        return _file().document
    client, _ = _client(context_runtime, monkeypatch, parser=parser)
    view = _upload(client, metadata={"files": [{"client_file_ref": "client-a"}, {"client_file_ref": "client-b"}]},
        files=[("file:client-a", ("synthetic-a.csv", b"a")), ("file:client-b", ("synthetic-b.csv", b"b"))]).json()
    response = _preview(client, view)
    assert response.status_code == 200
    plan = response.json()
    assert plan["can_commit"] is True
    for original, shown in zip(view["files"], plan["file_results"], strict=True):
        assert shown["file_ref"] == original["file_ref"]
        assert shown["status"] == original["status"]
        assert shown["recognized_row_count"] == original["recognized_row_count"]
    assert plan["file_results"][1]["recognized_row_count"] is None


def test_authentication_and_viewer_denial_keep_the_public_error_envelope(context_runtime, monkeypatch):
    client, store, view = _setup(context_runtime, monkeypatch)
    denied = client.get("/api/v1/cash-import/sessions/" + view["session_id"], headers={"Authorization": "Bearer invalid"})
    assert denied.status_code == 401
    assert denied.json()["error"]["field_errors"] == [] and denied.json()["error"]["retryable"] is False
    viewer, _ = _client(context_runtime, monkeypatch, store=store, role="viewer")
    denied = viewer.post("/api/v1/cash-import/sessions/" + view["session_id"] + "/plans", content=b"private body")
    assert denied.status_code == 403
    assert denied.json()["error"]["field_errors"] == [] and denied.json()["error"]["retryable"] is False
    assert "private" not in denied.text


def test_http_empty_import_evidence_failure_is_a_new_failure_without_business_writes(context_runtime, monkeypatch):
    from ft.application.cash_import_commit import CashImportCommandService
    file = _file()
    empty = replace(file, document=replace(file.document, rows=(), declared_count=0, observed_count=0))
    client, _, view = _setup(context_runtime, monkeypatch, files=(empty,))
    plan = _preview(client, view).json()
    monkeypatch.setattr(CashImportCommandService, "_save_evidence", lambda *_: {("alipay", "fixture", "empty"): "source_evidence_save_failed"})
    receipt = _commit(client, plan).json()
    assert receipt["commit_outcome"] == {"has_new_failures": True, "has_business_writes": False, "reason_codes": ["source_evidence_save_failed"]}
    assert client.get("/api/v1/cash-mutations/receipts/" + receipt["receipt_id"]).json() == receipt
    assert _commit(client, plan).json() == receipt


def test_http_duplicates_plus_a_new_failure_do_not_claim_business_writes(context_runtime, monkeypatch):
    from ft.adapters.relational.cash_sources import CashSourceRepository
    client, _, view = _setup(context_runtime, monkeypatch, files=(_rows(1),))
    _commit(client, _preview(client, view).json())
    client, _, view = _setup(context_runtime, monkeypatch, files=(_rows(2),))
    plan = _preview(client, view).json()
    original = CashSourceRepository.save_occurrence
    def fail(self, identifier, row, **kwargs):
        if row.provider_id == "synthetic-order-1":
            raise RuntimeError("private database SQL")
        return original(self, identifier, row, **kwargs)
    monkeypatch.setattr(CashSourceRepository, "save_occurrence", fail)
    receipt = _commit(client, plan, "mixed-confirm").json()
    assert receipt["status"] == "partial"
    assert receipt["commit_outcome"] == {"has_new_failures": True, "has_business_writes": False, "reason_codes": ["row_save_failed"]}
    assert "private" not in str(receipt["commit_outcome"])


def test_public_plan_reads_do_not_materialize_unrelated_parsed_documents(context_runtime, monkeypatch):
    client, store, view = _setup(context_runtime, monkeypatch, files=(_rows(3),))
    response = _preview(client, view)
    assert response.status_code == 200
    plan = response.json()
    monkeypatch.setattr(store, "get", lambda *_args, **_kwargs: pytest.fail("plan read decoded source documents"))
    path = "/api/v1/cash-import/plans/" + plan["plan_id"]
    assert client.get(path).status_code == 200
    assert client.get(path + "/items").status_code == 200
    assert client.get(path + "/processing-details").status_code == 200
    assert client.get(path + "/items/" + plan["items"][0]["preview_item_id"]).status_code == 200


def test_hot_public_pages_keep_plan_identity_and_complete_pagination_parameters(context_runtime, monkeypatch):
    client, _store, view = _setup(context_runtime, monkeypatch, files=(_rows(3),))
    plan = _preview(client, view).json()
    path = "/api/v1/cash-import/plans/" + plan["plan_id"]
    first = client.get(path + "/items?limit=1").json()
    assert len(first["items"]) == 1
    assert client.get(path + "/items?limit=1").json() == first
    assert len(client.get(path + "/items?limit=2").json()["items"]) == 2
    assert client.get(path + "/items?limit=1&confirmation_status=pending").json()["items"] == []
    second = client.get(path + "/items", params={"limit": 1, "cursor": first["next_cursor"]}).json()
    assert second["items"][0] != first["items"][0]
    forged = plan["plan_id"].split(".")[0] + "." + "x" * 16
    response = client.get("/api/v1/cash-import/plans/" + forged + "/items?limit=1")
    assert response.status_code == 409 and response.json()["error"]["code"] == "plan_changed"
    _commit(client, plan)
    response = client.get(path + "/items?limit=1")
    assert response.status_code == 409 and response.json()["error"]["code"] == "session_committed"
