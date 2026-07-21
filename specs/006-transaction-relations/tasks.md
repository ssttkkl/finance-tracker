# Tasks: Transaction Relations

**Input**: Design documents from `/specs/006-transaction-relations/`  
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: MANDATORY failing tests before implementation. Persistence/relation/projection changes require SQLite + real PostgreSQL matrix; neither backend may be mocks-only. If `FT_TEST_POSTGRES_URL` is unset, leave SQLite evidence and report missing PG matrix explicitly.

**Organization**: Setup → Foundational → User Stories US1–US9 (base 006, done) → **Open-Leg phase (remaining)** → Polish.

**Open-leg extension**: FR-042–047 / User Story 6b. Branch `006-open-leg-pending`.

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: Can run in parallel (different files, no incomplete dependency)
- **[Story]**: US1…US9 from spec.md
- Paths are repo-relative

---

## Phase 1: Setup

**Purpose**: Confirm artifacts and inventory integration points

- [x] T001 Confirm feature artifacts under `specs/006-transaction-relations/` and branch `006-transaction-relations`
- [x] T002 [P] Inventory import completion path, cash fact identity/raw linkage, report/balance projection, and convert offset fields in `src/ft/application/statement_import.py`, `src/ft/adapters/relational/{models,imports,repositories,queries,uow,runtime}.py`, `src/ft/application/{cashflow,queries}.py`, `src/ft/convert.py`, `src/ft/report.py`, `src/ft/cli.py`, `src/ft/repositories/protocols.py`

---

## Phase 2: Foundational (blocking)

**Purpose**: Domain types, schema, protocols, failing dual-backend skeleton — required before any story impl

**⚠️ CRITICAL**: No user-story implementation until this phase is complete

- [x] T003 [P] Add domain relation types/constants (kinds, statuses, subtype, evidence helpers, business-key ordering) in `src/ft/domain/relations.py`
- [x] T004 [P] Extend repository protocols for relations, check runs, aliases, logical delete in `src/ft/repositories/protocols.py`
- [x] T005 Create Alembic revision + models for `transaction_relations`, check runs, account aliases, formal-fact logical delete marker/event, active-aware identity constraints in `migrations/versions/20260721_05_transaction_relations.py` and `src/ft/adapters/relational/models.py`; update `tests/test_alembic_migration.py` revision list
- [x] T006 Wire relational adapters/UoW/runtime stubs for relation repositories in `src/ft/adapters/relational/{repositories,uow,runtime}.py` (enough for red tests to import)
- [x] T007 [P] Write failing dual-backend test harness/helpers for relation fixtures in `tests/test_transaction_relations_support.py` (SQLite always; PG when `FT_TEST_POSTGRES_URL` set)
- [x] T008 [P] Write failing foundational tests: active-only source identity occupancy + schema presence in `tests/test_transaction_relations_foundation.py`

**Checkpoint**: Red foundation tests; schema/protocol surface exists

---

## Phase 3: User Story 1 — 跨平台消费镜像 payment_mirror (P1) 🎯 MVP

**Goal**: Two active facts for one external payment linked by `payment_mirror`; expense counted once when accepted; balances keep both legs

**Independent Test**: Import/create Alipay+bank pair; relation accepted/pending; report once; dual backend

### Tests

- [x] T009 [P] [US1] Failing tests for payment_mirror auto-accept (≤10s, exact amount, card-tail/text, unique) and weak/pending/delta cases in `tests/test_transaction_relations_payment_mirror.py`
- [x] T010 [P] [US1] Failing tests for n-way mirror connected component + canonical conflict pending in `tests/test_transaction_relations_payment_mirror.py`
- [x] T011 [P] [US1] Failing projection test: accepted mirror expenses count once; balances both legs in `tests/test_transaction_relations_projection.py`

### Implementation

- [x] T012 [US1] Implement payment_mirror matching + evidence + relation persistence in `src/ft/domain/relations.py` and `src/ft/application/relations.py`
- [x] T013 [US1] Hook post-import relation check after commit with new-fact seeds in `src/ft/application/statement_import.py`
- [x] T014 [US1] Projection: mirror grouping + single external count in `src/ft/domain/relations.py` / `src/ft/application/queries.py` / `src/ft/adapters/relational/queries.py` / `src/ft/report.py` as needed
- [x] T015 [US1] Green US1 tests on SQLite; run PG matrix when available

