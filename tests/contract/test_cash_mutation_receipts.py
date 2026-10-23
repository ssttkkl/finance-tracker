from concurrent.futures import ThreadPoolExecutor
from threading import Event

import pytest
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError


@pytest.fixture(params=["cash_web_runtime", "postgres_cash_web_runtime"])
def mutation_runtime(request):
    return request.getfixturevalue(request.param)


def _repo(session, runtime):
    from ft.adapters.relational.cash_mutations import CashMutationRepository
    return CashMutationRepository(session, runtime.workspace_id)


def _save(repo, **changes):
    values = dict(user_id="fixture-user", idempotency_key="request-1", request_digest="a" * 64,
        operation_kind="import", plan_id="plan-1", session_digest="b" * 64,
        result_summary={"status": "completed", "summary": {"created_posted": 1}, "needs_attention_count": 0},
        results=[{"result_kind": "row", "item_ref": "f1:r1", "outcome": "created_posted", "transaction_id": "1001", "operation_ref": "op-1"}])
    values.update(changes)
    return repo.create(**values)


def test_receipt_and_item_results_commit_with_business_data_and_rollback_together(mutation_runtime):
    from ft.adapters.relational.models import CashTransactionModel
    runtime = mutation_runtime
    with pytest.raises(RuntimeError), runtime.sessions.begin() as session:
        session.get(CashTransactionModel, 1001).note = "rolled back together"
        _save(_repo(session, runtime))
        raise RuntimeError("synthetic outer failure")
    with runtime.sessions.begin() as session:
        repo = _repo(session, runtime)
        assert repo.by_key("fixture-user", "request-1") is None
        assert session.get(CashTransactionModel, 1001).note != "rolled back together"
        receipt = _save(repo)
        assert len(repo.results("fixture-user", receipt["receipt_id"])["items"]) == 1


def test_exact_retry_returns_original_receipt_without_mutating_result(mutation_runtime):
    runtime = mutation_runtime
    with runtime.sessions.begin() as session:
        repo = _repo(session, runtime)
        original = _save(repo)
        assert repo.replay("fixture-user", "request-1", request_digest="a" * 64, operation_kind="import", plan_id="plan-1") == original
    with runtime.sessions.begin() as session:
        assert _repo(session, runtime).by_key("fixture-user", "request-1") == original


@pytest.mark.parametrize("changed", [{"request_digest": "c" * 64}, {"operation_kind": "cash_edit"}, {"plan_id": "other-plan"}])
def test_same_key_with_different_request_is_rejected(mutation_runtime, changed):
    from ft.adapters.relational.cash_mutations import CashMutationConflict
    runtime = mutation_runtime
    with runtime.sessions.begin() as session:
        repo = _repo(session, runtime)
        _save(repo)
        request = dict(request_digest="a" * 64, operation_kind="import", plan_id="plan-1")
        request.update(changed)
        with pytest.raises(CashMutationConflict, match="idempotency_conflict"):
            repo.replay("fixture-user", "request-1", **request)


def test_session_can_only_be_consumed_once_but_equal_file_content_is_not_a_fence(mutation_runtime):
    from ft.adapters.relational.cash_mutations import CashMutationConflict
    runtime = mutation_runtime
    with runtime.sessions.begin() as session:
        repo = _repo(session, runtime)
        original = _save(repo)
        assert repo.for_context("fixture-user", session_digest="b" * 64) == original
        with pytest.raises(CashMutationConflict, match="session_committed") as error:
            _save(repo, idempotency_key="request-2")
        assert error.value.receipt_id == original["receipt_id"]
        other = _save(repo, idempotency_key="request-2", session_digest="c" * 64)
        assert other["receipt_id"] != original["receipt_id"]


def test_edit_context_consumption_and_accounts_without_context_share_receipts(mutation_runtime):
    from ft.adapters.relational.cash_mutations import CashMutationConflict
    runtime = mutation_runtime
    with runtime.sessions.begin() as session:
        repo = _repo(session, runtime)
        first = _save(repo, operation_kind="cash_edit", session_digest=None, edit_context_digest="d" * 64)
        assert repo.for_context("fixture-user", edit_context_digest="d" * 64) == first
        with pytest.raises(CashMutationConflict, match="edit_context_committed"):
            _save(repo, idempotency_key="other-edit", operation_kind="cash_edit", session_digest=None, edit_context_digest="d" * 64)
        for index in range(2):
            _save(repo, idempotency_key=f"account-{index}", operation_kind="account_update", plan_id=None, session_digest=None)


