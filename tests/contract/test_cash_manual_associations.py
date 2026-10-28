"""主动关联仍走完整预览、财务校验与同事务确认。"""
from datetime import datetime, timezone
from decimal import Decimal

import pytest
from sqlalchemy import select

from ft.adapters.relational.models import CashTransactionModel, TransactionRelationModel
from ft.application.cash_projections import CashProjectionService
from tests.contract.test_cash_current_relation_plan import _facts
from tests.contract.test_cash_mutation_business_plan import _preview
from tests.contract.test_cash_mutation_commit import _commit, _service
from tests.contract.test_cash_record_maintenance import _service as records
from tests.contract.test_parsed_cash_import_context import context_runtime
from tests.contract.test_cash_import_session_api import _client


def setup_records(runtime):
    _facts(runtime)
    with runtime.sessions.begin() as session:
        target = session.get(CashTransactionModel, 1102)
        target.occurred_at = datetime(2025, 1, 1, tzinfo=timezone.utc)
        target.counterparty = "自由搜索商户"
        target.note = "原始账单备注"
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()


def addition(runtime, **changes):
    return {"target_transaction_id": "1102", "expected_target_revision": records(runtime).read("1102")["row_revision"],
        "anchor_component_id": "6101", "target_component_id": "6102", "kind": "payment_mirror", "subtype": "", **changes}


def test_search_returns_actual_import_members_beyond_match_window(context_runtime):
    runtime = context_runtime
    setup_records(runtime)
    found = records(runtime).association_targets("1101", query="原始账单备注", amount="100.00")
    assert [row["transaction_id"] for row in found["items"]] == ["1102"]
    assert found["items"][0]["row_revision"] > 0
    assert Decimal(found["items"][0]["fields"]["amount"]) == Decimal("-100.00")
    assert found["items"][0]["fields"]["components"][0]["component_id"] == "6102"
    assert records(runtime).association_targets("1101", query="自由搜索", account_id="999999")["items"] == []


def test_search_excludes_manual_and_current_and_supports_pagination(context_runtime):
    runtime = context_runtime
    setup_records(runtime)
    with runtime.sessions.begin() as session:
        session.get(CashTransactionModel, 1105).record_origin = "manual"
    first = records(runtime).association_targets("1101", limit=1)
    assert len(first["items"]) == 1 and first["next_cursor"]
    second = records(runtime).association_targets("1101", limit=10, cursor=first["next_cursor"])
    ids = [row["transaction_id"] for row in first["items"] + second["items"]]
    assert len(ids) == len(set(ids)) and "1101" not in ids and "1105" not in ids


def test_manual_association_previews_and_commits_outside_candidates_without_changing_sources(context_runtime):
    runtime = context_runtime
    setup_records(runtime)
    service = _service(runtime)
    before = {identifier: records(runtime).read(identifier)["fields"] for identifier in ("1101", "1102")}
    plan = _preview(service, runtime, "1101", intent="maintain_relations", relation_additions=[addition(runtime)])
    assert plan["can_commit"] and len(plan["items"]) == 1
    assert set(ref["transaction_id"] for ref in plan["items"][0]["member_refs"]) == {"1101", "1102"}
    with runtime.sessions() as session:
        assert list(session.scalars(select(TransactionRelationModel))) == []
    receipt = _commit(service, plan, "manual-association-idempotency")
    assert receipt["committed"]
    assert _commit(service, plan, "manual-association-idempotency") == receipt
    with runtime.sessions() as session:
        relations = list(session.scalars(select(TransactionRelationModel)))
        assert len(relations) == 1 and relations[0].state == "confirmed" and relations[0].decision_source == "manual"
    assert {identifier: records(runtime).read(identifier)["fields"] for identifier in before} == before


@pytest.mark.parametrize("changes", [
    {"target_transaction_id": "1101"}, {"expected_target_revision": 999},
    {"target_component_id": "6103"}, {"kind": "transfer_pair", "subtype": "ordinary_transfer"},
    {"kind": "refund_offset"}, {"primary_role": "expense"},
])
def test_invalid_manual_input_is_rejected_without_partial_success(context_runtime, changes):
    runtime = context_runtime
    setup_records(runtime)
    with pytest.raises(ValueError):
        _preview(_service(runtime), runtime, "1101", intent="maintain_relations", relation_additions=[addition(runtime, **changes)])
    with runtime.sessions() as session:
        assert list(session.scalars(select(TransactionRelationModel))) == []


def test_target_change_after_preview_rejects_commit(context_runtime):
    runtime = context_runtime
    setup_records(runtime)
    service = _service(runtime)
    plan = _preview(service, runtime, "1101", intent="maintain_relations", relation_additions=[addition(runtime)])
    with runtime.sessions.begin() as session:
        session.get(CashTransactionModel, 1102).row_revision += 1
    with pytest.raises(ValueError):
        _commit(service, plan, "changed-manual-target")
    with runtime.sessions() as session:
        assert list(session.scalars(select(TransactionRelationModel))) == []


def test_http_search_and_association_use_explicit_target_contract(context_runtime, monkeypatch):
    runtime = context_runtime
    setup_records(runtime)
    client, _ = _client(runtime, monkeypatch)
    response = client.get("/api/v1/cash-records/1101/association-targets", params={"query": "自由搜索", "amount": "100.00"})
    assert response.status_code == 200, response.text
    assert [item["transaction_id"] for item in response.json()["items"]] == ["1102"]
    response = client.post("/api/v1/cash-mutations/plans", json={"intent": "maintain_relations", "transaction_id": "1101",
        "expected_revision": records(runtime).read("1101")["row_revision"], "relation_additions": [addition(runtime)]})
    assert response.status_code == 200, response.text
    assert response.json()["can_commit"]


def test_search_uses_explicit_local_calendar_day(context_runtime):
    runtime = context_runtime
    setup_records(runtime)
    with runtime.sessions.begin() as session:
        session.get(CashTransactionModel, 1102).occurred_at = datetime(2024, 12, 31, 17, tzinfo=timezone.utc)
    found = records(runtime).association_targets("1101", date_from="2025-01-01", date_to="2025-01-01", timezone_name="Asia/Shanghai")
    assert "1102" in [row["transaction_id"] for row in found["items"]]


def test_manual_association_can_explicitly_clear_conflicting_public_note(context_runtime):
    from ft.adapters.relational.cash_entries import CashEntryRepository
    runtime = context_runtime
    setup_records(runtime)
    with runtime.sessions.begin() as session:
        entries = CashEntryRepository(session, runtime.workspace_id)
        for identifier, note in ((1101, "人工甲"), (1102, "人工乙")):
            current = entries.current(identifier)
            entries.patch(identifier, {"note": note}, expected_revision=current.revision)
    service = _service(runtime)
    pending = _preview(service, runtime, "1101", intent="maintain_relations", relation_additions=[addition(runtime)])
    details = [service.item(pending["plan_id"], item["preview_item_id"]) for item in pending["items"]]
    assert any(choice.get("common_field_conflicts", {}).get("note") for detail in details for choice in detail["relation_choices"])
    plan = _preview(service, runtime, "1101", intent="maintain_relations", relation_additions=[addition(runtime, common_fields={"note": ""})])
    assert plan["can_commit"]
    assert _commit(service, plan, "manual-clear-public-note")["committed"]
    assert records(runtime).read("1101")["entry"]["fields"]["note"] == ""
