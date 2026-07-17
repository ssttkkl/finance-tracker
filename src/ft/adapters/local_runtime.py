"""Composition root for local-ledger application services."""
from ft.adapters.local_query import (
    LocalAccountQueryRepository,
    LocalSnapshotQueryRepository,
    LocalTransactionQueryRepository,
)
from ft.adapters.market_data import LegacyMarketDataProvider
from ft.adapters.local_change_set import LocalGitChangeSetRepository
from ft.adapters.local_config import LocalMappingProvider
from ft.adapters.local_import import LocalCashflowImporter, LocalCashflowImportRepository
from ft.adapters.local_investment import (
    LocalInvestmentCommandRepository,
    LocalInvestmentImporter,
    LocalPortfolioRepository,
)
from ft.adapters.local_verification import LocalVerificationRepository
from ft.adapters.local_sync import (
    LocalConnectorRegistry,
    LocalInvestmentEventRepository,
    LocalSecretStore,
)
from ft.adapters.local_reconciliation import LocalReconciliationRepository
from ft.application.change_sets import ChangeSetService
from ft.application.imports import CashflowImportService
from ft.application.investment import InvestmentService, PortfolioQueryService
from ft.application.queries import FinanceQueryService
from ft.application.reconcile import ReconcileService
from ft.application.sync import ConnectorSyncService
from ft.application.verification import VerificationService
from ft.runtime import ServiceBundle


def build_local_services(ledger_root) -> ServiceBundle:
    change_set_repository = LocalGitChangeSetRepository(ledger_root)
    change_sets = ChangeSetService(change_set_repository)
    queries = FinanceQueryService(
        accounts=LocalAccountQueryRepository(ledger_root),
        transactions=LocalTransactionQueryRepository(ledger_root),
        snapshots=LocalSnapshotQueryRepository(ledger_root),
        market_data=LegacyMarketDataProvider(),
    )
    cashflow_imports = CashflowImportService(
        importer=LocalCashflowImporter(),
        repository=LocalCashflowImportRepository(ledger_root),
        mappings=LocalMappingProvider(ledger_root),
        change_sets=change_set_repository,
    )
    verification = VerificationService(
        LocalVerificationRepository(ledger_root),
        change_sets=change_set_repository,
    )
    investments = InvestmentService(
        repository=LocalInvestmentCommandRepository(ledger_root),
        importer=LocalInvestmentImporter(ledger_root),
        change_sets=change_set_repository,
    )
    portfolio = PortfolioQueryService(
        LocalPortfolioRepository(ledger_root),
        LegacyMarketDataProvider(),
    )
    connector_sync = ConnectorSyncService(
        LocalConnectorRegistry(ledger_root),
        LocalSecretStore(ledger_root),
        LocalMappingProvider(ledger_root),
        LocalInvestmentEventRepository(ledger_root),
        change_set_repository,
    )
    reconciliation = ReconcileService(
        LocalReconciliationRepository(ledger_root),
        change_set_repository,
    )
    return ServiceBundle(
        queries=queries,
        cashflow_imports=cashflow_imports,
        verification=verification,
        change_sets=change_sets,
        investments=investments,
        portfolio=portfolio,
        connector_sync=connector_sync,
        reconciliation=reconciliation,
    )
