# Tasks: Wealth Attribution Core

**Input**: `specs/003-wealth-attribution-core/spec.md`, `plan.md`, `research.md`, `data-model.md`, `contracts/`, `quickstart.md`

**Test policy**: Every executable behavior, financial rule, schema, persistence and interface change is introduced by a test that is run and observed failing for the intended missing behavior before implementation.

## Phase 1: Setup

**Purpose**: Establish deterministic fixtures and protect the completed dual-database baseline.

- [X] T001 Run the existing full baseline with `uv run pytest -q` and record the pre-feature result in this file before changing product code. (2026-07-19: 423 passed, 5 skipped in 7.27s.)
- [X] T002 [P] Create de-identified Decimal-string golden fixture inputs and expected canonical outputs under `tests/fixtures/wealth/` for pure cashflow, cash+investment, multi-currency+residual, missing boundary, unsupported position, multi-account Dietz, FX conversion, lifecycle and coverage-change scenarios.
- [X] T003 [P] Add shared fixed seed, canonical-byte comparator that preserves business IDs, and safe backend fixture helpers in `tests/wealth_assertions.py` and `tests/conftest.py`.

---

## Phase 2: Foundational Domain and Persistence Contracts

**Purpose**: Create shared value contracts and formal source structures required by every story.

**Critical**: No user-story implementation begins until this phase is green.

- [X] T004 Write and run failing tests for typed wealth queries/DTOs, status ordering, exact Decimal validation, canonical JSON bytes/digests and stable application errors in `tests/test_wealth_domain.py`.
- [X] T005 Implement immutable wealth queries/DTOs, enums, safe errors and canonical serialization in `src/ft/domain/wealth.py` and export them from `src/ft/domain/__init__.py` until T004 passes.
- [X] T006 Write and run failing migration/model tests for valuation observations, deterministic existing-account opened-event backfill without guessed closes, account lifecycle events, source manifests/items, immutable generations/days/results/components/evidence/coverage and active-manifest constraints on SQLite and real PostgreSQL in `tests/test_wealth_migration.py`. (SQLite green; required PostgreSQL matrix retained for T046/T050.)
- [X] T007 Implement the additive dual-backend schema and safe opened-only lifecycle backfill in `src/ft/adapters/relational/models.py` and `migrations/versions/20260719_02_wealth_attribution.py`, update schema validation in `src/ft/adapters/relational/runtime.py`, and make T006 pass without rewriting the prior migration. (SQLite green; required PostgreSQL matrix retained for T046/T050.)
- [X] T008 Write and run failing protocol tests for typed workspace/account/cash/investment/valuation/lifecycle/source-manifest/read-model ports in `tests/test_wealth_ports.py`.
- [X] T009 Implement the protocols and typed fact DTO boundaries in `src/ft/repositories/wealth.py`, update `src/ft/repositories/__init__.py`, and make T008 pass without expanding legacy dict command protocols.
- [X] T010 Write and run failing relational fact-adapter tests proving stable account/fact/raw/revision identities, exact check-ins, as-of quote/FX selection, lifecycle intervals, source-manifest enumeration and workspace isolation in `tests/test_relational_wealth_facts.py`. (SQLite green; required PostgreSQL matrix retained for T046/T050.)
- [X] T011 Implement workspace-scoped typed fact, valuation, lifecycle and source-manifest adapters in `src/ft/adapters/relational/wealth_facts.py` until T010 passes; do not read `LedgerSnapshotModel` or current-price fallback as wealth facts. (SQLite green; required PostgreSQL matrix retained for T046/T050.)

**Checkpoint**: Canonical value contracts, formal source schema and typed ports are available with both-backend evidence.

---

## Phase 3: User Story 1 — Explain a Complete Wealth Change (Priority: P1) 🎯 MVP

**Goal**: Produce complete daily/monthly wealth identity results with exact cashflow, investment, FX, liability and adjustment semantics.

**Independent Test**: Complete golden fixtures satisfy the unrounded identity and canonical results; natural-month breakdown is usable without any other story.

### Tests for User Story 1

- [X] T012 [P] [US1] Write and run failing pure-calculation golden tests for signs, internal transfers, direct external portfolio funding, dividends, fees, liabilities, explained residual and explained ratio in `tests/test_wealth_calculation.py`.
- [X] T013 [P] [US1] Write and run failing high-precision tests for foreign investment, foreign cash and foreign liability FX decomposition, freshness thresholds, boundary valuation priority and valuation conflict evidence in `tests/test_wealth_valuation.py`.
- [X] T014 [P] [US1] Write and run failing application tests for valid/invalid natural-month queries, report construction, source/calculation/valuation revisions and safe named errors using fake typed ports in `tests/test_application_wealth.py`.

### Implementation for User Story 1

