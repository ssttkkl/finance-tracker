from datetime import datetime, timezone
from decimal import Decimal

import pytest
from sqlalchemy import event, select

from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.cash_sources import CashSourceRepository
from ft.adapters.relational.models import CashCategoryModel, CashProjectionModel, CashProjectionStateModel, CashTransactionModel
from ft.adapters.relational.projections import RelationalCashProjectionRepository
from ft.application.cash_projections import CashProjectionService
from ft.application.web_queries import CashLedgerQueryService
from tests.contract.test_cash_relation_context import _add, relation_context_runtime


CREATED = datetime(2026, 8, 1, tzinfo=timezone.utc)


def _drafts(runtime):
    with runtime.sessions.begin() as session:
        for identifier, amount, currency in ((1201, None, None), (1202, Decimal("-999"), "CNY")):
            session.add(CashTransactionModel(id=identifier, workspace_id=runtime.workspace_id, state="draft",
                account_id=None, amount=amount, currency=currency, occurred_at=None, created_at=CREATED,
                record_type=None, record_subtype=None, source_type="lifecycle-fixture", record_id=str(identifier)))


def _current(session, workspace, parent_id):
    return session.scalar(select(CashProjectionModel).join(CashProjectionStateModel,
        CashProjectionModel.dataset_id == CashProjectionStateModel.active_dataset_id).where(
            CashProjectionModel.workspace_id == workspace, CashProjectionModel.root_cash_transaction_id == parent_id))


def test_sources_keep_no_component_drafts_and_current_attention(relation_context_runtime):
    runtime = relation_context_runtime
    _drafts(runtime)
    with runtime.sessions.begin() as session:
        sources = CashSourceRepository(session, runtime.workspace_id)
        sources.save_issue(1101, "current", code="source_update_conflict")
        repo = RelationalCashProjectionRepository(session, runtime.workspace_id)
        assert repo.confirmed_transaction_closure({1201}) == {1201}
        facts, edges = repo.read_sources_for_facts({1101, 1201, 1202})
        by_id = {fact.id: fact for fact in facts}
        assert set(by_id) == {1101, 1201, 1202} and edges == ()
        assert by_id[1101].needs_attention
        assert by_id[1201].state == "draft" and by_id[1201].amount is None and by_id[1201].occurred_at is None
        assert by_id[1201].created_at == CREATED and by_id[1202].amount == Decimal("-999")


def test_local_publication_does_not_drop_empty_drafts_or_independent_attention(relation_context_runtime):
    runtime = relation_context_runtime
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    _drafts(runtime)
    with runtime.sessions.begin() as session:
        CashProjectionService.maintain_in_session(session, runtime.workspace_id, {1201, 1202})
        for identifier in (1201, 1202):
            row = _current(session, runtime.workspace_id, identifier)
            assert row is not None and row.state == "draft" and not row.statistics_eligible
            assert row.sort_at == CREATED and row.attention_count == 1
        sources = CashSourceRepository(session, runtime.workspace_id)
        sources.save_issue(1101, "current", code="source_update_conflict")
        parent = session.get(CashTransactionModel, 1101)
        CashProjectionService.replace_standalone_fact_if_ready_in_session(session, runtime.workspace_id, parent)
        assert _current(session, runtime.workspace_id, 1101).attention_count == 1


def test_pending_edges_publish_separate_formal_members_until_confirmed(relation_context_runtime):
    runtime = relation_context_runtime
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    with runtime.sessions.begin() as session:
        relations = CurrentCashRelationRepository(session, runtime.workspace_id)
        edge = _add(relations, 1101, 1102)
        CashProjectionService.maintain_in_session(session, runtime.workspace_id, {1101, 1102})
        for identifier in (1101, 1102):
            row = _current(session, runtime.workspace_id, identifier)
            assert row.member_count == 1 and row.attention_count == 1 and row.statistics_eligible
        relations.replace(edge["id"], {"state": "confirmed"}, expected_revision=1)
        CashProjectionService.maintain_in_session(session, runtime.workspace_id, {1101, 1102})
        row = _current(session, runtime.workspace_id, 1101)
        assert row.member_count == 2 and row.attention_count == 0 and row.net_amount == Decimal("-100")


