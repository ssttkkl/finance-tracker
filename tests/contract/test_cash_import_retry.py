"""失败重试只保留失败正文，数量与访问都由当前证据核实。"""
from dataclasses import replace
from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import func, select

from ft.adapters.relational.cash_mutations import CashMutationConflict, CashMutationRepository
from ft.adapters.relational.cash_sources import CashSourceRepository
from ft.adapters.relational.models import AccountModel, CashTransactionModel
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.application.cash_import_plans import CashImportPlanError
from ft.application.cash_import_errors import ImportSessionNotFound, ImportSessionStorageUnavailable
from ft.application.parsed_import_staging import InMemoryParsedImportStore
from ft.domain.cash_source_matching import SourceCoverage
from tests.contract.test_cash_import_commit import configured, _service, _plan, _commit, _results
from tests.contract.test_cash_import_operations import _file, _selections
from tests.contract.test_parsed_cash_import_context import context_runtime, USER


def _fail(runtime, monkeypatch, *, files=None, mapped=True, row_choices=None, store=None):
    files = files or (_file(provider="good", note="successful-private-body"),
        _file(provider="fail", note="failed-private-body", letter="b"))
    service = _service(runtime, store)
    staged = service._staging.create(workspace_id=runtime.workspace_id, user_id=USER, files=files)
    plan = service.preview(staged.session_id, expected_session_revision=1,
        account_selections=_selections(files) if mapped else [], row_choices=row_choices or [])
    save = CashSourceRepository.save_occurrence
    def fail(self, identifier, row, **kwargs):
        saved = save(self, identifier, row, **kwargs)
        if row.provider_id == "fail" or row.fields.get("note") == "failed-private-body":
            raise RuntimeError("synthetic single row storage error")
        return saved
    with monkeypatch.context() as fault:
        fault.setattr(CashSourceRepository, "save_occurrence", fail)
        receipt = _commit(service, plan, "synthetic-failed-batch")
    return service, plan, receipt


def _retry(service, receipt, *, rows=None, key="synthetic-retry-session"):
    return service.retry_session(receipt["receipt_id"], row_refs=rows, idempotency_key=key)


def test_partial_commit_retains_only_private_failed_body_and_original_expiry(context_runtime, monkeypatch):
    service, plan, receipt = _fail(context_runtime, monkeypatch)
    assert receipt["status"] == "partial" and receipt["retry_context_status"] == "available"
    capsule = service._staging.get(receipt["retry_context_id"], workspace_id=context_runtime.workspace_id, user_id=USER)
    assert capsule.workflow_kind == "retry_capsule" and capsule.plan is None
    assert capsule.session_id != plan["session_id"]
    assert capsule.expires_at.isoformat() == plan["expires_at"]
    assert len(capsule.files) == 1 and len(capsule.files[0].document.rows) == 1
    assert "successful-private-body" not in repr(capsule)
    assert "failed-private-body" in repr(capsule)
    assert service._staging.get(plan["session_id"], workspace_id=context_runtime.workspace_id, user_id=USER).cancelled
    with pytest.raises(ImportSessionNotFound):
        service.get(capsule.session_id)
    assert service.get(plan["session_id"]).receipt["receipt_id"] == receipt["receipt_id"]
    assert _commit(service, plan, "synthetic-failed-batch")["receipt_id"] == receipt["receipt_id"]


def test_retry_derivation_is_idempotent_and_preserves_failed_user_choices(context_runtime, monkeypatch):
    files = (_file(provider="fail", note="failed-private-body", letter="b"),)
    service, plan, receipt = _fail(context_runtime, monkeypatch, files=files,
        row_choices=[{"row_ref": "file_b:r1", "action": "apply", "patch": {"note": "人工补充"}}])
    child = _retry(service, receipt)
    replay = _retry(service, receipt)
    assert child.session_id == replay.session_id and child.revision == replay.revision == 1
    assert child.created_at < child.expires_at and child.expires_at.isoformat() == plan["expires_at"]
    assert child.workflow_kind == "retry" and all(file.is_subset for file in child.files)
    assert child.preview_input["row_choices"][0]["patch"]["note"] == "人工补充"
    assert child.preview_input["relation_choices"] == []
    new_plan = service.preview(child.session_id, expected_session_revision=1)
    saved = _commit(service, new_plan, "synthetic-retry-commit")
    assert saved["summary"]["created_posted"] == 1 and saved["summary"]["skipped"] == 0
    with context_runtime.sessions() as session:
        identifier = _results(service, saved, "row")[0]["transaction_id"]
        parent = session.get(CashTransactionModel, int(identifier))
        from ft.adapters.relational.cash_entries import CashEntryRepository
        entry = CashEntryRepository(session, context_runtime.workspace_id).current(identifier)
        assert parent.note == 'failed-private-body' and not parent.manual_overrides.get('note')
        assert entry.note == "人工补充" and 'note' in entry.manual_fields


