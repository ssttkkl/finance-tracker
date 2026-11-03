"""人工分类与备注保护、当前可见维护的双后端合同。"""
from datetime import datetime, timezone
from decimal import Decimal

import pytest
from sqlalchemy import event, select

from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.cash_entries import CashEntryRepository
from ft.adapters.relational.cash_sources import CashSourceRepository
from ft.adapters.relational.models import AccountModel, CashProjectionModel, CashTransactionComponentModel, CashTransactionModel
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.application.cash_categories import CashCategoryService
from ft.application.cash_classification import CashClassificationService
from ft.application.cash_ledger import CashLedgerCommandService
from ft.application.cash_projections import CashProjectionService
from tests.contract.test_cash_draft_storage import draft_runtime


from tests.cash_projection_assertions import current_projection_id as _public_id


def _mirror(runtime):
    with runtime.sessions.begin() as session:
        session.get(AccountModel, 101).currencies = ["CNY"]
        for identifier in (1002, 1003):
            parent = session.get(CashTransactionModel, identifier)
            parent.amount, parent.record_type, parent.account_id = Decimal("-12.50"), "consumption", 101
            parent.source_type = "alipay" if identifier == 1003 else "icbc_debit"
            part = session.get(CashTransactionComponentModel, identifier)
            part.amount, part.account_id = parent.amount, 101
        CurrentCashRelationRepository(session, runtime.workspace_id).add(dict(kind="payment_mirror", state="confirmed",
            primary_transaction_id=1003, secondary_transaction_id=1002, primary_component_id=1003, secondary_component_id=1002,
            anchor_transaction_id=1003, anchor_component_id=1003, primary_role="expense", secondary_role="expense", applied_amount="12.50"))


@pytest.mark.parametrize("category", ["category-food", None])
def test_explicit_projection_category_protects_entry_including_same_value_and_clear(draft_runtime, category):
    runtime = draft_runtime
    _mirror(runtime)
    with runtime.sessions.begin() as session:
        for identifier in (1002, 1003):
            row = session.get(CashTransactionModel, identifier)
            row.category_id = category
            row.note = "已有人工备注"
            row.manual_overrides = {"note": {"value": row.note}}
    status = CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    CashClassificationService(runtime.sessions, runtime.workspace_id).set_category(
        projection_ids=[_public_id(runtime, 1003)], projection_version=status["projection_version"], category_id=category)
    with runtime.sessions() as session:
        entry = CashEntryRepository(session, runtime.workspace_id).current(1003)
        assert entry.category_id == category and 'category_id' in entry.manual_fields
        for identifier in (1002, 1003):
            row = session.get(CashTransactionModel, identifier)
            assert row.category_id == category
            assert 'category_id' not in row.manual_overrides
            assert row.manual_overrides["note"] == {"value": "已有人工备注"}


@pytest.mark.parametrize("source_type", ["fixture", ""])
@pytest.mark.parametrize("changes, already_protected", [
    ({"note": ""}, False), ({"category_id": "category-food"}, False),
    ({"category_id": None}, False), ({"note": "来源备注"}, True),
])
def test_manual_record_edit_always_protects_explicit_note_or_category(draft_runtime, source_type, changes, already_protected):
    runtime = draft_runtime
    field, value = next(iter(changes.items()))
    other = "category_id" if field == "note" else "note"
    other_value = "category-food" if other == "category_id" else "已有备注"
    with runtime.sessions.begin() as session:
        row = session.get(CashTransactionModel, 1003)
        row.source_type = source_type
        row.note = value if field == "note" else other_value
        row.manual_overrides = {other: {"value": other_value}}
        if already_protected:
            row.manual_overrides = dict(row.manual_overrides, **{field: {"value": value, "source_value": value}})
    with RelationalUnitOfWork(runtime.sessions, runtime.workspace_id) as uow:
        uow.cashflows.update(1003, dict(account_name="日常账户", currency="CNY", **changes))
        uow.commit()
    with runtime.sessions() as session:
        row = session.get(CashTransactionModel, 1003)
        assert getattr(row, field) == value
        assert row.manual_overrides[field]["value"] == value
        assert row.manual_overrides[other] == {"value": other_value}


