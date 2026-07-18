# Tasks: PostgreSQL and SQLite Runtime Parity

**Input**: Design documents from `/specs/002-dual-database-runtime/`

**Prerequisites**: `plan.md`, `spec.md`, `research.md`, `data-model.md`, `contracts/runtime.md`,
`contracts/cli.md`, `quickstart.md`

**Tests**: Every executable behavior below has a test-first task. Storage-dependent tests use the
same contract matrix against a migrated file SQLite database and real PostgreSQL. Set
`FT_REQUIRE_TEST_POSTGRES=1` for completion verification; an absent/unreachable unsafe PostgreSQL
URL is then a failure, not a skip.

## Phase 1: Setup

**Purpose**: Establish the neutral adapter paths and test entry points without changing behavior.

- [ ] T001 Record the approved feature artifact set and verify `spec.md`, `plan.md`, `research.md`, `data-model.md`, `contracts/`, and `quickstart.md` are present under `specs/002-dual-database-runtime/`.
- [ ] T002 [P] Add neutral adapter package/test path scaffolding in `src/ft/adapters/relational/__init__.py`, `tests/conftest.py`, and `tests/test_relational_runtime.py`; keep the old implementation untouched until failing tests are in place.
- [ ] T003 [P] Add a backend-independent result normalizer used only by parity tests in `tests/relational_assertions.py`; exclude generated IDs, physical types, and backend-native exception text while retaining all business/audit fields.

## Phase 2: Foundational Tests

**Purpose**: Add all blocking failure tests before the corresponding implementation.

- [ ] T004 Add failing configuration tests in `tests/test_storage_configuration.py` for PostgreSQL URLs, file SQLite URLs, rejected memory SQLite runtime URLs, malformed/unsupported URLs, legacy variables, and sanitized summaries.
- [ ] T005 Add failing engine policy tests in `tests/test_relational_runtime.py` for owner-only new SQLite database/`-wal`/`-shm` files, existing permissive database/sidecar warnings without chmod, missing/unwritable parent, `foreign_keys=ON`, `busy_timeout` near 5000, WAL verification, and one-engine-factory usage.
- [ ] T006 [P] Add failing migration parity tests in `tests/test_alembic_migration.py` for the single head, upgrade/downgrade on file SQLite and real PostgreSQL, dialect-specific exact-decimal column types, and the same logical tables, workspace-qualified keys, indexes, and constraints.
- [ ] T007 Add failing storage error/redaction tests in `tests/test_relational_runtime.py` for `storage.config`, `storage.connect`, `storage.schema`, `storage.workspace`, `storage.readonly`, and `storage.busy`, asserting no password, URL query, raw cause, or complete SQLite path.
- [ ] T008 Add failing notice propagation tests in `tests/test_relational_runtime.py` and `tests/test_cli.py` for one combined sanitized database/sidecar permission notice in `ServiceBundle.notices`, one CLI rendering, no chmod, and no notice on owner-only files.
- [ ] T009 Add failing command transaction tests in `tests/test_relational_live.py` for SQLite `BEGIN IMMEDIATE` before workspace/projection reads, rollback on injected exceptions, close behavior, and PostgreSQL row-lock preservation.
- [ ] T010 Add failing required-PostgreSQL fixture tests in `tests/conftest.py` and `tests/test_relational_live.py` proving `FT_REQUIRE_TEST_POSTGRES=1` fails on absent, unsafe, or unreachable `FT_TEST_POSTGRES_URL` and succeeds only for a dedicated `_test` database.
- [ ] T011 Add failing outer CLI and documentation contract tests in `tests/test_cli.py`, `tests/test_cli_application_boundary.py`, and `tests/test_runtime_docs.py` for startup-time/commit-time storage errors, controlled stderr, exit status 1, database-free `--help`/`convert`, both supported backends, file SQLite limitations, and no fallback/dual-write/implicit-migration claims.

## Phase 3: Foundational Implementation

**Purpose**: Implement the shared database boundary that all user stories depend on.

- [ ] T012 Implement structured runtime settings and supported URL classification in `src/ft/config.py`, accepting PostgreSQL and file SQLite while rejecting memory runtime URLs and every legacy selector.
- [ ] T013 Implement `create_relational_engine(...)`, owner-only SQLite database/sidecar creation, existing database/sidecar permission inspection without chmod, connection hooks, one-time WAL verification, sanitized connection summaries, and dialect policy in `src/ft/adapters/relational/dialect.py`.
- [ ] T014 Route `migrations/env.py`, runtime composition, workspace provisioning, and integration fixtures through the relational engine factory; add explicit test-only memory engine helpers where needed.
- [ ] T015 Revise the unreleased Alembic baseline in `migrations/versions/20260717_01_initial.py` and metadata imports so PostgreSQL uses `NUMERIC(38,18)` and SQLite uses canonical decimal text while preserving one revision head and all logical constraints.
- [ ] T016 Move `src/ft/adapters/postgres/` to `src/ft/adapters/relational/`, rename exported `Postgres*` types to `Relational*`, update all imports/tests, and keep one shared model/repository/UoW implementation with no compatibility alias.
- [ ] T017 Add the structured `StorageError` hierarchy, stable codes, controlled messages, and safe cause chaining in `src/ft/adapters/relational/runtime.py` and `src/ft/runtime.py`.
- [ ] T018 Add immutable runtime notices to `src/ft/runtime.py`, collect SQLite permission warnings during composition, and expose them without mixing them into domain `OperationResult` values.
- [ ] T019 Implement backend-aware command transaction acquisition and busy/readonly/connect error mapping in `src/ft/adapters/relational/uow.py` and `src/ft/adapters/relational/dialect.py`; do not replay Application Services.
- [ ] T020 Implement one outer storage-error boundary and one notice-rendering path in `src/ft/cli.py`; remove per-command raw storage exception rendering while preserving current business result output and database-free help/export paths.

