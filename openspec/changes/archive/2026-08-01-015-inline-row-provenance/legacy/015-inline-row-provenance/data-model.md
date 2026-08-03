# Data Model: 015

## Entities retained

### cash_transactions

| Field | Notes |
|---|---|
| id, workspace_id, account_id | PK/FK |
| source_type | import channel; nullable for manual |
| record_id | row key; empty = no identity |
| source_payload | JSON snapshot; nullable |
| occurred_at, amount, currency, counterparty, note, category | formal |
| created_at | |
| deleted_at, deleted_by, delete_reason | soft delete on row only |

**Unique**: partial active `(workspace_id, source_type, record_id)` where all non-empty and deleted_at IS NULL.

**Removed**: raw_record_id, source, bill_source, transfer_account, locked, offset_*, proposed_action, revision.

### investment_events

| Field | Notes |
|---|---|
| id, workspace_id, account_id | |
| source_type, record_id, source_payload | same identity model |
| occurred_at, action, currency, note | |
| from_ticker, from_amount, to_ticker, to_amount | legs |
| commission, commission_asset | |
| payload | residual non-core only |
| created_at | |

**Unique**: `(workspace_id, source_type, record_id)` where both non-empty (no soft delete today).

**Removed**: raw_record_id, price, revision.

### ledger_snapshots

Unchanged: workspace_id PK + payload cache.

### transaction_relations

Unchanged logical model; still references fact ids as strings (until 016).

### accounts, account_aliases, account_lifecycle_events, wealth_*, valuation_observations

Unchanged except valuation drops optional raw_record_id if present.

## Entities removed

import_batches, raw_files, raw_records, fact_deletion_events, record_revisions, relation_check_runs.

## State transitions

- Import novel `(source_type, record_id)` → insert active fact  
- Import existing active identity → skip  
- Soft-delete cash → set deleted_*; frees identity for re-import  
- Re-import after delete → new fact id, same identity allowed  

## Validation

- Bill-derived: source_type, record_id, source_payload required (app layer)  
- Amounts: ExactDecimal  
- No price column validation  