def test_replaying_derivation_after_child_commit_returns_consumer_without_body(context_runtime, monkeypatch):
    service, _, receipt = _fail(context_runtime, monkeypatch)
    child = _retry(service, receipt)
    saved = _commit(service, service.preview(child.session_id, expected_session_revision=1), "synthetic-derived-success")
    with pytest.raises(CashMutationConflict, match="retry_already_processed") as error:
        _retry(service, receipt)
    assert error.value.receipt_id == saved["receipt_id"]


@pytest.mark.parametrize("rows", [[], ["file_a:r1"], ["missing:r1"], ["file_b:r1", "file_b:r1"]])
def test_retry_cannot_select_success_unknown_or_empty_rows(context_runtime, monkeypatch, rows):
    service, _, receipt = _fail(context_runtime, monkeypatch)
    with pytest.raises(CashImportPlanError, match="retry_rows_invalid"):
        _retry(service, receipt, rows=rows)


def test_retry_one_failed_alias_carries_whole_logical_operation(context_runtime, monkeypatch):
    files = (_file(provider="fail", note="failed-private-body"),
        _file(provider="fail", note="failed-private-body", letter="b"))
    service, _, receipt = _fail(context_runtime, monkeypatch, files=files)
    child = _retry(service, receipt, rows=["file_b:r1"])
    assert sum(len(file.document.rows) for file in child.files) == 2
    plan = service.preview(child.session_id, expected_session_revision=1)
    saved = _commit(service, plan, "synthetic-alias-retry")
    assert saved["summary"]["created_posted"] == 1 and saved["summary"]["duplicate"] == 1
    assert len({item["transaction_id"] for item in _results(service, saved, "row")}) == 1


def test_removing_required_alias_does_not_save_or_consume_half_the_operation(context_runtime, monkeypatch):
    files = (_file(provider="fail", note="failed-private-body"),
        _file(provider="fail", note="failed-private-body", letter="b"))
    service, _, receipt = _fail(context_runtime, monkeypatch, files=files)
    child = _retry(service, receipt)
    changed = service.remove_file(child.session_id, "file_b", expected_revision=1)
    plan = service.preview(child.session_id, expected_session_revision=changed.revision)
    assert plan["summary"].get("create_posted", 0) == 0
    saved = _commit(service, plan, "synthetic-missing-alias")
    assert saved["summary"]["created_posted"] == 0 and saved["summary"]["skipped"] == 1
    assert _results(service, saved, "row")[0]["code"] == "retry_aliases_incomplete"
    again = _retry(service, receipt, key="synthetic-whole-operation")
    assert sum(len(file.document.rows) for file in again.files) == 2


def test_changed_source_scope_skips_whole_alias_unit_and_keeps_other_failed_rows(context_runtime, monkeypatch):
    first = _file(provider="fail", note="failed-private-body")
    second = _file(provider="fail", note="failed-private-body", letter="b")
    files = tuple(replace(file, source_type="wechat", document=replace(file.document, source_type="wechat"))
        for file in (first, second)) + (_file(provider="other", note="failed-private-body", letter="c"),)
    service, _, receipt = _fail(context_runtime, monkeypatch, files=files)
    child = _retry(service, receipt)
    changed = service.set_file_source_scope(child.session_id, "file_b", "另一个微信", expected_revision=1)
    plan = service.preview(child.session_id, expected_session_revision=changed.revision)
    saved = _commit(service, plan, "synthetic-changed-scope")
    rows = {item["item_ref"]: item for item in _results(service, saved, "row")}
    assert saved["summary"]["created_posted"] == 1 and saved["summary"]["skipped"] == 2
    assert rows["file_a:r1"]["code"] == rows["file_b:r1"]["code"] == "retry_context_changed"
    assert rows["file_c:r1"]["outcome"] == "created_posted"
    again = _retry(service, receipt, key="synthetic-unchanged-aliases")
    assert {file.file_ref for file in again.files} == {"file_a", "file_b"}