**Checkpoint**: US1 demoable (mirror relations + projection)

---

## Phase 4: User Story 2 — 内部转账 transfer_pair (P1)

**Goal**: Opposite-sign legs form `transfer_pair`; balances both sides; external P&L excludes pair

**Independent Test**: Exact same-currency transfer within 10s + signals; unionpay same-day unique rule; amount delta pending

### Tests

- [x] T016 [P] [US2] Failing transfer_pair tests (exact, windows, signals, amount delta, unionpay same-day) in `tests/test_transaction_relations_transfer.py`

### Implementation

- [x] T017 [US2] Implement transfer_pair matching/rules in `src/ft/domain/relations.py` and `src/ft/application/relations.py`
- [x] T018 [US2] Projection excludes accepted transfer_pair legs from external P&L in projection modules used by `src/ft/application/queries.py` / `src/ft/report.py`
- [x] T019 [US2] Green US2 tests SQLite + PG if available

**Checkpoint**: Transfers no longer pollute expense/income

---

## Phase 5: User Story 3 — 退款 refund_offset (P1)

**Goal**: Refund links expense without rewriting amounts; net via accepted relations; multi-refund/one-expense; no multi-expense split

**Independent Test**: −100/+30 net −70; over-refund not auto-accepted; windows 14/30d; legacy offset_* ignored

### Tests

- [x] T020 [P] [US3] Failing refund_offset tests (partial/full/over, windows, multi-refund, one-refund-one-expense, strict remaining balance) in `tests/test_transaction_relations_refund.py`
- [x] T021 [P] [US3] Failing test: legacy `offset_*`/`proposed_action` do not drive nets in `tests/test_transaction_relations_refund.py`

### Implementation

- [x] T022 [US3] Implement refund matching + remaining-balance accounting in `src/ft/domain/relations.py` / `src/ft/application/relations.py`
- [x] T023 [US3] Ensure import/convert path does not net-rewrite formal fact amounts for refunds in `src/ft/application/statement_import.py` / `src/ft/convert.py` (preview tracking only if kept)
- [x] T024 [US3] Projection applies refund_offset after mirror grouping in projection modules
- [x] T025 [US3] Green US3 tests SQLite + PG if available

**Checkpoint**: Refund nets relation-only

---

## Phase 6: User Story 6 — Review Inbox (P1)

**Goal**: List pending; accept/reject/later with audit; reject suppresses re-recommend; later stays pending

**Independent Test**: Weak pending → accept changes report; reject blocks re-pending; later no report impact

### Tests

- [x] T026 [P] [US6] Failing review inbox contract tests in `tests/test_transaction_relations_review.py`

### Implementation

- [x] T027 [US6] Implement review decision APIs in `src/ft/application/relations.py` + repository methods
- [x] T028 [US6] CLI commands for pending/accept/reject/later in `src/ft/cli.py`
- [x] T029 [US6] Enforce human decisions not silently overwritten; supersede path only in `src/ft/application/relations.py`
- [x] T030 [US6] Green US6 tests SQLite + PG if available

**Checkpoint**: Review contract usable without Web UI

---

## Phase 7: User Story 9 — 跨批后到账单补齐 (P1)

**Goal**: Later batch seeds match earlier batches’ active facts

**Independent Test**: Bank then Alipay across batches creates cross-batch mirror

### Tests

- [x] T031 [P] [US9] Failing cross-batch seed/candidate tests in `tests/test_transaction_relations_cross_batch.py`

### Implementation

- [x] T032 [US9] Ensure check seeds are batch-new facts and candidates are workspace-active with windows in `src/ft/application/relations.py` / `src/ft/application/statement_import.py`
- [x] T033 [US9] Manual re-run seed-range API (batch/date/facts) in `src/ft/application/relations.py` + CLI hook in `src/ft/cli.py`
- [x] T034 [US9] Green US9 tests SQLite + PG if available

**Checkpoint**: Late-arriving bills link correctly

---

## Phase 8: User Story 4 — 信用还款 subtype (P2)

**Goal**: cash→loan as `transfer_pair` + `credit_repayment`; distinguishable; FX evidence without amount equality