@pytest.mark.parametrize("category", [None, "category-daily"])
def test_automatic_category_sync_preserves_manual_member_category_and_revision(draft_runtime, category):
    runtime = draft_runtime
    _mirror(runtime)
    with runtime.sessions.begin() as session:
        row = session.get(CashTransactionModel, 1002)
        row.category_id = category
        row.manual_overrides = {"category_id": {"value": category}, "note": {"value": "人工备注"}}
        session.flush()
        revision = row.row_revision
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    with runtime.sessions() as session:
        row = session.get(CashTransactionModel, 1002)
        assert row.category_id == category and row.row_revision == revision
        assert row.manual_overrides["category_id"]["value"] == category


@pytest.mark.parametrize("category", ["category-daily", "category-food", None])
def test_record_category_selection_protects_every_related_member(draft_runtime, category):
    runtime = draft_runtime
    _mirror(runtime)
    if category is None:
        with runtime.sessions.begin() as session:
            for identifier in (1002, 1003):
                session.get(CashTransactionModel, identifier).category_id = None
    status = CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    CashLedgerCommandService(runtime.sessions, runtime.workspace_id).update_record("1003", dict(
        account_name="日常账户", currency="CNY", category_id=category, projection_version=status["projection_version"]))
    with runtime.sessions() as session:
        for identifier in (1002, 1003):
            row = session.get(CashTransactionModel, identifier)
            assert row.category_id == category
            assert row.manual_overrides["category_id"]["value"] == category


def test_category_deletion_protects_empty_value_for_live_and_deleted_records(draft_runtime):
    runtime = draft_runtime
    with runtime.sessions.begin() as session:
        for identifier in (1002, 1003):
            row = session.get(CashTransactionModel, identifier)
            row.category_id = "category-food"
            row.manual_overrides = {"category_id": {"value": "category-food", "source_value": "category-daily"}, "note": {"value": "人工备注"}}
        session.get(CashTransactionModel, 1002).deleted_at = datetime(2026, 7, 5, tzinfo=timezone.utc)
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    categories = CashCategoryService(runtime.sessions, runtime.workspace_id)
    impact = categories.deletion_impact("category-food")
    result = categories.delete("category-food", expected_revision=impact["revision"],
        expected_category_revision=impact["category_revision"], expected_usage_count=impact["direct_usage_count"], confirmed=True)
    assert result["cleared_transaction_count"] == 1
    with runtime.sessions() as session:
        for identifier in (1002, 1003):
            row = session.get(CashTransactionModel, identifier)
            assert row.category_id is None and row.manual_overrides["category_id"]["value"] is None
            assert row.manual_overrides["category_id"]["source_value"] == "category-daily"
            assert row.manual_overrides["note"] == {"value": "人工备注"}


def _pending_full_refund(runtime):
    with runtime.sessions.begin() as session:
        expense = session.get(CashTransactionModel, 1003)
        expense.record_type = "consumption"
        session.add(CashTransactionModel(id=2001, workspace_id=runtime.workspace_id, account_id=101,
            amount=Decimal("12.50"), currency="CNY", occurred_at=expense.occurred_at, record_type="refund", source_type="fixture", record_id="full-refund"))
        session.flush()
        session.add(CashTransactionComponentModel(id=2001, workspace_id=runtime.workspace_id, cash_transaction_id=2001,
            account_id=101, amount=Decimal("12.50"), currency="CNY", ordinal=0))
        session.flush()
        CurrentCashRelationRepository(session, runtime.workspace_id).add(dict(kind="refund_offset", state="confirmed",
            primary_transaction_id=1003, secondary_transaction_id=2001, primary_component_id=1003, secondary_component_id=2001,
            anchor_transaction_id=1003, anchor_component_id=1003, primary_role="expense", secondary_role="refund", applied_amount="12.50"))
        CashSourceRepository(session, runtime.workspace_id).save_issue(1003, "pending", code="source_update_conflict")
    return CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()