@pytest.mark.parametrize("change", ["state", "created_at", "issue_add", "issue_update", "issue_clear", "issue_recreate", "draft_edge"])
def test_publication_digest_tracks_lifecycle_changes(relation_context_runtime, change):
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        sources = CashSourceRepository(session, runtime.workspace_id)
        if change in {"issue_update", "issue_clear", "issue_recreate"}:
            sources.save_issue(1101, "current", code="source_update_conflict")
        repo = RelationalCashProjectionRepository(session, runtime.workspace_id)
        before = repo.source_digest()
        if change == "state":
            session.get(CashTransactionModel, 1101).state = "draft"
        elif change == "created_at":
            session.get(CashTransactionModel, 1101).created_at = CREATED
        elif change == "draft_edge":
            _add(CurrentCashRelationRepository(session, runtime.workspace_id), 1101, 1102)
        else:
            if change in {"issue_clear", "issue_recreate"}:
                sources.clear_issue(1101, "current")
            if change != "issue_clear":
                sources.save_issue(1101, "current", code="source_update_conflict", pending_fields={"note": change})
        session.flush()
        assert repo.source_digest() != before


def test_repair_cannot_publish_before_an_issue_change_without_a_parent_revision(relation_context_runtime):
    from ft.domain.cash_projection import build_cash_projections
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        repo = RelationalCashProjectionRepository(session, runtime.workspace_id)
        digest = repo.source_digest()
        facts, relations = repo.read_sources()
        dataset = repo.create_staging_dataset(source_digest=digest, rules_version="cash-projection-v1")
        repo.replace_dataset(dataset, build_cash_projections(facts, relations), projection_version=1)
        revision = session.get(CashTransactionModel, 1101).row_revision
        CashSourceRepository(session, runtime.workspace_id).save_issue(1101, "new", code="source_update_conflict")
        assert session.get(CashTransactionModel, 1101).row_revision == revision
        with pytest.raises(RuntimeError, match="projection.concurrent_update"):
            repo.publish_dataset(dataset, source_digest=digest, rules_version="cash-projection-v1")


def test_default_page_keeps_unknown_drafts_but_all_summaries_exclude_them(relation_context_runtime):
    runtime = relation_context_runtime
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    service = CashLedgerQueryService(runtime.sessions, runtime.workspace_id)
    before = service.list_cash_projections()
    _drafts(runtime)
    with runtime.sessions.begin() as session:
        CashProjectionService.maintain_in_session(session, runtime.workspace_id, {1201, 1202})
    page = service.list_cash_projections()
    drafts = {item.projection_id: item for item in page.items if item.state == "draft"}
    assert set(drafts) == {"cash:1201", "cash:1202"}
    assert drafts["cash:1201"].occurred_at is None and drafts["cash:1201"].amount is None
    assert drafts["cash:1201"].currency is None and drafts["cash:1201"].economic_type is None
    assert all(item.confirmation_status == "pending" and item.attention_count == 1 and not item.statistics_eligible for item in drafts.values())
    assert page.pending_item_count == 2 and page.monthly_summaries == before.monthly_summaries
    assert service.list_cash_projections(state="draft").monthly_summaries == ()
    assert all(item.state == "posted" for item in service.list_cash_projections(state="posted").items)
    assert all(item.confirmation_status == "confirmed" for item in service.list_cash_projections(confirmation_status="confirmed").items)
    assert all(item.projection_version == page.projection_version for item in page.items)
    evidence = service.get_projection_evidence("cash:1201")
    assert evidence["root_record"]["occurred_at"] is None and evidence["root_record"]["amount"] is None
    assert evidence["root_record"]["account"] is None
    assert evidence["projection"].projection_version == evidence["projection_version"] == page.projection_version


