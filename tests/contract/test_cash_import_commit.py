"""一次确认提交全部流水和关系，局部失败不丢失其余结果。"""
from dataclasses import replace
from decimal import Decimal

import pytest
from sqlalchemy import func, select, update

from ft.adapters.relational.models import (AccountModel, CashSourceBindingModel,
    CashTransactionComponentModel, CashTransactionIssueModel, CashTransactionModel,
    TransactionRelationModel, WorkspaceMembershipModel)
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.application.parsed_import_staging import InMemoryParsedImportStore
from ft.application.cash_commit_outcome import CashCommitFailure
from tests.contract.test_cash_import_operations import _as_source, _file, _selections, _bind_existing
from tests.contract.test_parsed_cash_import_context import USER, context_runtime


@pytest.fixture(autouse=True)
def configured(context_runtime):
    from ft.application.cash_projections import CashProjectionService
    with context_runtime.sessions.begin() as session:
        session.execute(update(AccountModel).where(AccountModel.id.in_((101, 102)),
            AccountModel.workspace_id == context_runtime.workspace_id).values(currencies=["CNY"]))
    CashProjectionService(context_runtime.sessions, context_runtime.workspace_id).rebuild()


def _service(runtime, store=None):
    from ft.application.cash_import_commit import CashImportCommandService
    return CashImportCommandService(RelationalUnitOfWork(runtime.sessions, runtime.workspace_id),
        store or InMemoryParsedImportStore(), user_id=USER)


def _plan(runtime, files=None, *, mapped=True, rows=None):
    service = _service(runtime)
    files = tuple(files or (_file(),))
    staged = service._staging.create(workspace_id=runtime.workspace_id, user_id=USER, files=files)
    view = service.preview(staged.session_id, expected_session_revision=1,
        account_selections=_selections(files) if mapped else [], row_choices=rows or [])
    return service, view


def _commit(service, plan, key="synthetic-confirm"):
    return service.commit(plan["plan_id"], expected_session_revision=plan["session_revision"], idempotency_key=key)


def _results(service, receipt, kind=None):
    return service.results(receipt["receipt_id"], result_kind=kind, limit=200)["items"]


def _database_state(runtime):
    from ft.adapters.relational.models import Base
    with runtime.sessions() as session:
        return {table.name: sorted(repr(tuple(row)) for row in session.execute(select(table)))
            for table in Base.metadata.sorted_tables}


def _assert_atomic_failure(runtime, action, error=CashCommitFailure, *, code=None):
    before = _database_state(runtime)
    with pytest.raises(error, match=code) as rejected:
        action()
    if isinstance(rejected.value, CashCommitFailure):
        assert rejected.value.commit_state == "not_committed"
    assert _database_state(runtime) == before
    return rejected.value


def test_resolve_returns_pending_for_independent_writer_then_recovers(context_runtime):
    from sqlalchemy import text
    from ft.adapters.relational.workspace_lock import lock_workspace
    service, plan = _plan(context_runtime)
    def checker():
        session = context_runtime.sessions()
        if session.bind.dialect.name == "postgresql":
            session.execute(text("SET LOCAL lock_timeout='0'"))
            session.execute(text("SET LOCAL statement_timeout='2s'"))
        return session
    service._uow = RelationalUnitOfWork(checker, context_runtime.workspace_id)
    with context_runtime.sessions() as writer:
        lock_workspace(writer, context_runtime.workspace_id)
        assert service.resolve(plan["plan_id"], idempotency_key="synthetic-pending") == {"status": "pending"}
        writer.rollback()
    with context_runtime.sessions() as session:
        if session.bind.dialect.name == "sqlite":
            assert session.connection().exec_driver_sql("PRAGMA busy_timeout").scalar() == 5000
    assert service.resolve(plan["plan_id"], idempotency_key="synthetic-pending") == {"status": "retry_original"}
    receipt = _commit(service, plan, "synthetic-pending")
    assert service.resolve(plan["plan_id"], idempotency_key="synthetic-pending")["receipt"]["receipt_id"] == receipt["receipt_id"]


@pytest.mark.parametrize("code", ["storage.connect", "storage.readonly", "storage.schema"])
def test_resolve_does_not_treat_other_storage_failures_as_pending(context_runtime, monkeypatch, code):
    from ft.adapters.relational.runtime import StorageError
    service, plan = _plan(context_runtime)
    def fail(self):
        raise StorageError(code)
    monkeypatch.setattr(RelationalUnitOfWork, "__enter__", fail)
    with pytest.raises(StorageError) as captured:
        service.resolve(plan["plan_id"], idempotency_key="synthetic-storage-error")
    assert captured.value.code == code


def test_large_new_batch_failure_rolls_back_entire_command_without_row_fallback(context_runtime, monkeypatch):
    from ft.adapters.relational.cash_import_new_rows import CashImportNewRowsRepository
    from ft.adapters.relational.cash_sources import CashSourceRepository
    original_file = _file()
    first = original_file.document.rows[0]
    rows = tuple(replace(first, position=index + 1, provider_id=f"synthetic-{index}",
        fields={**first.fields, "source_payload": {"synthetic_index": index}}) for index in range(120))
    file = replace(original_file, document=replace(original_file.document, rows=rows,
        observed_count=120, declared_count=120))
    service, plan = _plan(context_runtime, (file,))
    bulk_original = CashImportNewRowsRepository.insert
    row_original = CashSourceRepository.save_occurrence
    calls = []
    def batch_fail(self, entries):
        from sqlalchemy import insert
        saved = bulk_original(self, entries)
        calls.append(len(entries))
        table = CashTransactionModel.__table__
        self.session.execute(insert(table).from_select([column.name for column in table.columns],
            select(*table.columns).where(table.c.id == saved[0][0])))
        return saved
    def row_fail(self, identifier, row, **kwargs):
        result = row_original(self, identifier, row, **kwargs)
        if row.provider_id == "synthetic-37":
            raise RuntimeError("synthetic single-row failure after aliases")
        return result
    monkeypatch.setattr(CashImportNewRowsRepository, "insert", batch_fail)
    monkeypatch.setattr(CashSourceRepository, "save_occurrence", row_fail)
    _assert_atomic_failure(context_runtime, lambda: _commit(service, plan), CashCommitFailure)


