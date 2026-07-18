# Tasks: PostgreSQL-Only Runtime Storage

**Input**: Design documents from `specs/001-postgres-only-storage/`

**Tests**: Every executable task follows RED → minimal implementation → focused GREEN. Existing filesystem tests are
deleted only after any still-valid financial invariant has been moved to PostgreSQL/application tests.

## Phase 1: Setup and Baseline Evidence

**Purpose**: Freeze the current capability matrix and establish reproducible evidence before destructive deletion.

- [X] T001 Record the supported statement provider/extension matrix from `src/ft/importers/`, `src/ft/convert.py`, and `tests/test_convert.py` in `specs/001-postgres-only-storage/quickstart.md`
- [X] T002 Run and record the pre-change PostgreSQL contract baseline in `specs/001-postgres-only-storage/tasks.md` using `tests/test_postgres_adapter.py`, `tests/test_storage_configuration.py`, and `tests/test_postgres_import_provenance.py`
- [X] T003 Capture the pre-change CLI tree and legacy executable reference inventory in `specs/001-postgres-only-storage/tasks.md` from `src/ft/cli.py`, `README.md`, `docs/`, `SKILL.md`, and `references/`

---

## Phase 2: Foundational PostgreSQL Schema

**Purpose**: Create the clean database contract required by every retained runtime capability.

**⚠️ CRITICAL**: No runtime wiring or legacy deletion begins until this phase is green.

- [X] T004 Add failing repeatable single-baseline, metadata-parity, account-RESTRICT, timestamptz, raw-lineage, revision-target, and workspace-constraint tests in `tests/test_alembic_migration.py`
- [X] T005 Add failing rename/projection identity, referenced-account delete rejection, cross-workspace reference, Decimal scale rejection, currency, timezone round-trip/bucketing, and rollback tests in `tests/test_postgres_adapter.py`
- [X] T006 Add failing statement provenance tests using non-migration source kinds, `raw_record_id` lineage, and fact-specific revision FKs in `tests/test_postgres_import_provenance.py`
- [X] T007 Replace the two development revisions with one clean initial baseline in `migrations/versions/20260717_01_initial.py`
- [X] T008 Update stable account IDs, RESTRICT deletes, timestamptz fields, raw-record/revision FKs, exactly-one revision target, uniqueness, and workspace constraints in `src/ft/adapters/postgres/models.py`
- [X] T009 Update repositories for Decimal scale validation, UTC/Asia-Shanghai time conversion, account/fact relations, projections, and imports in `src/ft/adapters/postgres/repositories.py` and `src/ft/adapters/postgres/imports.py`
- [X] T010 Make Alembic fixtures the schema authority and keep `create_schema` test-only in `src/ft/adapters/postgres/uow.py`, `tests/test_postgres_adapter.py`, and `tests/test_postgres_live.py`
- [X] T011 Run the focused schema/repository tests and record RED-to-GREEN evidence in `specs/001-postgres-only-storage/tasks.md`

**Checkpoint**: A fresh database has one enforceable PostgreSQL-only baseline and workspace-safe repositories.

---

## Phase 3: User Story 1 - All Runtime Entrypoints Use PostgreSQL (Priority: P1) 🎯 MVP

**Goal**: Retained CLI commands use one workspace-bound PostgreSQL bundle and fail closed without database/schema/workspace.

**Independent Test**: With an empty HOME and a configured test workspace, write through one retained entrypoint, query
through another, and confirm no `.ft` directory or ledger file appears.

### Tests for User Story 1 (write and observe RED first)

- [X] T012 [P] [US1] Replace backend-switch tests with failing required-URL/workspace, rejected legacy-key, and environment-only tests in `tests/test_storage_configuration.py`
- [X] T013 [P] [US1] Add failing startup tests for unreachable DB, stale/missing schema, unknown workspace, and help-without-DB in `tests/test_postgres_runtime.py`
- [X] T014 [P] [US1] Add failing CLI tests for PostgreSQL-backed account, cash, transfer, report, list, and cross-command visibility in `tests/test_cli.py` and `tests/test_cli_application_boundary.py`
- [X] T015 [P] [US1] Add failing PostgreSQL investment command/projection-by-account-ID tests for all retained manual operations in `tests/test_application_investment.py` and `tests/test_postgres_adapter.py`
- [X] T016 [US1] Run T012-T015 and record that failures come from the local selector or missing PostgreSQL behavior in `specs/001-postgres-only-storage/tasks.md`

