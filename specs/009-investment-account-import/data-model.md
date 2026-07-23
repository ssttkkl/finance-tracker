# Data Model: Investment Account Import

**Feature**: 009-investment-account-import  
**Date**: 2026-07-23

## Entity Definitions

### InvestmentEvent (Write Model)

Investment events are immutable financial facts representing a single investment operation. Each event links to its provenance (raw_record_id) or is manually created (raw_record_id=NULL).

**Attributes**:

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | UUID | PK | Unique event identifier |
| workspace_id | UUID | FK workspaces.id, NOT NULL | Workspace isolation |
| account_id | UUID | FK accounts.id, NOT NULL | Investment account (type='security' or 'crypto') |
| raw_record_id | UUID | FK raw_records.id, NULL | Source record (NULL for manual CLI entries) |
| occurred_at | TIMESTAMP | NOT NULL | Event timestamp (from statement or user input) |
| kind | TEXT | NOT NULL | 'security' or 'crypto' |
| action | TEXT | NOT NULL | Event type (see Action Enumeration) |
| ticker | TEXT | NULL | Primary asset ticker (NULL for pure cash events) |
| amount | DECIMAL(28,10) | NULL | Simplified amount (for single-asset events) |
| price | DECIMAL(28,10) | NULL | Unit price (for trades) |
| commission | DECIMAL(28,10) | NULL | Fee amount (0 if no commission) |
| currency | TEXT | NOT NULL | Base currency (e.g., 'CNY', 'USD') |
| payload | JSON/JSONB | NOT NULL | Full event details (see Payload Schema) |
| created_at | TIMESTAMP | DEFAULT NOW() | System timestamp |

**Unique Constraints**:
- `(workspace_id, raw_record_id)` - One event per raw record (idempotency)

**Indexes**:
- `(workspace_id, account_id, occurred_at)` - Query events by account and time range
- `(workspace_id, raw_record_id)` - Fast duplicate detection

### Action Enumeration

| Action | Description | Payload Keys | From/To Pattern |
|--------|-------------|--------------|-----------------|
| `deposit` | Cash inflow (bank transfer, wire) | to_ticker, to_amount | → cash |
| `withdraw` | Cash outflow (bank transfer, wire) | from_ticker, from_amount | cash → |
| `swap` | Asset exchange (buy/sell/crypto swap) | from_ticker, from_amount, to_ticker, to_amount, commission, commission_asset | asset ↔ asset |
| `dividend` | Dividend/interest income | to_ticker, to_amount, from_ticker (source asset) | → cash |
| `checkin` | Snapshot reconciliation | from_ticker/to_ticker, to_amount, price | Checkpoint |

**Legacy Compatibility**:
- `buy` (command) → `swap` (event with from=cash, to=ticker)
- `sell` (command) → `swap` (event with from=ticker, to=cash)
- Main branch's `SWAP_OUT`+`SWAP_IN` → single `swap` with from/to unified

### Payload Schema

The `payload` JSONB field preserves full event details for audit and replay. All parsers and domain commands must populate these fields.

**Common Fields** (all actions):
```json
{
  "account_name": "东方证券",
  "note": "印花税0.50 过户费0.50",
  "revision": 1
}
```

**SWAP Action**:
```json
{
  "from_ticker": "cny",
  "from_amount": "1251.00",
  "to_ticker": "600000.sh",
  "to_amount": "100",
  "price": "12.50",
  "commission": "1.00",
  "commission_asset": "cny",
  "account_name": "东方证券",
  "note": "印花税0.50 过户费0.50"
}
```

**DEPOSIT/WITHDRAW Actions**:
```json
{
  "from_ticker": "cny",  // WITHDRAW
  "to_ticker": "cny",    // DEPOSIT
  "from_amount": "10000.00",  // WITHDRAW
  "to_amount": "10000.00",    // DEPOSIT
  "account_name": "东方证券",
  "note": "银行转证券"
}
```

**DIVIDEND Action**:
```json
{
  "from_ticker": "600000.sh",  // Source asset for audit trail
  "to_ticker": "cny",
  "to_amount": "120.00",
  "account_name": "东方证券",
  "note": "现金红利"
}
```

**CHECKIN Action**:
```json
{
  "to_ticker": "cny",      // Cash check-in
  "to_amount": "10000.00",
  "price": "1.0",
  "account_name": "东方证券",
  "note": "cash checkin from statement balance"
}
// OR for position check-in:
{
  "from_ticker": "600000.sh",
  "to_amount": "1000",     // shares
  "price": "12.50",        // avg cost for snapshot
  "account_name": "东方证券",
  "note": "position checkin"
}
```

