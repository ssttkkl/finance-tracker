# Implementation Plan: Import No-Skip & Platform Refund at Import

**Branch**: `007-import-no-skip` | **Date**: 2026-07-21 | **Spec**: [spec.md](./spec.md)

## Summary

1. **No silent skip** on all wired bill sources; only documented whitelist skips (Alipay unpaid-closed, failed-repay-no-debit) with counters + code comments.
2. **No `funding_status` field** — paid closed expenses import as negative amounts; refunds positive; pairs cancel via amounts + `refund_offset`.
3. **Alipay**: import paid `交易关闭|支出`; skip unpaid-closed / failed-repay; order-key (`==`/`_`/`*`) unique → import-time `refund_offset`; auth-hold→unfreeze → `refund_offset`.
4. **WeChat**: import all dual-row refund legs; import-time `refund_offset` (pay + embedded amount + residual + transfer-return); **no** amount netting in convert; **no** Alipay txn-prefix.
5. **Relation scan**: does not own Alipay order-refund or WeChat dual-row main path; keeps mirror/transfer/exceptions.
6. **Mapping miss / parse errors**: fail closed.

## Technical Context

**Language/Version**: Python 3.11+  
**Primary Dependencies**: `ft` CLI, Application Services, SQLAlchemy 2.x, Alembic, uv  
**Storage**: PostgreSQL + SQLite via `FT_DATABASE_URL`  
**Testing**: pytest; dual-backend when `FT_TEST_POSTGRES_URL` set  
**Constraints**: Decimal money; dual-backend equivalence; formal facts immutable  
**Scale/Scope**: convert + statement_import + import-time relation insert; minimal check skip-if-linked

## Constitution Check

| Principle | Status | Notes |
|---|---|---|
| I. Financial correctness | PASS | No silent discard; Decimal; relation not amount rewrite |
| II. Spec Kit driven | PASS | spec/plan/tasks sole truth |
| III. Test-first | PASS | failing tests per skip removal + refund import |
| IV. Dual DB | PASS | acceptance counters + relations equivalent |
| V. Boundaries | PASS | import writes platform refunds; 006 owns cross-source |

**Post-design**: PASS — no funding_status column; platform_status/origin metadata only as needed.

## Project Structure

```text
src/ft/convert.py
src/ft/importers/wechat.py          # INCOME_OK usage audit
src/ft/application/statement_import.py
src/ft/application/relations.py     # skip if already linked; optional import helper
src/ft/domain/relations.py          # optional pure match helpers for import
tests/test_import_no_skip_*.py
tests/test_import_alipay_refund_*.py
tests/test_import_wechat_refund_*.py
specs/007-closed-trade-refund-import/
```

## Implementation Phases

### Phase A — Acceptance contract
- Source line counts; published + idempotent + whitelist skips.
- Expose skip reason counters on import result.

### Phase B — Remove silent skips
- Alipay: stop blanket skip of 交易关闭/已关闭/还款失败; apply FR-008a/c only.
- WeChat: remove silent expense-fail and income-not-INCOME_OK continues; fail-closed or import.
- Mapping: fail closed on miss.
- Zero-amount: import (non unpaid-closed).

### Phase C — Alipay closed + order-key refund_offset
- Paid closed expense import.
- origin_order_id / prefix match FR-013.
- Import-time refund_offset unique; multi → open-leg/pending.
- Auth-hold → unfreeze FR-014a.

### Phase D — WeChat dual-row refund_offset
- Both legs import; no amount rewrite.
- FR-029 rules: mer/txn, full, partial embedded (≤60d), residual split, transfer return.
- Tests: 味多美 30d, JD split, 对方已退还, redpacket mer.

### Phase E — Scan boundary
- Relation check skips pairs with active refund_offset from import.
- No alipay order-refund 补漏 mission; wechat main path import-owned.

### Phase F — Verification
- Unit/integration + real `~/.ft/bills` alipay/wechat dry or copy import counts.
- PG matrix when URL set.

## Complexity Tracking

| Violation | Why needed | Simpler alternative rejected |
|---|---|---|
| Import writes relations | Spec: platform keys known at import | Full defer to 006 scan loses order keys / dual-row |
| Whitelist skips | Unpaid-closed / failed-repay pollute balances | funding_status enum rejected by product |

## Risks

| Risk | Mitigation |
|---|---|
| WeChat residual mis-attach | Require same pay + status T + time cluster; open-leg if multi origin |
| Alipay unpaid-closed misclassified | Require all three FR-008a predicates + tests |
| Convert still nets refunds | Delete/bypass _pair_refunds amount mutation; tests assert amounts |
