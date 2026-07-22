# Quickstart: 007

```bash
# Import bills (facts + raw only; no relations expected)
ft statement-import --type alipay --file ~/.ft/bills/支付宝….csv
ft statement-import --type wechat --file ~/.ft/bills/微信支付….xlsx
ft statement-import --type icbc --file … --password-file …

# One-shot relation scan: Phase A platform refunds → B mirror → C rest
ft relations check

ft relations pending
```

Verify: after import alone, platform refund_offset count for new batch is 0; after check, alipay/wechat pairs exist.