### Implementation for User Story 1

- [X] T017 [US1] Remove backend, ledger-root, legacy environment, HOME, and runtime YAML behavior from `src/ft/config.py`
- [X] T018 [US1] Add connection/schema/workspace validation and PostgreSQL-only composition in `src/ft/adapters/postgres/runtime.py` and `src/ft/runtime.py`
- [X] T019 [US1] Make account CLI helpers consume injected application/query services in `src/ft/acct.py`
- [X] T020 [US1] Build one settings/bundle per invocation and route retained account/cash/transfer/query commands through it in `src/ft/cli.py`
- [X] T021 [US1] Extract storage-independent investment projection from `src/ft/stock.py` into `src/ft/domain/investment_projection.py` while preserving financial cases in `tests/test_stock.py`
- [X] T022 [US1] Implement atomic PostgreSQL investment command and projection writes in `src/ft/adapters/postgres/investments.py`, `src/ft/adapters/postgres/uow.py`, and `src/ft/adapters/postgres/runtime.py`
- [X] T023 [US1] Remove Git change-set calls from retained investment services in `src/ft/application/investment.py` and `src/ft/runtime.py`
- [X] T024 [US1] Route investment commands through the PostgreSQL bundle and parse numeric CLI inputs as Decimal-compatible strings in `src/ft/cli.py`
- [X] T025 [US1] Add an empty-HOME guard and live cross-entrypoint smoke scenario in `tests/test_postgres_live.py`
- [X] T026 [US1] Run the complete US1 suite and record GREEN/no-`.ft` evidence in `specs/001-postgres-only-storage/tasks.md`

**Checkpoint**: Core account, cash, transfer, investment, and query flows work without local storage.

---

## Phase 4: User Story 3 - Raw Statements Enter One PostgreSQL Import Flow (Priority: P3)

**Goal**: Current statement formats go directly from source artifact to immutable raw records and formal facts in one transaction.

**Independent Test**: Import each provider fixture, repeat one import, and inject a mid-batch failure; verify lineage,
idempotency, exact amounts, projection updates, and zero partial facts.

### Tests for User Story 3 (write and observe RED first)

- [X] T027 [P] [US3] Add failing direct cash-statement import tests for all retained provider fixtures in `tests/test_postgres_statement_import.py`
- [X] T028 [P] [US3] Add failing direct DFZQ import tests in `tests/test_postgres_statement_import.py` and preserve parser cases in `tests/test_stock_convert.py`
- [X] T029 [P] [US3] Add failing duplicate-digest, lineage, Decimal-scale, provider-timezone, invalid-row, and mid-transaction rollback tests in `tests/test_postgres_statement_import.py`
- [X] T030 [P] [US3] Add failing `ft import FILE --source ... --account ...` tests proving direct import needs no converted CSV and explicit export never becomes runtime state in `tests/test_cli.py`
- [X] T031 [US3] Run T027-T030 and record the intended missing-service/lineage failures in `specs/001-postgres-only-storage/tasks.md`

### Implementation for User Story 3

- [X] T032 [US3] Move pure cash parsing and built-in mappings out of `src/ft/adapters/local_import.py` into `src/ft/importers/` and `src/ft/adapters/statement_import.py`
- [X] T033 [US3] Move pure DFZQ parsing out of local persistence into `src/ft/importers/dfzq.py` and `src/ft/adapters/statement_import.py`
- [X] T034 [US3] Implement one UoW import service for digest, raw records, facts, revisions, projection, completion, and rollback in `src/ft/application/statement_import.py`
- [X] T035 [US3] Add batch account/source preload and direct raw-record lineage writes in `src/ft/adapters/postgres/imports.py` and `src/ft/adapters/postgres/repositories.py`
- [X] T036 [US3] Implement the contracted `ft import FILE --source ... --account ...` command, replace converted-CSV append paths, and retain explicit user export in `src/ft/cli.py` and `src/ft/adapters/export_csv.py`
- [X] T037 [US3] Run parser/import/provenance/CLI/rollback/live tests and record GREEN evidence in `specs/001-postgres-only-storage/tasks.md`

**Checkpoint**: Every retained provider publishes only PostgreSQL facts with auditable source lineage.

---

## Phase 5: User Story 2 - Delete the Entire Legacy Storage Surface (Priority: P2)

