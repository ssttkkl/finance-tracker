# Data Model: Relations Kind Decouple

**Date**: 2026-07-22  
**Feature**: 008  
**Note**: No durable schema change. This document describes **in-memory / domain** structures introduced or clarified for decoupling. Persisted `transaction_relations` and cash facts remain as in 006/007.

## Unchanged persisted entities (reference)

| Entity | Owner feature | Notes |
|---|---|---|
| Cash formal fact | 004/006 | Active vs logically deleted |
| `transaction_relations` | 006 | kind, status, evidence, keys, open-leg |
| `relation_check_runs` | 006 | check audit |
| Raw record payload | 007 | Phase A hard keys |

## New / clarified domain structures (not tables)

### MatchContext

| Field | Type (logical) | Required | Description |
|---|---|---|---|
| workspace_id | string | yes | Scope |
| used_fact_ids | set of fact id | yes | Facts already claimed by earlier proposals in this run (1:1 discipline) |
| accepted_mirrors | list of Edge | yes | Accepted payment_mirror edges from this run and/or preloaded accepted DB edges as pipeline defines |
| accepted_platform_refunds | list of Edge | yes | Accepted refund_offset edges usable as platform hard-key/platform refunds for diamond |
| accepted_transfers | list of Edge | optional | If a later step needs them (default empty for 008) |
| remaining_by_expense | map fact_id → Decimal | yes for refund paths | Remaining refundable amount per expense |
| fx_rate_provider | callable optional | no | Transfer FX repayment |

**Validation**: Context is read-only to packs; only pipeline mutates after each phase.

**Seed policy**: For full recompute / normal check, pipeline MUST initialize `accepted_mirrors` and `accepted_platform_refunds` from persisted accepted relations in the workspace, then append this-run accepts after phases A and B. This-run-only seeding is not permitted for full check (SC-001 / 007 parity).

### Edge

| Field | Description |
|---|---|
| fact_a_id / fact_b_id | Endpoints (ordered or canonicalized per kind rules when stored) |
| kind | Relation kind string |
| subtype | Optional (e.g. credit_repayment) |

### RelationProposal (existing, package-local type)

Unchanged fields vs 006: kind, status, confidence, rule_id, primary/secondary fact ids, evidence, subtype, open-leg candidate lists.

### Rule boundary (logical module, not row)

| Boundary | Owns | Produces kind |
|---|---|---|
| mirror | platform×bank matching | payment_mirror |
| transfer | signals, taxonomy, withdraw, repayment | transfer_pair |
| refund | refund signals, P2P family, hard key, merchant, open-leg, **diamond** | refund_offset |

### Pipeline run (ephemeral)

| Field | Description |
|---|---|
| phases | A, B, C, D in order |
| proposals | Concatenated list for Application to persist with existing idempotency |

## State transitions

No new relation statuses. Existing: `pending_review` → `accepted` / `rejected`; `superseded` via audit chain (006).

## Dual-backend notes

No new columns/indexes. Relation persistence remains through existing repositories; SQLite and PostgreSQL must continue to accept the same Application-level relation DTOs.

## Migration of data

None. Optional one-time full recompute after deploy for confidence; not a schema migration.
