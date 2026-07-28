# Implementation Plan: Transaction Relations (+ Open-Leg Pending)

**Branch**: `006-open-leg-pending` (extends `006-transaction-relations`) | **Date**: 2026-07-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/006-transaction-relations/spec.md` including open-leg pending clarifications (FR-042–047, SC-019–023).

## Summary

在既有关系层（`payment_mirror` / `transfer_pair` / `refund_offset`、关系审查列表、投影、逻辑删除）之上，增加 **待配对关系**：

- 仅 `refund_offset` 与 `transfer_pair`（含 `credit_repayment`）
- 当对侧不唯一（≥2 合法候选）或 0 候选但锚点形态成立时，落 **1 条** `pending_review`，锚点非空、**对侧可空**
- 建议对侧仅存 `evidence.candidate_fact_ids`（top-K=20）+ `candidate_count`
- 用户 accept 时 **必须** 提供 `other_fact_id`，一步绑定为双边 `accepted`
- 待配对关系 **永不** 参与报表投影
- `payment_mirror` 保持双边 + 1:1 greedy，不使用待配对关系
- 消除「1 退款 × N 消费」双边 pending 扇出

既有 006 合同不变：事实不可变、Decimal 严格、导入后检查、双后端等价。

## Technical Context

**Language/Version**: Python 3.11+  
**Primary Dependencies**: SQLAlchemy, Alembic, psycopg, uv, pytest  
**Storage**: PostgreSQL and file SQLite via explicit `FT_DATABASE_URL`  
**Testing**: pytest; SQLite + real PostgreSQL for schema/relation/review/projection  
**Target Platform**: macOS/Linux CLI  
**Project Type**: CLI + Application Service + relational adapters  
**Performance Goals**: Full check ≤60s on ≥10k facts (index candidates); open-leg reduces pending row volume  
**Constraints**: Exact Decimal; open-leg only pending; accepted bilateral only; no placeholder facts; dual-backend parity  
**Scale/Scope**: Schema nullability + dual business keys; domain proposal shapes; RelationService fan-out control; review accept-with-other; CLI; tests; docs

## Constitution Check

*GATE: pre-research and post-design*

| Principle | Status |
|---|---|
| I 财务正确性与可审计性 | PASS — open-leg never affects nets; accept binds real facts only; audit on accept/reject; no placeholder facts |
| II Spec Kit 规格驱动 | PASS — spec FR-042–047 first; plan/tasks before implementer |
| III 测试先行与验证证据 | PASS — red tests for multi-candidate → 1 open-leg, accept+other, reject key, projection ignore |
| IV 显式数据库选择与行为等价 | PASS — migration + uniqueness must work on PG and SQLite; parity matrix extended |
| V 清晰边界与最小复杂度 | PASS — same relation table; no second inbox product; mirror unchanged |

### Parity Matrix (PostgreSQL / SQLite) — open-leg deltas

| Dimension | PostgreSQL | SQLite | Notes |
|---|---|---|---|
| `secondary_fact_id` NULL for open-leg pending | yes | yes | accepted/mirror always non-null |
| Open-leg active uniqueness on anchor key | partial unique index | partial unique index (SQLite 3.8+) | `(workspace, kind, subtype, anchor_fact_id)` where open & active |
| Bilateral uniqueness ordered pair | existing | existing | both facts non-null |
| Accept open-leg requires other_fact_id | app service | app service | fail closed |
| Projection ignores open-leg | same pure function | same | FR-010/033 |
| Auto no second open-leg per anchor | app + unique | app + unique | |

**Permitted operational differences**: unchanged (locks, throughput, driver text).

## Project Structure

### Documentation

```text
specs/006-transaction-relations/
├── plan.md              # this file
├── research.md          # Decision 17 open-leg
├── data-model.md        # nullable secondary + open key
├── quickstart.md        # § open-leg scenarios
├── contracts/
│   ├── review-inbox.md  # accept + other_fact_id
│   ├── relation-check.md
│   ├── logical-delete.md
│   └── report-projection.md
├── checklists/requirements.md
└── tasks.md
```

### Source Code (delta surface)

```text
migrations/versions/20260722_06_open_leg_pending.py   # NEW
src/ft/domain/relations.py                            # OpenLeg proposal; multi→one; no expense fan-out
src/ft/application/relations.py                       # persist open; accept(other_id); keys
src/ft/adapters/relational/models.py                  # nullable secondary; anchor; constraints
src/ft/adapters/relational/repositories.py
src/ft/cli.py                                         # relations accept --other
tests/test_transaction_relations_open_leg.py          # NEW
tests/test_transaction_relations_refund.py            # multi-candidate asserts 1 open-leg
tests/test_transaction_relations_transfer.py
tests/test_alembic_migration.py                       # tip includes 06
```

## Implementation Approach

### 1. Schema migration `20260722_06`

Goals:

1. Allow **open-leg pending** rows for `refund_offset` / `transfer_pair` only.
2. Keep **bilateral** uniqueness for two-fact rows.
3. Enforce **one active open pending (or reject occupancy) per anchor**.
4. `payment_mirror` and all `accepted` rows remain two-fact.

**Recommended shape** (both backends):

| Column | Change |
|---|---|
| `secondary_fact_id` | `nullable=True` |
| `secondary_fact_type` | `nullable=True` or empty string when open |
| `ordered_fact_b` | allow empty string `''` for open-leg sentinel **or** keep non-null via sentinel `__open__` — pick one and test both backends |
| `anchor_fact_id` | **add** `String(36) NOT NULL` (backfill: refund→secondary/refund leg mapping; transfer→primary/out or evidence; bilateral→deterministic from pair) |
| optional `open_leg` | boolean/generated: `secondary_fact_id IS NULL` |

**Constraints**:

```text
CHECK: status != 'accepted' OR (secondary_fact_id IS NOT NULL)
CHECK: kind != 'payment_mirror' OR (secondary_fact_id IS NOT NULL)
CHECK: secondary_fact_id IS NULL → status IN ('pending_review','rejected','superseded')
       AND kind IN ('refund_offset','transfer_pair')