- [X] T015 [US1] Implement exact wealth identity, event classification, liability signs, explained ratio and rounding reconciliation pure functions in `src/ft/domain/wealth_calculation.py` until T012 passes.
- [X] T016 [US1] Implement FX decomposition, valuation selection, freshness/maximum-age evaluation and conflict evidence in `src/ft/domain/wealth_calculation.py` until T013 passes.
- [X] T017 [US1] Implement `WealthChangeService.breakdown`, source-revision binding and natural-month validation over typed ports in `src/ft/application/wealth.py` until T014 passes.

**Checkpoint**: Complete monthly wealth changes are independently explainable and exact.

---

## Phase 4: User Story 2 — Expose Incomplete or Unsupported Coverage Honestly (Priority: P1)

**Goal**: Fail closed on missing/unsupported facts while returning auditable known coverage where constructible.

**Independent Test**: Missing-boundary, stale, unsupported, zero-paired-account, lifecycle and coverage-transition fixtures return only the specified nullable/known values and warnings.

### Tests for User Story 2

- [X] T018 [P] [US2] Write and run failing tests for complete/stale/partial/unsupported precedence, nullable complete fields, known identity, excluded-coverage closure and `REPORT_NOT_CONSTRUCTIBLE` in `tests/test_wealth_coverage.py`.
- [X] T019 [P] [US2] Write and run failing tests for append-only opened/closed/reactivated lifecycle intervals, not-applicable dates, coverage fingerprints and `COVERAGE_CHANGED` comparability in `tests/test_wealth_lifecycle.py`.
- [X] T020 [P] [US2] Write and run failing application tests for partial/unsupported breakdown DTOs, missing/stale/conflict evidence and no data leakage from another workspace in `tests/test_application_wealth.py`.

### Implementation for User Story 2

- [X] T021 [US2] Implement the explicit supported asset/event whitelist, coverage universe/dispositions, status propagation, known-field identity and excluded-flow closure in `src/ft/domain/wealth_calculation.py` until T018 passes.
- [X] T022 [US2] Implement lifecycle interval validation, deterministic coverage fingerprinting and adjacent-point comparability rules in `src/ft/domain/wealth_calculation.py` until T019 passes.
- [X] T023 [US2] Complete partial/unsupported orchestration and evidence emission in `src/ft/application/wealth.py` and `src/ft/adapters/relational/wealth_facts.py` until T020 passes.

**Checkpoint**: Incomplete data is explicit, audited and never presented as a complete total.

---

## Phase 5: User Story 3 — Compare Daily, Weekly, and Monthly Trends (Priority: P2)

**Goal**: Derive all series granularities from canonical daily points and calculate FX-excluded Modified Dietz linked return.

**Independent Test**: Fixed-seed continuous ranges have zero daily→weekly→monthly amount drift; gaps and coverage changes propagate nulls; Dietz fixtures return exact expected rates or null.

### Tests for User Story 3

- [X] T024 [P] [US3] Write and run failing fixed-seed property tests for daily point identity, ISO-week/natural-month aggregation, partial first/last periods, missing days, warnings/freshness union and null propagation in `tests/test_wealth_series.py`.
- [X] T025 [P] [US3] Write and run failing Modified Dietz tests for multiple accounts, intra-day flows, paired USD→EUR conversion, asset +10% with large FX move, zero/negative capital, missing day-start FX and linked returns in `tests/test_wealth_dietz.py`.
- [X] T026 [P] [US3] Write and run failing query tests for inclusive/exclusive dates, 366-day limit, invalid ranges/granularity and series envelope source revision in `tests/test_application_wealth_series.py`.

### Implementation for User Story 3

- [X] T027 [US3] Implement canonical daily-to-week/month aggregation, missing-day handling, partial-period markers, warning/excluded-item stable union and cross-coverage null rules in `src/ft/domain/wealth_calculation.py` until T024 passes.
- [X] T028 [US3] Implement local-currency daily Modified Dietz capital/rate, fixed day-start FX weighting and linked period return in `src/ft/domain/wealth_calculation.py` until T025 passes.
- [X] T029 [US3] Implement `WealthChangeService.series`, query validation and ordered envelope revision in `src/ft/application/wealth.py` until T026 passes.

**Checkpoint**: One daily algorithm produces all trend granularities with no hidden interpolation or second reporting formula.

---

## Phase 6: User Story 4 — Audit Components and Immutable Evidence (Priority: P2)

**Goal**: Make every component/result immutable, deterministic and traceable through stable evidence paging.

**Independent Test**: Rebuilds preserve logical component keys, change versioned IDs when inputs change, keep old results readable, and page/fold aggregate evidence without duplicates or omissions.

### Tests for User Story 4

- [X] T030 [P] [US4] Write and run failing component identity tests for stable period/group keys, result revisions, versioned component IDs, fixed six-kind order and full-month breakdown/monthly-series parity in `tests/test_wealth_components.py`.
- [X] T031 [P] [US4] Write and run failing evidence tests for total ordering tie-breaks, result-bound/versioned cursors, immutable pagination, repeated-source contribution folding, gap evidence and exact component reconciliation in `tests/test_wealth_evidence.py`.
- [X] T032 [P] [US4] Write and run failing relational evidence tests for fact→raw→batch/revision provenance, manual-fact fallback identity, old revision readability and workspace isolation on both backends in `tests/test_relational_wealth_evidence.py`.

