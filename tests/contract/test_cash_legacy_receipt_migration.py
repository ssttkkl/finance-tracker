"""旧导入结果迁入统一收据，只保留确实存在的业务结果。"""
from datetime import datetime, timezone

from alembic import command
import pytest
from sqlalchemy import MetaData, Table, event, inspect, select, text

from ft.adapters.relational.models import CashMutationReceiptModel
from tests.contract.test_cash_draft_storage import _migration_config
from tests.contract.test_cash_mutation_receipts import mutation_runtime, _save, _repo


OLD = "20261021_48"
NEW = "20261023_49"
WHEN = datetime(2026, 1, 2, 3, 4, 5, tzinfo=timezone.utc)


def _engine(runtime):
    return runtime.sessions.kw["bind"]


def _revision(runtime, target, *, downgrade=False):
    with _engine(runtime).connect() as connection:
        action = command.downgrade if downgrade else command.upgrade
        action(_migration_config(connection), target)


def _old(runtime, rows):
    _revision(runtime, OLD, downgrade=True)
    with _engine(runtime).begin() as connection:
        table = Table("cash_import_commits", MetaData(), autoload_with=connection)
        connection.execute(table.insert(), [{"id": "legacy-one", "workspace_id": runtime.workspace_id,
            "user_id": "fixture-user", "idempotency_key": "legacy-key", "session_digest": "same-old-file-digest",
            "result_json": {}, "created_at": WHEN, **row} for row in rows])


def _contents(runtime):
    with _engine(runtime).connect() as connection:
        return {table: sorted([tuple((key, str(value)) for key, value in row._mapping.items()
                if key not in {'record_origin', 'manual_transfer_id'})
            for row in connection.execute(text(f"SELECT * FROM {table}"))], key=repr)
            for table in ("cash_transactions", "cash_transaction_components", "transaction_relations",
                "cash_source_bindings", "ledger_snapshots", "statement_account_mappings")}


def test_legacy_summary_upgrade_preserves_identity_real_counts_and_empty_details(mutation_runtime):
    runtime = mutation_runtime
    _old(runtime, [{"result_json": {"new_rows": 1, "updated_rows": 2, "mapping_saved": 0,
        "skipped_rows": 3, "by_account": {"历史账户": 8}, "message": "private-old-message",
        "files": ["private-old-file.csv"], "batch_digest": "private-old-digest"}},
        {"id": "legacy-two", "idempotency_key": "旧导入/结果 ?&+=#%", "result_json": {"by_account": {}}}])
    before = _contents(runtime)
    _revision(runtime, "head")
    assert "cash_import_commits" not in inspect(_engine(runtime)).get_table_names()
    assert _contents(runtime) == before
    with runtime.sessions() as session:
        repo = _repo(session, runtime)
        first = repo.get("fixture-user", "legacy-one")
        assert first["operation_kind"] == "legacy_import" and first["plan_id"] is None
        assert first["summary"] == {"new_rows": 1, "updated_rows": 2, "mapping_saved": 0, "skipped_rows": 3}
        assert first["legacy_by_account"] == {"历史账户": 8}
        assert first["session_digest"] is first["edit_context_digest"] is None
        assert "status" not in first and first["committed"] is True
        assert not {"files", "message", "batch_digest", "retry_context_id"} & first.keys()
        second = repo.get("fixture-user", "legacy-two")
        assert second["summary"] == {} and second["legacy_by_account"] == {}
        assert first["request_digest"] != second["request_digest"]
        for receipt_id in ("legacy-one", "legacy-two"):
            assert repo.results("fixture-user", receipt_id)["items"] == []
            assert repo.get("different-user", receipt_id) is None
        model = session.get(CashMutationReceiptModel, "legacy-one")
        assert model.idempotency_key == "legacy-key" and model.created_at == WHEN


def test_upgrade_preserves_existing_current_receipt_and_does_not_consume_old_file_hash(mutation_runtime):
    runtime = mutation_runtime
    _old(runtime, [{"session_digest": "b" * 64}])
    with runtime.sessions.begin() as session:
        current = _save(_repo(session, runtime))
    _revision(runtime, "head")
    with runtime.sessions() as session:
        repo = _repo(session, runtime)
        assert repo.get("fixture-user", current["receipt_id"]) == current
        assert repo.for_context("fixture-user", session_digest="b" * 64)["receipt_id"] == current["receipt_id"]
        assert repo.get("fixture-user", "legacy-one")["session_digest"] is None


