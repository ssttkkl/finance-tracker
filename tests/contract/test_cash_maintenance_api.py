"""当前流水无需原文件即可补充、预览和确认，所有写入可核实。"""
from datetime import timedelta
from decimal import Decimal

import pytest

from ft.adapters.relational.models import CashTransactionModel, WorkspaceMembershipModel
from ft.application.cash_projections import CashProjectionService
from tests.contract.test_cash_import_commit import configured
from tests.contract.test_cash_import_session_api import _client
from tests.contract.test_cash_mutation_business_plan import _posted
from tests.contract.test_cash_record_maintenance import _complete, _draft, _service as _records
from tests.contract.test_cash_source_issue_resolution import _issue
from tests.contract.test_parsed_cash_import_context import USER, context_runtime


def _input(runtime, identifier, **changes):
    return {"intent": "edit_record", "transaction_id": identifier,
        "expected_revision": _records(runtime).read(identifier)["row_revision"], **changes}


def _preview(client, runtime, identifier, **changes):
    return client.post("/api/v1/cash-mutations/plans", json=_input(runtime, identifier, **changes))


def _commit(client, plan, key="synthetic-maintenance-confirm"):
    return client.post("/api/v1/cash-mutations/plans/" + plan["plan_id"] + "/commit",
        headers={"Idempotency-Key": key}, json={"expected_plan_revision": plan["plan_revision"]})


def test_draft_save_is_idempotent_without_a_file_and_keeps_explicit_current_detail(context_runtime, monkeypatch):
    identifier = _draft(context_runtime)
    client, store = _client(context_runtime, monkeypatch)
    path = "/api/v1/cash-records/" + identifier
    response = client.get(path)
    assert response.status_code == 200 and response.json()["state"] == "draft"
    before = response.json()
    body = {"expected_revision": before["row_revision"], "patch": _complete(before, note="")}
    response = client.patch(path + "/draft", headers={"Idempotency-Key": "draft-save-http"}, json=body)
    assert response.status_code == 200, response.text
    saved = response.json()
    assert saved["receipt"]["committed"] and saved["receipt"]["operation_kind"] == "save_draft"
    current = saved["current_record"]
    assert saved["current_record_status"] == "available" and current["state"] == "draft"
    assert current["fields"]["note"] == "" and current["transaction_id"] == identifier
    assert current["row_revision"] > before["row_revision"] and current["confirmation_status"] == "pending"
    with context_runtime.sessions.begin() as session:
        session.get(CashTransactionModel, int(identifier)).note = "后来的当前备注"
    replay = client.patch(path + "/draft", headers={"Idempotency-Key": "draft-save-http"}, json=body)
    assert replay.json()["receipt"] == saved["receipt"]
    assert replay.json()["current_record"]["fields"]["note"] == "后来的当前备注"
    assert store._entries == {}
    assert "request_digest" not in response.text and "manual_overrides" not in response.text


