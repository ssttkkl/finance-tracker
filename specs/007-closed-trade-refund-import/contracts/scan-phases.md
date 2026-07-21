# Contract: Relation Scan Phases

1. Phase A: alipay order-key refunds, auth-unfreeze, wechat dual-row (rules in domain/platform_refund)
2. Phase B: payment_mirror
3. Phase C: bank refund_offset, transfer_pair, open-leg/weak

- Skip if active relation business key exists
- No amount rewrite
