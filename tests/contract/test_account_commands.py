from copy import deepcopy

import pytest
from sqlalchemy import select


USER = "00000000-0000-4000-8000-000000000001"


@pytest.fixture(params=["cash_web_runtime", "postgres_cash_web_runtime"])
def account_runtime(request):
    from ft.adapters.relational.models import UserModel, WorkspaceMembershipModel
    runtime = request.getfixturevalue(request.param)
    with runtime.sessions.begin() as session:
        session.add(UserModel(id=USER, email="account-commands@example.test", password_hash="synthetic")); session.flush()
        session.add(WorkspaceMembershipModel(user_id=USER, workspace_id=runtime.workspace_id, role="editor"))
    return runtime


def _service(runtime):
    from ft.adapters.relational.uow import RelationalUnitOfWork
    from ft.application.account_commands import AccountCommandService
    return AccountCommandService(RelationalUnitOfWork(runtime.sessions, runtime.workspace_id), user_id=USER)


def _create(service, key="create", **changes):
    payload = {"name": "新增测试账户", "type": "cash", "currencies": ["CNY"]}
    payload.update(changes)
    return service.create(payload, idempotency_key=key)


def test_account_is_immediately_available_and_exact_create_replay_is_stable(account_runtime):
    service = _service(account_runtime)
    result = _create(service)
    assert result["account_revision"] == 1 and result["committed"] is True
    assert result["account"]["name"] == "新增测试账户"
    assert service.get(result["account_id"])["account_revision"] == 1
    assert _create(service) == result
    from ft.adapters.relational.models import AccountModel
    with account_runtime.sessions() as session:
        assert len(session.scalars(select(AccountModel).where(AccountModel.name == "新增测试账户")).all()) == 1


def test_patch_uses_revision_and_protects_an_intervening_edit(account_runtime):
    from ft.application.account_commands import AccountCommandError
    service = _service(account_runtime)
    created = _create(service)
    updated = service.update(created["account_id"], {"name": "其他修改"}, expected_revision=1, idempotency_key="edit")
    assert updated["account_revision"] == 2
    with pytest.raises(AccountCommandError, match="account_changed"):
        service.update(created["account_id"], {"name": "过时补偿"}, expected_revision=1, idempotency_key="undo")
    assert service.get(created["account_id"])["name"] == "其他修改"
    assert service.update(created["account_id"], {"name": "其他修改"}, expected_revision=1, idempotency_key="edit") == updated
    assert _create(service) == created


def test_noop_patch_still_advances_the_account_command_chain(account_runtime):
    service = _service(account_runtime)
    created = _create(service)
    updated = service.update(created["account_id"], {}, expected_revision=1, idempotency_key="noop")
    assert updated["account_revision"] == 2
    assert service.get(created["account_id"])["account_revision"] == 2


def test_account_type_is_fixed_and_invalid_patch_is_atomic(account_runtime):
    from ft.application.account_commands import AccountCommandError
    service = _service(account_runtime)
    created = _create(service)
    with pytest.raises(AccountCommandError):
        service.update(created["account_id"], {"name": "不能部分应用", "type": "loan"}, expected_revision=1, idempotency_key="invalid")
    assert service.get(created["account_id"])["name"] == "新增测试账户"
    assert service.get(created["account_id"])["account_revision"] == 1


def test_compensation_deactivates_then_deletes_and_delete_replay_outlives_account(account_runtime):
    from ft.application.account_commands import AccountCommandError
    service = _service(account_runtime)
    created = _create(service)
    with pytest.raises(AccountCommandError, match="account_active"):
        service.delete(created["account_id"], expected_revision=1, idempotency_key="early-delete")
    inactive = service.update(created["account_id"], {"active": False}, expected_revision=1, idempotency_key="deactivate")
    deleted = service.delete(created["account_id"], expected_revision=2, idempotency_key="delete")
    assert inactive["account_revision"] == 2 and deleted["account"]["deleted"] is True
    assert service.delete(created["account_id"], expected_revision=2, idempotency_key="delete") == deleted
    with pytest.raises(AccountCommandError, match="account_not_found"):
        service.get(created["account_id"])
    assert _create(service) == created
    newer = _create(service, key="new-create", name="另一个账户")
    assert newer["account_id"] != created["account_id"]


def test_same_key_different_command_is_rejected_before_mutation(account_runtime):
    from ft.adapters.relational.cash_mutations import CashMutationConflict
    service = _service(account_runtime)
    created = _create(service)
    with pytest.raises(CashMutationConflict, match="idempotency_conflict"):
        _create(service, name="不同内容")
    with pytest.raises(CashMutationConflict, match="idempotency_conflict"):
        service.update(created["account_id"], {"name": "冲突"}, expected_revision=1, idempotency_key="create")
    assert service.get(created["account_id"])["account_revision"] == 1