### LedgerSnapshot (Read Model)

Snapshots are projections derived from event replay. They are rebuilt on every import and can be regenerated from events at any time.

**Structure** (JSON, stored in snapshot repository):
```json
{
  "accounts": {
    "security": {
      "东方证券": {
        "currency": "CNY",
        "positions": {
          "cny": {
            "shares": "8749.00",
            "total_cost": "8749.00",
            "cost_currency": "CNY"
          },
          "600000.sh": {
            "shares": "100",
            "total_cost": "1251.00",
            "cost_currency": "CNY"
          }
        }
      }
    }
  },
  "updated_at": "2026-06-12"
}
```

**Position Fields**:
- `shares`: Asset quantity (Decimal as text, e.g., "100", "0.12345678" for crypto)
- `total_cost`: Aggregate cost basis in cost_currency (Decimal as text)
- `cost_currency`: Currency denomination for cost tracking (must match account currency or be a configured currency)

**Validation Rules** (`_validate_security_snapshot_finite`):
- All `shares` values MUST be finite Decimals (no NaN, Infinity)
- All `total_cost` values MUST be finite Decimals
- `shares` MAY be negative (short positions) but MUST be finite
- `total_cost` MAY be negative (for certain accounting scenarios) but MUST be finite
- Cost currency conflicts (same ticker, different cost_currency while position ≠ 0) MUST raise ValueError

### RawRecord (Import Provenance)

Raw records preserve the original parsed data before normalization. They enable idempotency, audit, and error recovery.

**Attributes**:

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | UUID | PK | Unique record identifier |
| workspace_id | UUID | FK workspaces.id, NOT NULL | Workspace isolation |
| batch_id | UUID | FK import_batches.id, NOT NULL | Import batch membership |
| raw_file_id | UUID | FK raw_files.id, NULL | Source file (NULL for API sync) |
| source_type | TEXT | NOT NULL | Parser identifier (see Source Types) |
| source_identity | TEXT | NOT NULL | Business key for idempotency |
| payload | JSON/JSONB | NOT NULL | Original parsed data |
| created_at | TIMESTAMP | DEFAULT NOW() | System timestamp |

**Unique Constraints**:
- `(workspace_id, source_type, source_identity)` - Prevent duplicate records

**Indexes**:
- `(workspace_id, batch_id)` - List records in batch
- `(workspace_id, source_type, source_identity)` - Fast duplicate detection

### Source Types and Identity Construction

| Source Type | source_identity Format | Example | Idempotency Basis |
|-------------|------------------------|---------|-------------------|
| `dfzq_pdf` | `dfzq:{date}:{ticker}:{action}:{amount}:{balance}` | `dfzq:20260612:600000.sh:BUY:1251.00:8749.00` | Composite business key (date+ticker+action+amounts unique within statement) |
| `ibkr_csv` | `ibkr:{date}:{type}:{code}:{qty}:{net}:{commission}` | `ibkr:20260717:buy:SNDK:4:-5479.280012:1.000012` (use `format(Decimal,"f")` for amounts; type = buy/sell/deposit/dividend/wht/interest/fx/checkin) | Composite business key within Activity CSV; CHECKIN: `ibkr:{date}:checkin:cash:{amount}:0` |
| `schwab_csv` | `schwab:{参照号码}:{类型}` | `schwab:1007269524312:TRD` | Broker 参照号码 + type |
| `ccxt_binance` | `ccxt:binance:trade:{trade_id}` | `ccxt:binance:trade:123456789` | Exchange trade ID (authoritative) |
| `ccxt_okx` | `ccxt:okx:trade:{trade_id}` | `ccxt:okx:trade:987654321` | Exchange trade ID |
| `polymarket` | `polymarket:tx:{tx_hash}` | `polymarket:tx:0xabc123...` | Blockchain transaction hash (finality) |

**Payload Examples**:

**DFZQ**:
```json
{
  "date": "2026-06-12 00:00:00",
  "action": "BUY",
  "ticker": "600000.sh",
  "name": "浦发银行",
  "shares": "100",
  "price": "12.50",
  "amount": "1251.00",
  "fee": "1.00",
  "stamp_tax": "0",
  "transfer_fee": "0",
  "balance": "8749.00",
  "note": "印花税0.50 过户费0.50"
}
```