Bilateral unique (existing spirit):
  (workspace_id, kind, ordered_fact_a, ordered_fact_b, subtype, active_slot)
  for rows with secondary_fact_id IS NOT NULL

Open-leg unique (partial):
  (workspace_id, kind, subtype, anchor_fact_id)
  WHERE secondary_fact_id IS NULL AND active_slot = 'active'
  (rejected open rows: set active_slot to relation id or 'rejected:<id>' so key frees only on supersede reopen — mirror existing bilateral rejected occupancy pattern)
```

**Backfill**: all existing rows get `anchor_fact_id` from kind role mapping (refund_offset secondary=refund → anchor=secondary; transfer primary=out → anchor=primary; mirror either).

**Downgrade**: refuse or only if no open-leg rows remain (document).

### 2. Domain

- `RelationProposal` allows `secondary_fact_id: str | None`, `open_leg: bool`, `anchor_fact_id: str`.
- `evaluate_refund_offset`:
  - Collect matches as today (rules unchanged).
  - If unique strong auto → bilateral accepted proposal.
  - If unique match not auto → bilateral pending.
  - If `len(matches) >= 2` OR (`len==0` and refund seed signal) → **one** open-leg pending; `candidate_fact_ids` sorted (time/amount proximity), top-20; `candidate_count=len(matches)`.
  - Expense seeds: MUST NOT emit multi-candidate bilateral fan-out; prefer only strong unique or skip (refund seed owns open-leg).
- `evaluate_transfer_pair`: same multi/zero → open-leg; anchor = stronger signal leg; `anchor_role` in evidence.
- `match_payment_mirrors_greedy`: unchanged; never open-leg.
- Projection: skip relations with null other / `open_leg`.

### 3. Application / Review

- `_persist_proposal`: open-leg key path; no second open for same anchor.
- `accept(relation_id, *, other_fact_id=None, actor, reason)`:
  - bilateral: other_fact_id optional (already set).
  - open-leg: **required** other_fact_id; validate legality; write both legs; status accepted; clear open.
- `reject`: occupies open anchor key.
- Check loop: do not let expense seeds recreate open anchors.

### 4. CLI

```text
ft relations pending
ft relations accept <id> --other <fact_id>   # required for open-leg
ft relations reject <id>
```

### 5. Tests (red first)

- Multi merchant candidates → 1 open-leg, N not written
- Zero candidate refund signal → 1 open-leg empty candidates
- Accept without other fails; with legal other → accepted + projection
- Illegal other fail closed
- Reject suppresses re-open
- Unique weak remains bilateral pending (optional assert)
- Mirror never null secondary
- Migration upgrade on SQLite + PG
- Real-ledger style: 1 京东退货 × 14 京东消费 → 1 pending row

### 6. Docs

- quickstart open-leg section; README pointer if needed.

## Complexity Tracking

None beyond justified schema dual-key (required by FR-013 open vs bilateral).

## Post-Design Constitution Check

**PASS**. Open-leg is review-only; financial effects only after bilateral accept; dual-backend uniqueness specified; no fact fabrication; Spec Kit artifacts updated before implement.
