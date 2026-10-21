"""当前派生数据的归属、唯一发布及无历史副本合同。"""
from decimal import Decimal

import pytest
from alembic import command
from sqlalchemy import delete, select, text
from sqlalchemy.exc import IntegrityError

from ft.adapters.relational import ensure_workspace
from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.models import (
    AccountModel, CashProjectionDatasetModel, CashProjectionMemberModel, CashProjectionModel, CashProjectionRelationModel,
    CashProjectionStateModel, CashTransactionComponentModel, CashTransactionModel,
)
from ft.adapters.relational.projections import RelationalCashProjectionRepository
from ft.application.cash_projections import CashProjectionService
from ft.domain.cash_projection import RULES_VERSION, build_cash_projections
from tests.contract.test_cash_draft_storage import _migration_config, draft_runtime


@pytest.fixture(autouse=True)
def _other_workspace(draft_runtime):
    # The shared backend fixtures use different names for their other tenant.
    ensure_workspace(draft_runtime.sessions, "other-workspace")


def _dataset(session, runtime, identifier, *, workspace=None, state="staging"):
    session.add(CashProjectionDatasetModel(id=identifier, workspace_id=workspace or runtime.workspace_id,
        state=state, source_revision=0, source_digest="0" * 64, rules_version=RULES_VERSION))
    session.flush()


def _projection(session, runtime, dataset="first"):
    parent = session.get(CashTransactionModel, 1003)
    projection = CashProjectionModel(workspace_id=runtime.workspace_id, dataset_id=dataset, projection_id="cash:1003",
        root_cash_transaction_id=1003, account_id=101, economic_type="expense", net_amount=parent.amount,
        currency=parent.currency, occurred_at=parent.occurred_at, visible=True, member_count=1,
        accepted_relation_count=0, built_projection_version=1)
    session.add(projection)
    session.flush()
    return projection


def _mirror(runtime):
    with runtime.sessions.begin() as session:
        for identifier in (1002, 1003):
            parent = session.get(CashTransactionModel, identifier)
            parent.amount, parent.record_type = Decimal("-12.50"), "consumption"
            session.get(CashTransactionComponentModel, identifier).amount = parent.amount
        return CurrentCashRelationRepository(session, runtime.workspace_id).add(dict(kind="payment_mirror", state="confirmed",
            primary_transaction_id=1003, secondary_transaction_id=1002, primary_component_id=1003, secondary_component_id=1002,
            anchor_transaction_id=1003, anchor_component_id=1003, primary_role="expense", secondary_role="expense", applied_amount="12.50"))


def _snapshot(runtime, *, current_only=False):
    tables = ("cash_transactions", "cash_transaction_components", "transaction_relations", "cash_projection_datasets",
        "cash_projections", "cash_projection_members", "cash_projection_relations", "cash_projection_states")
    with runtime.sessions.kw["bind"].connect() as connection:
        current = connection.scalar(text("SELECT active_dataset_id FROM cash_projection_states WHERE workspace_id=:workspace"),
            {"workspace": runtime.workspace_id})
        result = {}
        for table in tables:
            condition = ""
            if current_only and table == "cash_projection_datasets":
                condition = " WHERE id=:current"
            elif current_only and table in {"cash_projections", "cash_projection_members", "cash_projection_relations"}:
                condition = " WHERE dataset_id=:current"
            ordering = "workspace_id" if table == "cash_projection_states" else "id"
            result[table] = connection.execute(text(f"SELECT * FROM {table}{condition} ORDER BY {ordering}"), {"current": current}).all()
        return result


def _migrate(runtime, target, *, downgrade=False):
    with runtime.sessions.kw["bind"].connect() as connection:
        (command.downgrade if downgrade else command.upgrade)(_migration_config(connection), target)


def test_projection_dataset_must_belong_to_the_same_workspace(draft_runtime):
    runtime = draft_runtime
    with runtime.sessions.begin() as session:
        _dataset(session, runtime, "foreign", workspace="other-workspace")
    with pytest.raises(IntegrityError), runtime.sessions.begin() as session:
        _projection(session, runtime, "foreign")


@pytest.mark.parametrize("child", ["member", "relation"])
def test_projection_children_must_use_their_parent_dataset(draft_runtime, child):
    runtime = draft_runtime
    relation = _mirror(runtime)
    with runtime.sessions.begin() as session:
        _dataset(session, runtime, "first")
        _dataset(session, runtime, "second")
        projection_id = _projection(session, runtime).id
    with pytest.raises(IntegrityError), runtime.sessions.begin() as session:
        common = dict(workspace_id=runtime.workspace_id, dataset_id="second", projection_row_id=projection_id, ordinal=0)
        session.add(CashProjectionMemberModel(**common, cash_transaction_id=1003, roles_json=["root"]) if child == "member"
            else CashProjectionRelationModel(**common, transaction_relation_id=relation["id"], kind="payment_mirror"))