def test_full_refund_projection_can_be_classified_and_deleted(draft_runtime):
    runtime = draft_runtime
    status = _pending_full_refund(runtime)
    with runtime.sessions() as session:
        row = session.scalar(select(CashProjectionModel).where(CashProjectionModel.root_cash_transaction_id == 1003))
        assert row.visible and row.attention_count == 1
    classified = CashClassificationService(runtime.sessions, runtime.workspace_id).set_category(
        projection_ids=[_public_id(runtime, 1003)], projection_version=status["projection_version"], category_id="category-daily")
    removed = CashLedgerCommandService(runtime.sessions, runtime.workspace_id).delete_projections(
        [_public_id(runtime, 1003)], projection_version=classified["projection_version"])
    assert set(removed["deleted_fact_ids"]) == {"1003", "2001"}


def test_other_business_hidden_pending_projection_can_be_classified_and_deleted(draft_runtime):
    runtime = draft_runtime
    with runtime.sessions.begin() as session:
        session.get(CashTransactionModel, 1003).amount = Decimal("0")
        session.get(CashTransactionComponentModel, 1003).amount = Decimal("0")
        CashSourceRepository(session, runtime.workspace_id).save_issue(1003, "pending", code="source_update_conflict")
    status = CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    with runtime.sessions() as session:
        row = session.scalar(select(CashProjectionModel).where(CashProjectionModel.root_cash_transaction_id == 1003))
        assert not row.visible and row.hidden_reason == "balance_adjustment" and row.attention_count == 1
    classified = CashClassificationService(runtime.sessions, runtime.workspace_id).set_category(
        projection_ids=[_public_id(runtime, 1003)], projection_version=status["projection_version"], category_id="category-daily")
    removed = CashLedgerCommandService(runtime.sessions, runtime.workspace_id).delete_projections(
        [_public_id(runtime, 1003)], projection_version=classified["projection_version"])
    assert set(removed["deleted_fact_ids"]) == {"1003"}


@pytest.mark.parametrize("operation", ["classify", "delete"])
def test_pending_projection_with_deleted_source_stays_outside_maintenance(draft_runtime, operation):
    runtime = draft_runtime
    status = _pending_full_refund(runtime)
    with runtime.sessions.begin() as session:
        session.get(CashTransactionModel, 2001).deleted_at = datetime(2026, 7, 5, tzinfo=timezone.utc)
    with pytest.raises(ValueError, match="projection.version_conflict"):
        if operation == "classify":
            CashClassificationService(runtime.sessions, runtime.workspace_id).set_category(
                projection_ids=[_public_id(runtime, 1003)], projection_version=status["projection_version"], category_id="category-daily")
        else:
            CashLedgerCommandService(runtime.sessions, runtime.workspace_id).delete_projections(
                [_public_id(runtime, 1003)], projection_version=status["projection_version"])


def _candidate_on(runtime, anchor):
    with runtime.sessions.begin() as session:
        repository = CurrentCashRelationRepository(session, runtime.workspace_id)
        relation = repository.add(dict(kind="refund_offset", primary_transaction_id=anchor,
            anchor_transaction_id=anchor))
        candidate = session.get(CashTransactionModel, 1001)
        return repository.set_candidates(relation["id"], [dict(candidate_transaction_id=1001,
            candidate_component_id=1001, expected_row_revision=candidate.row_revision)],
            expected_revision=relation["row_revision"])