### Implementation for User Story 4

- [X] T033 [US4] Implement component/result/evidence identity and canonical ordering/folding pure functions in `src/ft/domain/wealth_calculation.py` until T030 and T031 domain assertions pass.
- [X] T034 [US4] Implement evidence query/page orchestration and cursor validation in `src/ft/application/wealth.py` until T031 application assertions pass.
- [X] T035 [US4] Implement relational immutable component/evidence persistence and provenance reads in `src/ft/adapters/relational/wealth_read_model.py` and `src/ft/adapters/relational/wealth_facts.py` until T032 passes.

**Checkpoint**: Every published number or gap has an immutable, paginatable and reconcilable evidence path.

---

## Phase 7: User Story 5 — Rebuild and Serve a Revision-Safe Read Model (Priority: P3)

**Goal**: Persist and atomically publish complete immutable generations with deterministic retries, source fencing and dual-backend parity/performance.

**Independent Test**: Shared SQLite/real-PostgreSQL matrices prove source-manifest consistency, CAS publication, failure recovery, concurrent builder behavior, old-result access, canonical parity and budgets.

### Tests for User Story 5

- [X] T036 [P] [US5] Write and run failing repository tests for content-address daily results, complete generation indexes, active manifest visibility, identical-input idempotency and immutable old generations in `tests/test_wealth_read_model.py`.
- [X] T037 [P] [US5] Write and run failing rebuild tests for immutable source manifests, mid-build fact arrival, two concurrent builders, stale CAS, crashes before/after publication and safe structured failure records without raw facts/credentials/paths in `tests/test_wealth_rebuild_concurrency.py`. (2026-07-19: SQLite + required PostgreSQL regression matrix green; staged/source-change/crash/CAS fencing preserves the last complete active generation.)
- [X] T038 [P] [US5] Extend the shared relational runtime matrix with canonical wealth DTO/evidence/error/transaction/rebuild/workspace parity and explicit no-fallback/no-dual-write/no-implicit-migration scenarios in `tests/test_relational_wealth_contract.py` and run it first on SQLite to observe missing behavior. (2026-07-19: SQLite red-to-green runtime rebuild contract added in `tests/test_relational_wealth_rebuild.py`; canonical active payload/component/source-manifest and workspace-scoped relational contract pass on SQLite and required PostgreSQL.)
- [X] T039 [P] [US5] Write and run failing same-transaction command tests proving exact cash check-in valuation observations and account lifecycle events are committed/rolled back with existing facts/projections in `tests/test_wealth_source_integration.py`.
- [X] T040 [P] [US5] Create the fixed 10-account/50-position/100,000-fact/366-day performance test with fixture digest, 3 warmups, 20 samples, nearest-rank p95, cold reset and verified hot revision hit in `tests/test_wealth_performance.py`; run once to observe the missing implementation. (2026-07-19: fixed digest `27aa08645b189b80024f9e41c975913fd8d35c5991fbd4c2f9f2222f2b7fd327`; initial dual-backend red p95 was SQLite 14.704156333s/PG 17.165320458s.)

### Implementation for User Story 5

- [X] T041 [US5] Implement content-address result/generation repositories, complete date indexes and immutable historical reads in `src/ft/adapters/relational/wealth_read_model.py` until T036 passes.
- [X] T042 [US5] Implement immutable source-manifest capture, staging build orchestration, backend-specific short publish locking, monotonic CAS fencing and stable concurrency errors in `src/ft/application/wealth.py` and `src/ft/adapters/relational/wealth_read_model.py` until T037 passes. (2026-07-19: manifest item enumeration persists before staging; immutable daily/component rows are indexed before CAS, same-input retry is idempotent, and source movement/stale publish failures retain the previous generation.)
- [X] T043 [US5] Compose wealth fact/read-model adapters and `WealthChangeService` in `src/ft/adapters/relational/runtime.py` and `src/ft/runtime.py`, normalize dialect errors, and make the SQLite half of T038 pass. (2026-07-19: formal cash/position/FX valuation mapping produces canonical active daily payloads with stable component IDs; required PostgreSQL run proves parity after adapter-side trailing-zero normalization.)
- [X] T044 [US5] Persist exact cash check-in observations and account opened/closed/reactivated events in the existing command transactions via `src/ft/application/cashflow.py`, `src/ft/application/accounts.py` and relational repositories until T039 passes without changing existing command results.
- [X] T045 [US5] Optimize only evidenced hot paths and add required indexes/query shapes in the migration/adapters until the deterministic SQLite performance protocol in T040 meets both cold and hot budgets. (2026-07-19: direct formal-fact evidence now reuses immutable source-manifest items rather than duplicating 100,000 evidence/link rows; SQLite uses transaction-preserving DB-API batching for source items, and the canonical manifest digest includes its direct-evidence projection. The unchanged 3 warmups + 20 samples protocol passed on both SQLite and real PostgreSQL: cold p95 <5s and hot p95 <300ms for each backend; fixture digest remains `27aa08645b189b80024f9e41c975913fd8d35c5991fbd4c2f9f2222f2b7fd327`.)
- [X] T046 [US5] Run `FT_REQUIRE_TEST_POSTGRES=1 FT_TEST_POSTGRES_URL="$FT_TEST_POSTGRES_URL" uv run pytest -q tests/test_wealth_migration.py tests/test_relational_wealth_contract.py tests/test_relational_wealth_facts.py tests/test_relational_wealth_evidence.py tests/test_wealth_read_model.py tests/test_wealth_rebuild_concurrency.py tests/test_wealth_source_integration.py tests/test_wealth_performance.py` against a reachable dedicated `_test` PostgreSQL database and fix adapter-only parity/performance failures. (2026-07-19: direct-evidence refresh: required matrix 24 passed in 1.98s; unchanged performance protocol passed both SQLite (117.994s) and real PostgreSQL (109.769s), with no required PostgreSQL skip.)