def test_keys_and_contexts_are_scoped_to_user_and_workspace(mutation_runtime):
    from ft.adapters.relational.models import WorkspaceModel
    from ft.adapters.relational.cash_mutations import CashMutationRepository
    runtime = mutation_runtime
    with runtime.sessions.begin() as session:
        original = _save(_repo(session, runtime))
        other_user = _save(_repo(session, runtime), user_id="different-user")
        session.add(WorkspaceModel(id="different-workspace", name="Other")); session.flush()
        other_workspace = _save(CashMutationRepository(session, "different-workspace"))
        assert len({original["receipt_id"], other_user["receipt_id"], other_workspace["receipt_id"]}) == 3
        assert _repo(session, runtime).get("unknown-user", original["receipt_id"]) is None
        assert CashMutationRepository(session, "different-workspace").get("fixture-user", original["receipt_id"]) is None
        assert _repo(session, runtime).results("different-user", original["receipt_id"]) is None


def test_retry_consumption_reads_only_registered_import_success_with_bounded_parameters(mutation_runtime):
    from hashlib import sha256
    from sqlalchemy import event
    runtime = mutation_runtime
    identifiers = [f"{index:043d}" for index in range(256)]
    positive = ["created_posted", "updated_posted", "saved_draft", "finalized_draft", "duplicate", "duplicate_in_batch"]
    rows = [{"result_kind": "row", "item_ref": f"f:r{index}", "outcome": outcome, "operation_ref": "op-1"}
        for index, outcome in enumerate(positive)]
    rows += [{"result_kind": kind, "item_ref": reference, "outcome": outcome}
        for kind, reference, outcome in (("row", "f:skipped", "skipped"), ("row", "f:failed", "failed"),
            ("row", "f:alias", "duplicate_in_batch"), ("relation", "f:relation", "duplicate"))]
    with runtime.sessions.begin() as session:
        repo = _repo(session, runtime)
        digest = sha256(identifiers[0].encode()).hexdigest()
        receipt = _save(repo, session_digest=digest, results=rows)
        _save(repo, user_id="other-user", session_digest=digest,
            results=[{"result_kind": "row", "item_ref": "f:other_user", "outcome": "saved_draft"}])
        _save(repo, idempotency_key="derivation", operation_kind="retry_session", session_digest=sha256(identifiers[1].encode()).hexdigest(),
            results=[{"result_kind": "row", "item_ref": "f:derivation", "outcome": "saved_draft"}])
        _save(repo, idempotency_key="unregistered", session_digest="a" * 64,
            results=[{"result_kind": "row", "item_ref": "f:unregistered", "outcome": "saved_draft"}])
        sizes = []
        def observe(_connection, _cursor, statement, parameters, _context, _many):
            if statement.lstrip().upper().startswith("SELECT"):
                sizes.append(len(parameters))
        event.listen(session.bind, "before_cursor_execute", observe)
        try:
            references = {f"unused:r{index}" for index in range(701)} | {row["item_ref"] for row in rows}
            references.update(("f:other_user", "f:derivation", "f:unregistered"))
            result = repo.successful_import_rows("fixture-user", identifiers, references)
        finally:
            event.remove(session.bind, "before_cursor_execute", observe)
        assert set(result) == {f"f:r{index}" for index in range(len(positive))}
        assert {value["receipt_id"] for value in result.values()} == {receipt["receipt_id"]}
        assert sizes and max(sizes) < 999


