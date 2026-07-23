# Implementation Plan: Investment Account Import

**Branch**: `009-investment-account-import` | **Date**: 2026-07-23 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/009-investment-account-import/spec.md`

## Summary

Restore the full investment event domain model from main branch into the current hexagonal architecture with PostgreSQL + SQLite dual-backend support. Enable direct import of broker statements (DFZQ PDF), exchange trades (Binance/OKX via ccxt), and prediction market activities (Polymarket) into the relational database with complete provenance tracking.

**Technical Approach**:
- Adopt single-row SWAP event model (from/to unified schema) from current branch
- Use commission + commission_asset fields (no independent FEE action for MVP)
- Follow 007 import contract: ImportBatch → RawRecords → InvestmentEvents → Snapshot update (atomic transaction)
- Restore snapshot validation (`_validate_security_snapshot_finite`) from main branch
- Implement dual-backend equivalence tests per Constitution IV

## Technical Context

**Language/Version**: Python 3.11+

**Primary Dependencies**: 
- SQLAlchemy 2.0+ (ORM, dual-dialect support)
- Alembic (schema migrations)
- psycopg2-binary (PostgreSQL driver)
- ccxt (exchange API client library, for Phase 2)
- qpdf, mutool (external tools for DFZQ PDF processing)

**Storage**: 
- PostgreSQL 14+ (production, web deployment)
- SQLite 3.35+ (local, CLI workflow)
- Explicit backend selection via `FT_DATABASE_URL`

**Testing**: 
- pytest (unit, integration, dual-backend contracts)
- pytest-postgresql (PostgreSQL test fixtures)
- Decimal-exact assertions for financial correctness

**Target Platform**: 
- macOS / Linux (CLI primary)
- Docker (for PostgreSQL test environment)

**Project Type**: CLI + library (hexagonal architecture: domain/application/adapters)

**Performance Goals**: 
- Import <1000 transactions: <10s end-to-end (parse + database write)
- Snapshot replay: <2s for 1000 events
- Dual-backend parity: <5% performance variance acceptable

**Constraints**: 
- Decimal precision: 28,10 (sufficient for shares, price, amount)
- Transaction atomicity: All-or-nothing (no partial facts)
- File size limit: 100 MiB per statement (PDF/CSV)
- Memory: Entire statement parsed in-memory (<500 MB for 10k transactions)

**Scale/Scope**: 
- Typical user: 2-5 investment accounts, 50-200 transactions/month
- Power user: 10+ accounts, 1000+ transactions/month
- Single-user focus (multi-user in future features)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### Principle I: 财务正确性与可审计性 ✅

- **Decimal Precision**: All amounts use `Decimal(28,10)`, exact-decimal domain logic with prec=80 context
- **Currency Explicit**: Every event has explicit currency field, no implicit defaults in storage
- **Provenance**: `raw_record_id` links every imported event to source, manual CLI entries have NULL but preserve created_at
- **Idempotency**: `source_identity` unique constraint prevents duplicates, same source_digest returns existing batch
- **Error Handling**: Transaction rollback on parse/validation failure, snapshot validation rejects NaN/Infinity
- **Audit Trail**: ImportBatch tracks source_kind + source_digest + source_ref, payload preserves original parsed data

### Principle II: Spec Kit 规格驱动 ✅

- Feature driven by `spec.md` (scenarios, acceptance, FR), `plan.md` (technical decisions), `tasks.md` (execution order)
- Research phase completed (see research.md): analyzed current branch + main branch, documented schema decisions
- Design artifacts: data-model.md, contracts/cli.md, contracts/import_api.md, quickstart.md
- No behavior beyond spec: SWAP single-row model, commission field approach, snapshot validation explicitly specified

### Principle III: 测试先行与验证证据 ✅

**Test Coverage Required**:
- Unit: Parser tests (DFZQ text parsing, ccxt mapping, Polymarket activity mapping)
- Unit: Domain tests (apply_investment_event for all actions, snapshot validation edge cases)
- Integration: Full import flow (PDF → events → snapshot) for DFZQ
- Integration: Dual-backend contract matrix (same statement → both backends → assert equivalence)
- Integration: Idempotency tests (repeat import → count=0, no duplicates)
- Contract: CLI error messages (account not found, parse failure, validation failure)

**Evidence Required**:
- All tests passing on both PostgreSQL and SQLite
- Code coverage ≥85% for domain/application layers
- Manual verification: Import real DFZQ PDF (redacted) on both backends, snapshot match

### Principle IV: 显式数据库选择与行为等价 ✅ CRITICAL

**Dual-Backend Requirements**:
- Schema parity: Same Alembic migrations for both dialects, JSONB→JSON mapping explicit
- Transaction atomicity: Both use serializable semantics (PostgreSQL SERIALIZABLE, SQLite WAL+IMMEDIATE)
- Decimal precision: Both store NUMERIC(28,10), text affinity in SQLite preserves exact values
- Idempotency: Unique constraints enforced identically (workspace_id, source_identity)
- Snapshot replay: Same events → same positions/cash/cost (Decimal-exact equality)

**Parity Matrix** (see data-model.md):

| Aspect | PostgreSQL | SQLite | Equivalence Test |
|--------|-----------|--------|------------------|
| Schema | JSONB, UUID, NUMERIC(28,10) | JSON (text), TEXT uuid, NUMERIC (text affinity) | Alembic migrations apply cleanly |
| Transactions | SERIALIZABLE | WAL + IMMEDIATE | Same import → no conflicts |
| Idempotency | UNIQUE (workspace_id, source_identity) | Same | Duplicate import rejected |
| Event replay | apply_investment_event() | Same | Final snapshot positions match |
| Snapshot validation | _validate_security_snapshot_finite | Same | Same NaN/Infinity rejection |

**Prohibited Actions**:
- ❌ Automatic fallback SQLite → PostgreSQL or vice versa
- ❌ Dual writes (write to both backends simultaneously)
- ❌ Implicit cross-backend migration
- ❌ Dialect-specific event replay logic (all logic in domain layer)

**Test Strategy**:
```python
@pytest.mark.parametrize("backend", ["postgresql", "sqlite"])
def test_dfzq_import(backend, sample_pdf):
    # Import same PDF to both backends
    result = import_statement(backend, sample_pdf)
    assert result.ok
    return query_snapshot(backend)