@pytest.mark.parametrize("collision", ["id", "key"])
def test_target_collision_refuses_entire_upgrade_without_rekeying(mutation_runtime, collision):
    runtime = mutation_runtime
    _old(runtime, [{}])
    with runtime.sessions.begin() as session:
        current = _save(_repo(session, runtime), idempotency_key="legacy-key" if collision == "key" else "current-key", results=[])
        if collision == "id":
            session.get(CashMutationReceiptModel, current["receipt_id"]).id = "legacy-one"
    with pytest.raises(ValueError, match="legacy_receipt"):
        _revision(runtime, "head")
    with _engine(runtime).connect() as connection:
        assert connection.scalar(text("SELECT count(*) FROM cash_import_commits")) == 1
        assert connection.scalar(text("SELECT count(*) FROM cash_mutation_receipts")) == 1
        assert connection.scalar(text("SELECT version_num FROM alembic_version")) == OLD


@pytest.mark.parametrize("summary", [[], {"new_rows": True}, {"new_rows": -1}, {"updated_rows": 1.5},
    {"by_account": {"历史账户": -1}}, {"by_account": []}, {"by_account": {"x" * (1024 * 1024): 0}}, {"by_account": {"导" * 200000: 0}}])
def test_invalid_legacy_summary_refuses_upgrade_without_losing_original(mutation_runtime, summary):
    runtime = mutation_runtime
    _old(runtime, [{"result_json": summary}])
    with pytest.raises(ValueError, match="legacy_receipt"):
        _revision(runtime, "head")
    with _engine(runtime).connect() as connection:
        assert connection.scalar(text("SELECT count(*) FROM cash_import_commits")) == 1
        assert connection.scalar(text("SELECT count(*) FROM cash_mutation_receipts")) == 0
        assert connection.scalar(text("SELECT version_num FROM alembic_version")) == OLD


def test_all_rows_are_preflighted_before_any_receipt_is_inserted(mutation_runtime):
    runtime = mutation_runtime
    _old(runtime, [{"id": f"legacy-{index:04d}", "idempotency_key": f"key-{index}"} for index in range(1001)]
        + [{"id": "legacy-zz-bad", "idempotency_key": "key-bad", "result_json": {"new_rows": -1}}])
    inserted = []
    def observe(_connection, _cursor, statement, _parameters, _context, _many):
        if statement.lstrip().upper().startswith("INSERT INTO CASH_MUTATION_RECEIPTS"):
            inserted.append(statement)
    event.listen(_engine(runtime), "before_cursor_execute", observe)
    try:
        with pytest.raises(ValueError, match="legacy_receipt"):
            _revision(runtime, "head")
    finally:
        event.remove(_engine(runtime), "before_cursor_execute", observe)
    assert inserted == []


def test_failure_after_actual_drop_rolls_back_receipts_schema_and_version(mutation_runtime):
    runtime = mutation_runtime
    _old(runtime, [{}])
    copied = []
    def fail(_connection, _cursor, statement, _parameters, _context, _many):
        normalized = statement.strip().upper()
        if normalized.startswith("INSERT INTO CASH_MUTATION_RECEIPTS"):
            copied.append(statement)
        if normalized.startswith("DROP TABLE CASH_IMPORT_COMMITS"):
            raise RuntimeError("synthetic late migration failure")
    event.listen(_engine(runtime), "after_cursor_execute", fail)
    try:
        with pytest.raises(RuntimeError, match="synthetic late migration failure"):
            _revision(runtime, "head")
    finally:
        event.remove(_engine(runtime), "after_cursor_execute", fail)
    assert copied
    with _engine(runtime).connect() as connection:
        assert connection.scalar(text("SELECT count(*) FROM cash_import_commits")) == 1
        assert connection.scalar(text("SELECT count(*) FROM cash_mutation_receipts")) == 0
        assert connection.scalar(text("SELECT version_num FROM alembic_version")) == OLD


def test_downgrade_with_migrated_legacy_result_refuses_before_any_schema_change(mutation_runtime):
    runtime = mutation_runtime
    _old(runtime, [{}])
    _revision(runtime, "head")
    with runtime.sessions() as session:
        head = session.scalar(text('SELECT version_num FROM alembic_version'))
    with pytest.raises(ValueError, match="refusing a lossy"):
        _revision(runtime, OLD, downgrade=True)
    assert "cash_import_commits" not in inspect(_engine(runtime)).get_table_names()
    with runtime.sessions() as session:
        assert _repo(session, runtime).get("fixture-user", "legacy-one") is not None
        assert session.scalar(text("SELECT version_num FROM alembic_version")) == head


def test_empty_legacy_downgrade_restores_only_empty_old_table_and_keeps_current_receipts(mutation_runtime):
    runtime = mutation_runtime
    with runtime.sessions.begin() as session:
        current = _save(_repo(session, runtime))
    _revision(runtime, OLD, downgrade=True)
    with _engine(runtime).connect() as connection:
        assert connection.scalar(text("SELECT count(*) FROM cash_import_commits")) == 0
    _revision(runtime, "head")
    with runtime.sessions() as session:
        assert _repo(session, runtime).get("fixture-user", current["receipt_id"]) == current