def test_successful_bulk_keeps_mixed_components_aliases_protection_and_exact_balances(context_runtime, monkeypatch):
    from decimal import localcontext
    from ft.adapters.relational.cash_import_new_rows import CashImportNewRowsRepository
    from ft.adapters.relational.cash_sources import CashSourceRepository
    from ft.adapters.relational.repositories import RelationalSnapshotRepository
    from ft.application.cash_import_source_rows import source_batch_rows
    file = _file()
    source = file.document.rows[0]
    rows, choices, expected_parts = [], [], {}
    for index in range(32):
        amount = f"-{index + 3}.123456789012345678"
        rows.append(replace(source, position=index + 1, provider_id=f"mixed-{index}",
            fields={**source.fields, "amount": amount, "source_payload": {"synthetic_index": index}}))
        account = "101" if index % 2 == 0 else "102"
        parts = [{"ordinal": 0, "account_id": account, "amount": amount, "currency": "CNY"}]
        if index % 3 == 0:
            parts = [{"ordinal": 0, "account_id": account, "amount": "-1", "currency": "CNY"},
                {"ordinal": 1, "account_id": "102" if account == "101" else "101",
                    "amount": f"-{index + 2}.123456789012345678", "currency": "CNY"}]
        expected_parts[index] = parts
        choices.append({"row_ref": f"file_a:r{index + 1}", "action": "apply", "patch": {
            "account_id": account if len(parts) == 1 else None,
            "cash_granularity": "atomic" if len(parts) == 1 else "aggregate",
            "components": parts, "note": f"人工备注{index}"}})
    file = replace(file, document=replace(file.document, rows=tuple(rows), declared_count=32, observed_count=32))
    duplicate = replace(file, file_ref="file_b", client_file_ref="client_b", digest="b" * 64)
    with context_runtime.sessions() as session:
        before = RelationalSnapshotRepository(session, context_runtime.workspace_id).load()
        accounts = {str(identifier): (session.get(AccountModel, identifier).type,
            session.get(AccountModel, identifier).name) for identifier in (101, 102)}
    service, plan = _plan(context_runtime, (file, duplicate), rows=choices)
    assert plan["summary"].get("create_posted") == 32, (plan["summary"], service.processing_details(plan["plan_id"])["items"][:2])
    original, calls, errors = CashImportNewRowsRepository.insert, [], []
    def track(self, entries):
        try:
            result = original(self, entries)
        except Exception as error:
            errors.append((type(error).__name__, getattr(error, "code", None)))
            raise
        calls.append(len(result))
        return result
    monkeypatch.setattr(CashImportNewRowsRepository, "insert", track)
    receipt = _commit(service, plan)
    assert sum(calls) == 32 and receipt["summary"]["created_posted"] == 32, (errors, receipt["summary"])
    assert receipt["summary"]["duplicate"] == 32 and receipt["summary"]["skipped"] == 0
    incoming = source_batch_rows((file, duplicate))
    expected_deltas = {key: Decimal(0) for key in accounts}
    with context_runtime.sessions() as session, localcontext() as arithmetic:
        arithmetic.prec = 80
        repository = CashSourceRepository(session, context_runtime.workspace_id)
        for index, row in enumerate(rows):
            identifier = repository.find(incoming[f"file_a:r{index + 1}"])
            assert identifier == repository.find(incoming[f"file_b:r{index + 1}"])
            parent = session.get(CashTransactionModel, identifier)
            assert parent.source_payload == {"synthetic_index": index} and parent.amount == Decimal(row.fields["amount"])
            from ft.adapters.relational.cash_entries import CashEntryRepository
            entry = CashEntryRepository(session, context_runtime.workspace_id).current(identifier)
            assert parent.note == source.fields['note'] and not parent.manual_overrides.get('note')
            assert entry.note == f"人工备注{index}" and 'note' in entry.manual_fields
            parts = list(session.scalars(select(CashTransactionComponentModel).where(
                CashTransactionComponentModel.cash_transaction_id == identifier).order_by(CashTransactionComponentModel.ordinal)))
            assert [(str(part.account_id), part.amount, part.currency) for part in parts] == [
                (part["account_id"], Decimal(part["amount"]), part["currency"]) for part in expected_parts[index]]
            for part in expected_parts[index]:
                expected_deltas[part["account_id"]] += Decimal(part["amount"])
        after = RelationalSnapshotRepository(session, context_runtime.workspace_id).load()
        for identifier, (kind, name) in accounts.items():
            old = Decimal(before.get("accounts", {}).get(kind, {}).get(name, {}).get("CNY", "0"))
            assert Decimal(after["accounts"][kind][name]["CNY"]) - old == expected_deltas[identifier]


def test_real_plan_creates_formal_record_binding_and_projection_in_one_confirmation(context_runtime):
    service, plan = _plan(context_runtime)
    receipt = _commit(service, plan)
    assert receipt["status"] == "completed" and receipt["committed"] is True
    assert receipt["summary"]["created_posted"] == 1
    result, = _results(service, receipt, "row")
    with context_runtime.sessions() as session:
        parent = session.get(CashTransactionModel, int(result["transaction_id"]))
        assert parent.state == "posted" and parent.amount == Decimal("-12.5")
        assert session.scalar(select(func.count()).select_from(CashSourceBindingModel).where(
            CashSourceBindingModel.transaction_id == parent.id)) > 0
    assert receipt["projection_version"] > 0