def test_legacy_ordinary_writers_and_currency_extensions_advance_the_same_revision(account_runtime):
    from ft.adapters.relational.models import AccountModel
    from ft.adapters.relational.uow import RelationalUnitOfWork
    from ft.application.accounts import AccountService
    service = _service(account_runtime)
    created = _create(service)
    legacy = AccountService(RelationalUnitOfWork(account_runtime.sessions, account_runtime.workspace_id))
    assert legacy.rename_account("新增测试账户", "旧入口重命名").ok
    assert service.get(created["account_id"])["account_revision"] == 2
    assert legacy.set_active("旧入口重命名", False).ok
    assert service.get(created["account_id"])["account_revision"] == 3
    with account_runtime.sessions.begin() as session:
        session.get(AccountModel, int(created["account_id"])).currencies = ["CNY", "USD"]
    assert service.get(created["account_id"])["account_revision"] == 4


def test_duplicate_names_are_safe_conflicts_without_partial_currency_changes(account_runtime):
    from ft.application.account_commands import AccountCommandError
    service = _service(account_runtime)
    created = _create(service)
    _create(service, key="second", name="已有名称")
    with pytest.raises(AccountCommandError, match="account_name_unavailable"):
        service.update(created["account_id"], {"name": "已有名称", "currencies": ["USD"]}, expected_revision=1, idempotency_key="duplicate-name")
    assert service.get(created["account_id"])["currencies"] == ["CNY"]


def test_currencies_and_rename_preserve_existing_balances(account_runtime):
    from ft.adapters.relational.uow import RelationalUnitOfWork
    service = _service(account_runtime)
    created = _create(service)
    with RelationalUnitOfWork(account_runtime.sessions, account_runtime.workspace_id) as uow:
        snapshot = uow.snapshot.load(lock=True)
        snapshot["accounts"]["cash"]["新增测试账户"]["CNY"] = "123.456"
        uow.snapshot.save(snapshot); uow.commit()
    service.update(created["account_id"], {"name": "更名账户", "currencies": ["usd"]}, expected_revision=1, idempotency_key="currency")
    with RelationalUnitOfWork(account_runtime.sessions, account_runtime.workspace_id) as uow:
        pockets = uow.snapshot.load()["accounts"]["cash"]["更名账户"]
        assert pockets == {"CNY": "123.456", "USD": "0"}


def test_legacy_nonzero_snapshot_is_not_deleted_by_compensation(account_runtime):
    from ft.adapters.relational.uow import RelationalUnitOfWork
    from ft.application.account_commands import AccountCommandError
    service = _service(account_runtime)
    created = _create(service)
    with RelationalUnitOfWork(account_runtime.sessions, account_runtime.workspace_id) as uow:
        snapshot = uow.snapshot.load(lock=True)
        snapshot["accounts"]["cash"]["新增测试账户"]["CNY"] = "1"
        uow.snapshot.save(snapshot); uow.commit()
    service.update(created["account_id"], {"active": False}, expected_revision=1, idempotency_key="deactivate")
    assert service.get(created["account_id"])["capabilities"]["can_delete"] is False
    with pytest.raises(AccountCommandError, match="account_in_use"):
        service.delete(created["account_id"], expected_revision=2, idempotency_key="delete")


def test_crypto_account_uses_the_existing_security_snapshot_bucket(account_runtime):
    from ft.adapters.relational.uow import RelationalUnitOfWork
    from ft.application.account_commands import AccountCommandError
    service = _service(account_runtime)
    created = _create(service, type="crypto", currencies=["USD"])
    with RelationalUnitOfWork(account_runtime.sessions, account_runtime.workspace_id) as uow:
        snapshot = uow.snapshot.load(lock=True)
        snapshot.setdefault("accounts", {}).setdefault("security", {})["新增测试账户"] = {"cash": {"USD": "100"}, "positions": []}
        uow.snapshot.save(snapshot); uow.commit()
    service.update(created["account_id"], {"active": False}, expected_revision=1, idempotency_key="deactivate")
    assert service.get(created["account_id"])["capabilities"]["can_delete"] is False
    with pytest.raises(AccountCommandError, match="account_in_use"):
        service.delete(created["account_id"], expected_revision=2, idempotency_key="delete")