def test_finalize_http_uses_one_plan_and_recovers_receipt_after_context_cleanup(context_runtime, monkeypatch):
    identifier = _draft(context_runtime)
    client, store = _client(context_runtime, monkeypatch)
    before = _records(context_runtime).read(identifier)
    response = _preview(client, context_runtime, identifier, intent="finalize_draft", patch=_complete(before))
    assert response.status_code == 200, response.text
    plan = response.json()
    assert plan["summary"]["finalize_draft"] == 1 and plan["plan_revision"] == 1 and plan["can_commit"]
    assert _records(context_runtime).read(identifier) == before
    context = store.get(plan["edit_context_id"], workspace_id=context_runtime.workspace_id, user_id=USER)
    assert context.workflow_kind == "mutation" and context.files == ()
    path = "/api/v1/cash-mutations/plans/" + plan["plan_id"]
    assert client.get(path).json() == plan
    assert client.get("/api/v1/cash-mutations/edit-contexts/" + plan["edit_context_id"] + "/plan").json() == plan
    item = client.get(path + "/items?limit=1").json()["items"][0]
    detail = client.get(path + "/items/" + item["preview_item_id"])
    assert detail.status_code == 200 and detail.json()["members"][0]["fields"]["state"] == "posted"
    for secret in ("dependencies", "operations", "fields_ref", "context_revision", "source_scope_key"):
        assert secret not in response.text and secret not in detail.text
    committed = _commit(client, plan)
    assert committed.status_code == 200, committed.text
    receipt = committed.json()
    assert receipt["summary"]["finalized_draft"] == 1 and receipt["retained_transaction_id"] == identifier
    assert client.get("/api/v1/cash-records/" + identifier).json()["state"] == "posted"
    assert client.get("/api/v1/cash-mutations/receipts/" + receipt["receipt_id"]).json() == receipt
    results = client.get("/api/v1/cash-mutations/receipts/" + receipt["receipt_id"] + "/rows").json()
    assert results["items"][0]["transaction_id"] == identifier and len(results["projection_refs"]) == 1
    store._entries.clear()
    assert _commit(client, plan).json() == receipt
    consumed = client.get("/api/v1/cash-mutations/edit-contexts/" + plan["edit_context_id"] + "/plan")
    assert consumed.status_code == 409 and consumed.json()["receipt_id"] == receipt["receipt_id"]
    assert _commit(client, plan, "different-maintenance-key").status_code == 409
    resolved = client.post("/api/v1/cash-mutations/resolve", json={"plan_id": plan["plan_id"],
        "idempotency_key": "synthetic-maintenance-confirm"})
    assert resolved.json() == {"status": "committed", "receipt": receipt}


def test_repreview_replaces_complete_input_without_renewing_context(context_runtime, monkeypatch):
    identifier = _posted(context_runtime)
    client, _ = _client(context_runtime, monkeypatch)
    first = _preview(client, context_runtime, identifier, patch={"note": "第一版"}).json()
    assert "plan_id" in first
    response = _preview(client, context_runtime, identifier, patch={}, edit_context_id=first["edit_context_id"],
        previous_plan_id=first["plan_id"], expected_plan_revision=first["plan_revision"])
    assert response.status_code == 200, response.text
    next_plan = response.json()
    assert next_plan["plan_revision"] == 2 and next_plan["expires_at"] == first["expires_at"]
    assert next_plan["items"][0]["display"]["note"] != "第一版"
    assert _commit(client, first).status_code == 409
    assert _commit(client, next_plan).status_code == 200


def test_current_source_issue_and_manual_patch_share_one_http_confirmation(context_runtime, monkeypatch):
    identifier = _posted(context_runtime)
    choice = _issue(context_runtime, identifier, fields={"counterparty": "待采用的对方"}, payload={"备注": "来源原文"})
    CashProjectionService(context_runtime.sessions, context_runtime.workspace_id).rebuild()
    client, _ = _client(context_runtime, monkeypatch)
    before = client.get("/api/v1/cash-records/" + identifier).json()
    issue = before["issues"][0]
    assert issue["revision"] == choice["expected_issue_revision"] and "apply_source" in issue["allowed_resolutions"]
    assert issue["pending_source_payload"] == {"备注": "来源原文"}
    response = _preview(client, context_runtime, identifier, intent="resolve_issue", issue_resolutions=[choice], patch={"note": ""})
    assert response.status_code == 200, response.text
    committed = _commit(client, response.json())
    assert committed.status_code == 200 and committed.json()["summary"]["resolved_issues"] == 1
    after = client.get("/api/v1/cash-records/" + identifier).json()
    assert after["fields"]["note"] == "" and after["fields"]["counterparty"] == "待采用的对方"
    assert after["issues"] == [] and after["confirmation_status"] == "confirmed"


