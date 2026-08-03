# Contract: Cash Formal Row (public list/export)

**Feature**: 014-fact-field-unify

## Public list/export fields (catalog)

Replaces prior `CASH_CSV_FIELDS` / `CASHFLOW_EXPORT_FIELDS` entry `description` with `note`, and primary time key `date` with `occurred_at` where those constants define public shape.

Minimum public shape:

- `record_id`
- `occurred_at` (ISO string)
- `amount`
- `currency`
- `counterparty`
- `note`
- `category`
- `account_name`
- `source`
- `bill_source`
- `transfer_account`
- `locked`
- `offset_group`, `offset_role`, `offset_strength`, `offset_source`, `offset_rule_hint`, `offset_match_type`
- `proposed_action`

Internal detailed row may also include `id`, `raw_record_id`, `raw_payload`, soft-delete fields, `_record_type`.

## Write path

Writers accept `note` (not `description`). Storage column is `note`.
