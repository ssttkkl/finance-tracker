# Tasks

## 1. 迁移后的历史任务清单

- [x] T001 Confirm active feature pointer `OpenSpec active change state` → `openspec/specs/014-fact-field-unify` and branch `014-fact-field-unify`
- [x] T002 [P] Inventory call sites for cash `description`, investment `kind`, and investment `payload` core keys under `src/ft/` and `tests/` (grep list used for mechanical renames)
- [x] T003 Add failing schema/contract tests for end-state columns and public field names in `tests/test_fact_field_unify.py` (or split modules): cash has `note` not `description`; investment has `action` + legs not `kind`; public keys use `note` and `occurred_at`
- [x] T004 Add failing migration tests: promote+strip cores; conflict fail-closed; empty residual payload `{}` — cover SQLite path in same module; register PostgreSQL matrix hook per project convention (`FT_TEST_POSTGRES_URL` / existing dual-db fixtures)
- [x] T005 Add failing projection-parity fixture test: pre-shape investment events → migrate → project equals baseline snapshot in `tests/test_fact_field_unify_projection.py`
- [x] T006 Implement Alembic revision `migrations/versions/20260724_07_fact_field_unify.py` (or next free id): cash rename `description`→`note`; investment rename `kind`→`action`; add leg/note columns; backfill from payload; strip CORE_KEYS; fail on action/currency conflict; SQLite rebuild-in-txn if required (see plan + data-model)
- [x] T007 Update ORM `CashTransactionModel` / `InvestmentEventModel` in `src/ft/adapters/relational/models.py` to match end-state data-model
- [x] T008 Wire migration into project alembic chain; ensure `tests/test_alembic_migration.py` (or equivalent) expects new head
- [x] T009 [US1] Update cash repository write/read in `src/ft/adapters/relational/repositories.py` for `note` and formal `occurred_at` public mapping (no `description`)
- [x] T010 [US1] Update investment repository write/read in `src/ft/adapters/relational/repositories.py` to persist/read columns (`action`, legs, `note`, …); residual payload non-core only; stop `kind=payload.action` pattern
- [x] T011 [P] [US1] Update `src/ft/schema.py` `CASH_CSV_FIELDS` and `src/ft/domain/imports.py` `CASHFLOW_EXPORT_FIELDS` to catalog names (`note`, `occurred_at`)
- [x] T012 [P] [US1] Update import adapter paths in `src/ft/adapters/relational/imports.py` for new column names
- [x] T013 [US1] Update `src/ft/adapters/relational/wealth_facts.py` to read investment formal columns (not payload cores)
- [x] T014 [US1] Mechanical renames across remaining `src/ft/` consumers of `description` / investment `kind` as action (CLI/application if any)
- [x] T015 [US1] Make T003 tests pass; confirm no production reader of cash `description` or investment `kind`
- [x] T016 [US2] Flesh golden pre-migration seed helpers in `tests/test_fact_field_unify_projection.py` covering swap/deposit/withdraw/dividend and fee/ipo if fixtures exist
- [x] T017 [US2] Assert raw_record linkage counts and soft-delete cash behavior unchanged post-migration in tests
- [x] T018 [US2] Run and pass SQLite migration+parity tests; run real PostgreSQL matrix for same tests
- [x] T019 [US2] Verify migration failure message includes fact id on conflict (test assertion)
- [x] T020 [US2] Grep gate: no shipped dual-read/dual-write/compat alias code for retired names under `src/ft/`
- [x] T021 [US3] Update public cash list `_public_row` / queries in `src/ft/adapters/relational/repositories.py` and `src/ft/adapters/relational/queries.py` to catalog keys
- [x] T022 [US3] Update investment list assembly to column-based formal row per `contracts/investment-formal-row.md`
- [x] T023 [P] [US3] Update all tests/fixtures expecting `description` or public `date` or investment payload-spread cores under `tests/`
- [x] T024 [US3] Align CLI help/docs snippets if they document old field names under `docs/` or CLI module strings
- [x] T025 [US3] Make contract-oriented tests in T003 for public shapes pass
- [x] T026 [P] Update `openspec/specs/014-fact-field-unify/quickstart.md` with exact pytest node ids that prove SC-001–SC-008
- [x] T027 Full test suite `uv run pytest` (or project standard) + note any skipped Postgres if unavailable with risk
- [x] T028 Static grep completion evidence for SC-007 (no `description` column usage; no `InvestmentEventModel.kind`; no core-from-payload readers)
- [x] T029 Mark tasks complete; prepare for `openspec validate --all --strict` / converge

## 2. 迁移确认

- [x] 2.1 保留原始任务、验证证据和未解决风险。
- [x] 2.2 将行为需求投影到 OpenSpec 主规格。
