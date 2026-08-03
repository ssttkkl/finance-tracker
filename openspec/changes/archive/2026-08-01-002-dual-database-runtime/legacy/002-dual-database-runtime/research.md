# Research: PostgreSQL and SQLite Runtime Parity

## Decision 1: Use one neutral relational adapter

**Decision**: Rename the current PostgreSQL-named SQLAlchemy adapter to `relational` and keep a
single model/repository/UoW/composition implementation with a small dialect policy.

**Rationale**: Current models already use dialect-aware `ExactDecimal` and `UTCDateTime`, current
repository tests execute on SQLite, and Application Services depend on repository/UoW behavior rather
than PostgreSQL APIs. One implementation makes parity the default and confines unavoidable database
differences to engine, locking, DDL type, and error handling.

**Alternatives considered**:

- Separate PostgreSQL and SQLite adapter trees: rejected because most code would be duplicated and
  future behavior could drift.
- Keep the package named `postgres`: rejected because it misstates the runtime contract and invites
  backend checks outside the intended boundary.
- Introduce a generic plugin framework: rejected as unnecessary for exactly two known SQLAlchemy
  dialects.

## Decision 2: Validate and classify the URL before engine creation

**Decision**: Parse `FT_DATABASE_URL` with SQLAlchemy's structured URL API. Accept PostgreSQL and
file-backed SQLite for runtime; allow memory SQLite only through explicit test fixtures. Reject all
other forms before any engine or business service is created.

**Rationale**: Structured parsing prevents fragile string matching and provides one fail-closed
selection point. The resulting settings carry the selected dialect but no fallback list.

**Alternatives considered**:

- Detect a local file or running server automatically: rejected because selection would be implicit.
- Try PostgreSQL and then SQLite: rejected as fallback and as a risk of writing to the wrong fact
  source.
- Accept SQLite memory URLs in production: rejected because separate CLI processes would not share
  durable state.

## Decision 3: Acquire SQLite write ownership before reading mutable state

**Decision**: Command UoWs on SQLite begin with `BEGIN IMMEDIATE` before workspace validation and
projection reads. PostgreSQL continues to use row-level `FOR UPDATE` locks. SQLite uses WAL and a
5,000 ms busy timeout; timeout maps to `storage.busy` without service replay.

**Rationale**: SQLite ignores `SELECT ... FOR UPDATE`. A deferred transaction could let two writers
read the same projection and then race, while an immediate transaction reserves the single writer
slot before either reads the old value. This guarantees serialization or a bounded explicit failure.

**Alternatives considered**:

- Optimistic snapshot version compare/retry: viable but adds conflict loops and Application Service
  replay semantics not required by the spec.
- Process-local mutex: rejected because it does not protect separate CLI processes.
- Rely only on `busy_timeout`: rejected because waiting alone does not prevent stale read/overwrite.
- `BEGIN EXCLUSIVE`: rejected because it is stronger than required and unnecessarily restricts
  readers under non-WAL modes.

## Decision 4: Enable required SQLite pragmas centrally

**Decision**: Use SQLAlchemy connection hooks to set and verify `foreign_keys=ON` and
`busy_timeout=5000` on every file-runtime connection. Set and verify persistent
`journal_mode=WAL` once during relational engine initialization.

**Rationale**: SQLite foreign keys and busy timeout are connection-local, while WAL is a persistent
database operating mode whose negotiation can acquire a database lock. Applying all settings in the
central engine factory prevents weaker semantics; avoiding WAL negotiation on every pooled checkout
keeps read connections from taking an unnecessary mode lock.

**Alternatives considered**:

- Set pragmas once during provisioning: rejected because future connections would not inherit all
  connection-local settings.
- Put pragma calls in every repository: rejected as duplication and a boundary leak.
- Continue if foreign keys or WAL cannot be established: rejected because runtime behavior would no
  longer satisfy the declared backend contract.

## Decision 4A: Use one engine factory across every database entry point

**Decision**: Runtime composition, Alembic online migration, provisioning guidance, and integration
fixtures all call `create_relational_engine(...)`. Direct SQLAlchemy engine construction is limited
to explicit isolated unit tests.

**Rationale**: SQLite file mode, connect pragmas, WAL verification, timeout, and sanitization are
runtime invariants. If Alembic creates the file first with `engine_from_config`, or provisioning uses
plain `create_engine`, the file can have weaker permissions and behavior before normal runtime starts.

**Alternatives considered**:

- Duplicate hooks in `migrations/env.py` and test fixtures: rejected because drift would be likely.
- Document `umask 077` as an operator prerequisite: rejected because the application can enforce a
  secure default for new files directly.

## Decision 5: Preserve exact decimals with dialect-specific physical types

**Decision**: Keep one logical exact-decimal type: PostgreSQL stores `NUMERIC(38,18)` and SQLite
stores a validated canonical decimal string. Revise the unreleased initial migration so migrated
SQLite uses text rather than NUMERIC affinity.