def test_draft_is_saved_in_same_tables_without_entering_statistics_or_balances(context_runtime):
    from ft.adapters.relational.models import LedgerSnapshotModel
    with context_runtime.sessions() as session:
        snapshot = session.get(LedgerSnapshotModel, context_runtime.workspace_id)
        before = snapshot.payload if snapshot else None
    service, plan = _plan(context_runtime, mapped=False)
    receipt = _commit(service, plan)
    row, = _results(service, receipt, "row")
    assert row["outcome"] == "saved_draft" and receipt["needs_attention_count"] == 1
    assert receipt["pending_item_count"] == 1
    with context_runtime.sessions() as session:
        parent = session.get(CashTransactionModel, int(row["transaction_id"]))
        assert parent.state == "draft" and parent.account_id is None
        snapshot = session.get(LedgerSnapshotModel, context_runtime.workspace_id)
        assert (snapshot.payload if snapshot else None) == before
        assert session.scalar(select(func.count()).select_from(CashTransactionIssueModel).where(
            CashTransactionIssueModel.transaction_id == parent.id)) > 0


def test_same_key_replays_after_temporary_session_is_gone_and_other_key_cannot_consume_again(context_runtime):
    from ft.adapters.relational.cash_mutations import CashMutationConflict
    service, plan = _plan(context_runtime)
    first = _commit(service, plan)
    service._staging._entries.clear()
    assert _commit(service, plan) == first
    with pytest.raises(CashMutationConflict, match="session_committed") as error:
        _commit(service, plan, "different-key")
    assert error.value.receipt_id == first["receipt_id"]
    with pytest.raises(CashMutationConflict, match="idempotency_conflict"):
        service.commit(plan["plan_id"], expected_session_revision=99, idempotency_key="synthetic-confirm")


def test_row_failure_rolls_back_all_aliases_and_duplicate_results_and_preserves_entire_batch(context_runtime, monkeypatch):
    from ft.adapters.relational.cash_sources import CashSourceRepository
    first, other = _file(), _file(letter="b", provider="002", amount="-3")
    duplicate = replace(first, file_ref="file_c", client_file_ref="client_c")
    service, plan = _plan(context_runtime, (first, duplicate, other))
    original = CashSourceRepository.save_occurrence
    def fail(self, identifier, row, **kwargs):
        result = original(self, identifier, row, **kwargs)
        if row.provider_id == "001":
            raise RuntimeError("synthetic SQL secret after source flush")
        return result
    monkeypatch.setattr(CashSourceRepository, "save_occurrence", fail)
    _assert_atomic_failure(context_runtime, lambda: _commit(service, plan), CashCommitFailure)


def test_current_row_conflict_only_skips_that_operation_and_preserves_manual_change(context_runtime):
    first, other = _file(), _file(letter="b", provider="002", amount="-3")
    with context_runtime.sessions.begin() as session:
        _bind_existing(session, context_runtime, first)
    service, plan = _plan(context_runtime, (first, other))
    with context_runtime.sessions.begin() as session:
        row = session.get(CashTransactionModel, 1003)
        row.note, row.manual_overrides = "刚刚修改", {"note": True}
    from ft.application.cash_import_plans import CashImportPlanError
    _assert_atomic_failure(context_runtime, lambda: _commit(service, plan), CashImportPlanError, code='plan_changed')


def test_source_text_update_does_not_rewrite_balance_snapshot(context_runtime):
    from ft.adapters.relational.models import LedgerSnapshotModel
    first = _file()
    service, plan = _plan(context_runtime, (first,))
    _commit(service, plan)
    with context_runtime.sessions() as session:
        snapshot = session.get(LedgerSnapshotModel, context_runtime.workspace_id)
        before = snapshot.payload, snapshot.version
    changed = replace(first, document=replace(first.document, rows=(replace(first.document.rows[0], fields={
        **first.document.rows[0].fields, "counterparty": "更新商户"}),)))
    service, plan = _plan(context_runtime, (changed,))
    receipt = _commit(service, plan, "source-update")
    assert receipt["summary"]["updated_posted"] == 1
    with context_runtime.sessions() as session:
        snapshot = session.get(LedgerSnapshotModel, context_runtime.workspace_id)
        assert (snapshot.payload, snapshot.version) == before


def test_stale_existing_counterpart_issue_only_defers_original_group_not_independent_rows(context_runtime):
    from ft.adapters.relational.cash_sources import CashSourceRepository
    platform, bank = _mirror_files()
    with context_runtime.sessions.begin() as session:
        _bind_existing(session, context_runtime, bank)
    service, plan = _plan(context_runtime, (platform, _file(letter="c", provider="002", amount="-3")))
    assert plan["summary"]["relations_accepted"] == 1
    with context_runtime.sessions.begin() as session:
        CashSourceRepository(session, context_runtime.workspace_id).save_issue(1003, "other-current-issue",
            code="posting_incomplete")
    from ft.application.cash_import_plans import CashImportPlanError
    _assert_atomic_failure(context_runtime, lambda: _commit(service, plan), CashImportPlanError, code='plan_changed')


def _mirror_files():
    platform, bank = _file(), _file(letter="b", provider="bank-1")
    bank = _as_source(bank, "icbc_debit")
    return platform, bank


def test_confirmed_relation_and_rows_are_saved_together(context_runtime):
    service, plan = _plan(context_runtime, _mirror_files())
    assert plan["item_counts"]["confirmed"] == 1
    receipt = _commit(service, plan)
    assert receipt["summary"]["relations_confirmed"] == 1
    assert receipt["summary"]["created_posted"] == 2
    assert receipt["pending_item_count"] == 0
    with context_runtime.sessions() as session:
        relation = session.scalar(select(TransactionRelationModel).where(TransactionRelationModel.state == "confirmed"))
        assert relation.primary_transaction_id != relation.secondary_transaction_id
        assert relation.primary_component_id is not None and relation.secondary_component_id is not None