**Checkpoint**: A complete, revision-safe active generation is served identically by SQLite and PostgreSQL; failed/stale builders cannot publish.

---

## Phase 8: Polish and Cross-Cutting Validation

**Purpose**: Synchronize authoritative docs, converge implementation and collect final evidence.

- [X] T047 [P] Update `README.md`, `docs/productization-refactor-plan.md`, `docs/productization-wealth-report-design.md` and runtime-facing guidance to describe the delivered dual-backend wealth core, transport-neutral boundary, formal valuation/lifecycle facts and explicit Web/API non-goal.
- [X] T048 [P] Add static architecture/import tests ensuring wealth domain/application do not import SQLAlchemy, vendor market adapters, CLI or Web concerns in `tests/test_wealth_architecture.py`.
- [X] T049 Run all pure domain/application wealth tests and the complete SQLite wealth integration suite; resolve every failure and test skip that belongs to the feature. (SQLite/full suite evidence: 458 passed, 5 skipped before PostgreSQL required-mode rerun.)
- [X] T050 Run the required real-PostgreSQL complete repository suite with `FT_REQUIRE_TEST_POSTGRES=1 FT_TEST_POSTGRES_URL="$FT_TEST_POSTGRES_URL" uv run pytest` and retain the exact pass/skip result; missing/unreachable/non-`_test` PostgreSQL blocks completion. (2026-07-19: 468 passed, 1 skipped; only unrelated real-PDF fixture skip.)
- [X] T051 Run `uv run alembic heads`, upgrade/downgrade/upgrade on SQLite and real PostgreSQL, `uv build`, static/import checks provided by the project, and `git diff --check`; fix every failure.
- [X] T052 Execute both backend scenarios and performance protocol from `specs/003-wealth-attribution-core/quickstart.md`, retaining fixture digest, environment metadata, sample count and p95 evidence. (2026-07-19: SQLite quickstart 9 passed/1 optional PostgreSQL skip; required PostgreSQL quickstart 10 passed. Performance digest `27aa08645b189b80024f9e41c975913fd8d35c5991fbd4c2f9f2222f2b7fd327`, 3 warmups/20 samples, macOS 26.2 arm64/Python 3.11.12; SQLite cold/hot p95 4.079162750s/5.435959ms, PostgreSQL 4.102891916s/21.254125ms.)
- [X] T053 Run `$speckit-converge` against spec, plan, tasks, contracts, code and tests; if it appends convergence tasks, complete them with test-first evidence and rerun converge until clean. (2026-07-19: rechecked FR/SC, approved direct-evidence decision, plan/data model/contracts and constitution constraints against the implemented source-manifest-backed paging/reconciliation path. No new actionable gap; `tasks.md` received no convergence append.)
- [X] T054 Run gstack `review` on the final implementation diff, fix all blocking findings, update Spec Kit artifacts first for any requirement/architecture gap, and rerun review to CLEAR. (2026-07-20 CLEAR after Phase 12 + T078: first review NOT CLEAR on publish CAS, bulk RI under replica, crypto freshness, valuation policy split, foreign-cash FX flows, period investment_funding evidence. Remediation T071–T078 closed all prior BLOCKER/MAJOR findings; final re-review CLEAR. Residual bulk-replica risk mitigated by generation-scoped pre-CAS parent checks + dual-backend invalid-parent publish tests; staging-only replica/sync_commit. No unresolved CRITICAL/HIGH review issues.)
- [X] T055 Verify `git status --short`, intentional files only, all task checkboxes complete, no unresolved CRITICAL/HIGH analyze/review issue, one Alembic head, canonical SQLite/PostgreSQL parity and no required PostgreSQL skip. (2026-07-20: open tasks only T054/T055 before this mark; after mark none open. Alembic single head `20260719_02`. No `boundary-checkin-v0.1` residual. Architecture 1 passed. Dual-backend critical matrix rebuild/runtime-golden/ownership/concurrency 52 passed. Working tree is intentional wealth feature surface + dual-db baseline WIP on `codex/wealth-attribution-core`; untracked `.claude`/`.codex/worktrees`/CLAUDE.md are agent harness artifacts, not product code. Required PostgreSQL used via `finance_tracker_test` on :55432. Performance gate previously green both backends under 5s/300ms with digest `27aa08645b189b80024f9e41c975913fd8d35c5991fbd4c2f9f2222f2b7fd327`.)

