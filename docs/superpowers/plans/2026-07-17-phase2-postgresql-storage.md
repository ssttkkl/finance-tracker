# Phase 2 PostgreSQL Storage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a workspace-isolated PostgreSQL storage adapter, provenance model, local migration workflow, shadow comparison, and explicit backend selection while preserving local behavior.

**Architecture:** SQLAlchemy models and workspace-bound repositories implement the Phase 1 ports. Alembic manages the production schema. A storage-independent migration service copies and compares local facts without dual writing.

**Tech Stack:** Python 3.11+, SQLAlchemy 2.x, Alembic, psycopg 3, pytest, SQLite contract tests, PostgreSQL production schema.

## Global Constraints

- Local CSV/YAML remains the default and retains existing behavior.
- A repository instance is bound to exactly one workspace.
- No query accepts an arbitrary workspace override.
- Database writes use one transaction and preserve Decimal precision.
- Imports are idempotent and revisions are append-only.
- No automatic long-term dual write or automatic backend cutover.

---

### Task 1: Schema and workspace-bound repositories

**Files:**
- Create: `src/ft/adapters/postgres/models.py`
- Create: `src/ft/adapters/postgres/repositories.py`
- Create: `src/ft/adapters/postgres/uow.py`
- Create: `src/ft/adapters/postgres/__init__.py`
- Modify: `pyproject.toml`
- Test: `tests/test_postgres_adapter.py`

**Interfaces:**
- Produces: `create_schema(engine)`, `PostgresUnitOfWork(session_factory, workspace_id)`, and workspace-bound account/cashflow/investment/snapshot repositories implementing existing protocols.

- [ ] Write contract tests for account CRUD, cash/investment writes, snapshot persistence, rollback, and workspace isolation.
- [ ] Run `uv run pytest tests/test_postgres_adapter.py -q` and confirm missing-module failures.
- [ ] Add SQLAlchemy models, repositories, and unit of work with fixed workspace predicates.
- [ ] Re-run the focused tests and then the Phase 1 application-service tests.
- [ ] Commit the independently passing schema/adapter increment.

### Task 2: Alembic production migration

**Files:**
- Create: `alembic.ini`
- Create: `migrations/env.py`
- Create: `migrations/script.py.mako`
- Create: `migrations/versions/20260717_01_phase2_storage.py`
- Test: `tests/test_alembic_migration.py`

**Interfaces:**
- Consumes: `ft.adapters.postgres.models.Base.metadata`.
- Produces: a complete upgrade/downgrade path for all Phase 2 tables and constraints.

- [ ] Write a migration smoke test against a temporary SQLite URL and a PostgreSQL SQL compilation assertion.
- [ ] Run it and confirm the missing Alembic configuration failure.
- [ ] Add Alembic configuration and the explicit initial revision.
- [ ] Run upgrade, inspect required tables/constraints, run downgrade, and re-run the test.
- [ ] Commit the migration increment.

### Task 3: Import provenance and immutable revisions

**Files:**
- Create: `src/ft/adapters/postgres/imports.py`
- Modify: `src/ft/adapters/postgres/models.py`
- Test: `tests/test_postgres_import_provenance.py`

**Interfaces:**
- Produces: `PostgresImportRepository.start_batch`, `add_raw_file`, `add_raw_records`, `append_revision`, and `complete_batch`.

- [ ] Write tests for source-digest idempotency, raw-record immutability, append-only revisions, rollback, and workspace isolation.
- [ ] Run tests and confirm missing repository behavior.
- [ ] Implement the minimal repository and database constraints.
- [ ] Run focused and adapter tests.
- [ ] Commit the provenance increment.

### Task 4: Local migration and shadow comparison

**Files:**
- Create: `src/ft/domain/migration.py`
- Create: `src/ft/application/migration.py`
- Create: `src/ft/adapters/local_migration.py`
- Create: `src/ft/adapters/postgres/migration.py`
- Test: `tests/test_storage_migration.py`

**Interfaces:**
- Produces: `MigrationService.inspect()`, `import_ledger()`, `verify()`, and `export()` returning structured DTOs and findings.

- [ ] Build a representative ledger fixture containing accounts, cash rows, investment rows, and a snapshot.
- [ ] Write failing inspect/import/idempotency/shadow-comparison/export tests.
- [ ] Implement deterministic local reading and canonical comparison.
- [ ] Implement one-transaction database import and deterministic export.
- [ ] Verify clean fixtures match and mutations produce explicit mismatches.
- [ ] Commit the migration-service increment.

### Task 5: Backend configuration and CLI composition

**Files:**
- Create: `src/ft/config.py`
- Create: `src/ft/adapters/postgres/runtime.py`
- Modify: `src/ft/runtime.py`
- Modify: `src/ft/cli.py`
- Test: `tests/test_storage_configuration.py`
- Test: `tests/test_migrate_cli.py`

**Interfaces:**
- Produces: `StorageSettings.load(...)`, `build_services(settings)`, and `ft migrate inspect|import|verify|export`.

- [ ] Write failing tests for local default, invalid backend, missing PostgreSQL settings, runtime selection, command exit codes, and JSON migration reports.
- [ ] Implement configuration parsing without resolving `Path.home()` at import time.
- [ ] Add PostgreSQL query/runtime adapters and migrate CLI handlers.
- [ ] Run focused CLI and application boundary tests.
- [ ] Commit the configuration/CLI increment.

### Task 6: Phase 2 acceptance verification

**Files:**
- Modify: `docs/productization-refactor-plan.md` only to record verified Phase 2 completion evidence.

**Interfaces:**
- Consumes all prior tasks; produces no new runtime API.

- [ ] Run all PostgreSQL adapter, Alembic, migration, and configuration tests.
- [ ] Run `uv run pytest -q` and require zero failures.
- [ ] Run a migration CLI smoke flow twice to prove idempotency.
- [ ] Inspect `git diff --check` and the final diff for secrets, absolute user paths, and accidental user-file changes.
- [ ] Record exact test evidence and remaining operational requirements (a live PostgreSQL integration environment) in the handoff.

