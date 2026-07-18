# CLI Contract

## Supported Behavior

All current runtime commands use the database chosen by `FT_DATABASE_URL` and the workspace chosen by
`FT_WORKSPACE_ID`. The command grammar and output data do not branch by database.

Storage-dependent command groups covered by the shared matrix are:

- account create/list/rename/activate/deactivate/delete rejection;
- manual cash transaction, balance check-in, and transfer;
- transaction list and monthly report;
- investment deposit/withdraw/buy/sell/swap/dividend/check-in/list;
- direct statement import, duplicate import, and failed import rollback.

`convert` and `stock convert` remain explicit file-to-file preview/export operations. They do not
select, provision, migrate, or synchronize a runtime database and must not be presented as a
cross-database migration facility.

## Invocation

PostgreSQL and SQLite use the same commands:

```bash
export FT_DATABASE_URL='<one supported URL>'
export FT_WORKSPACE_ID='default'
uv run alembic upgrade head
uv run ft acct list
uv run ft report --month 2026-07
```

Schema migration and workspace provisioning remain explicit operator steps. Ordinary CLI commands do
not create tables or workspaces.

## Exit and Output Contract

| Outcome | Exit | Output requirement |
|---|---:|---|
| Successful command | 0 | Same normalized business data for both backends |
| Business rejection | nonzero | Same domain error/message contract and unchanged storage state |
| Storage config/connect/schema/workspace/readonly/busy failure | nonzero | Stable storage category, actionable controlled message, sanitized connection summary |
| Existing SQLite file permission warning with otherwise valid runtime | 0 when command succeeds | Warning once per startup/composition, repair guidance, no full file path, no chmod |

CLI `--help` remains available without loading storage settings or touching the database. Help and
README state that PostgreSQL and file SQLite are supported, SQLite memory is test-only, and there is
no fallback, dual write, or implicit migration.

One outer CLI storage-error boundary covers service construction and command execution. It renders
controlled storage errors to stderr and exits `1`. Individual command handlers never print raw
SQLAlchemy/driver exceptions. Bundle notices are rendered once before normal command output.

## Equivalent Result Rules

Equivalent output means:

- exact decimal values and signs match;
- currency, account, category, counterparty, event, and date/month values match;
- deterministic list ordering and report totals match;
- successful import counts and duplicate flags match;
- generated internal IDs and database-native messages need not match and should not be printed as
  parity evidence.

## Failure Scenarios

The CLI contract matrix must exercise both backends for:

- missing schema and stale revision;
- unknown workspace;
- invalid amount/scale/currency and account mismatch;
- injected failure after a fact is staged but before projection/batch completion;
- repeated import/provider identity;
- database unavailable or path unusable;
- write attempt against read-only storage;
- concurrent projection update (both commit correctly, or SQLite returns `storage.busy` after its
  bounded wait without partial data).

SQLite-only operational tests additionally cover permissive file warnings, lock timeout duration,
WAL, and foreign-key enforcement. PostgreSQL-only operational tests cover real row-lock concurrency.

## Forbidden Behavior

CLI code and output must not:

- inspect a local ledger path to choose a backend;
- connect to both databases in one normal command;
- retry an Application Service automatically;
- copy, migrate, synchronize, compare, or reconcile PostgreSQL and SQLite data;
- restore CSV/YAML/Git as a runtime ledger;
- print a password, username/password URL, URL query, full SQLite path, or raw driver exception.