def test_dual_backend_equivalence(postgresql_snapshot, sqlite_snapshot):
    # Assert identical positions, cash, cost basis
    assert postgresql_snapshot["positions"] == sqlite_snapshot["positions"]
```

### Principle V: 清晰边界与最小复杂度 ✅

- Domain logic (investment_projection.py) pure, no SQLAlchemy imports
- Parsers (importers/dfzq.py) return dicts, no ORM models
- Application service orchestrates transaction boundary
- Credentials handling: Minimal (env vars, config file), vault integration deferred to 011
- No premature abstraction: Parser registry is dict, not plugin framework

### Constitution Check Summary

**Status**: ✅ PASS (with noted complexity in dual-backend testing)

**Blockers**: None

**Open Issues** (to resolve in design):
1. InvestmentEventModel schema verification (need to check models.py columns)
2. Snapshot lock mechanism (confirm `load(lock=True)` exists)
3. Commission asset NULL handling (default behavior in apply_investment_event)

For persistence changes, the completed plan INCLUDES a PostgreSQL/SQLite parity
matrix covering schema, transactions, concurrency, queries, errors, and permitted
operational differences. Automatic fallback, dual writes, and implicit cross-backend
migration are constitution violations and explicitly prohibited in this design.

## Project Structure

### Documentation (this feature)

```text
specs/009-investment-account-import/
├── spec.md              # Feature specification (user scenarios, requirements)
├── plan.md              # This file (technical design, constitution check)
├── research.md          # Phase 0 research (current vs main branch analysis)
├── data-model.md        # Entity definitions, relationships, dual-backend schema
├── quickstart.md        # End-to-end validation scenarios
├── contracts/
│   ├── cli.md          # ft import command specification
│   └── import_api.md   # InvestmentImportService API contract
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT YET CREATED)
```

### Source Code (repository root)

**Hexagonal Architecture** (domain/application/adapters pattern):

```text
src/ft/
├── domain/
│   ├── investment_projection.py       # [EXISTS] Event replay, snapshot projection
│   ├── investment.py                  # [EXISTS] Domain DTOs
│   ├── imports.py                     # [EXISTS] Import domain types
│   └── decimal.py                     # [EXISTS] exact_decimal utility
│
├── application/
│   ├── investment.py                  # [EXISTS] InvestmentService (CLI commands)
│   ├── investment_import.py           # [NEW] InvestmentImportService (statement import)
│   └── statement_import.py            # [EXISTS] Generic StatementImportService (used by 007)
│
├── adapters/
│   ├── relational/
│   │   ├── models.py                  # [EXTEND] Add/verify InvestmentEventModel
│   │   ├── investments.py             # [EXTEND] Add add_event() method
│   │   ├── imports.py                 # [EXISTS] Import provenance repository
│   │   ├── repositories.py            # [EXISTS] Snapshot, Account repos
│   │   └── uow.py                     # [EXISTS] Unit of work with investments property
│   │
│   ├── importers/
│   │   ├── dfzq.py                    # [EXISTS] DFZQ PDF parser (parse_dfzq_text)
│   │   ├── ibkr.py                    # [NEW] IBKR Activity CSV (US5 / FR-014–017)
│   │   ├── schwab.py                  # [NEW] Schwab Transaction History CSV (US6 / FR-018–021)
│   │   ├── exchange.py                # [DEFERRED → 011] ccxt — not 009
│   │   └── polymarket.py              # [DEFERRED → 011] activity API; quotes → 010
│   │
│   └── portfolio_cli.py               # [EXISTS] Portfolio query CLI output
│
└── cli/
    ├── __init__.py                    # [EXISTS] CLI app entry point
    ├── import_cmd.py                  # [NEW] ft import command (dispatches to investment/cash)
    └── investment.py                  # [EXISTS] ft stock subcommands (buy, sell, etc.)