@pytest.mark.parametrize("operation", ["draft", "mutation"])
def test_storage_rollback_and_lost_commit_response_are_safe_for_both_maintenance_writes(context_runtime, monkeypatch, operation):
    from ft.adapters.relational.cash_mutations import CashMutationRepository
    from ft.adapters.relational.uow import RelationalUnitOfWork
    identifier = _draft(context_runtime) if operation == "draft" else _posted(context_runtime)
    client, _ = _client(context_runtime, monkeypatch)
    before = _records(context_runtime).read(identifier)
    if operation == "draft":
        def perform():
            return client.patch("/api/v1/cash-records/" + identifier + "/draft", headers={"Idempotency-Key": "fault-http"},
                json={"expected_revision": before["row_revision"], "patch": {"note": "已保存"}})
    else:
        response = _preview(client, context_runtime, identifier, patch={"note": "已保存"})
        assert response.status_code == 200, response.text
        plan = response.json()
        def perform():
            return _commit(client, plan, "fault-http")
    create = CashMutationRepository.create
    def fail(self, **kwargs):
        create(self, **kwargs)
        raise RuntimeError("private SQL password /tmp/private.csv")
    with monkeypatch.context() as fault:
        fault.setattr(CashMutationRepository, "create", fail)
        result = perform()
    assert result.status_code == 503 and result.json()["commit_state"] == "not_committed"
    assert "private" not in result.text and _records(context_runtime).read(identifier) == before
    commit = RelationalUnitOfWork.commit
    def lost(self):
        commit(self)
        raise RuntimeError("private lost response")
    with monkeypatch.context() as fault:
        fault.setattr(RelationalUnitOfWork, "commit", lost)
        result = perform()
    assert result.status_code == 503 and result.json()["commit_state"] == "unknown" and "private" not in result.text
    replay = perform()
    assert replay.status_code == 200, replay.text
    receipt = replay.json()["receipt"] if operation == "draft" else replay.json()
    assert client.get("/api/v1/cash-mutations/receipts/by-key/fault-http").json() == receipt
    assert _records(context_runtime).read(identifier)["fields"]["note"] == "已保存"


@pytest.mark.parametrize("operation", ["draft", "mutation"])
def test_existing_key_replay_read_failure_does_not_claim_not_committed(context_runtime, monkeypatch, operation):
    from ft.adapters.relational.cash_mutations import CashMutationRepository
    identifier = _draft(context_runtime) if operation == "draft" else _posted(context_runtime)
    client, _ = _client(context_runtime, monkeypatch)
    before = _records(context_runtime).read(identifier)
    if operation == "draft":
        def perform():
            return client.patch("/api/v1/cash-records/" + identifier + "/draft", headers={"Idempotency-Key": "replay-http"},
                json={"expected_revision": before["row_revision"], "patch": {"note": "已保存"}})
    else:
        response = _preview(client, context_runtime, identifier, patch={"note": "已保存"})
        assert response.status_code == 200, response.text
        def perform():
            return _commit(client, response.json(), "replay-http")
    assert perform().status_code == 200
    def fail(*_args, **_kwargs):
        raise RuntimeError("private replay query")
    with monkeypatch.context() as fault:
        fault.setattr(CashMutationRepository, "replay", fail)
        result = perform()
    assert result.status_code == 503 and result.json()["commit_state"] == "unknown" and "private" not in result.text


def test_maintenance_expiry_and_current_viewer_permission(context_runtime, monkeypatch):
    identifier = _posted(context_runtime)
    client, store = _client(context_runtime, monkeypatch)
    response = _preview(client, context_runtime, identifier, patch={"note": "允许的字段"})
    assert response.status_code == 200, response.text
    plan = response.json()
    with context_runtime.sessions.begin() as session:
        session.get(WorkspaceMembershipModel, (context_runtime.workspace_id, USER)).role = "viewer"
    detail = client.get("/api/v1/cash-records/" + identifier)
    assert detail.status_code == 200 and detail.json()["editable_fields"] == []
    assert _commit(client, plan).status_code == 403
    assert _preview(client, context_runtime, identifier).status_code == 403
    with context_runtime.sessions.begin() as session:
        session.get(WorkspaceMembershipModel, (context_runtime.workspace_id, USER)).role = "editor"
    current = store.get(plan["edit_context_id"], workspace_id=context_runtime.workspace_id, user_id=USER)
    store._clock = lambda: current.expires_at + timedelta(seconds=1)
    assert client.get("/api/v1/cash-mutations/plans/" + plan["plan_id"]).status_code == 410
    assert _commit(client, plan).status_code == 410


