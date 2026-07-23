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

---

## Investment source: ibkr (Interactive Brokers / 盈透证券)

**Status**: Living-spec extension of 009 (2026-07-23). Calibrated on real Activity Statement
CSV (Transaction History, Chinese column labels), sample path local-only:
`exports/ibkr/U19367228.TRANSACTIONS.1Y.csv` (gitignored); redacted fixture:
`tests/fixtures/ibkr/transactions_1y_sample.csv`.

### Export format

- **Format**: IBKR Activity Statement style multi-section CSV (`Section,Header|Data,...`)
- **Sections used**:
  - `Statement` — Title / Period / WhenGenerated
  - `总结` — 基础货币, 期初现金, 变更, 期末现金
  - `Transaction History` — row-level cash/trades
- **Decrypt/extract tools**: none (plain UTF-8 CSV)
- **Account type**: `security`
- **Default currency**: base currency from 总结 (`USD` in sample); CLI `--currency` override allowed
- **source CLI**: `ft import <file.csv> --source ibkr --account <name>`

### Native columns (Transaction History)

| # | Header (sample) | Meaning | Sign / unit |
|---|---|---|---|
| 0–1 | section + `Data` | row kind | — |
| 2 | 日期 | trade/settle calendar date | `YYYY-MM-DD` (no time) |
| 3 | 账户 | account id | redacted `U***…` in fixtures |
| 4 | 说明 | free-text description | — |
| 5 | 交易类型 | action label | see map below |
| 6 | 代码 | symbol or pair (`GOOG`, `USD.HKD`) | `-` when N/A |
| 7 | 数量 | shares or FX base qty | buy `+`, sell `-`; `-` when N/A |
| 8 | 价格 | trade / FX rate | `-` when N/A |
| 9 | Price Currency | quote ccy of price | e.g. `USD`, `HKD` |
| 10 | 总额 | **gross** cash of equity trade | buy `-`, sell `+` (equity) |
| 11 | 佣金 | commission | **always ≤ 0** when present; `-` empty |
| 12 | 净额 | **net cash impact in base** | signed; cash recon key |

### Transaction type census (sample, 38 flow rows)

| 交易类型 | Count | Cash direction | Notes |
|---|---:|---|---|
| 买 | 17 | net ≤ 0 | equity buy |
| 卖 | 10 | net ≥ 0 | equity sell |
| 存款 | 6 | net > 0 | 电子资金转账 |
| 股息 | 1 | net > 0 | cash dividend |
| 外国预扣税 | 1 | net < 0 | tax on dividend |
| 借方利息 | 1 | net < 0 | debit interest |
| 外汇交易组成部分 | 2 | residual base cash | pair in 代码 `USD.HKD` |

### Fee / cash-leg contract (equity) — **chosen model: gross + commission**

Empirically on **all 27** sample equity trades:

```text
净额 = 总额 + 佣金     (佣金 ≤ 0)
|总额| ≈ |数量| × 价格   (sign: buy 总额 negative, sell positive)
```

| Field | Example buy SNDK | In cash leg? | In commission field? | Note only? |
|---|---|---|---|---|
| 总额 (gross) | `-5478.28` | **Yes** — SWAP cash amount = `abs(总额)` | No | — |
| 佣金 | `-1.000012` | No (not embedded in 总额) | **Yes** — `commission = abs(佣金)`, `commission_asset = base` (usd) | Yes (audit) |
| 净额 | `-5479.280012` | Derived: gross − commission after projection | — | Sanity: must match 总额+佣金 |
| 数量 × 价格 | notional | Not the cash leg | — | Sanity vs 总额 |

**Projection effect (009 single-row SWAP + commission):**

- **BUY** → `swap` cash→ticker: `from_amount = abs(总额)`, `to_amount = abs(数量)`,
  `commission = abs(佣金)`, `commission_asset = usd` → cash decreases by gross+commission = |净额|
- **SELL** → `swap` ticker→cash: `from_amount = abs(数量)`, `to_amount = abs(总额)`,
  `commission = abs(佣金)`, `commission_asset = usd` → cash increases by gross−commission = |净额|
  (projection deducts commission from cash target when `commission_asset == to_ticker`)

**Contrast with DFZQ (peel model, not “always commission=0”)**:

DFZQ `总发生金额` is **net** cash (after 手续费/印花税/过户费). Code peels **手续费** when separable:

| Side | Cash leg | commission | Projection cash |
|---|---|---|---|
| BUY | `\|net\| - 手续费` | `手续费` | from + commission = \|net\| out |
| SELL | `\|net\| + 手续费` | `手续费` | to − commission = \|net\| in |
| Peel fail / fee=0 | `\|net\|` | `0` | cash = \|net\| |

印花税/过户费 stay inside the cash leg (not always cleanly invertible).  
**Forbidden (both brokers)**: same fee fen in cash leg **and** commission.

IBKR equities already expose **gross** 总额 + 佣金 → cash leg = \|gross\|, commission = \|佣金\| (no peel needed).  
Do **not** apply DFZQ peel formulas to IBKR net rows or set IBKR cash_leg=\|净额\| with non-zero commission.

### Cash reconciliation (sample)

- 总结: 期初现金 `0`, 变更 `5044.938780328453`, 期末现金 `5044.938780328453`, 基础货币 `USD`
- Σ(全部行 净额) ≈ `5044.938781…` (sub-µUSD float noise from scientific notation in CSV)
- Pass rule: after import + cash CHECKIN, snapshot USD cash = 总结.期末现金 within ≤ `0.01` (or exact Decimal when rows re-parsed without binary float)

### Non-equity action map (Phase-1 decisions)

| 交易类型 | Event action | Mapping rules |
|---|---|---|
| 买 | `swap` | cash→symbol; gross + commission (above) |
| 卖 | `swap` | symbol→cash; gross + commission |
| 存款 | `deposit` | `to_amount = abs(净额)`, cash ticker = base |
| 股息 | `dividend` | cash dividend: `to_ticker=base`, `to_amount=abs(净额)`; `from_ticker=代码` for audit |
| 外国预扣税 | `withdraw` | `from_amount = abs(净额)` cash (tax outflow) |
| 借方利息 | `withdraw` | `from_amount = abs(净额)` cash |
| 外汇交易组成部分 | `swap` | multi-currency swap (below) |

**Clarification on freeform wording**: user message grouped “存款股息…withdraw”; **cash direction
overrides wording** — 存款 is **deposit**, 股息 is **dividend**, only 预扣税/借方利息 are **withdraw**.

### FX (`外汇交易组成部分`) contract

Sample rows:

1. Tiny: `USD.HKD` qty=`0.0095` px=`7.84045` 总额≈净额≈`2.75e-7`, 佣金 empty  
2. Large: `USD.HKD` qty=`1275.46` px=`7.84025` 总额=`-2.030…` 佣金=`-2.0` **净额=`-2.030…`**  
   Note: for this row **净额 = 总额 ≠ 总额+佣金**. Equity identity does **not** hold.

Interpretation for Phase-1 (fail-closed if pair unparseable) — **locked for implementer**:

1. Pair `BASE.QUOTE` in 代码 (e.g. `USD.HKD`); tickers = lower-case `usd`, `hkd`.
2. Left amount = `abs(数量)`; right amount = `abs(数量) × abs(价格)` (Price Currency column).
3. Direction (which side is `from` vs `to`):
   - Prefer sign of 数量: positive qty → buy left / sell right (`from`=quote, `to`=base with
     amounts right→left); negative qty → sell left / buy right. Lock with unit tests on both
     sample FX rows.
   - Cross-check description `外汇交易基础货币净额: <n> USD.HKD` when present (n may be base notional).
4. Commission: if 佣金 present and **净额 == 总额** (large sample FX row), commission is
   **embedded** → `commission=0`, note `佣金{abs}`; **never** equity-style double apply on FX
   when net==gross. If a future sample shows 净额=总额+佣金 for FX, document switch to
   equity-style for that variant only.
5. Calibration gate: after flows + cash CHECKIN, **base** cash == 总结.期末现金. Non-base
   positions (e.g. hkd) MAY remain non-zero; do not invent CHECKIN or skip FX rows to force zero.
6. Unparseable pair / missing qty or price → abort entire import (fail-closed).

### CHECKIN policy (this export)

- Emit **one cash CHECKIN** after flows: `to_ticker=base`, `to_amount=总结.期末现金`,
  `date = max(flow date) or WhenGenerated date`
- **No per-ticker holdings CHECKIN** — sample has no 成本价/持仓表; do not invent cost
- Flow-replay shares for open names (sample end): AVGO 5, KO 30, NVDA 25, SNDK 4, TSM 20;
  closed: MU/GOOG/QCOM/MRVL → 0. Avg cost is flow-only, not broker-official

