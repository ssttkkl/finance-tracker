# Contract: Cash formal row (post-015)

## Required storage fields

- `id`, `workspace_id`, `account_id`
- `occurred_at`, `amount`, `currency`
- `counterparty`, `note`, `category`
- `created_at`
- Soft-delete: `deleted_at` (null if active), `deleted_by`, `delete_reason`

## Provenance (bill-derived)

- `source_type` — import channel name  
- `record_id` — row key  
- `source_payload` — JSON object  

## Forbidden fields (must not exist)

`raw_record_id`, `source`, `bill_source`, `transfer_account`, `locked`,  
`offset_group`, `offset_role`, `offset_strength`, `offset_source`, `offset_rule_hint`,  
`offset_match_type`, `proposed_action`, `revision`

## Public list/export

Use catalog names above; no legacy headers for deleted columns.
