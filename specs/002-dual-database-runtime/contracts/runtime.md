# Runtime Contract

## Configuration

Runtime storage configuration consists only of:

- `FT_DATABASE_URL`: required PostgreSQL or file-backed SQLite URL;
- `FT_WORKSPACE_ID`: required existing workspace identifier.

Accepted examples:

```text
postgresql+psycopg://finance_user:secret@db.example/finance_tracker
sqlite+pysqlite:////absolute/path/to/finance-tracker.db
```

Rejected examples include absent/empty values, malformed URLs, MySQL/other dialects, SQLite memory
URLs in runtime configuration, SQLite URLs without a durable file, and any legacy storage selector.
Rejection occurs before engine creation or business work. The process never probes or connects to a
second backend.

## Composition

`build_services(settings)` returns the existing `ServiceBundle` surface:

```text
queries
investments
portfolio
accounts
cashflow
transfers
statement_import
uow
```

Both supported dialects use the same Application Service classes and relational repository/UoW
implementations. The selected dialect may not change which services exist or which CLI command calls
them.

The bundle also exposes `notices`, an immutable tuple of sanitized operational notices. It is empty
for normal PostgreSQL/SQLite startup and contains at most one combined SQLite permission notice.

## Startup Validation

Before returning the bundle, runtime validation must prove:

1. the selected database is reachable/openable;
2. SQLite connection invariants are active when selected;
3. all required logical tables and `alembic_version` exist;
4. the stored revision equals the expected single head;
5. `FT_WORKSPACE_ID` exists;
6. SQLite file permissions were inspected and any warning was captured for CLI output.

Validation never creates schema, provisions a workspace, copies another database, or falls back.

## SQLite Operating Contract

For file-backed SQLite:

- `PRAGMA foreign_keys` is `ON` for every connection;
- `PRAGMA journal_mode` is set and verified as `wal` once at engine initialization;
- driver timeout and `PRAGMA busy_timeout` are approximately 5,000 ms;
- each command UoW obtains a write reservation before reading workspace/projection state;
- lock timeout produces `storage.busy` and does not replay the service;
- read-only query transactions may proceed under WAL while another process owns the writer slot;
- a new database file is created owner-readable/writable only where the platform supports POSIX mode,
  including when Alembic is the first process to open it;
- permissive existing DB, `-wal`, or `-shm` files produce a warning without chmod or startup refusal.

SQLite files must be placed on a filesystem with reliable SQLite locking. Unsupported shared/network
filesystem behavior is an operator error and is not masked by another backend.

## PostgreSQL Operating Contract

For PostgreSQL:

- SQLAlchemy uses the psycopg driver and pre-ping behavior;
- command UoWs use ordinary transactions and existing row/workspace `FOR UPDATE` locks;
- unique constraint races use savepoints where required to preserve idempotent results;
- connection, transaction, schema, and workspace failures map to the same stable categories used by
  SQLite where semantically applicable.

PostgreSQL may admit more concurrent writers than SQLite. This throughput difference is not a
functional difference.

## Stable Errors

| Code | Trigger | Required action text | Forbidden content |
|---|---|---|---|
| `storage.config` | Missing/malformed/unsupported/non-file runtime URL | Correct `FT_DATABASE_URL` / `FT_WORKSPACE_ID` | Raw invalid URL if it may contain credentials/query/path |
| `storage.connect` | Unreachable PostgreSQL; missing/unwritable SQLite parent; open/connect failure | Verify selected database/path access | Password, URL query, full SQLite path, raw driver message |
| `storage.schema` | Missing tables/version or stale head | Run `uv run alembic upgrade head` for selected URL | Credentials/query/full SQLite path |
| `storage.workspace` | Workspace absent | Provision the named workspace | Connection secrets |
| `storage.readonly` | Write required but selected database is read-only | Correct database/file permissions | Full SQLite path or raw URL |
| `storage.busy` | SQLite writer wait exceeds bound | Retry the command later after competing writer finishes | Automatic retry claim, full path, raw SQL |

Errors carry a code and controlled message. CLI converts them to nonzero exit status. Raw SQLAlchemy
and driver exceptions may be chained for debugging but are not rendered or logged directly.

## Sanitized Connection Summary

- PostgreSQL: `postgresql` plus safe host/database labels if present. Never include username,
  password, query parameters, or reconstruct the full URL.
- SQLite: `sqlite` plus a short non-reversible identifier or basename class sufficient to distinguish
  the selected file in a single diagnostic. Never include directory components, full path, query
  parameters, or the complete URL.
- Invalid input: use a generic `database configuration` label rather than echoing the value.

The same summary is used consistently for startup, schema, permission, readonly, and busy messages.

## Transaction Contract

Application Service command boundaries remain one Unit of Work. A command either commits all of its
formal facts, provenance, revision, batch status, and snapshot changes or exposes none of them. On
exception, explicit rejection, lock failure, or commit failure, the UoW rolls back and closes.

There is no automatic service replay. Callers may choose to invoke a command again after a
`storage.busy` error; idempotency constraints then protect duplicate import/fact publication.

## Parity Comparison

Contract tests normalize away only:

- generated UUID values while preserving relationship shape;
- database-generated internal timing when a controlled clock is unavailable;
- physical type/storage representation;
- backend-native exception strings and query plans.

They must compare account identity, currencies, exact amounts, event kinds/payloads, source links,
revision before/after data, projection balances/positions/version behavior, report/list output,
error code, exit status, and transaction pre/post state.
