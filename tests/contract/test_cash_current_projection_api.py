"""当前展示与稳定流水定位不依赖过期的预览或旧分组。"""
from datetime import datetime, timezone
from decimal import Decimal

import pytest
from sqlalchemy import select

from ft.adapters.relational.models import CashProjectionStateModel, CashTransactionModel, WorkspaceModel
from ft.application.cash_projections import CashProjectionService
from tests.contract.test_cash_current_relation_plan import _start
from tests.contract.test_cash_import_commit import configured
from tests.contract.test_cash_import_session_api import _client
from tests.contract.test_cash_maintenance_api import _commit, _preview
from tests.contract.test_cash_mutation_business_plan import _posted
from tests.contract.test_cash_record_maintenance import _draft, _service as _records
from tests.contract.test_cash_source_issue_resolution import _issue
from tests.contract.test_parsed_cash_import_context import context_runtime


def _locate(client, identifier):
    return client.get("/api/v1/cash-records/" + str(identifier) + "/projection")


def test_unknown_draft_without_components_has_one_current_projection_and_no_statistics(context_runtime, monkeypatch):
    identifier = _draft(context_runtime)
    records = _records(context_runtime)
    before = records.read(identifier)
    records.save_draft(identifier, expected_revision=before["row_revision"], patch={
        "amount": None, "currency": None, "occurred_at": None, "components": []}, idempotency_key="unknown-projection")
    client, _ = _client(context_runtime, monkeypatch)
    located = _locate(client, identifier)
    assert located.status_code == 200, located.text
    location = located.json()
    response = client.get("/api/v1/cash-projections/" + location["projection_id"])
    assert response.status_code == 200, response.text
    current = response.json()
    projection = current["projection"]
    assert current["projection_version"] == projection["projection_version"] == location["projection_version"]
    assert projection["state"] == "draft" and projection["confirmation_status"] == "pending"
    assert projection["amount"] is None and projection["occurred_at"] is None and projection["statistics_eligible"] is False
    assert current["members"] == [{"transaction_id": identifier, "row_revision": records.read(identifier)["row_revision"],
        "state": "draft", "confirmation_status": "pending"}]
    assert all(issue["transaction_id"] == identifier and issue["message"] for issue in current["issues"])
    for private in ("pending_fields", "pending_source_payload", "source_payload", "relation_metadata", "manual_overrides", "candidate_set"):
        assert private not in response.text


def test_projection_detail_matches_list_and_only_exposes_current_safe_issues(context_runtime, monkeypatch):
    identifier = _posted(context_runtime)
    choice = _issue(context_runtime, identifier, fields={"counterparty": "私有待采用正文"}, payload={"备注": "私有来源原文"})
    CashProjectionService(context_runtime.sessions, context_runtime.workspace_id).rebuild()
    client, _ = _client(context_runtime, monkeypatch)
    location = _locate(client, identifier).json()
    response = client.get("/api/v1/cash-projections/" + location["projection_id"])
    assert response.status_code == 200, response.text
    current = response.json()
    listed = next(item for item in client.get("/api/v1/cash-projections").json()["items"]
        if item["projection_id"] == location["projection_id"])
    assert current["projection"] == listed
    assert current["projection"]["confirmation_status"] == "pending" and location["attention_count"] > 0
    assert current["issues"][0]["issue_id"] == choice["issue_id"]
    assert current["issues"][0]["revision"] == choice["expected_issue_revision"]
    assert "私有" not in response.text and current["members"][0]["confirmation_status"] == "pending"


