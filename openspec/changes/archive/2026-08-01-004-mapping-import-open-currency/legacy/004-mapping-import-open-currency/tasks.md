# Tasks: Mapping Import & Open Currency

**Input**: Design documents from `/specs/004-mapping-import-open-currency/`  
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: MANDATORY failing tests before implementation. Persistence changes require SQLite + real PostgreSQL matrix.

## Phase 1: Setup

- [x] T001 Confirm feature artifacts under `specs/004-mapping-import-open-currency/` and branch `codex/mapping-import-open-currency`
- [x] T002 [P] Inventory current forced `--account` and `CURRENCIES` whitelist call sites in `src/ft/cli.py`, `src/ft/domain/accounts.py`, `src/ft/application/accounts.py`, `src/ft/schema.py`, `src/ft/application/statement_import.py`, `src/ft/adapters/statement_import.py`

## Phase 2: Foundational

- [x] T003 Write failing tests for open currency in `tests/test_open_currency.py` (JPY acct add; reject `US`; display unknown code)
- [x] T004 Write failing tests for mapping matcher in `tests/test_mapping.py` (long match wins; empty; skip/error default)
- [x] T005 Write failing tests for multi-account import without `--account` in `tests/test_statement_import_mapping.py` (alipay multi-pay; reject `--account`; idempotent digest; unmatched error rollback)
- [x] T006 [P] Write failing dual-backend smoke hooks or extend existing postgres/sqlite import tests for mapping multi-account + JPY
- [x] T007 Implement `normalize_currency` and remove whitelist from `src/ft/domain/accounts.py`, `src/ft/application/accounts.py`, `src/ft/schema.py`, `src/ft/cli.py` (no choices=CNY/USD/HKD)
- [x] T008 Restore `src/ft/mapping.py` from master semantics (`load_rules`, `match_payment_method`, DEFAULT_RULES)
- [x] T009 Alembic migration `migrations/versions/20260720_03_import_batch_multi_account.py`: `import_batches.target_account_id` nullable; update `src/ft/adapters/relational/models.py`
- [x] T010 Update `src/ft/adapters/relational/imports.py` `start_batch` / `batch_target_accounts` for nullable multi-account batches

**Checkpoint**: Foundation + red tests in place

## Phase 3: US1 — Mapping multi-pay import (P1)

- [x] T011 [US1] Implement shared row routing in `src/ft/adapters/statement_import.py` / convert helpers: payment_method + mapping → account_name/currency; no CLI account
- [x] T012 [US1] Refactor `src/ft/application/statement_import.py` to import per-row accounts in one transaction; remove single global account force
- [x] T013 [US1] CLI `src/ft/cli.py`: remove `--account` from `import`; keep source/password-file; optional currency only as row default fallback if needed
- [x] T014 [US1] Make T005 mapping multi-account tests green (SQLite)
- [x] T015 [US1] Run equivalent assertions on PostgreSQL when `FT_TEST_POSTGRES_URL` available

## Phase 4: US2 — Bank bills via mapping (P1)

- [x] T016 [US2] Ensure card_number / bill_type routing for icbc/ccb in routing layer (master `_route_account` semantics)
- [x] T017 [US2] Tests: ccb card 2820/0523 and icbc multi-currency rows route via mapping only
- [x] T018 [US2] Confirm CLI rejects `ft import ... --account X`

## Phase 5: US3 — Open currency (P1)

- [x] T019 [US3] Green `tests/test_open_currency.py`; JPY loan account + import row updates projection
- [x] T020 [US3] Report/display fallback for unknown currency symbols in `src/ft/report.py` or schema consumers

## Phase 6: US4 — convert/import parity (P2)

- [x] T021 [US4] Align `src/ft/convert.py` / CLI convert: same mapping router; remove required `--account`
- [x] T022 [US4] Test convert vs import account_name distribution equal on sample alipay fixture

## Phase 7: Polish

- [x] T023 Update `README.md`, `SKILL.md`, `docs/import-reconcile-flow.md` for no-account import + open currency + mapping
- [x] T024 Fix any tests still requiring `--account` on import (`tests/test_cli.py`, etc.)
- [x] T025 Run `uv run pytest` (and postgres matrix if env set); `uv build`; mark tasks complete
- [x] T026 `$speckit-converge` against spec/plan/tasks

## Dependencies

- Phase 2 before US implementation
- US1 before US2 (shared router)
- US3 can parallelize after T007
- US4 after US1 router stable

## Parallel examples

- T003/T004/T005 red tests in parallel
- T007 currency vs T008 mapping after tests exist
- US3 display polish parallel with US2 bank routing once router lands

## Implementation strategy

MVP = T007–T015 (open currency + mapping import + no `--account`). Then bank routing, convert parity, docs.
