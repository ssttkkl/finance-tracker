# Phase 1 Application Service Closure Design

## Status and intent

This design closes Phase 1 of `docs/productization-refactor-plan.md`. The
existing implementation is a completed core slice, not a completed Phase 1:
only account mutations, manual cashflow, balance check-in, transfers, and the
start reconciliation path currently enter application services.

The closure must preserve the current CLI and its 734-test behavior baseline
while making every leaf command enter an application service. Application and
domain tests must use in-memory fakes and must not depend on `~/.ft`, CSV, Git,
network clients, or terminal output.

## Considered approaches

### 1. Ports-first incremental extraction — selected

Introduce focused application services and DTOs, define ports for every
external dependency, and put the existing local implementations behind
compatibility adapters. Move orchestration and transaction decisions into the
services. Keep CSV/YAML/Git, import parsers, ccxt, Polymarket HTTP, market data,
and CSV rendering at the adapter boundary.

This approach preserves the characterization suite, gives Web/PostgreSQL work
stable contracts, and obeys the requirement not to expand
`LocalCsvUnitOfWork` into a permanent god adapter.

### 2. Expand `LocalCsvUnitOfWork`

Add report, import, verification, investment, connector, and Git methods to the
existing local UoW. This is faster initially but cements local storage semantics
into application contracts and directly contradicts the Phase 1 closure goal.

### 3. Rewrite all domain algorithms before routing the CLI

Rebuild convert, reconciliation, security replay, and sync around pure domain
objects in one change. This produces the cleanest end state but needlessly
risks mature refund, reconciliation, and investment behavior. Domain extraction
should continue behind the stable ports after Phase 1.

## Architecture

The CLI is a composition and rendering adapter. It parses arguments, obtains a
service bundle from one composition root, invokes exactly one application
service operation, renders its DTO, and maps structured errors to exit codes.
It does not load accounts, snapshots, CSV rows, credentials, mappings, market
prices, or Git state.

Application services depend only on protocols and domain DTOs:

- `FinanceQueryService` uses account, transaction, snapshot, and market-data
  ports and returns account balance, report, and transaction DTOs.
- `CashflowImportService` coordinates a cashflow importer, repository,
  snapshot/rebuild port, and change-set port. Conversion returns rows; a CLI
  export adapter writes requested CSV output.
- `VerificationService` coordinates cashflow and investment verification and
  optional rebuild, returning findings rather than printing them.
- `InvestmentService` records buy, sell, swap, deposit, withdraw, dividend,
  ticker check-in, cash check-in, converted imports, and append operations via
  an investment command repository.
- `PortfolioQueryService` combines position projections with injected market
  data and returns a portfolio DTO.
- `ConnectorSyncService` resolves a connector, reads secrets and mapping via
  explicit ports, fetches normalized events, performs repository-backed
  idempotency, and either previews/exports or commits the events.
- `ReconciliationService` exposes `start`, `continue_with_decisions`, and
  `abort`. The service owns valid state transitions; a review repository stores
  session state and decisions.
- `ChangeSetService` exposes `status`, `commit`, and `reset`; confirmation
  remains a CLI concern, while semantics and results are stable DTOs.

`LocalRuntime` is the local composition root. Its adapters may call proven
legacy functions temporarily, but application code never receives a ledger
path and never imports `ft.models`, `ft.snapshot`, `ft.stock`, `ft.report`,
`ft.convert`, `ft.reconcile`, connector SDKs, or terminal modules. Compatibility
adapters receive an explicit ledger root and isolate global-module patching
where legacy code has not yet accepted dependency injection.

## Port boundaries

Repository protocols describe domain persistence and query capabilities, not
file layouts. Connector and importer protocols describe external data sources.

- Repositories: `AccountRepository`, `CashflowRepository`,
  `InvestmentRepository`, `SnapshotRepository`, `ReviewRepository`,
  `ChangeSetRepository`, and query-specific read repositories.
- Connectors: `CashflowImporter`, `InvestmentImporter`, `ExternalConnector`,
  `ConnectorRegistry`, and `MarketDataProvider`.
- Configuration: `SecretStore` and `MappingProvider`. Local YAML is one adapter;
  it is not referenced by application services.
- Export: CSV serialization and file writes are CLI/export adapters. Services
  return normalized rows and summaries.

No new use case is added to `LocalCsvUnitOfWork`. New local compatibility
adapters are separate, focused classes so PostgreSQL and remote connector
implementations can replace them independently.

## Data flow and transactions

For writes, a service validates the command DTO, opens the injected transaction
or repository operation, applies the complete batch, rebuilds projections if
required, stages through the injected change-set port when local mode requests
it, and commits once. Failures roll back and return or raise a structured domain
error. Conversion, dry-run sync, report, list, status, and portfolio queries are
read-only.

Sync idempotency is keyed by connector, connector account, destination account,
and external event ID. Output-only and dry-run modes never mutate repositories
or change sets.

Reconciliation transitions are:

```text
idle --start(needs_review)--> awaiting_decisions
idle --start(no_review)-----> completed
awaiting_decisions --continue--> completed
awaiting_decisions --abort-----> aborted
```

Invalid transitions return `reconciliation.invalid_state` without mutation.

## DTO and error rules

- Money-like values cross application boundaries as `Decimal` or decimal
  strings, never binary floats.
- DTOs are frozen dataclasses and contain no `Path` tied to a local ledger.
  Export payloads may carry a suggested filename as text.
- Results include machine-readable status/counts/findings; human CLI copy is
  rendered outside the application layer.
- Domain failures use stable error codes such as `import.invalid_row`,
  `investment.invalid_command`, `connector.unknown`,
  `verification.inconsistent`, and `changeset.nothing_to_commit`.

## Testing and completion evidence

Each service is developed red-green-refactor using hand-written fake
repositories/connectors. Fake tests prove orchestration, rollback, dry-run,
idempotency, state transitions, and DTOs without a home directory. Adapter
contract and existing characterization tests protect local compatibility.

Completion requires all of the following evidence:

1. Every independently callable leaf command routes through a service.
2. No CLI handler imports or invokes legacy business functions directly.
3. Application modules have no local path, CSV, YAML, Git, network, market SDK,
   `print`, or `input` dependency.
4. Credentials, mappings, and market prices are accessed only through ports.
5. New service tests use fakes; local adapter tests remain integration tests.
6. The complete pytest suite and static import-boundary audit pass.
7. Phase 1 documentation names the full closure accurately and includes the
   command-by-command evidence matrix.