**Independent Test**: Same-currency ≤600s exact; FX ≤10s with dual amounts in evidence

### Tests

- [x] T035 [P] [US4] Failing credit_repayment tests in `tests/test_transaction_relations_transfer.py` (or dedicated file)

### Implementation

- [x] T036 [US4] Implement repayment detection/subtype/evidence in `src/ft/domain/relations.py` / `src/ft/application/relations.py`
- [x] T037 [US4] Ensure user-visible listing distinguishes repayment vs ordinary transfer in CLI/query output
- [x] T038 [US4] Green US4 tests SQLite + PG if available

**Checkpoint**: Repayments not counted as spend/income

---

## Phase 9: User Story 5 — 可审计逻辑删除 + 再导入 (P2)

**Goal**: User logical-delete instance; projection/relations update; re-import same identity publishes **new active** fact

**Independent Test**: Delete one duplicate; report fixed; re-import creates new active; no silent undelete

### Tests

- [x] T039 [P] [US5] Failing logical-delete + re-import tests (active idempotency, new instance, no undelete, digest unchanged) in `tests/test_transaction_relations_delete.py`

### Implementation

- [x] T040 [US5] Implement logical delete + supersede related relations atomically in `src/ft/application/cashflow.py` / `src/ft/application/relations.py` / adapters
- [x] T041 [US5] Active-only formal publish / identity occupancy in `src/ft/adapters/relational/imports.py` and `src/ft/application/statement_import.py`
- [x] T042 [US5] CLI delete command with required reason in `src/ft/cli.py`
- [x] T043 [US5] Green US5 tests SQLite + PG if available

**Checkpoint**: Delete governs instances, not permanent identity ban

---

## Phase 10: User Story 7 — 账户别名 (P2)

**Goal**: Aliases enhance match evidence; never override import mapping

**Independent Test**: Card-tail alias raises confidence/evidence; conflict visible; import account unchanged

### Tests

- [x] T044 [P] [US7] Failing alias tests in `tests/test_transaction_relations_aliases.py`

### Implementation

- [x] T045 [US7] Persist/query account aliases in models/adapters/application
- [x] T046 [US7] Use aliases only in relation scoring/evidence in `src/ft/application/relations.py`
- [x] T047 [US7] CLI maintain aliases in `src/ft/cli.py`
- [x] T048 [US7] Green US7 tests SQLite + PG if available

**Checkpoint**: Aliases help matching without hijacking import

---

## Phase 11: User Story 8 — 规则 supersede (P3)

**Goal**: Rule upgrades supersede old relations with audit chain; no silent human overwrite

**Independent Test**: v1 accepted → v2 supersede; history queryable

### Tests

- [x] T049 [P] [US8] Failing supersede tests in `tests/test_transaction_relations_review.py` or `tests/test_transaction_relations_foundation.py`

### Implementation

- [x] T050 [US8] Implement supersede API + automatic rule version path in `src/ft/application/relations.py`
- [x] T051 [US8] Green US8 tests SQLite + PG if available

**Checkpoint**: History preserved under rule upgrades

---

## Phase 12: Cross-cutting polish

**Purpose**: Compatibility matrix, concurrency, docs, full suite

- [x] T052 [P] Failing/complete tests for cross-kind compatibility matrix and projection order in `tests/test_transaction_relations_projection.py`
- [x] T053 Enforce cross-kind accept guards (pending on conflict) in `src/ft/application/relations.py`
- [x] T054 Idempotent concurrent check behavior tests + implementation notes in `tests/test_transaction_relations_foundation.py` / service locking strategy
- [x] T055 Ensure matching ignores logically deleted facts (SC-017) in check path + tests
- [x] T056 Update docs: `docs/import-reconcile-flow.md`, `README.md` — relation layer, no CSV reconcile, logical delete/re-import semantics
- [x] T057 Run full `uv run pytest` on SQLite; run PG matrix when URL set; typecheck/lint if project provides; report any skipped evidence with exact commands

**Checkpoint**: Feature ready for converge/review

### Calibration follow-ups (post T057, real-ledger)

