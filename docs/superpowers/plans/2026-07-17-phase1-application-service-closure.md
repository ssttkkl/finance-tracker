# Phase 1 Application Service Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route every Finance Tracker CLI leaf command through reusable application services with DTOs and injected repository/connector ports, while preserving local behavior and avoiding further expansion of Local CSV persistence.

**Architecture:** Use focused ports-first services and a local composition root. Existing CSV/YAML/Git/parser/connector implementations become compatibility adapters; services never receive ledger paths or import local runtime modules. New application tests use in-memory fakes, while the existing suite remains the compatibility contract.

**Tech Stack:** Python 3.11+, frozen dataclasses, `typing.Protocol`, `Decimal`, argparse, pytest 9, existing CSV/YAML/Git adapters.

## Global Constraints

- Preserve all current CLI command names, flags, exit behavior, and finance rules.
- Do not add new responsibilities to `LocalCsvUnitOfWork`.
- Application and domain tests must not touch `Path.home()`, `~/.ft`, Git, network, or terminal I/O.
- File parsers and API clients are adapters; CSV output is a CLI/export adapter.
- All application failures expose stable machine-readable domain error codes.
- Write a focused failing test and observe the expected failure before each production change.

---

### Task 1: Shared ports, DTOs, fakes, and composition root

**Files:**
- Create: `src/ft/domain/application.py`
- Create: `src/ft/repositories/queries.py`
- Create: `src/ft/connectors.py`
- Create: `src/ft/runtime.py`
- Create: `tests/fakes.py`
- Test: `tests/test_application_boundaries.py`

**Interfaces:**
- Produces: frozen `OperationResult`, `TextFinding`, `ExportPayload`, and `ChangeSetStatusDTO`.
- Produces: focused query, import, verification, investment, review, change-set, secret, mapping, market-data, and connector protocols.
- Produces: `build_local_services(ledger_root) -> ServiceBundle` as the only CLI composition entry point.

- [ ] Write import-boundary tests that block `Path.home()` and assert protocols are runtime-checkable.
- [ ] Run `uv run pytest tests/test_application_boundaries.py -q`; expect failures for missing modules.
- [ ] Add the DTOs, protocols, fakes, and composition skeleton with no local behavior.
- [ ] Re-run the focused test; expect all Task 1 tests to pass.
- [ ] Run `uv run pytest -q` and commit the focused change.

### Task 2: Finance queries and account balances

**Files:**
- Create: `src/ft/domain/queries.py`
- Create: `src/ft/application/queries.py`
- Create: `src/ft/adapters/local_query.py`
- Create: `src/ft/adapters/market_data.py`
- Modify: `src/ft/acct.py`
- Modify: `src/ft/report.py`
- Modify: `src/ft/cli.py`
- Test: `tests/test_application_queries.py`

**Interfaces:**
- Consumes: `AccountQueryRepository`, `TransactionQueryRepository`, `SnapshotQueryRepository`, and `MarketDataProvider`.
- Produces: `list_accounts() -> AccountListDTO`, `report(month) -> FinanceReportDTO`, and `list_transactions(query) -> TransactionPageDTO`.

- [ ] Write fake-backed tests for cash and valued-investment account balances, reports, filters, limits, and no-market-price fallback.
- [ ] Run the focused tests; expect missing `FinanceQueryService` failures.
- [ ] Implement query calculations in the service and focused local read adapters.
- [ ] Route `acct list`, `report`, and transaction `list` through `FinanceQueryService`; leave rendering in CLI helpers.
- [ ] Run focused and full tests, then commit.

### Task 3: Cashflow conversion/import, verification, and change sets

**Files:**
- Create: `src/ft/domain/imports.py`
- Create: `src/ft/domain/verification.py`
- Create: `src/ft/application/imports.py`
- Create: `src/ft/application/verification.py`
- Create: `src/ft/application/change_sets.py`
- Create: `src/ft/adapters/local_import.py`
- Create: `src/ft/adapters/local_verification.py`
- Create: `src/ft/adapters/local_change_set.py`
- Create: `src/ft/adapters/export_csv.py`
- Modify: `src/ft/cli.py`
- Test: `tests/test_application_import_verification_changeset.py`

**Interfaces:**
- Produces: `CashflowImportService.convert(command)`, `append(command)`, `VerificationService.verify(fix=False)`, and `ChangeSetService.status/commit/reset`.