@pytest.mark.parametrize("invalid_bucket", ["invalid bucket", None, []])
def test_unreadable_snapshot_bucket_cannot_be_treated_as_an_unused_account(account_runtime, invalid_bucket):
    from ft.adapters.relational.models import LedgerSnapshotModel
    from ft.application.account_commands import AccountCommandError
    service = _service(account_runtime)
    created = _create(service)
    service.update(created["account_id"], {"active": False}, expected_revision=1, idempotency_key="deactivate")
    with account_runtime.sessions.begin() as session:
        session.get(LedgerSnapshotModel, account_runtime.workspace_id).payload = {"accounts": {"cash": invalid_bucket}}
    assert service.get(created["account_id"])["capabilities"]["can_delete"] is False
    with pytest.raises(AccountCommandError, match="account_in_use"):
        service.delete(created["account_id"], expected_revision=2, idempotency_key="delete")


@pytest.mark.parametrize("reference_kind", ["draft_component", "mapping", "alias"])
def test_references_prevent_account_compensation_from_cascading(account_runtime, reference_kind):
    from ft.adapters.relational.models import AccountAliasModel, StatementAccountMappingModel, CashTransactionModel, CashTransactionComponentModel
    from ft.application.account_commands import AccountCommandError
    service = _service(account_runtime)
    created = _create(service)
    account_id = int(created["account_id"])
    with account_runtime.sessions.begin() as session:
        if reference_kind == "alias":
            session.add(AccountAliasModel(workspace_id=account_runtime.workspace_id, account_id=account_id, alias_type="other", alias_value="synthetic"))
        elif reference_kind == "mapping":
            session.add(StatementAccountMappingModel(workspace_id=account_runtime.workspace_id, account_id=account_id, source_type="alipay", identity_kind="account", source_account_key="synthetic"))
        else:
            parent = CashTransactionModel(workspace_id=account_runtime.workspace_id, state="draft", cash_granularity="aggregate", account_id=None)
            session.add(parent); session.flush()
            session.add(CashTransactionComponentModel(workspace_id=account_runtime.workspace_id, cash_transaction_id=parent.id, account_id=account_id, ordinal=0))
    service.update(created["account_id"], {"active": False}, expected_revision=1, idempotency_key="deactivate")
    with pytest.raises(AccountCommandError, match="account_in_use"):
        service.delete(created["account_id"], expected_revision=2, idempotency_key="delete")
    assert service.get(created["account_id"])["active"] is False


def test_permissions_and_workspace_scope_are_checked_inside_command(account_runtime):
    from ft.adapters.relational.models import WorkspaceMembershipModel
    from ft.application.account_commands import AccountCommandError
    from ft.application.access import PermissionDenied
    service = _service(account_runtime)
    with pytest.raises(AccountCommandError, match="account_not_found"):
        service.get("999999")
    with pytest.raises(AccountCommandError, match="account_not_found"):
        service.get("104")
    with pytest.raises(AccountCommandError, match="account_not_found"):
        service.update("104", {"name": "不可跨工作区"}, expected_revision=1, idempotency_key="foreign-account")
    with account_runtime.sessions.begin() as session:
        session.get(WorkspaceMembershipModel, (account_runtime.workspace_id, USER)).role = "viewer"
    with pytest.raises(PermissionDenied):
        _create(service)
    assert service.get("101")["capabilities"]["can_update"] is False


def test_account_receipt_and_mutation_rollback_together(account_runtime, monkeypatch):
    from ft.adapters.relational.cash_mutations import CashMutationRepository
    from ft.adapters.relational.models import AccountModel
    original = CashMutationRepository.create
    def failed_receipt(*args, **kwargs):
        original(*args, **kwargs)
        raise RuntimeError("synthetic outer failure")
    monkeypatch.setattr(CashMutationRepository, "create", failed_receipt)
    with pytest.raises(RuntimeError):
        _create(_service(account_runtime))
    with account_runtime.sessions() as session:
        assert session.scalar(select(AccountModel).where(AccountModel.name == "新增测试账户")) is None


def test_orm_rejects_a_stale_account_writer_even_outside_workspace_service(account_runtime):
    from ft.adapters.relational.models import AccountModel
    from sqlalchemy.orm.exc import StaleDataError
    with account_runtime.sessions() as first, account_runtime.sessions() as second:
        original, stale = first.get(AccountModel, 101), second.get(AccountModel, 101)
        original.name = "先保存"; first.commit()
        stale.name = "过时保存"
        with pytest.raises(StaleDataError):
            second.commit()


@pytest.mark.parametrize("patch", [{"currencies": "CNY"}, {"active": 1}, {"name": ""}, {"name": "x" * 256}, {"currencies": ["人民币"]}, {"source_payload": {}}])
def test_invalid_patch_does_not_write_or_advance_revision(account_runtime, patch):
    from ft.application.account_commands import AccountCommandError
    service = _service(account_runtime)
    created = _create(service)
    with pytest.raises(AccountCommandError):
        service.update(created["account_id"], deepcopy(patch), expected_revision=1, idempotency_key="invalid")
    assert service.get(created["account_id"])["account_revision"] == 1