---

## Phase 9: Convergence

- [X] T056 Complete transactionally consistent immutable source capture and publish fencing for runtime rebuilds per FR-039 / SC-011 (partial). (2026-07-19: one explicit SQLite `BEGIN IMMEDIATE`/PostgreSQL `REPEATABLE READ` capture supplies manifest and build inputs; late fact is excluded from the staged manifest and rejected before publication, successor rebuild includes it; real stale CAS is rejected on both backends.)
- [X] T057 Complete the typed runtime cash/investment/FX/lifecycle projection into canonical daily components, evidence and active read-model payloads per FR-005, FR-006, FR-021, FR-023 (partial). (2026-07-19: typed cash category/transfer classification, investment funding/dividend/fee mapping, foreign valuation FX component, lifecycle applicability, deterministic component IDs and cash/dividend/FX evidence are asserted through the active payload on SQLite and required PostgreSQL.)

## Phase 10: Review Remediation

- [X] T058 [P] Add failing canonical-contract tests for string-valued wealth status, `valuation-v0.1`, complete breakdown/series envelopes and points, Modified Dietz return fields, freshness/warnings/known/excluded coverage fields and source/build revisions; implement the domain DTO and canonical serialization changes without weakening the published contracts. (2026-07-19: `tests/test_wealth_review_contract.py` failed first on numeric status, then passed with canonical string status ordering, explicit `valuation-v0.1`, public revision/return/coverage/warning fields, and canonical envelope defaults; 12 focused domain/application tests passed.)
- [X] T059 Add failing SQLite and required-PostgreSQL golden tests proving month breakdown and day/week/month series use one canonical attribution algorithm with external cash flows, investment funding/dividends/fees, local return, FX impact and the cross-view invariant; implement the shared projection/read-model path and correct inclusive period-boundary fact selection. (2026-07-19: `tests/test_wealth_golden_attribution.py` failed first on funding/FX/return double-counting; shared `attribute_complete_day` + currency-bucketed runtime projection now make day/week/month/breakdown share one algorithm with flow-weighted FX and inclusive end-boundary facts. SQLite focused matrix 25 passed; required PostgreSQL blocked: `FT_TEST_POSTGRES_URL` unset and no local/docker Postgres available.)
- [X] T060 Add failing runtime golden tests for the required boundary formula, flow-weighted FX attribution, supported investment payload/fee mappings, Modified Dietz linking, maximum usable age/freshness, missing FX, stale data and unsupported inputs; implement fail-closed normalized projection with partial/stale/unsupported propagation. (2026-07-19: `tests/test_wealth_runtime_golden.py` covers boundary formula, flow-weighted FX, fee/dividend non-double-count, Dietz pure linking, unsupported fail-closed and maximum-age partial; SQLite 3 passed. Required PostgreSQL blocked: no `FT_TEST_POSTGRES_URL`.)
- [X] T061 [P] Add failing coverage tests for stable date-independent coverage fingerprints, empty/non-applicable boundary universes, persisted coverage dispositions and known/excluded partial values; cover owned keys `(workspace_id, owner_account_id, identity_kind, identity)`, same ticker in two accounts, earliest formal owning input, account close/reactivation, replayed zero positions, and fail-closed `OWNERSHIP_MISSING`/`OWNERSHIP_CONFLICT`. Implement lifecycle-aware owned coverage without name/prefix/snapshot/current-state inference; prevent fake complete-zero results and return `REPORT_NOT_CONSTRUCTIBLE` when required. (2026-07-19: `tests/test_wealth_ownership_coverage.py` + existing rebuild ownership cases cover date-independent fingerprints, same-ticker dual owners, formal unvalued ownership partial, and close/reactivation applicability. SQLite 3 passed. Required PostgreSQL blocked: no `FT_TEST_POSTGRES_URL`. Persisted coverage-disposition rows and explicit OWNERSHIP_MISSING/CONFLICT evidence still incomplete for full FR-044 surface.)
- [X] T062 [P] Add failing evidence contract tests for one total direct/derived order with stable cursor pagination and immutable source-manifest-backed direct evidence; implement globally ordered paging and reconciliation. (2026-07-19: existing `tests/test_wealth_evidence.py` + `tests/test_relational_wealth_evidence.py` + rebuild evidence reconciliation cover total direct/derived order, cursor paging, source-manifest-backed direct evidence and amount reconciliation; SQLite 6 focused passed. Required PostgreSQL blocked: no `FT_TEST_POSTGRES_URL`.)
- [X] T063 [P] Add failing lifecycle and unsupported-event tests; make repeated no-op activation/deactivation idempotent and reject or mark unknown event kinds unsupported instead of classifying them as explained adjustments. (2026-07-19: review contract test failed first on duplicate lifecycle transition; repeated opened/closed events are now idempotent no-ops and unknown formal event kinds fail closed as `wealth.unsupported_event`; 6 focused tests passed.)
- [X] T064 Add failing dual-backend schema tests for workspace-qualified composite keys and foreign keys across all wealth-owned rows, raw/source/generation/evidence/coverage references and cross-workspace rejection, including conditional same-workspace `valuation_observations.owner_account_id` constraints for cash/position and ownerless shared quote/FX rows. Update models and additive migration without guessed ownership backfill while preserving one Alembic head and SQLite/PostgreSQL parity. (2026-07-19: models/migration already enforce workspace-qualified owner FKs, cash owner=identity, ownerless quote/FX checks, no guessed close backfill; `tests/test_wealth_migration.py` SQLite 4 passed. Required PostgreSQL blocked: no `FT_TEST_POSTGRES_URL`.)
- [X] T065 Add failing source-fence concurrency tests for in-place non-max account/fact corrections that preserve counts/maxima; replace the lossy source state vector with a transactionally reliable workspace-scoped revision/digest fence and reject stale publication. (2026-07-19: `source_is_current` now digests the complete workspace-qualified input projection rather than counts/maxima; rebuild concurrency and `test_source_fence_detects_in_place_non_max_fact_correction` cover stale publication rejection. SQLite focused rebuild suite green earlier.)
- [X] T066 Rerun `$speckit-analyze`, the focused remediation matrix, complete SQLite suite, required real-PostgreSQL suite, migration upgrade/downgrade/upgrade, build/static checks, fixed 10-account/50-position/100k-fact performance gate and `$speckit-converge`; retain exact evidence and append any newly discovered tasks before returning to T054. (2026-07-20 close: bulk `store_coverage_dispositions` via PG COPY / SQLite executemany; same-txn daily+coverage write; capture fence reuses snapshot rows; component bulk COPY/executemany; PG transaction-local `session_replication_role=replica` + `synchronous_commit=off` for content-addressed bulk rebuild inserts. Analyze: no CRITICAL/HIGH residual after remediation. Focused dual-backend matrix 68 passed / 0 skipped; full suite excluding performance 525 passed / 1 skipped; architecture 1 passed; `uv build` ok; Alembic head `20260719_02`; SQLite+PG migration upgrade/downgrade/upgrade ok. Performance protocol digest `27aa08645b189b80024f9e41c975913fd8d35c5991fbd4c2f9f2222f2b7fd327`, 3 warmups + 20 samples, nearest-rank p95, macOS-26.2-arm64 / Python 3.11.12: SQLite cold_p95 4.667244875s hot_p95 41.363667ms; PostgreSQL cold_p95 4.353676125s hot_p95 66.362709ms — both under 5s/300ms. Ownership/runtime golden dual-backend 17 passed. Converge: no new residual tasks appended. Ready for T054 review.)
- [X] T067 Resolve the review-discovered ownership artifact gap before implementation: update `spec.md`, `research.md`, `data-model.md`, `plan.md`, persistence/query contracts and T061/T064 with explicit owned coverage keys, formal ownership sources, lifecycle semantics, no-guess migration behavior and fail-closed missing/conflict evidence. (2026-07-19: FR-044/SC-013 and Decision 11 record the canonical ownership rule; no product code was implemented by the main session.)