- [x] T058 [US3] Exclude transfer/receipt/redpacket/withdraw legs from `refund_offset` (both sides) unless explicit refund signal; tokens + `is_refund_excluded_leg` in `src/ft/domain/relations.py`; prune in `FactCandidateIndex` refund buckets; FR-020 + edge cases + research Decision 7 updated; tests in `tests/test_transaction_relations_refund.py`
- [x] T059 [US3] Real-ledger verification on `~/.ft` copy: refund accepted 162 / pending 3218 (vs prior 165 / 3305); transferish pollution 0; 消费退货 still accepted; 0 true-refund positives lost all candidates in simulation
- [x] T060 [US3] Tighten weak refund pending: exact amount only (not ≤ remaining); expense seeds strong_link only; FR-020 + research; real-ledger pending 3218 → 226, accepted stays 162
- [x] T061 [US3] Spec first: asymmetric P2P refund rule in FR-020 + edge cases + research (bare p2p income ≠ refund; p2p expense MAY pair with 微信红包-退款 as strong; merchant refunds still exclude p2p expenses)
- [x] T062 [US3] Implement T061: asymmetric P2P + fine subtype (红包/转账/收款/提现); p2p expense strong only via same subtype or order_lock; tests; real-ledger 微信红包-退款 pairs 微信红包（单发） without merchant×p2p flood
- [x] T063 Converge + dual-backend verification: all `tasks.md` items `[x]` (T001–T062); domain/application surface check 19/19; `pytest tests/test_transaction_relations_*.py tests/test_alembic_migration.py` **69 passed** with `FT_TEST_POSTGRES_URL=postgresql+psycopg://finance_tracker:finance_tracker_test@127.0.0.1:55432/finance_tracker_test` + `FT_REQUIRE_TEST_POSTGRES=1` (foundation/delete parametrize sqlite+postgresql green); real-ledger `~/.ft` copy check ~5s → mirror acc 2213 / pend 160, transfer acc 8 / pend 104, refund acc **163** / pend **226**, 微信红包-退款→微信红包（单发） accepted. Full-repo `pytest tests/` on same PG: 629 passed + unrelated wealth/multi-currency PG teardown errors (`NotImplementedError: multi-currency account merge is one-shot and not irreversible`) and 1 wealth perf budget miss — **out of 006 scope**, not regressions of this feature. No project ruff/mypy config. No push/PR (no user auth).

**Checkpoint**: Feature 006 implementation + calibration + dual-backend evidence complete; ready for optional gstack `review` / user-authorized ship.

---

## Dependencies

```text
Phase1 → Phase2 → US1 → US2 → US3 → US6 → US9 → US4 → US5 → US7 → US8 → Polish
```

- Foundational blocks all stories
- US1 establishes check hook + projection skeleton used by later stories
- US6 review depends on pending relations existing (US1+)
- US5 delete/re-import can follow after relations exist (needs supersede of relations)
- US7 aliases enhance US1 matching but not required for MVP mirror exact tests

## Parallel opportunities

- Within Phase 2: T003/T004/T007/T008 in parallel after T005/T006 scaffolding starts
- Within each story: all `[P]` test tasks before sequential implementation tasks
- US4/US7 can parallelize after US2/US1 cores if staffing allows (still after foundational)

## MVP scope

**MVP = Phase 1–3 (US1 payment_mirror)** plus minimal post-import check hook.  
Recommended first shippable correctness slice after MVP: US2 + US3 + US6.

## Independent test criteria (summary)

| Story | Independent test |
|---|---|
| US1 | Mirror pair → 2 facts + relation; report once when accepted |
| US2 | Transfer pair balances both; external P&L excludes |
| US3 | Refund nets without amount rewrite |
| US4 | Repayment subtype excluded from spend/income |
| US5 | Logical delete + re-import new active fact |
| US6 | Review accept/reject/later audit behavior |
| US7 | Alias evidence without import hijack |
| US8 | Supersede preserves history |
| US9 | Cross-batch seed finds prior facts |

## Notes for implementer

- Test-first: each story’s tests must fail for missing behavior before impl turns green
- No `duplicate_of`; no amount tolerance; no import rollback on check failure
- Dual-backend: same Application Service assertions on SQLite and real PostgreSQL
- Do not expand into FX product, full Web UI, or CSV reconcile revival

## Phase Open-Leg: 开放单腿 pending（006 extension）

