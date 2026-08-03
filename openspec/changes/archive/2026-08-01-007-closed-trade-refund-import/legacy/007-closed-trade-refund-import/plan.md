# Implementation Plan: Import No-Skip, Raw Payload, Unified Scan

**Branch**: `007-import-no-skip` / `pr/007-import-no-skip-to-refactor-web`  
**Date**: 2026-07-22  
**Spec**: [spec.md](./spec.md)

## Summary

1. No silent skip; whitelist unpaid-closed + failed-repay only.
2. No funding_status.
3. **Import**: facts + **raw payload contract**; **no** relation inserts.
4. **Scan**: Phase A platform refunds → Phase B mirror → **Phase C transfer** → Phase D bank refund/weak/open-leg.
5. Reuse pure matchers (`platform_refund.py`); call from relation check, not statement_import.
6. Fail closed on mapping/parse errors; Decimal; dual-backend.

## Technical Context

Python 3.11+, SQLAlchemy, Alembic, uv. SQLite + PostgreSQL.

## Constitution Check

| Principle | Status | Notes |
|---|---|---|
| I. Financial correctness | PASS | No silent drop; relation not amount rewrite |
| II. Spec Kit | PASS | Living-spec update this session |
| III. Test-first | PASS | tests for no import relations; scan phases |
| IV. Dual DB | PASS | payload + relations matrix |
| V. Boundaries | PASS | import vs scan orchestration explicit |

## Project Structure

```text
src/ft/convert.py                      # no-skip; populate raw fields; no amount netting authority
src/ft/domain/platform_refund.py       # pure alipay/wechat matchers
src/ft/application/statement_import.py # stop create_import_refund_offsets
src/ft/application/relations.py        # Phase A then existing B/C
src/ft/adapters/relational/imports.py  # payload persistence
tests/test_platform_refund_matchers.py
tests/test_convert.py
tests/test_statement_import_*.py
tests/test_transaction_relations_*.py
```

## Implementation Phases

### A — Spec alignment (this change)
- Artifacts updated; remove import-time relation requirement.

### B — Import path
- Ensure payload keys written for alipay/wechat/bank.
- Remove/disable `create_import_refund_offsets` from import success path.
- Acceptance counters unchanged.

### C — Scan Phase A
- Before mirror/transfer loops, run platform hard-key refund matching over workspace facts+payload.
- Persist refund_offset; skip if active relation exists.
- Auth-unfreeze pairs.

### D — Phase order
- Enforce A → existing mirror → transfer/bank refund paths (C).

### E — Tests & real bills
- Assert import creates 0 platform refund relations.
- Assert check creates alipay/wechat pairs.
- PG when available; optional real bills re-run.

## Complexity

| Item | Why |
|---|---|
| Scan needs raw payload join | Hard keys live in source fields |
| Phase ordering | Product requirement |

## Risks

| Risk | Mitigation |
|---|---|
| Payload incomplete on old rows | Contract for new imports; no mandatory backfill |
| Double relations from old import path | Skip active business keys |
| Performance | Index by txn_id/status in Phase A; not full Cartesian |