def test_receipt_counts_pending_items_after_merge_and_pages_current_locations(context_runtime, monkeypatch):
    service, plan = _plan(context_runtime, _mirror_files())
    original = service._discovery_issues
    def unresolved(session, current, identity, affected):
        original(session, current, identity, affected)
        parents = {int(value) for value in identity.values()}
        service._relation_problem(session, parents, "synthetic-current-attention", "relation_context_changed")
        affected.update(parents)
    monkeypatch.setattr(service, "_discovery_issues", unresolved)
    receipt = _commit(service, plan)
    assert receipt["needs_attention_count"] == 2 and receipt["pending_item_count"] == 1
    assert "affected_transaction_ids" not in receipt and "projection_refs" not in receipt
    page = service.results(receipt["receipt_id"], result_kind="row", limit=200)
    assert len(page["projection_refs"]) == 2
    assert len({item["projection_id"] for item in page["projection_refs"]}) == 1
    assert page["projection_version"] == receipt["projection_version"]


def test_old_receipt_pages_follow_current_split_and_deletion_without_rewriting_summary(context_runtime):
    from datetime import datetime, timezone
    from ft.application.cash_projections import CashProjectionService
    service, plan = _plan(context_runtime, _mirror_files())
    receipt = _commit(service, plan)
    page = service.results(receipt["receipt_id"], result_kind="row")
    assert len({item["projection_id"] for item in page["projection_refs"]}) == 1
    identifiers = {int(item["transaction_id"]) for item in page["items"]}
    with context_runtime.sessions.begin() as session:
        relation = session.scalar(select(TransactionRelationModel).where(TransactionRelationModel.state == "confirmed"))
        relation.active = False
        session.flush()
        CashProjectionService.maintain_in_session(session, context_runtime.workspace_id, identifiers)
    split = service.results(receipt["receipt_id"], result_kind="row")
    assert len({item["projection_id"] for item in split["projection_refs"]}) == 2
    removed = min(identifiers)
    with context_runtime.sessions.begin() as session:
        session.get(CashTransactionModel, removed).deleted_at = datetime.now(timezone.utc)
        session.flush()
        CashProjectionService.maintain_in_session(session, context_runtime.workspace_id, {removed})
    remaining = service.results(receipt["receipt_id"], result_kind="row")
    assert {int(item["transaction_id"]) for item in remaining["projection_refs"]} == identifiers - {removed}
    assert remaining["projection_version"] > receipt["projection_version"]
    assert service.receipt(receipt["receipt_id"]) == receipt


def test_relation_save_failure_rolls_back_records_relations_and_attention(context_runtime, monkeypatch):
    from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
    service, plan = _plan(context_runtime, _mirror_files())
    original = CurrentCashRelationRepository.add
    def fail(self, values):
        original(self, values)
        raise RuntimeError("synthetic relation failure after flush")
    monkeypatch.setattr(CurrentCashRelationRepository, "add", fail)
    _assert_atomic_failure(context_runtime, lambda: _commit(service, plan), CashCommitFailure)


def _refund_bundle():
    a, b = _mirror_files()
    a = replace(a, document=replace(a.document, rows=(replace(a.document.rows[0], source_explanation={"txn_id": "order1"}),)))
    c = _file(letter="c", amount="12.5", provider="003")
    c = replace(c, document=replace(c.document, rows=(replace(c.document.rows[0], fields={**c.document.rows[0].fields,
        "record_type": "refund"}, source_explanation={"txn_id": "order1_refund", "_is_refund": True}),)))
    d = replace(c, file_ref="file_d", client_file_ref="client_d", digest="d" * 64, source_type="icbc_debit",
        document=replace(c.document, source_type="icbc_debit", rows=(replace(c.document.rows[0], provider_id="004",
            source_explanation={"_is_refund": True, "_icbc_refund_merchant_trusted": True}),)))
    d = _as_source(d, "icbc_debit")
    return a, b, c, d


@pytest.mark.parametrize("defer", [True, False])
def test_full_refund_bundle_is_joint_and_zero_amount_remains_visible_before_and_after_commit(context_runtime, defer):
    from ft.adapters.relational.models import CashProjectionModel
    service, plan = _plan(context_runtime, _refund_bundle())
    assert len(plan["items"]) == 1 and Decimal(plan["items"][0]["display"]["amount"]) == 0
    if defer:
        staged = service.get(plan["session_id"]).session
        group, = staged.plan["relation_groups"]
        plan = service.preview(staged.session_id, expected_session_revision=staged.revision, previous_plan_id=plan["plan_id"],
            account_selections=staged.preview_input["account_selections"],
            relation_choices=[{"suggestion_id": group["suggestion_id"], "action": "defer"}])
        assert len(plan["items"]) == 4 and all(item["confirmation_status"] == "pending" for item in plan["items"])
    receipt = _commit(service, plan)
    assert receipt["summary"]["created_posted"] == 4
    assert receipt["summary"]["relations_deferred" if defer else "relations_confirmed"] == 1
    identifiers = {int(row["transaction_id"]) for row in _results(service, receipt, "row")}
    with context_runtime.sessions() as session:
        relations = session.scalars(select(TransactionRelationModel)).all()
        assert len(relations) >= 3 and len({row.decision_group_id for row in relations}) == 1
        assert {row.state for row in relations} == ({"draft"} if defer else {"confirmed"})
        projections = session.scalars(select(CashProjectionModel).where(CashProjectionModel.root_cash_transaction_id.in_(identifiers))).all()
        assert len(projections) == (4 if defer else 1) and all(row.visible for row in projections)
        if not defer:
            assert projections[0].net_amount == 0 and projections[0].hidden_reason is None


def test_failure_after_one_joint_edge_rolls_back_whole_bundle_and_entire_command(context_runtime, monkeypatch):
    from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
    files = list(_refund_bundle())
    for letter, source in (("e", "alipay"), ("f", "ccb_debit")):
        file = _file(letter=letter, amount="-3", provider="another-" + letter)
        files.append(_as_source(file, source) if source == "ccb_debit" else
            replace(file, source_type=source, document=replace(file.document, source_type=source)))
    service, plan = _plan(context_runtime, files)
    original, attempts = CurrentCashRelationRepository.add, []
    def fail(self, values):
        row = original(self, values)
        attempts.append(values["kind"])
        if len(attempts) == 2:
            raise RuntimeError("synthetic middle edge failure")
        return row
    monkeypatch.setattr(CurrentCashRelationRepository, "add", fail)
    _assert_atomic_failure(context_runtime, lambda: _commit(service, plan), CashCommitFailure)


