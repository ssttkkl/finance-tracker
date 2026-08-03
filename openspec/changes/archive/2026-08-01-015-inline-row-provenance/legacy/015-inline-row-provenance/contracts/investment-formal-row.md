# Contract: Investment formal row (post-015)

## Required storage fields

- `id`, `workspace_id`, `account_id`
- `occurred_at`, `action`, `currency`, `note`
- `from_ticker`, `from_amount`, `to_ticker`, `to_amount`
- `commission`, `commission_asset`
- `payload` — residual non-core only  
- `created_at`

## Provenance (bill-derived)

- `source_type`, `record_id`, `source_payload`

## Forbidden

`raw_record_id`, `price`, `revision`, and any core key re-stored only in payload (`action`, legs, commission*, note, currency mirrors).

## Projection input

Authoritative: legs + commission*. Unit price, if needed, is derived — never a stored column.
