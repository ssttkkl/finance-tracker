# Contract: Relation Scan Phases

1. **Phase A**: alipay/wechat hard-key `refund_offset` (+ auth-unfreeze)
2. **Phase B**: `payment_mirror`
3. **Phase C**: `transfer_pair` / credit_repayment — taxonomy gate then fine match  
   (see `attachments/transfer-source-taxonomy.md`)
4. **Phase D**: bank `refund_offset`, weak match, open-leg

- Skip if active relation business key exists
- No amount rewrite
- Phase C MUST NOT auto-pair P2P/QR/refund/consume-only legs
