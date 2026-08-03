# Tasks

## 1. 迁移后的历史任务清单

- [X] T001 [P] Confirm Docker PG for dual-backend: document URL in openspec/specs/010-row-idempotent-import/quickstart.md (finance-tracker-postgres-test :55432)
- [X] T002 [P] Inventory cash/investment short-circuit call sites in src/ft/application/statement_import.py and src/ft/application/investment_import.py
- [X] T003 Add helper or reuse formal_fact_targets path for investment skip classification in src/ft/adapters/relational/imports.py (document if already sufficient)
- [X] T004 [P] Create tests/contract/test_row_idempotent_import.py skeleton with sqlite+postgresql params (fail until services fixed)
- [X] T005 [P] Create overlapping fixture builders or mini fixtures under tests/fixtures/import_idempotency/ (shared identities + novel rows for cash and/or investment)
- [X] T006 [P] [US1] Failing test: investment same-file re-import count=0 without digest short-circuit assumption in tests/integration/test_investment_reimport_idempotent.py (or extend test_ibkr_import.py)
- [X] T007 [P] [US1] Failing test: cash same-file re-import count=0 after removing completed-batch early return in tests/ (appropriate cash import integration)
- [X] T008 [US1] Remove completed-batch early return in src/ft/application/statement_import.py (still start_batch + formal_fact_targets skip)
- [X] T009 [US1] Remove _find_existing_batch early return in src/ft/application/investment_import.py
- [X] T010 [US1] After add_raw_records in investment_import.py, skip raw_ids present in formal_fact_targets; only apply_investment_event + investments.add for novel; return novel count
- [X] T011 [US1] Make T006–T007 green; keep unique constraints as backstop
- [X] T012 [P] [US2] Failing test: investment overlap A→B novel-only count in tests/contract/test_row_idempotent_import.py
- [X] T013 [P] [US2] Failing test: cash overlap A→B if cash fixtures available; else document cash covered by identity skip + T007 path
- [X] T014 [P] [US2] Failing test: reverse order B→A same final identity set
- [X] T015 [US2] Ensure investment path handles different digests with shared identities without double events (T010)
- [X] T016 [US2] Ensure cash path does not reintroduce digest short-circuit (T008)
- [X] T017 [US2] Green T012–T014 on SQLite
- [X] T018 [P] [US3] Assert after two imports formal fact count is identity-based; batch status completed does not imply skip without row work (contract assertions)
- [X] T019 [US3] Document OperationResult messages: success with new_rows=0 vs error; optional details keys in application services
- [X] T020 [US3] Verify start_batch digest uniqueness still allows re-entry (research D3); no schema change unless forced
- [X] T021 Run dual-backend matrix with FT_TEST_POSTGRES_URL Docker :55432 for test_row_idempotent_import + investment reimport
- [X] T022 [P] Update openspec/specs/010-row-idempotent-import/quickstart.md if CLI messages changed
- [X] T023 [P] Cross-check 009/007 docs: note superseded digest-primary semantics (brief note in 010 research or 009 living cross-link only if needed)
- [X] T024 git status hygiene; no secrets; mark all tasks [X]

## 2. 迁移确认

- [x] 2.1 保留原始任务、验证证据和未解决风险。
- [x] 2.2 将行为需求投影到 OpenSpec 主规格。
