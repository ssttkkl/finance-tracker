# Tasks

## 1. 迁移后的历史任务清单

- [X] T001 Record the approved feature artifact set and verify `spec.md`, `plan.md`, `research.md`, `data-model.md`, `contracts/`, and `quickstart.md` are present under `openspec/specs/002-dual-database-runtime/`.
- [X] T002 [P] Add neutral adapter package/test path scaffolding in `src/ft/adapters/relational/__init__.py`, `tests/conftest.py`, and `tests/test_relational_runtime.py`; keep the old implementation untouched until failing tests are in place.
- [X] T003 [P] Add a backend-independent result normalizer used only by parity tests in `tests/relational_assertions.py`; exclude generated IDs, physical types, and backend-native exception text while retaining all business/audit fields.
- [X] T004 Add failing configuration tests in `tests/test_storage_configuration.py` for PostgreSQL URLs, file SQLite URLs, rejected memory SQLite runtime URLs, malformed/unsupported URLs, legacy variables, and sanitized summaries.
- [X] T005 Add failing engine policy tests in `tests/test_relational_runtime.py` for owner-only new SQLite database/`-wal`/`-shm` files, existing permissive database/sidecar warnings without chmod, missing/unwritable parent, `foreign_keys=ON`, `busy_timeout` near 5000, WAL verification, and one-engine-factory usage.
- [X] T006 [P] Add failing migration parity tests in `tests/test_alembic_migration.py` for the single head, upgrade/downgrade on file SQLite and real PostgreSQL, dialect-specific exact-decimal column types, and the same logical tables, workspace-qualified keys, indexes, and constraints.
- [X] T007 Add failing storage error/redaction tests in `tests/test_relational_runtime.py` for `storage.config`, `storage.connect`, `storage.schema`, `storage.workspace`, `storage.readonly`, and `storage.busy`, asserting no password, URL query, raw cause, or complete SQLite path.
- [X] T008 Add failing notice propagation tests in `tests/test_relational_runtime.py` and `tests/test_cli.py` for one combined sanitized database/sidecar permission notice in `ServiceBundle.notices`, one CLI rendering, no chmod, and no notice on owner-only files.
- [X] T009 Add failing command transaction tests in `tests/test_relational_live.py` for SQLite `BEGIN IMMEDIATE` before workspace/projection reads, rollback on injected exceptions, close behavior, and PostgreSQL row-lock preservation.
- [X] T010 Add failing required-PostgreSQL fixture tests in `tests/conftest.py` and `tests/test_relational_live.py` proving `FT_REQUIRE_TEST_POSTGRES=1` fails on absent, unsafe, or unreachable `FT_TEST_POSTGRES_URL` and succeeds only for a dedicated `_test` database.
- [X] T011 Add failing outer CLI and documentation contract tests in `tests/test_cli.py`, `tests/test_cli_application_boundary.py`, and `tests/test_runtime_docs.py` for startup-time/commit-time storage errors, controlled stderr, exit status 1, database-free `--help`/`convert`, both supported backends, file SQLite limitations, and no fallback/dual-write/implicit-migration claims.
- [X] T012 Implement structured runtime settings and supported URL classification in `src/ft/config.py`, accepting PostgreSQL and file SQLite while rejecting memory runtime URLs and every legacy selector.
- [X] T013 Implement `create_relational_engine(...)`, owner-only SQLite database/sidecar creation, existing database/sidecar permission inspection without chmod, connection hooks, one-time WAL verification, sanitized connection summaries, and dialect policy in `src/ft/adapters/relational/dialect.py`.
- [X] T014 Route `migrations/env.py`, runtime composition, workspace provisioning, and integration fixtures through the relational engine factory; add explicit test-only memory engine helpers where needed.
- [X] T015 Revise the unreleased Alembic baseline in `migrations/versions/20260717_01_initial.py` and metadata imports so PostgreSQL uses `NUMERIC(38,18)` and SQLite uses canonical decimal text while preserving one revision head and all logical constraints.
- [X] T016 Move `src/ft/adapters/postgres/` to `src/ft/adapters/relational/`, rename exported `Postgres*` types to `Relational*`, update all imports/tests, and keep one shared model/repository/UoW implementation with no compatibility alias.
- [X] T017 Add the structured `StorageError` hierarchy, stable codes, controlled messages, and safe cause chaining in `src/ft/adapters/relational/runtime.py` and `src/ft/runtime.py`.
- [X] T018 Add immutable runtime notices to `src/ft/runtime.py`, collect SQLite permission warnings during composition, and expose them without mixing them into domain `OperationResult` values.
- [X] T019 Implement backend-aware command transaction acquisition and busy/readonly/connect error mapping in `src/ft/adapters/relational/uow.py` and `src/ft/adapters/relational/dialect.py`; do not replay Application Services.
- [X] T020 Implement one outer storage-error boundary and one notice-rendering path in `src/ft/cli.py`; remove per-command raw storage exception rendering while preserving current business result output and database-free help/export paths.
- [X] T021 [US1] Add the shared runtime startup contract matrix in `tests/test_relational_contract.py` for valid PostgreSQL/file SQLite selection, schema-head validation, workspace provisioning, and unknown-workspace failure.
- [X] T022 [US1] Add the shared CLI workflow matrix in `tests/test_relational_contract.py` and `tests/test_cli.py` for account, cash, check-in, transfer, investment, list, report, and statement import dispatch through the same service bundle.
- [X] T023 [P] [US1] Add no-fallback/one-connection spy tests in `tests/test_relational_runtime.py` proving unsupported, unavailable, stale, or read-only selected storage never opens another backend or legacy file ledger.
- [X] T024 [US1] Wire `src/ft/runtime.py` to build the shared relational `ServiceBundle` for either parsed dialect, including the selected workspace and sanitized notices.
- [X] T025 [US1] Update `src/ft/adapters/relational/runtime.py`, query repositories, command repositories, imports, and UoW construction so all current services use the neutral adapter for both dialects.
- [X] T026 [US1] Update CLI help, import success text, and current runtime configuration messages in `src/ft/cli.py` to name PostgreSQL and file SQLite and state no fallback/dual-write/implicit migration.
- [X] T027 [US1] Run the US1 SQLite matrix first, then the same matrix with `FT_REQUIRE_TEST_POSTGRES=1` against real PostgreSQL; record normalized outputs and mark this phase complete only when both pass.
- [X] T028 [US2] Add decimal/time parity tests in `tests/test_relational_contract.py` for 38/18 boundaries, over-scale/non-finite rejection, Decimal round-trip, UTC restoration, Asia/Shanghai day/month bucketing, and no float conversion.
- [X] T029 [US2] Add transaction atomicity/failure-injection tests in `tests/test_relational_contract.py`, `tests/test_relational_statement_import.py`, and `tests/test_relational_import_provenance.py` proving no partial facts, raw records, revisions, projections, or batch status.
- [X] T030 [US2] Add idempotency parity tests for repeated source digest, provider source identity, raw file, and formal fact publication in `tests/test_relational_contract.py` and `tests/test_relational_import_provenance.py`.
- [X] T031 [P] [US2] Add two-process file SQLite concurrency tests in `tests/test_relational_live.py` and real PostgreSQL concurrency tests in `tests/test_relational_live.py` for correct projection sums, bounded SQLite busy failure, and no lost update.
- [X] T032 [US2] Add normalized query/report/revision/source relationship comparisons in `tests/test_relational_contract.py` for all account, cashflow, investment, import, and projection scenarios.
- [X] T033 [US2] Apply the shared `ExactDecimal`/`UTCDateTime` model contract and dialect-specific migration types in `src/ft/adapters/relational/models.py` and `migrations/versions/20260717_01_initial.py`.
- [X] T034 [US2] Ensure projection repository locking and save/version behavior in `src/ft/adapters/relational/repositories.py` is serialized by PostgreSQL row locks or SQLite UoW writer reservation without changing repository results.
- [X] T035 [US2] Preserve import nested-savepoint/idempotency behavior and workspace-qualified foreign-key handling in `src/ft/adapters/relational/imports.py` and related repositories for both dialects.
- [X] T036 [US2] Normalize dialect-native connection, lock, readonly, uniqueness, and transaction failures at `src/ft/adapters/relational/runtime.py` without leaking raw errors or replaying command services.
- [X] T037 [US2] Run the complete US2 SQLite and required real PostgreSQL contract matrix, including failure injection and concurrency, and compare facts/provenance/revisions/projections/queries with `tests/relational_assertions.py`.
- [X] T038 [US3] Update `README.md`, `docs/README.md`, `docs/productization-refactor-plan.md`, and current runtime-facing docs to satisfy the failing T011 documentation contract with both formal backends, file permissions/locking, allowed operational differences, and non-goals.
- [X] T039 [US3] Update `alembic.ini`, `migrations/env.py`, test fixtures, and quickstart snippets to use the single relational engine factory and explicit `FT_REQUIRE_TEST_POSTGRES=1` completion gate.
- [X] T040 [US3] Add operator-facing SQLite busy/permission/schema troubleshooting text and sanitized CLI help in `src/ft/cli.py` and `openspec/specs/002-dual-database-runtime/contracts/` without exposing full paths or credentials.
- [X] T041 [US3] Execute SQLite and real PostgreSQL quickstarts from `openspec/specs/002-dual-database-runtime/quickstart.md`, capture normalized evidence, and fail the task if PostgreSQL is missing/unreachable/unsafe.
- [X] T042 [P] Run `FT_REQUIRE_TEST_POSTGRES=1 FT_TEST_POSTGRES_URL="$FT_TEST_POSTGRES_URL" uv run pytest` for the full suite and retain the exact command/result in the handoff; pure domain/parser tests may run once but storage skips must be explained.
- [X] T043 [P] Run `uv run alembic heads`, `uv build`, `git diff --check`, and static import/source checks; fix all failures before convergence.
- [X] T044 Run `openspec validate --all --strict` against `spec.md`, `plan.md`, `tasks.md`, contracts, code, and tests; add any convergence tasks to this file before marking them complete.
- [X] T045 Run gstack `review` on the final implementation diff; final status CLEAR with no unresolved findings.
- [X] T046 Verify `git status --short`, intentional files only, all task checkboxes complete, and no PostgreSQL storage skip remains under the required completion command; `431 passed, 1 skipped` (real-PDF parser fixture only), `uv build` and single Alembic head passed.
- [X] T047 CRITICAL Execute the required real PostgreSQL contract matrix against a reachable dedicated `_test` database per FR-013/SC-001 (missing).
- [X] T048 Complete SQLite database and sidecar permission inspection, combined immutable notice propagation, and focused engine-policy tests per FR-012/FR-016; verified by `tests/test_relational_runtime.py` and the focused 68-test matrix.
- [X] T049 Normalize commit-time readonly/busy/connection failures through the outer CLI boundary and add error-path transaction tests per FR-008/FR-017; verified by `tests/test_relational_live.py`, `tests/test_relational_runtime.py`, and `tests/test_cli.py`.
- [X] T050 Assert SQLite canonical decimal storage and run migration schema/constraint parity against real PostgreSQL per FR-006/FR-010; verified by the focused matrix and real PostgreSQL migration cycle.
- [X] T051 Expand the shared relational contract suite to all account, cash, transfer, investment, import, idempotency, failure-injection, and concurrency scenarios per US1/US2; verified by the SQLite/real PostgreSQL focused matrix and required full suite.
- [X] T052 Add executable CLI/documentation/quickstart contract coverage for supported backends, no fallback/dual-write/implicit migration, and SQLite troubleshooting per FR-014/SC-007; verified by `tests/test_runtime_docs.py`, CLI tests, and both quickstarts.

## 2. 迁移确认

- [x] 2.1 保留原始任务、验证证据和未解决风险。
- [x] 2.2 将行为需求投影到 OpenSpec 主规格。
