# Implementation Plan: PostgreSQL and SQLite Runtime Parity

**Branch**: `refactor/web` | **Date**: 2026-07-18 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/002-dual-database-runtime/spec.md`

## Summary

Make PostgreSQL and file-backed SQLite equal, explicitly selected runtime databases through
`FT_DATABASE_URL`. Refactor the misleading PostgreSQL-named SQLAlchemy implementation into one
neutral relational adapter, keep one model/repository/UoW/Application Service path, and isolate
dialect behavior in a small database policy responsible for URL validation, engine setup,
transaction acquisition, physical schema types, safe diagnostics, and error normalization.

SQLite command transactions acquire a write reservation before reading workspace state, use WAL,
foreign keys, and a 5,000 ms busy timeout, and return a stable busy error rather than replaying an
Application Service. PostgreSQL retains row-level locking and higher write concurrency. One
storage-dependent contract suite runs against a file SQLite database and a dedicated real
PostgreSQL database; normalized business and audit results must match.

## Technical Context

**Language/Version**: Python 3.11+

**Primary Dependencies**: SQLAlchemy 2.0.51, Alembic 1.18.5, psycopg 3.3.4, Python `sqlite3`

**Storage**: PostgreSQL and file-backed SQLite, selected only by `FT_DATABASE_URL`, with one logical
schema and Alembic head

**Testing**: pytest 9.0.3; shared backend fixture/contract matrix; dedicated migration, locking,
permission, redaction, CLI, and real PostgreSQL integration tests

**Target Platform**: Local or server-side Python CLI on platforms supported by Python, SQLAlchemy,
PostgreSQL, and SQLite; SQLite runtime files must reside on a filesystem with reliable local locks

**Project Type**: Single Python CLI/application package

**Performance Goals**: Preserve current CLI behavior; SQLite write contention waits no more than
approximately 5 seconds before a stable busy failure; no throughput-equivalence promise

**Constraints**: Exact decimal values with at most 18 fractional digits and no binary-float path;
UTC persistence with Asia/Shanghai input/bucketing semantics; atomic facts/audit/projection writes;
one backend connection graph per process; no fallback, dual write, shadow comparison, automatic
replay, or implicit cross-backend migration

**Scale/Scope**: Current accounts, cashflow, transfer, investment, query/report, conversion, and
statement-import CLI/Application Service surface; nine logical tables plus `alembic_version`; no new
Web, Worker, MCP, analytics, or data-transfer capability

## Constitution Check

*GATE: Passed before Phase 0 research and re-checked after Phase 1 design.*

| Principle / gate | Design evidence | Status |
|---|---|---|
| I. Financial correctness and auditability | `ExactDecimal` uses PostgreSQL `NUMERIC(38,18)` and SQLite canonical decimal text; shared parity tests compare facts, revisions, source links, and projections; transactions roll back as a unit | PASS |
| II. Spec-driven work | `spec.md`, this plan, research, data model, contracts, quickstart, then generated `tasks.md` remain the only feature truth source | PASS |
| III. Test-first and verification evidence | Tasks must add failing tests before implementation; the same storage contract suite must pass on file SQLite and real PostgreSQL; full pytest, build, migration head, and diff checks are required | PASS |
| IV. Explicit selection and parity | Only `FT_DATABASE_URL` selects exactly one supported dialect; one relational adapter and Application Service graph serve both; no fallback, dual write, shadow mode, or cross-backend migration exists | PASS |
| V. Clear boundaries and minimum complexity | Dialect policy contains unavoidable engine/locking/type/error differences; domain and Application Services remain unchanged; no second repository hierarchy or framework is added | PASS |

### PostgreSQL / SQLite Parity Matrix

| Concern | Shared contract | PostgreSQL implementation | SQLite implementation | Allowed difference / evidence |
|---|---|---|---|---|
| Selection | One valid `FT_DATABASE_URL` chooses one backend before business work | `postgresql` URL and psycopg engine | `sqlite` file URL; `:memory:` test-only | URL syntax and deployment differ; config tests prove unsupported/memory runtime URLs fail without fallback |
| Logical schema | Same tables, keys, workspace-scoped constraints, indexes, and Alembic head | Native constraints and `NUMERIC(38,18)` | Foreign keys enabled; exact decimals stored as canonical text | Physical decimal type differs deliberately; migration introspection and contract tests prove logical equivalence |
| Migrations | Same Alembic command and revision head from empty storage | Dedicated empty `_test` database | New temporary file | DDL rendering may differ; upgrade/head/downgrade run independently on both |
| Decimal / JSON / time | Same validation, canonical values, UTC restore, and Asia/Shanghai buckets | Native numeric, JSON, timezone-aware timestamp behavior | text decimal, SQLite JSON mapping, UTC-aware type decorator | Physical encodings differ; normalized DTO/fact comparisons must be identical |
| Workspace isolation | Every formal row and query is workspace-bound; unknown workspace is stable error | Row predicates and foreign keys | Same predicates and enabled foreign keys | No user-visible difference; cross-workspace contract matrix |
| Transactions / rollback | Facts, source records, revisions, projection, and batch status commit or roll back together | SQLAlchemy transaction | SQLAlchemy transaction beginning with a write reservation for command UoW | Lock acquisition differs; failure-injection tests compare pre/post state |
| Projection concurrency | No committed increment is lost | `SELECT ... FOR UPDATE` row/workspace locking | `BEGIN IMMEDIATE` serializes writers before the first state read | SQLite may wait then return busy; concurrency tests prove sum or stable busy, never lost update |
| Import idempotency | Same workspace/source/digest and source identity produce one batch/fact set | Unique constraints plus nested savepoint race handling | Writer serialization plus same unique constraints | Race mechanics differ; duplicate/sequential/concurrent import tests compare outcomes |
| Queries / ordering | Same filters, deterministic ordering, DTOs, report totals, and CLI rendering | Shared repositories and services | Shared repositories and services | Query planner and internal IDs may differ; normalized outputs exclude generated IDs |
| Errors | Stable category, actionable text, nonzero CLI status, sanitized connection summary | Driver errors mapped at adapter boundary | locked/busy/readonly/path errors mapped at adapter boundary | Raw driver text differs and is never the contract; error/redaction matrix asserts categories and absence of secrets |
| File security | N/A to server database files | PostgreSQL operator responsibility | New file owner-only where supported; warn on permissive existing DB/sidecar without mutation | SQLite-only operational concern; permission tests assert warning and sanitized path token |
| Operations / performance | Same financial and audit result | Higher concurrent write throughput and remote deployment | Local file, single-writer behavior, WAL sidecars | Throughput, topology, lock implementation, generated IDs, and DB-native messages are explicitly non-equivalent |

**Post-design re-check**: The completed research, data model, runtime/CLI contracts, and quickstart
cover every matrix row. No constitution exception or unresolved clarification remains; Complexity
Tracking is intentionally empty.

## Architecture

### Neutral relational adapter

Move `src/ft/adapters/postgres/` to `src/ft/adapters/relational/` and rename exported
`Postgres*` implementation types to `Relational*`. This is an internal, unpublished adapter surface;
tests and the composition root move to the neutral names in the same feature, without a long-lived
compatibility alias. The package continues to implement the existing repository protocols and feed
the existing Application Services.

The adapter has one model set, repository set, Unit of Work, session factory, runtime validator, and
composition root. A compact dialect policy provides only:

- supported URL classification and sanitized connection summary;
- engine options and connect hooks;
- command-transaction start behavior;
- dialect-aware physical exact-decimal migration type;
- permission inspection for SQLite database/sidecar files;
- mapping of connection, schema, workspace, readonly/path, and busy failures to stable runtime errors.

All engine-producing paths use this policy. `create_relational_engine(...)` is the sole production
engine factory used by the runtime composition root, Alembic online migrations, workspace
provisioning examples, and integration fixtures. Direct SQLAlchemy `create_engine(...)` remains
allowed only for isolated unit tests that explicitly request memory SQLite. This prevents migration
or provisioning from creating a permissive SQLite file or omitting connection invariants.

No dialect conditional is permitted in the domain or Application Service packages. Repository-level
dialect handling is allowed only where database race semantics require it and must return the same
repository result.

### SQLite engine and transaction policy

For a valid file URL, validate that its parent exists and is a writable directory. If the database
does not exist, create it atomically with owner read/write mode (`0600`) before SQLAlchemy connects.
Existing files are never chmodded. On every connection execute `PRAGMA foreign_keys=ON` and
`PRAGMA busy_timeout=5000`. During engine initialization, request and verify
`PRAGMA journal_mode=WAL` once; do not re-negotiate journal mode for every pooled read connection.
Startup fails if required runtime settings cannot be established. Use the driver timeout as a
matching secondary bound. Alembic and explicit workspace provisioning use this same factory.

Every command Unit of Work must acquire `BEGIN IMMEDIATE` before its first workspace or projection
read. This closes the `SELECT ... FOR UPDATE` gap because SQLite ignores row locks. Read-only query
repositories keep ordinary read transactions. A busy/locked failure is mapped once to
`storage.busy`, includes a retry-later instruction, and does not rerun the service. PostgreSQL keeps
the existing row-lock path.

### Schema and exact types

Keep one Alembic head. Because the initial migration is not released, revise the existing baseline
in place so exact-decimal columns render as `NUMERIC(38,18)` on PostgreSQL and canonical decimal text
on SQLite. The SQLAlchemy `ExactDecimal` and `UTCDateTime` decorators remain the model boundary.
SQLite foreign-key enforcement is a connection invariant, not merely a test option.

### Stable diagnostics

Define structured runtime error codes used by tests and CLI mapping:

- `storage.config`: missing, malformed, unsupported, or memory-only runtime URL;
- `storage.connect`: unreachable database or unusable SQLite path/file;
- `storage.schema`: missing or stale Alembic schema;
- `storage.workspace`: unknown workspace;
- `storage.readonly`: selected database cannot accept required writes;
- `storage.busy`: SQLite lock wait exceeded.

Messages include only a sanitized summary. PostgreSQL summaries contain dialect and, when safe,
host/database name but never username, password, port query parameters, or URL query. SQLite
summaries contain dialect plus a non-reversible short path digest/basename classification, never the
full path or URL query. Exception chaining may retain driver details internally but neither logs nor
CLI render `str(cause)`.

`ServiceBundle` gains an immutable `notices` tuple containing structured, already-sanitized runtime
notices. The SQLite permission inspector appends at most one notice per startup, combining affected
database/sidecar classes without paths. The CLI renders bundle notices once before command output.
Warnings are not printed inside repositories and are not represented as business-operation results.

The CLI dispatcher has one outer `StorageError` boundary covering both service construction and
service execution. It maps every storage code to controlled stderr text and exit status `1`; command
handlers do not catch or render SQLAlchemy/driver exceptions individually. `--help` and explicit
file conversion still avoid runtime composition entirely.

### Contract test topology

Create one parametrized storage fixture yielding the same `RelationalUnitOfWork`, repositories, and
runtime services for:

- a migrated, file-backed SQLite temporary database; and
- a migrated, dedicated PostgreSQL database from `FT_TEST_POSTGRES_URL`, whose database name must end
  in `_test`.

The shared suite owns all persistence-dependent Application Service and CLI scenarios. The
PostgreSQL parameter may skip during a developer's default fast loop, but when
`FT_REQUIRE_TEST_POSTGRES=1` is set it must fail during collection/fixture setup if the URL is missing,
unsafe (database name does not end in `_test`), or unreachable. Every documented completion command
sets this flag. SQLite-specific locking/permissions and
PostgreSQL-specific row-lock assertions remain focused integration tests alongside the shared suite.
Pure parser/domain tests run once.

### Runtime data flow

```text
FT_DATABASE_URL + FT_WORKSPACE_ID
              |
              v
     StorageSettings.parse
       | reject unsupported/memory runtime URL
       v
