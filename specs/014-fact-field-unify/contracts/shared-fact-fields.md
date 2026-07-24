# Contract: Shared Fact Fields

**Feature**: 014-fact-field-unify

## Shared keys (both cash and investment formal rows)

| Key | Type (logical) | Required |
|-----|----------------|----------|
| `id` | string UUID | yes (on read) |
| `workspace_id` | string | yes (storage; may omit in public list if scoped) |
| `account_id` | string | storage; public may expose `account_name` via join |
| `raw_record_id` | string \| null | optional |
| `occurred_at` | timezone-aware datetime or ISO string | yes |
| `currency` | 3-letter code string | yes when fact has currency |
| `note` | string | yes (may be empty) |
| `revision` | int | yes |
| `created_at` | datetime | storage |

## Retired public names (MUST NOT appear after cutover)

- `description` (use `note`)
- Investment storage/public action field named `kind` (use `action`)
- Primary public time key `date` (use `occurred_at`)

## Non-shared

Cash keeps `amount`, `category`, …  
Investment keeps `action`, legs, `payload` residual, …