def test_unknown_dates_paginate_by_stable_created_position(relation_context_runtime):
    runtime = relation_context_runtime
    _drafts(runtime)
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    service = CashLedgerQueryService(runtime.sessions, runtime.workspace_id)
    items, cursor = [], None
    while True:
        page = service.list_cash_projections(limit=1, cursor=cursor)
        items.extend(item.projection_id for item in page.items)
        cursor = page.next_cursor
        if cursor is None:
            break
    assert items[:2] == ["cash:1202", "cash:1201"]
    assert len(items) == len(set(items)) == 10
    first = service.list_cash_projections(limit=1)
    with pytest.raises(ValueError, match="invalid_cursor"):
        service.list_cash_projections(limit=1, cursor=first.next_cursor, state="posted")
    with pytest.raises(ValueError, match="invalid_cursor"):
        service.list_cash_projections(limit=1, cursor=first.next_cursor, hide_zero_amount=False)


@pytest.mark.parametrize("filters, expected", [
    ({"amount_min": "-1000", "amount_max": "-998"}, {"cash:1202"}),
    ({"amount_min": "0"}, set()),
    ({"date_from": "2026-01-01"}, set()),
    ({"date_to": "2027-01-01"}, set()),
    ({"currency": "CNY"}, {"cash:1202"}),
    ({"account_id": 101}, set()),
])
def test_explicit_filters_do_not_guess_unknown_draft_values(relation_context_runtime, filters, expected):
    runtime = relation_context_runtime
    _drafts(runtime)
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    page = CashLedgerQueryService(runtime.sessions, runtime.workspace_id).list_cash_projections(state="draft", **filters)
    assert {item.projection_id for item in page.items} == expected
    assert page.pending_item_count == len(expected) and not page.monthly_summaries


def test_full_refund_stays_visible_without_overriding_explicit_filters_or_deletion(relation_context_runtime):
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        _add(CurrentCashRelationRepository(session, runtime.workspace_id), 1101, 1103, kind="refund_offset", state="confirmed")
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    service = CashLedgerQueryService(runtime.sessions, runtime.workspace_id)
    assert "cash:1101" not in {item.projection_id for item in service.list_cash_projections().items}
    confirmed = next(item for item in service.list_cash_projections(hide_zero_amount=False).items if item.projection_id == "cash:1101")
    assert confirmed.amount == "0" and confirmed.confirmation_status == "confirmed" and confirmed.statistics_eligible
    with runtime.sessions.begin() as session:
        sources = CashSourceRepository(session, runtime.workspace_id)
        sources.save_issue(1101, "one", code="source_update_conflict")
        sources.save_issue(1101, "two", code="source_update_conflict")
        CashProjectionService.maintain_in_session(session, runtime.workspace_id, {1101})
    hidden = service.list_cash_projections(confirmation_status="pending")
    assert hidden.items == () and hidden.pending_item_count == 0
    page = service.list_cash_projections(confirmation_status="pending", hide_zero_amount=False)
    assert len(page.items) == 1 and page.pending_item_count == 1
    assert page.items[0].amount == "0" and page.items[0].attention_count == 1 and page.items[0].statistics_eligible
    for filters in ({"currency": "USD"}, {"amount_min": "1"}, {"date_from": "2026-02-01"}):
        assert not service.list_cash_projections(confirmation_status="pending", hide_zero_amount=False, **filters).items
    indexed = service.list_cash_projections(confirmation_status="pending", hide_zero_amount=False, start_index=0, limit=1)
    assert indexed.total_count == 1 and indexed.pending_item_count == 1
    assert sum(item.end_index - item.start_index for item in indexed.month_positions) == 1
    filtered_indexed = service.list_cash_projections(confirmation_status="pending", start_index=0, limit=1)
    assert filtered_indexed.total_count == filtered_indexed.pending_item_count == 0
    with runtime.sessions.begin() as session:
        session.get(CashTransactionModel, 1103).deleted_at = CREATED
    deleted = service.list_cash_projections(confirmation_status="pending", hide_zero_amount=False, start_index=0, limit=1)
    assert deleted.projection_version == indexed.projection_version
    assert deleted.total_count == deleted.pending_item_count == 0
    assert not deleted.items and not deleted.month_positions and not deleted.monthly_summaries
    assert not service.list_cash_projections(confirmation_status="pending", hide_zero_amount=False).items