### source_identity recipe

```text
ibkr:{date}:{type}:{code}:{qty}:{net}:{commission}
```

- `date` = `YYYYMMDD`
- `type` = raw 交易类型 (or stable English token: buy/sell/deposit/dividend/wht/interest/fx)
- `code` = 代码 or `cash`
- `qty` / `net` / `commission` = full Decimal string via `format(x, "f")` (no sci notation)
- CHECKIN: `ibkr:{date}:checkin:cash:{amount}:0`

### Parser checklist (ibkr)

- [ ] UTF-8 CSV via `csv` module (quoted fields, scientific notation → Decimal)
- [ ] Require sections Statement + Transaction History; fail if 交易类型 unknown
- [ ] No silent `continue` without incrementing skip counter; unknown type → abort batch
- [ ] Map + construct_source_identity + optional summary cash CHECKIN
- [ ] Unit tests on redacted fixture; offline replay cash ≈ 期末现金
- [ ] Wire `InvestmentImportService._parse_statement` + CLI `--source ibkr`
- [ ] `source_type` for raw_records: `ibkr_csv`

### Calibration targets (sample)

| Metric | Expected |
|---|---|
| Flow rows | 38 |
| + cash CHECKIN | 39 events (if one checkin) |
| End USD cash | 5044.938780328453 (after checkin) |
| Open share counts | AVGO5 KO30 NVDA25 SNDK4 TSM20 |
| Equity fee double-count | 0 |
| Re-import | duplicate batch, count 0 |

### Non-goals (ibkr Phase-1)

- Flex Query / API / Client Portal auto-sync (→ 011)
- Other IBKR sections (Trades, Open Positions, MTM, Corporate Actions beyond types above)
- English-only statement variants (may share structure; not certified until sample)
- Official cost-basis alignment without Positions export
- Valuation / FX rates beyond statement price field
- Lot accounting / realized P&L relations

### Decision log

| # | Decision | Rationale |
|---|---|---|
| 1 | Living extend 009 (not new feature dir) | Same investment import service + event model; user chose Living |
| 2 | Equity fee = gross + commission field (IBKR) | 净额=总额+佣金; preserves commission audit; projection cash = net |
| 2b | DFZQ fee = peel 手续费 from net into commission | Product preference: commission field when separable; cash impact still \|净额\|; fallback commission=0 if cannot peel |
| 3 | FX = swap multi-ccy; FX commission embed if 净额==总额 | Sample identity differs from equity |
| 4 | 存款→deposit, 股息→dividend, tax/interest→withdraw | Cash sign / domain; freeform “withdraw” for 存款/股息 rejected |
| 5 | Cash CHECKIN only | No holdings cost table in this CSV |
| 6 | Remove “IBKR out of scope” from 009 spec | Superseded by this extension |
| 7 | One-place fee rule (all sources) | Same fen never in cash leg and commission |
| 8 | US3 exchange + US4 Polymarket **out of 009** | Align `docs/productization-refactor-plan.md`: file import = 009; valuation = 010; connector API sync = 011 |

## References

- Constitution Principle IV: Dual-backend equivalence, explicit DB selection
- Feature 007: Import contract (batch → raw_records → formal facts)
- Feature 005: Multi-currency accounts (base_currencies in metadata)
- Feature 002: Dual-database runtime (explicit FT_DATABASE_URL)
- Skill: `.claude/skills/investment-statement-importer-onboarding`

---

## Investment source: schwab (Charles Schwab / 嘉信理财)

**Status**: Living-spec extension of 009 (2026-07-23). Calibrated on real
Transaction History CSV (Chinese headers), sample:
`exports/schwab/TransactionHistory.csv` (gitignored);
fixture: `tests/fixtures/schwab/transaction_history_sample.csv`.

### Export format

- Single-table CSV (UTF-8, possible BOM), header:
  `日期, 类型, 说明, 参照号码, 杂费, 佣金, 金额, 余额`
- Header cells may have leading spaces (` 类型`); strip on parse.
- Row order: **newest first**; import/replay MUST sort ascending by 日期 then 参照号码.
- No PDF tools. Account type: `security`. Currency: USD (statement amounts are US$).
- CLI: `ft import <file.csv> --source schwab --account <name>`

### Type census (sample, 36 rows)

