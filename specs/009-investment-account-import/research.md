# Research: Investment Account Import

**Feature**: 009-investment-account-import  
**Date**: 2026-07-23

## Current State (Current Branch)

### Investment Infrastructure Present

1. **Domain Layer** (`src/ft/domain/investment_projection.py`):
   - `apply_investment_command()`: Converts CLI commands to immutable event rows
   - `apply_investment_event()`: Replay unified statement events into exact-Decimal projection
   - Event schema: unified from/to structure (from_ticker, to_ticker, from_amount, to_amount)
   - Actions supported: BUY, SELL, SWAP, DEPOSIT, WITHDRAW, DIVIDEND, CHECKIN
   - Commission handling: `commission` + `commission_asset` fields
   - High-precision Decimal context (prec=80) for cost basis tracking
   - Position tracking: shares + total_cost per ticker per account
   - Validation: cost currency conflict detection

2. **Application Layer** (`src/ft/application/investment.py`):
   - `InvestmentService`: buy/sell/swap/deposit/withdraw/dividend/checkin_ticker/checkin_cash methods
   - `PortfolioQueryService`: get_portfolio with market data integration
   - All amounts validated as finite Decimals

3. **Adapter Layer**:
   - `RelationalInvestmentCommandRepository` (`src/ft/adapters/relational/investments.py`): 
     - Executes commands via UoW (account validation, snapshot lock, event write, commit)
   - Import infrastructure (`src/ft/adapters/relational/imports.py`):
     - `RelationalImportRepository.start_batch()`: Idempotent batch creation by source_digest
     - `add_raw_records()`: Deduplication by source_identity
     - Batch status tracking: pending → completed
   - Statement import orchestration (`src/ft/application/statement_import.py`):
     - `StatementImportService.import_statement()`: SHA256 digest, parser delegation, UoW transaction

4. **Parsers** (`src/ft/importers/dfzq.py`):
   - DFZQ PDF text parser (qpdf decrypt + mutool text extraction)
   - Action mapping: 证券买入→BUY, 证券卖出→SELL, 银行转证券→DEPOSIT, 红利入账→DIVIDEND, etc.
   - Ticker suffix: .sh/.sz/.otc based on code patterns
   - Output: list[dict] with date/action/ticker/shares/price/amount/fee/balance/note
   - CHECKIN event appended from last balance

5. **Database Schema** (inferred from `imports.py` and `uow.py`):
   - `import_batches`: workspace_id, source_kind, source_digest (unique), source_ref, status, target_account_id
   - `raw_files`: batch_id, source_path, content_digest, size_bytes, media_type
   - `raw_records`: source_identity (unique per source_type), source_type, payload, batch_id
   - `investment_events`: (structure not yet inspected but referenced)

### Missing Pieces

1. **Direct Import Flow**: Current branch can parse DFZQ but lacks CLI `ft import <file> --source dfzq` wiring
2. **Multi-broker Parsers**: Exchange (ccxt) and Polymarket parsers deleted from current branch
3. **Snapshot Validation**: `_validate_security_snapshot_finite` removed (main has this)
4. **Investment Event Schema**: Need to verify table structure supports all required fields
5. **Dual-backend Tests**: No investment-specific PostgreSQL/SQLite equivalence tests yet

## Main Branch Implementation

### Key Files Analyzed

1. **`main:src/ft/stock.py`** (300 lines):
   - CSV-based recording to `records/security/{date}.csv`
   - `_validate_security_snapshot_finite()`: Checks cash, shares, avg_cost are finite
   - `do_convert()`: DFZQ PDF → 10-column CSV (current preview-only flow)
   - `do_append()`: Batch CSV import with validation, day-split, snapshot rebuild
   - Snapshot backup/restore on error (atomic CSV + snapshot write)

2. **`main:src/ft/exchange_sync.py`** (200 lines):
   - ccxt integration: `fetch_trades()` with pagination, deduplication by trade.id
   - `trade_to_rows()`: Maps ccxt trade to 1-3 CSV rows
     - USDT/USD quote → BUY/SELL + commission
     - Other pairs → SWAP_OUT + SWAP_IN (two rows)
     - Third-party fee asset → separate FEE row
   - `validate_crypto_account()`: Must be type='crypto'
   - Credentials: `load_credentials(provider)` from `~/.ft/credentials.json`
   - Sync: `filter_new_rows()` via `sync_common.filter_new_rows()` (tid: prefix)

