"""Composition root and fail-closed validation for PostgreSQL runtime services."""
from sqlalchemy import create_engine, inspect, select, text
from sqlalchemy.exc import SQLAlchemyError

from ft.adapters.market_data import MarketDataProvider
from ft.application.accounts import AccountService
from ft.application.cashflow import CashflowService, TransferService
from ft.application.investment import InvestmentService, PortfolioQueryService
from ft.application.queries import FinanceQueryService
from ft.application.statement_import import StatementImportService
from ft.runtime import ServiceBundle

from .queries import (
    PostgresAccountQueryRepository,
    PostgresPortfolioRepository,
    PostgresSnapshotQueryRepository,
    PostgresTransactionQueryRepository,
)
from .uow import PostgresUnitOfWork, create_session_factory
from .models import WorkspaceModel
from .investments import PostgresInvestmentCommandRepository
from ft.adapters.statement_import import StatementParser


SCHEMA_REVISION = "20260717_01"
REQUIRED_TABLES = {
    "workspaces", "accounts", "cash_transactions", "investment_events",
    "ledger_snapshots", "import_batches", "raw_files", "raw_records",
    "record_revisions",
}


class PostgresRuntimeError(RuntimeError):
    pass


def validate_runtime(engine, workspace_id: str) -> None:
    try:
        tables = set(inspect(engine).get_table_names())
        missing = sorted(REQUIRED_TABLES - tables)
        if missing or "alembic_version" not in tables:
            raise PostgresRuntimeError(
                "database schema is not initialized; run `uv run alembic upgrade head`"
            )
        with engine.connect() as connection:
            revision = connection.scalar(text("SELECT version_num FROM alembic_version"))
            workspace = connection.scalar(select(WorkspaceModel.id).where(
                WorkspaceModel.id == workspace_id
            ))
        if revision != SCHEMA_REVISION:
            raise PostgresRuntimeError(
                f"database schema revision is {revision!r}, expected {SCHEMA_REVISION!r}; "
                "run `uv run alembic upgrade head`"
            )
        if workspace is None:
            raise PostgresRuntimeError(f"unknown workspace: {workspace_id}")
    except PostgresRuntimeError:
        raise
    except SQLAlchemyError as exc:
        raise PostgresRuntimeError("unable to connect to PostgreSQL") from exc


def build_postgres_services(settings) -> ServiceBundle:
    engine = create_engine(settings.database_url, pool_pre_ping=True)
    validate_runtime(engine, settings.workspace_id)
    sessions = create_session_factory(engine)
    uow = PostgresUnitOfWork(sessions, settings.workspace_id)
    market_data = MarketDataProvider()
    queries = FinanceQueryService(
        accounts=PostgresAccountQueryRepository(sessions, settings.workspace_id),
        transactions=PostgresTransactionQueryRepository(sessions, settings.workspace_id),
        snapshots=PostgresSnapshotQueryRepository(sessions, settings.workspace_id),
        market_data=market_data,
    )
    return ServiceBundle(
        queries=queries,
        portfolio=PortfolioQueryService(
            PostgresPortfolioRepository(sessions, settings.workspace_id), market_data
        ),
        investments=InvestmentService(
            repository=PostgresInvestmentCommandRepository(uow)
        ),
        statement_import=StatementImportService(uow, StatementParser()),
        accounts=AccountService(uow),
        cashflow=CashflowService(uow),
        transfers=TransferService(uow),
        uow=uow,
    )