**Goal**: No executable local backend, migration, Git ledger, file snapshot, reconcile session, Connector sync, or obsolete command remains.

**Independent Test**: CLI help and repository scan find only PostgreSQL runtime storage; old `~/.ft` is untouched; all
still-valid financial tests pass without local adapters.

**Dependency note**: Deletion follows US3 because parsers must first leave mixed local modules.

### Tests for User Story 2 (write and observe RED first)

- [X] T038 [P] [US2] Add failing CLI tests for removed commit/status/reset/migrate/append/verify-fix/reconcile/stock-sync commands in `tests/test_cli.py`
- [X] T039 [P] [US2] Add a failing executable-reference and forbidden-ledger-file static test in `tests/test_postgres_only_surface.py`
- [X] T040 [P] [US2] Rewrite live tests to remove local migration/shadow/export setup in `tests/test_postgres_live.py`
- [X] T041 [US2] Run T038-T040 and record the intended legacy-surface failures in `specs/001-postgres-only-storage/tasks.md`

### Implementation for User Story 2

- [X] T042 [US2] Delete migration code in `src/ft/domain/migration.py`, `src/ft/application/migration.py`, `src/ft/adapters/local_migration.py`, and `src/ft/adapters/postgres/migration.py`
- [X] T043 [US2] Delete local composition/core adapters under `src/ft/adapters/local_runtime.py`, `src/ft/adapters/local_change_set.py`, `src/ft/adapters/local_config.py`, `src/ft/adapters/local_csv/`, `src/ft/adapters/local_query.py`, `src/ft/adapters/local_verification.py`, and `src/ft/adapters/local_legacy.py`
- [X] T044 [US2] Delete file-backed mixed adapters after parser extraction in `src/ft/adapters/local_import.py`, `src/ft/adapters/local_investment.py`, `src/ft/adapters/local_reconciliation.py`, and `src/ft/adapters/local_sync.py`
- [X] T045 [US2] Delete Git/file-only application surfaces in `src/ft/application/change_sets.py`, `src/ft/application/reconcile.py`, `src/ft/application/verification.py`, and obsolete DTOs in `src/ft/domain/application.py`
- [X] T046 [US2] Remove local functions from `src/ft/models.py`, `src/ft/report.py`, and `src/ft/stock.py`, then delete `src/ft/accounts.py`, `src/ft/append.py`, `src/ft/snapshot.py`, `src/ft/ledger_layout.py`, `src/ft/pending.py`, `src/ft/ai_working_csv.py`, and `src/ft/ai_apply.py`
- [X] T047 [US2] Remove reconcile, Connector, migration, append, verify, and Git branches from `src/ft/cli.py` plus unused bundle fields/protocols in `src/ft/runtime.py` and `src/ft/repositories/queries.py`
- [X] T048 [US2] Delete `tests/test_storage_migration.py`, `tests/test_migrate_cli.py`, `tests/test_accounts.py`, `tests/test_append.py`, `tests/test_snapshot.py`, `tests/test_report_csv.py`, `tests/test_application_reconciliation.py`, and file-backed `tests/test_reconcile.py`, `tests/test_reconcile_locked.py`, `tests/test_reconcile_pending.py`; move only valid invariants to PostgreSQL/application suites under `tests/`
- [X] T049 [US2] Remove dead imports, protocols, dependencies, exports, and cache artifacts in `src/ft/`, `tests/`, and `pyproject.toml` after an import audit
- [X] T050 [US2] Run CLI/static surface tests and full pytest, then record zero executable legacy references in `specs/001-postgres-only-storage/tasks.md`

**Checkpoint**: PostgreSQL is physically the only runtime storage implementation.

---

## Phase 6: Documentation, Convergence, Review, and Verification