3. **`main:src/ft/polymarket_sync.py`** (200 lines):
   - Public Activity API: `fetch_activity(proxy_wallet)` with pagination
   - `activity_to_stock_row()`: TRADE → BUY/SELL (slug:outcome ticker)
   - `validate_security_account()`: Must be type='security'
   - Deduplication: tx_hash in note field (`tx:0x...`)
   - Credentials: `load_polymarket_credentials()` for proxy wallet resolution

### Event Schema Decisions

**Main Branch Approach** (from `exchange_sync.py` and `polymarket_sync.py`):
- **SWAP representation**: Two-row model (SWAP_OUT + SWAP_IN with shared note `swap:<id>`)
- **FEE handling**: Independent FEE action row (third-party fee assets)
- **CSV Fields**: 10 columns (date, action, ticker, shares, price, amount, commission, currency, account_name, note)

**Current Branch Approach** (from `investment_projection.py`):
- **SWAP representation**: Single-row unified schema (from_ticker/to_ticker, from_amount/to_amount)
- **FEE handling**: commission + commission_asset fields (no independent FEE action)
- **Event Payload**: JSON with full details (supports both legacy CSV and new unified model)

**Decision for 009** (from spec.md FR-006, FR-007):
- ✅ **Keep current branch single-row SWAP**: Simpler atomicity, already implemented in apply_investment_event()
- ✅ **Keep commission field approach**: Covers 99% of broker/exchange fees (commission deducted from trade)
- ⚠️ **Third-party fee assets**: Can be represented via commission_asset ≠ from_ticker/to_ticker
- 📝 **Independent FEE action**: Not required for 009 MVP; can add later if needed (e.g., withdrawal fees, account management fees)

### Import Flow Architecture

**Main Branch Pattern**:
```
PDF → parse → CSV preview → manual review → batch append → snapshot rebuild → git commit
```

**Target Pattern (007 + 009)**:
```
PDF/API → parse → raw_records → investment_events → snapshot update (in single transaction)
         ↓
    ImportBatch (source_digest idempotency)
```

**Key Differences**:
1. **No CSV intermediate**: Direct database write
2. **Atomic transaction**: All-or-nothing (no partial facts)
3. **Idempotency**: source_identity prevents duplicates (not file-path based)
4. **Provenance**: raw_records preserves original data, investment_events links via raw_record_id

## Technical Decisions

### 1. Event Schema: Unified Single-Row SWAP

**Rationale**:
- Current branch already implements from/to unified model
- Simpler transaction boundary (one row = one logical operation)
- Cost basis tracking: released cost calculated from source position in apply_investment_event()
- Spec explicitly chooses single-row (FR-006)

**Trade-offs**:
- Lose explicit SWAP_OUT/SWAP_IN audit trail (but JSON payload preserves full details)
- Must ensure from_ticker/to_ticker always set for SWAP (validation in domain layer)

### 2. Commission Field vs Independent FEE Action

**Rationale**:
- 99% of broker/exchange fees are trading commissions (deducted from proceeds or added to cost)
- commission_asset handles third-party fee currencies (e.g., BNB for Binance trades)
- Simpler event model (fewer action types)
- Spec explicitly chooses commission approach (FR-007)

**Out of Scope for 009**:
- Withdrawal fees (e.g., crypto transfer fees)
- Account management fees
- Margin interest
- These can be added as independent FEE/EXPENSE events in future features

### 3. Snapshot Validation

**Decision**: Restore `_validate_security_snapshot_finite` from main branch

**Checks Required**:
- All position shares ≥ 0 and finite
- All position costs finite (can be negative for short positions, but must be finite)
- All cash balances finite (can be negative for margin accounts)
- No NaN, Infinity, or -Infinity values

**Integration Point**: After every apply_investment_event() in import flow and CLI commands

### 4. Parser Output Schema

**Current DFZQ Output**:
```python
{
    "date": "2026-06-12 00:00:00",
    "action": "BUY",  # or SELL, DEPOSIT, WITHDRAW, DIVIDEND, CHECKIN
    "ticker": "600000.sh",
    "name": "浦发银行",
    "shares": Decimal("100"),
    "price": Decimal("12.50"),
    "amount": Decimal("1251.00"),  # principal + commission
    "fee": Decimal("1.00"),  # commission
    "stamp_tax": Decimal("0"),
    "transfer_fee": Decimal("0"),
    "balance": Decimal("10000.00"),
    "note": "印花税0.50 过户费0.50"
}
```