@pytest.mark.parametrize("child", ["member", "relation"])
def test_projection_children_cannot_belong_to_another_workspace(draft_runtime, child):
    runtime = draft_runtime
    with runtime.sessions.begin() as session:
        _dataset(session, runtime, "first")
        _dataset(session, runtime, "foreign", workspace="other-workspace")
        projection_id = _projection(session, runtime).id
        session.add(AccountModel(id=2104, workspace_id="other-workspace", name="归属测试账户", type="cash", currencies=["CNY"]))
        session.flush()
        parent = session.get(CashTransactionModel, 1003)
        session.add(CashTransactionModel(id=2004, workspace_id="other-workspace", account_id=2104,
            amount=parent.amount, currency=parent.currency, occurred_at=parent.occurred_at))
        session.flush()
        session.add(CashTransactionComponentModel(id=2004, workspace_id="other-workspace", cash_transaction_id=2004,
            account_id=2104, amount=parent.amount, currency=parent.currency, ordinal=0))
        session.flush()
        relation = CurrentCashRelationRepository(session, "other-workspace").add(dict(kind="refund_offset", state="draft",
            primary_transaction_id=2004, primary_component_id=2004, anchor_transaction_id=2004, anchor_component_id=2004))
    with pytest.raises(IntegrityError), runtime.sessions.begin() as session:
        common = dict(workspace_id="other-workspace", dataset_id="foreign", projection_row_id=projection_id, ordinal=0)
        session.add(CashProjectionMemberModel(**common, cash_transaction_id=2004, roles_json=["root"]) if child == "member"
            else CashProjectionRelationModel(**common, transaction_relation_id=relation["id"], kind="refund_offset"))


@pytest.mark.parametrize("dataset", ["missing", "foreign"])
def test_current_dataset_pointer_requires_an_existing_owned_dataset(draft_runtime, dataset):
    runtime = draft_runtime
    with runtime.sessions.begin() as session:
        _dataset(session, runtime, "foreign", workspace="other-workspace")
    with pytest.raises(IntegrityError), runtime.sessions.begin() as session:
        session.add(CashProjectionStateModel(workspace_id=runtime.workspace_id, active_dataset_id=dataset))


def test_only_one_active_dataset_per_workspace_and_no_retired_state(draft_runtime):
    runtime = draft_runtime
    with runtime.sessions.begin() as session:
        _dataset(session, runtime, "first", state="active")
        _dataset(session, runtime, "foreign", workspace="other-workspace", state="active")
        _dataset(session, runtime, "staging-one")
        _dataset(session, runtime, "staging-two")
    for identifier, state in (("second", "active"), ("old", "retired")):
        with pytest.raises(IntegrityError), runtime.sessions.begin() as session:
            _dataset(session, runtime, identifier, state=state)


def test_current_dataset_cannot_be_deleted_before_moving_the_pointer(draft_runtime):
    runtime = draft_runtime
    current = CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    with pytest.raises(IntegrityError), runtime.sessions.begin() as session:
        session.execute(delete(CashProjectionDatasetModel).where(CashProjectionDatasetModel.id == current["active_dataset_id"]))


def test_rebuild_keeps_only_the_new_current_derived_copy(draft_runtime):
    runtime = draft_runtime
    _mirror(runtime)
    service = CashProjectionService(runtime.sessions, runtime.workspace_id)
    service.rebuild()
    sources = {key: value for key, value in _snapshot(runtime).items() if key in
        {"cash_transactions", "cash_transaction_components", "transaction_relations"}}
    for _ in range(3):
        current = service.rebuild()
        with runtime.sessions() as session:
            datasets = session.scalars(select(CashProjectionDatasetModel)).all()
            assert [(row.id, row.state) for row in datasets] == [(current["active_dataset_id"], "active")]
        after = _snapshot(runtime)
        assert all(after[key] == value for key, value in sources.items())
        assert after == _snapshot(runtime, current_only=True)


@pytest.mark.parametrize("target", ["current", "foreign"])
def test_publish_preflight_refuses_non_new_staging_without_demoting_current(draft_runtime, target):
    runtime = draft_runtime
    current = CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    with runtime.sessions.begin() as session:
        _dataset(session, runtime, "foreign", workspace="other-workspace")
        repository = RelationalCashProjectionRepository(session, runtime.workspace_id)
        with pytest.raises(RuntimeError, match="projection.incomplete"):
            repository.publish_dataset(current["active_dataset_id"] if target == "current" else target,
                source_digest=repository.source_digest(), rules_version=RULES_VERSION)
        assert session.get(CashProjectionDatasetModel, current["active_dataset_id"]).state == "active"
        assert repository.status()["projection_version"] == current["projection_version"]