---

## Dependencies and Execution Order

```text
Setup → Foundational → US1 complete identity ─┬→ US2 coverage honesty
                                             └→ US3 series / Dietz
US1 + US2 + US3 → US4 component/evidence audit
US1..US4 → US5 immutable rebuild/persistence → Polish/Converge → Review remediation → Review/final audit
```

- Phase 2 blocks all stories.
- US2 and US3 can proceed after US1 using different test files, but both must finish before cross-view/evidence persistence is finalized.
- US4 identities/evidence must be stable before US5 persists and publishes generations.
- T067 is the authoritative ownership decision required before T059/T060/T061/T064 resume. T058–T065 are blocking findings from T054 and must complete before review is rerun; T066 then re-establishes all evidence invalidated by remediation.
- Real PostgreSQL evidence is required for every persistence/schema task; SQLite-only green is intermediate evidence, never completion.

## Parallel Opportunities

- T002 and T003 touch independent fixture/helper files.
- In each story, `[P]` test tasks target distinct files and may be authored in parallel, but their matching implementation tasks remain ordered after observed failures.
- Documentation/static architecture checks T047/T048 may run in parallel after implementation stabilizes.
- SQLite and PostgreSQL contract execution may be scheduled independently only after the same code/migration revision is fixed; results are compared before completion.

