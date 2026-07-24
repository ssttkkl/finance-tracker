# Research: Fact Field Unification

**Feature**: 014-fact-field-unify  
**Date**: 2026-07-24

## R1 — Physical renames vs add+drop

**Decision**: Use physical column rename where the dialect supports it (`description`→`note`, `kind`→`action`); SQLite may rebuild tables if rename under FK is unsafe (same pattern as 005).

**Rationale**: Spec forbids dual-column end state; values in `kind` are already action strings; cash `description` is 1:1 with `note`.

**Alternatives considered**:
- Add new column + copy + drop: more churn, temporary dual columns during migration only (acceptable mid-revision, not end state).
- Keep old names with app aliases: rejected by clarify (no compat).

## R2 — Investment payload after promotion

**Decision**: Backfill formal columns from payload, then **delete promoted keys** from JSON; residual only non-core extensions; empty `{}` OK.

**Rationale**: User: no dual-write compatibility; readers must use columns only.

**Alternatives considered**:
- Keep full historical payload: rejected (dual source of truth).
- Drop payload column entirely: deferred — raw_records still hold source; residual may hold importer breadcrumbs; keep nullable/empty JSON column for now.

## R3 — Public field break

**Decision**: Break public CSV/list keys to catalog (`note`, `occurred_at`); update all in-repo tests in this feature.

**Rationale**: Early product stage; long-term aliases reintroduce synonym debt.

**Alternatives considered**: Alias headers — rejected in clarify.

## R4 — Conflict policy

**Decision**: Fail closed migration/unit when promoted core disagrees between column and payload.

**Rationale**: Constitution I; no silent winner.

**Alternatives considered**: Prefer payload or prefer column with audit log — rejected.

## R5 — account_name in investment payload

**Decision**: Strip `account_name` from residual payload after cutover; resolve name via `accounts` join on read (current repository already joins). Writers may still accept account_name in **input** commands to resolve account_id, but must not persist it as ownership truth in payload cores.

**Rationale**: Ownership is `account_id`; denormalized name drifts.

## R6 — Exact decimal for new amount columns

**Decision**: `from_amount`, `to_amount`, `price`, `commission` use existing `ExactDecimal` (PG NUMERIC 38,18 / SQLite text).

**Rationale**: Matches cash `amount` and project money rules.

## R7 — Empty string vs null

**Decision**: Text legs default `""`; numeric optional legs default `None` or `0` consistent with current projection (`_decimal(..., default="0")` for missing). Migration maps missing payload keys to empty string / zero the same way projection does today so baselines match.

**Rationale**: Projection already treats missing as zero for amounts; preserve that.

## R8 — No NEEDS CLARIFICATION remaining

All plan technical choices derive from clarified spec; no open research blockers.