**IBKR Activity CSV (raw payload shape after parse)**:
```json
{
  "date": "2026-07-17 00:00:00",
  "action": "BUY",
  "type_raw": "买",
  "ticker": "SNDK",
  "name": "SANDISK CORP",
  "shares": "4",
  "price": "1369.57",
  "price_currency": "USD",
  "gross": "-5478.28",
  "commission": "-1.000012",
  "net": "-5479.280012",
  "balance": "0",
  "note": ""
}
```
Fee map (equity): cash leg = abs(gross), event commission = abs(commission), commission_asset = base.  
Fee map (FX when net==gross): commission=0, fee in note. See research.md § Investment source: ibkr.

**ccxt (Binance)**:
```json
{
  "id": "123456789",
  "symbol": "BTC/USDT",
  "side": "buy",
  "price": "50000.00",
  "amount": "0.1",
  "cost": "5000.00",
  "fee": {"cost": "5.00", "currency": "USDT"},
  "timestamp": 1719936000000
}
```

**Polymarket**:
```json
{
  "type": "TRADE",
  "side": "BUY",
  "slug": "election-2024",
  "outcome": "yes",
  "size": "100",
  "price": "0.60",
  "usdcSize": "60.00",
  "timestamp": 1719936000,
  "transactionHash": "0xabc123..."
}
```

### ImportBatch

Import batches track the lifecycle of a statement import operation. They enable idempotency (via source_digest) and audit trail.

**Attributes**:

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | UUID | PK | Unique batch identifier |
| workspace_id | UUID | FK workspaces.id, NOT NULL | Workspace isolation |
| target_account_id | UUID | FK accounts.id, NULL | Target account (NULL for multi-account imports) |
| source_kind | TEXT | NOT NULL | Source system (e.g., 'dfzq', 'ibkr', 'binance', 'polymarket') |
| source_digest | TEXT | NOT NULL | SHA256 hash of source file/query (idempotency key) |
| source_ref | TEXT | NOT NULL | Human-readable reference (filename, date range) |
| status | TEXT | NOT NULL | 'pending' or 'completed' |
| created_at | TIMESTAMP | DEFAULT NOW() | Start time |
| completed_at | TIMESTAMP | NULL | Completion time |

**Unique Constraints**:
- `(workspace_id, source_kind, source_digest)` - One batch per unique source

**Status Lifecycle**:
1. `pending`: Batch created, records being processed
2. `completed`: All records processed, events written, snapshot updated

**Error Handling**:
- Transaction failure → rollback, status remains 'pending', can retry
- Parser error → raise before batch creation, no database state
- Duplicate digest → return existing batch_id, status='completed' → idempotent success

### RawFile

Raw files preserve the original binary content for PDF statements and other file-based imports.

**Attributes**:

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | UUID | PK | Unique file identifier |
| workspace_id | UUID | FK workspaces.id, NOT NULL | Workspace isolation |
| batch_id | UUID | FK import_batches.id, NOT NULL | Parent batch |
| source_path | TEXT | NOT NULL | Original file path (for reference) |
| content_digest | TEXT | NOT NULL | SHA256 hash of file content |
| size_bytes | INTEGER | NOT NULL | File size in bytes |
| media_type | TEXT | NOT NULL | MIME type (e.g., 'application/pdf') |
| created_at | TIMESTAMP | DEFAULT NOW() | System timestamp |

**Unique Constraints**:
- `(workspace_id, batch_id, content_digest)` - One file record per unique content in batch

**Storage**:
- Phase 1 (009): File content NOT stored in database (too large, privacy concern)
- Future: Object storage integration (S3, local vault) with encryption

### Account (Existing, from 005)

Investment accounts must have `type` in ('security', 'crypto') and support multi-currency via `metadata.base_currencies`.

**Relevant Fields**:
- `type`: 'security' (stocks, bonds, funds) or 'crypto' (digital assets)
- `name`: User-defined account name (e.g., "东方证券", "Binance现货")
- `metadata`: JSON containing `{"base_currencies": ["CNY", "USD"]}` for multi-currency cash tracking

**Validation**:
- Investment imports MUST target accounts with type='security' or 'crypto'
- Currency in investment events MUST be in account's base_currencies list

## Relationships

```
ImportBatch 1───N RawFile (optional, for file-based imports)
ImportBatch 1───N RawRecord
RawRecord 1───0..1 InvestmentEvent (0 if record skipped/invalid)
InvestmentEvent N───1 Account (investment account)
Account N───1 Workspace
```

**Key Insight**: RawRecord → InvestmentEvent is 1:0..1 (not 1:N) because:
- One raw record = one business event (e.g., one DFZQ trade line)
- SWAP is single-row (not SWAP_OUT + SWAP_IN)
- Commission is field (not separate FEE event)