def test_failure_after_pointer_switch_rolls_back_all_derived_changes(draft_runtime, monkeypatch):
    runtime = draft_runtime
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    before = _snapshot(runtime)
    with pytest.raises(RuntimeError, match="injected cleanup failure"), runtime.sessions.begin() as session:
        repository = RelationalCashProjectionRepository(session, runtime.workspace_id)
        digest = repository.source_digest()
        facts, relations = repository.read_sources()
        identifier = repository.create_staging_dataset(source_digest=digest, rules_version=RULES_VERSION)
        repository.replace_dataset(identifier, build_cash_projections(facts, relations), projection_version=2)
        execute = session.execute

        def fail_cleanup(statement, *args, **kwargs):
            if getattr(statement, "is_delete", False) and statement.table.name == "cash_projection_datasets":
                raise RuntimeError("injected cleanup failure")
            return execute(statement, *args, **kwargs)

        monkeypatch.setattr(session, "execute", fail_cleanup)
        repository.publish_dataset(identifier, source_digest=digest, rules_version=RULES_VERSION)
    assert _snapshot(runtime) == before


@pytest.mark.parametrize("target", ["current", "foreign"])
def test_full_derived_write_refuses_current_or_foreign_dataset_before_deleting(draft_runtime, target):
    runtime = draft_runtime
    current = CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    with runtime.sessions.begin() as session:
        _dataset(session, runtime, "foreign", workspace="other-workspace")
    before = _snapshot(runtime)
    with runtime.sessions.begin() as session:
        repository = RelationalCashProjectionRepository(session, runtime.workspace_id)
        with pytest.raises(RuntimeError, match="projection.incomplete"):
            repository.replace_dataset(current["active_dataset_id"] if target == "current" else target,
                build_cash_projections((), ()), projection_version=2)
    assert _snapshot(runtime) == before


def _stage(session, runtime, *, version=1):
    repository = RelationalCashProjectionRepository(session, runtime.workspace_id)
    digest = repository.source_digest()
    dataset = repository.create_staging_dataset(source_digest=digest, rules_version=RULES_VERSION)
    repository.replace_dataset(dataset, build_cash_projections(*repository.read_sources()), projection_version=version)
    return dataset, digest


@pytest.mark.parametrize("operation", ["replace", "publish"])
def test_stale_session_cannot_overwrite_a_dataset_published_by_another_session(draft_runtime, operation):
    runtime = draft_runtime
    with runtime.sessions() as stale:
        dataset, digest = _stage(stale, runtime)
        cached = stale.get(CashProjectionDatasetModel, dataset)
        stale.commit()
        with runtime.sessions.begin() as publisher:
            RelationalCashProjectionRepository(publisher, runtime.workspace_id).publish_dataset(dataset,
                source_digest=digest, rules_version=RULES_VERSION)
        before = _snapshot(runtime)
        assert cached.state == "staging"
        repository = RelationalCashProjectionRepository(stale, runtime.workspace_id)
        with pytest.raises(RuntimeError, match="projection.incomplete"):
            if operation == "replace":
                repository.replace_dataset(dataset, build_cash_projections((), ()), projection_version=2)
            else:
                repository.publish_dataset(dataset, source_digest=digest, rules_version=RULES_VERSION)
        stale.commit()
    assert _snapshot(runtime) == before


def test_later_publish_refreshes_cached_pointer_and_keeps_versions_monotonic(draft_runtime):
    runtime = draft_runtime
    with runtime.sessions() as stale:
        first, digest = _stage(stale, runtime)
        cached_state = stale.get(CashProjectionStateModel, runtime.workspace_id)
        stale.commit()
        with runtime.sessions.begin() as publisher:
            published = RelationalCashProjectionRepository(publisher, runtime.workspace_id).publish_dataset(first,
                source_digest=digest, rules_version=RULES_VERSION)
        assert cached_state.projection_version == 0
        second, digest = _stage(stale, runtime, version=2)
        latest = RelationalCashProjectionRepository(stale, runtime.workspace_id).publish_dataset(second,
            source_digest=digest, rules_version=RULES_VERSION)
        stale.commit()
        assert latest["projection_version"] == published["projection_version"] + 1
    with runtime.sessions() as session:
        assert session.scalars(select(CashProjectionDatasetModel.id)).all() == [second]