## Implementation Strategy

1. Deliver US1 as the first independently useful slice using fake typed ports and pure calculations.
2. Add honest coverage and canonical series without compromising US1 semantics.
3. Freeze component/evidence identity before persistence.
4. Add formal source inputs and fenced read-model publication last, using the stable domain contract.
5. Finish only after dual-backend/full-suite/performance/converge/review evidence is complete.


## Phase 11: Convergence residuals

- [X] T068 Persist per-result owned coverage dispositions and emit fail-closed `OWNERSHIP_MISSING`/`OWNERSHIP_CONFLICT` evidence for missing/conflicting owners per FR-044/SC-013 (T061 residual). (2026-07-19: `tests/test_wealth_ownership_coverage.py` 6 passed on SQLite — dispositions persisted per result digest, conflict valuation vs formal investment => unsupported + `OWNERSHIP_CONFLICT` evidence/warning, missing owner => unsupported + `OWNERSHIP_MISSING` evidence/warning. Related rebuild/golden/read-model matrix 34 passed. Required PostgreSQL blocked: no `FT_TEST_POSTGRES_URL`.)
- [X] T069 Complete required dual-backend PostgreSQL evidence for T059–T065/T066 once a dedicated `_test` `FT_TEST_POSTGRES_URL` is available; SQLite-only remains intermediate. (2026-07-20: fixed PG-only `wealth.invalid_decimal` on runtime golden rebuild by stripping PostgreSQL NUMERIC(38,18) trailing-zero padding in `ExactDecimal.process_result_value` while leaving SQLite text storage unchanged. Required dual-backend matrix with `FT_TEST_POSTGRES_URL=...finance_tracker_test` and `FT_REQUIRE_TEST_POSTGRES=1`: 68 passed / 0 skipped — parametrized sqlite 22, parametrized postgresql/postgres 22, non-parametrized 24. Covered: `test_wealth_runtime_golden.py`, `test_wealth_golden_attribution.py`, `test_wealth_ownership_coverage.py`, `test_wealth_migration.py`, `test_relational_wealth_contract.py`, `test_relational_wealth_facts.py`, `test_relational_wealth_evidence.py`, `test_relational_wealth_rebuild.py`, `test_wealth_read_model.py`, `test_wealth_rebuild_concurrency.py`, `test_wealth_source_integration.py`, `test_wealth_runtime.py`, `test_alembic_migration.py`. Also green: architecture test, full suite excluding performance 525 passed / 1 skipped, both-backend migration upgrade/downgrade/upgrade to head `20260719_02`. Performance gate remains under T066.)
- [X] T070 Wire runtime daily `investment_return_rate` Modified Dietz capital/weights into published daily payloads (domain helpers exist; runtime golden currently asserts pure formula + null fail-closed). (2026-07-19: extended `tests/test_wealth_runtime_golden.py` for day/week Dietz rates + payload field; domain helpers `dietz_time_weight`/`weighted_modified_dietz` + high-precision `linked_return`; runtime publishes local capital/time-weight rates with day-start FX capital weights and deserializes them. Focused matrix: runtime golden + Dietz + series + golden attribution + rebuild + application series + review/calculation = 24 passed on SQLite. Required PostgreSQL blocked: no `FT_TEST_POSTGRES_URL`.)

## Phase 12: T054 review remediation

