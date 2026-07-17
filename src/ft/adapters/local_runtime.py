"""Composition root for local-ledger application services."""
from ft.adapters.local_query import (
    LocalAccountQueryRepository,
    LocalSnapshotQueryRepository,
    LocalTransactionQueryRepository,
)
from ft.adapters.market_data import LegacyMarketDataProvider
from ft.application.queries import FinanceQueryService
from ft.runtime import ServiceBundle


def build_local_services(ledger_root) -> ServiceBundle:
    queries = FinanceQueryService(
        accounts=LocalAccountQueryRepository(ledger_root),
        transactions=LocalTransactionQueryRepository(ledger_root),
        snapshots=LocalSnapshotQueryRepository(ledger_root),
        market_data=LegacyMarketDataProvider(),
    )
    return ServiceBundle(queries=queries)
