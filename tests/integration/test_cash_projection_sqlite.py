"""用户 SQLite 临时副本上的收支投影集成合同。"""
from __future__ import annotations


def test_projection_rebuild_isolated_to_sqlite_copy(projection_sqlite_copy):
    from alembic import command
    from alembic.config import Config
    from sqlalchemy import inspect, select
    from ft.adapters.relational import create_relational_engine, create_session_factory
    from ft.adapters.relational.models import CashTransactionModel, WorkspaceModel
    from ft.application.cash_projections import CashProjectionService
    from ft.application.web_queries import CashLedgerQueryService
    from ft.domain.cash_projection import CashProjectionError

    database_url = f"sqlite+pysqlite:///{projection_sqlite_copy.copy}"
    config = Config("alembic.ini")
    config.set_main_option("script_location", "migrations")
    config.set_main_option("sqlalchemy.url", database_url)
    command.upgrade(config, "head")
    engine = create_relational_engine(database_url)
    try:
        sessions = create_session_factory(engine)
        with sessions() as session:
            workspace_id = session.scalar(select(WorkspaceModel.id).where(WorkspaceModel.id == "default"))
            if workspace_id is None:
                workspace_id = session.scalar(select(WorkspaceModel.id).order_by(WorkspaceModel.id))
        assert workspace_id is not None
        service = CashProjectionService(sessions, workspace_id)
        assert {"cash_projection_states", "cash_projection_datasets"} <= set(inspect(engine).get_table_names())
        try:
            first = service.rebuild()
        except CashProjectionError as error:
            assert str(error) == "projection.invalid_relation"
            status = service.status()
            assert status["availability"] == "uninitialized"
            assert status["last_build_status"] == "failed"
        else:
            second = service.rebuild()
            with sessions() as session:
                source_count = len(session.scalars(select(CashTransactionModel).where(
                    CashTransactionModel.workspace_id == workspace_id,
                    CashTransactionModel.deleted_at.is_(None),
                )).all())
            assert first["availability"] == second["availability"] == "ready"
            assert second["projection_version"] == first["projection_version"] + 1
            assert second["member_count"] == source_count
            CashLedgerQueryService(sessions, workspace_id).list_cash_projections(limit=1)
    finally:
        engine.dispose()