def test_result_paging_is_stable_and_bounded(mutation_runtime):
    runtime = mutation_runtime
    with runtime.sessions.begin() as session:
        repo = _repo(session, runtime)
        result = _save(repo, results=[{"result_kind": "row", "item_ref": f"f1:r{index}", "outcome": "duplicate"} for index in range(5)])
        first = repo.results("fixture-user", result["receipt_id"], limit=2)
        second = repo.results("fixture-user", result["receipt_id"], limit=2, after=first["next_after"])
        third = repo.results("fixture-user", result["receipt_id"], limit=2, after=second["next_after"])
        assert [item["item_ref"] for page in (first, second, third) for item in page["items"]] == [f"f1:r{index}" for index in range(5)]
        assert third["next_after"] is None
        with pytest.raises(ValueError, match="mutation_page_invalid"):
            repo.results("fixture-user", result["receipt_id"], limit=201)


@pytest.mark.parametrize("changes", [
    {"result_summary": {"source_payload": {"secret": "must not persist"}}},
    {"results": [{"result_kind": "row", "item_ref": "r1", "outcome": "failed", "message": "SQL details must not persist"}]},
    {"results": [{"result_kind": "row", "item_ref": "r1", "outcome": "failed", "code": "SQL: private details"}]},
    {"results": [{"result_kind": "row", "item_ref": "r1", "outcome": "duplicate"}] * 2},
    {"session_digest": ""},
    {"edit_context_digest": "c" * 64},
    {"result_summary": {"status": []}},
    {"results": [{"result_kind": [], "item_ref": "r1", "outcome": "failed"}]},
])
def test_invalid_or_sensitive_result_is_rejected_before_any_receipt_write(mutation_runtime, changes):
    runtime = mutation_runtime
    with runtime.sessions.begin() as session:
        repo = _repo(session, runtime)
        with pytest.raises(ValueError, match="mutation_.*invalid"):
            _save(repo, **changes)
        assert repo.by_key("fixture-user", "request-1") is None


@pytest.mark.parametrize("changes", [
    {"source_payload": {"private": "must not persist"}}, {"account_revision": 0},
    {"account_id": "different-account"}, {"currencies": ["CNY", "CNY"]},
])
def test_account_receipt_only_accepts_matching_whitelisted_account_results(mutation_runtime, changes):
    account = {"account_id": "101", "account_revision": 2, "name": "Ordinary account", "type": "cash", "active": True, "currencies": ["CNY"], "deleted": False}
    account.update(changes)
    with mutation_runtime.sessions.begin() as session:
        repo = _repo(session, mutation_runtime)
        with pytest.raises(ValueError, match="mutation_summary_invalid"):
            _save(repo, operation_kind="account_update", session_digest=None, plan_id=None,
                result_summary={"status": "completed", "account_id": "101", "account_revision": 2, "account": account})
        assert repo.by_key("fixture-user", "request-1") is None


def test_result_references_survive_later_deletion_without_blocking_it(mutation_runtime):
    from ft.adapters.relational.models import CashMutationResultModel
    runtime = mutation_runtime
    with runtime.sessions.begin() as session:
        repo = _repo(session, runtime)
        receipt = _save(repo, results=[{"result_kind": "account", "item_ref": "account-1", "outcome": "deleted", "account_id": "999999"}])
        # 永久结果不是目标对象的所有权或引用占用；目标可能已不存在。
        result = session.scalar(select(CashMutationResultModel))
        assert result.account_id == "999999"
        assert repo.results("fixture-user", receipt["receipt_id"])["items"][0]["account_id"] == "999999"


def test_database_uniqueness_fences_session_consumption_without_repository(mutation_runtime):
    from ft.adapters.relational.models import CashMutationReceiptModel
    runtime = mutation_runtime
    with runtime.sessions.begin() as session:
        _save(_repo(session, runtime))
    with pytest.raises(IntegrityError), runtime.sessions.begin() as session:
        session.add(CashMutationReceiptModel(workspace_id=runtime.workspace_id, user_id="fixture-user",
            idempotency_key="different-key", request_digest="c" * 64, operation_kind="import", plan_id="p2",
            session_digest="b" * 64, result_summary={}))