@pytest.mark.parametrize("category", ["category-food", None])
def test_same_protected_category_does_not_advance_rows_or_invalidate_candidates(draft_runtime, category):
    runtime = draft_runtime
    status = CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    classifications = CashClassificationService(runtime.sessions, runtime.workspace_id)
    first = classifications.set_category(projection_ids=[_public_id(runtime, 1003)],
        projection_version=status["projection_version"], category_id=category)
    with runtime.sessions.begin() as session:
        row = session.get(CashTransactionModel, 1003)
        row.manual_overrides = {"category_id": {"value": category, "source_value": "category-daily"}}
    relation = _candidate_on(runtime, 1003)
    with runtime.sessions() as session:
        revision = session.get(CashTransactionModel, 1003).row_revision
    repeated = classifications.set_category(projection_ids=[_public_id(runtime, 1003)],
        projection_version=first["projection_version"], category_id=category)
    assert repeated["projection_version"] == first["projection_version"]
    with runtime.sessions() as session:
        assert session.get(CashTransactionModel, 1003).row_revision == revision
        assert session.get(CashTransactionModel, 1003).manual_overrides["category_id"]["source_value"] == "category-daily"
        current = CurrentCashRelationRepository(session, runtime.workspace_id).get(relation["id"])
        assert current["row_revision"] == relation["row_revision"]
        assert current["candidates"][0]["validity"] == "valid"


@pytest.mark.parametrize("category", [None, "category-daily"])
def test_legacy_import_merge_respects_manual_note_and_category(draft_runtime, category):
    runtime = draft_runtime
    with runtime.sessions.begin() as session:
        row = session.get(CashTransactionModel, 1003)
        row.source_type, row.record_id = "fixture", "protected-record"
        row.category_id, row.note = category, "人工备注"
        row.manual_overrides = {"category_id": {"value": category}, "note": {"value": row.note}}
    with RelationalUnitOfWork(runtime.sessions, runtime.workspace_id) as uow:
        incoming = dict(uow.cashflows.get(1003), note="新来源备注", category_id="category-food", source_payload={"revision": 2})
        identifier, created = uow.cashflows.merge_import("cash", incoming)
        assert identifier == 1003 and not created
        uow.commit()
    with runtime.sessions() as session:
        row = session.get(CashTransactionModel, 1003)
        assert row.category_id == category and row.note == "人工备注"
        assert row.manual_overrides["category_id"]["value"] == category


def test_category_propagation_invalidates_only_members_that_really_changed(draft_runtime):
    runtime = draft_runtime
    _mirror(runtime)
    with runtime.sessions.begin() as session:
        row = session.get(CashTransactionModel, 1002)
        row.category_id = "category-daily"
        row.manual_overrides = {"category_id": {"value": "category-daily"}}
    status = CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    relation = _candidate_on(runtime, 1002)
    with runtime.sessions() as session:
        revision = session.get(CashTransactionModel, 1002).row_revision
    CashLedgerCommandService(runtime.sessions, runtime.workspace_id).update_record("1003", dict(
        account_name="日常账户", currency="CNY", category_id="category-daily",
        projection_version=status["projection_version"]))
    with runtime.sessions() as session:
        assert session.get(CashTransactionModel, 1002).row_revision == revision
        current = CurrentCashRelationRepository(session, runtime.workspace_id).get(relation["id"])
        assert current["row_revision"] == relation["row_revision"]
        assert current["candidates"][0]["validity"] == "valid"


def test_projection_rebuild_preserves_source_category_and_candidate_proof(draft_runtime):
    runtime = draft_runtime
    _mirror(runtime)
    with runtime.sessions.begin() as session:
        session.get(CashTransactionModel, 1002).category_id = "category-daily"
    relation = _candidate_on(runtime, 1002)
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    with runtime.sessions() as session:
        assert session.get(CashTransactionModel, 1002).category_id == "category-daily"
        assert CashEntryRepository(session, runtime.workspace_id).current(1002).category_id == 'category-food'
        current = CurrentCashRelationRepository(session, runtime.workspace_id).get(relation["id"])
        assert current["row_revision"] == relation["row_revision"]
        assert current["candidates"][0]["validity"] == "valid"