tests/
├── unit/
│   ├── domain/
│   │   ├── test_investment_projection.py           # [EXTEND] Add swap, dividend, checkin tests
│   │   └── test_investment_snapshot_validation.py  # [NEW] Finite checks
│   │
│   └── importers/
│       ├── test_dfzq_parser.py        # [NEW] parse_dfzq_text edge cases
│       ├── test_ibkr_parser.py        # [NEW] IBKR CSV parse + map (US5)
│       ├── test_ibkr_map.py           # [NEW] fee contract + FX map (US5)
│       ├── test_exchange_parser.py    # [DEFERRED → 011]
│       └── test_polymarket_parser.py  # [DEFERRED → 011]
│
├── integration/
│   ├── test_dfzq_import.py            # [NEW] Full DFZQ import flow
│   ├── test_dfzq_import_idempotency.py # [NEW] Duplicate detection
│   ├── test_ibkr_import.py            # [NEW] Full IBKR CSV import (US5)
│   ├── test_exchange_import.py        # [DEFERRED → 011]
│   └── test_polymarket_import.py      # [DEFERRED → 011]
│
├── contract/
│   ├── test_dual_backend_dfzq.py      # [NEW] PostgreSQL vs SQLite parity
│   ├── test_dual_backend_ibkr.py      # [NEW] IBKR dual-backend parity (US5)
│   ├── test_dual_backend_exchange.py  # [DEFERRED → 011]
│   └── test_cli_errors.py             # [NEW] Error message validation
│
└── fixtures/
    ├── dfzq/
    │   ├── sample_statement.txt       # [NEW] Simulated mutool output
    │   └── sample_statement.pdf       # [NEW] Real DFZQ PDF (redacted, for manual testing)
    │
    └── credentials/
        └── test_credentials.json      # [NEW] Mock API credentials (TEST_ prefix)

alembic/
└── versions/
    └── YYYYMMDD_HHMM_add_investment_event_fields.py  # [NEW] Migration for commission_asset, etc.