def test_failed_required_row_prevents_group_from_using_old_record_as_substitute(context_runtime, monkeypatch):
    from ft.adapters.relational.cash_sources import CashSourceRepository
    platform, bank = _mirror_files()
    with context_runtime.sessions.begin() as session:
        _bind_existing(session, context_runtime, bank)
    changed = replace(bank, document=replace(bank.document, rows=(replace(bank.document.rows[0],
        fields={**bank.document.rows[0].fields, "note": "新的原文"}),)))
    service, plan = _plan(context_runtime, (platform, changed))
    original = CashSourceRepository.save_occurrence
    def fail(self, identifier, row, **kwargs):
        if row.source_type == "icbc_debit":
            raise RuntimeError("synthetic existing operation failure")
        return original(self, identifier, row, **kwargs)
    monkeypatch.setattr(CashSourceRepository, "save_occurrence", fail)
    _assert_atomic_failure(context_runtime, lambda: _commit(service, plan), CashCommitFailure)


def test_integrity_failure_uses_business_reason_and_does_not_leak_driver_code(context_runtime, monkeypatch):
    service, plan = _plan(context_runtime, (_file(), _file(letter="b", provider="002", amount="-3")))
    original = service._save_row
    def invalid(session, operation, sources):
        result = original(session, operation, sources)
        if operation["row_refs"] == ["file_a:r1"]:
            session.get(CashTransactionModel, result[0]).currency = "x"
            session.flush()
        return result
    monkeypatch.setattr(service, "_save_row", invalid)
    _assert_atomic_failure(context_runtime, lambda: _commit(service, plan), CashCommitFailure)


def test_new_session_with_identical_bill_is_silent_duplicate_and_does_not_rewrite_balance(context_runtime):
    from ft.adapters.relational.models import LedgerSnapshotModel
    service, plan = _plan(context_runtime)
    first = _commit(service, plan)
    with context_runtime.sessions() as session:
        before = session.get(LedgerSnapshotModel, context_runtime.workspace_id).version
    service, plan = _plan(context_runtime)
    second = _commit(service, plan, "new-import")
    assert second["status"] == "no_changes" and second["summary"]["duplicate"] == 1
    assert _results(service, first, "row")[0]["transaction_id"] == _results(service, second, "row")[0]["transaction_id"]
    with context_runtime.sessions() as session:
        assert session.get(LedgerSnapshotModel, context_runtime.workspace_id).version == before


def test_reimport_preserves_historical_relation_failure_without_creating_retry_body(context_runtime):
    from ft.adapters.relational.cash_sources import CashSourceRepository
    from ft.application.cash_projections import CashProjectionService
    service, plan = _plan(context_runtime, _mirror_files())
    first = _commit(service, plan)
    identifiers = {int(row["transaction_id"]) for row in _results(service, first, "row")}
    with context_runtime.sessions.begin() as session:
        for identifier in identifiers:
            CashSourceRepository(session, context_runtime.workspace_id).save_issue(identifier,
                "relation:historical-failure", code="relation_save_failed")
    CashProjectionService(context_runtime.sessions, context_runtime.workspace_id).rebuild()
    service, plan = _plan(context_runtime, _mirror_files())
    second = _commit(service, plan, "reimport")
    assert second["summary"]["duplicate"] == 2
    with context_runtime.sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionIssueModel)) == 2


def test_reimport_of_identity_draft_preserves_its_unresolved_candidate_set(context_runtime):
    from ft.adapters.relational.models import CashIssueCandidateModel
    with context_runtime.sessions.begin() as session:
        _bind_existing(session, context_runtime, _file(provider=None))
    incoming = _file(letter="b", provider=None, note="新的原文")
    first, plan = _plan(context_runtime, (incoming,))
    receipt = _commit(first, plan)
    assert receipt["summary"]["saved_draft"] == 1
    with context_runtime.sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashIssueCandidateModel)) == 1
    second, plan = _plan(context_runtime, (incoming,))
    _commit(second, plan, "same-identity-draft")
    with context_runtime.sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashIssueCandidateModel)) == 1


def test_known_partial_draft_component_is_saved_but_invalid_structure_rejects_plan(context_runtime):
    from ft.application.cash_import_plans import CashImportPlanError
    first, second = _file(), _file(letter="b", provider="002", amount="-3")
    service, plan = _plan(context_runtime, (first, second), rows=[{"row_ref": "file_a:r1", "action": "defer", "patch": {
        "components": [{"ordinal": 0, "account_id": "101"}]}}])
    receipt = _commit(service, plan)
    assert receipt["summary"]["saved_draft"] == 1 and receipt["summary"]["created_posted"] == 1
    identifier = int(_results(service, receipt, "row")[0]["transaction_id"])
    with context_runtime.sessions() as session:
        component = session.scalar(select(CashTransactionComponentModel).where(CashTransactionComponentModel.cash_transaction_id == identifier))
        assert component.account_id == 101 and component.amount is None and component.currency is None
    with pytest.raises(CashImportPlanError, match="plan_input_invalid"):
        _plan(context_runtime, (_file(letter="c", provider="003"), second), rows=[{
            "row_ref": "file_c:r1", "action": "defer", "patch": {"components": [{"account_id": "101"}]}}])


@pytest.mark.parametrize("same_key", [True, False])
def test_concurrent_confirmations_consume_the_session_once(context_runtime, same_key):
    from concurrent.futures import ThreadPoolExecutor
    from ft.adapters.relational.cash_mutations import CashMutationConflict
    service, plan = _plan(context_runtime)
    def confirm(key):
        try:
            return _commit(_service(context_runtime, service._staging), plan, key)
        except CashMutationConflict as error:
            return {"code": error.code, "receipt_id": error.receipt_id}
    with ThreadPoolExecutor(max_workers=2) as executor:
        first, second = list(executor.map(confirm, ["race-1", "race-1" if same_key else "race-2"]))
    assert first["receipt_id"] == second["receipt_id"]
    if same_key:
        assert first == second
    else:
        assert sum(value.get("code") == "session_committed" for value in (first, second)) == 1
    with context_runtime.sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel).where(
            CashTransactionModel.source_type == "alipay")) == 1