| 类型 | Count | Meaning (sample) | Event |
|---|---:|---|---|
| TRD | 27 | BOT/SOLD equity | swap |
| DOI | 4 | Qualified Dividend / INTEREST | dividend if amount>0; withdraw if amount<0 (interest charge) |
| JRN | 4 | tax withhold / REFUND | withdraw if amount<0; deposit if REFUND / amount>0 |
| WIN | 1 | WIRED FUNDS RECEIVED | deposit |

### Native columns

| Header | Meaning |
|---|---|
| 日期 | local datetime `YYYY/M/D HH:MM` |
| 类型 | TRD / DOI / JRN / WIN / … |
| 说明 | free text; TRD: `BOT +N SYM @price` or `SOLD -N SYM @price` |
| 参照号码 | broker ref (unique in sample) — primary idempotency atom |
| 杂费 | fee (often empty/`-` on buys; **negative** on sells, e.g. `-0.03`) |
| 佣金 | commission (always `0.00` in sample) |
| 金额 | signed cash of the line (`$1,550.00` / `($5,992.00)`) |
| 余额 | post-transaction cash balance |

### Fee / cash-leg contract (chosen)

Empirically:

1. Balance walk: `余额_new = 余额_old + 金额 + 杂费` (杂费 empty → 0). **金额 alone fails** on sells with 杂费.
2. 佣金 is 0 in sample; treat as additional cash fee if non-zero later:  
   `fee_total = abs(杂费) + abs(佣金)` when both are cash fees in base currency.
3. 金额 is **principal / quoted cash** (not net of 杂费).

| Side | Cash leg (SWAP) | commission | Projection cash |
|---|---|---|---|
| BOT (buy) | `abs(金额)` | `fee_total` | from + commission (if fee on buy ever appears) |
| SOLD (sell) | `abs(金额)` | `fee_total` (=abs(杂费) typically) | to − commission = 金额+杂费 net |

**One-place rule**: do not set cash leg = `abs(金额+杂费)` **and** non-zero commission.

**Contrast**:
- IBKR equity: gross 总额 + 佣金 (佣金 signed ≤0).
- DFZQ: net 总发生金额 + peel 手续费.
- Schwab: 金额 + 杂费 (杂费 separate; 佣金 column usually 0).

### Action map

| 类型 + 说明 pattern | action | Notes |
|---|---|---|
| TRD + `BOT +qty SYM @px` | swap usd→sym | qty, price from description; verify abs(金额)≈qty×price |
| TRD + `SOLD -qty SYM @px` | swap sym→usd | qty = abs |
| WIN + wire received | deposit | to_amount=abs(金额) |
| DOI + dividend / amount>0 | dividend | to_amount=abs(金额); from_ticker=underlying if parseable |
| DOI + INTEREST / amount<0 | withdraw | abs(金额) |
| JRN + REFUND / amount>0 | deposit | e.g. tax refund |
| JRN + amount<0 (withhold) | withdraw | e.g. US$ tax |
| unknown 类型 | fail-closed | |

### CHECKIN

- No holdings table in this export.
- Emit **one cash CHECKIN** after flows: `to_amount = newest-row 余额` (first data row in file order / max datetime 余额), `to_ticker=usd`.
- Flow-only shares (sample open): AVGO 7, MSFT 5; closed QLD/MU/SMH/SNDK.

### source_identity

```text
schwab:{参照号码}:{类型}
```

CHECKIN: `schwab:{YYYYMMDD}:checkin:cash:{amount}`

Prefer 参照号码 (unique in sample). If missing, fall back to date+type+amount+balance.

### Calibration targets (sample)

| Metric | Expected |
|---|---|
| Flow rows | 36 |
| + cash CHECKIN | 37 |
| End USD cash | 2865.36 (newest 余额) |
| Open shares | AVGO 7, MSFT 5 |
| Equity double fee | 0 |
| Re-import | count 0 |

### Non-goals (schwab Phase-1)

- Positions/cost CSV, tax lots, margin detail statements
- English-only header variants until sampled
- Schwab API / thinkorswim auto-sync (→ 011)
- Multi-currency non-USD (sample is US$ only)

### Decision log (schwab)

| # | Decision | Rationale |
|---|---|---|
| S1 | Living extend 009 as US6 (file import) | Same InvestmentImportService; productization 009 = file importers |
| S2 | Cash impact = 金额 + 杂费; commission = abs(杂费)+abs(佣金) | Balance walk identity |
| S3 | Cash CHECKIN from newest 余额 | No summary section |
| S4 | source_identity = 参照号码 + type | Stable broker ref |