```

**Structure Decision**: 

This feature follows the established hexagonal architecture with domain/application/adapters separation. Key decisions:

1. **Domain Layer Pure**: `investment_projection.py` already exists with event replay logic; we extend test coverage but no structural changes needed.

2. **New Application Service**: `investment_import.py` separates statement import (batch provenance, transaction orchestration) from CLI command execution (`investment.py` handles buy/sell/swap commands).

3. **Parser Adapter Pattern**: `importers/` directory holds broker/exchange-specific parsers. Each implements the `InvestmentStatementParser` protocol (duck typing, no ABC overhead for 3 parsers).

4. **CLI Dispatch**: `import_cmd.py` routes to `InvestmentImportService` (investment sources) or `StatementImportService` (cash sources) based on `--source` flag.

5. **Test Organization**:
   - `unit/`: Domain logic, parser output correctness
   - `integration/`: Full import flow, database writes
   - `contract/`: Dual-backend equivalence, CLI error messages

6. **No New Top-Level Directories**: Fits cleanly into existing `src/ft/` structure.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No constitution violations requiring justification. All MUST constraints satisfied:

- ✅ Decimal precision explicit
- ✅ Spec-driven with complete artifacts
- ✅ Test-first approach documented
- ✅ Dual-backend equivalence matrix provided
- ✅ Domain/adapter boundaries clear

**Acknowledged Complexity** (not violations, but notable effort):

| Complexity | Justification | Mitigation |
|------------|---------------|------------|
| Dual-backend contract testing | Constitution IV mandates equivalence proof for all persistence changes | Test matrix in quickstart.md, parametrized pytest fixtures, CI runs both backends |
| External tool dependencies (qpdf, mutool) | DFZQ PDF parsing requires text extraction; no Python-native PDF parser handles all edge cases reliably | Clear installation instructions, graceful error messages with install commands, tool availability checks on startup |
| Three parser implementations | Spec requires DFZQ (P1), Exchange (P2), Polymarket (P3) for complete investment coverage | Parser protocol interface, phased delivery (DFZQ blocks 009 completion, others in P2/P3), shared test fixtures |

**Not Added** (avoided premature complexity):

- ❌ Plugin architecture for parsers (dict registry sufficient for 3 parsers)
- ❌ Encrypted credential vault (deferred to 011, env vars sufficient for 009)
- ❌ Async import workers (single-user CLI, <10s imports acceptable)
- ❌ Web upload UI (deferred to 012, CLI sufficient for 009)
- ❌ Cross-backend migration tool (Constitution prohibits implicit migration)

## Technical Design Deep-Dive

### Event Schema: Single-Row SWAP Decision

**Background**: Main branch used two-row model (SWAP_OUT + SWAP_IN) for crypto pairs, current branch has unified from/to schema.

**Decision**: Adopt current branch single-row SWAP (spec FR-006).

**Rationale**:
1. **Simpler Transaction Boundary**: One event = one atomic operation (no need to ensure SWAP_OUT/SWAP_IN consistency)
2. **Cost Basis Tracking**: Released cost calculated in `apply_investment_event()` from source position (lines 216-219 in investment_projection.py)
3. **Already Implemented**: Current branch projection logic supports unified schema
4. **Audit Trail Preserved**: JSON payload contains full from/to details

**Trade-offs**:
- Lose explicit two-leg visualization (but payload preserves structure)
- Must ensure from_ticker/to_ticker never NULL for SWAP (domain validation)

**Implementation Notes**:
- BUY command → SWAP event (from=cash, to=ticker)
- SELL command → SWAP event (from=ticker, to=cash)
- Crypto swap → SWAP event (from=ticker1, to=ticker2)
- Commission: Deducted from source (if commission_asset==from_ticker) or target (if ==to_ticker) or third position (if neither)

### Commission Field vs Independent FEE Action

**Background**: Main branch has independent FEE action for withdrawal fees, exchange third-party fees (e.g., BNB for Binance).

**Decision**: Use commission + commission_asset fields (spec FR-007), no independent FEE action in 009.

**Rationale**:
1. **Coverage**: 99% of broker/exchange fees are trade commissions (deducted from trade proceeds or added to cost)
2. **Simpler Event Model**: 5 actions (deposit, withdraw, swap, dividend, checkin) vs 6 with FEE
3. **Commission Asset Handles Edge Cases**: BNB-paid fees via commission_asset="bnb"
4. **MVP Scope**: Independent fees (withdrawal fees, account management) can be added in future feature

**Out of Scope** (as independent FEE action):
- Withdrawal fees (crypto network fees when moving assets out)
- Account management fees (monthly/annual broker fees)
- Margin interest as standalone FEE action

**Note (fee contracts, one-place rule)**:
- **Universal**: each fen of fee appears in **either** cash leg **or** `commission`, never both.
- **DFZQ**: 总发生金额 is **net** cash. Peel 手续费 into `commission` and adjust cash leg so total cash impact still equals |net| (`_split_commission` in `importers/dfzq.py`). 印花税/过户费 stay in cash leg when not peeled. Fallback commission=0 only when peel impossible.
- **IBKR US5**: statement 总额 is **gross**; cash leg = |总额|, `commission` = |佣金| (equity). FX may embed commission when net==gross.
- Do not copy DFZQ peel formulas onto IBKR gross rows or vice versa.

**Future Extension** (if needed):
- Add FEE action in 013 or later
- Migrate existing commission-only events (backwards compatible, commission field remains)

### Idempotency Strategy

**Level 1: Batch (source_digest)**
```python
source_digest = f"sha256:{hashlib.sha256(file_content).hexdigest()}"
# Example: "sha256:abc123...def456"

