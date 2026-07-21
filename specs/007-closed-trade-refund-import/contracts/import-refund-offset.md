# Contract: Import-time refund_offset

## Alipay
- Trigger: 退款成功 published + unique origin via FR-013.
- Auth-hold→unfreeze: FR-014a.
- Emit: TransactionRelation kind=refund_offset, usually accepted, rule_id auditable.
- Multi: pending/open-leg; never silent pick.

## WeChat
- Both dual-row legs published with original amounts.
- Match FR-029; emit refund_offset; residual multi-edge same primary expense.
- MUST NOT mutate expense amount.

## Scan
- If active refund_offset exists for pair/refund leg → skip re-propose main path.