def test_hide_zero_amount_keeps_unknown_drafts_and_updates_counts(relation_context_runtime):
    runtime = relation_context_runtime
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    _drafts(runtime)
    with runtime.sessions.begin() as session:
        session.add(CashTransactionModel(id=1203, workspace_id=runtime.workspace_id, state="draft",
            account_id=None, amount=Decimal("0.000"), currency="CNY", occurred_at=None, created_at=CREATED,
            record_type=None, record_subtype=None, source_type="lifecycle-fixture", record_id="1203"))
        CashProjectionService.maintain_in_session(session, runtime.workspace_id, {1201, 1202, 1203})
    service = CashLedgerQueryService(runtime.sessions, runtime.workspace_id)
    hidden = service.list_cash_projections(state="draft", start_index=0, limit=20)
    assert {item.projection_id for item in hidden.items} == {"cash:1201", "cash:1202"}
    assert hidden.total_count == hidden.pending_item_count == 2
    assert next(item for item in hidden.items if item.projection_id == "cash:1201").amount is None
    shown = service.list_cash_projections(state="draft", hide_zero_amount=False, start_index=0, limit=20)
    assert {item.projection_id for item in shown.items} == {"cash:1201", "cash:1202", "cash:1203"}
    assert shown.total_count == shown.pending_item_count == 3
    assert next(item for item in shown.items if item.projection_id == "cash:1203").amount == "0"


def test_clearing_last_issue_keeps_full_refund_visible_and_confirmed(relation_context_runtime):
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        _add(CurrentCashRelationRepository(session, runtime.workspace_id), 1101, 1103, kind="refund_offset", state="confirmed")
        CashSourceRepository(session, runtime.workspace_id).save_issue(1101, "one", code="source_update_conflict")
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    service = CashLedgerQueryService(runtime.sessions, runtime.workspace_id)
    assert service.list_cash_projections().pending_item_count == 0
    assert service.list_cash_projections(hide_zero_amount=False).pending_item_count == 1
    with runtime.sessions.begin() as session:
        CashSourceRepository(session, runtime.workspace_id).clear_issue(1101, "one")
        CashProjectionService.maintain_in_session(session, runtime.workspace_id, {1101})
    page = service.list_cash_projections(hide_zero_amount=False)
    assert page.pending_item_count == 0
    item = next(item for item in page.items if item.projection_id == "cash:1101")
    assert item.amount == "0" and item.confirmation_status == "confirmed" and item.attention_count == 0
    assert "cash:1101" not in {item.projection_id for item in service.list_cash_projections().items}


def test_other_hidden_zero_records_still_require_attention_to_be_visible(relation_context_runtime):
    runtime = relation_context_runtime
    from ft.adapters.relational.models import CashTransactionComponentModel
    with runtime.sessions.begin() as session:
        session.get(CashTransactionModel, 1105).amount = Decimal("0")
        session.get(CashTransactionComponentModel, 6105).amount = Decimal("0")
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    query = CashLedgerQueryService(runtime.sessions, runtime.workspace_id)
    assert "cash:1105" not in {item.projection_id for item in query.list_cash_projections().items}
    with runtime.sessions.begin() as session:
        CashSourceRepository(session, runtime.workspace_id).save_issue(1105, "pending", code="source_update_conflict")
        CashProjectionService.maintain_in_session(session, runtime.workspace_id, {1105})
    assert "cash:1105" not in {item.projection_id for item in query.list_cash_projections().items}
    item = next(item for item in query.list_cash_projections(hide_zero_amount=False).items if item.projection_id == "cash:1105")
    assert item.amount == "0" and item.confirmation_status == "pending"
    with runtime.sessions.begin() as session:
        CashSourceRepository(session, runtime.workspace_id).clear_issue(1105, "pending")
        CashProjectionService.maintain_in_session(session, runtime.workspace_id, {1105})
    assert "cash:1105" not in {item.projection_id for item in query.list_cash_projections(hide_zero_amount=False).items}