# On import:
existing_batch = query(ImportBatch).filter_by(
    workspace_id=workspace_id,
    source_kind='dfzq',
    source_digest=source_digest
).first()

if existing_batch and existing_batch.status == 'completed':
    return OperationResult(ok=True, count=0, duplicate=True, batch_id=existing_batch.id)
```

**Level 2: Record (source_identity)**
```python
# DFZQ: Composite business key
source_identity = f"dfzq:{date}:{ticker}:{action}:{amount}:{balance}"

# IBKR Activity CSV (amounts via format(Decimal, "f"); type = buy|sell|deposit|dividend|wht|interest|fx|checkin)
source_identity = f"ibkr:{date}:{type}:{code}:{qty}:{net}:{commission}"

# Exchange: Trade ID (authoritative)
source_identity = f"ccxt:{provider}:trade:{trade_id}"

# Polymarket: Transaction hash (blockchain finality)
source_identity = f"polymarket:tx:{tx_hash}"

# Database constraint:
UNIQUE (workspace_id, source_type, source_identity)
```

**Edge Cases**:
1. **Same file, different path**: Digest matches → idempotent (path not in digest)
2. **File modified (whitespace)**: Digest changes → new batch, but records may collide on source_identity → unique constraint violation
3. **Two PDFs with overlapping trades**: Different digests, but source_identity collision → explicit error with batch ID reference

**Error Handling**:
- Duplicate batch (completed) → Success, count=0, inform user
- Duplicate source_identity (across batches) → Fail with clear message: "Record already imported in batch <id> on <date>"

### Snapshot Validation (Restoring from Main)

**Main Branch Implementation** (`src/ft/stock.py` lines 89-99):
```python
def _validate_security_snapshot_finite(snap: dict) -> None:
    security = snap.get("accounts", {}).get("security", {})
    for account_name, account in security.items():
        _ensure_finite_values(**{f"{account_name}.cash": account.get("cash", 0)})
        for ticker, pos in account.get("positions", {}).items():
            _ensure_finite_values(
                **{
                    f"{account_name}.{ticker}.shares": pos.get("shares", 0),
                    f"{account_name}.{ticker}.avg_cost": pos.get("avg_cost", 0),
                }
            )
```

**Current Branch Adaptation**:
- Snapshot structure changed: `positions[ticker] = {shares, total_cost, cost_currency}`
- Validate: `shares` and `total_cost` are finite (not NaN, Infinity)
- Allow negative: Shares can be negative (short positions), cost can be negative (certain scenarios)
- Call point: After all events applied in import transaction, before snapshot save

**Implementation**:
```python
# In src/ft/domain/investment_projection.py or new validation.py
def validate_investment_snapshot(snapshot: dict) -> None:
    for account_type in ["security", "crypto"]:
        accounts = snapshot.get("accounts", {}).get(account_type, {})
        for account_name, account in accounts.items():
            positions = account.get("positions", {})
            for ticker, position in positions.items():
                shares = Decimal(str(position.get("shares", "0")))
                total_cost = Decimal(str(position.get("total_cost", "0")))
                
                if not shares.is_finite():
                    raise ValueError(
                        f"Position '{ticker}' in account '{account_name}' has "
                        f"invalid shares: {shares}"
                    )
                
                if not total_cost.is_finite():
                    raise ValueError(
                        f"Position '{ticker}' in account '{account_name}' has "
                        f"invalid total_cost: {total_cost}"
                    )
