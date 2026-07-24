# Implementation Plan: 整数代理主键（016）

**Branch**: `016-bigint-surrogate-ids` | **Date**: 2026-07-24 | **Spec**: [spec.md](./spec.md)

**Input**: `specs/016-bigint-surrogate-ids/spec.md`（015 后重评）

## Summary

One-shot cutover of **internal surrogate PKs** from UUID strings to **integers** for in-scope ledger tables after 015. Business idempotency stays **`record_id × source_type`**. No dual-stack, no `public_id`, no restoring 015-deleted tables. Dual-backend (PG BIGINT / SQLite INTEGER) with financial parity.

## Technical Context

**Language/Version**: Python 3.11+  
**Primary Dependencies**: SQLAlchemy, Alembic, pytest, uv  
**Storage**: PostgreSQL + file SQLite via `FT_DATABASE_URL`  
**Baseline head**: `20260724_08` (015)  
**Testing**: pytest; SQLite + real PostgreSQL matrix  
**Target Platform**: macOS/Linux CLI  
**Constraints**: Exact decimal; fail-closed broken FKs; no dual-write; keep 015 identity model  

## Constitution Check

| Principle | Status |
|---|---|
| I 财务正确性 | PASS — projection parity SC-002; no silent re-accounting |
| II Spec Kit | PASS — 016 artifacts drive work |
| III 测试先行 | PASS — failing schema/id tests before cutover |
| IV 双后端等价 | PASS — parity matrix; id values may differ |
| V 最小复杂度 | PASS — only in-scope tables; no public_id |

### Parity Matrix

| Dimension | PostgreSQL | SQLite | Notes |
|---|---|---|---|
| PK type | BIGINT IDENTITY/SERIAL-like | INTEGER AUTOINCREMENT | logical int |
| FK type | BIGINT | INTEGER | same |
| UUID PK gone | yes | yes | rebuild if needed |
| Business keys | unchanged text | unchanged | source_type/record_id |
| Money results | equal | equal | SC-002/005 |
| Auto fallback / dual-write | forbidden | forbidden | |

### SQLite boundary

Rebuild in-scope tables in one Alembic revision when ALTER cannot change PK type; map UUID→int in temp tables; rewrite all FKs in same txn; `PRAGMA foreign_key_check`; no downgrade.

### Migration order (logical)

1. `accounts` (+ rewrite FKs that point at accounts)  
2. `cash_transactions` / `investment_events`  
3. `account_aliases`  
4. `transaction_relations` (endpoints + own PK；保留开放单腿关系的空有序端点)
5. Wealth/lifecycle **account_id / owner_account_id** columns only  
6. Drop any leftover UUID id columns  

### Application changes

- Models: integer PK, no `default=_uuid` on in-scope tables  
- Repositories: flush and read DB-assigned id  
- Relations: int fact ids; keep fact_type  
- Runtime `SCHEMA_REVISION` = new head  
- Do not expose int PK in public cash CSV  

### `~/.ft` (optional delivery)

Same pattern as 015: backup → upgrade → verify counts/projections.

### 开放单腿关系迁移合同（2026-07-25 Flow-Back）

`ordered_fact_a` 与 `ordered_fact_b` 在 015 数据中可为 `NULL`，例如开放的
`refund_offset` 与 `transfer_pair`。SQLite 和 PostgreSQL 的新 schema 都必须保持这两列可空：

- 旧端点为 `NULL` 或空字符串：新端点必须为 `NULL`；不查询映射表，也不视为孤儿。
- 旧端点非 `NULL`：必须映射到 cash 或 investment 的同一业务事实；映射失败时迁移失败关闭。
- 迁移测试必须从真实 015 schema seed 这两种情况，验证升级成功、端点空值保留，并继续验证非空断链失败关闭。

这不改变 relation kind 规则、事实来源或财富计算，仅恢复既有列可空性合同。

### PostgreSQL 历史基线合同（2026-07-25 Flow-Back）

`20260719_02` 在 PG 上创建财富表时，引用当时 UUID 字符串 `accounts.id` 的 owner/account FK 必须也是字符串；不得用当前 ORM 的 BIGINT 定义回填历史 schema。迁移测试须从空 PG 到 `20260724_08` 再到 016，验证基线可建立并在 016 中统一转为整数。

## Project Structure

```text
specs/016-bigint-surrogate-ids/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── surrogate-keys.md
│   └── relation-endpoints.md
└── tasks.md
```

Impact: `models.py`, Alembic `20260724_09_*`, repositories, relations, wealth FK columns, tests.

## Complexity Tracking

| Choice | Why | Rejected |
|---|---|---|
| Per-table sequences | Simple, matches B1 | Global single sequence across cash/inv |
| No public_id | 015 record_id enough | Extra column |
| One-shot rebuild SQLite | Correct FKs | Partial ALTER |