def test_joint_refund_confirmation_and_dissolution_relocate_every_stable_member(context_runtime, monkeypatch):
    _start(context_runtime, joint=True)
    client, _ = _client(context_runtime, monkeypatch)
    initial = [_locate(client, identifier).json() for identifier in range(1101, 1105)]
    first = _preview(client, context_runtime, "1101", intent="maintain_relations").json()
    groups = {}
    for item in first["items"]:
        detail = client.get("/api/v1/cash-mutations/plans/" + first["plan_id"] + "/items/" + item["preview_item_id"]).json()
        groups.update((group["suggestion_id"], group) for group in detail["relation_choices"] if group["kind"] == "payment_mirror")
    group, = groups.values()
    confirmed = _preview(client, context_runtime, "1101", intent="maintain_relations", edit_context_id=first["edit_context_id"],
        previous_plan_id=first["plan_id"], expected_plan_revision=first["plan_revision"],
        relation_choices=[{"suggestion_id": group["suggestion_id"], "action": "accept"}]).json()
    assert _commit(client, confirmed).status_code == 200
    current = [_locate(client, identifier).json() for identifier in range(1101, 1105)]
    assert len({item["projection_id"] for item in current}) == 1
    merged = client.get("/api/v1/cash-projections/" + current[0]["projection_id"])
    assert merged.status_code == 200 and Decimal(merged.json()["projection"]["amount"]) == 0
    assert merged.json()["projection"]["visible"] and len(merged.json()["members"]) == 4
    assert merged.json()["projection"]["confirmation_status"] == "confirmed"
    assert all(client.get("/api/v1/cash-projections/" + item["projection_id"]).status_code == 404
        for item in initial if item["projection_id"] != current[0]["projection_id"])
    editing = _preview(client, context_runtime, '1101', intent='maintain_relations').json()
    choices = {}
    for item in editing['items']:
        detail = client.get('/api/v1/cash-mutations/plans/' + editing['plan_id'] + '/items/' + item['preview_item_id']).json()
        choices.update((group['suggestion_id'], {'suggestion_id': group['suggestion_id'], 'action': 'remove'})
            for group in detail['relation_choices'] if 'remove' in group['allowed_actions'])
    response = _preview(client, context_runtime, '1101', intent='maintain_relations', edit_context_id=editing['edit_context_id'],
        previous_plan_id=editing['plan_id'], expected_plan_revision=editing['plan_revision'], relation_choices=list(choices.values()))
    assert response.status_code == 200 and _commit(client, response.json(), "split-current-projection").status_code == 200
    after = [_locate(client, identifier).json() for identifier in range(1101, 1105)]
    assert len({item["projection_id"] for item in after}) > 1
    assert all(item["projection_version"] > current[0]["projection_version"] for item in after)
    stale = client.get("/api/v1/cash-projections/" + current[0]["projection_id"])
    if current[0]["projection_id"] in {item["projection_id"] for item in after}:
        assert stale.status_code == 200 and stale.json()["projection_version"] == after[0]["projection_version"]
        assert len(stale.json()["members"]) < 4
    else:
        assert stale.status_code == 404 and stale.json()["error"]["code"] == "projection_not_found"


def test_deleted_and_cross_workspace_targets_cannot_be_read_or_relocated(context_runtime, monkeypatch):
    identifier = _posted(context_runtime)
    client, _ = _client(context_runtime, monkeypatch)
    location = _locate(client, identifier).json()
    with context_runtime.sessions.begin() as session:
        session.get(CashTransactionModel, int(identifier)).deleted_at = datetime.now(timezone.utc)
    assert _locate(client, identifier).status_code == 404
    assert client.get("/api/v1/cash-projections/" + location["projection_id"]).status_code == 404
    with context_runtime.sessions.begin() as session:
        session.add(WorkspaceModel(id="private-other-workspace", name="other"))
        session.flush()
        other = CashTransactionModel(workspace_id="private-other-workspace", source_type="fixture", record_id="other-private",
            state="draft", record_type=None, record_subtype=None, amount=None, currency=None, occurred_at=None)
        session.add(other)
        session.flush()
        other_id = str(other.id)
    CashProjectionService(context_runtime.sessions, "private-other-workspace").rebuild()
    from ft.application.web_queries import CashLedgerQueryService
    other_projection = CashLedgerQueryService(context_runtime.sessions, "private-other-workspace").list_cash_projections().items[0].projection_id
    assert _locate(client, other_id).status_code == 404
    assert client.get("/api/v1/cash-projections/" + other_projection).status_code == 404


