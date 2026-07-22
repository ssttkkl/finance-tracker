# Contract: Relation Scan Phases

1. **Phase A**: alipay/wechat hard-key `refund_offset` (+ auth-unfreeze)
2. **Phase B**: `payment_mirror`
3. **Phase C**: `transfer_pair` / credit_repayment — taxonomy gate then fine match  
   (see `attachments/transfer-source-taxonomy.md`)
4. **Phase D**: bank `refund_offset`, weak match, open-leg

- Skip if active relation business key exists
- No amount rewrite
- Phase C MUST NOT auto-pair P2P/QR/refund/consume-only legs

## Phase B additions (007)
- `payment_mirror.bank_date_only.v1` — raw business day (Asia/Shanghai), bank date-only
- `payment_mirror.refund_dual_source.v1` — platform refund credit × bank 消费退货/退款

## Phase D additions
- `refund_offset.diamond_via_platform.v1` — disambiguate bank refund via platform chain
