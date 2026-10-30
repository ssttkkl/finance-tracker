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
        "anchor_component_id": "6101", "target_component_id": "6102", "kind": "payment_mirror",  **changes}


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
    {"target_component_id": "6103"}, {"kind": "transfer_pair"},
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


def test_search_kind_filters_drafts_and_returns_component_choices(context_runtime):
    runtime = context_runtime
    setup_records(runtime)
    with runtime.sessions.begin() as session:
        session.get(CashTransactionModel, 1105).state = 'draft'
    found = records(runtime).association_targets('1101', kind='payment_mirror', query='自由搜索')
    assert found['kind'] == 'payment_mirror'
    assert [row['transaction_id'] for row in found['items']] == ['1102']
    assert found['items'][0]['component_choices'] == [{'anchor_component_id': '6101', 'target_component_id': '6102'}]
    assert found['items'][0]['unavailable_reason'] is None
    refund = records(runtime).association_targets('1101', kind='refund_offset')
    assert {row['transaction_id'] for row in refund['items']} == {'1103', '1104'}
    assert records(runtime).association_targets('1101', kind='transfer_pair')['items'] == []


def test_search_http_rejects_invalid_kind(context_runtime, monkeypatch):
    runtime = context_runtime
    setup_records(runtime)
    client, _ = _client(runtime, monkeypatch)
    response = client.get('/api/v1/cash-records/1101/association-targets', params={'kind': 'unknown'})
    assert response.status_code == 422
    assert response.json()['error']['code'] == 'record_input_invalid'


@pytest.mark.parametrize('source_id,target_id', [('1101', '1102'), ('1102', '1101')])
def test_search_and_manual_preview_support_zero_refund_in_both_directions(context_runtime, source_id, target_id):
    from ft.adapters.relational.models import CashTransactionComponentModel
    runtime = context_runtime
    setup_records(runtime)
    with runtime.sessions.begin() as session:
        for identifier, record_type in ((1101, 'consumption'), (1102, 'refund')):
            row = session.get(CashTransactionModel, identifier)
            row.amount, row.record_type = Decimal('0'), record_type
            session.get(CashTransactionComponentModel, identifier + 5000).amount = Decimal('0')
    found = records(runtime).association_targets(source_id, kind='refund_offset')
    target = next(row for row in found['items'] if row['transaction_id'] == target_id)
    anchor, other = str(int(source_id) + 5000), str(int(target_id) + 5000)
    assert target['component_choices'] == [{'anchor_component_id': anchor, 'target_component_id': other}]
    plan = _preview(_service(runtime), runtime, source_id, intent='maintain_relations', relation_additions=[
        addition(runtime, kind='refund_offset', target_transaction_id=target_id,
            expected_target_revision=records(runtime).read(target_id)['row_revision'],
            anchor_component_id=anchor, target_component_id=other)])
    assert plan['can_commit']


def test_search_aggregate_account_filter_uses_components_before_pagination(context_runtime):
    from ft.adapters.relational.models import CashTransactionComponentModel
    runtime = context_runtime
    setup_records(runtime)
    with runtime.sessions.begin() as session:
        row = session.get(CashTransactionModel, 1102)
        row.account_id, row.cash_granularity, row.amount = None, 'aggregate', Decimal('-130')
        session.add(CashTransactionComponentModel(id=6202, workspace_id=runtime.workspace_id,
            cash_transaction_id=1102, account_id=102, amount=Decimal('-30'), currency='CNY', ordinal=1))
    found = records(runtime).association_targets('1101', kind='payment_mirror', account_id='101', amount='130', limit=1)
    assert [row['transaction_id'] for row in found['items']] == ['1102']
    assert found['items'][0]['component_choices'] == [{'anchor_component_id': '6101', 'target_component_id': '6102'}]
    assert records(runtime).association_targets('1101', query='自由搜索', account_id='101', amount='100')['items'] == []


