# Implementation Plan: Transaction Relations

**Branch**: `006-transaction-relations` | **Date**: 2026-07-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/006-transaction-relations/spec.md`

## Summary

在既有 `ft import` 正式事实链路之后，增加**追加式账务关系层**：识别并持久化 `payment_mirror`、`transfer_pair`（含 `credit_repayment` subtype）、`refund_offset`；弱匹配进入 Review Inbox；报表/投影只读取 **活跃正式事实 + accepted 关系**。禁止因配对/退款/镜像而物理删除或改写原始事实金额。历史错误重复事实走**用户可审计逻辑删除**；删除后再导入同 `source_identity` 发布**新活跃正式事实**（不静默 undelete）。匹配信号复用 main 的 dedup/reconcile/transfer_rules/convert 退款语义，但落盘改为关系 + 投影。PostgreSQL 与 SQLite 用户可见行为等价。

## Technical Context

**Language/Version**: Python 3.11+  
**Primary Dependencies**: SQLAlchemy, Alembic, psycopg, uv, pytest  
**Storage**: PostgreSQL and file SQLite via explicit `FT_DATABASE_URL` (no fallback/dual-write)  
**Testing**: pytest; SQLite automation + real PostgreSQL matrix for persistence/relations/projections  
**Target Platform**: macOS/Linux CLI  
**Project Type**: CLI + Application Service + relational adapters  
**Performance Goals**: Personal finance scale; post-import relation check over bounded candidate windows (not full-table scans)  
**Constraints**: Exact Decimal; immutable formal facts; relation-only pairing; active-only row idempotency; no amount tolerance; dual-backend parity  
**Scale/Scope**: Relation model, post-import check, review decisions, logical delete + re-import, report projections, account aliases, CLI contracts, docs

## Constitution Check

*GATE: pre-research and post-design*

| Principle | Status |
|---|---|
| I 财务正确性与可审计性 | PASS — facts immutable; relations append-only with evidence; refund nets only via accepted relations; exact Decimal; no float `0.01` tolerance; logical delete audited |
| II Spec Kit 规格驱动 | PASS — 006 artifacts drive change; main session does not implement product code until tasks/analyze gate |
| III 测试先行与验证证据 | PASS — failing tests before impl for each relation kind, review, delete/re-import, dual backend |
| IV 显式数据库选择与行为等价 | PASS — parity matrix below; no fallback/dual-write/implicit cross-backend migration |
| V 清晰边界与最小复杂度 | PASS — relation layer beside import; no CSV reconcile revival; no FX product; no full Web UI required |

### Parity Matrix (PostgreSQL / SQLite)

| Dimension | PostgreSQL | SQLite | Notes |
|---|---|---|---|
| Schema: `transaction_relations` (+ optional check runs / aliases / deletion events) | yes | yes | same logical columns/constraints |
| Relation status machine | same states | same states | pending/accepted/rejected/superseded |
| Active formal fact definition | `deleted_at IS NULL` (or equivalent) | same | projection + matching + row idempotency |
| Post-import relation check after commit | yes | yes | failure never rolls back import facts |
| Report nets from facts+accepted relations | exact Decimal | exact Decimal adapter | same Application Service |
| Logical delete + re-import new active fact | yes | yes | no silent undelete; digest idempotency unchanged |
| Review accept/reject audit | yes | yes | same CLI/query contract |
| Auto fallback / dual-write / implicit cross-backend migrate | forbidden | forbidden | constitution |

**Permitted operational differences**: lock implementation, concurrency throughput, driver error text, optional async task scheduling latency — must not fork relation state, report nets, or review outcomes.

## Project Structure

### Documentation (this feature)

```text
specs/006-transaction-relations/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── relation-check.md
│   ├── review-inbox.md
│   ├── logical-delete.md
│   └── report-projection.md
├── checklists/requirements.md
└── tasks.md            # via /speckit-tasks
```

### Source Code (impact surface)

```text
src/ft/domain/relations.py                 # planned: kinds, statuses, evidence DTOs, projection pure functions
src/ft/domain/imports.py                   # keep raw fields; stop treating offset_* as authority
src/ft/application/statement_import.py     # after commit: schedule/run relation check; active-only formal publish
src/ft/application/relations.py            # planned: RelationService (check, review, supersede)
src/ft/application/cashflow.py             # logical delete entry; balance projection excludes deleted
src/ft/application/queries.py              # report/list use projection rules
src/ft/adapters/relational/models.py       # relations, aliases, deletion marker/events, active indexes
src/ft/adapters/relational/repositories.py
src/ft/adapters/relational/imports.py      # formal_fact_targets / active idempotency
src/ft/adapters/relational/queries.py
src/ft/adapters/relational/uow.py
src/ft/adapters/relational/runtime.py
src/ft/repositories/protocols.py
src/ft/cli.py                              # review / relation check / delete commands
src/ft/convert.py                          # ensure import path does not net refunds; convert may keep preview-only tracking
src/ft/report.py                           # consume relation-aware projections
migrations/versions/20260721_05_transaction_relations.py   # planned
tests/test_transaction_relations_*.py                      # planned matrix
tests/test_statement_import_mapping.py                     # update active idempotency
tests/test_relational_contract.py                          # update
README.md / docs/import-reconcile-flow.md                  # document relation layer
```

**Structure Decision**: Single-project CLI layout. Add a dedicated Application Service for relations; keep parsers/mapping unchanged; put rule matching in domain-pure modules with main-signal windows from spec; persist only via relational adapters.

## Implementation Approach

1. **Red tests (dual backend where persistence)**: payment_mirror/transfer/refund windows; pending vs accepted; review accept/reject; projection order; logical delete + re-import new active; no `duplicate_of`; legacy offset non-authority; concurrent check idempotency.
2. **Schema**: `transaction_relations`, relation check runs (optional but recommended), account aliases, formal-fact logical delete marker/event; partial/active uniqueness for source identity / raw linkage as decided in research.
3. **Domain rules**: port main signals with Decimal strict equality and fixed time windows; evidence JSON; confidence tiers.
4. **Import hook**: commit facts first; then relation check with seed = new active facts; never roll back import on check failure.
5. **Review CLI/query contract**: list pending; accept/reject/later; audit fields.
6. **Projection**: balance = all active facts; P&L order: mirror groups → exclude transfer_pair → apply refund_offset.
7. **Docs + green suite**; report missing PostgreSQL evidence if `FT_TEST_POSTGRES_URL` unset.

## Complexity Tracking

None. No constitution violations requiring justification.

## Post-Design Constitution Check

Re-evaluated after research/data-model/contracts/quickstart: **PASS**. Relation-only pairing preserves auditability; dual-backend parity specified; logical delete re-import defined without permanent identity ban; no CSV reconcile revival; no FX product creep.