**Exception Handling**:
- Unparseable records: Fail entire batch (no partial facts)
- Duplicate source_identity: Skip record creation, continue batch
- Invalid event (negative shares on non-short action): Fail entire batch

## Domain Rules

### Cost Basis Tracking

**SWAP (Buy)**:
- `from_ticker` (cash) position: shares -= from_amount + commission, cost -= from_amount + commission
- `to_ticker` (asset) position: shares += to_amount, cost += from_amount + commission

**SWAP (Sell)**:
- `from_ticker` (asset) position: shares -= from_amount, cost -= released_cost (proportional)
- `to_ticker` (cash) position: shares += to_amount - commission, cost += to_amount - commission
- `released_cost` = (position.total_cost * from_amount / position.shares) if shares > 0 else from_amount

**SWAP (Crypto-to-Crypto)**:
- `from_ticker` position: shares -= from_amount, cost -= released_cost
- `to_ticker` position: shares += to_amount, cost += released_cost
- Commission: deducted from from_ticker if commission_asset == from_ticker, else from to_ticker, else from third ticker

### Idempotency

**Level 1: Batch (source_digest)**:
- Same file content → same SHA256 → same batch_id
- Status='completed' → return success immediately, no writes

**Level 2: Record (source_identity)**:
- Within batch: Deduplicate by source_identity before insert
- Across batches: Unique constraint prevents duplicate records from different batches

**Outcome**:
- Importing same DFZQ PDF twice: Second import returns batch_id from first, status='completed', count=0
- Importing two PDFs with overlapping trades (same date+ticker+amount): Unique constraint violation → fail batch with specific error

## PostgreSQL vs SQLite Equivalence

### Schema Parity

| Feature | PostgreSQL | SQLite | Notes |
|---------|-----------|--------|-------|
| JSON column | JSONB | JSON (text) | Both support json_extract, SQLite lacks native indexing |
| DECIMAL | NUMERIC(28,10) | NUMERIC (text affinity) | Both preserve exact decimal representation |
| UUID | UUID | TEXT | SQLite stores as text, application generates UUID |
| Transactions | SERIALIZABLE | WAL + IMMEDIATE | Both prevent dirty reads, SERIALIZABLE stronger |
| Unique constraints | Same | Same | Both enforce (workspace_id, source_identity) uniqueness |

### Behavioral Equivalence

**MUST Match**:
- Event replay: Same input → same positions, cash, cost basis
- Idempotency: Same source_identity → duplicate rejection
- Snapshot validation: Same finite checks
- Transaction atomicity: All-or-nothing import

**MAY Differ**:
- UUID values (different generation, but uniqueness preserved)
- Timestamp precision (PostgreSQL µs, SQLite ms if stored as INTEGER)
- Concurrent write performance (PostgreSQL scales better)
- JSON query syntax (json_extract vs jsonb operators)

**Test Coverage**:
- Contract matrix: Import same DFZQ PDF to both backends
- Assert: event count, sum(amount), final snapshot positions match
- Assert: second import returns idempotent success
- Assert: injected NaN/Infinity rejected by both

## Migration Notes

**Current State** (inferred, needs verification in plan phase):
- `investment_events` table likely exists with some schema
- May need migration to add `commission_asset` column
- May need migration to ensure payload is JSONB (PostgreSQL) / JSON (SQLite)

**Alembic Migration** (pseudo-code for plan phase):
```python
# Add commission_asset if missing
op.add_column('investment_events', sa.Column('commission_asset', sa.Text(), nullable=True))

# Ensure payload is JSON/JSONB
# PostgreSQL: ALTER COLUMN payload TYPE JSONB USING payload::jsonb
# SQLite: Already text, no-op

# Add indexes for query performance
op.create_index('idx_investment_events_account_time', 'investment_events', 
                ['workspace_id', 'account_id', 'occurred_at'])
op.create_index('idx_investment_events_raw_record', 'investment_events', 
                ['workspace_id', 'raw_record_id'])
```

**Data Migration**: N/A (current stage data is disposable per constitution)

## Open Issues

1. **InvestmentEventModel Schema**: Need to verify actual columns in `src/ft/adapters/relational/models.py` match this design
2. **Snapshot Lock**: Confirm `snapshot.load(lock=True)` exists for optimistic locking during import
3. **Commission Asset Default**: When commission_asset is NULL, does apply_investment_event() default to from_ticker or to_ticker?
4. **CHECKIN Semantics**: Should CHECKIN replace entire position or delta-adjust? (Current: replace based on investment_projection.py line 241-244)
5. **Negative Shares**: Are short positions supported? (Research shows validation checks shares >= 0 for most actions, but snapshot allows negatives)