create_relational_engine  <---------------- Alembic / provisioning / test fixtures
       | PostgreSQL: psycopg + pre-ping
       | SQLite: secure pre-create -> connect pragmas -> WAL verify -> permission notice
       v
validate schema head + workspace
       |
       v
ServiceBundle(shared Application Services + Relational UoW/repos + notices)
       |
       +--> CLI prints sanitized notices once
       |
       v
command UoW
  PostgreSQL: BEGIN -> row locks ---------+
  SQLite: BEGIN IMMEDIATE -> writer slot --+--> fact + audit + projection -> COMMIT
                                                | any failure
                                                +--------------------------> ROLLBACK
```

### Error flow

```text
config / connect / schema / workspace / readonly / busy failure
                           |
                           v
             adapter maps to StorageError(code, safe summary)
                           |
                           v
                 one CLI outer boundary
                           |
                           +--> controlled stderr + exit 1
                           +--> never raw cause / URL / full SQLite path
```

## Project Structure

### Documentation (this feature)

```text
specs/002-dual-database-runtime/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── cli.md
│   └── runtime.md
└── tasks.md
```

### Source Code (repository root)

```text
src/ft/
├── config.py
├── runtime.py
├── cli.py
├── adapters/
│   └── relational/
│       ├── __init__.py
│       ├── dialect.py
│       ├── runtime.py
│       ├── models.py
│       ├── uow.py
│       ├── repositories.py
│       ├── queries.py
│       ├── investments.py
│       └── imports.py
├── application/
└── domain/

