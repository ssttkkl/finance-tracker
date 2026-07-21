# Quickstart Validation: 007 Import No-Skip

## Prerequisites

- Worktree branch `007-import-no-skip`
- `uv sync`
- Optional: `FT_TEST_POSTGRES_URL` for dual-backend
- Mapping configured for test accounts (fail-closed if missing)

## Automated

```bash
uv run pytest tests/test_import_no_skip_*.py tests/test_closed_trade_import_*.py -q
# with PG:
export FT_TEST_POSTGRES_URL='postgresql+psycopg://finance_tracker:finance_tracker_test@127.0.0.1:55432/finance_tracker_test'
export FT_REQUIRE_TEST_POSTGRES=1
uv run pytest tests/test_import_no_skip_*.py tests/test_closed_trade_import_*.py -q
```

## Manual / real bills (copy, never mutate ~/.ft live DB unless intended)

1. Copy a known Alipay CSV slice containing 交易关闭 + 退款成功 + later 交易成功 (e.g. 小桌子 pattern).
2. Import into empty test DB with mapping.
3. Expect:
   - 3 source lines → 3 formal facts (closed non_funding, refund, success funding)
   - closed does not move balance; success does
   - refund `origin_order_id` equals closed `txn_id`
4. Re-import same file → idempotent_hits, no duplicate active facts.
5. Temporarily break mapping → import fails, no partial silent success.

## Regression

- Ordinary success+refund still imports both funding facts.
- WeChat closed/failed expense lines appear as non_funding facts.