- [X] T051 [P] Rewrite current setup/command/storage guidance in `README.md`, `docs/README.md`, `docs/import-reconcile-flow.md`, and `docs/unified-csv-format.md`
- [X] T052 [P] Mark superseded implementation docs historical in `docs/phase1-application-services.md` and `docs/phase2-postgresql-storage.md`
- [X] T053 [P] Synchronize completed status and deferred Connector/Review Inbox scope in `docs/productization-refactor-plan.md` and `docs/productization-wealth-report-design.md`
- [X] T054 [P] Rewrite or delete obsolete local-ledger instructions in `SKILL.md` and `references/`
- [X] T055 Validate Markdown links, CLI examples, forbidden references, and `git diff --check` across repository documentation
- [X] T056 Run `$speckit-converge` against `specs/001-postgres-only-storage/` and append any required tasks
- [X] T057 Run gstack `review`, fix blocking findings, and rerun review against the final diff
- [X] T058 Run focused tests, full `uv run pytest`, live PostgreSQL tests when available, `uv run alembic heads`, package build/import checks, and record evidence in `specs/001-postgres-only-storage/tasks.md`
- [X] T059 Inspect final diff/status/untracked files/task completion for repository root `.` without staging, committing, pushing, or publishing

---

## Dependencies & Execution Order

```text
Setup -> Schema -> US1 core runtime -> US3 direct import -> US2 deletion -> Docs/review/verification
```

- US1 is the MVP and depends only on the schema foundation.
- US3 depends on US1's UoW and finishes before mixed parser modules are deleted.
- US2 has higher product priority but executes after US3 due to that extraction dependency.
- Final documentation and review depend on all story checkpoints.

## Parallel Opportunities

- T012-T015, T027-T030, and T038-T040 are independent test-writing groups within their phases.
- T051-T054 touch separate documentation areas after behavior stabilizes.
- Core implementation is sequential because models, UoW, CLI, and mixed legacy modules overlap.

## Implementation Strategy

1. Prove the clean schema foundation.
2. Deliver US1 as the independently testable PostgreSQL-only MVP.
3. Deliver US3 direct imports and provenance.
4. Complete US2 by deleting every legacy implementation and obsolete test/command.
5. Synchronize docs, converge, review, and run the full verification matrix.

## Task Accounting

- Total: 80 (including convergence and review follow-ups)
- US1: 15 (T012-T026)
- US3: 11 (T027-T037)
- US2: 13 (T038-T050)
- Shared: 28

## Notes

- `[P]` means different files and no dependency on another incomplete task.
- Never delete a legacy test until its still-valid financial rule has a replacement.
- Do not create commits, PRs, pushes, user-data migration, compatibility shims, or runtime rollback.

## Implementation Evidence

### Phase 1 baseline (2026-07-17)

- Provider matrix recorded in `quickstart.md`: Alipay CSV, WeChat XLSX, ICBC credit/debit PDF,
  CCB debit XLS, and DFZQ PDF-to-text.
- `uv run pytest -q tests/test_postgres_adapter.py tests/test_storage_configuration.py tests/test_postgres_import_provenance.py`:
  `17 passed in 0.30s`.
- Pre-change CLI exposed 14 top-level commands, including legacy `verify`, `commit`, `status`, `reset`, `append`,
  `reconcile`, and `migrate`.
- Pre-change forbidden-pattern scan returned 323 matching lines across code, tests, and documentation; 64 files
  contained the primary local-storage identifiers.

### Phase 2 schema RED/GREEN (2026-07-17)

- Initial focused run failed 7 tests for the intended missing behavior: two-revision history, missing composite FKs,
  account identity replacement, referenced-account deletion, Decimal scale acceptance, string timestamps, and
  polymorphic revision targets.
- After T007-T010, `uv run pytest -q tests/test_alembic_migration.py tests/test_postgres_adapter.py tests/test_postgres_import_provenance.py`:
  `17 passed in 0.47s`.
- `tests/test_postgres_adapter.py` now applies Alembic head through a supplied connection; `create_schema()` remains
  explicitly test-only and is not part of runtime startup.

### Phase 3 US1 RED/GREEN (2026-07-17)

- RED run: 14 intended failures for legacy settings, absent startup validator, local CLI composition, and missing
  PostgreSQL investment command adapter.
- GREEN run: `uv run pytest -q tests/test_storage_configuration.py tests/test_postgres_runtime.py tests/test_cli_application_boundary.py tests/test_application_investment.py tests/test_postgres_adapter.py tests/test_postgres_live.py`
  returned `34 passed, 2 skipped in 0.55s`.
- The two skips are the gated live PostgreSQL scenarios because `FT_TEST_POSTGRES_URL` is not configured. The live
  test code covers cross-entrypoint visibility, empty HOME, workspace isolation, exact Numeric, and rollback.

### Phase 4 US3 RED/GREEN (2026-07-17)