def test_classification_write_failure_rolls_back_protection_candidates_and_projection(draft_runtime):
    runtime = draft_runtime
    _mirror(runtime)
    status = CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    relation = _candidate_on(runtime, 1002)
    with runtime.sessions() as session:
        before = {identifier: (session.get(CashTransactionModel, identifier).row_revision,
            session.get(CashTransactionModel, identifier).manual_overrides,
            session.get(CashTransactionModel, identifier).category_id) for identifier in (1002, 1003)}
        entry_before = CashEntryRepository(session, runtime.workspace_id).current(1003)
        entry_snapshot = (entry_before.id, entry_before.revision, entry_before.category_id, list(entry_before.manual_fields))
    def fail_projection(_connection, _cursor, statement, _parameters, _context, _executemany):
        if statement.lower().startswith("update cash_projections "):
            raise RuntimeError("injected projection failure")
    engine = runtime.sessions.kw["bind"]
    event.listen(engine, "before_cursor_execute", fail_projection)
    try:
        with pytest.raises(RuntimeError, match="injected projection failure"):
            CashClassificationService(runtime.sessions, runtime.workspace_id).set_category(
                projection_ids=[_public_id(runtime, 1003)], projection_version=status["projection_version"], category_id="category-daily")
    finally:
        event.remove(engine, "before_cursor_execute", fail_projection)
    assert CashProjectionService(runtime.sessions, runtime.workspace_id).status()["projection_version"] == status["projection_version"]
    with runtime.sessions() as session:
        for identifier in (1002, 1003):
            row = session.get(CashTransactionModel, identifier)
            assert (row.row_revision, row.manual_overrides, row.category_id) == before[identifier]
        entry = CashEntryRepository(session, runtime.workspace_id).current(1003)
        assert (entry.id, entry.revision, entry.category_id, entry.manual_fields) == entry_snapshot
        current = CurrentCashRelationRepository(session, runtime.workspace_id).get(relation["id"])
        assert current["row_revision"] == relation["row_revision"]
        assert current["candidates"][0]["validity"] == "valid"


def test_large_category_change_invalidates_candidate_in_last_batch(draft_runtime):
    from sqlalchemy import insert
    from ft.adapters.relational.cash_categories import set_manual_category

    runtime = draft_runtime
    identifiers = tuple(range(10000, 10501))
    with runtime.sessions.begin() as session:
        session.execute(insert(CashTransactionModel), [dict(id=identifier, workspace_id=runtime.workspace_id,
            account_id=101, amount=Decimal("-1"), currency="CNY", occurred_at=datetime(2026, 7, 5, tzinfo=timezone.utc),
            record_type="consumption", record_subtype="not_applicable") for identifier in identifiers])
        session.add(CashTransactionComponentModel(id=10500, workspace_id=runtime.workspace_id,
            cash_transaction_id=10500, account_id=101, amount=Decimal("-1"), currency="CNY", ordinal=0))
        session.flush()
        repository = CurrentCashRelationRepository(session, runtime.workspace_id)
        relation = repository.add(dict(kind="refund_offset", primary_transaction_id=1002, anchor_transaction_id=1002))
        relation = repository.set_candidates(relation["id"], [dict(candidate_transaction_id=10500,
            candidate_component_id=10500, expected_row_revision=1)], expected_revision=relation["row_revision"])
        changed = set_manual_category(session, runtime.workspace_id, identifiers, "category-daily")
        assert changed == set(identifiers)
        assert repository.mark_candidates_stale(changed) == {relation["id"]}
    with runtime.sessions() as session:
        current = CurrentCashRelationRepository(session, runtime.workspace_id).get(relation["id"])
        assert current["row_revision"] == relation["row_revision"] + 1
        assert current["candidates"][0]["validity"] == "stale"