@pytest.mark.parametrize("changes", [
    {"expected_revision": True}, {"expected_revision": 1.0}, {"intent": "unknown"},
    {"intent": "resolve_issue", "issue_resolutions": []}, {"intent": "maintain_relations", "issue_resolutions": []},
    {"issue_resolutions": [{"issue_id": "1", "expected_issue_revision": 1, "resolution": "merge_into"}]},
    {"edit_context_id": "a" * 43}, {"operation_graph": []},
])
def test_maintenance_protocol_rejects_invalid_structure_before_publishing(context_runtime, monkeypatch, changes):
    identifier = _posted(context_runtime)
    client, store = _client(context_runtime, monkeypatch)
    response = _preview(client, context_runtime, identifier, **changes)
    assert response.status_code == 422 and response.json()["error"]["field_errors"] == []
    assert store._entries == {}


def test_fixed_financial_field_conflict_has_safe_business_field_error(context_runtime, monkeypatch):
    identifier = _posted(context_runtime)
    client, store = _client(context_runtime, monkeypatch)
    before = _records(context_runtime).read(identifier)
    response = _preview(client, context_runtime, identifier, patch={"amount": "-999"})
    assert response.status_code == 422 and response.json()["error"]["code"] == "fixed_field_conflict"
    assert response.json()["error"]["field_errors"][0]["field"] == "amount"
    assert "-999" not in response.text and _records(context_runtime).read(identifier) == before and store._entries == {}


def test_other_editor_cannot_read_or_commit_private_maintenance_context(context_runtime, monkeypatch):
    from ft.adapters.relational.models import UserModel
    identifier = _posted(context_runtime)
    first, store = _client(context_runtime, monkeypatch)
    response = _preview(first, context_runtime, identifier, patch={"note": "他人的未确认内容"})
    assert response.status_code == 200, response.text
    plan = response.json()
    other = "00000000-0000-4000-8000-000000000002"
    with context_runtime.sessions.begin() as session:
        session.add(UserModel(id=other, email="maintenance-other@example.test", password_hash="synthetic"))
        session.flush()
        session.add(WorkspaceMembershipModel(workspace_id=context_runtime.workspace_id, user_id=other, role="editor"))
    second, _ = _client(context_runtime, monkeypatch, store=store, user_id=other)
    assert second.get("/api/v1/cash-records/" + identifier).status_code == 200
    assert second.get("/api/v1/cash-mutations/plans/" + plan["plan_id"]).status_code == 404
    denied = _commit(second, plan)
    assert denied.status_code == 404 and denied.json()["error"]["retryable"] is False
    assert "他人的未确认内容" not in denied.text and _commit(first, plan).status_code == 200


def test_draft_saved_receipt_remains_success_when_current_read_fails(context_runtime, monkeypatch):
    from ft.application.cash_record_maintenance import CashRecordMaintenanceService
    identifier = _draft(context_runtime)
    client, _ = _client(context_runtime, monkeypatch)
    before = _records(context_runtime).read(identifier)
    def unreadable(*_args, **_kwargs):
        raise RuntimeError("private current read")
    with monkeypatch.context() as fault:
        fault.setattr(CashRecordMaintenanceService, "read", unreadable)
        response = client.patch("/api/v1/cash-records/" + identifier + "/draft", headers={"Idempotency-Key": "current-read-http"},
            json={"expected_revision": before["row_revision"], "patch": {"note": "已成功"}})
    assert response.status_code == 200 and response.json()["receipt"]["committed"]
    assert response.json()["current_record_status"] == "unavailable" and response.json()["current_record"] is None
    assert "private" not in response.text and _records(context_runtime).read(identifier)["fields"]["note"] == "已成功"