def test_list_metadata_and_month_summaries_share_the_page_snapshot(relation_context_runtime):
    runtime = relation_context_runtime
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    baseline = CashLedgerQueryService(runtime.sessions, runtime.workspace_id).list_cash_projections()
    engine = runtime.sessions.kw["bind"]
    if engine.dialect.name == "sqlite":
        with engine.connect().execution_options(isolation_level="AUTOCOMMIT") as connection:
            connection.exec_driver_sql("PRAGMA journal_mode=WAL")
    changed = False
    def change_after_page(_connection, _cursor, statement, _parameters, _context, _many):
        nonlocal changed
        if changed or "WITH active_state AS" not in statement:
            return
        changed = True
        with runtime.sessions.begin() as session:
            session.get(CashCategoryModel, "category-food").name = "新的分类"
            CashSourceRepository(session, runtime.workspace_id).save_issue(1003, "one", code="source_update_conflict")
            session.get(CashTransactionModel, 1105).state = "draft"
            CashProjectionService.maintain_in_session(session, runtime.workspace_id, {1003, 1105})
    event.listen(engine, "after_cursor_execute", change_after_page)
    try:
        page = CashLedgerQueryService(runtime.sessions, runtime.workspace_id).list_cash_projections(limit=1)
    finally:
        event.remove(engine, "after_cursor_execute", change_after_page)
    assert changed and page.projection_version == baseline.projection_version
    assert page.pending_item_count == 0 and page.filter_options == baseline.filter_options and page.monthly_summaries == baseline.monthly_summaries
    current = CashLedgerQueryService(runtime.sessions, runtime.workspace_id).list_cash_projections()
    assert current.pending_item_count == 2 and current.projection_version > page.projection_version


@pytest.mark.parametrize("values", [{"state": "bogus"}, {"state": ["draft"]}, {"confirmation_status": "draft"}, {"confirmation_status": []}, {"hide_zero_amount": "sometimes"}, {"hide_zero_amount": 1}])
def test_lifecycle_filters_reject_unknown_or_malformed_values(relation_context_runtime, values):
    with pytest.raises(ValueError, match="invalid_filter"):
        CashLedgerQueryService(relation_context_runtime.sessions, relation_context_runtime.workspace_id).list_cash_projections(**values)


def test_http_lists_nullable_drafts_and_forwards_lifecycle_filters(relation_context_runtime):
    from tests.contract.test_web_api import _client
    runtime = relation_context_runtime
    _drafts(runtime)
    client = _client(runtime)
    drafts = client.get("/api/v1/cash-projections", params={"state": "draft", "confirmation_status": "pending"})
    assert drafts.status_code == 200
    body = drafts.json()
    assert body["pending_item_count"] == 2 and len(body["items"]) == 2 and not body["monthly_summaries"]
    unknown = next(item for item in body["items"] if item["projection_id"] == "cash:1201")
    assert unknown["amount"] is None and unknown["occurred_at"] is None and not unknown["statistics_eligible"]
    assert unknown["confirmation_status"] == "pending" and unknown["state"] == "draft"
    assert client.get("/api/v1/cash-projections", params={"state": "bad"}).status_code == 400


def test_http_zero_amount_filter_defaults_on_and_can_show_full_refund(relation_context_runtime):
    from tests.contract.test_web_api import _client
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        _add(CurrentCashRelationRepository(session, runtime.workspace_id), 1101, 1103, kind="refund_offset", state="confirmed")
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    client = _client(runtime)
    hidden = client.get("/api/v1/cash-projections")
    shown = client.get("/api/v1/cash-projections", params={"hide_zero_amount": "false"})
    assert hidden.status_code == shown.status_code == 200
    assert "cash:1101" not in {item["projection_id"] for item in hidden.json()["items"]}
    refund = next(item for item in shown.json()["items"] if item["projection_id"] == "cash:1101")
    assert refund["amount"] == "0"