migrations/
├── env.py
└── versions/20260717_01_initial.py

tests/
├── conftest.py
├── test_storage_configuration.py
├── test_relational_runtime.py
├── test_relational_contract.py
├── test_relational_import_provenance.py
├── test_relational_statement_import.py
├── test_relational_live.py
├── test_alembic_migration.py
├── test_cli.py
└── test_cli_application_boundary.py
```

**Structure Decision**: Keep the single-package project. Rename the current adapter rather than
adding parallel PostgreSQL and SQLite adapters, because the existing models and repositories are
already mostly dialect-neutral and two implementations would create parity drift. Existing focused
tests may be renamed or consolidated when doing so improves the explicit shared matrix; pure domain
and parser test locations remain unchanged.

## Verification Gates

1. Add each test first and record a failure caused by the missing dual-runtime behavior.
2. Run focused SQLite and PostgreSQL contract tests while implementing.
3. Run the same migrated storage contract matrix with both parameters and no unexplained skips using
   `FT_REQUIRE_TEST_POSTGRES=1`.
4. Run `FT_REQUIRE_TEST_POSTGRES=1 uv run pytest`, `uv run alembic heads`, `uv build`, and
   `git diff --check`.
5. Execute the SQLite and PostgreSQL quickstarts and compare normalized outputs.
6. Run `$speckit-converge`, gstack `review`, fix blockers, and repeat affected/full checks.

## Complexity Tracking

No constitution violations or approved exceptions.

## Engineering Review Findings Incorporated

1. **P1, architecture (confidence 10/10)**: direct `create_engine` in current runtime tests,
   Alembic, and provisioning would bypass SQLite file mode/pragmas. One engine factory now owns all
   production/integration engine creation.
2. **P1, error contract (confidence 10/10)**: current CLI command paths call `_runtime_services()`
   and execute services without one storage exception boundary. The plan now requires a single outer
   boundary that covers startup and commit-time errors.
3. **P2, diagnostics (confidence 9/10)**: warning-only file permission behavior had no defined path
   from adapter to user. Immutable `ServiceBundle.notices` makes delivery explicit and testable.
4. **P2, performance (confidence 8/10)**: running `PRAGMA journal_mode=WAL` on every connection can
   take a database mode lock on otherwise read-only connection checkout. Set and verify it once per
   engine initialization; keep foreign key and busy timeout hooks per connection.
5. **P1, verification (confidence 10/10)**: a skip-gated PostgreSQL fixture does not prove the
   constitution's real-backend requirement. `FT_REQUIRE_TEST_POSTGRES=1` turns absent/unsafe/unreachable
   PostgreSQL into an explicit failure in completion runs.

## Test Coverage Map

```text
CODE PATHS                                      USER FLOWS
[+] StorageSettings.parse                      [+] Choose runtime database
  +-- valid PostgreSQL [matrix]                  +-- PostgreSQL quickstart [integration]
  +-- valid file SQLite [matrix]                 +-- SQLite quickstart [integration]
  +-- memory/other/malformed [unit]               +-- no fallback/second connect [spy]
