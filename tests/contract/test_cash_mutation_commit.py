"""无原文件的正式编辑及草稿转正式使用一次确认和永久消费。"""
from dataclasses import replace
from decimal import Decimal

import pytest
from sqlalchemy import func, select

from ft.adapters.relational.cash_mutations import CashMutationConflict, CashMutationRepository
from ft.adapters.relational.cash_sources import CashSourceRepository
from ft.adapters.relational.models import (AccountModel, CashMutationReceiptModel, CashTransactionIssueModel,
    CashTransactionModel, TransactionRelationModel)
from ft.adapters.relational.repositories import RelationalSnapshotRepository
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.application.cash_projections import CashProjectionService
from ft.application.cash_commit_outcome import CashCommitFailure
from ft.application.parsed_import_staging import InMemoryParsedImportStore
from tests.contract.test_cash_import_business_plan import _existing_mirror
from tests.contract.test_cash_import_commit import configured, _plan, _commit as _import_commit, _results
from tests.contract.test_cash_import_operations import _file
from tests.contract.test_cash_mutation_business_plan import _posted, _preview
from tests.contract.test_cash_record_maintenance import _draft, _complete, _service as _records
from tests.contract.test_parsed_cash_import_context import USER, context_runtime


def _service(runtime, *, store=None, user_id=USER):
    from ft.application.cash_mutation_commit import CashMutationCommandService
    return CashMutationCommandService(RelationalUnitOfWork(runtime.sessions, runtime.workspace_id),
        store or InMemoryParsedImportStore(), user_id=user_id)


def _commit(service, view, key="synthetic-mutation-confirm"):
    return service.commit(view["plan_id"], expected_plan_revision=view["plan_revision"], idempotency_key=key)


def _snapshot(runtime):
    with runtime.sessions() as session:
        return RelationalSnapshotRepository(session, runtime.workspace_id).load()


def _category_plan(runtime, *, split=True):
    from ft.application.cash_categories import CashCategoryService
    with runtime.sessions.begin() as session:
        _, relation_id = _existing_mirror(session, runtime)
        session.get(CashTransactionModel, 1003).note = "成员甲"
        session.get(CashTransactionModel, 1001).note = "成员乙"
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    category = CashCategoryService(runtime.sessions, runtime.workspace_id).create(name="整笔分类")["id"]
    service = _service(runtime)
    view = _preview(service, runtime, "1003", patch={"category_id": category, "note": "整笔备注"})
    return service, view, relation_id, category


def test_posted_edit_commits_once_and_replays_after_context_and_target_are_gone(context_runtime):
    runtime, identifier = context_runtime, _posted(context_runtime)
    balances = _snapshot(runtime)
    service = _service(runtime)
    view = _preview(service, runtime, identifier, patch={"note": "明确保存"})
    before = _records(runtime).read(identifier)
    receipt = _commit(service, view)
    after = _records(runtime).read(identifier)
    assert receipt["committed"] and receipt["status"] == "completed" and receipt["operation_kind"] == "mutation"
    assert receipt["summary"]["updated_posted"] == 1
    assert after["entry"]["fields"]["note"] == "明确保存" and after["row_revision"] == before["row_revision"]
    assert _snapshot(runtime) == balances
    service._staging._entries.clear()
    with runtime.sessions.begin() as session:
        from datetime import datetime, timezone
        session.get(CashTransactionModel, int(identifier)).deleted_at = datetime.now(timezone.utc)
    assert _commit(service, view) == receipt
    assert service.receipt(receipt["receipt_id"]) == receipt
    assert service.receipt_by_key("synthetic-mutation-confirm") == receipt
    assert service.resolve(view["plan_id"], idempotency_key="synthetic-mutation-confirm") == {"status": "committed", "receipt": receipt}


def test_complete_draft_finalizes_same_identity_and_changes_balances_only_once(context_runtime):
    runtime, identifier = context_runtime, _draft(context_runtime)
    before = _records(runtime).read(identifier)
    balances = _snapshot(runtime)
    with runtime.sessions() as session:
        account = session.get(AccountModel, 101)
        kind, name = account.type, account.name
    service = _service(runtime)
    view = _preview(service, runtime, identifier, intent="finalize_draft", patch=_complete(before))
    receipt = _commit(service, view)
    assert receipt["summary"]["finalized_draft"] == 1
    after = _records(runtime).read(identifier)
    assert after["transaction_id"] == identifier and after["state"] == "posted" and after["confirmation_status"] == "confirmed"
    actual = _snapshot(runtime)
    old = Decimal(balances.get("accounts", {}).get(kind, {}).get(name, {}).get("CNY", "0"))
    assert Decimal(actual["accounts"][kind][name]["CNY"]) - old == Decimal("-12.5")
    assert _commit(service, view) == receipt and _snapshot(runtime) == actual