- RED run: 12 intended failures for the absent statement command, parser adapter, import service, lineage, idempotency,
  investment projection, and rollback behavior.
- Direct import now uses one UoW commit for batch/raw/facts/revisions/projection/completion and returns an idempotent
  duplicate result for the same workspace/source/digest.
- Cash and DFZQ parser numeric paths were converted from float/round to Decimal; converter expectations now assert
  exact Decimal values.
- `uv run pytest -q tests/test_convert.py tests/test_stock_convert.py tests/test_postgres_statement_import.py tests/test_postgres_import_provenance.py tests/test_cli.py::test_cli_direct_statement_import_dispatches_without_intermediate_csv`:
  `228 passed, 1 skipped in 1.13s`.

### Phase 5 US2 RED/GREEN (2026-07-17)

- RED CLI run returned 9 intended failures: seven removed top-level commands still appeared in argparse help and
  `stock append`/`stock sync` still appeared under stock help.
- The local adapter family, migration/cutover services, Git change sets, file snapshots, file reconcile/pending state,
  Connector sync, converted-file append path, obsolete protocols and their filesystem contracts were physically deleted.
- Explicit conversion now calls the same storage-independent statement parser as direct import and only writes a
  user-selected export file; it does not build a runtime service bundle or read account mappings.
- `uv run pytest -q tests/test_cli.py tests/test_postgres_only_surface.py tests/test_postgres_live.py`:
  `17 passed, 2 skipped in 0.19s`; skips require `FT_TEST_POSTGRES_URL`.
- Full suite after deletion: `433 passed, 3 skipped in 1.29s`.
- Runtime source scan found zero executable local composition, migration, ledger filename or file-session references;
  rejected legacy environment names remain only in `src/ft/config.py` to implement fail-closed configuration.

### Phase 6 documentation evidence (2026-07-17)

- README, command/storage guidance, import flow, export format, product roadmap, wealth design, project skill and
  references now describe PostgreSQL-only runtime behavior.
- Phase 1 and Phase 2 docs are concise historical records without executable legacy instructions.
- Markdown relative links were checked after excluding fenced code examples; forbidden current-command references
  returned zero, and `git diff --check` passed.

## Phase 7: Convergence

- [X] T060 [CRITICAL] Replace float conversion in manual transfer projection updates with exact Decimal text and add cash/security cross-currency precision regression tests per FR-018 and Constitution I (contradicts)
- [X] T061 Reject investment command/event projection results whose computed shares or costs exceed 18 decimal places, with atomic rollback tests for proportional sell/swap calculations per FR-018 and SC-011 (partial)

### Phase 7 convergence RED/GREEN (2026-07-17)

- RED: one cross-currency cash-to-security transfer test exposed float-truncated projection values; proportional
  sell/swap tests showed computed repeating costs were persisted without the 18-place scale guard.
- GREEN: transfer inputs and projection arithmetic now validate finite scale-18 Decimal values and persist exact text;
  every computed investment projection value passes the same scale guard before event/snapshot writes.
- Focused result: `3 passed in 0.30s`; the sell/swap exceptions roll back both event and projection changes.

## Phase 8: Review Follow-ups

- [X] T062 [CRITICAL] Capture each statement once and derive digest, size, media validation and parser rows from the
  same private immutable copy; add a source-replacement regression test
- [X] T063 [CRITICAL] Move ICBC/DFZQ decrypted artifacts into mode-0700 temporary directories, pass qpdf passwords
  through mode-0600 password files, remove inline `--password`, and add no-sidecar/no-secret-argv tests
- [X] T064 [CRITICAL] Make overlapping statements idempotent by provider record identity so reused raw records do not
  create duplicate formal facts or projection effects
- [X] T065 [CRITICAL] Enforce both scale 18 and NUMERIC(38,18)'s 20-integer-digit bound before persistence and add
  adjacent boundary tests
- [X] T066 Dispatch pure `stock convert` before PostgreSQL service composition and add a no-database regression test
- [X] T067 Delete confirmed unreachable compatibility/reconcile remnants and obsolete test-only islands
- [X] T068 Correct repository protocols for fact IDs and import provenance used by the application service
- [X] T069 Run focused review regression tests, full tests, gstack re-review, build, documentation/link scans and final
  diff/status inspection; record all unavailable live-PostgreSQL validation explicitly
- [X] T070 [CRITICAL] Isolate shared runtime UoW state per concurrent context and lock the workspace projection row
  before every read-modify-write
