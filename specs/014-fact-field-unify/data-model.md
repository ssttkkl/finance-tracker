# Data Model: Fact Field Unification

**Feature**: 014-fact-field-unify  
**Date**: 2026-07-24

## Tables (end state)

### `cash_transactions`

Unchanged structure except:

| Column | Change |
|--------|--------|
| `description` | **REMOVED** (renamed) |
| `note` | **Text NOT NULL default ''** — free-text memo (was description) |

All other cash columns unchanged (`amount`, `category`, `counterparty`, offset_*, soft-delete, …).

Shared: `id`, `workspace_id`, `account_id`, `raw_record_id`, `occurred_at`, `currency`, `note`, `revision`, `created_at`.

### `investment_events`

| Column | Type | Notes |
|--------|------|--------|
| `id` | String(36) PK | unchanged |
| `workspace_id` | String(64) FK | unchanged |
| `account_id` | String(36) | unchanged |
| `raw_record_id` | String(36) null | unchanged |
| `occurred_at` | UTCDateTime | unchanged |
| `action` | String(64) NOT NULL | **renamed from `kind`** |
| `currency` | String(3) NOT NULL default '' | unchanged role |
| `note` | Text NOT NULL default '' | **new formal** |
| `from_ticker` | String(64) NOT NULL default '' | **new** |
| `from_amount` | ExactDecimal null | **new** |
| `to_ticker` | String(64) NOT NULL default '' | **new** |
| `to_amount` | ExactDecimal null | **new** |
| `price` | ExactDecimal null | **new** |
| `commission` | ExactDecimal null | **new** |
| `commission_asset` | String(64) NOT NULL default '' | **new** |
| `payload` | JSON NOT NULL | residual non-core only; may be `{}` |
| `revision` | Integer | unchanged |
| `created_at` | UTCDateTime | unchanged |

**Removed**: `kind`.

**Constraints** (keep):  
- `uq_investment_events_workspace_id`  
- `uq_investment_events_workspace_raw_record`  
- FKs to accounts / raw_records  
- Indexes on (workspace_id, occurred_at), (workspace_id, account_id)

Optional check (if easy on both dialects): `action` in known set — may enforce in application only to avoid migration friction with unknown historical actions; application validation remains source of truth for living set.

## Migration algorithm

1. **Cash**: rename `description` → `note` (or rebuild).
2. **Investment**:
   a. Add new columns nullable/defaulted.  
   b. For each row: read `payload` + `kind`/`currency`/`occurred_at`.  
   c. If payload.action (or kind key) present and ≠ column action source (kind) after casefold → **abort**.  
   d. If payload.currency present and ≠ column currency (non-empty both) → **abort**.  
   e. Set `action` from kind (post-rename path: rename kind→action first or copy then drop).  
   f. Set note, from_*, to_*, price, commission, commission_asset from payload with projection-compatible defaults.  
   g. Build new_payload = payload without CORE_KEYS.  
   h. Write columns + new_payload.  
3. Ensure NOT NULL where required.  
4. Drop `kind` if not renamed in place.  
5. Both dialects; FK check on SQLite rebuild path.

### CORE_KEYS (strip set)

`action`, `kind`, `date`, `occurred_at`, `currency`, `note`, `from_ticker`, `from_amount`, `to_ticker`, `to_amount`, `price`, `commission`, `commission_asset`, `amount`, `ticker`, `shares`, `quantity`, `account_name`, `revision` (revision is column-owned).

## Relationships

Unchanged: workspace → accounts → cash_transactions | investment_events; raw_records 0..1 formal fact; record_revisions targets either fact id.

## Validation rules

- Investment write: `action` required non-empty; legs per 013/projection rules in domain layer.  
- Cash write: `note` replaces description in all writers.  
- No writer may put CORE_KEYS into investment.payload.