def test_common_fields_edit_updates_one_entry_and_keeps_sources_and_group(context_runtime):
    runtime = context_runtime
    service, view, relation_id, category = _category_plan(runtime)
    balances = _snapshot(runtime)
    assert view["additional_effects"] == []
    receipt = _commit(service, view)
    assert receipt["summary"]["updated_posted"] == 1 and receipt["summary"]["relations_removed"] == 0
    with runtime.sessions() as session:
        first, second = (session.get(CashTransactionModel, identifier) for identifier in (1003, 1001))
        assert first.note == "成员甲" and second.note == "成员乙"
        assert (first.category_id, second.category_id) == ('category-food', 'category-income')
        assert session.get(TransactionRelationModel, relation_id).active
    entry = _records(runtime).read("1003")["entry"]
    assert entry["fields"]["note"] == "整笔备注" and entry["fields"]["category_id"] == category
    page = service.results(receipt["receipt_id"], result_kind="row")
    assert len(page["items"]) == 1
    assert _snapshot(runtime) == balances


def test_failure_after_second_category_member_rolls_back_the_entire_record_unit(context_runtime, monkeypatch):
    from ft.application.cash_record_writer import CashRecordWriter
    runtime = context_runtime
    service, view, relation_id, category = _category_plan(runtime)
    before = {identifier: _records(runtime).read(identifier) for identifier in ("1003", "1001")}
    balances = _snapshot(runtime)
    original, attempted = CashRecordWriter.save, []
    def fail(self, session, operation, source_rows):
        result = original(self, session, operation, source_rows)
        attempted.append(operation["transaction_id"])
        if len(attempted) == 1:
            raise RuntimeError("synthetic private second member failure")
        return result
    monkeypatch.setattr(CashRecordWriter, "save", fail)
    receipt = _commit(service, view)
    assert len(attempted) == 1 and receipt["status"] == "failed" and receipt["summary"]["skipped"] == 1
    assert receipt["summary"]["updated_posted"] == 0 and receipt["summary"]["relations_removed"] == 0
    assert {identifier: _records(runtime).read(identifier) for identifier in before} == before
    with runtime.sessions() as session:
        assert session.get(TransactionRelationModel, relation_id).active
    assert _snapshot(runtime) == balances
    assert {item["code"] for item in _results(service, receipt, "row")} == {"row_save_failed"}


def test_retained_source_problem_body_and_revision_survive_formal_edit(context_runtime):
    runtime, identifier = context_runtime, _posted(context_runtime)
    with runtime.sessions.begin() as session:
        issue = CashSourceRepository(session, runtime.workspace_id).save_issue(identifier, "source:pending", code="source_update_pending",
            pending_fields={"counterparty": "稍后采用"}, pending_source_payload={"备注": "原始待采用正文"})
        issue_id, revision = issue.id, issue.revision
    service = _service(runtime)
    receipt = _commit(service, _preview(service, runtime, identifier, patch={"note": "人工备注"}))
    assert receipt["needs_attention_count"] == receipt["pending_item_count"] == 1
    with runtime.sessions() as session:
        issue = session.get(CashTransactionIssueModel, issue_id)
        assert issue.revision == revision and issue.pending_fields == {"counterparty": "稍后采用"}
        assert issue.pending_source_payload == {"备注": "原始待采用正文"}


@pytest.mark.parametrize("failure", ["projection", "receipt"])
def test_publication_failure_rolls_back_record_and_keeps_context_retryable(context_runtime, monkeypatch, failure):
    runtime, identifier = context_runtime, _draft(context_runtime)
    before = _records(runtime).read(identifier)
    balances = _snapshot(runtime)
    service = _service(runtime)
    view = _preview(service, runtime, identifier, intent="finalize_draft", patch=_complete(before))
    if failure == "projection":
        original = CashProjectionService.maintain_in_session
        def fail(*args, **kwargs):
            original(*args, **kwargs)
            raise RuntimeError("synthetic publication failure")
        monkeypatch.setattr(CashProjectionService, "maintain_in_session", staticmethod(fail))
    else:
        original = CashMutationRepository.create
        def fail(*args, **kwargs):
            original(*args, **kwargs)
            raise RuntimeError("synthetic receipt failure")
        monkeypatch.setattr(CashMutationRepository, "create", fail)
    with pytest.raises(CashCommitFailure) as error:
        _commit(service, view)
    assert error.value.commit_state == "not_committed"
    assert _records(runtime).read(identifier) == before and _snapshot(runtime) == balances
    assert service.read_plan(view["plan_id"]) == view
    with runtime.sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashMutationReceiptModel).where(
            CashMutationReceiptModel.operation_kind == "mutation")) == 0