**Mapping to Investment Event**:
- BUY → SWAP (from_ticker=currency.lower(), to_ticker=ticker, from_amount=amount, to_amount=shares, commission=fee)
- SELL → SWAP (from_ticker=ticker, to_ticker=currency.lower(), from_amount=shares, to_amount=amount-fee, commission=fee)
- DEPOSIT → DEPOSIT (to_ticker=currency.lower(), to_amount=amount)
- WITHDRAW → WITHDRAW (from_ticker=currency.lower(), from_amount=amount)
- DIVIDEND → DIVIDEND (to_ticker=currency.lower(), to_amount=amount, from_ticker=ticker for audit)
- CHECKIN → CHECKIN (to_ticker=currency.lower(), to_amount=balance for cash check-in)

### 5. Credentials Management

**Temporary Solution (009)**:
- Environment variables: `FT_EXCHANGE_<PROVIDER>_API_KEY`, `FT_EXCHANGE_<PROVIDER>_API_SECRET`
- Config file: `~/.ft/credentials.json` (JSON with provider → {api_key, api_secret, password?})
- Test fixtures: Hardcoded mock credentials for integration tests

**Long-term Solution (011)**:
- Encrypted vault (e.g., keyring integration)
- Per-workspace credential isolation
- Credential rotation support
- Audit log for credential access

**Security Requirements**:
- credentials.json must be in .gitignore
- CLI must check file permissions (warn if world-readable)
- Test credentials must be clearly marked (prefix with TEST_)

### 6. Source Identity Construction

**DFZQ**:
```python
source_identity = f"dfzq:{date}:{ticker}:{action}:{amount}:{balance}"
# Example: "dfzq:20260612:600000.sh:BUY:1251.00:10000.00"
```

**Exchange (ccxt)**:
```python
source_identity = f"ccxt:{provider}:trade:{trade_id}"
# Example: "ccxt:binance:trade:123456789"
```

**Polymarket**:
```python
source_identity = f"polymarket:activity:{activity_id}"
# Or: "polymarket:tx:{tx_hash}" (transaction hash more stable)
```

**Rationale**:
- DFZQ: No external ID, use composite business key (date+ticker+action+amounts unique within statement)
- Exchange: trade.id is authoritative, provider-qualified for multi-exchange support
- Polymarket: activity.id or tx_hash (tx_hash preferred for chain finality)

## PostgreSQL vs SQLite Equivalence

### Schema Equivalence

**Common Schema** (Alembic migrations):
```sql
-- Investment events table
CREATE TABLE investment_events (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    account_id UUID NOT NULL REFERENCES accounts(id),
    raw_record_id UUID REFERENCES raw_records(id),  -- NULL for manual CLI entries
    occurred_at TIMESTAMP NOT NULL,
    kind TEXT NOT NULL,  -- 'security' | 'crypto'
    action TEXT NOT NULL,  -- 'buy' | 'sell' | 'swap' | 'deposit' | 'withdraw' | 'dividend' | 'checkin'
    ticker TEXT,
    amount DECIMAL(28,10),  -- Unified amount (for simple actions)
    price DECIMAL(28,10),
    commission DECIMAL(28,10),
    currency TEXT,
    payload JSONB,  -- Full event details (from/to, note, etc.)
    created_at TIMESTAMP DEFAULT NOW(),
    UNIQUE (workspace_id, raw_record_id)  -- One event per raw record
);

-- SQLite: JSONB → JSON (no native JSONB, but json1 extension for queries)
```

**Dialect Differences**:
- PostgreSQL: JSONB with GIN indexes, SERIALIZABLE isolation
- SQLite: JSON (text), WAL + IMMEDIATE transaction for write lock

### Behavioral Equivalence Requirements

**Must Be Identical**:
- Event replay results (same positions, same cash, same cost basis)
- Idempotency (same source_identity → reject duplicate)
- Snapshot validation (same finite checks)
- Transaction atomicity (all-or-nothing import)
- Decimal precision (28,10 sufficient for shares, price, amount)

**Allowed Differences**:
- UUID generation (different values, but uniqueness guaranteed)
- Transaction isolation semantics (both prevent dirty reads, but SERIALIZABLE vs SNAPSHOT differs)
- JSONB indexing (PostgreSQL can index JSON fields, SQLite requires manual extraction)
- Concurrent import performance (PostgreSQL better, but both must serialize correctly)

**Test Strategy**:
- Contract matrix: Import same DFZQ PDF to both backends, assert event count + amount totals + final snapshot match
- Idempotency: Repeat import, verify rejection
- Snapshot validation: Inject NaN/Infinity, verify both backends reject
- Transaction rollback: Inject mid-import error, verify no partial facts