@pytest.mark.parametrize("phase", ["receipt", "projection"])
def test_final_receipt_or_projection_failure_rolls_back_all_business_writes(context_runtime, monkeypatch, phase):
    from ft.adapters.relational.cash_mutations import CashMutationRepository
    from ft.application.cash_projections import CashProjectionService
    service, plan = _plan(context_runtime)
    with context_runtime.sessions() as session:
        before = session.scalar(select(func.count()).select_from(CashTransactionModel))
    def fail(*args, **kwargs):
        raise RuntimeError("synthetic final failure")
    target, method = (CashMutationRepository, "create") if phase == "receipt" else (CashProjectionService, "maintain_in_session")
    original = getattr(target, method)
    monkeypatch.setattr(target, method, fail)
    with pytest.raises(CashCommitFailure) as failure:
        _commit(service, plan)
    assert failure.value.commit_state == "not_committed"
    with context_runtime.sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == before
        assert session.scalar(select(func.count()).select_from(CashSourceBindingModel)) == 0
    monkeypatch.setattr(target, method, original)
    receipt = _commit(service, plan)
    assert receipt["summary"]["created_posted"] == 1


def test_deferred_source_update_preserves_current_description_and_stores_the_new_explanation(context_runtime):
    first = _file()
    with context_runtime.sessions.begin() as session:
        _bind_existing(session, context_runtime, first)
        session.get(CashTransactionModel, 1003).relation_metadata = {"status": "原来源状态"}
    changed = _file(note="新的原文")
    changed = replace(changed, document=replace(changed.document, rows=(replace(changed.document.rows[0],
        source_explanation={"status": "新来源状态"}),)))
    service, plan = _plan(context_runtime, (changed,), rows=[{"row_ref": "file_a:r1", "action": "defer"}])
    receipt = _commit(service, plan)
    assert receipt["summary"]["updated_posted"] == 1
    with context_runtime.sessions() as session:
        parent = session.get(CashTransactionModel, 1003)
        assert parent.note == first.document.rows[0].fields["note"]
        assert parent.relation_metadata == {"status": "原来源状态"}
        issue = session.scalar(select(CashTransactionIssueModel).where(CashTransactionIssueModel.transaction_id == 1003))
        assert issue.pending_fields["note"] == "新的原文"
        assert issue.pending_fields["_source_explanation"] == {"status": "新来源状态"}


@pytest.mark.parametrize("mirror", [False, True])
def test_second_already_previewed_session_rejects_stale_plan_after_first_commit(context_runtime, mirror):
    files = _mirror_files() if mirror else (_file(),)
    first, old = _plan(context_runtime, files)
    second, stale = _plan(context_runtime, files)
    _commit(first, old, "first-session")
    from ft.application.cash_import_plans import CashImportPlanError
    _assert_atomic_failure(context_runtime, lambda: _commit(second, stale, "second-session"), CashImportPlanError, code="plan_changed")


def test_concurrent_equivalent_duplicate_rejects_stale_plan_without_changing_record(context_runtime):
    from ft.adapters.relational.cash_sources import CashSourceRepository
    from ft.application.cash_import_source_rows import source_batch_rows
    first, a = _plan(context_runtime, (_file(),))
    file = _file(letter="b")
    second, b = _plan(context_runtime, (file,))
    committed = _commit(first, a, "original-file")
    identifier = int(_results(first, committed, "row")[0]["transaction_id"])
    with context_runtime.sessions() as session:
        revision = session.get(CashTransactionModel, identifier).row_revision
    from ft.application.cash_import_plans import CashImportPlanError
    _assert_atomic_failure(context_runtime, lambda: _commit(second, b, "other-file"), CashImportPlanError, code="plan_changed")


@pytest.mark.parametrize("failure", [True, False])
def test_success_clears_body_and_failed_commit_keeps_original_session_only(context_runtime, monkeypatch, failure):
    service, plan = _plan(context_runtime)
    if failure:
        def fail(*args, **kwargs): raise RuntimeError("synthetic row failure")
        monkeypatch.setattr(service, "_save_row", fail)
        _assert_atomic_failure(context_runtime, lambda: _commit(service, plan))
        staged = service._staging.get(plan["session_id"], workspace_id=context_runtime.workspace_id, user_id=USER)
        assert not staged.cancelled and staged.files and staged.plan is not None
        assert len(service._staging._entries) == 1
    else:
        receipt = _commit(service, plan)
        staged = service._staging.get(plan["session_id"], workspace_id=context_runtime.workspace_id, user_id=USER)
        assert not any(key.startswith("retry_context") for key in receipt)
        assert staged.cancelled and not staged.files and staged.plan is None


def test_existing_relation_with_changed_allocation_is_not_mistaken_for_same_decision(context_runtime):
    from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
    from ft.application.cash_projections import CashProjectionService
    a, _b, c, _d = _refund_bundle()
    first, old = _plan(context_runtime, (a, c))
    second, stale = _plan(context_runtime, (a, c))
    receipt = _commit(first, old, "first-allocation")
    identifiers = {int(item["transaction_id"]) for item in _results(first, receipt, "row")}
    with context_runtime.sessions.begin() as session:
        repository = CurrentCashRelationRepository(session, context_runtime.workspace_id)
        row = session.scalar(select(TransactionRelationModel))
        repository.replace(row.id, {"applied_amount": "5"}, expected_revision=row.row_revision)
        CashProjectionService.maintain_in_session(session, context_runtime.workspace_id, identifiers)
    from ft.application.cash_import_plans import CashImportPlanError
    _assert_atomic_failure(context_runtime, lambda: _commit(second, stale, "second-allocation"),
        CashImportPlanError, code="plan_changed")