@pytest.mark.parametrize("operation", ["classify", "category_create", "category_delete"])
def test_category_commands_lock_workspace_before_other_state(draft_runtime, operation):
    runtime = draft_runtime
    status = CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    categories = CashCategoryService(runtime.sessions, runtime.workspace_id)
    impact = categories.deletion_impact("category-food")
    public_id = _public_id(runtime, 1003)
    statements = []
    def capture(_connection, _cursor, statement, _parameters, _context, _executemany):
        statements.append(statement.lower())
    engine = runtime.sessions.kw["bind"]
    event.listen(engine, "before_cursor_execute", capture)
    try:
        if operation == "classify":
            CashClassificationService(runtime.sessions, runtime.workspace_id).set_category(
                projection_ids=[public_id], projection_version=status["projection_version"], category_id=None)
        elif operation == "category_create":
            categories.create(name="新分类")
        else:
            categories.delete("category-food", expected_revision=impact["revision"],
                expected_category_revision=impact["category_revision"],
                expected_usage_count=impact["direct_usage_count"], confirmed=True)
    finally:
        event.remove(engine, "before_cursor_execute", capture)
    if engine.dialect.name == "postgresql":
        locks = [sql for sql in statements if "for update" in sql]
        assert locks and "workspaces" in locks[0]
    else:
        assert statements[0] == "begin immediate"


def test_classification_rejects_entry_members_outside_selected_projection(draft_runtime):
    from ft.adapters.relational.models import CashEntryMemberModel
    runtime = draft_runtime
    _mirror(runtime)
    status = CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    with runtime.sessions.begin() as session:
        entry = CashEntryRepository(session, runtime.workspace_id).current(1003)
        session.get(CashEntryMemberModel, (runtime.workspace_id, 1001)).entry_id = entry.id
        old = (entry.category_id, entry.revision)
    with pytest.raises(ValueError, match='projection.version_conflict'):
        CashClassificationService(runtime.sessions, runtime.workspace_id).set_category(
            projection_ids=[_public_id(runtime, 1003)], projection_version=status['projection_version'], category_id='category-daily')
    with runtime.sessions() as session:
        entry = CashEntryRepository(session, runtime.workspace_id).current(1003)
        assert (entry.category_id, entry.revision) == old


@pytest.mark.parametrize('origin', ['import', 'manual'])
def test_legacy_relation_origin_uses_resolved_parent_not_component_id(draft_runtime, origin):
    from ft.adapters.relational.repositories import RelationalRelationRepository
    from ft.domain.cash_relation_policy import CashRelationError
    runtime = draft_runtime
    with runtime.sessions.begin() as session:
        source = session.get(CashTransactionModel, 1003)
        for identifier, creation in [(9001, origin), (9002, 'import'), (9101, 'manual' if origin == 'import' else 'import'), (9102, 'import')]:
            session.add(CashTransactionModel(id=identifier, workspace_id=runtime.workspace_id, account_id=101,
                amount=Decimal('-10'), currency='CNY', occurred_at=source.occurred_at,
                record_type='consumption', record_subtype='not_applicable', record_origin=creation))
        session.flush()
        for identifier, parent in [(9101, 9001), (9102, 9002)]:
            session.add(CashTransactionComponentModel(id=identifier, workspace_id=runtime.workspace_id,
                cash_transaction_id=parent, account_id=101, amount=Decimal('-10'), currency='CNY', ordinal=0))
        session.flush()
        repository = RelationalRelationRepository(session, runtime.workspace_id)
        relation = dict(kind='payment_mirror', status='accepted', primary_fact_id='9101', secondary_fact_id='9102',
            primary_component_id='9101', secondary_component_id='9102', anchor_fact_id='9101', anchor_component_id='9101')
        if origin == 'manual':
            with pytest.raises(CashRelationError, match='manual_relation_forbidden'):
                repository.add(relation)
        else:
            identifier = repository.add(relation)
            from ft.adapters.relational.models import TransactionRelationModel
            assert session.get(TransactionRelationModel, identifier).primary_transaction_id == 9001
