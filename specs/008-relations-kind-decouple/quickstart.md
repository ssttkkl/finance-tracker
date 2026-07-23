# Quickstart: Relations Kind Decouple validation

**Feature**: 008-relations-kind-decouple  
**Date**: 2026-07-22

## Prerequisites

- Repo with 007 relation scan behavior available (Phase A→D already product intent).
- `uv` env; `FT_DATABASE_URL` / workspace as usual for local SQLite tests.
- Optional: snapshot of `~/.ft/finance-tracker.db` or test fixtures for baseline.

## 1. Baseline (before or on facade-still-present commit)

```bash
export FT_WORKSPACE_ID=default
# export FT_DATABASE_URL=... if using real DB
uv run pytest tests/test_transaction_relations_payment_mirror.py \
  tests/test_transaction_relations_transfer.py \
  tests/test_transaction_relations_refund.py \
  tests/test_transfer_phase_c.py \
  tests/test_transaction_relations_open_leg.py \
  tests/test_platform_refund_matchers.py -q
```

Optional real-DB baseline export (document path you choose):

```bash
uv run ft relations check
# export active relations kind/status/keys to a file for diff
```

## 2. After structure migration

```bash
uv run pytest tests/test_transaction_relations_*.py tests/test_transfer_phase_c.py \
  tests/test_relations_pack_boundaries.py tests/test_relations_pipeline_order.py -q
```

Expected: all pass; boundary test fails if packs cross-import signals.

## 3. Dual backend

Run project’s existing PostgreSQL relation/integration target when available (same commands as 006/007 CI). No new migrations to apply.

## 4. Parity check (SC-001)

1. Full recompute on same data as baseline.  
2. Diff accepted + pending_review business identities.  
3. Only superseded-only diffs allowed if documented.

## 5. Maintainer drill (SC-002 / SC-003)

- Touch only `transfer/signals.py` comment or constant—confirm review path doesn’t require refund files.  
- Touch only `refund/signals.py`—confirm transfer files untouched.

## 6. Smoke CLI

```bash
uv run ft relations check
uv run ft relations pending
```

Review commands unchanged.


## 7. SC-004 documentation locate drill

From repo root, open:

1. `specs/008-relations-kind-decouple/spec.md` — FR-003 (A→D), FR-005 (diamond under refund)
2. `specs/008-relations-kind-decouple/contracts/pipeline-phases.md` — phase table + seed policy
3. `specs/008-relations-kind-decouple/contracts/match-context.md` — edge-only collaboration

Pass criterion: locate order and diamond edge-only rule in under 15 minutes (T037).

## SC-004 walkthrough result

PASS (2026-07-22): FR-003/005 + contracts/pipeline-phases.md + match-context.md locate Phase A→D and diamond edge-only + preload seed policy within 15 minutes.


## Follow-up completion (2026-07-22)

- B–D recognition sole entry: `run_relation_phases` from Application `check`
- `core/mirror_graph.py` owns connectivity/canonical (projection no longer imports mirror pack)
- `_monolith_backup.py` removed


## Phase A split (follow-up)

- Domain: `refund/hard_key.py` → `match_phase_a_platform_refunds` (proposals only)
- Application: load detailed rows + linked pairs → domain match → `_persist_proposal`
- B–D: still `run_relation_phases`

## FactCandidateIndex injection

- `FactCandidateIndex(facts, source_group=..., refund_gates=DefaultRefundTextGates())`
- core no longer imports refund.signals