def test_new_relation_failure_keeps_finalized_record_and_visible_current_problem(context_runtime, monkeypatch):
    from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
    runtime = context_runtime
    bank = _file(letter="b", provider="bank-only")
    bank = replace(bank, source_type="icbc_debit", document=replace(bank.document, source_type="icbc_debit"))
    importer, plan = _plan(runtime, (bank,))
    _import_commit(importer, plan, "synthetic-existing-bank")
    identifier = _draft(runtime)
    before = _records(runtime).read(identifier)
    service = _service(runtime)
    view = _preview(service, runtime, identifier, intent="finalize_draft", patch=_complete(before))
    assert view["summary"]["relations_accepted"] == 1
    original = CurrentCashRelationRepository.add
    def fail(*args, **kwargs):
        original(*args, **kwargs)
        raise RuntimeError("synthetic private relation failure after flush")
    monkeypatch.setattr(CurrentCashRelationRepository, "add", fail)
    receipt = _commit(service, view)
    assert receipt["status"] == "partial" and receipt["summary"]["finalized_draft"] == 1
    assert receipt["summary"]["relations_failed"] == 1 and receipt["needs_attention_count"] == 2
    current = _records(runtime).read(identifier)
    assert current["state"] == "posted" and current["confirmation_status"] == "pending"
    assert any(issue["code"] == "relation_save_failed" for issue in current["issues"])
    with runtime.sessions() as session:
        assert session.scalar(select(func.count()).select_from(TransactionRelationModel)) == 0
    assert "synthetic private" not in str(receipt) + str(_results(service, receipt))


def test_posted_source_text_user_edit_is_rejected_and_full_refund_stays_confirmed(context_runtime):
    from tests.contract.test_cash_import_commit import _refund_bundle
    runtime = context_runtime
    importer, plan = _plan(runtime, _refund_bundle())
    imported = _import_commit(importer, plan)
    identifier = _results(importer, imported, "row")[0]["transaction_id"]
    before = _records(runtime).read(identifier)
    with pytest.raises(ValueError):
        _preview(_service(runtime), runtime, identifier,
            patch={"source_payload": {"备注": "伪造来源原文"}})
    assert _records(runtime).read(identifier) == before
    unchanged = _preview(_service(runtime), runtime, identifier)
    assert len(unchanged["items"]) == 1 and Decimal(unchanged["items"][0]["display"]["amount"]) == 0


def test_failure_after_one_edge_rolls_back_the_whole_new_group_but_keeps_previous_graph(context_runtime, monkeypatch):
    from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
    from tests.contract.test_cash_import_commit import _refund_bundle
    runtime = context_runtime
    files = _refund_bundle()
    importer, plan = _plan(runtime, files[:3])
    _import_commit(importer, plan)
    importer, plan = _plan(runtime, (files[3],), mapped=False)
    identifier = _results(importer, _import_commit(importer, plan, "synthetic-bank-refund-draft"), "row")[0]["transaction_id"]
    before = _records(runtime).read(identifier)
    part = before["fields"]["components"][0]
    service = _service(runtime)
    view = _preview(service, runtime, identifier, intent="finalize_draft", patch={"account_id": "101", "components": [{
        "component_id": part["component_id"], "ordinal": 0, "account_id": "101", "currency": "CNY", "amount": "12.5"}]})
    with runtime.sessions() as session:
        previous = {(row.id, row.active, row.state) for row in session.scalars(select(TransactionRelationModel))}
    original, attempts = CurrentCashRelationRepository.add, []
    def fail(*args, **kwargs):
        row = original(*args, **kwargs)
        attempts.append(row["id"])
        if len(attempts) == 2:
            raise RuntimeError("synthetic incomplete decision group")
        return row
    monkeypatch.setattr(CurrentCashRelationRepository, "add", fail)
    receipt = _commit(service, view)
    assert len(attempts) == 2 and receipt["summary"]["finalized_draft"] == 1 and receipt["summary"]["relations_failed"] == 1
    assert _records(runtime).read(identifier)["state"] == "posted"
    with runtime.sessions() as session:
        assert {(row.id, row.active, row.state) for row in session.scalars(select(TransactionRelationModel))} == previous