## Phase 4: User Story 1 - Explicitly Select Either Runtime Database (Priority: P1)

**Goal**: The same current CLI/Application Service workflows run against exactly the PostgreSQL or
file SQLite URL selected by `FT_DATABASE_URL`.

**Independent Test**: Start one empty migrated file SQLite database and one empty migrated real
PostgreSQL `_test` database, provision the same workspace, run account/cash/transfer/investment/query
and import commands, and assert each process touched only its selected backend.

### Tests for User Story 1 (write first)

- [ ] T021 [US1] Add the shared runtime startup contract matrix in `tests/test_relational_contract.py` for valid PostgreSQL/file SQLite selection, schema-head validation, workspace provisioning, and unknown-workspace failure.
- [ ] T022 [US1] Add the shared CLI workflow matrix in `tests/test_relational_contract.py` and `tests/test_cli.py` for account, cash, check-in, transfer, investment, list, report, and statement import dispatch through the same service bundle.
- [ ] T023 [P] [US1] Add no-fallback/one-connection spy tests in `tests/test_relational_runtime.py` proving unsupported, unavailable, stale, or read-only selected storage never opens another backend or legacy file ledger.

### Implementation for User Story 1

- [ ] T024 [US1] Wire `src/ft/runtime.py` to build the shared relational `ServiceBundle` for either parsed dialect, including the selected workspace and sanitized notices.
- [ ] T025 [US1] Update `src/ft/adapters/relational/runtime.py`, query repositories, command repositories, imports, and UoW construction so all current services use the neutral adapter for both dialects.
- [ ] T026 [US1] Update CLI help, import success text, and current runtime configuration messages in `src/ft/cli.py` to name PostgreSQL and file SQLite and state no fallback/dual-write/implicit migration.
- [ ] T027 [US1] Run the US1 SQLite matrix first, then the same matrix with `FT_REQUIRE_TEST_POSTGRES=1` against real PostgreSQL; record normalized outputs and mark this phase complete only when both pass.

## Phase 5: User Story 2 - Preserve Financial and Audit Semantics (Priority: P2)

**Goal**: Equal inputs produce equal exact financial facts, provenance, revisions, projections, queries,
idempotency, and rollback outcomes.

**Independent Test**: Run deterministic success, rejection, failure-injection, duplicate-import, and
concurrent-projection scenarios through the shared matrix and compare normalized state before/after.

### Tests for User Story 2 (write first)

- [ ] T028 [US2] Add decimal/time parity tests in `tests/test_relational_contract.py` for 38/18 boundaries, over-scale/non-finite rejection, Decimal round-trip, UTC restoration, Asia/Shanghai day/month bucketing, and no float conversion.
- [ ] T029 [US2] Add transaction atomicity/failure-injection tests in `tests/test_relational_contract.py`, `tests/test_relational_statement_import.py`, and `tests/test_relational_import_provenance.py` proving no partial facts, raw records, revisions, projections, or batch status.
- [ ] T030 [US2] Add idempotency parity tests for repeated source digest, provider source identity, raw file, and formal fact publication in `tests/test_relational_contract.py` and `tests/test_relational_import_provenance.py`.
- [ ] T031 [P] [US2] Add two-process file SQLite concurrency tests in `tests/test_relational_live.py` and real PostgreSQL concurrency tests in `tests/test_relational_live.py` for correct projection sums, bounded SQLite busy failure, and no lost update.
- [ ] T032 [US2] Add normalized query/report/revision/source relationship comparisons in `tests/test_relational_contract.py` for all account, cashflow, investment, import, and projection scenarios.

### Implementation for User Story 2

- [ ] T033 [US2] Apply the shared `ExactDecimal`/`UTCDateTime` model contract and dialect-specific migration types in `src/ft/adapters/relational/models.py` and `migrations/versions/20260717_01_initial.py`.
- [ ] T034 [US2] Ensure projection repository locking and save/version behavior in `src/ft/adapters/relational/repositories.py` is serialized by PostgreSQL row locks or SQLite UoW writer reservation without changing repository results.
- [ ] T035 [US2] Preserve import nested-savepoint/idempotency behavior and workspace-qualified foreign-key handling in `src/ft/adapters/relational/imports.py` and related repositories for both dialects.
- [ ] T036 [US2] Normalize dialect-native connection, lock, readonly, uniqueness, and transaction failures at `src/ft/adapters/relational/runtime.py` without leaking raw errors or replaying command services.
- [ ] T037 [US2] Run the complete US2 SQLite and required real PostgreSQL contract matrix, including failure injection and concurrency, and compare facts/provenance/revisions/projections/queries with `tests/relational_assertions.py`.

