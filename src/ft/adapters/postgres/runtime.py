"""Composition root for PostgreSQL-backed application services."""
from sqlalchemy import create_engine

from ft.adapters.market_data import LegacyMarketDataProvider
from ft.application.accounts import AccountService
from ft.application.cashflow import CashflowService, TransferService
from ft.application.investment import PortfolioQueryService
from ft.application.queries import FinanceQueryService
from ft.runtime import ServiceBundle

from .queries import (
    PostgresAccountQueryRepository,
    PostgresPortfolioRepository,
    PostgresSnapshotQueryRepository,
    PostgresTransactionQueryRepository,
)
from .uow import PostgresUnitOfWork, create_session_factory


def build_postgres_services(settings) -> ServiceBundle:
    engine = create_engine(settings.database_url, pool_pre_ping=True)
    sessions = create_session_factory(engine)
    uow = PostgresUnitOfWork(sessions, settings.workspace_id)
    market_data = LegacyMarketDataProvider()
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
        accounts=AccountService(uow),
        cashflow=CashflowService(uow),
        transfers=TransferService(uow),
        uow=uow,
    )
