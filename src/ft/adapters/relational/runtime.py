"""Composition root and fail-closed validation for the selected relational runtime."""
from sqlalchemy import inspect, select, text
from sqlalchemy.exc import SQLAlchemyError

from ft.adapters.market_data import MarketDataProvider
from ft.application.accounts import AccountService
from ft.application.cashflow import CashflowService, TransferService
from ft.application.investment import InvestmentService, PortfolioQueryService
from ft.application.queries import FinanceQueryService
from ft.application.statement_import import StatementImportService
from ft.runtime import ServiceBundle

from .queries import (
    RelationalAccountQueryRepository,
    RelationalPortfolioRepository,
    RelationalSnapshotQueryRepository,
    RelationalTransactionQueryRepository,
)
from .uow import RelationalUnitOfWork, create_session_factory
from .dialect import RelationalEngineError, connection_summary, create_relational_engine
from .models import WorkspaceModel
from .investments import RelationalInvestmentCommandRepository
from ft.adapters.statement_import import StatementParser


SCHEMA_REVISION = "20260717_01"
REQUIRED_TABLES = {
    "workspaces", "accounts", "cash_transactions", "investment_events",
    "ledger_snapshots", "import_batches", "raw_files", "raw_records",
    "record_revisions",
}


class StorageError(RuntimeError):
    def __init__(self, code: str, database_url: str | None = None):
        self.code = code
        try:
            summary = connection_summary(database_url) if database_url else "database"
        except Exception:
            summary = "database"
        labels = {
            "storage.config": "storage configuration is invalid",
            "storage.connect": "unable to connect to selected storage",
            "storage.schema": "database schema is not initialized or current",
            "storage.workspace": "workspace does not exist",
            "storage.readonly": "selected storage is read-only",
            "storage.busy": "selected storage is busy; retry after other writes complete",
        }
        super().__init__(f"{code}: {labels.get(code, 'storage operation failed')} ({summary})")


def storage_error(exc: BaseException, database_url: str) -> StorageError:
    """Map dialect-native failures without exposing driver text to callers."""
    code = getattr(getattr(exc, "orig", exc), "sqlite_errorcode", None)
    if code in {5, 6} or "locked" in str(exc).lower() or "busy" in str(exc).lower():
        return StorageError("storage.busy", database_url)
    if code == 8 or "readonly" in str(exc).lower() or "read-only" in str(exc).lower():
        return StorageError("storage.readonly", database_url)
    return StorageError("storage.connect", database_url)


def validate_runtime(engine, workspace_id: str, database_url: str) -> None:
    try:
        tables = set(inspect(engine).get_table_names())
        missing = sorted(REQUIRED_TABLES - tables)
        if missing or "alembic_version" not in tables:
            raise StorageError("storage.schema", database_url)
        with engine.connect() as connection:
            revision = connection.scalar(text("SELECT version_num FROM alembic_version"))
            workspace = connection.scalar(select(WorkspaceModel.id).where(
                WorkspaceModel.id == workspace_id
            ))
        if revision != SCHEMA_REVISION:
            raise StorageError("storage.schema", database_url)
        if workspace is None:
            raise StorageError("storage.workspace", database_url)
    except StorageError:
        raise
    except SQLAlchemyError as exc:
        raise storage_error(exc, database_url) from exc


def build_relational_services(settings) -> ServiceBundle:
    try:
        engine = create_relational_engine(settings.database_url)
    except RelationalEngineError as exc:
        raise StorageError(exc.code, settings.database_url) from exc
    validate_runtime(engine, settings.workspace_id, settings.database_url)
    sessions = create_session_factory(engine)
    uow = RelationalUnitOfWork(sessions, settings.workspace_id)
    market_data = MarketDataProvider()
    queries = FinanceQueryService(
        accounts=RelationalAccountQueryRepository(sessions, settings.workspace_id),
        transactions=RelationalTransactionQueryRepository(sessions, settings.workspace_id),
        snapshots=RelationalSnapshotQueryRepository(sessions, settings.workspace_id),
        market_data=market_data,
    )
    return ServiceBundle(
        queries=queries,
        portfolio=PortfolioQueryService(
            RelationalPortfolioRepository(sessions, settings.workspace_id), market_data
        ),
        investments=InvestmentService(
            repository=RelationalInvestmentCommandRepository(uow)
        ),
        statement_import=StatementImportService(uow, StatementParser()),
        accounts=AccountService(uow),
        cashflow=CashflowService(uow),
        transfers=TransferService(uow),
        uow=uow,
        notices=tuple(engine.info["runtime_notices"]),
    )
