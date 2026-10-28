"""全额退款显示规则升级只修改派生标志，保留当前财务事实和快照边界。"""
from decimal import Decimal

import pytest
from alembic import command
from sqlalchemy import event, inspect, select, text

from ft.adapters.relational import ensure_workspace
from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.models import CashProjectionDatasetModel, CashProjectionModel, CashProjectionStateModel
from ft.adapters.relational.projections import RelationalCashProjectionRepository
from ft.application.cash_projections import CashProjectionService
from ft.application.web_queries import CashLedgerQueryService, ProjectionUpdatedError
from ft.domain.cash_projection import build_cash_projections
from tests.contract.test_cash_draft_storage import _migration_config
from tests.contract.test_cash_relation_context import _add, relation_context_runtime


def _legacy(runtime):
    engine = runtime.sessions.kw["bind"]
    with runtime.sessions.begin() as session:
        _add(CurrentCashRelationRepository(session, runtime.workspace_id), 1101, 1103,
            kind="refund_offset", state="confirmed")
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    ensure_workspace(runtime.sessions, "refund-unaffected")
    CashProjectionService(runtime.sessions, "refund-unaffected").rebuild()
    ensure_workspace(runtime.sessions, "refund-unready")
    with runtime.sessions.begin() as session:
        session.add(CashProjectionStateModel(workspace_id="refund-unready", rules_version="cash-projection-v1"))
        session.execute(text("UPDATE cash_projection_states SET rules_version='cash-projection-v1'"))
        session.execute(text("UPDATE cash_projection_datasets SET rules_version='cash-projection-v1'"))
        session.execute(text("UPDATE cash_projections SET visible=false, hidden_reason='full_refund' "
            "WHERE workspace_id=:workspace AND root_cash_transaction_id=1101"), {"workspace": runtime.workspace_id})
        repo = RelationalCashProjectionRepository(session, runtime.workspace_id)
        staging = repo.create_staging_dataset(source_digest=repo.source_digest(), rules_version="cash-projection-v1")
        repo.replace_dataset(staging, build_cash_projections(*repo.read_sources()), projection_version=99)
        session.execute(text("UPDATE cash_projections SET visible=false, hidden_reason='full_refund' "
            "WHERE dataset_id=:dataset AND root_cash_transaction_id=1101"), {"dataset": staging})
    # 游标在当前 schema 生成，降级后不再调用当前 ORM 查询。
    page = CashLedgerQueryService(runtime.sessions, runtime.workspace_id).list_cash_projections(limit=1)
    with engine.connect() as connection:
        command.downgrade(_migration_config(connection), "20261021_47")
        connection.commit()
    return engine, staging, page.next_cursor


def _snapshot(connection):
    tables = ("cash_transactions", "cash_transaction_components", "transaction_relations", "cash_source_bindings",
        "cash_transaction_issues", "cash_relation_candidates", "cash_projection_members", "cash_projection_relations")
    return {table: connection.execute(text(f"SELECT {','.join(column['name'] for column in inspect(connection).get_columns(table) if column['name'] not in {'record_origin', 'manual_transfer_id'})} FROM {table} ORDER BY id")).all() for table in tables}


def _projection_values(connection):
    return connection.execute(select(*(column for column in CashProjectionModel.__table__.columns
        if column.name not in {"visible", "hidden_reason", "built_projection_version", "entry_id"})).order_by(CashProjectionModel.id)).all()


