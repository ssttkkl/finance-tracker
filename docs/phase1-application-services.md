# Phase 1 Application Services

## Status

Phase 1 closure is complete. The earlier implementation represented a core
slice; the closure now routes all 36 independently callable CLI leaves through
application services and explicit ports.

The CLI owns argument parsing, confirmation, exit-code mapping, and rendering.
Application services own use-case validation, orchestration, state transitions,
idempotency, and structured DTOs. Local CSV/YAML/Git, statement parsing,
external APIs, market prices, and file output remain adapters.

## Application services

- `AccountService` owns account mutations.
- `CashflowService` and `TransferService` own manual cashflow, check-in, and
  transfers.
- `FinanceQueryService` owns account balances, reports, and transaction queries.
- `CashflowImportService` owns statement conversion and atomic cashflow import.
- `VerificationService` owns verification and optional projection rebuild.
- `ChangeSetService` owns status, commit, and reset semantics; reset confirmation
  remains in the CLI.
- `InvestmentService` owns all manual investment commands and investment import.
- `PortfolioQueryService` owns portfolio projection and injected valuation.
- `ConnectorSyncService` owns provider selection, credential/mapping injection,
  account-scoped idempotency, dry-run/export, and atomic event append.
- `ReconcileService` owns the start/continue/abort state machine.

## Command evidence matrix

| # | CLI leaf | Application service | Operation |
|---:|---|---|---|
| 1 | `acct add` | `AccountService` | `create_account` |
| 2 | `acct list` | `FinanceQueryService` | `list_accounts` |
| 3 | `acct rename` | `AccountService` | `rename_account` |
| 4 | `acct delete` | `AccountService` | `delete_account` |
| 5 | `acct activate` | `AccountService` | `set_active` |
| 6 | `acct deactivate` | `AccountService` | `set_active` |
| 7 | `add` | `CashflowService` | `add_manual_transaction` |
| 8 | `checkin` | `CashflowService` | `checkin_balance` |
| 9 | `transfer` | `TransferService` | `transfer` |
| 10 | `report` | `FinanceQueryService` | `report` |
| 11 | `list` | `FinanceQueryService` | `list_transactions` |
| 12 | `convert` | `CashflowImportService` | `convert` |
| 13 | `append` | `CashflowImportService` | `append` |
| 14 | `verify` | `VerificationService` | `verify` |
| 15 | `commit` | `ChangeSetService` | `commit` |
| 16 | `status` | `ChangeSetService` | `status` |
| 17 | `reset` | `ChangeSetService` | `reset` |
| 18 | `reconcile` | `ReconcileService` | `start` |
| 19 | `reconcile --continue-with-decisions` | `ReconcileService` | `continue_with_decisions` |
| 20 | `reconcile --abort` | `ReconcileService` | `abort` |
| 21 | `stock buy` | `InvestmentService` | `buy` |
| 22 | `stock sell` | `InvestmentService` | `sell` |
| 23 | `stock swap` | `InvestmentService` | `swap` |
| 24 | `stock deposit` | `InvestmentService` | `deposit` |
| 25 | `stock withdraw` | `InvestmentService` | `withdraw` |
| 26 | `stock dividend` | `InvestmentService` | `dividend` |
| 27 | `stock checkin` | `InvestmentService` | `checkin_ticker` / `checkin_cash` |
| 28 | `stock list` | `PortfolioQueryService` | `get_portfolio` |
| 29 | `stock convert` | `InvestmentService` | `convert` |
| 30 | `stock append` | `InvestmentService` | `append` |
| 31 | `stock sync polymarket` | `ConnectorSyncService` | `sync` |
| 32 | `stock sync kraken` | `ConnectorSyncService` | `sync` |
| 33 | `stock sync okx` | `ConnectorSyncService` | `sync` |
| 34 | `stock sync binance` | `ConnectorSyncService` | `sync` |
| 35 | `stock sync coinbase` | `ConnectorSyncService` | `sync` |
| 36 | `stock sync bybit` | `ConnectorSyncService` | `sync` |

## Ports and adapter evidence

The application-facing protocols live in `ft.repositories` and
`ft.connectors`. They cover account/cashflow/investment persistence, snapshot
and query reads, verification, reconciliation state, change sets, secrets,
mappings, market data, importers, connector registry, and external connectors.

Local compatibility is split into focused adapters rather than adding new use
cases to `LocalCsvUnitOfWork`:

| Concern | Local adapter |
|---|---|
| Query reads | `LocalAccountQueryRepository`, `LocalTransactionQueryRepository`, `LocalSnapshotQueryRepository` |
| Market prices | `LegacyMarketDataProvider` |
| Cashflow parser/import | `LocalCashflowImporter`, `LocalCashflowImportRepository` |
| CSV export | `write_csv_export` CLI adapter |
| Verification/rebuild | `LocalVerificationRepository` |
| Change sets | `LocalGitChangeSetRepository` |
| Investment commands/import | `LocalInvestmentCommandRepository`, `LocalInvestmentImporter` |
| Portfolio reads | `LocalPortfolioRepository` |
| Secrets/mapping | `LocalSecretStore`, `LocalMappingProvider` |
| External connectors | `CcxtConnector`, `PolymarketConnector`, `LocalConnectorRegistry` |
| Sync idempotency/write | `LocalInvestmentEventRepository` |
| Reconciliation persistence | `LocalReconciliationRepository` |

Parsers under `ft.importers`, ccxt/Polymarket clients, and local CSV rendering do
not become application services. They are invoked only through these adapter
boundaries. The local composition root is `ft.runtime.build_local_services`.

## Reconciliation state machine

```text
idle --start(needs review)--> awaiting_decisions
idle --start(no review)-----> completed
awaiting_decisions --continue_with_decisions--> completed
awaiting_decisions --abort--------------------> aborted
```

Invalid transitions return the structured code
`reconciliation.invalid_state` without calling the repository.

## Testing and enforcement

- Application-service tests use hand-written fake repositories, importers,
  connectors, market data, secret stores, mappings, and change sets.
- Local adapter behavior remains protected by the pre-existing conversion,
  append, reconciliation, investment replay, connector, and CLI suites.
- `tests/test_cli_application_boundary.py` performs an AST audit that rejects
  direct CLI calls to legacy business entry points and rejects local-storage,
  Git, network implementation, or terminal dependencies in `ft.application`.
- The same test requires this matrix to contain exactly 36 unique leaves.

Historical direct functions remain temporarily available as compatibility
adapter implementations and for characterization tests. They are no longer CLI
composition points. Further extraction can replace those adapters without
changing application service contracts.

## Ledger compatibility retained from the core slice

- Cash/loan/lend balances use the nested
  `account -> currency -> numeric balance` snapshot shape.
- Security and crypto accounts require nonempty `base_currencies`.
- Security ledger CSV uses the unified 12-column swap schema.
- Cash/loan/lend records use monthly `YYYY-MM.csv` files; legacy daily files are
  rejected.