## Implementation Phasing

### Phase 1: DFZQ Direct Import (P1)

**Scope**:
- CLI: `ft import <file> --source dfzq --account <name>`
- Parser: Reuse existing `dfzq.py`, map to investment events
- Flow: ImportBatch → RawFile → RawRecords → InvestmentEvents → Snapshot update
- Validation: Restore `_validate_security_snapshot_finite`
- Tests: Unit (parser), integration (full import), dual-backend contract

**Deliverables**:
- `src/ft/application/investment_import.py`: InvestmentImportService
- `src/ft/cli/investment.py`: `ft import` command
- `tests/integration/test_dfzq_import_dual_backend.py`

### Phase 2: Exchange Sync (P2)

**Scope**:
- Restore `exchange_sync.py` logic, adapt to current architecture
- CLI: `ft import --source binance --account <name> --since 2026-01-01`
- Credentials: Environment variables or ~/.ft/credentials.json
- Mapping: ccxt trade → SWAP (cash quote) or SWAP_OUT+IN (crypto pairs)
- Tests: Mock ccxt client, dual-backend contract

**Deliverables**:
- `src/ft/importers/exchange.py`: ExchangeStatementParser (ccxt wrapper)
- `src/ft/cli/investment.py`: Add --source binance/okx support
- `tests/integration/test_exchange_import.py`

### Phase 3: Polymarket Sync (P3)

**Scope**:
- Restore `polymarket_sync.py` logic, adapt to current architecture
- CLI: `ft import --source polymarket --account <name>`
- Credentials: Proxy wallet address (env var or config)
- Mapping: Activity → BUY/SELL (pm:slug:outcome ticker)
- Tests: Mock Activity API, dual-backend contract

**Deliverables**:
- `src/ft/importers/polymarket.py`: PolymarketStatementParser
- `src/ft/cli/investment.py`: Add --source polymarket support
- `tests/integration/test_polymarket_import.py`

## Risk Assessment

### High Risk

1. **DFZQ Format Changes**: Broker may update PDF layout
   - Mitigation: Parser returns specific error (page/line), user reports to maintainer
   - Fallback: Manual CSV fallback (export from `ft stock convert` preview)

2. **Snapshot Corruption**: Import writes invalid snapshot
   - Mitigation: `_validate_security_snapshot_finite` before commit
   - Recovery: Transaction rollback preserves pre-import state

3. **Dual-Backend Divergence**: Subtle logic differences cause different results
   - Mitigation: Contract test matrix on every import feature
   - Detection: CI runs both backends, asserts output equality

### Medium Risk

1. **Third-Party API Changes**: Exchange/Polymarket API schema evolution
   - Mitigation: ccxt library abstracts exchange differences (for Phase 2)
   - Monitoring: Version lock ccxt, test against real API in pre-prod

2. **Credential Security**: Plaintext credentials.json
   - Mitigation: File permission check (warn if 0644), .gitignore enforcement
   - Long-term: Move to 011 encrypted vault

3. **Idempotency Edge Cases**: Hash collision, file modification detection
   - Mitigation: SHA256 digest + business key (date+ticker+amount) dual check
   - Documentation: Explain that file path changes don't bypass idempotency

### Low Risk

1. **Performance**: Large PDF parsing (10k+ transactions)
   - Mitigation: 100 MiB file size limit, batch insert in chunks
   - Monitoring: Log import duration, investigate if >30s

2. **Currency Confusion**: User imports USD statement to CNY account
   - Mitigation: Parser enforces currency match (or explicit --currency override)
   - Error message: "Statement currency USD does not match account currency CNY"

## Open Questions for Plan Phase

1. **Investment Event Table Schema**: Need to inspect `src/ft/adapters/relational/models.py` for InvestmentEventModel columns
2. **CLI Integration**: How does `ft import` dispatch to investment vs cash statement parsers? (Check existing CLI structure)
3. **Snapshot Repository**: Does current branch have `load(lock=True)` for snapshot optimistic locking?
4. **Test Data**: Where should test PDFs/fixtures live? (Likely `tests/fixtures/dfzq/`)
5. **Commission Asset Null Handling**: When commission_asset is NULL vs empty string, does projection default to from_ticker?

## References

- Constitution Principle IV: Dual-backend equivalence, explicit DB selection
- Feature 007: Import contract (batch → raw_records → formal facts)
- Feature 005: Multi-currency accounts (base_currencies in metadata)
- Feature 002: Dual-database runtime (explicit FT_DATABASE_URL)