@pytest.mark.parametrize("corruption", ["credit_units", "document_witnesses", "resolution"])
def test_corrupt_private_proof_fails_closed_before_any_body_or_plan(context_runtime, monkeypatch, corruption):
    from copy import deepcopy
    service, _, receipt = _fail(context_runtime, monkeypatch)
    child = _retry(service, receipt)
    data = deepcopy(child.context_data)
    if corruption == "credit_units":
        next(iter(data["source_proofs"]["groups"].values()))["credit_units"] = {"synthetic": None}
    elif corruption == "document_witnesses":
        data["source_proofs"]["document_witnesses"] = [{}]
    else:
        next(iter(data["units"].values()))["resolution"] = {"action": "new"}
    corrupted = service._staging.replace(child.session_id, workspace_id=context_runtime.workspace_id, user_id=USER,
        expected_revision=1, files=child.files, preview_input=child.preview_input, plan=None, context_data=data)
    for action in (lambda: service.get(child.session_id),
        lambda: service.preview(child.session_id, expected_session_revision=corrupted.revision)):
        with pytest.raises(ImportSessionStorageUnavailable):
            action()
    assert service._staging.get(child.session_id, workspace_id=context_runtime.workspace_id, user_id=USER).revision == corrupted.revision


@pytest.mark.parametrize("mapped", [True, False])
def test_successful_child_revokes_ancestor_and_sibling_body_including_saved_draft(context_runtime, monkeypatch, mapped):
    service, _, receipt = _fail(context_runtime, monkeypatch, mapped=mapped)
    first = _retry(service, receipt)
    sibling = _retry(service, receipt, key="synthetic-other-child")
    sibling_plan = service.preview(sibling.session_id, expected_session_revision=1)
    first_plan = service.preview(first.session_id, expected_session_revision=1)
    saved = _commit(service, first_plan, "synthetic-child-save")
    assert saved["summary"]["created_posted" if mapped else "saved_draft"] == 1
    for action in (lambda: _retry(service, receipt, key="synthetic-after-success"),
        lambda: service.get(sibling.session_id),
        lambda: service.read_plan(sibling_plan["plan_id"]),
        lambda: _commit(service, sibling_plan, "synthetic-stale-child")):
        with pytest.raises((CashMutationConflict, CashImportPlanError)):
            action()
    for identifier in (receipt["retry_context_id"], sibling.session_id):
        current = service._staging.get(identifier, workspace_id=context_runtime.workspace_id, user_id=USER)
        assert "failed-private-body" not in repr(current) and current.plan is None
    assert _commit(service, first_plan, "synthetic-child-save")["receipt_id"] == saved["receipt_id"]


def test_capsule_storage_failure_keeps_valid_rows_and_requires_reselection(context_runtime, monkeypatch):
    store = InMemoryParsedImportStore()
    create = store.create
    def fail(**kwargs):
        if kwargs.get("workflow_kind") == "retry_capsule":
            raise ImportSessionStorageUnavailable()
        return create(**kwargs)
    monkeypatch.setattr(store, "create", fail)
    service, plan, receipt = _fail(context_runtime, monkeypatch, store=store)
    assert receipt["summary"]["created_posted"] == 1
    assert receipt["retry_context_status"] == "reselect_file" and not receipt.get("retry_context_id")
    assert service._staging.get(plan["session_id"], workspace_id=context_runtime.workspace_id, user_id=USER).files == ()


def test_registered_orphan_is_not_public_and_does_not_consume_failure(context_runtime, monkeypatch):
    service, _, receipt = _fail(context_runtime, monkeypatch)
    with monkeypatch.context() as fault:
        def fail(_self):
            raise RuntimeError("synthetic derivation transaction rollback")
        fault.setattr(RelationalUnitOfWork, "commit", fail)
        with pytest.raises(RuntimeError):
            _retry(service, receipt)
    root = service._staging.get(receipt["retry_context_id"], workspace_id=context_runtime.workspace_id, user_id=USER)
    orphan_ids = list(root.context_data["derived_context_ids"])
    assert orphan_ids
    for identifier in orphan_ids:
        with pytest.raises(ImportSessionNotFound):
            service.get(identifier)
    child = _retry(service, receipt)
    assert child.session_id not in orphan_ids and "failed-private-body" in repr(child)


def test_registration_failure_does_not_publish_child_or_consume_failure(context_runtime, monkeypatch):
    from ft.application.cash_import_retry import CashImportRetryService
    service, _, receipt = _fail(context_runtime, monkeypatch)
    with monkeypatch.context() as fault:
        def fail(*_args):
            raise ImportSessionStorageUnavailable()
        fault.setattr(CashImportRetryService, "_register", fail)
        with pytest.raises(ImportSessionStorageUnavailable):
            _retry(service, receipt)
    root = service._staging.get(receipt["retry_context_id"], workspace_id=context_runtime.workspace_id, user_id=USER)
    assert root.context_data["derived_context_ids"] == []
    orphans = set(service._staging._entries) - {receipt["retry_context_id"]}
    for identifier in orphans:
        if service._staging.get(identifier, workspace_id=context_runtime.workspace_id, user_id=USER).workflow_kind == "retry":
            with pytest.raises(ImportSessionNotFound):
                service.get(identifier)
    assert "failed-private-body" in repr(_retry(service, receipt))