- [X] T071 [CRITICAL] Restore retained financial invariants for positive transfers, explicit multi-currency account
  selection, sell commission, and cost-currency conflicts with failing tests first
- [X] T072 Make Alembic honor `FT_DATABASE_URL`, generate default timestamps in Asia/Shanghai, and bound statement/PDF
  extraction memory with regression checks
- [X] T073 [CRITICAL] Add a failing portfolio regression for a normally created investment account, then include its
  account currency in the account's cash currencies without requiring metadata
- [X] T074 Add a failing same-file duplicate-provider-ID import regression, then deduplicate raw IDs before formal-fact
  projection while preserving source order
- [X] T075 Add a failing overlap-only duplicate-digest target-account regression, then persist the stable target account
  ID on `ImportBatch` in the single baseline and use it for duplicate validation
- [X] T076 Add a failing active-empty-account deletion regression, then require deactivation before hard deletion
- [X] T077 Add failing CLI regressions for rejected investment commands, then propagate `OperationResult.ok=False` as
  a non-zero process exit
- [X] T078 Run focused regressions, full tests, gstack re-review, build, documentation/link scans and final diff/status
  inspection; record unavailable live-PostgreSQL validation explicitly

### Phase 8 review RED/GREEN (2026-07-18)

- Security and red-team RED cases covered source TOCTOU, plaintext PDF sidecars, password argv leakage, overlapping
  provider identities, target-account conflicts, currency/fact projection divergence, Decimal precision, unsupported
  short positions, concurrent UoW/projection writes, and pure export runtime coupling.
- Focused post-fix result: `59 passed in 0.93s`; full result: `373 passed, 4 skipped in 1.54s`.
- Live PostgreSQL concurrency/constraint tests remain gated by `FT_TEST_POSTGRES_URL`; the four skips are recorded for
  final validation rather than treated as passing evidence.

### Phase 8 review-finding regressions (2026-07-18)

- RED: six new regressions failed for default investment-account cash valuation, overlap-only batch target
  preservation, duplicate provider IDs, active empty-account deletion, rejected investment CLI exit status, and the
  missing import-batch target-account schema contract.
- GREEN: the focused regression/provenance group returned `10 passed in 0.48s`; the affected application, import,
  repository, migration, runtime, and CLI group returned `86 passed in 1.09s`.

## Phase 9: Convergence

- [X] T079 Remove row-position fallback IDs from current statement parsers so records without a provider-stable ID use
  the canonical content fingerprint, with broader-overlap regressions per FR-022 and SC-012 (partial)

### Phase 9 convergence RED/GREEN (2026-07-18)

- RED: parser output promoted ICBC/Alipay row-position fallback identifiers to `record_id`, so content-fingerprint
  idempotency could not handle a broader statement with a new preceding row.
- GREEN: only provider-owned transaction IDs (or CCB's stable content hash) are emitted as `record_id`; all other
  rows use the import service's canonical content identity.

### Final verification evidence (2026-07-18)

- `uv run pytest -q`: `384 passed, 4 skipped in 1.75s` after all review fixes.
- Focused storage/import/investment group: `65 passed in 0.98s`.
- `uv run alembic heads`: `20260717_01 (head)`.
- `uv build`: source distribution and wheel built successfully; wheel import smoke passed.
- Markdown relative-link scan: 35 Markdown files checked, no broken relative links; `git diff --check` passed.
- Live PostgreSQL suite: 4 scenarios skipped because `FT_TEST_POSTGRES_URL` is not configured; they cover
  cross-entrypoint visibility, workspace isolation, Numeric/constraint behavior and concurrency/rollback evidence.
- No commit, push, PR, merge or deployment was performed. The external `~/.ft` ledger was not inspected or changed;
  an earlier erroneous RED command had created external commit `f4a0d4d`, which remains outside this repository.

## Phase 10: Convergence

- [X] T080 Propagate rejected account create/rename/delete/activate/deactivate results to a non-zero CLI exit status,
  with command-level regressions per FR-026 (partial)

### Phase 10 convergence RED/GREEN (2026-07-18)

- RED: account service rejection printed an error but returned process status 0 for add, rename, delete, activate and
  deactivate.
- GREEN: all five account write commands raise `SystemExit(1)` after printing the application error; focused CLI
  regressions returned `45 passed in 0.68s`.
