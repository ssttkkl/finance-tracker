# Tasks

## 1. 迁移后的历史任务清单

- [x] T001 Confirm `OpenSpec active change state` → `openspec/specs/015-inline-row-provenance` and work on branch `015-inline-row-provenance` (or current feature branch)
- [x] T002 [P] Inventory call sites under `src/ft/` and `tests/` for: `import_batches`/`raw_files`/`raw_records`/`raw_record_id`, cash `offset_*`/`proposed_action`/`locked`/`transfer_account`/`bill_source`/cash `source`, `record_revisions`/`revision`, `relation_check_runs`, `fact_deletion_events`, investment `price` — write list into tasks notes or temporary grep artifact for renames
- [x] T003 Add failing tests in `tests/test_015_schema_cleanup.py` (or split): post-upgrade schema has no `import_batches`/`raw_files`/`raw_records`/`fact_deletion_events`/`record_revisions`/`relation_check_runs`; cash has `source_type`/`record_id`/`source_payload` and lacks FR-015 columns; investment has identity fields and lacks `price`/`raw_record_id`/`revision`; partial unique semantics documented in assert helpers
- [x] T004 [P] Add failing migration backfill tests in `tests/test_015_migration_backfill.py`: raw→fact `source_type`/`record_id`/`source_payload`; active duplicate identity fail-closed; SQLite path; register PostgreSQL matrix via `FT_TEST_POSTGRES_URL` project convention
- [x] T005 [P] Add failing idempotency contract tests in `tests/test_015_idempotency.py`: double import same `(source_type,record_id)` → new=0; cross-channel same record_id string → two facts; soft-delete then reimport allows new active cash row
- [x] T006 Implement Alembic revision `migrations/versions/20260724_08_inline_provenance_cleanup.py` (revises `20260724_07`): dialect-branched upgrade; backfill; drop tables/columns; create partial uniques; SQLite rebuild-in-txn; `downgrade` NotImplementedError
- [x] T007 Update `src/ft/adapters/relational/models.py` to match `data-model.md` / contracts (cash + investment + remove deleted models)
- [x] T008 Update `src/ft/adapters/relational/runtime.py` table lists / validate_runtime expectations for removed tables
- [x] T009 Update `tests/test_alembic_migration.py` head revision id and insert statements to new cash/investment column sets
- [x] T010 [US1] Failing tests: after import, fact rows have non-empty provenance triple; no rows in raw tables (tables absent)
- [x] T011 [US1] Rewrite import persistence in `src/ft/adapters/relational/imports.py` and UoW wiring: remove batch/raw repositories or no-op delete; write facts with provenance
- [x] T012 [US1] Update `src/ft/application/statement_import.py` and `src/ft/application/investment_import.py` to formalize without batch/raw_record_id
- [x] T013 [US1] Update repositories `src/ft/adapters/relational/repositories.py` cash/investment insert/read maps for new columns only
- [x] T014 [US1] Green US1 tests SQLite; run same on PostgreSQL when URL set
- [x] T015 [US2] Extend/finish `tests/test_015_idempotency.py` for digest-not-gate and content-key record_id paths
- [x] T016 [US2] Implement identity lookup/skip in import application + repo queries using `(workspace_id, source_type, record_id)`
- [x] T017 [US2] Align `src/ft/schema.py` / `src/ft/domain/imports.py` field lists; strip formal write of deleted keys
- [x] T018 [US2] Green US2 dual-backend
- [x] T019 [US3] Failing tests: platform refund fixture without raw table join still proposes relations
- [x] T020 [US3] Update `src/ft/application/relations.py` FactView assembly to load from `source_payload` / formal columns only
- [x] T021 [US3] Remove `seed_batch_id` / batch-based relation check entrypoints if any
- [x] T022 [US3] Green US3 dual-backend
- [x] T023 [US4] Grep gate test or script assertion: no runtime reads of deleted column names in `src/ft/` (exclude migrations/tests historical)
- [x] T024 [US4] Remove dead code paths in `imports.py`, `uow.py`, protocols in `src/ft/repositories/protocols.py`
- [x] T025 [US4] Green full migration from 014 head fixture on SQLite + PG
- [x] T026 [US5] Failing tests: insert/read cash without offset/locked/transfer_account/source/bill_source; wealth cash_kind uses category transfer*
- [x] T027 [US5] Update `src/ft/adapters/relational/wealth_facts.py` and `src/ft/adapters/relational/runtime.py` cash_kind / digests — no offset/transfer_account
- [x] T028 [US5] Update `src/ft/application/cashflow.py`, `src/ft/application/queries.py`, `src/ft/domain/queries.py`, `src/ft/convert.py` formal write path
- [x] T029 [US5] Green US5 dual-backend
- [x] T030 [US6] Failing tests: fact-delete only sets row deleted_*; relation check works without check_run table; ledger_snapshots load/save still works; no revision writes
- [x] T031 [US6] Remove `RelationalFactDeletionRepository` event inserts; keep logical delete on cash row in `repositories.py`
- [x] T032 [US6] Remove relation_check_run repository usage from `src/ft/application/relations.py` and UoW
- [x] T033 [US6] Remove record_revision writes from `imports.py`; drop revision from models already in T007; fix wealth watermark in `wealth_facts.py` / `wealth_read_model.py` per plan (content digest)
- [x] T034 [US6] Confirm `ledger_snapshots` paths in `repositories.py`/`queries.py` untouched except compile
- [x] T035 [US6] Green US6 dual-backend
- [x] T036 [US7] Failing tests: buy/sell projection without price field equals leg-derived baseline
- [x] T037 [US7] Update `src/ft/domain/investment_projection.py` and `src/ft/domain/investment.py` to not require price
- [x] T038 [US7] Update importers under `src/ft/importers/` to fill cash legs when only price×qty available; stop persisting price
- [x] T039 [US7] Update schema constants / export headers for investment rows
- [x] T040 [US7] Green US7 dual-backend
- [x] T041 [US8] Document exact commands already in `openspec/specs/015-inline-row-provenance/quickstart.md` (verify workspace id placeholder)
- [x] T042 [US8] Backup `~/.ft/finance-tracker.db` to timestamped `.bak-015-*`
- [x] T043 [US8] Run `uv run alembic upgrade head` with `FT_DATABASE_URL` pointing at `~/.ft/finance-tracker.db`
- [x] T044 [US8] Verify schema/counts/CLI smoke; record evidence (command outputs) in PR notes or `openspec/specs/015-inline-row-provenance/quickstart.md` checklist note
- [x] T045 [US8] On failure restore bak and fix migration — do not leave sole corrupt copy
- [x] T046 [P] Sync `docs/database-schema.md` from target `openspec/specs/015-inline-row-provenance/database-schema.md` (post-implement truth)
- [x] T047 [P] Update README / import-reconcile docs removing legacy offset / raw chain statements as needed
- [x] T048 Run full `uv run pytest` + fix regressions
- [x] T049 Mark tasks complete; optional `openspec validate --all --strict` / gstack review before claim done

## 2. 迁移确认

- [x] 2.1 保留原始任务、验证证据和未解决风险。
- [x] 2.2 将行为需求投影到 OpenSpec 主规格。
