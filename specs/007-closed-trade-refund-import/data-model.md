# Data Model: 007 Import No-Skip & Platform Refund

## Entities (no new funding_status)

### CashTransaction (formal fact)
Existing fields remain authority for amount/currency/account.
**Optional metadata** (if not already present; prefer evidence/JSON over required columns when possible):
- `platform_status` (text, optional): e.g. 交易关闭, 已全额退款
- `origin_order_id` / txn linkage for alipay refunds (may live in evidence or existing record_id/txn fields)

**Rules**:
- Paid closed expense: normal negative amount.
- Refund: positive amount; never rewrite original expense amount.
- Unpaid-closed / failed-repay: **not persisted** as formal facts (skipped).

### TransactionRelation
Existing 006 model:
- `kind=refund_offset`
- `status=accepted|pending_review|...`
- `rule_id` e.g. `import.alipay.order_prefix.v1`, `import.wechat.partial_embedded.v1`
- open-leg allowed for multi-candidate per 006

### ImportBatch / acceptance counters
Logical result fields (API/DTO, not necessarily new tables):
- `source_lines`
- `published`
- `idempotent_hits`
- `skipped_unpaid_closed`
- `skipped_failed_repay`
- `failed` (if fail-closed)

Constraint: `source_lines = published + idempotent_hits + skipped_unpaid_closed + skipped_failed_repay` on success.

## Validation
- Decimal amounts exact.
- Alipay order match: FR-013 only.
- WeChat match: FR-029; no alipay prefix.
- Dual backend: same counters and relation sets.