[+] create_relational_engine                   [+] Operate SQLite file
  +-- PostgreSQL connect failure [integration]   +-- secure new file [integration]
  +-- SQLite parent/open failure [integration]   +-- permissive existing file [integration]
  +-- FK/busy/WAL invariants [integration]        +-- lock wait -> actionable busy [2-process]
[+] runtime validation                         [+] Run every persisted CLI workflow
  +-- schema missing/stale [matrix]               +-- account/cash/transfer [matrix]
  +-- workspace missing [matrix]                  +-- investment/query/report [matrix]
  +-- sanitized notices/errors [capture]          +-- import/duplicate/rollback [matrix]
[+] command UoW                                [+] Concurrent writers
  +-- PostgreSQL row lock [real PostgreSQL]       +-- both succeed with correct sum, or
  +-- SQLite BEGIN IMMEDIATE [file SQLite]         +-- SQLite busy with no partial state
  +-- commit/rollback/close [failure injection]
[+] exact type + migration
  +-- upgrade/head/downgrade [both backends]
  +-- 38/18 boundaries + non-finite [matrix]
  +-- UTC/Asia-Shanghai buckets [matrix]
```

Every branch above must have an assertion on output and persisted pre/post state where applicable.
The two-process SQLite lock test uses independent engines so a process-local lock cannot produce a
false pass. The PostgreSQL matrix uses an actual database, never SQLite or a mock.

## Failure Modes

| Code path | Production failure | Required coverage and user result |
|---|---|---|
| URL parse | Credentials in malformed input | Redaction test; `storage.config`, no input echo |
| SQLite preparation | Parent missing/unwritable or concurrent first creation | File integration test; `storage.connect`, no fallback |
| SQLite initialization | WAL unsupported or pragma not active | Invariant test; startup fails with sanitized `storage.connect` |
| Permission inspection | DB/sidecar group-readable | Mode test; one notice, no chmod, command may succeed |
| Runtime validation | Missing/stale head or workspace | Both-backend matrix; actionable schema/workspace error |
| SQLite command begin | Another process owns writer slot | Timed two-process test; about 5 seconds then `storage.busy`, no replay/state change |
| PostgreSQL command | Deadlock/disconnect at commit | Failure mapping/rollback test; controlled error, no partial state |
| Projection update | Two writers read old projection | Backend concurrency test; correct sum or SQLite busy, never lost delta |
| Import uniqueness | Same batch/record races | Concurrent/sequential matrix; one logical fact set |
| CLI error rendering | SQLAlchemy cause includes secret/path | Captured stdout/stderr test; stable code and no forbidden substrings |
| Test environment | PostgreSQL URL absent or points to non-test DB | Required-mode fixture fails before destructive setup |

No silent failure mode remains in the planned paths.

## What Already Exists

- `ExactDecimal`, `UTCDateTime`, the SQLAlchemy model set, workspace-qualified relationships, and
  repository/UoW code are reused and renamed, not rebuilt.
- Existing SQLite-backed adapter tests supply most business contracts; they move to a file-backed
  shared matrix and gain a real PostgreSQL parameter.
- Existing live PostgreSQL migration, isolation, rollback, and concurrency tests are retained and
  generalized.
- Existing Alembic head and initial migration remain the one history; only unreleased dialect-aware
  physical decimal rendering and engine construction change.
- Existing Application Services, repository protocols, domain validation, CLI grammar, and renderers
  remain the behavioral path for both databases.

## NOT in Scope

- Cross-database import/export, migration, synchronization, backup conversion, dual write, fallback,
  and shadow comparison: each would require a separate audited feature.
- Restoring CSV/YAML/Git runtime ledgers or compatibility aliases for the unpublished adapter package.
- Matching PostgreSQL and SQLite throughput, topology, lock primitives, generated IDs, raw driver
  text, or query plans.
- New Web, Worker, MCP, analytics, packaging/distribution, or deployment artifacts: this feature
  changes the existing Python CLI runtime only.
- Automatically changing permissions on existing SQLite files or supporting unreliable network
  filesystem locking.

## Parallelization

Sequential implementation, no safe worktree parallelization opportunity. Configuration, engine
policy, UoW locking, renamed repositories, migration imports, shared fixtures, and CLI error handling
all converge on the same adapter/composition surface. Documentation can be updated after behavior is
green but is too small to justify a second worktree.

## Implementation Tasks From Engineering Review

- [X] **ER1 (P1)**: Introduce and route all production, migration, provisioning, and integration
  engine creation through the relational engine factory; verify secure SQLite creation and pragmas.
- [X] **ER2 (P1)**: Add the shared storage error hierarchy and one CLI outer boundary; verify every
  category, exit status, rollback behavior, and secret/path redaction.
- [X] **ER3 (P2)**: Add immutable runtime notices and the warning-only SQLite permission flow; verify
  new/existing DB and sidecar modes without mutation.
- [X] **ER4 (P1)**: Implement backend-aware command transaction acquisition and real concurrent
  tests; verify PostgreSQL serialization and SQLite correct-sum-or-busy behavior.
- [X] **ER5 (P1)**: Build the shared migrated contract matrix and required-PostgreSQL mode; verify all
  storage-dependent Application/CLI workflows on both backends with no unexplained skips.
- [X] **ER6 (P2)**: Update README/help/current product docs and execute both quickstarts, full tests,
  Alembic head check, build, and diff check.

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 1 | CLEAR | mode: HOLD_SCOPE, 0 critical gaps |
| Codex Review | `/codex review` | Independent 2nd opinion | 0 | NOT RUN | No outside-voice review requested |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 1 | CLEAR | 5 issues incorporated, 0 critical gaps |
| Code Review | `/review` | Final implementation diff (required) | 1 | CLEAR | Scope clean for feature, 0 unresolved findings |
| Design Review | `/plan-design-review` | UI/UX gaps | 0 | NOT APPLICABLE | No UI scope |
| DX Review | `/plan-devex-review` | Developer experience gaps | 0 | NOT RUN | Not required by repository workflow |

**VERDICT:** CEO + ENG + CODE REVIEW CLEARED - implementation and verification complete.

NO UNRESOLVED DECISIONS
