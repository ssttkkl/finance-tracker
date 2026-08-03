# Contract: Investment Formal Row

**Feature**: 014-fact-field-unify

## Formal read shape (repository list)

Assembled from columns (+ join `account_name`), **not** by spreading full legacy payload cores:

- `id`
- `occurred_at` (ISO string)
- `account_name`
- `action`
- `currency`
- `note`
- `from_ticker`, `from_amount`, `to_ticker`, `to_amount`
- `price`, `commission`, `commission_asset`
- `revision`
- optional residual extension keys only if product chooses to surface residual payload (default: do not re-merge cores)
- `_record_type` = security|crypto

## Write path

- Resolve account by name + type → `account_id`
- Persist cores to columns
- `payload` = only non-core extension dict (default `{}`)
- Column `action` required; no `kind`

## Projection

`investment_projection` continues to consume dict rows; repository must supply the same logical keys (`action`, legs, …) from columns.
