# Quickstart: Validate Both Runtime Databases

This guide validates the implemented feature. It is not a cross-database migration procedure. Use
fresh disposable databases and the same workspace/business inputs on each backend.

## Prerequisites

- Python 3.11+ and `uv`
- a local writable directory for SQLite
- a reachable disposable PostgreSQL database whose name ends in `_test`
- repository dependencies installed with `uv sync`

Set the PostgreSQL test URL without embedding it in repository files:

```bash
export FT_TEST_POSTGRES_URL='postgresql+psycopg://localhost/finance_tracker_test'
```

## SQLite Runtime

```bash
tmp_dir="$(mktemp -d)"
export FT_DATABASE_URL="sqlite+pysqlite:///$tmp_dir/finance-tracker.db"
export FT_WORKSPACE_ID='parity-workspace'
uv run alembic upgrade head
uv run python -c "import os; from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace; e=create_relational_engine(os.environ['FT_DATABASE_URL']); ensure_workspace(create_session_factory(e), os.environ['FT_WORKSPACE_ID']); e.dispose()"
uv run ft acct add Cash --type cash --currency CNY
uv run ft add --amount -12.50 --counterparty Coffee --account Cash --currency CNY --date '2026-07-17 09:00:00'
uv run ft report --month 2026-07
```

Expected: migration reaches the single head, the workspace is provisioned, commands succeed, and the
report includes the exact `-12.50` transaction semantics. The database is file-backed; no other
backend is contacted.

Inspect SQLite operating invariants:

```bash
uv run python -c "import os; from ft.adapters.relational import create_relational_engine; e=create_relational_engine(os.environ['FT_DATABASE_URL']); c=e.connect(); print(c.exec_driver_sql('PRAGMA foreign_keys').scalar(), c.exec_driver_sql('PRAGMA journal_mode').scalar(), c.exec_driver_sql('PRAGMA busy_timeout').scalar()); c.close(); e.dispose()"
```

Expected values: foreign keys `1`, journal mode `wal`, busy timeout approximately `5000`.

## PostgreSQL Runtime

The following destroys and recreates schema only in the dedicated `_test` database.

```bash
export FT_DATABASE_URL="$FT_TEST_POSTGRES_URL"
export FT_WORKSPACE_ID='parity-workspace'
uv run alembic downgrade base
uv run alembic upgrade head
uv run python -c "import os; from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace; e=create_relational_engine(os.environ['FT_DATABASE_URL']); ensure_workspace(create_session_factory(e), os.environ['FT_WORKSPACE_ID']); e.dispose()"
uv run ft acct add Cash --type cash --currency CNY
uv run ft add --amount -12.50 --counterparty Coffee --account Cash --currency CNY --date '2026-07-17 09:00:00'
uv run ft report --month 2026-07
```

Expected: the same normalized account, transaction, and report result as SQLite. UUIDs and physical
storage details may differ.

## Shared Contract Matrix

Run the repository's shared matrix with both backends enabled:

```bash
FT_REQUIRE_TEST_POSTGRES=1 FT_TEST_POSTGRES_URL="$FT_TEST_POSTGRES_URL" uv run pytest \
  tests/test_relational_contract.py \
  tests/test_relational_statement_import.py \
  tests/test_relational_live.py \
  tests/test_alembic_migration.py
```

Expected: both backend parameters pass with no unexplained skip. A missing/unreachable PostgreSQL URL
is not acceptable evidence for feature completion.

## Failure and Security Checks

Run focused configuration, runtime, lock, permission, and redaction tests:

```bash
FT_REQUIRE_TEST_POSTGRES=1 FT_TEST_POSTGRES_URL="$FT_TEST_POSTGRES_URL" uv run pytest \
  tests/test_storage_configuration.py \
  tests/test_relational_runtime.py \
  tests/test_cli.py
```

Expected: unsupported/memory runtime URLs fail closed; missing schema and workspace errors are
actionable; SQLite lock contention waits for the configured bound then returns `storage.busy`; a
permissive existing SQLite file warns without mutation; captured output contains no password, query
parameter, or full SQLite path.

## Full Verification

```bash
FT_REQUIRE_TEST_POSTGRES=1 FT_TEST_POSTGRES_URL="$FT_TEST_POSTGRES_URL" uv run pytest
uv run alembic heads
uv build
git diff --check
git status --short
```

Completion requires a passing real PostgreSQL run, a passing file SQLite run, one Alembic head, a
successful build, a clean diff check, and only intentional files in status. Run gstack `review` and
`$speckit-converge` according to `AGENTS.md` before declaring the feature complete.

## Cleanup

Remove only the disposable SQLite directory you created and reset the dedicated PostgreSQL test
schema. Never point cleanup commands at a production or non-`_test` database.