def test_discovery_exception_keeps_finalization_and_publishes_a_safe_current_problem(context_runtime, monkeypatch):
    import ft.application.cash_import_business_plan as business
    runtime, identifier = context_runtime, _draft(context_runtime)
    service = _service(runtime)
    def fail(*args, **kwargs):
        raise RuntimeError("synthetic private native pairing failure")
    monkeypatch.setattr(business, "plan_relation_proposals", fail)
    view = _preview(service, runtime, identifier, intent="finalize_draft", patch=_complete(_records(runtime).read(identifier)))
    receipt = _commit(service, view)
    current = _records(runtime).read(identifier)
    assert receipt["summary"]["finalized_draft"] == 1 and current["state"] == "posted" and current["confirmation_status"] == "pending"
    assert current["issues"] and "synthetic private" not in str(current) + str(receipt)
    assert receipt["status"] == "partial" and receipt["summary"]["relations_failed"] == 1
    result, = _results(service, receipt, "relation")
    assert result["outcome"] == "failed" and result["code"] == "relation_discovery_failed"


def test_import_discovery_failure_is_also_present_in_permanent_relation_results(context_runtime, monkeypatch):
    import ft.application.cash_import_business_plan as business
    def fail(*args, **kwargs):
        raise RuntimeError("synthetic private native pairing failure")
    monkeypatch.setattr(business, "plan_relation_proposals", fail)
    importer, plan = _plan(context_runtime)
    assert plan["summary"]["relations_failed"] == 1
    receipt = _import_commit(importer, plan)
    assert receipt["status"] == "partial" and receipt["summary"]["created_posted"] == 1
    assert receipt["summary"]["relations_failed"] == 1
    result, = _results(importer, receipt, "relation")
    assert result["outcome"] == "failed" and result["code"] == "relation_discovery_failed" and result["transaction_id"]


def test_two_failed_payment_components_have_one_current_discovery_problem_and_result(context_runtime, monkeypatch):
    import ft.application.cash_import_business_plan as business
    runtime, identifier = context_runtime, _draft(context_runtime)
    detail = _records(runtime).read(identifier)
    part = detail["fields"]["components"][0]
    def fail(*args, **kwargs):
        raise RuntimeError("synthetic per-component native failure")
    monkeypatch.setattr(business, "plan_relation_proposals", fail)
    service = _service(runtime)
    view = _preview(service, runtime, identifier, intent="finalize_draft", patch={"cash_granularity": "aggregate", "account_id": None,
        "components": [{"component_id": part["component_id"], "ordinal": 0, "account_id": "101", "currency": "CNY", "amount": "-5"},
            {"ordinal": 1, "account_id": "102", "currency": "CNY", "amount": "-7.5"}]})
    assert view["summary"]["relations_failed"] == 1
    receipt = _commit(service, view)
    assert receipt["summary"]["finalized_draft"] == receipt["summary"]["relations_failed"] == 1
    assert len(_results(service, receipt, "relation")) == 1
    current = _records(runtime).read(identifier)
    assert len(current["fields"]["components"]) == 2
    assert sum(issue["code"] == "relation_discovery_failed" for issue in current["issues"]) == 1


@pytest.mark.parametrize("failure", ["connection", "problem"])
def test_connection_or_failed_problem_publication_rolls_back_outer_transaction(context_runtime, monkeypatch, failure):
    from sqlalchemy.exc import OperationalError
    runtime, identifier = context_runtime, _draft(context_runtime)
    before = _records(runtime).read(identifier)
    balances = _snapshot(runtime)
    service = _service(runtime)
    if failure == "problem":
        import ft.application.cash_import_business_plan as business
        def discovery_fail(*args, **kwargs):
            raise RuntimeError("synthetic native failure")
        monkeypatch.setattr(business, "plan_relation_proposals", discovery_fail)
    view = _preview(service, runtime, identifier, intent="finalize_draft", patch=_complete(before))
    if failure == "connection":
        original = service._save_row
        def fail(*args, **kwargs):
            original(*args, **kwargs)
            raise OperationalError("synthetic driver failure", {}, Exception("private detail"), connection_invalidated=True)
        monkeypatch.setattr(service, "_save_row", fail)
    else:
        original = CashSourceRepository.save_issue
        def fail(*args, **kwargs):
            original(*args, **kwargs)
            raise RuntimeError("synthetic failed problem publication")
        monkeypatch.setattr(CashSourceRepository, "save_issue", fail)
    with pytest.raises(CashCommitFailure) as error:
        _commit(service, view)
    assert error.value.commit_state == "not_committed"
    assert _records(runtime).read(identifier) == before and _snapshot(runtime) == balances
    assert service.read_plan(view["plan_id"]) == view
