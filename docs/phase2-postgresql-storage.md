# Phase 2 PostgreSQL Storage

## Status

Phase 2 is complete. Finance Tracker now has a workspace-scoped PostgreSQL
storage adapter, production migrations, local-ledger migration commands,
shadow comparison, import provenance, and explicit backend configuration.

Local CSV/YAML remains the default and existing local CLI behavior is retained.
Phase 2 does not introduce dual writes or silently switch a workspace to
PostgreSQL. Operators inspect, import, verify, and then explicitly select the
database backend.

## Delivered architecture

The implementation extends the Phase 1 application boundaries instead of
copying business rules into a database-specific service:

- `ft.adapters.postgres.models` owns the SQLAlchemy persistence model.
- `ft.adapters.postgres.repositories` implements account, cashflow, investment,
  and snapshot persistence.
- `ft.adapters.postgres.imports` stores import batches, source records, and
  append-only revisions.
- `ft.adapters.postgres.uow` binds one transaction and one workspace to all
  repositories.
- `ft.adapters.postgres.queries` supplies database-backed account,
  transaction, snapshot, and portfolio reads.
- `ft.adapters.postgres.runtime` composes PostgreSQL services without changing
  Phase 1 application-service contracts.
- `ft.application.migration` owns inspect/import/verify/export orchestration and
  storage-independent comparison rules.
- `ft.adapters.local_migration` and `ft.adapters.postgres.migration` translate
  the two storage formats at the edges.

```text
local CSV/YAML
    -- inspect --> LedgerData
    -- import  --> PostgresUnitOfWork(workspace_id)
    -- verify  --> 8 projection comparisons
    -- export  --> deterministic CSV/YAML
```

The PostgreSQL adapter is bound to a workspace when constructed. Individual
repository methods do not accept a caller-supplied workspace override.

## Schema evidence matrix

Alembic owns the production schema through two revisions:

| Revision | Purpose |
|---|---|
| `20260717_01` | Workspace, account, cash transaction, investment event, and ledger snapshot storage |
| `20260717_02` | Import batches, raw file metadata, immutable raw records, and append-only revisions |

The current schema contains nine Phase 2 tables:

| Table | Workspace scope | Purpose and key constraints |
|---|:---:|---|
| `workspaces` | identity | Stable workspace boundary and display name |
| `accounts` | yes | Stable UUID, `(workspace_id, name, currency)` uniqueness, account metadata |
| `cash_transactions` | yes | Normalized cash ledger fields, exact amount, source identity, revision number |
| `investment_events` | yes | Event routing fields plus the complete event payload |
| `ledger_snapshots` | yes | One versioned current projection per workspace |
| `import_batches` | yes | Unique `(workspace_id, source_kind, source_digest)` migration run |
| `raw_files` | yes | Source path, media type, size, and content digest; file bytes remain outside PostgreSQL |
| `raw_records` | yes | Immutable source payload and stable source identity linked to a batch/file |
| `record_revisions` | yes | Append-only before/after record changes with actor and reason |

All fact queries include `workspace_id`. Workspace-owned rows reference the
workspace with cascading cleanup. Timestamps are timezone-aware.

Ledger amounts use `ExactDecimal`: PostgreSQL stores them as
`NUMERIC(38, 18)`. SQLite contract tests use lossless text because SQLite's
numeric affinity would otherwise round through binary floating point. The
live PostgreSQL test confirms the production column is actually
`numeric(38,18)`.

## Repository and transaction evidence

| Concern | PostgreSQL implementation | Verified behavior |
|---|---|---|
| Accounts | `PostgresAccountRepository` | CRUD contract and workspace-scoped uniqueness |
| Cash transactions | `PostgresCashflowRepository` | Decimal-safe writes and account-type routing |
| Investment events | `PostgresInvestmentRepository` | Security/crypto event payload preservation |
| Snapshot | `PostgresSnapshotRepository` | Versioned load/save and Decimal-safe balance updates |
| Import provenance | `PostgresImportRepository` | Digest-based batch identity, raw record immutability, append-only revisions |
| Transaction boundary | `PostgresUnitOfWork` | Account, cashflow, investment, snapshot, and provenance writes commit or roll back together |
| Workspace guard | `PostgresUnitOfWork.__enter__` | Unknown workspaces are rejected before repositories become available |

`ensure_workspace` is an explicit provisioning operation. The migration import
command uses it because `--workspace` authorizes creation of that migration
target. Normal repository access never creates a missing workspace implicitly.

## Migration command evidence matrix

| # | CLI leaf | Application operation | Result |
|---:|---|---|---|
| 1 | `migrate inspect` | `MigrationService.inspect` | Counts accounts, cash transactions, investment events, raw files, and computes a source digest |
| 2 | `migrate import` | `MigrationService.import_ledger` | Creates/binds the workspace and imports all facts and provenance in one transaction |
| 3 | `migrate verify` | `MigrationService.verify` | Produces machine-readable checks and exits nonzero when any projection differs |
| 4 | `migrate export` | `MigrationService.export` | Writes deterministic accounts, snapshot, monthly cash CSV, and daily investment CSV files |

Example local-to-PostgreSQL cutover preparation:

```bash
uv run ft migrate inspect \
  --from ~/.ft

uv run ft migrate import \
  --from ~/.ft \
  --database-url postgresql+psycopg://localhost/finance_tracker_dev \
  --workspace personal

uv run ft migrate verify \
  --from ~/.ft \
  --database-url postgresql+psycopg://localhost/finance_tracker_dev \
  --workspace personal

uv run ft migrate export \
  --to /tmp/finance-tracker-export \
  --database-url postgresql+psycopg://localhost/finance_tracker_dev \
  --workspace personal
```

Import is idempotent: repeating the same source digest for the same workspace
returns the completed batch instead of duplicating facts. “Idempotent” here
means repeated execution produces no additional writes after the first
successful import.

The source digest covers relative file paths and file bytes. A failed import
rolls back the batch and all facts. A changed ledger produces a new digest;
Phase 2 migration is a cutover workflow, not an indefinite incremental
synchronizer. The target workspace must therefore be empty for a new digest.
If the source changes before cutover, rebuild a fresh migration workspace or
empty test database and run import/verify again instead of layering the new
ledger over previously imported facts.

## Shadow comparison

`MigrationService.verify` compares the local and PostgreSQL adapters through
eight named projections:

| Check | Compared result |
|---|---|
| `accounts` | Name, type, currency, and active state |
| `cash_transactions` | Canonical cash facts with normalized Decimal amounts |
| `investment_events` | Canonical investment event payloads |
| `snapshot` | Recursive snapshot structure and numeric values |
| `account_balances` | Cash, loan, and lend balances by account/currency |
| `cashflow_summary` | Income and expense totals by currency |
| `portfolio` | Position quantity, cost, and cost currency |
| `net_worth_projection` | Cash balances plus portfolio cost grouped by currency |

A mismatch creates a `MigrationFinding` containing the component, expected
local value, and actual PostgreSQL value. Verification never repairs or hides a
difference. A clean report is evidence for cutover; it does not change backend
configuration automatically.

## Backend configuration and runtime

`StorageSettings` accepts YAML or environment variables. Local remains the
default when no storage configuration is supplied.

```yaml
storage:
  backend: postgres
  database_url: postgresql+psycopg://localhost/finance_tracker_dev
  workspace_id: personal
```

Equivalent environment configuration:

```bash
export FT_STORAGE_BACKEND=postgres
export FT_DATABASE_URL=postgresql+psycopg://localhost/finance_tracker_dev
export FT_WORKSPACE_ID=personal
```

Environment variables override YAML. PostgreSQL configuration requires both
the database URL and workspace ID. Importing `ft.config` or `ft.runtime` does
not resolve the user's home directory or import filesystem adapters.

`ft.runtime.build_services(settings)` selects the local or PostgreSQL
composition root. The PostgreSQL bundle currently exposes finance queries,
portfolio queries, account writes, manual cashflow writes, transfers, and its
unit of work.

Existing ordinary CLI commands still use the local composition path in Phase
2. Only `ft migrate` explicitly targets PostgreSQL. Wiring the read-only Web/API
to the PostgreSQL composition root belongs to Phase 3; database-backed
connector sync, reconciliation UI, and full Web writes belong to later phases.

Before importing, the target database schema must be upgraded to Alembic head
`20260717_02`. Application startup and `ft migrate` deliberately do not create
or mutate production schema automatically.

## Testing and enforcement

| Test file | Evidence |
|---|---|
| `tests/test_postgres_adapter.py` | Phase 1 service compatibility, Decimal snapshot persistence, rollback, unknown workspace rejection, cross-workspace isolation |
| `tests/test_alembic_migration.py` | Upgrade/downgrade path, required tables, PostgreSQL NUMERIC compilation |
| `tests/test_postgres_import_provenance.py` | Batch and raw-record idempotency, immutability, revisions, rollback, workspace isolation |
| `tests/test_storage_migration.py` | Inspect/import/verify/export, eight shadow checks, mismatch reporting, export round trip |
| `tests/test_storage_configuration.py` | Local default, YAML/environment precedence, configuration validation, runtime selection |
| `tests/test_migrate_cli.py` | End-to-end migration CLI, repeated import, verify, export |
| `tests/test_postgres_live.py` | Real PostgreSQL Alembic lifecycle, application writes, exact Decimal values, workspace isolation, migration idempotency, provenance, shadow comparison |

The live test accepts only `FT_TEST_POSTGRES_URL` databases whose names end in
`_test`. It runs Alembic from `base` to `head`, executes the integration
contract, and downgrades to `base` during cleanup. This guard prevents the test
from targeting a normal development or production database by mistake.

```bash
FT_TEST_POSTGRES_URL=postgresql+psycopg://localhost/finance_tracker_phase2_test \
  uv run pytest tests/test_postgres_live.py -q
```

Verified on PostgreSQL 17.10:

- live integration test: `1 passed`;
- full suite with live PostgreSQL enabled: `799 passed, 1 skipped`;
- actual database amount column: `numeric(38,18)`;
- Alembic head: `20260717_02`.

## Compatibility and Phase 3 handoff

- Local-only users keep CSV/YAML/Git as their sole fact store.
- A workspace has one selected fact store; Phase 2 does not add dual writes.
- Raw file metadata and content digests are stored in PostgreSQL, while source
  file bytes remain in local/object storage scope.
- The imported snapshot is a comparison projection. Raw records and formal
  facts remain independently verifiable.
- The original local Git history remains an archive and is not converted into
  database revisions commit by commit.
- Phase 3 can build read-only FastAPI/Web paths against
  `build_services(StorageSettings(...))` without shelling out to the CLI or
  duplicating financial rules.