def test_draft_can_replace_component_at_same_ordinal_before_finalization(context_runtime):
    service, plan = _plan(context_runtime, mapped=False)
    receipt = _commit(service, plan)
    identifier = int(_results(service, receipt, "row")[0]["transaction_id"])
    with context_runtime.sessions() as session:
        old = session.scalar(select(CashTransactionComponentModel.id).where(CashTransactionComponentModel.cash_transaction_id == identifier))
    service, plan = _plan(context_runtime, rows=[{"row_ref": "file_a:r1", "action": "apply", "patch": {
        "account_id": "101", "components": [{"ordinal": 0, "account_id": "101", "amount": "-12.5", "currency": "CNY"}]}}])
    assert plan["summary"]["finalize_draft"] == 1
    receipt = _commit(service, plan, "finalize")
    assert receipt["summary"]["finalized_draft"] == 1
    with context_runtime.sessions() as session:
        part = session.scalar(select(CashTransactionComponentModel).where(CashTransactionComponentModel.cash_transaction_id == identifier))
        assert part.id != old and part.ordinal == 0
        assert session.get(CashTransactionModel, identifier).state == "posted"


def test_draft_can_swap_component_ordinals_without_changing_component_identities(context_runtime):
    patch = {"cash_granularity": "aggregate", "account_id": None, "components": [
        {"ordinal": 0, "account_id": "101", "amount": "-6.25", "currency": "CNY"},
        {"ordinal": 1, "account_id": "102", "amount": "-6.25", "currency": "CNY"}]}
    service, plan = _plan(context_runtime, mapped=False,
        rows=[{"row_ref": "file_a:r1", "action": "defer", "patch": patch}])
    receipt = _commit(service, plan)
    identifier = int(_results(service, receipt, "row")[0]["transaction_id"])
    with context_runtime.sessions() as session:
        original = [(row.id, row.account_id, row.ordinal) for row in session.scalars(select(CashTransactionComponentModel).where(
            CashTransactionComponentModel.cash_transaction_id == identifier).order_by(CashTransactionComponentModel.ordinal))]
    patch["components"] = [{"component_id": str(part), "account_id": str(account), "ordinal": 1 - ordinal,
        "amount": "-6.25", "currency": "CNY"} for part, account, ordinal in original]
    service, plan = _plan(context_runtime, mapped=False,
        rows=[{"row_ref": "file_a:r1", "action": "defer", "patch": patch}])
    receipt = _commit(service, plan, "swap-components")
    assert receipt["summary"]["saved_draft"] == 1
    with context_runtime.sessions() as session:
        current = {(row.id, row.account_id, row.ordinal) for row in session.scalars(select(CashTransactionComponentModel).where(
            CashTransactionComponentModel.cash_transaction_id == identifier))}
        assert current == {(part, account, 1 - ordinal) for part, account, ordinal in original}


def test_imported_type_conflict_preserves_members_and_existing_projection(context_runtime, monkeypatch):
    from ft.adapters.relational.projections import RelationalCashProjectionRepository
    from ft.adapters.relational.models import CashProjectionModel
    files = _mirror_files()
    service, plan = _plan(context_runtime, files)
    receipt = _commit(service, plan)
    ids = {int(row["transaction_id"]) for row in _results(service, receipt, "row")}
    file = files[0]
    changed = replace(file, document=replace(file.document, rows=(replace(file.document.rows[0], fields={
        **file.document.rows[0].fields, "record_type": "transfer_out", "record_subtype": "ordinary_transfer"}),)))
    service, plan = _plan(context_runtime, (changed,))
    assert plan['items'] == [] and plan['summary']['skip'] == 1
    original, calls = RelationalCashProjectionRepository.replace_active_components, []
    def observe(self, *args, **kwargs):
        calls.append(1)
        return original(self, *args, **kwargs)
    monkeypatch.setattr(RelationalCashProjectionRepository, "replace_active_components", observe)
    receipt = _commit(service, plan, "type-update")
    assert receipt["summary"]["updated_posted"] == 0 and calls == []
    assert receipt['summary']['skipped'] == 1
    with context_runtime.sessions() as session:
        relation = session.scalar(select(TransactionRelationModel))
        assert relation.active is True
        assert len(session.scalars(select(CashProjectionModel).where(CashProjectionModel.root_cash_transaction_id.in_(ids))).all()) == 1


@pytest.mark.parametrize("failed_file", [True, False])
def test_file_failure_and_normal_whitelist_skip_have_distinct_result_status(context_runtime, failed_file):
    if failed_file:
        bad = replace(_file(letter="b"), status="failed", document=None, error_code="statement_unreadable")
        files = (_file(), bad)
    else:
        row = replace(_file().document.rows[0], fields=None, disposition="skipped", error_code="account_opening")
        files = (_file(rows=(row,)),)
    service, plan = _plan(context_runtime, files)
    receipt = _commit(service, plan)
    assert receipt["status"] == ("partial" if failed_file else "no_changes")
    assert receipt["commit_outcome"] == {"has_new_failures": False, "has_business_writes": failed_file, "reason_codes": []}


def test_empty_page_failure_rolls_back_entire_command(context_runtime, monkeypatch):
    from ft.adapters.relational.cash_source_documents import CashSourceDocumentRepository
    from ft.adapters.relational.models import CashSourceEmptyPageModel
    from test_cash_source_documents import document_coverage, document_file, document_row
    document = document_file(document_coverage({"1": "a" * 64, "2": "b" * 64, "3": "c" * 64}),
        source="alipay", rows=(document_row(),))
    other = _file(letter="b", provider="different-account", amount="-3")
    service, plan = _plan(context_runtime, (document, other))
    original = CashSourceDocumentRepository.save_empty_pages
    def fail(self, files):
        original(self, files)
        raise RuntimeError("synthetic empty-page failure after flush")
    monkeypatch.setattr(CashSourceDocumentRepository, "save_empty_pages", fail)
    _assert_atomic_failure(context_runtime, lambda: _commit(service, plan), CashCommitFailure)


