# Tasks

## 1. 迁移后的历史任务清单

- [x] T001 Confirm feature artifacts under `openspec/specs/004-mapping-import-open-currency/` and branch `codex/mapping-import-open-currency`
- [x] T002 [P] Inventory current forced `--account` and `CURRENCIES` whitelist call sites in `src/ft/cli.py`, `src/ft/domain/accounts.py`, `src/ft/application/accounts.py`, `src/ft/schema.py`, `src/ft/application/statement_import.py`, `src/ft/adapters/statement_import.py`
- [x] T003 Write failing tests for open currency in `tests/test_open_currency.py` (JPY acct add; reject `US`; display unknown code)
- [x] T004 Write failing tests for mapping matcher in `tests/test_mapping.py` (long match wins; empty; skip/error default)
- [x] T005 Write failing tests for multi-account import without `--account` in `tests/test_statement_import_mapping.py` (alipay multi-pay; reject `--account`; idempotent digest; unmatched error rollback)
- [x] T006 [P] Write failing dual-backend smoke hooks or extend existing postgres/sqlite import tests for mapping multi-account + JPY
- [x] T007 Implement `normalize_currency` and remove whitelist from `src/ft/domain/accounts.py`, `src/ft/application/accounts.py`, `src/ft/schema.py`, `src/ft/cli.py` (no choices=CNY/USD/HKD)
- [x] T008 Restore `src/ft/mapping.py` from master semantics (`load_rules`, `match_payment_method`, DEFAULT_RULES)
- [x] T009 Alembic migration `migrations/versions/20260720_03_import_batch_multi_account.py`: `import_batches.target_account_id` nullable; update `src/ft/adapters/relational/models.py`
- [x] T010 Update `src/ft/adapters/relational/imports.py` `start_batch` / `batch_target_accounts` for nullable multi-account batches
- [x] T011 [US1] Implement shared row routing in `src/ft/adapters/statement_import.py` / convert helpers: payment_method + mapping → account_name/currency; no CLI account
- [x] T012 [US1] Refactor `src/ft/application/statement_import.py` to import per-row accounts in one transaction; remove single global account force
- [x] T013 [US1] CLI `src/ft/cli.py`: remove `--account` from `import`; keep source/password-file; optional currency only as row default fallback if needed
- [x] T014 [US1] Make T005 mapping multi-account tests green (SQLite)
- [x] T015 [US1] Run equivalent assertions on PostgreSQL when `FT_TEST_POSTGRES_URL` available
- [x] T016 [US2] Ensure card_number / bill_type routing for icbc/ccb in routing layer (master `_route_account` semantics)
- [x] T017 [US2] Tests: ccb card 2820/0523 and icbc multi-currency rows route via mapping only
- [x] T018 [US2] Confirm CLI rejects `ft import ... --account X`
- [x] T019 [US3] Green `tests/test_open_currency.py`; JPY loan account + import row updates projection
- [x] T020 [US3] Report/display fallback for unknown currency symbols in `src/ft/report.py` or schema consumers
- [x] T021 [US4] Align `src/ft/convert.py` / CLI convert: same mapping router; remove required `--account`
- [x] T022 [US4] Test convert vs import account_name distribution equal on sample alipay fixture
- [x] T023 Update `README.md`, `SKILL.md`, `docs/import-reconcile-flow.md` for no-account import + open currency + mapping
- [x] T024 Fix any tests still requiring `--account` on import (`tests/test_cli.py`, etc.)
- [x] T025 Run `uv run pytest` (and postgres matrix if env set); `uv build`; mark tasks complete
- [x] T026 `openspec validate --all --strict` against spec/plan/tasks

## 2. 迁移确认

- [x] 2.1 保留原始任务、验证证据和未解决风险。
- [x] 2.2 将行为需求投影到 OpenSpec 主规格。