def _migration_config(connection):
    from pathlib import Path
    from alembic.config import Config
    root = Path(__file__).resolve().parents[2]
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.attributes["connection"] = connection
    return config


def test_account_revision_migration_preserves_populated_accounts_and_references(account_runtime):
    from alembic import command
    from sqlalchemy import text
    engine = account_runtime.sessions.kw["bind"]
    with engine.connect() as connection:
        config = _migration_config(connection)
        command.downgrade(config, "20261021_40")
        connection.commit()
        tables = ("accounts", "cash_transactions", "cash_transaction_components", "transaction_relations", "cash_projections", "ledger_snapshots")
        before = {table: connection.execute(text(f"SELECT * FROM {table} ORDER BY 1")).mappings().all() for table in tables}
        connection.commit()
        command.upgrade(config, "head")
        for table in tables:
            after = connection.execute(text(f"SELECT * FROM {table} ORDER BY 1")).mappings().all()
            normalized = [{key: value for key, value in row.items() if key in (before[table][0].keys() if before[table] else ()) and not (table == "accounts" and key == "revision")} for row in after]
            assert normalized == [dict(row) for row in before[table]]
        assert all(row[0] == 1 for row in connection.execute(text("SELECT revision FROM accounts")))


def test_account_revision_migration_refuses_to_drop_used_concurrency_tokens(account_runtime):
    from alembic import command
    from sqlalchemy import text
    from ft.adapters.relational.models import AccountModel
    with account_runtime.sessions.begin() as session:
        session.get(AccountModel, 101).name = "已推进修订"
    with account_runtime.sessions.kw["bind"].connect() as connection:
        with pytest.raises(ValueError, match="account revisions"):
            command.downgrade(_migration_config(connection), "20261021_40")
        connection.rollback()
        assert connection.scalar(text("SELECT revision FROM accounts WHERE id=101")) == 2
        assert connection.scalar(text("SELECT version_num FROM alembic_version")) == "20261023_50"


def test_sqlite_account_migration_keeps_deleted_id_high_watermark(cash_web_runtime):
    from alembic import command
    from sqlalchemy import text
    from ft.adapters.relational.models import AccountModel
    engine = cash_web_runtime.sessions.kw["bind"]
    with engine.connect() as connection:
        config = _migration_config(connection)
        command.downgrade(config, "20261021_40"); connection.commit()
        connection.execute(text("INSERT INTO accounts (id, workspace_id, name, type, active, currencies, metadata_json, created_at, updated_at) "
            "VALUES (999999, :workspace, 'high-water', 'cash', 0, '[]', '{}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"), {"workspace": cash_web_runtime.workspace_id})
        connection.execute(text("DELETE FROM accounts WHERE id=999999")); connection.commit()
        command.upgrade(config, "head"); connection.commit()
    with cash_web_runtime.sessions.begin() as session:
        row = AccountModel(workspace_id=cash_web_runtime.workspace_id, name="new-identity", type="cash")
        session.add(row); session.flush()
        assert row.id > 999999


def test_sqlite_empty_account_table_keeps_its_previous_identity_sequence(tmp_path):
    from alembic import command
    from alembic.config import Config
    from pathlib import Path
    from sqlalchemy import create_engine, text
    root = Path(__file__).resolve().parents[2]
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", f"sqlite+pysqlite:///{tmp_path / 'empty-accounts.db'}")
    command.upgrade(config, "head")
    engine = create_engine(config.get_main_option("sqlalchemy.url"))
    with engine.begin() as connection:
        connection.execute(text("INSERT INTO workspaces (id,name,created_at) VALUES ('empty','Empty',CURRENT_TIMESTAMP)"))
        connection.execute(text("INSERT INTO accounts (id,workspace_id,name,type,active,currencies,metadata_json,created_at,updated_at) "
            "VALUES (999999,'empty','deleted','cash',0,'[]','{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)"))
        connection.execute(text("DELETE FROM accounts"))
    command.downgrade(config, "20261021_40")
    command.upgrade(config, "head")
    with engine.begin() as connection:
        connection.execute(text("INSERT INTO accounts (workspace_id,name,type,active,currencies,metadata_json,created_at,updated_at) "
            "VALUES ('empty','new-identity','cash',1,'[]','{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)"))
        assert connection.scalar(text("SELECT id FROM accounts")) > 999999
    engine.dispose()
