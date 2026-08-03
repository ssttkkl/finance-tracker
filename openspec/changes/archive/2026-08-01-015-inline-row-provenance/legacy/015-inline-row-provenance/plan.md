# Implementation Plan: 正式事实结构清理（015）

**Branch**: `015-inline-row-provenance` | **Date**: 2026-07-24 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/015-inline-row-provenance/spec.md`

## Summary

One-shot **schema + application cutover** for cash/investment formal facts:

1. **Inline row provenance**: drop `import_batches` / `raw_files` / `raw_records` and `raw_record_id`; store `source_type` + `record_id` + `source_payload` on facts; idempotency = **`record_id` × `source_type`** (import channel name × row key) within workspace, active-only for cash soft-delete.
2. **Drop dead cash columns**: offset_* (6), `proposed_action`, `locked`, `transfer_account`, `source`, `bill_source`.
3. **Drop job/over-design tables**: `fact_deletion_events`, `record_revisions`, `relation_check_runs`; drop fact `revision` if only used for unbuilt edit history; **keep** `ledger_snapshots`.
4. **Drop investment `price`**; projection derives unit price from legs (+ commission rules).
5. **No long-term shims**; dual-backend parity; migrate real **`~/.ft/finance-tracker.db`** once after code lands (backup first).

## Technical Context

**Language/Version**: Python 3.11+  
**Primary Dependencies**: SQLAlchemy, Alembic, psycopg, uv, pytest  
**Storage**: PostgreSQL + file SQLite via explicit `FT_DATABASE_URL`  
**Testing**: pytest; SQLite + real PostgreSQL matrix for migration and import/relations/projection  
**Target Platform**: macOS/Linux CLI  
**Project Type**: CLI + Application Service + relational adapters  
**Performance Goals**: Personal-finance scale; migrate ~12k cash + ~1k inv + raw join on `~/.ft` in one shot  
**Constraints**: Exact decimal; fail-closed unsafe backfill; no dual-write; no digest formalize gate; no bare `record_id` cross-channel dedupe  
**Scale/Scope**: models, Alembic `20260724_08_*`, import orchestration, convert/schema constants, relations assembly, wealth cash_kind, investment_projection, repositories, tests, `~/.ft` ops

## Constitution Check

*GATE: pre-research and post-design — PASS both*

| Principle | Status |
|---|---|
| I 财务正确性与可审计性 | PASS — money on formal facts/legs; raw payload only for match/debug; fail-closed if required source backfill impossible; projection parity tests |
| II Spec Kit 规格驱动 | PASS — 015 artifacts only; implementer follows tasks |
| III 测试先行与验证证据 | PASS — failing tests for schema, idempotency key, deleted columns/tables, no price, dual backend, then implement |
| IV 显式数据库选择与行为等价 | PASS — parity matrix; no fallback/dual-write/cross-backend auto-migrate |
| V 清晰边界与最小复杂度 | PASS — drop unused shells; keep ledger_snapshots; no wealth redesign |

### Parity Matrix (PostgreSQL / SQLite)

| Dimension | PostgreSQL | SQLite | Notes |
|---|---|---|---|
| Drop import/raw tables | DROP TABLE CASCADE order | same / rebuild dependents | same end absence |
| Add `source_type`, `source_payload` | ALTER ADD | rebuild if needed | same names/nullability |
| Idempotent unique | partial unique index WHERE non-empty + active | partial unique / equivalent filtered unique | same logical rule |
| Drop cash dead columns | DROP COLUMN | table rebuild | same end columns |
| Drop inv `price` | DROP COLUMN | rebuild | legs remain ExactDecimal |
| Drop job tables | DROP | DROP | same |
| Drop `revision` on facts | DROP if present | rebuild | wealth watermark uses id+content digest |
| Public/import contracts | app | app | same Application Service |
| Auto fallback / dual-write / cross-backend migrate | forbidden | forbidden | constitution |
| `~/.ft` one-shot | n/a (user SQLite file) | alembic upgrade on that URL | backup first |

**Permitted operational differences**: lock style, throughput, error text, surrogate key values — not money, identity sets, or column presence.

### SQLite migration boundary

Follow 014/005 pattern: if DROP COLUMN / multi-FK rebuild required, rebuild tables **in the same Alembic transaction**, copy rows, recreate indexes/FKs, `PRAGMA foreign_key_check` before commit. `downgrade()` = `NotImplementedError` (one-shot).

### Wealth watermark without `revision`

Replace fact `revision` in source digests with **stable content fingerprint**: e.g. hash of (account_id, occurred_at, amounts/category or action+legs, record_id, source_type) so rebuild still invalidates when formal content changes without an integer revision counter.

### Transfer classification after column drop

`cash_kind` / wealth paths that used `transfer_account or offset_group or offset_role` → use **`category` in {`transfer`,`transfer_in`,`transfer_out`}** (and accepted relations only where already authoritative). Do not reintroduce deleted columns.

### Import orchestration

- Stop `start_batch` / raw_records insert path.
- Formalize: compute `source_type` (channel), `record_id` (provider id or content key), `source_payload` (parsed row snapshot needed for relations).
- Skip if active row exists for `(workspace, source_type, record_id)`.
- Relations after import: `seed_fact_ids` only; no `relation_check_runs` persistence (optional: keep in-memory stats in CLI output only).

### `~/.ft` delivery

After tests green:

1. `cp ~/.ft/finance-tracker.db ~/.ft/finance-tracker.db.bak-015-$(date +%Y%m%d%H%M%S)`
2. `FT_DATABASE_URL=sqlite+pysqlite:////$HOME/.ft/finance-tracker.db FT_WORKSPACE_ID=... uv run alembic upgrade head`
3. Verify table list, column list, counts, sample balance
4. Document in quickstart; on failure restore bak

## Project Structure

### Documentation (this feature)

```text
specs/015-inline-row-provenance/
├── plan.md
├── research.md
├── data-model.md
├── database-schema.md
├── quickstart.md
├── contracts/
│   ├── cash-formal-row.md
│   ├── investment-formal-row.md
│   └── idempotency.md
├── checklists/requirements.md
└── tasks.md
```

### Source Code (impact surface)

```text
migrations/versions/20260724_08_inline_provenance_cleanup.py
src/ft/adapters/relational/models.py
src/ft/adapters/relational/repositories.py
src/ft/adapters/relational/imports.py
src/ft/adapters/relational/runtime.py
src/ft/adapters/relational/wealth_facts.py
src/ft/adapters/relational/uow.py
src/ft/application/statement_import.py
src/ft/application/investment_import.py
src/ft/application/relations.py
src/ft/application/cashflow.py
src/ft/application/queries.py
src/ft/domain/imports.py
src/ft/domain/investment_projection.py
src/ft/domain/queries.py
src/ft/schema.py
src/ft/convert.py
src/ft/adapters/statement_import.py
tests/**
docs/database-schema.md
```

## Complexity Tracking

| Choice | Why needed | Simpler alternative rejected |
|---|---|---|
| Table rebuild on SQLite for multi-column drop | SQLite limited ALTER | Leave dead columns — rejected by product |
| Keep ledger_snapshots | Product requires cache | Drop and recompute always — rejected |
| Derive price not store | Avoid dual source | Keep price column — rejected |

## Implementation Phases (for tasks)

0. Research locked (this plan + research.md)
1. Contracts + data-model + quickstart
2. Alembic migration + model
3. Import/idempotency/application
4. Relations/wealth/projection without deleted fields
5. Tests dual-backend
6. `~/.ft` backup + upgrade + verify
7. Docs sync `docs/database-schema.md`