```

### Credentials Management (Temporary Solution)

**Phase 1 (009 MVP)**:

**Environment Variables**:
```bash
export FT_EXCHANGE_BINANCE_API_KEY="your_key"
export FT_EXCHANGE_BINANCE_API_SECRET="your_secret"
export FT_POLYMARKET_WALLET="0x..."
```

**Config File** (`~/.ft/credentials.json`):
```json
{
  "binance": {
    "api_key": "your_key",
    "api_secret": "your_secret"
  },
  "okx": {
    "api_key": "your_key",
    "api_secret": "your_secret",
    "password": "your_passphrase"
  },
  "polymarket": {
    "proxy_wallet": "0x..."
  }
}
```

**Security Measures**:
- Check `.gitignore` includes `credentials.json`
- Warn if file permissions not 0600 (world-readable)
- Test credentials prefixed with `TEST_` (e.g., `TEST_BINANCE_API_KEY`)

**Long-term Solution** (deferred to 011):
- Encrypted vault (e.g., keyring integration, OS keychain)
- Per-workspace credential isolation
- Credential rotation support
- Audit log for credential access

### Dual-Backend Test Strategy

**Test Pyramid**:

**Unit Tests** (domain, no database):
- `test_apply_investment_event()`: All actions (deposit, withdraw, swap, dividend, checkin)
- `test_validate_investment_snapshot()`: NaN, Infinity, finite edge cases
- `test_dfzq_parser()`: Text parsing correctness

**Integration Tests** (single backend, full flow):
- `test_dfzq_import_postgres()`: Import PDF → verify events + snapshot (PostgreSQL)
- `test_dfzq_import_sqlite()`: Same test, SQLite backend
- `test_idempotency()`: Repeat import → count=0

**Contract Tests** (dual-backend parity):
```python
@pytest.mark.parametrize("backend", ["postgresql", "sqlite"])
def test_dfzq_import_dual_backend(backend, sample_dfzq_pdf):
    # Setup database for backend
    setup_backend(backend)
    
    # Import statement
    result = import_investment_statement(
        source="dfzq",
        source_path=sample_dfzq_pdf,
        account_name="东方证券",
    )
    
    assert result.ok
    assert result.count == 5
    
    # Query and return for cross-backend comparison
    events = query_investment_events(account_name="东方证券")
    snapshot = get_portfolio(account_name="东方证券")
    
    return {
        "event_count": len(events),
        "total_amount": sum(Decimal(e["amount"]) for e in events if e["amount"]),
        "positions": snapshot["positions"],
    }

def test_backend_equivalence(results_by_backend):
    """Assert PostgreSQL and SQLite produce identical results."""
    pg = results_by_backend["postgresql"]
    sqlite = results_by_backend["sqlite"]
    
    assert pg["event_count"] == sqlite["event_count"]
    assert pg["total_amount"] == sqlite["total_amount"]
    assert pg["positions"] == sqlite["positions"]
```

**CI Pipeline**:
```yaml
# .github/workflows/test.yml
jobs:
  test:
    strategy:
      matrix:
        backend: [postgresql, sqlite]
    steps:
      - name: Setup database
        run: |
          if [ "${{ matrix.backend }}" == "postgresql" ]; then
            docker run -d -p 5432:5432 postgres:14
            export FT_DATABASE_URL="postgresql://postgres@localhost/test"
          else
            export FT_DATABASE_URL="sqlite:///tmp/test.db"
          fi
      - name: Run tests
        run: pytest tests/ -v --backend=${{ matrix.backend }}