def test_joint_current_relation_confirmation_is_one_http_task_and_keeps_zero_result(context_runtime, monkeypatch):
    from tests.contract.test_cash_current_relation_plan import _start
    _start(context_runtime, joint=True)
    client, _ = _client(context_runtime, monkeypatch)
    response = _preview(client, context_runtime, "1101", intent="maintain_relations")
    assert response.status_code == 200, response.text
    first = response.json()
    groups = {}
    for item in first["items"]:
        detail = client.get("/api/v1/cash-mutations/plans/" + first["plan_id"] + "/items/" + item["preview_item_id"]).json()
        groups.update((group["suggestion_id"], group) for group in detail["relation_choices"] if group["kind"] == "payment_mirror")
    group, = groups.values()
    response = _preview(client, context_runtime, "1101", intent="maintain_relations", edit_context_id=first["edit_context_id"],
        previous_plan_id=first["plan_id"], expected_plan_revision=first["plan_revision"],
        relation_choices=[{"suggestion_id": group["suggestion_id"], "action": "accept"}])
    assert response.status_code == 200, response.text
    confirmed = _commit(client, response.json())
    assert confirmed.status_code == 200 and confirmed.json()["summary"]["relations_confirmed"] == 1
    records = [client.get("/api/v1/cash-records/" + str(identifier)).json() for identifier in range(1101, 1105)]
    assert len({record["projection_ref"]["projection_id"] for record in records}) == 1
    assert all(record["confirmation_status"] == "confirmed" for record in records)
    projected = client.get("/api/v1/cash-projections").json()["items"]
    assert any(item["projection_id"] == records[0]["projection_ref"]["projection_id"] and Decimal(item["amount"]) == 0
        for item in projected)


def test_identity_merge_http_returns_retained_target_and_preserves_original_result_reference(context_runtime, monkeypatch):
    from tests.contract.test_cash_draft_merge_plan import _choice, _pair
    source, target = _pair(context_runtime)
    client, _ = _client(context_runtime, monkeypatch)
    detail = client.get("/api/v1/cash-records/" + source).json()
    issue = next(issue for issue in detail["issues"] if issue["code"] == "source_identity_ambiguous")
    assert any(candidate["transaction_id"] == target for candidate in issue["candidates"])
    response = _preview(client, context_runtime, source, intent="resolve_issue", issue_resolutions=[_choice(context_runtime, source, target)])
    assert response.status_code == 200, response.text
    committed = _commit(client, response.json())
    assert committed.status_code == 200 and committed.json()["retained_transaction_id"] == target
    assert committed.json()["summary"]["merged_draft"] == 1
    assert client.get("/api/v1/cash-records/" + source).status_code == 404
    rows = client.get("/api/v1/cash-mutations/receipts/" + committed.json()["receipt_id"] + "/rows").json()["items"]
    assert rows[0]["item_ref"] == source and rows[0]["transaction_id"] == target


def test_record_state_missing_key_and_stale_revision_have_distinct_safe_errors(context_runtime, monkeypatch):
    identifier = _posted(context_runtime)
    client, _ = _client(context_runtime, monkeypatch)
    detail = _records(context_runtime).read(identifier)
    missing = client.get("/api/v1/cash-records/9223372036854775807")
    assert missing.status_code == 404 and missing.json()["error"]["code"] == "record_not_found"
    path = "/api/v1/cash-records/" + identifier + "/draft"
    body = {"expected_revision": detail["row_revision"], "patch": {"note": "不应写入"}}
    assert client.patch(path, json=body).status_code == 422
    posted = client.patch(path, json=body, headers={"Idempotency-Key": "posted-is-not-draft"})
    assert posted.status_code == 409 and posted.json()["error"]["code"] == "draft_required"
    stale = _preview(client, context_runtime, identifier, expected_revision=detail["row_revision"] + 1)
    assert stale.status_code == 409 and stale.json()["error"]["code"] == "record_changed"
    assert _records(context_runtime).read(identifier) == detail


def test_incomplete_draft_stays_editable_and_does_not_publish_finalize_plan(context_runtime, monkeypatch):
    identifier = _draft(context_runtime)
    client, store = _client(context_runtime, monkeypatch)
    before = _records(context_runtime).read(identifier)
    response = _preview(client, context_runtime, identifier, intent="finalize_draft")
    assert response.status_code == 422 and response.json()["error"]["code"] == "account_missing"
    assert "账户" in response.json()["error"]["message"]
    assert _records(context_runtime).read(identifier) == before and store._entries == {}