def test_connection_failure_aborts_outer_transaction_instead_of_becoming_a_row_skip(context_runtime, monkeypatch):
    from sqlalchemy.exc import OperationalError
    service, plan = _plan(context_runtime, (_file(), _file(letter="b", provider="002", amount="-3")))
    original = service._save_row
    def fail(session, operation, sources):
        result = original(session, operation, sources)
        if operation["row_refs"] == ["file_b:r1"]:
            raise OperationalError("synthetic driver failure", {}, Exception("private detail"), connection_invalidated=True)
        return result
    monkeypatch.setattr(service, "_save_row", fail)
    with pytest.raises(CashCommitFailure) as failure:
        _commit(service, plan)
    assert failure.value.commit_state == "not_committed"
    with context_runtime.sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel).where(
            CashTransactionModel.source_type == "alipay")) == 0


def test_permission_is_rechecked_even_when_replaying_a_receipt(context_runtime):
    from ft.application.cash_import_context import CashImportPermissionDenied
    service, plan = _plan(context_runtime)
    _commit(service, plan)
    with context_runtime.sessions.begin() as session:
        session.scalar(select(WorkspaceMembershipModel).where(WorkspaceMembershipModel.user_id == USER)).role = "viewer"
    with pytest.raises(CashImportPermissionDenied):
        _commit(service, plan)


def test_unknown_result_resolves_under_same_lock_before_and_after_commit_or_cancellation(context_runtime):
    service, plan = _plan(context_runtime)
    assert service.resolve(plan["plan_id"], idempotency_key="synthetic-confirm") == {"status": "retry_original"}
    receipt = _commit(service, plan)
    service._staging._entries.clear()
    assert service.resolve(plan["plan_id"], idempotency_key="synthetic-confirm") == {"status": "committed", "receipt": receipt}
    assert service.receipt_by_key("synthetic-confirm") == receipt
    other, pending = _plan(context_runtime)
    other.cancel(pending["session_id"])
    assert other.resolve(pending["plan_id"], idempotency_key="not-submitted") == {"status": "not_committed"}


def test_receipt_reads_are_user_scoped_and_only_contain_safe_results(context_runtime):
    from ft.application.cash_import_commit import CashImportCommandService
    from ft.application.cash_import_plans import CashImportPlanError
    from ft.adapters.relational.models import UserModel
    service, plan = _plan(context_runtime)
    receipt = _commit(service, plan)
    assert service.receipt(receipt["receipt_id"]) == receipt
    assert "session_digest" not in receipt and "request_digest" not in receipt
    other_user = "00000000-0000-4000-8000-000000000099"
    with context_runtime.sessions.begin() as session:
        session.add(UserModel(id=other_user, email="another-receipt@example.test", password_hash="synthetic"))
        session.flush()
        session.add(WorkspaceMembershipModel(user_id=other_user, workspace_id=context_runtime.workspace_id, role="admin"))
    other = CashImportCommandService(RelationalUnitOfWork(context_runtime.sessions, context_runtime.workspace_id), service._staging, user_id=other_user)
    for read in (lambda: other.receipt(receipt["receipt_id"]), lambda: other.results(receipt["receipt_id"]),
        lambda: other.receipt_by_key("synthetic-confirm")):
        with pytest.raises(CashImportPlanError, match="receipt_not_found"):
            read()


def test_discovery_programming_error_stops_import_preview(context_runtime, monkeypatch):
    import ft.application.cash_import_business_plan as module
    def broken(*_args, **_kwargs):
        raise RuntimeError("private bill body")
    monkeypatch.setattr(module, "plan_relation_proposals", broken)
    _assert_atomic_failure(context_runtime, lambda: _plan(context_runtime, _mirror_files()), RuntimeError)

def test_execution_failure_rolls_back_entire_import_without_retry_capsule(context_runtime, monkeypatch):
    from ft.application.cash_plan_execution import CashPlanWriteOperations
    from ft.adapters.relational.models import CashMutationReceiptModel
    files = (_file(), _file(letter="b", provider="002"))
    service, plan = _plan(context_runtime, files)
    with context_runtime.sessions() as session:
        before = session.scalar(select(func.count()).select_from(CashTransactionModel))
    original = CashPlanWriteOperations._save_row
    count = 0
    def fail_second(self, *args, **kwargs):
        nonlocal count
        result = original(self, *args, **kwargs)
        count += 1
        if count == 2: raise RuntimeError("synthetic internal failure after write")
        return result
    monkeypatch.setattr(CashPlanWriteOperations, "_save_row", fail_second)
    with pytest.raises(CashCommitFailure) as rejected:
        _commit(service, plan)
    assert rejected.value.commit_state == "not_committed"
    with context_runtime.sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == before
        assert session.scalar(select(CashMutationReceiptModel).where(
            CashMutationReceiptModel.idempotency_key == "synthetic-confirm")) is None
    assert len(service._staging._entries) == 1


def test_success_has_no_failed_subset_recovery_capability(context_runtime):
    service, plan = _plan(context_runtime)
    receipt = _commit(service, plan)
    assert not any(key.startswith("retry_context") for key in receipt)
    assert not hasattr(service, "retry_session")


@pytest.mark.parametrize("empty", [False, True])
def test_preview_rejected_document_namespace_does_not_block_other_legal_rows(context_runtime, empty):
    from test_cash_source_documents import document_file, document_coverage, document_row
    rows = () if empty else (document_row(),)
    first = document_file(document_coverage({"2": "b" * 64}), rows=rows, source="alipay")
    second = document_file(document_coverage({"2": "f" * 64}), rows=rows, source="alipay", letter="b")
    service, plan = _plan(context_runtime, (first, second, _file(letter="c", provider="synthetic-good")))
    assert plan["summary"]["create_posted"] == 1 and plan["summary"].get("skip", 0) == (0 if empty else 2)
    receipt = _commit(service, plan)
    assert receipt["summary"]["created_posted"] == 1 and receipt["summary"].get("skipped", 0) == (0 if empty else 2)
    assert receipt["commit_outcome"]["has_new_failures"] is False