- [ ] Write fake-backed tests proving convert is read-only, append is atomic, verification rebuild occurs only with `fix`, and change-set reset does not own confirmation.
- [ ] Run tests and observe missing-service failures.
- [ ] Implement services and focused compatibility adapters; conversion returns `ExportPayload` and the export adapter writes CSV.
- [ ] Route `convert`, `append`, `verify`, `commit`, `status`, and confirmed `reset` through services.
- [ ] Run focused tests, existing append/convert/CLI/snapshot tests, and the full suite; commit.

### Task 4: Investment commands, import, and portfolio queries

**Files:**
- Create: `src/ft/domain/investment.py`
- Create: `src/ft/application/investment.py`
- Create: `src/ft/adapters/local_investment.py`
- Modify: `src/ft/cli.py`
- Test: `tests/test_application_investment.py`

**Interfaces:**
- Produces: `InvestmentService.buy/sell/swap/deposit/withdraw/dividend/checkin_ticker/checkin_cash/convert/append` and `PortfolioQueryService.get_portfolio`.

- [ ] Write fake-backed tests for each command DTO, Decimal preservation, atomic append, conversion export, and market-data fallback.
- [ ] Run tests and observe missing-service failures.
- [ ] Implement validation/orchestration and a focused local compatibility adapter.
- [ ] Route all `stock` commands except sync through the services and render DTOs in the CLI.
- [ ] Run focused tests, all stock tests, and the full suite; commit.

### Task 5: Connector sync and configuration ports

**Files:**
- Create: `src/ft/domain/sync.py`
- Create: `src/ft/application/sync.py`
- Create: `src/ft/adapters/exchange_connectors.py`
- Create: `src/ft/adapters/local_config.py`
- Modify: `src/ft/exchange_sync.py`
- Modify: `src/ft/polymarket_sync.py`
- Modify: `src/ft/cli.py`
- Test: `tests/test_application_sync.py`

**Interfaces:**
- Consumes: `ConnectorRegistry`, `ExternalConnector`, `SecretStore`, `MappingProvider`, and `InvestmentEventRepository`.
- Produces: `ConnectorSyncService.sync(command) -> SyncResultDTO` with fetched/new/skipped counts and optional export payload.

- [ ] Write fake-backed tests for every provider, credential injection, account-scoped idempotency, dry-run/output non-mutation, and connector errors.
- [ ] Run tests and observe missing-service failures.
- [ ] Implement the service and adapters, making connector functions accept injected credentials/storage instead of reading YAML/CSV internally.
- [ ] Route Polymarket, Kraken, OKX, Binance, Coinbase, and Bybit CLI leaves through the service.
- [ ] Run focused, exchange, Polymarket, stock, and full tests; commit.

### Task 6: Reconciliation state machine

**Files:**
- Create: `src/ft/domain/reconciliation.py`
- Replace: `src/ft/application/reconcile.py`
- Create: `src/ft/adapters/local_reconciliation.py`
- Modify: `src/ft/reconcile.py`
- Modify: `src/ft/cli.py`
- Test: `tests/test_application_reconciliation.py`

**Interfaces:**
- Produces: `ReconcileService.start`, `continue_with_decisions`, and `abort`, all returning `ReconcileResultDTO`.
- Consumes: `ReconciliationRepository` whose methods have no ledger-root argument.

- [ ] Write fake-backed state transition tests including invalid continue/abort and rollback.
- [ ] Run tests and observe missing methods/state failures.
- [ ] Implement the application state machine and focused local adapter around existing reconciliation mechanics.
- [ ] Route all three CLI reconciliation leaves through the service.
- [ ] Run focused, pending, locked, reconciliation, CLI, and full tests; commit.

### Task 7: Structural audit, documentation, and Phase 1 acceptance

**Files:**
- Modify: `docs/phase1-application-services.md`
- Create: `tests/test_cli_application_boundary.py`

**Interfaces:**
- Produces: a 36-command evidence matrix and automated import/call boundary checks.

- [ ] Add an AST-based test that fails when `ft.cli` directly imports or calls legacy business entry points or when application modules import local/runtime dependencies.
- [ ] Run it and fix every reported bypass.
- [ ] Update the implementation record from “core slice” to complete closure only after the matrix has evidence for every leaf.
- [ ] Run `uv run pytest -q`, compile/import checks, and the boundary audit from a clean environment.
- [ ] Review the objective requirement by requirement, inspect `git diff`, and commit only after every item is proven.
