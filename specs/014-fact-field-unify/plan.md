# Implementation Plan: Fact Field Unification

**Branch**: `014-fact-field-unify` | **Date**: 2026-07-24 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/014-fact-field-unify/spec.md`

## Summary

One-shot alignment of **cash** (`cash_transactions`) and **investment** (`investment_events`) formal fact storage and public field names: shared catalog names (`occurred_at`, `currency`, `note`, …), physical renames (`description`→`note`, `kind`→`action`), promote investment core legs from JSON into columns, **strip** promoted keys from residual `payload`, update repositories/exports/tests end-to-end. **No long-term compatibility shims.** Dual-backend (PostgreSQL + SQLite) equivalent outcomes; conflict on promoted-core disagreement **fail closed**.

## Technical Context

**Language/Version**: Python 3.11+  
**Primary Dependencies**: SQLAlchemy, Alembic, psycopg, uv, pytest  
**Storage**: PostgreSQL and file SQLite via explicit `FT_DATABASE_URL` (no fallback/dual-write)  
**Testing**: pytest; SQLite automation + real PostgreSQL matrix for migration and fact parity  
**Target Platform**: macOS/Linux CLI  
**Project Type**: CLI + Application Service + relational adapters  
**Performance Goals**: Personal-finance scale; one-shot migration over existing workspaces  
**Constraints**: Exact decimal; fail-closed migration conflicts; no dual-read/dual-write after cutover; two tables retained  
**Scale/Scope**: ORM models, Alembic revision, cash/investment repositories, schema CSV constants, wealth fact loaders, projection row assembly, tests/fixtures

## Constitution Check

*GATE: pre-research and post-design — PASS both*

| Principle | Status |
|---|---|
| I 财务正确性与可审计性 | PASS — projection parity SC-002; fail-closed core conflicts FR-016; exact decimal columns; provenance uniqueness retained |
| II Spec Kit 规格驱动 | PASS — 014 artifacts only; main session produces plan/tasks/analyze; implementer runs implement |
| III 测试先行与验证证据 | PASS — failing tests first for renames, promotion, strip, public headers, conflict migration, dual backend |
| IV 显式数据库选择与行为等价 | PASS — parity matrix below; no auto fallback / dual-write / implicit cross-backend migrate |
| V 清晰边界与最小复杂度 | PASS — no single ledger table; no compat layer; residual payload only for non-core keys |

### Parity Matrix (PostgreSQL / SQLite)

| Dimension | PostgreSQL | SQLite | Notes |
|---|---|---|---|
| Cash `description` → `note` | RENAME COLUMN / equivalent | table rebuild if needed | same end column name |
| Investment `kind` → `action` | RENAME COLUMN | same / rebuild | values already action strings |
| Investment core columns | NUMERIC/TEXT via ExactDecimal | ExactDecimal text | same logical types |
| Promote + strip payload cores | JSON ops in migration | JSON text | same end residual keys |
| Conflict fail-closed | abort revision/unit | abort | report fact id |
| Public field names | n/a (app) | n/a | same Application Service |
| Auto fallback / dual-write / cross-backend migrate | forbidden | forbidden | constitution |

**Permitted operational differences**: lock style, concurrency throughput, driver error text; not field names, nullability, or projection money results.

### SQLite migration boundary

Follow 005 pattern: if SQLite cannot RENAME in place safely under FK constraints, rebuild affected tables **inside the same Alembic transaction**, copy all rows, recreate FKs/uniques, `PRAGMA foreign_key_check` before commit. No commit-before-rebuild FK disable escape hatch.

## Project Structure

### Documentation (this feature)

```text
specs/014-fact-field-unify/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── shared-fact-fields.md
│   ├── cash-formal-row.md
│   └── investment-formal-row.md
├── checklists/requirements.md
└── tasks.md
```

### Source Code (impact surface)

```text
src/ft/schema.py
src/ft/adapters/relational/models.py
src/ft/adapters/relational/repositories.py
src/ft/adapters/relational/wealth_facts.py
src/ft/adapters/relational/imports.py
src/ft/adapters/relational/queries.py
src/ft/domain/imports.py
src/ft/domain/investment_projection.py
migrations/versions/YYYYMMDD_HH_fact_field_unify.py
tests/
```

**Structure Decision**: Existing single-package layout; no new top-level apps.

## Complexity Tracking

No constitution violations requiring justification.

## Phase 0 / Phase 1

See [research.md](./research.md), [data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md).

## Implementation approach (for tasks)

1. **Tests first**: public field names, model columns, migration promote+strip, conflict fail, projection parity fixture, PG+SQLite.
2. **Alembic one-shot**: rename columns; add investment leg columns; data backfill from payload; strip cores; fail on conflict.
3. **ORM + repositories + schema constants**: write/read formal columns only; public rows use catalog names (`note`, `occurred_at`).
4. **Wealth/import/CLI mechanical updates**.
5. **Remove** any temporary dual-read if introduced during green-up (must not ship).
6. Dual-backend evidence documented in quickstart.

### Public time field

Public and formal time field name is **`occurred_at`**. Update `CASH_CSV_FIELDS` / export DTOs; ISO formatting stays in adapters.

### Investment residual payload

Empty `{}` when no non-core keys. Strip set: see data-model CORE_KEYS.

### Conflict detection

If payload action/currency disagrees with column sources after case-normalization, abort with fact id + workspace_id.