def test_upgrade_converts_active_and_staging_refunds_without_rebuilding_sources(relation_context_runtime):
    runtime = relation_context_runtime
    engine, staging, cursor = _legacy(runtime)
    query = CashLedgerQueryService(runtime.sessions, runtime.workspace_id)
    assert cursor is not None
    with engine.connect() as connection:
        before, values = _snapshot(connection), _projection_values(connection)
        versions = dict(connection.execute(text("SELECT workspace_id,projection_version FROM cash_projection_states")).all())
        connection.commit()
        command.upgrade(_migration_config(connection), "head")
        connection.commit()
        assert connection.scalar(text("SELECT version_num FROM alembic_version")) == "20261028_51"
        assert "cash-projection-v2" in next(column["default"] for column in inspect(connection).get_columns("cash_projection_states")
            if column["name"] == "rules_version")
        assert _snapshot(connection) == before and _projection_values(connection) == values
    with runtime.sessions() as session:
        rows = session.scalars(select(CashProjectionModel).where(CashProjectionModel.workspace_id == runtime.workspace_id,
            CashProjectionModel.root_cash_transaction_id == 1101)).all()
        assert len(rows) == 2 and any(row.dataset_id == staging for row in rows)
        assert all(row.visible and row.hidden_reason is None and row.net_amount == Decimal("0") for row in rows)
        state = session.get(CashProjectionStateModel, runtime.workspace_id)
        assert state.projection_version == versions[runtime.workspace_id] + 1 and state.availability == "ready"
        assert session.get(CashProjectionStateModel, "refund-unaffected").projection_version == versions["refund-unaffected"]
        assert session.get(CashProjectionStateModel, "refund-unready").availability == "uninitialized"
        assert all(row.rules_version == "cash-projection-v2" for row in session.scalars(select(CashProjectionStateModel)))
        assert all(row.rules_version == "cash-projection-v2" for row in session.scalars(select(CashProjectionDatasetModel)))
    with pytest.raises(ProjectionUpdatedError):
        query.list_cash_projections(limit=1, cursor=cursor)
    full = next(item for item in query.list_cash_projections().items if item.projection_id == "cash:1101")
    assert full.amount == "0" and full.confirmation_status == "confirmed"


def test_downgrade_hides_new_rule_refunds_and_never_rewinds_publication_version(relation_context_runtime):
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        _add(CurrentCashRelationRepository(session, runtime.workspace_id), 1101, 1103,
            kind="refund_offset", state="confirmed")
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    with runtime.sessions() as session:
        full = session.scalar(select(CashProjectionModel).where(CashProjectionModel.workspace_id == runtime.workspace_id,
            CashProjectionModel.root_cash_transaction_id == 1101))
        assert full.visible and full.hidden_reason is None
        version = session.get(CashProjectionStateModel, runtime.workspace_id).projection_version
    engine = runtime.sessions.kw["bind"]
    with engine.connect() as connection:
        before = _snapshot(connection)
        connection.commit()
        command.downgrade(_migration_config(connection), "20261021_47")
        connection.commit()
        assert "cash-projection-v1" in next(column["default"] for column in inspect(connection).get_columns("cash_projection_states")
            if column["name"] == "rules_version")
        assert _snapshot(connection) == before
        flags = connection.execute(text("SELECT visible,hidden_reason FROM cash_projections WHERE "
            "workspace_id=:workspace AND root_cash_transaction_id=1101"), {"workspace": runtime.workspace_id}).one()
        assert not flags[0] and flags[1] == "full_refund"
        assert connection.scalar(text("SELECT projection_version FROM cash_projection_states WHERE workspace_id=:workspace"),
            {"workspace": runtime.workspace_id}) == version + 1
        connection.commit()
        command.upgrade(_migration_config(connection), "head")
        connection.commit()
        assert _snapshot(connection) == before
        assert connection.scalar(text("SELECT projection_version FROM cash_projection_states WHERE workspace_id=:workspace"),
            {"workspace": runtime.workspace_id}) == version + 2


def test_visibility_migration_failure_rolls_back_flags_versions_and_schema(relation_context_runtime):
    runtime = relation_context_runtime
    engine, _staging, _cursor = _legacy(runtime)
    with engine.connect() as connection:
        before = {table: connection.execute(text(f"SELECT * FROM {table} ORDER BY " +
            ("workspace_id" if table == "cash_projection_states" else "id"))).all()
            for table in ("cash_projections", "cash_projection_states", "cash_projection_datasets")}
        connection.commit()
        reached = False
        def fail(_connection, _cursor, statement, _parameters, _context, _many):
            nonlocal reached
            if statement.lstrip().lower().startswith("update cash_projections"):
                reached = True
                raise RuntimeError("injected visibility migration failure")
        event.listen(engine, "after_cursor_execute", fail)
        try:
            with pytest.raises(RuntimeError, match="injected visibility migration failure"):
                command.upgrade(_migration_config(connection), "head")
        finally:
            event.remove(engine, "after_cursor_execute", fail)
        assert reached
        connection.rollback()
        assert connection.scalar(text("SELECT version_num FROM alembic_version")) == "20261021_47"
        for table, values in before.items():
            assert connection.execute(text(f"SELECT * FROM {table} ORDER BY " +
                ("workspace_id" if table == "cash_projection_states" else "id"))).all() == values