**Rationale**: SQLite NUMERIC affinity may convert decimal strings through floating-point storage,
which cannot prove 18-digit fractional fidelity. Canonical text round-trips through `Decimal`
without binary float, while validation and calculations remain in the shared domain/model boundary.

**Alternatives considered**:

- SQLite NUMERIC columns: rejected because exact storage class selection is not guaranteed.
- Scaled integers: rejected because the model supports values with varying scale and total range;
  conversion would be more invasive and still dialect-specific.
- Store all backends as text: rejected because PostgreSQL provides a native exact numeric type and
  database-level precision enforcement.

## Decision 6: Keep one Alembic history and logical head

**Decision**: Use the same Alembic environment, revision ID, metadata, and upgrade command for both
backends. Because the baseline is unreleased, update the current initial revision rather than append
a compatibility migration. Verify upgrade/downgrade and logical constraints on both.

**Rationale**: One migration history makes schema parity reviewable and aligns with the constitution.
Physical type rendering can branch at the adapter/DDL type boundary without creating separate heads.

**Alternatives considered**:

- Two migration directories or heads: rejected because they could evolve independently.
- Runtime `Base.metadata.create_all`: rejected because production schema must be versioned and
  checked.
- Automatic PostgreSQL-to-SQLite copy: rejected as an explicit non-goal.

## Decision 7: Normalize errors at the adapter boundary

**Decision**: Expose stable storage error codes for config, connect, schema, workspace, readonly, and
busy failures. Driver exceptions remain causes, but CLI/log rendering uses only controlled messages
and sanitized summaries.

**Rationale**: PostgreSQL and SQLite produce different exception classes and text, and raw database
URLs or file paths can contain secrets. Stable categories provide equivalent user behavior without
pretending raw messages are portable.

**Alternatives considered**:

- Print raw SQLAlchemy/driver errors: rejected for parity and secret leakage.
- Collapse all failures to one message: rejected because users need actionable schema, workspace,
  lock, and connectivity guidance.
- Sanitize using ad hoc regexes: rejected in favor of structured URL components.

## Decision 7A: Deliver operational warnings as structured bundle notices

**Decision**: Add an immutable notices tuple to `ServiceBundle`. Adapter startup produces sanitized
notices and the CLI renders them once. Repositories and Application Services do not print warnings.

**Rationale**: Permission warnings are operational, not business failures. An explicit value makes
them deterministic, testable, and independent of Python warning filters or logging configuration.

**Alternatives considered**:

- `warnings.warn`: rejected because filters can hide it and formatting is not the CLI contract.
- Print during engine creation: rejected because the adapter should not depend on terminal output.
- Put warnings in every `OperationResult`: rejected because queries and startup errors do not all
  return that type and the concern is not domain behavior.

## Decision 8: Treat SQLite permissions as a warning for existing files

**Decision**: Atomically pre-create a new SQLite database with owner-only mode where supported.
Inspect an existing database and present sidecars for group/other permission bits; emit a sanitized
warning and repair command pattern, but do not chmod or block startup.

**Rationale**: New secure defaults are controllable. Existing file ownership and access may be an
intentional operator choice, so mutation or refusal would exceed the approved behavior. The warning
still makes local financial-data exposure visible.

**Alternatives considered**:

- Automatically chmod existing files: rejected by the clarification and because it changes operator
  state.
- Block startup: rejected by the clarification.
- Ignore permissions: rejected because local finance data would lack an operational warning.

## Decision 9: Use one explicit backend contract matrix

**Decision**: Parametrize all persistence-dependent Application Service and CLI tests over migrated
file SQLite and a real dedicated PostgreSQL database. Normalize generated IDs/timestamps only where
they are intentionally outside the contract. Keep backend-specific tests for SQLite file/locking
behavior and PostgreSQL row-lock behavior.

**Rationale**: Running separate suites with different scenarios would not prove equivalence. A single
scenario function makes missing backend coverage visible. Pure domain/parser behavior has no storage
branch and should remain fast and run once.

**Alternatives considered**:

- In-memory SQLite as a PostgreSQL substitute: rejected because it proves neither file operations nor
  PostgreSQL behavior.
- Mocks for one backend: rejected by the constitution.
- Skip live PostgreSQL when the environment variable is absent: acceptable for a developer's quick
  local loop, but explicitly insufficient for final completion evidence.

Completion commands set `FT_REQUIRE_TEST_POSTGRES=1`; in this mode an absent, unsafe, or unreachable
PostgreSQL URL is a test failure rather than a skip. The default local fast loop may still skip the
live parameter when the flag is absent.

## Resolved Unknowns

No `NEEDS CLARIFICATION` items remain. The only environmental prerequisite still to be supplied
before final verification is a reachable dedicated PostgreSQL database URL ending in `_test`; it is
an execution prerequisite, not an architecture ambiguity.