@pytest.mark.parametrize('funded_id', [1101, 1102])
def test_search_does_not_offer_funding_occupied_members(context_runtime, funded_id):
    from ft.adapters.relational.models import CashInvestmentFundingRelationModel, CashTransactionComponentModel, InvestmentEventModel
    from ft.domain.record_type import default_cash_record_subtype
    runtime = context_runtime
    setup_records(runtime)
    with runtime.sessions.begin() as session:
        for identifier, record_type, amount in ((1101, 'transfer_out', '-100'), (1102, 'transfer_in', '100')):
            row = session.get(CashTransactionModel, identifier)
            row.record_type, row.record_subtype, row.amount = record_type, default_cash_record_subtype(record_type), Decimal(amount)
            session.get(CashTransactionComponentModel, identifier + 5000).amount = Decimal(amount)
        investment = InvestmentEventModel(workspace_id=runtime.workspace_id, account_id=103, record_type='funding', record_subtype='external',
            currency='CNY', occurred_at=datetime(2026, 1, 1, tzinfo=timezone.utc), payload={}, to_amount=Decimal('100'))
        session.add(investment)
        session.flush()
        session.add(CashInvestmentFundingRelationModel(workspace_id=runtime.workspace_id, cash_transaction_component_id=funded_id + 5000,
            investment_event_id=investment.id, direction='cash_to_investment' if funded_id == 1101 else 'investment_to_cash',
            status='accepted', active_slot='active', rule_id='fixture'))
    target, = records(runtime).association_targets('1101', kind='transfer_pair', query='自由搜索')['items']
    assert target['component_choices'] == []
    assert target['unavailable_reason'] == 'relation_endpoint_occupied'


@pytest.mark.parametrize('params', [{'subtype': 'ordinary_transfer'}, {'knd': 'refund_offset'}, [('kind', 'payment_mirror'), ('kind', 'refund_offset')]])
def test_search_http_rejects_unknown_or_duplicate_filters(context_runtime, monkeypatch, params):
    runtime = context_runtime
    setup_records(runtime)
    client, _ = _client(runtime, monkeypatch)
    response = client.get('/api/v1/cash-records/1101/association-targets', params=params)
    assert response.status_code == 422
    assert response.json()['error']['code'] == 'record_input_invalid'


def test_search_funding_occupation_blocks_all_parts_of_an_aggregate_parent():
    from types import SimpleNamespace
    from ft.application.cash_manual_associations import _component_choices
    source = SimpleNamespace(id=1, cash_granularity='aggregate')
    target = SimpleNamespace(id=2)
    parts = {1: [SimpleNamespace(id=11), SimpleNamespace(id=12)], 2: [SimpleNamespace(id=21)]}
    assert _component_choices(source, target, parts, 'refund_offset', {1}) == {
        'component_choices': [], 'unavailable_reason': 'relation_endpoint_occupied'}


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


@pytest.mark.parametrize("out_type,in_type", [("refund", "refund"), ("investment_out", "refund"),
    ("transfer_reversal", "transfer_reversal"), ("withdrawal_out", "repayment")])
def test_manual_no_proof_relationship_uses_common_rules_at_preview_and_commit(context_runtime, out_type, in_type):
    from ft.adapters.relational.models import CashTransactionComponentModel
    from ft.domain.record_type import default_cash_record_subtype
    runtime = context_runtime
    setup_records(runtime)
    transfer = out_type == "withdrawal_out"
    with runtime.sessions.begin() as session:
        for identifier, record_type, amount in ((1101, out_type, "-100"), (1102, in_type, "100" if transfer else "30")):
            row = session.get(CashTransactionModel, identifier)
            row.record_type = record_type
            row.record_subtype = default_cash_record_subtype(record_type)
            row.amount = Decimal(amount)
            session.get(CashTransactionComponentModel, identifier + 5000).amount = Decimal(amount)
    service = _service(runtime)
    plan = _preview(service, runtime, "1101", intent="maintain_relations", relation_additions=[addition(runtime,
        kind="transfer_pair" if transfer else "refund_offset")])
    assert plan["can_commit"]
    assert _commit(service, plan, "manual-common-rules")["committed"]
