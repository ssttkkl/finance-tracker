# Research: 016 Bigint Surrogate IDs

## R1 — Scope after 015

**Decision**: In-scope UUID PK tables only: `accounts`, `cash_transactions`, `investment_events`, `transaction_relations`, `account_aliases`. Plus UUID-shaped FKs to those ids on wealth/lifecycle.

**Rationale**: 015 deleted import/raw/revision/check_run/deletion tables.

## R2 — Public identity

**Decision**: No `public_id`. External/idempotent identity remains `source_type`×`record_id` and account `name`.

## R3 — Integer dialect

**Decision**: SQLAlchemy `BigInteger` with autoincrement; PG BIGINT; SQLite INTEGER affinity.

## R4 — Migration mapping

**Decision**: Transient mapping tables or CTEs inside migration only; not product tables. Fail-closed if FK target missing.

## R5 — Relation endpoints

**Decision**: Store integer fact ids; keep `primary_fact_type` / `secondary_fact_type`.

## R6 — Wealth tables

**Decision**: Keep string PKs (`observation_id`, digests). Only convert `account_id` / `owner_account_id` to int where they referenced UUID accounts.

## R7 — Application id generation

**Decision**: Remove client-side UUID for in-scope PKs; rely on DB. Code that compared id strings still works if coerced to str for display, but storage is int.

## R8 — ~/.ft

**Decision**: Optional delivery gate: backup and upgrade after tests green (same as 015).

## R9 — 开放单腿关系端点

**Decision**: `ordered_fact_a` 与 `ordered_fact_b` 保持 nullable INTEGER/BIGINT；将 015 的 NULL 或空字符串 sentinel 规范化为 NULL，只对其他非空值要求映射。

**Rationale**: 真实 015 SQLite 数据中 23 条开放 `refund_offset` / `transfer_pair` 关系的
`ordered_fact_b` 为 NULL。这是既有关系合同，不是损坏 FK；将它定义成 NOT NULL 会阻断安全升级。

**Alternatives considered**: 用 primary 端点填充空 ordered 端点（拒绝：改变关系事实）；删除这些关系（拒绝：丢失审计数据）。

## R10 — PostgreSQL 历史财富 FK

**Decision**: 历史迁移显式声明当时的字符串 UUID FK 类型，不能以当前已 bigint 化的 ORM metadata 创建旧 revision 表。

**Rationale**: PG 严格校验 FK 类型；SQLite 的宽松类型系统掩盖了该缺陷。
