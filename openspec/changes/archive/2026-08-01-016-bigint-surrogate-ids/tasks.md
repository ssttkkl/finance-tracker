# Tasks

## 1. 迁移后的历史任务清单

- [X] T001 Confirm `OpenSpec active change state` → `openspec/specs/016-bigint-surrogate-ids` and branch `016-bigint-surrogate-ids`
- [X] T002 [P] Inventory all UUID PK/FK usages under `src/ft/adapters/relational/models.py` and repositories (accounts, facts, relations, aliases, wealth account FKs)
- [X] T003 Add failing tests `tests/test_016_schema_bigint.py`: after head, in-scope PK/FK integer; no UUID default on those models; 015 dead tables still absent
- [X] T004 [P] Add failing tests `tests/test_016_migration_parity.py`: seed UUID-era fixture at 015 head → upgrade → balances/positions equal; relation endpoints remap; dual-backend when PG URL set
- [X] T005 [P] Add failing tests `tests/test_016_idempotency.py`: double import still new=0 via source_type×record_id
- [X] T006 Implement Alembic `migrations/versions/20260724_09_bigint_surrogate_ids.py` (revises `20260724_08`): map UUID→int; rebuild SQLite; PG alter/rebuild; fail-closed; no downgrade
- [X] T007 Update `src/ft/adapters/relational/models.py` integer PKs/FKs; remove `_uuid` defaults for in-scope tables
- [X] T008 Update `src/ft/adapters/relational/runtime.py` SCHEMA_REVISION and any id-type assumptions
- [X] T009 Update repositories/uow/relations/wealth writers to use int ids (`src/ft/adapters/relational/repositories.py`, `wealth_facts.py`, `application/relations.py`, etc.)
- [X] T010 Update `tests/test_alembic_migration.py` head list and metadata assertions
- [X] T011 [US1] Green schema tests on SQLite
- [X] T012 [US1] Green schema tests on PostgreSQL when available
- [X] T013 [US1] Grep gate: no `default=_uuid` on in-scope model PKs
- [X] T014 [US2] Green idempotency tests (cash + investment import paths)
- [X] T015 [US2] Public list/CSV still expose source_type/record_id; int id not required in cash CSV
- [X] T016 [US3] Green relation fixtures after migration (mirror/transfer/refund sample)
- [X] T017 [US3] Confirm fact_type discrimination still present on relation rows
- [X] T018 [US4] Migration fail-closed unit/integration for broken FK (if practical)
- [X] T019 [US4] Optional: backup + upgrade `~/.ft/finance-tracker.db`; record evidence in quickstart
- [X] T020 [US4] SCHEMA_REVISION rejects pre-016 schema
- [X] T021 [P] Sync `docs/database-schema.md` PK types for in-scope tables
- [X] T022 Run full `env -u FT_DATABASE_URL uv run pytest tests/ -q`
- [X] T023 Mark tasks complete; ready for PR to `refactor/web` if requested
- [X] T024 [US3] 先在 `tests/test_016_migration_parity.py` 写失败测试：从 015 schema 建立 `refund_offset` / `transfer_pair` 的 `ordered_fact_a` 或 `ordered_fact_b` 为 NULL 的关系，升级后保留 NULL，非空端点仍映射。
- [X] T025 [US4] 先在 `tests/test_016_migration_parity.py` 写失败测试：非空 `ordered_fact_*` 无法映射时 fail-closed，并安排 SQLite 与真实 PostgreSQL 契约矩阵。
- [X] T026 [US3] 在 `migrations/versions/20260724_09_bigint_surrogate_ids.py` 使 SQLite 与 PostgreSQL 的 `ordered_fact_a` / `ordered_fact_b` 保持 nullable，并只为非空未映射端点执行 fail-closed 检查。
- [X] T027 [US4] 按 `quickstart.md` 备份并升级 `/Users/huangwenlong/.ft/finance-tracker.db`，记录 `20260724_09`、整数键和 `foreign_key_check` 验证证据：2026-07-25 从已核对 SHA-256 的 015 备份升级成功；revision `20260724_09`，19 个 accounts 为 integer，23 个待配对关系端点规范化为 NULL，`foreign_key_check` 无输出。
- [X] T028 [US3] 在 `tests/test_016_migration_parity.py` 先覆盖 015 空字符串 `ordered_fact_*` sentinel 规范化为 NULL，再在 `migrations/versions/20260724_09_bigint_surrogate_ids.py` 实现该转换并重跑本机升级。
- [X] T029 [US4] 先在 `tests/test_016_migration_parity.py` 覆盖空 PostgreSQL → `20260724_08` → 016；修复 `migrations/versions/20260719_02_wealth_attribution.py` 的历史 owner/account FK 类型，使其在 UUID 基线与 016 bigint 切换中均正确。

## 2. 迁移确认

- [x] 2.1 保留原始任务、验证证据和未解决风险。
- [x] 2.2 将行为需求投影到 OpenSpec 主规格。