def test_unready_current_dataset_does_not_return_old_projection_or_location(context_runtime, monkeypatch):
    identifier = _posted(context_runtime)
    client, _ = _client(context_runtime, monkeypatch)
    location = _locate(client, identifier).json()
    with context_runtime.sessions.begin() as session:
        state = session.scalar(select(CashProjectionStateModel).where(CashProjectionStateModel.workspace_id == context_runtime.workspace_id))
        state.availability = "uninitialized"
    assert _locate(client, identifier).status_code == 503
    response = client.get("/api/v1/cash-projections/" + location["projection_id"])
    assert response.status_code == 503 and response.json()["error"]["retryable"] is True


def test_active_member_without_current_projection_fails_closed(context_runtime, monkeypatch):
    from ft.adapters.relational.models import CashProjectionMemberModel
    from sqlalchemy import delete
    identifier = _posted(context_runtime)
    client, _ = _client(context_runtime, monkeypatch)
    location = _locate(client, identifier).json()
    with context_runtime.sessions.begin() as session:
        session.execute(delete(CashProjectionMemberModel).where(CashProjectionMemberModel.workspace_id == context_runtime.workspace_id,
            CashProjectionMemberModel.cash_transaction_id == int(identifier)))
    response = _locate(client, identifier)
    assert response.status_code == 503 and response.json()["error"]["retryable"] is True
    assert client.get("/api/v1/cash-projections/" + location["projection_id"]).status_code == 503


def test_business_hidden_projection_only_opens_when_current_attention_makes_it_maintainable(context_runtime, monkeypatch):
    from ft.adapters.relational.models import CashTransactionComponentModel
    identifier = _posted(context_runtime)
    with context_runtime.sessions.begin() as session:
        session.get(CashTransactionModel, int(identifier)).amount = Decimal("0")
        for part in session.scalars(select(CashTransactionComponentModel).where(
            CashTransactionComponentModel.cash_transaction_id == int(identifier))):
            part.amount = Decimal("0")
    CashProjectionService(context_runtime.sessions, context_runtime.workspace_id).rebuild()
    client, _ = _client(context_runtime, monkeypatch)
    location = _locate(client, identifier).json()
    path = "/api/v1/cash-projections/" + location["projection_id"]
    hidden = client.get(path)
    assert hidden.status_code == 404 and hidden.json()["error"]["code"] == "projection_not_found"
    _issue(context_runtime, identifier, fields={"note": "待采用"})
    CashProjectionService(context_runtime.sessions, context_runtime.workspace_id).rebuild()
    response = client.get(path)
    assert response.status_code == 200 and response.json()["projection"]["visible"] is False
    assert response.json()["projection"]["confirmation_status"] == "pending"
    assert Decimal(response.json()["projection"]["amount"]) == 0


def test_current_projection_read_storage_fault_has_safe_retryable_error(context_runtime, monkeypatch):
    from ft.adapters.relational.runtime import StorageError
    from ft.adapters.relational.web_queries import RelationalCashLedgerQueryRepository
    identifier = _posted(context_runtime)
    client, _ = _client(context_runtime, monkeypatch)
    location = _locate(client, identifier).json()
    def unavailable(*_args, **_kwargs):
        raise StorageError("storage.connect")
    monkeypatch.setattr(RelationalCashLedgerQueryRepository, "_active", unavailable)
    for path in ("/api/v1/cash-projections/" + location["projection_id"], "/api/v1/cash-records/" + identifier + "/projection"):
        response = client.get(path)
        assert response.status_code == 503 and response.json()["error"]["retryable"] is True
        assert response.json()["error"]["field_errors"] == [] and "storage.connect" not in response.text