def test_unreadable_root_blocks_child_body_but_not_original_receipt(context_runtime, monkeypatch):
    service, original, receipt = _fail(context_runtime, monkeypatch)
    child = _retry(service, receipt)
    get = service._staging.get
    def fail(identifier, **kwargs):
        if identifier == receipt["retry_context_id"]:
            raise ImportSessionStorageUnavailable()
        return get(identifier, **kwargs)
    monkeypatch.setattr(service._staging, "get", fail)
    for action in (lambda: service.get(child.session_id),
        lambda: service.preview(child.session_id, expected_session_revision=1)):
        with pytest.raises(ImportSessionStorageUnavailable):
            action()
    assert service.get(original["session_id"]).receipt["receipt_id"] == receipt["receipt_id"]


def test_cleanup_failure_still_blocks_ancestor_and_sibling_body(context_runtime, monkeypatch):
    service, original, receipt = _fail(context_runtime, monkeypatch)
    one = _retry(service, receipt)
    sibling = _retry(service, receipt, key="synthetic-cleanup-sibling")
    plan = service.preview(one.session_id, expected_session_revision=1)
    replace_ = service._staging.replace
    def fail(identifier, **kwargs):
        if identifier in {receipt["retry_context_id"], sibling.session_id}:
            raise ImportSessionStorageUnavailable()
        return replace_(identifier, **kwargs)
    with monkeypatch.context() as fault:
        fault.setattr(service._staging, "replace", fail)
        saved = _commit(service, plan, "synthetic-cleanup-child")
        assert saved["summary"]["created_posted"] == 1
        for action in (lambda: service.get(sibling.session_id), lambda: _retry(service, receipt, key="synthetic-cleanup-again")):
            with pytest.raises(ImportSessionStorageUnavailable):
                action()
        assert _commit(service, plan, "synthetic-cleanup-child")["receipt_id"] == saved["receipt_id"]
        assert service.get(original["session_id"]).receipt["receipt_id"] == receipt["receipt_id"]
    with pytest.raises(CashMutationConflict, match="retry_already_processed"):
        service.get(sibling.session_id)


def test_derivation_refreshes_account_revision_without_reverting_current_account(context_runtime, monkeypatch):
    service, _, receipt = _fail(context_runtime, monkeypatch)
    with context_runtime.sessions.begin() as session:
        account = session.get(AccountModel, 101)
        account.name, account.revision = "更新后的账户", account.revision + 1
        revision = account.revision
    child = _retry(service, receipt)
    assert child.preview_input["account_selections"][0]["expected_account_revision"] == revision
    saved = _commit(service, service.preview(child.session_id, expected_session_revision=1), "synthetic-current-account")
    assert saved["summary"]["created_posted"] == 1
    with context_runtime.sessions() as session:
        account = session.get(AccountModel, 101)
        assert account.name == "更新后的账户" and account.revision == revision


def test_independent_import_of_same_physical_row_wins_over_old_retry_credit(context_runtime, monkeypatch):
    file = _no_number_file(letter="b", count=1)
    service, _, receipt = _fail(context_runtime, monkeypatch, files=(file,))
    independent, independent_plan = _plan(context_runtime, (file,))
    saved = _commit(independent, independent_plan, "synthetic-independent-import")
    assert saved["summary"]["created_posted"] == 1
    child = _retry(service, receipt)
    plan = service.preview(child.session_id, expected_session_revision=1)
    assert plan["summary"].get("create_posted", 0) == 0
    saved = _commit(service, plan, "synthetic-physical-duplicate")
    assert saved["summary"]["created_posted"] == 0 and saved["summary"]["duplicate"] == 1


def test_certified_failed_row_keeps_document_witness_without_granting_subset_completeness(context_runtime, monkeypatch):
    from ft.adapters.relational.models import CashSourceEmptyPageModel
    from test_cash_source_documents import document_coverage
    file = _file(provider=None, note="failed-private-body")
    coverage = document_coverage({"1": "a" * 64, "2": "b" * 64, "3": "c" * 64})
    file = replace(file, document=replace(file.document, coverage=coverage,
        rows=(replace(file.document.rows[0], document_position="p2:t0:r1"),)))
    service, _, receipt = _fail(context_runtime, monkeypatch, files=(file,))
    child = _retry(service, receipt)
    assert len(child.context_data["source_proofs"]["document_witnesses"]) == 1
    assert child.files[0].is_subset and not child.files[0].document.coverage.complete
    plan = service.preview(child.session_id, expected_session_revision=1)
    saved = _commit(service, plan, "synthetic-certified-retry")
    assert saved["summary"]["created_posted"] == 1 and saved["summary"]["skipped"] == 0
    with context_runtime.sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashSourceEmptyPageModel)) == 2


