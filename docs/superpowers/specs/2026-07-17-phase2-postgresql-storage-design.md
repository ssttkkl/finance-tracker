# Phase 2 PostgreSQL Storage Design

## Scope

Phase 2 adds a PostgreSQL persistence adapter without changing the local CSV/YAML ledger behavior. A workspace is bound when an adapter or unit of work is constructed; callers never pass a workspace identifier to individual repository methods. This makes accidental cross-workspace reads difficult and keeps the Phase 1 application-service interfaces unchanged.

The first database model intentionally mirrors the existing domain contracts. It stores normalized account and cash transaction columns, investment event payloads, the current snapshot projection, import provenance, immutable raw records, and append-only revisions. Later phases can normalize investment instruments and wealth projections without blocking migration.

## Architecture

`ft.adapters.postgres` owns SQLAlchemy models, repository implementations, query adapters, and the database unit of work. PostgreSQL is the production target; repository contract tests use SQLite so they remain fast and deterministic. Alembic owns production schema evolution.

`ft.application.migration` orchestrates local-ledger inspection, import, comparison, and export. It receives local readers and a workspace-bound database gateway. Import is idempotent by stable source identities. Verification compares canonical accounts, cash transactions, investment events, snapshots, cashflow summaries, balances, and portfolio projections. Differences are returned as structured findings and are never silently repaired.

`ft.config` resolves `storage.backend=local|postgres`. Local remains the default. PostgreSQL requires a database URL and workspace ID. Runtime composition selects one backend; a workspace has only one active fact store, so this phase does not introduce dual writes.

## Data model

- `workspaces`: immutable workspace identity and display name.
- `accounts`: workspace-scoped accounts with stable UUIDs and a uniqueness constraint on `(workspace_id, name, currency)`.
- `cash_transactions`: normalized ledger fields plus source identity and revision number.
- `investment_events`: normalized routing fields plus the complete event payload.
- `ledger_snapshots`: one current projection per workspace.
- `import_batches`: an idempotent migration/import operation and its status.
- `raw_files`: source metadata and content digest; file bytes stay outside the database for now.
- `raw_records`: immutable source rows linked to a batch and optional raw file.
- `record_revisions`: append-only before/after changes for imported facts.

Every fact table contains `workspace_id` and every repository query includes it. Foreign keys also include or point through workspace-owned rows where practical. Timestamps are timezone-aware. Money is stored as fixed precision numeric or lossless strings inside JSON payloads.

## Migration flow

1. `inspect` reads accounts, monthly cash ledgers, daily investment ledgers, and snapshot metadata without writing.
2. `import` creates/binds the workspace, persists accounts and immutable raw rows, then writes cash and investment facts in one database transaction.
3. The database snapshot projection is loaded from the local snapshot only as a comparison candidate; verification still compares facts and projections explicitly.
4. `verify` reads both adapters and emits a machine-readable report with counts, canonical hashes, and field-level mismatches.
5. `export` writes deterministic CSV/JSON-compatible output from PostgreSQL.
6. Only a clean verification report permits an operator to change `storage.backend` to `postgres`; the command itself does not edit user configuration automatically.

## Failure handling

All migration writes are transactional. An exception rolls back the whole batch. Re-running the same source digest and workspace returns the existing completed batch rather than duplicating facts. Workspace lookup failures and configuration errors use explicit exceptions. Verification reports unexplained differences and exits non-zero in the CLI.

## Testing

- Run one repository contract against local CSV and SQLAlchemy adapters.
- Prove two workspace-bound adapters cannot see each other's accounts, facts, raw records, or snapshots.
- Exercise rollback and idempotent import behavior.
- Import a representative fixture and compare balances, cashflow summary, investment events, portfolio snapshot, and net-worth projection.
- Run Alembic upgrade/downgrade smoke tests and the existing full CLI suite.