## Phase 6: User Story 3 - Operate and Validate Both Backends (Priority: P3)

**Goal**: Operators and maintainers have one explicit migration/provisioning path, accurate docs, and a
non-skippable dual-backend verification gate.

**Independent Test**: From empty disposable storage, run both quickstarts, migration head checks,
required-mode matrix, full tests, build, and documentation/static checks.

### Tests for User Story 3 (write first)

US3's failing tests are intentionally front-loaded: T006 covers the complete migration/schema matrix,
T010 covers the non-skippable real PostgreSQL gate, and T011 covers CLI/help/documentation contracts.
They must fail for missing dual-runtime behavior before T012-T020 or the US3 implementation begins.

### Implementation for User Story 3

- [ ] T038 [US3] Update `README.md`, `docs/README.md`, `docs/productization-refactor-plan.md`, and current runtime-facing docs to satisfy the failing T011 documentation contract with both formal backends, file permissions/locking, allowed operational differences, and non-goals.
- [ ] T039 [US3] Update `alembic.ini`, `migrations/env.py`, test fixtures, and quickstart snippets to use the single relational engine factory and explicit `FT_REQUIRE_TEST_POSTGRES=1` completion gate.
- [ ] T040 [US3] Add operator-facing SQLite busy/permission/schema troubleshooting text and sanitized CLI help in `src/ft/cli.py` and `specs/002-dual-database-runtime/contracts/` without exposing full paths or credentials.
- [ ] T041 [US3] Execute SQLite and real PostgreSQL quickstarts from `specs/002-dual-database-runtime/quickstart.md`, capture normalized evidence, and fail the task if PostgreSQL is missing/unreachable/unsafe.

## Phase 7: Polish and Cross-Cutting Verification

- [ ] T042 [P] Run `FT_REQUIRE_TEST_POSTGRES=1 FT_TEST_POSTGRES_URL="$FT_TEST_POSTGRES_URL" uv run pytest` for the full suite and retain the exact command/result in the handoff; pure domain/parser tests may run once but storage skips must be explained.
- [ ] T043 [P] Run `uv run alembic heads`, `uv build`, `git diff --check`, and static import/source checks; fix all failures before convergence.
- [ ] T044 Run `$speckit-converge` against `spec.md`, `plan.md`, `tasks.md`, contracts, code, and tests; add any convergence tasks to this file before marking them complete.
- [ ] T045 Run gstack `review` on the final implementation diff; fix every blocking finding, rerun affected tests, and record the final review status.
- [ ] T046 Verify `git status --short`, intentional files only, all task checkboxes complete, and no PostgreSQL test skip remains under the required completion command.

## Dependencies and Execution Order

### Phase Dependencies

- Setup (Phase 1) precedes all work.
- Foundational tests (Phase 2) must fail for the intended reasons before foundational implementation (Phase 3).
- US1 depends on the foundational engine/config/UoW/CLI boundary and is the MVP.
- US2 depends on US1's shared service composition but its parity tests can be drafted in parallel with US1 implementation.
- US3 depends on US1 and US2 behavior so docs and quickstarts describe delivered behavior accurately.
- Polish depends on all three stories and includes converge/review gates.

### User Story Dependencies

- **US1 (P1)**: foundational phase only; delivers explicit selection and current workflow MVP.
- **US2 (P2)**: depends on US1's shared bundle; adds financial/audit/concurrency equivalence.
- **US3 (P3)**: depends on US1 and US2; adds operational/documentation/verification closure.

### Parallel Opportunities

- T003 and T006 are parallel failing-test additions because they touch independent files; T004-T005, T007-T011 stay sequential where test files overlap.
- T023 may run beside T021-T022, and T031 may run beside T028-T030/T032 because each pair uses distinct files.
- T042-T043 are parallel read-only verification commands after implementation.
- Product implementation itself is sequential through the one relational adapter and composition root; do not split it into competing adapter worktrees.

## Implementation Strategy

1. Complete Setup and all foundational failing tests.
2. Implement the neutral engine/config/error/UoW boundary and turn the foundational tests green.
3. Deliver US1 as the MVP and validate the same workflow on file SQLite and real PostgreSQL.
4. Add US2 parity, atomicity, idempotency, and concurrency evidence.
5. Add US3 docs, quickstarts, and required verification mode.
6. Run converge, final gstack review, full tests/build/checks, and only then mark all tasks complete.

**MVP scope**: US1 plus the foundational phase. It is not a completion claim until US2 parity and US3
real-backend/documentation gates also pass.

**Format validation**: Every task uses `- [ ]`, a sequential `T###` ID, exact file paths, `[P]` only
for independent work, and `[US1]`/`[US2]`/`[US3]` only inside the corresponding story phases.