```

## Open Questions for Tasks Phase

1. **InvestmentEventModel Schema**: Need to verify actual columns in `src/ft/adapters/relational/models.py` match data-model.md design
   - Action: Read models.py in tasks phase, create migration if needed
   
2. **Snapshot Lock**: Confirm `snapshot.load(lock=True)` exists for optimistic locking during import
   - Action: Check repositories.py, add lock parameter if missing
   
3. **Commission Asset Default**: When commission_asset is NULL, does apply_investment_event() default to from_ticker or to_ticker?
   - Action: Test current behavior, document in code comments
   
4. **CHECKIN Semantics**: Should CHECKIN replace entire position or delta-adjust?
   - Current: Replaces (line 241-244 in investment_projection.py: `_set(target, to_amount, cost)`)
   - Decision: Keep replace semantics (matches main branch behavior)
   
5. **Test Fixture Organization**: Where should test PDFs/credentials live?
   - Decision: `tests/fixtures/dfzq/`, `tests/fixtures/credentials/`
   - `.gitignore` must exclude real credentials, include TEST_ prefixed mocks
   
6. **CLI Router Integration**: Does existing CLI have source-based dispatch logic?
   - Action: Read `src/ft/cli/` structure in tasks phase, design dispatch in import_cmd.py

## Implementation Phases

### Phase 1: DFZQ Direct Import (P1) - Blocks 009 Completion

**Scope**: FR-001, FR-002, FR-003, FR-004, FR-005, FR-006, FR-011, FR-012, FR-013

**Deliverables**:
- `src/ft/application/investment_import.py`: InvestmentImportService
- `src/ft/cli/import_cmd.py`: `ft import` command with source dispatch
- `src/ft/domain/investment_validation.py`: validate_investment_snapshot()
- Migration: Add/verify InvestmentEventModel columns (commission_asset, etc.)
- Tests: Unit (parser, validation), integration (full import), contract (dual-backend)

**Success Criteria** (from spec SC-001, SC-002, SC-004, SC-006):
- Import DFZQ PDF in <5min end-to-end (user time, not wall time)
- Dual-backend 100% equivalence on contract tests
- Idempotent duplicate detection (count=0 on repeat)
- Snapshot validation rejects NaN/Infinity

### Phase 1b: IBKR Activity CSV Import (P1) - Living extension 2026-07-23

**Scope**: FR-014, FR-015, FR-016, FR-017, FR-002, FR-011, FR-013; SC-008

**Deliverables**:
- `src/ft/importers/ibkr.py`: parse_ibkr_csv / map / source_identity / cash CHECKIN
- Wire `InvestmentImportService` for multi-source (not dfzq-only map)
- CLI `--source ibkr`; fixtures under `tests/fixtures/ibkr/`
- research.md IBKR census + fee contract (authoritative)

**Success Criteria**:
- Equity: no double fee; projection cash = |净额|
- End cash CHECKIN = 总结.期末现金
- Unknown 交易类型 fail-closed
- Dual-backend parity on fixture
- Idempotent re-import

### Phase 1c: Schwab Transaction History CSV (P1) - Living 2026-07-23

**Scope**: FR-018–021, FR-002, FR-011, FR-013; SC-009

**Deliverables**: `src/ft/importers/schwab.py`; multi-source wire; CLI `--source schwab`; fixtures `tests/fixtures/schwab/`.

**Fee contract**: cash leg = abs(金额); commission = abs(杂费)+abs(佣金); CHECKIN = newest 余额.

### Phase 2: Exchange Sync — **NOT IN 009** (→ `011-investment-connector-sync`)

**Living 2026-07-23**: Removed from 009 delivery per `docs/productization-refactor-plan.md`.
Former FR-008/FR-010 and draft tasks T072–T095 are **deferred**; do not implement under 009.
CLI source names `binance`/`okx` may remain reserved and must fail closed until 011.
### Phase 3: Polymarket Sync — **NOT IN 009** (→ `011`; quotes → `010`)

**Living 2026-07-23**: Activity/API trade sync deferred to **011**. Live market quotes for
Polymarket → **010**. Former FR-009/FR-010 and draft tasks T096–T112 are **not** 009 work.

## Risks and Mitigation

### High Risk: DFZQ Format Changes

**Risk**: Broker updates PDF layout, parser breaks

**Mitigation**:
- Parser returns specific error (page/line + raw text snippet)
- Fallback: `ft stock convert` generates CSV preview for manual review
- Version detection: Log PDF metadata (creation date, title) to detect format drift

### High Risk: Dual-Backend Divergence

**Risk**: Subtle logic difference causes different results between PostgreSQL and SQLite

**Mitigation**:
- Contract test matrix on every import feature (parametrized pytest)
- CI runs both backends, asserts Decimal-exact equality
- Manual verification: Import real statement to both, diff results

### Medium Risk: Scope creep back into 009

**Risk**: Implementer re-opens US3/US4 under 009 despite productization plan.

**Mitigation**:
- Spec FR-008/009/010 marked DEFERRED → 011; SC-003 redefined as DFZQ+IBKR only
- tasks.md Phase 5/6 cancelled; do not implement under 009

### Low Risk: Performance Degradation

**Risk**: Large PDF (10k+ transactions) takes >30s

**Mitigation**:
- 100 MiB file size limit
- Batch insert in chunks (500 records/batch)
- Log import duration, investigate if >30s
- Profile: Measure parse time vs database write time separately
