# Contract: Report Projection with Relations

## Inputs

- Active formal facts (not logically deleted)
- Relations with `status=accepted` only

## Balance projection

```text
for each active cash fact:
  apply amount to (account, currency) pocket
```

Relations never drop a balance leg for mirrors/transfers/refunds.

## External income/expense projection (ordered)

1. **Mirror grouping**: build connected components of accepted `payment_mirror`.  
   - Count external spend/income once per component using deterministic canonical fact.  
   - Canonical preference: payment platform detail over bank channel summary; then longer counterparty+description.
2. **Transfer exclusion**: remove both legs of accepted `transfer_pair` (including `subtype=credit_repayment`) from external P&L.
3. **Refund offset**: apply accepted `refund_offset` to the logical expense group (not raw double-count across mirrors).  
   - net = original_expense_amount − sum(accepted refund amounts)  
   - pending refunds ignored  
   - over-refund cannot be accepted by auto rules (review path only if ever allowed later)

## Compatibility matrix (accepted)

| Combo on same fact | Allowed |
|---|---|
| payment_mirror + refund_offset | yes |
| transfer_pair + payment_mirror | no |
| transfer_pair + refund_offset | no |

## Non-authoritative sources

Must **not** use for nets/pairing authority:

- `offset_*`, `proposed_action`, `transfer_account` columns
- pending/rejected/superseded relations
- deleted facts

## Rebuildability

Given active facts + accepted relations, projections must be deterministic and re-computable without hidden state.
