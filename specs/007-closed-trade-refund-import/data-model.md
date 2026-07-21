# Data Model: 007

## Existing

- `raw_records.payload` JSON — **authority for source fields**
- `cash_transactions` formal facts
- `transaction_relations` for all relation kinds

## Changes

### Required writes (no new table required for MVP)

Import MUST store in `payload` (and may denormalize onto fact columns if already present):

**Alipay**: platform_status, txn_id, merchant_order_id, direction, payment_method, description, amount, occurred_at  
**WeChat**: status, txn_type, direction, pay_method, amount, occurred_at, txn_id, merchant_order_id, counterparty/description  
**Bank**: amount, time, counterparty/raw, description/summary/location, refund signal if any

### Explicit non-changes

- No `funding_status` column
- No import-time mandatory relation rows
- Optional: `origin_order_id` derived field for convenience (scan may compute on the fly)

## Dual backend

JSON payload supported on SQLite + PostgreSQL; equivalence on required keys and relation outcomes.