@pytest.mark.parametrize("change", [{"id": "bad/id"}, {"user_id": "bad/user"}, {"idempotency_key": "   "}])
def test_invalid_original_references_refuse_upgrade_with_safe_reason(mutation_runtime, change):
    runtime = mutation_runtime
    _old(runtime, [change])
    with pytest.raises(ValueError, match="^legacy_receipt_invalid$"):
        _revision(runtime, "head")
    with _engine(runtime).connect() as connection:
        assert connection.scalar(text("SELECT count(*) FROM cash_import_commits")) == 1
        assert connection.scalar(text("SELECT count(*) FROM cash_mutation_receipts")) == 0
        assert connection.scalar(text("SELECT version_num FROM alembic_version")) == OLD


def test_other_user_may_reuse_same_key_without_colliding_with_old_owner(mutation_runtime):
    runtime = mutation_runtime
    _old(runtime, [{}])
    with runtime.sessions.begin() as session:
        current = _save(_repo(session, runtime), user_id="other-user", idempotency_key="legacy-key")
    _revision(runtime, "head")
    with runtime.sessions() as session:
        repo = _repo(session, runtime)
        assert repo.lookup_key("fixture-user", "legacy-key")["receipt_id"] == "legacy-one"
        assert repo.lookup_key("other-user", "legacy-key") == current


def test_id_collision_in_another_workspace_still_refuses_upgrade(mutation_runtime):
    from ft.adapters.relational.cash_mutations import CashMutationRepository
    from ft.adapters.relational.models import WorkspaceModel
    runtime = mutation_runtime
    _old(runtime, [{}])
    with runtime.sessions.begin() as session:
        session.add(WorkspaceModel(id="legacy-other-workspace", name="Other"))
        session.flush()
        current = _save(CashMutationRepository(session, "legacy-other-workspace"), results=[])
        session.get(CashMutationReceiptModel, current["receipt_id"]).id = "legacy-one"
    with pytest.raises(ValueError, match="^legacy_receipt_collision$"):
        _revision(runtime, "head")
    with _engine(runtime).connect() as connection:
        assert connection.scalar(text("SELECT count(*) FROM cash_import_commits")) == 1
        assert connection.scalar(text("SELECT version_num FROM alembic_version")) == OLD


def test_workspace_deletion_removes_migrated_and_current_results_only_in_that_workspace(mutation_runtime):
    from ft.adapters.relational.cash_mutations import CashMutationRepository
    from ft.adapters.relational.models import UserModel, UserSessionModel, WorkspaceMembershipModel, WorkspaceModel
    from tests.test_user_workspace_access import _app, _register
    runtime = mutation_runtime
    _old(runtime, [{}])
    with runtime.sessions.begin() as session:
        current = _save(_repo(session, runtime))
        session.add(WorkspaceModel(id="legacy-unrelated", name="Unrelated"))
        session.flush()
        unrelated = _save(CashMutationRepository(session, "legacy-unrelated"), results=[])
    _revision(runtime, "head")
    client = _app(runtime)
    assert _register(client, "legacy-delete-admin@example.test").status_code == 200
    with runtime.sessions.begin() as session:
        owner = session.scalar(select(UserModel).where(UserModel.email == "legacy-delete-admin@example.test"))
        session.add(WorkspaceMembershipModel(workspace_id=runtime.workspace_id, user_id=owner.id, role="admin"))
        session.scalar(select(UserSessionModel).where(UserSessionModel.user_id == owner.id)).active_workspace_id = runtime.workspace_id
        name = session.get(WorkspaceModel, runtime.workspace_id).name
    response = client.request("DELETE", "/api/v1/auth/workspace", json={"name": name})
    assert response.status_code == 200, response.text
    with runtime.sessions() as session:
        assert session.get(WorkspaceModel, runtime.workspace_id) is None
        assert session.get(CashMutationReceiptModel, "legacy-one") is None
        assert session.get(CashMutationReceiptModel, current["receipt_id"]) is None
        assert CashMutationRepository(session, "legacy-unrelated").get("fixture-user", unrelated["receipt_id"]) == unrelated


@pytest.mark.parametrize("column, value", [("result_json", "private malformed JSON"), ("created_at", "private malformed time")])
def test_sqlite_malformed_stored_values_fail_with_safe_migration_reason(cash_web_runtime, column, value):
    runtime = cash_web_runtime
    _old(runtime, [{}])
    with _engine(runtime).begin() as connection:
        connection.execute(text(f"UPDATE cash_import_commits SET {column}=:value"), {"value": value})
    with pytest.raises(ValueError, match="^legacy_receipt_invalid$"):
        _revision(runtime, "head")
    with _engine(runtime).connect() as connection:
        assert connection.scalar(text("SELECT count(*) FROM cash_import_commits")) == 1
        assert connection.scalar(text("SELECT count(*) FROM cash_mutation_receipts")) == 0
        assert connection.scalar(text("SELECT version_num FROM alembic_version")) == OLD
