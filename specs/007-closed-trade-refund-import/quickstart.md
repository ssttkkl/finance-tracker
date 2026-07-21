# Quickstart: 007 Import No-Skip

## Prerequisites
- Branch `007-import-no-skip`
- Optional: `FT_TEST_POSTGRES_URL` for dual-backend

## Tests
```bash
uv run pytest tests/test_import_no_skip*.py tests/test_import_alipay_refund*.py tests/test_import_wechat_refund*.py -q
```

## Real bills (copy only)
```bash
# Alipay: source lines = published + idempotent + skips
# WeChat: skips 0; dual-row refunds linked
export FT_DATABASE_URL=sqlite:////tmp/ft-007-$$.db
# migrate + import from ~/.ft/bills copies per project CLI
```

## Expected
- Alipay unpaid-closed/failed-repay counted skips; paid closed present.
- Alipay order refunds have refund_offset at import.
- WeChat 味多美/京东拆退/对方已退还 linked; amounts not netted.
