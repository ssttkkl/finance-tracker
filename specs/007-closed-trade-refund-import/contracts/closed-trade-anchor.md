# Contract: Closed/Failed Trade Anchor

## Published non-funding fact

When platform status ∈ closed/failed/revoked set for the source:

- formal cash fact **exists**
- `funding_status = non_funding`
- `platform_status` preserves source label
- `txn_id` preserved
- default balance **unchanged** by this fact

## Refund origin metadata

When refund `txn_id` matches `{base}_{suffix}`:

- `origin_order_id = base`
- full `txn_id` retained

## Pairing expectation (006)

Given closed fact `txn_id=A` and refund `origin_order_id=A`:

- 006 **may** create `refund_offset` using order identity
- MUST NOT require title match to A
- MUST NOT auto-bind refund to success fact `txn_id=B≠A` solely by title

## Net balance

Closed A (non_funding) + full refund of A:

- net default balance impact 0 before any reorder success B
- B funding success applies normal expense impact

## Import-time refund_offset

When alipay refund uniquely matches an origin by order key:

- MUST create `refund_offset` at import (`rule_id` e.g. `import.alipay.order_prefix.v1`)
- Later relation scan MUST NOT create a competing auto edge for the same refund leg

## Auth hold / unfreeze (refund_offset)

| Leg | Alipay status (examples) | Role |
|---|---|---|
| Origin | 芝麻免押下单成功 | authorization / deposit hold |
| Release | 解冻成功 | release (refund_offset counterparty) |

- Both rows MUST import (amount may be 0).
- Import MUST create `refund_offset` when uniquely paired (order key or same-day unique status pair).
- rule_id example: `import.alipay.auth_unfreeze.v1`