def test_uow_acquires_workspace_write_boundary_before_any_business_read(mutation_runtime):
    from ft.adapters.relational.uow import RelationalUnitOfWork
    runtime = mutation_runtime
    attempted, entered, release = Event(), Event(), Event()
    def writer():
        attempted.set()
        with RelationalUnitOfWork(runtime.sessions, runtime.workspace_id):
            entered.set()
            assert release.wait(3)
    with ThreadPoolExecutor(max_workers=1) as executor:
        with RelationalUnitOfWork(runtime.sessions, runtime.workspace_id):
            future = executor.submit(writer)
            try:
                assert attempted.wait(2)
                assert not entered.wait(0.15)
            finally:
                release.set()
        future.result(timeout=3)
    assert entered.is_set()


def test_projection_reference_objects_follow_the_confirmed_receipt_contract(mutation_runtime):
    runtime = mutation_runtime
    projection_refs = [{"transaction_id": "1001", "projection_id": "p_73"}]
    with runtime.sessions.begin() as session:
        repo = _repo(session, runtime)
        receipt = _save(repo, result_summary={"status": "completed", "projection_refs": projection_refs})
        assert receipt["projection_refs"] == projection_refs
        assert repo.replay("fixture-user", "request-1", request_digest="a" * 64, operation_kind="import", plan_id="plan-1")["projection_refs"] == projection_refs


def test_sqlite_unfiltered_result_paging_does_not_sort_the_entire_receipt(cash_web_runtime):
    from sqlalchemy import text
    runtime = cash_web_runtime
    with runtime.sessions.begin() as session:
        receipt = _save(_repo(session, runtime))
        plan = session.execute(text("EXPLAIN QUERY PLAN SELECT id FROM cash_mutation_results WHERE workspace_id=:workspace AND receipt_id=:receipt AND id>0 ORDER BY id LIMIT 51"),
            {"workspace": runtime.workspace_id, "receipt": receipt["receipt_id"]}).all()
        assert not any("TEMP B-TREE" in str(row) for row in plan)


def test_receipt_migration_preserves_legacy_results_and_refuses_lossy_downgrade(mutation_runtime):
    from datetime import datetime, timezone
    from alembic import command
    from sqlalchemy import MetaData, Table, text
    from tests.contract.test_cash_draft_storage import _migration_config
    runtime = mutation_runtime
    engine = runtime.sessions.kw["bind"]
    with engine.connect() as connection:
        command.downgrade(_migration_config(connection), "20261021_39")
    with engine.begin() as connection:
        old = Table("cash_import_commits", MetaData(), autoload_with=connection)
        connection.execute(old.insert(), {"id": "legacy-receipt", "workspace_id": runtime.workspace_id,
            "user_id": "fixture-user", "idempotency_key": "legacy-key", "session_digest": "legacy-content-digest",
            "result_json": {"new_rows": 1}, "created_at": datetime.now(timezone.utc)})
    with engine.connect() as connection:
        command.upgrade(_migration_config(connection), "20261021_40")
        # Revision 40 adds unified receipts while retaining the original result unchanged.
        old = Table("cash_import_commits", MetaData(), autoload_with=connection)
        assert connection.execute(select(old.c.result_json)).scalar_one() == {"new_rows": 1}
        assert connection.scalar(text("SELECT count(*) FROM cash_mutation_receipts")) == 0
    with runtime.sessions.begin() as session:
        receipt = _save(_repo(session, runtime))
    with engine.connect() as connection:
        with pytest.raises(ValueError, match="refusing a lossy"):
            command.downgrade(_migration_config(connection), "20261021_39")
    with engine.connect() as connection:
        command.upgrade(_migration_config(connection), "head")
    with runtime.sessions() as session:
        repo = _repo(session, runtime)
        assert repo.get("fixture-user", receipt["receipt_id"]) == receipt
        assert repo.get("fixture-user", "legacy-receipt")["summary"] == {"new_rows": 1}


def test_database_rejects_result_attached_to_another_workspaces_receipt(mutation_runtime):
    from ft.adapters.relational.models import CashMutationResultModel
    runtime = mutation_runtime
    with runtime.sessions.begin() as session:
        receipt = _save(_repo(session, runtime))
    with pytest.raises(IntegrityError), runtime.sessions.begin() as session:
        session.add(CashMutationResultModel(workspace_id="different-workspace", receipt_id=receipt["receipt_id"],
            result_kind="row", item_ref="wrong-owner", outcome="duplicate"))