**Purpose**: Implement FR-042–047 / US6b — multi/zero candidate → one open-leg pending; accept with `--other`; no fan-out.

**Prerequisites**: Base 006 relation layer green (T001–T057). Branch `006-open-leg-pending`.

**Independent test**: 1 refund × N expenses → 1 open-leg pending; accept requires other; projection ignores open-leg; mirror never open-leg; SQLite+PG migration.

### OL Setup / inventory

- [x] T100 Confirm open-leg updates in `specs/006-transaction-relations/{spec,plan,research,data-model}.md` and contracts `contracts/review-inbox.md`, `contracts/relation-check.md`
- [x] T101 [P] Inventory current `secondary_fact_id` NOT NULL, business key, accept API in `migrations/versions/20260721_05_transaction_relations.py`, `src/ft/adapters/relational/models.py`, `src/ft/application/relations.py`, `src/ft/cli.py`

### OL Foundational (schema + red tests)

- [x] T102 [P] Write failing tests for open-leg multi-candidate refund → single pending in `tests/test_transaction_relations_open_leg.py`
- [x] T103 [P] Write failing tests: open-leg accept requires other_fact_id; illegal other fails; projection ignores open-leg in `tests/test_transaction_relations_open_leg.py`
- [x] T104 [P] Write failing tests: transfer multi-candidate open-leg; reject suppresses re-open; payment_mirror never null secondary in `tests/test_transaction_relations_open_leg.py`
- [x] T105 Create Alembic revision `migrations/versions/20260722_06_open_leg_pending.py`: nullable secondary, `anchor_fact_id`, checks, partial unique for open-leg; dual-backend; update `tests/test_alembic_migration.py`
- [x] T106 Update ORM/repositories for nullable secondary + anchor + open key in `src/ft/adapters/relational/models.py`, `src/ft/adapters/relational/repositories.py`, `src/ft/repositories/protocols.py`

**Checkpoint**: Migration applies SQLite (+PG if available); open-leg tests red for missing behavior

### OL Domain + application (US6b / US6)

- [x] T107 [US6b] Extend domain proposals for open-leg (`secondary_fact_id` optional, evidence candidate_fact_ids top-K=20) in `src/ft/domain/relations.py`
- [x] T108 [US6b] Change `evaluate_refund_offset` multi/zero-candidate path to one open-leg; stop expense-seed fan-out of multi bilateral pendings in `src/ft/domain/relations.py`
- [x] T109 [US6b] Change `evaluate_transfer_pair` multi/zero-candidate path to one open-leg with anchor_role in `src/ft/domain/relations.py`
- [x] T110 [US6b] Persist open-leg keys; accept(other_fact_id); reject open anchor occupancy; projection skip open-leg in `src/ft/application/relations.py`
- [x] T111 [US6] CLI list shows open-leg; `accept` requires `--other` for open-leg in `src/ft/cli.py`
- [x] T112 Green `tests/test_transaction_relations_open_leg.py` and adjust `tests/test_transaction_relations_refund.py` / `tests/test_transaction_relations_transfer.py` for multi-candidate → open-leg

**Checkpoint**: US6b acceptance scenarios pass on SQLite

### OL Polish

- [x] T113 [P] Dual-backend matrix for open-leg migration + core open-leg tests when `FT_TEST_POSTGRES_URL` set
- [x] T114 [P] Real-ledger smoke optional: multi 京东退货 candidates → 1 open pending (document path under `/tmp`)
- [x] T115 Update `specs/006-transaction-relations/tasks.md` checkboxes; `$speckit-converge` if available
- [x] T116 Commit on `006-open-leg-pending` with message covering schema+behavior (no push unless authorized)

---

## Dependencies (open-leg)

```text
T100–T101 → T102–T104 (red) → T105–T106 (schema) → T107–T111 (impl) → T112 green → T113–T116 polish
```

## MVP (open-leg only)

T100–T112: schema + refund open-leg + accept/reject + tests. Transfer open-leg included in T109. Mirror out of scope for open-leg.

## Notes

- Base tasks T001–T057 remain completed for original 006.
- Open-leg tasks are the **only** remaining implementation work on this branch.
- Main session must use `speckit_implementer` for T102–T112 product code per constitution.