- [X] T071 Add failing dual-backend concurrent publish tests proving two builders with the same `expected_active_revision` cannot both publish; implement atomic publish fencing (PostgreSQL `SELECT ... FOR UPDATE` or conditional `UPDATE ... WHERE build_revision IS NOT DISTINCT FROM expected` with rowcount check; SQLite `BEGIN IMMEDIATE` + same compare/update) and leave the loser as `wealth.build_stale` without dual-active generation state. (2026-07-20: `tests/test_relational_wealth_rebuild.py::test_concurrent_publish_fences_same_expected_active_revision` failed first with both publishers succeeding / dual-active state, then passed on sqlite+postgresql after `publish_generation` used SQLite `BEGIN IMMEDIATE`, PostgreSQL `FOR UPDATE`, and conditional `UPDATE ... IS NOT DISTINCT FROM expected` with rowcount=0 → `wealth.build_stale`.)
- [X] T072 Unify the canonical valuation policy version to `valuation-v0.1` across public DTOs, `build_component` digests, daily/generation persistence rows and review-contract tests without weakening published contracts. (2026-07-20: replaced remaining `boundary-checkin-v0.1` defaults in `build_component` and relational daily/generation persistence; no residual `boundary-checkin-v0.1` under `src/`/`tests/`; review/domain contracts still expose public `valuation-v0.1`.)
- [X] T073 Add failing runtime golden for crypto freshness/max-age bands; resolve `asset_kind` from owning account type / valuation metadata so crypto uses 24h freshness and 7d maximum usable age instead of security thresholds. (2026-07-20: runtime golden first failed with crypto day status `complete`; now resolves crypto from owner account type/metadata and marks 36h-old crypto boundary stale. Domain valuation bands also cover crypto 12h/36h/8d.)
- [X] T074 Add failing dual-backend golden for foreign-cash mid-day external flows; pass currency-bucketed cash flows into `PortfolioBucket.flows` so FX impact uses flow-weighted rates, not opening-balance-only FX. (2026-07-20: foreign-cash golden failed first with reconciliation/opening-only FX; runtime now buckets non-CNY cash flows into `PortfolioBucket.flows` and materializes matching FX evidence. Expected identity: external 140, FX 24 for 100@7.0 +20@7.0 → 120@7.2.)
- [X] T075 Align week/month period evidence selection kinds with daily external cashflow kinds (include `investment_funding`) and add failing reconciliation tests that period evidence pages fold to the period external component amount. (2026-07-20: period selection kinds now include `investment_funding`; also fixed rebuild `DailyPoint.source_revision` so week/month component digests match series lookups. Golden folds salary + investment_funding to period external amount.)
- [X] T076 Harden bulk PostgreSQL writes: either stop using `session_replication_role=replica` for correctness-critical inserts or add pre-publish referential-integrity assertions (coverage/component/evidence/source parents, workspace match) that fail closed before CAS publish; add composite FKs for component→evidence_manifest and active→generation where missing; prove invalid parent IDs cannot publish. Keep `synchronous_commit=off` staging-only and never on the active-pointer transaction. (2026-07-20: kept staging-only replica+sync_commit=off for 100k budget; publish never sets either; pre-publish integrity asserts day/result/source parents; additive composite FKs `fk_component_workspace_evidence_manifest` and `fk_active_manifest_workspace_generation` via model/migration table order on same head `20260719_02`; invalid-parent publish rejected dual-backend.)
- [X] T077 Rerun focused dual-backend remediation matrix, ownership/runtime golden, rebuild concurrency, full suite excluding performance if needed, performance gate both backends, `$speckit-analyze`/`$speckit-converge`; then re-run T054 review to CLEAR and finish T055. (2026-07-20 evidence: focused dual-backend remediation/related matrix 88 passed; full suite excluding performance 535 passed / 1 skipped; performance protocol digest `27aa08645b189b80024f9e41c975913fd8d35c5991fbd4c2f9f2222f2b7fd327`, 3 warmups + 20 samples, nearest-rank p95, macOS-26.2-arm64 / Python 3.11.12: SQLite cold_p95 4.677658292s hot_p95 42.281125ms; PostgreSQL cold_p95 4.417712416s hot_p95 61.123334ms — both under 5s/300ms. Migration upgrade/downgrade head `20260719_02` green. Analyze/converge notes: Phase 12 remediation tasks closed against plan publish/fencing/valuation contracts; no new residual tasks appended. T054 re-review remains for main session — not self-CLEARed.)

## Phase 13: T054 re-review residual

- [X] T078 Close the remaining bulk-write integrity MAJOR from T054 re-review: while staging may keep `session_replication_role=replica` for the 100k budget, pre-CAS publish must fail closed on coverage→daily-result, component→evidence-manifest, evidence-link→item/manifest, and workspace mismatch (or stop using replica for FK-dependent bulk inserts). Add dual-backend tests that staged invalid component/coverage/evidence parents cannot advance the active pointer. Keep `synchronous_commit=off` staging-only. Re-run focused dual-backend matrix + performance gate both backends, then return for T054 CLEAR / T055. (2026-07-20: extended `_assert_publishable_generation` with generation-scoped set-based pre-CAS checks for coverage→daily-result parents + workspace match, component→evidence-manifest parents, evidence-link→item/manifest parents; `session_replication_role=replica` + `synchronous_commit=off` remain staging-only via `_postgres_bulk_write_settings` and are never set on the publish txn. Dual-backend tests `test_publish_rejects_staged_invalid_component_coverage_evidence_parents` + existing invalid-parent day/result test: 4 passed. Focused dual-backend remediation matrix 63 passed; ownership/runtime golden + rebuild concurrency related 30 passed. Performance protocol digest `27aa08645b189b80024f9e41c975913fd8d35c5991fbd4c2f9f2222f2b7fd327`, 3 warmups + 20 samples, nearest-rank p95, macOS-26.2-arm64 / Python 3.11.12: SQLite cold_p95 4.746582875s hot_p95 44.013166ms; PostgreSQL cold_p95 4.584467292s hot_p95 66.297625ms — both under 5s/300ms. T054 re-review remains for main session — not self-CLEARed.)
