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
4. `transaction_relations` (endpoints + own PK)  
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