def test_retry_expiry_is_not_extended_by_derivation(context_runtime, monkeypatch):
    now = [datetime(2026, 10, 22, tzinfo=timezone.utc)]
    store = InMemoryParsedImportStore(clock=lambda: now[0], ttl_seconds=60)
    service, plan, receipt = _fail(context_runtime, monkeypatch, store=store)
    now[0] += timedelta(seconds=45)
    child = _retry(service, receipt)
    assert child.expires_at.isoformat() == plan["expires_at"]
    now[0] += timedelta(seconds=15)
    with pytest.raises(CashImportPlanError, match="retry_context_expired"):
        _retry(service, receipt, key="synthetic-expired")


def _no_number_file(*, letter, count, note="failed-private-body"):
    first = _file(provider=None, note=note, letter=letter)
    rows = tuple(replace(first.document.rows[0], position=index + 1) for index in range(count))
    return replace(first, document=replace(first.document, rows=rows, declared_count=count, observed_count=count,
        coverage=SourceCoverage(start="2026-07-01T00:00:00Z", end="2026-08-01T00:00:00Z",
            complete=True, evidence="synthetic-complete")))


def test_no_number_remaining_new_count_is_not_lost_when_retry_has_only_one_row(context_runtime, monkeypatch):
    original = _no_number_file(letter="a", count=1)
    service, plan = _plan(context_runtime, (original,))
    _commit(service, plan)
    incoming = _no_number_file(letter="b", count=2)
    retry_service, _, failed = _fail(context_runtime, monkeypatch, files=(incoming,))
    assert failed["summary"]["duplicate"] == 1 and failed["summary"]["skipped"] == 1
    child = _retry(retry_service, failed)
    assert sum(len(file.document.rows) for file in child.files) == 1
    child_plan = retry_service.preview(child.session_id, expected_session_revision=1)
    assert child_plan["summary"]["create_posted"] == 1
    saved = _commit(retry_service, child_plan, "synthetic-no-number-retry")
    assert saved["summary"]["created_posted"] == 1 and saved["summary"]["skipped"] == 0
    with context_runtime.sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel).where(
            CashTransactionModel.source_type == "alipay")) == 2


def test_failed_identity_proof_cannot_gain_new_credit_after_current_state_changed(context_runtime, monkeypatch):
    original = _no_number_file(letter="a", count=1)
    first, plan = _plan(context_runtime, (original,))
    _commit(first, plan)
    incoming = _no_number_file(letter="b", count=2)
    service, _, receipt = _fail(context_runtime, monkeypatch, files=(incoming,))
    with context_runtime.sessions.begin() as session:
        parent = session.scalar(select(CashTransactionModel).where(CashTransactionModel.source_type == "alipay"))
        parent.counterparty = "后来改动的商户"
        parent.row_revision += 1
    child = _retry(service, receipt)
    plan = service.preview(child.session_id, expected_session_revision=1)
    assert plan["summary"].get("create_posted", 0) == 0
    assert plan["summary"].get("create_draft", 0) == 1


def test_two_failed_new_counts_survive_success_in_another_registered_child(context_runtime, monkeypatch):
    first, original = _plan(context_runtime, (_no_number_file(letter="a", count=1),))
    _commit(first, original)
    service, _, receipt = _fail(context_runtime, monkeypatch, files=(_no_number_file(letter="b", count=3),))
    assert receipt["summary"]["duplicate"] == 1 and receipt["summary"]["skipped"] == 2
    one = _retry(service, receipt, rows=["file_b:r2"])
    two = _retry(service, receipt, rows=["file_b:r3"], key="synthetic-second-count")
    one_plan = service.preview(one.session_id, expected_session_revision=1)
    _commit(service, one_plan, "synthetic-first-count-commit")
    two_plan = service.preview(two.session_id, expected_session_revision=1)
    assert two_plan["summary"].get("create_posted") == 1
    result = _commit(service, two_plan, "synthetic-second-count-commit")
    assert result["summary"]["created_posted"] == 1
    with context_runtime.sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel).where(
            CashTransactionModel.source_type == "alipay")) == 3