def test_ownership_migration_preserves_current_ids_and_removes_only_retired_copies(draft_runtime):
    runtime = draft_runtime
    _mirror(runtime)
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    _migrate(runtime, "20261021_44", downgrade=True)
    with runtime.sessions.begin() as session:
        _dataset(session, runtime, "old-derived")
        repository = RelationalCashProjectionRepository(session, runtime.workspace_id)
        repository.replace_dataset("old-derived", build_cash_projections(*repository.read_sources()), projection_version=1)
        session.get(CashProjectionDatasetModel, "old-derived").state = "retired"
    before = _snapshot(runtime, current_only=True)
    # 只核对本次归属迁移；后续显示规则迁移可以合法推进规则元数据。
    _migrate(runtime, "20261021_45")
    assert _snapshot(runtime) == before
    _migrate(runtime, "20261021_44", downgrade=True)
    _migrate(runtime, "20261021_45")
    assert _snapshot(runtime) == before


@pytest.mark.parametrize("corruption", ["missing_pointer", "foreign_pointer", "staging_pointer", "orphan_active", "multiple_active", "member_dataset", "relation_dataset", "projection_workspace"])
def test_ownership_migration_rejects_corruption_and_rolls_back_every_change(draft_runtime, corruption):
    runtime = draft_runtime
    _mirror(runtime)
    current = CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    _migrate(runtime, "20261021_44", downgrade=True)
    with runtime.sessions.begin() as session:
        _dataset(session, runtime, "old-derived", state="retired")
        _dataset(session, runtime, "foreign", workspace="other-workspace")
        if corruption in {"missing_pointer", "foreign_pointer"}:
            state = session.get(CashProjectionStateModel, runtime.workspace_id)
            state.active_dataset_id = "missing" if corruption == "missing_pointer" else "foreign"
        elif corruption == "staging_pointer":
            _dataset(session, runtime, "staging-target")
            session.get(CashProjectionStateModel, runtime.workspace_id).active_dataset_id = "staging-target"
            session.get(CashProjectionDatasetModel, current["active_dataset_id"]).state = "staging"
        elif corruption == "orphan_active":
            session.execute(delete(CashProjectionStateModel))
        elif corruption == "multiple_active":
            _dataset(session, runtime, "another-active", state="active")
        elif corruption in {"member_dataset", "relation_dataset"}:
            model = CashProjectionMemberModel if corruption == "member_dataset" else CashProjectionRelationModel
            row = session.scalar(select(model).where(model.dataset_id == current["active_dataset_id"]))
            row.dataset_id = "foreign"
        else:
            session.get(CashProjectionDatasetModel, current["active_dataset_id"]).workspace_id = "other-workspace"
    before = _snapshot(runtime)
    with pytest.raises(ValueError, match="inconsistent"):
        _migrate(runtime, "head")
    assert _snapshot(runtime) == before
    with runtime.sessions.kw["bind"].connect() as connection:
        assert connection.scalar(text("SELECT version_num FROM alembic_version")) == "20261021_44"
        if connection.dialect.name == "sqlite":
            assert connection.exec_driver_sql("PRAGMA foreign_keys").scalar() == 1


def test_ownership_migration_ddl_failure_restores_deleted_derived_copies(draft_runtime, monkeypatch):
    from alembic import op
    from sqlalchemy import inspect
    runtime = draft_runtime
    _mirror(runtime)
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    _migrate(runtime, "20261021_44", downgrade=True)
    with runtime.sessions.begin() as session:
        _dataset(session, runtime, "old-derived")
        repository = RelationalCashProjectionRepository(session, runtime.workspace_id)
        repository.replace_dataset("old-derived", build_cash_projections(*repository.read_sources()), projection_version=1)
        session.get(CashProjectionDatasetModel, "old-derived").state = "retired"
    before = _snapshot(runtime)
    batch = op.batch_alter_table

    def fail_after_dataset_ddl(table, *args, **kwargs):
        if table == "cash_projections":
            bind = op.get_bind()
            assert bind.scalar(text("SELECT COUNT(*) FROM cash_projection_datasets WHERE state='retired'")) == 0
            assert "uq_cash_projection_datasets_workspace_id" in {
                item["name"] for item in inspect(bind).get_unique_constraints("cash_projection_datasets")}
            raise RuntimeError("injected migration DDL failure")
        return batch(table, *args, **kwargs)

    monkeypatch.setattr(op, "batch_alter_table", fail_after_dataset_ddl)
    with pytest.raises(RuntimeError, match="injected migration DDL failure"):
        _migrate(runtime, "head")
    assert _snapshot(runtime) == before
    with runtime.sessions.kw["bind"].connect() as connection:
        assert connection.scalar(text("SELECT version_num FROM alembic_version")) == "20261021_44"
        assert "uq_cash_projection_datasets_workspace_id" not in {
            item["name"] for item in inspect(connection).get_unique_constraints("cash_projection_datasets")}
        if connection.dialect.name == "sqlite":
            assert connection.exec_driver_sql("PRAGMA foreign_keys").scalar() == 1
            assert connection.exec_driver_sql("PRAGMA foreign_key_check").all() == []
