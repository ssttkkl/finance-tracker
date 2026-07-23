# Tasks: Investment Account Import

**Input**: Design documents from `/specs/009-investment-account-import/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Tests are MANDATORY for executable behavior, financial logic, data, migration, compatibility, and interface changes. Write failing tests before the corresponding implementation. Persistence changes require the same contract matrix against SQLite and real PostgreSQL; neither backend may be represented only by mocks.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `- [ ] T### [P?] [US#?] Description with file path`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[US#]**: User story label (US1=DFZQ P1, US2=Event types P1, US5=IBKR CSV P1, US6=Schwab CSV P1; US3/US4 deferred → 011)
- Setup/Foundational tasks have no story label
- Include exact file paths in descriptions

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization, dependencies, test fixtures

- [X] T001 [P] Verify external tools installed (qpdf, mutool) and add version checks to src/ft/importers/dfzq.py
- [X] T002 [P] Add ccxt dependency to pyproject.toml for exchange API support (version lock for stability)
- [X] T003 [P] Create test fixtures directory structure: tests/fixtures/dfzq/, tests/fixtures/credentials/, tests/fixtures/exchange/
- [X] T004 [P] Create sample DFZQ text fixture tests/fixtures/dfzq/sample_statement.txt with 5 transaction types
- [X] T005 [P] Create test credentials fixture tests/fixtures/credentials/test_credentials.json with TEST_ prefix
- [X] T006 [P] Add tests/fixtures/ to .gitignore (exclude real credentials, PDFs with PII)

---

## Phase 2: Foundational (Blocking All Stories)

**Purpose**: Core domain models, snapshot validation, database schema

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Database Schema & Migration

- [X] T007 Inspect src/ft/adapters/relational/models.py InvestmentEventModel schema, verify columns match data-model.md
- [X] T008 Create Alembic migration alembic/versions/YYYYMMDD_add_investment_event_fields.py to add commission_asset column if missing (NOT NEEDED - payload JSON stores all fields)
- [X] T009 Add/verify investment_events indexes: (workspace_id, account_id, occurred_at), (workspace_id, raw_record_id) (ALREADY EXISTS)
- [X] T010 Run migration on both PostgreSQL and SQLite test databases, verify schema parity (NO NEW MIGRATION NEEDED)

### Domain Layer - Snapshot Validation

- [X] T011 Create src/ft/domain/investment_validation.py with validate_investment_snapshot() function
- [X] T012 Implement finite checks: all position shares, total_cost must be finite Decimals (no NaN, Infinity)
- [X] T013 Implement cost currency conflict detection per investment_projection.py existing logic (DEFERRED - handled in projection)
- [X] T014 Write unit tests tests/unit/domain/test_investment_snapshot_validation.py covering NaN, Infinity, negative, zero edge cases

### Domain Layer - Event Replay Extension

- [X] T015 [P] Review src/ft/domain/investment_projection.py apply_investment_event() for all action types (REVIEWED - complete)
- [X] T016 [P] Write unit tests tests/unit/domain/test_investment_event_replay.py for SWAP (buy/sell/crypto-to-crypto), DIVIDEND, CHECKIN actions
- [X] T017 [P] Verify commission_asset handling: default to from_ticker if NULL, third-party asset deduction logic (VERIFIED - lines 220-233)

### Application Layer - Import Service

- [X] T018 Create src/ft/application/investment_import.py with InvestmentImportService class (EXISTS; multi-source branch still US5 T141)
- [X] T019 Implement import_statement() method: batch → raw_records → investment_events → snapshot update (atomic transaction) (EXISTS for dfzq)
- [X] T020 Integrate validate_investment_snapshot() call before transaction commit (EXISTS)
- [X] T021 Add idempotency: check existing batch by source_digest, return existing batch_id if status='completed' (EXISTS)

### Adapter Layer - Repository Extensions

- [X] T022 Add add_investment_event() method to src/ft/adapters/relational/investments.py RelationalInvestmentCommandRepository (ALREADY EXISTS as add())
- [X] T023 Implement event creation with raw_record_id linkage, workspace isolation, unique constraint on (workspace_id, raw_record_id) (ALREADY EXISTS)
- [X] T024 Verify src/ft/adapters/relational/repositories.py snapshot repository has load(lock=True) for optimistic locking (VERIFIED)

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Import DFZQ Broker Statement Directly (Priority: P1) 🎯 MVP

**Goal**: Direct import of DFZQ PDF statements to database with full provenance tracking

**Independent Test**: Import real DFZQ PDF to both PostgreSQL and SQLite, verify identical event count and snapshot

### Tests for User Story 1 (MANDATORY) ⚠️

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [X] T025 [P] [US1] Create tests/unit/importers/test_dfzq_parser.py with parse_dfzq_text edge cases (buy, sell, deposit, dividend, checkin)
- [X] T026 [P] [US1] Create tests/integration/test_dfzq_import.py with full import flow (PDF → batch → events → snapshot)
- [X] T027 [P] [US1] Create tests/integration/test_dfzq_import_idempotency.py to verify duplicate rejection (repeat import → count=0)
- [X] T028 [P] [US1] Create tests/contract/test_dual_backend_dfzq.py parametrized for PostgreSQL and SQLite with equivalence assertions
- [X] T029 [P] [US1] Create tests/contract/test_cli_import_errors.py for account not found, parse failure, validation failure scenarios

### Parser - DFZQ Event Mapping

- [X] T030 [US1] Review src/ft/importers/dfzq.py parse_dfzq_text(), verify action mapping to unified event schema
- [X] T031 [US1] Implement source_identity construction: f"dfzq:{date}:{ticker}:{action}:{amount}:{balance}"
- [X] T032 [US1] Map DFZQ actions to investment events: 证券买入→SWAP(cny→ticker), 证券卖出→SWAP(ticker→cny), 银行转证券→DEPOSIT, 红利入账→DIVIDEND
- [X] T033 [US1] DFZQ fee contract (peel model): 总发生金额 is **net** cash impact. If 手续费 separable: BUY from_amount=|net|-fee, SELL to_amount=|net|+fee, commission=fee, commission_asset=cny so projection cash impact still equals |net|. If fee missing or cannot peel (BUY fee≥net): cash leg=|net|, commission=0. 印花税/过户费 remain inside cash leg (not peeled). **Never** put the same fee in both cash leg and commission. (IBKR US5 uses statement **gross** + commission; different source shape, same one-place rule.)
- [X] T034 [US1] Add CHECKIN event from final balance line in DFZQ statement

### Application Service - DFZQ Import Flow

- [X] T035 [US1] Implement DFZQ-specific import flow in src/ft/application/investment_import.py: parse_dfzq() wrapper (EXISTS as _parse_statement source==dfzq)
- [X] T036 [US1] Integrate with ImportBatch creation: source_kind='dfzq', source_digest=SHA256(file_content), source_ref=filename (EXISTS)
- [X] T037 [US1] Implement RawFile creation: save file metadata (path, digest, size, media_type) (EXISTS; media_type still text/plain after mutool — ok for dfzq path)
- [X] T038 [US1] Implement RawRecord batch creation: iterate parsed rows, construct source_identity, save payload (EXISTS; still dfzq-only helpers — US5 T141 generalizes)
- [X] T039 [US1] Implement InvestmentEvent creation: map each raw_record via map_dfzq_to_investment_event + apply_investment_event (EXISTS)
- [X] T040 [US1] Implement snapshot update: replay events via apply_investment_event(), call validate_investment_snapshot(), commit (EXISTS)

### CLI - Import Command

- [X] T041 [US1] Create src/ft/cli/import_cmd.py with ft import command group (INTEGRATED into existing ft import with source routing)
- [X] T042 [US1] Implement source dispatch: --source dfzq routes to InvestmentImportService.import_dfzq()
- [X] T043 [US1] Add CLI arguments: <file_path> (required), --source (required), --account (required), --password (optional for encrypted PDF)
- [X] T044 [US1] Add account validation: must be type='security' or 'crypto', clear error if type mismatch
- [X] T045 [US1] Add output formatting: success message with batch_id, event count breakdown, final balance
- [X] T046 [US1] Add error handling: tool not found (qpdf/mutool), parse failure (page/line reference), validation failure (specific field)

### Validation & Error Handling

- [X] T047 [US1] Add PDF tool availability check: verify qpdf and mutool in PATH, provide install instructions on error
- [X] T048 [US1] Add parse error enrichment: capture page/line context, include raw text snippet in error message
- [X] T049 [US1] Add transaction rollback on failure: ensure no partial facts (no events written if any record fails)
- [X] T050 [US1] Add idempotent success response: duplicate batch returns existing batch_id with clear "already imported" message

**Checkpoint**: User Story 1 fully functional - can import DFZQ statements on both backends with idempotency

---

## Phase 4: User Story 2 - Restore Full Investment Event Types (Priority: P1)

**Goal**: Complete event type support (SWAP/DEPOSIT/WITHDRAW/DIVIDEND/CHECKIN) with snapshot validation

**Independent Test**: Create events of each type via CLI commands, verify snapshot calculations match expected cost basis

### Tests for User Story 2 (MANDATORY) ⚠️

- [X] T051 [P] [US2] Extend tests/unit/domain/test_investment_event_replay.py with comprehensive SWAP scenarios (buy, sell, crypto swap)
- [X] T052 [P] [US2] Add DEPOSIT/WITHDRAW test cases to test_investment_event_replay.py (cash in/out, position unchanged)
- [X] T053 [P] [US2] Add DIVIDEND test cases to test_investment_event_replay.py (cash increase, position unchanged, from_ticker audit)
- [X] T054 [P] [US2] Add CHECKIN test cases to test_investment_event_replay.py (position replacement, cash replacement)
- [X] T055 [P] [US2] Create tests/integration/test_event_types_integration.py with multi-event sequences (buy → dividend → sell)

### Domain Logic - SWAP Event Handling

- [X] T056 [US2] Verify SWAP buy logic in src/ft/domain/investment_projection.py: from=cash, to=ticker, commission deducted from cash
- [X] T057 [US2] Verify SWAP sell logic: from=ticker, to=cash, released_cost calculation (proportional cost basis)
- [X] T058 [US2] Verify SWAP crypto-to-crypto logic: from=ticker1, to=ticker2, released_cost transferred
- [X] T059 [US2] Verify commission_asset handling: third-party fee (e.g., BNB) deducted from correct position
- [X] T060 [US2] Add edge case handling: zero shares, zero cost, missing position (create on first event)

### Domain Logic - Other Event Types

- [X] T061 [P] [US2] Verify DEPOSIT logic in src/ft/domain/investment_projection.py: to_ticker cash increase
- [X] T062 [P] [US2] Verify WITHDRAW logic: from_ticker cash decrease
- [X] T063 [P] [US2] Verify DIVIDEND logic: to_ticker cash increase, from_ticker for audit (position unchanged)
- [X] T064 [P] [US2] Verify CHECKIN logic: position replacement (not delta), used for statement reconciliation

### Snapshot Validation Integration

- [X] T065 [US2] Add validate_investment_snapshot() call to src/ft/application/investment.py InvestmentService methods (buy, sell, swap, etc.)
- [X] T066 [US2] Add validation to investment_import.py after event replay (before commit)
- [X] T067 [US2] Test snapshot validation rejection: inject NaN via corrupted event, verify transaction rollback
- [X] T068 [US2] Test snapshot validation rejection: inject Infinity via corrupted event, verify error message includes field name

### CLI Command Extensions

- [X] T069 [P] [US2] Verify src/ft/cli/investment.py buy/sell/swap/deposit/withdraw/dividend/checkin commands work with validation
- [X] T070 [P] [US2] Add commission_asset parameter to swap command (optional, defaults to from_ticker)
- [X] T071 [P] [US2] Update CLI help text to explain SWAP single-row model vs BUY/SELL legacy commands

**Checkpoint**: All event types supported with snapshot validation - investment domain model complete

---

## Phase 5: User Story 3 - Exchange API — **CANCELLED / DEFERRED → 011**

> Living 2026-07-23: Not part of 009 completion. See `docs/productization-refactor-plan.md`.
> Historical task IDs T072–T095 retained as struck/cancelled for audit only.

## Phase 5 (historical detail)

**Goal**: Sync Binance/OKX trades via ccxt API to investment events

**Independent Test**: Configure Binance test credentials, sync trades to both backends, verify idempotency

### Tests for User Story 3 (MANDATORY) ⚠️

- [ ] ~~T072~~ **CANCELLED → 011** [P] [US3] Create tests/unit/importers/test_exchange_parser.py with ccxt trade mapping (buy, sell, crypto swap, commission)
- [ ] ~~T073~~ **CANCELLED → 011** [P] [US3] Create tests/integration/test_exchange_import.py with mock ccxt client (avoid live API in tests)
- [ ] ~~T074~~ **CANCELLED → 011** [P] [US3] Create tests/integration/test_exchange_import_idempotency.py to verify trade ID deduplication
- [ ] ~~T075~~ **CANCELLED → 011** [P] [US3] Create tests/contract/test_dual_backend_exchange.py parametrized for PostgreSQL and SQLite

### Parser - Exchange Trade Mapping

- [ ] ~~T076~~ **CANCELLED → 011** [US3] Create src/ft/importers/exchange.py with ExchangeStatementParser class
- [ ] ~~T077~~ **CANCELLED → 011** [US3] Implement ccxt client initialization: load credentials from env vars or ~/.ft/credentials.json
- [ ] ~~T078~~ **CANCELLED → 011** [US3] Implement fetch_trades() wrapper: pagination, rate limiting, since parameter support
- [ ] ~~T079~~ **CANCELLED → 011** [US3] Implement trade_to_investment_event() mapping: side=buy → SWAP(quote→base), side=sell → SWAP(base→quote)
- [ ] ~~T080~~ **CANCELLED → 011** [US3] Implement commission mapping: fee.cost → commission, fee.currency → commission_asset
- [ ] ~~T081~~ **CANCELLED → 011** [US3] Implement source_identity: f"ccxt:{provider}:trade:{trade.id}"

### Credentials Management

- [ ] ~~T082~~ **CANCELLED → 011** [US3] Create src/ft/config/credentials.py with load_credentials(provider) function
- [ ] ~~T083~~ **CANCELLED → 011** [US3] Implement environment variable loading: FT_EXCHANGE_{PROVIDER}_API_KEY, FT_EXCHANGE_{PROVIDER}_API_SECRET
- [ ] ~~T084~~ **CANCELLED → 011** [US3] Implement config file loading: ~/.ft/credentials.json with provider → {api_key, api_secret, password?} mapping
- [ ] ~~T085~~ **CANCELLED → 011** [US3] Add security checks: warn if credentials.json not 0600, verify .gitignore includes credentials.json
- [ ] ~~T086~~ **CANCELLED → 011** [US3] Add test credentials: TEST_ prefix, separate fixture for integration tests

### Application Service - Exchange Import Flow

- [ ] ~~T087~~ **CANCELLED → 011** [US3] Add import_exchange() method to src/ft/application/investment_import.py
- [ ] ~~T088~~ **CANCELLED → 011** [US3] Implement exchange-specific batch creation: source_kind='ccxt_{provider}', source_digest=hash(query_params), source_ref=date_range
- [ ] ~~T089~~ **CANCELLED → 011** [US3] Implement incremental sync: --since parameter filters trades by timestamp, existing trade IDs skipped
- [ ] ~~T090~~ **CANCELLED → 011** [US3] Implement error handling: API timeout, rate limit, invalid credentials, network failure (all fail-closed, no partial facts)

### CLI - Exchange Import Command

- [ ] ~~T091~~ **CANCELLED → 011** [US3] Add --source binance support to src/ft/cli/import_cmd.py
- [ ] ~~T092~~ **CANCELLED → 011** [US3] Add --source okx support to src/ft/cli/import_cmd.py
- [ ] ~~T093~~ **CANCELLED → 011** [US3] Add --since parameter: ISO date (e.g., 2026-01-01) for incremental sync
- [ ] ~~T094~~ **CANCELLED → 011** [US3] Add account validation: must be type='crypto', clear error if type='security'
- [ ] ~~T095~~ **CANCELLED → 011** [US3] Add credential validation: check provider credentials exist before API call, provide setup instructions on error

**Checkpoint**: Exchange sync functional - can import Binance/OKX trades with incremental sync

---

## Phase 6: User Story 4 - Polymarket Activity — **CANCELLED / DEFERRED → 011** (quotes → 010)

> Living 2026-07-23: Not part of 009 completion.
> Historical task IDs T096–T112 retained as cancelled for audit only.

## Phase 6 (historical detail)

**Goal**: Sync Polymarket trades and resolutions via Activity API

**Independent Test**: Configure Polymarket wallet, sync activities to both backends, verify idempotency

### Tests for User Story 4 (MANDATORY) ⚠️

- [ ] ~~T096~~ **CANCELLED → 011** [P] [US4] Create tests/unit/importers/test_polymarket_parser.py with Activity API response mapping
- [ ] ~~T097~~ **CANCELLED → 011** [P] [US4] Create tests/integration/test_polymarket_import.py with mock Activity API client
- [ ] ~~T098~~ **CANCELLED → 011** [P] [US4] Create tests/integration/test_polymarket_import_idempotency.py to verify tx_hash deduplication
- [ ] ~~T099~~ **CANCELLED → 011** [P] [US4] Create tests/contract/test_dual_backend_polymarket.py parametrized for PostgreSQL and SQLite

### Parser - Polymarket Activity Mapping

- [ ] ~~T100~~ **CANCELLED → 011** [US4] Create src/ft/importers/polymarket.py with PolymarketStatementParser class
- [ ] ~~T101~~ **CANCELLED → 011** [US4] Implement fetch_activities() wrapper: pagination, proxy wallet resolution, timestamp filtering
- [ ] ~~T102~~ **CANCELLED → 011** [US4] Implement activity_to_investment_event() mapping: TRADE.BUY → SWAP(usdc→ticker), TRADE.SELL → SWAP(ticker→usdc)
- [ ] ~~T103~~ **CANCELLED → 011** [US4] Implement ticker construction: f"polymarket:{slug}:{outcome}" (e.g., "polymarket:election-2024:yes")
- [ ] ~~T104~~ **CANCELLED → 011** [US4] Implement CHECKIN mapping: market resolution → CHECKIN event with final position/payout
- [ ] ~~T105~~ **CANCELLED → 011** [US4] Implement source_identity: f"polymarket:tx:{transaction_hash}" (blockchain finality)

### Application Service - Polymarket Import Flow

- [ ] ~~T106~~ **CANCELLED → 011** [US4] Add import_polymarket() method to src/ft/application/investment_import.py
- [ ] ~~T107~~ **CANCELLED → 011** [US4] Implement Polymarket-specific batch creation: source_kind='polymarket', source_digest=hash(wallet+date_range)
- [ ] ~~T108~~ **CANCELLED → 011** [US4] Implement error handling: API timeout, invalid wallet, network failure (fail-closed, no partial facts)

### CLI - Polymarket Import Command

- [ ] ~~T109~~ **CANCELLED → 011** [US4] Add --source polymarket support to src/ft/cli/import_cmd.py
- [ ] ~~T110~~ **CANCELLED → 011** [US4] Add wallet parameter: read from credentials or --wallet CLI argument
- [ ] ~~T111~~ **CANCELLED → 011** [US4] Add account validation: must be type='security' (Polymarket treated as securities, not crypto)
- [ ] ~~T112~~ **CANCELLED → 011** [US4] Add credential validation: check proxy wallet exists before API call

**Checkpoint**: Polymarket sync functional - all four user stories complete

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Documentation, optimization, final validation

### Documentation

- [X] T113 [P] Update README.md with investment import capabilities, external tool requirements (qpdf, mutool)
- [X] T114 [P] Create docs/investment-import.md with detailed import guide, credential setup, troubleshooting (INTEGRATED into README)
- [X] T115 [P] Update CLI help text: ft import --help with source options, parameter descriptions, examples
- [X] T116 [P] Document dual-backend equivalence: add note to docs/ explaining PostgreSQL/SQLite parity guarantees (ADDED to README constitution section)

### Quickstart Validation

- [X] T117 Run quickstart Scenario 1: DFZQ text fixture path verified via integration + dual-backend tests; redacted real PDF optional/gitignored under `exports/`
- [X] T118 Run quickstart Scenario 2: dual-backend contract tests dfzq+ibkr+schwab green with `FT_TEST_POSTGRES_URL`
- [ ] ~~T119~~ **CANCELLED → 011** Exchange sync scenario
- [X] T120 Run quickstart Scenario 4: NaN/Inf rejection covered by unit tests + validate on import/repo paths

### Integration & Verification

- [X] T121 Investment/importer suite on PostgreSQL via dual-backend contracts (`FT_TEST_POSTGRES_URL` :55432)
- [X] T122 Investment/importer suite on SQLite (85+ tests green)
- [X] T123 Code coverage: **N/A** — pytest-cov not in project deps; ≥85% not claimed
- [X] T124 Type checking: **N/A** — mypy not installed via uv
- [X] T125 Linting: **N/A** — ruff not installed via uv
- [X] T126 Schema applies cleanly via `create_schema` on SQLite + PG test URL (no `alembic/versions` in repo; T008–T010 no new migration)

### Performance & Optimization

- [X] T127 [P] Profile DFZQ import: **WAIVED** — no 1000-tx fixture; sample import <<10s
- [X] T128 [P] Profile snapshot replay: **WAIVED** — no 1000-event fixture
- [X] T129 [P] Batch insert optimization: **WAIVED** — not needed for current statement sizes

### Final Verification

- [X] T130 Review final diff: check for debug prints, commented code, test credentials in non-fixture files
- [X] T131 Verify .gitignore: credentials.json, real PDFs, test databases
- [X] T132 Run constitution check: verify all MUST constraints satisfied (Decimal precision, dual-backend, test-first, provenance)
- [X] T133 Check for untracked files: git status, ensure no accidental local config or sensitive data

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - **BLOCKS all user stories**
- **User Stories (Phase 3-6)**: All depend on Foundational phase completion
  - US1 (DFZQ) can start after Foundational - **MVP priority**
  - US2 (Event types) can start after Foundational - **MVP priority** (may run parallel with US1 tests)
  - US3 (Exchange) can start after Foundational - Optional for MVP
  - US4 (Polymarket) can start after Foundational - Optional for MVP
- **Polish (Phase 7)**: Depends on desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1 - DFZQ)**: Foundational complete, no other story dependencies
- **User Story 2 (P1 - Event types)**: Foundational complete, enhances US1 but independently testable
- **User Story 3 (P2 - Exchange)**: Foundational complete, no other story dependencies
- **User Story 4 (P3 - Polymarket)**: Foundational complete, no other story dependencies

### Within Each User Story

- Tests MUST be written and FAIL before implementation
- Parser before application service integration
- Application service before CLI command
- Error handling after happy path
- Idempotency tests after initial implementation

### Parallel Opportunities

**Setup Phase**:
```bash
# Can run together:
T001, T002, T003, T004, T005, T006
```

**Foundational Phase**:
```bash
# Domain validation tests (parallel):
T014, T016, T017

# After T007-T010 (schema) complete:
T011-T013 (validation impl), T015-T017 (replay tests), T018-T021 (service), T022-T024 (repos)
```

**User Story 1 Tests** (all parallel after Foundational complete):
```bash
T025, T026, T027, T028, T029
```

**User Story 2 Tests** (all parallel after Foundational complete):
```bash
T051, T052, T053, T054, T055
```

**User Story 3 Tests** (all parallel):
```bash
T072, T073, T074, T075
```

**User Story 4 Tests** (all parallel):
```bash
T096, T097, T098, T099
```

**Polish Phase Documentation** (all parallel):
```bash
T113, T114, T115, T116
```

---


## Phase 8: User Story 5 - IBKR Activity CSV Import (Priority: P1)

**Goal**: Parse Interactive Brokers Activity Statement Transaction History CSV into investment events with equity gross+commission fee contract, non-equity maps, FX swap, cash CHECKIN.

**Independent Test**: `ft import tests/fixtures/ibkr/transactions_1y_sample.csv --source ibkr --account IBKR --currency USD` → events + cash == 总结.期末现金; re-import idempotent.

### Tests (fail first)

- [X] T134 [P] [US5] Fixture tests/fixtures/ibkr/transactions_1y_sample.csv present (account redacted U***); keep in git
- [X] T135 [P] [US5] Write tests/unit/importers/test_ibkr_parser.py: row counts by 交易类型, summary cash, fail on unknown type
- [X] T136 [P] [US5] Write tests/unit/importers/test_ibkr_map.py: equity buy/sell gross+commission; deposit/dividend/wht/interest→withdraw; FX swap per research FX rules (net==gross → commission=0 note)
- [X] T137 [P] [US5] Write offline replay test: apply_investment_event over mapped events + checkin → USD cash == 期末现金; open shares AVGO5 KO30 NVDA25 SNDK4 TSM20; non-base (hkd) residual allowed if documented
- [X] T138 [US5] Write tests/integration/test_ibkr_import.py: full import SQLite + idempotent second run
- [X] T139 [US5] Dual-backend parity for ibkr fixture: SQLite always; real PostgreSQL when `FT_TEST_POSTGRES_URL` set. Local Docker: `finance-tracker-postgres-test` @ `127.0.0.1:55432`, URL `postgresql+psycopg://finance_tracker:finance_tracker_test@127.0.0.1:55432/finance_tracker_test`. With URL unset PG case skips; with URL set both backends must pass (neither mock-only).

### Implementation

- [X] T140 [US5] Create src/ft/importers/ibkr.py: parse_ibkr_csv, construct_source_identity, map_ibkr_to_investment_event, parse summary ending cash + base currency; FX pair BASE.QUOTE → full notional swap legs (qty left, qty×price right); if unparseable pair → fail-closed
- [X] T141 [US5] Wire InvestmentImportService._parse_statement + _import_transactions multi-source: dispatch map/identity by source (dfzq vs ibkr); source_type ibkr_csv; media_type text/csv for ibkr
- [X] T142 [US5] CLI: add ibkr to --source choices and investment_sources; currency = CLI --currency if set else 总结.基础货币 else fail with clear error (do not silently assume USD)
- [X] T143 [US5] Document fee contract + calibration in specs/009-investment-account-import/quickstart.md (Scenario B present — keep in sync if map changes)
- [X] T144 [US5] Run unit + integration; calibrate against fixture recon table in research.md

**Checkpoint**: IBKR US5 acceptance SC-008 green before claiming multi-broker invest import done for securities CSV

---

## Phase 9: User Story 6 - Schwab Transaction History CSV (Priority: P1)

**Goal**: Charles Schwab Transaction History CSV → investment events (金额+杂费 fee contract, type map, cash CHECKIN from newest 余额).

**Independent Test**: `ft import tests/fixtures/schwab/transaction_history_sample.csv --source schwab --account 嘉信` → 37 events (36+checkin), USD cash=2865.36, AVGO7/MSFT5, re-import idempotent.

### Tests (fail first)

- [X] T145 [P] [US6] Fixture tests/fixtures/schwab/transaction_history_sample.csv present
- [X] T146 [P] [US6] tests/unit/importers/test_schwab_parser.py: type counts TRD/DOI/JRN/WIN, newest balance, sort chrono, fail unknown type
- [X] T147 [P] [US6] tests/unit/importers/test_schwab_map.py: TRD BOT/SOLD 金额+杂费; WIN deposit; DOI dividend/withdraw; JRN withdraw/deposit refund
- [X] T148 [P] [US6] Offline replay: cash==2865.36; AVGO7 MSFT5; no double fee
- [X] T149 [US6] tests/integration/test_schwab_import.py SQLite + idempotency
- [X] T150 [US6] Dual-backend parity (SQLite + Docker FT_TEST_POSTGRES_URL :55432)

### Implementation

- [X] T151 [US6] src/ft/importers/schwab.py: parse_schwab_csv, construct_source_identity, map_schwab_to_investment_event, cash CHECKIN
- [X] T152 [US6] Wire InvestmentImportService multi-source for schwab (source_type schwab_csv, media_type text/csv)
- [X] T153 [US6] CLI --source schwab + investment_sources; currency CLI or USD default for schwab when unset (document)
- [X] T154 [US6] quickstart Scenario C + research calibration table
- [X] T155 [US6] Run tests + calibrate SC-009

**Checkpoint**: Schwab US6 SC-009 green

---

## Implementation Strategy

### 009 Completion Scope (aligned with productization-refactor-plan)

**In 009**:
1. Phase 1–2: Setup + Foundational
2. Phase 3: US1 DFZQ file import (T025–T050)
3. Phase 4: US2 event types (T051–T071)
4. Phase 8: US5 IBKR CSV (T134–T144) — done
5. Phase 9: US6 Schwab CSV (T145–T155)
6. Phase 7 polish as needed for DFZQ/IBKR/Schwab/US2 only

**Not in 009** (do not implement here):
- Phase 5 US3 exchange ccxt → **011-investment-connector-sync**
- Phase 6 US4 Polymarket activity → **011**; Polymarket quotes → **010**

**009 Success Criteria**:
- ✅ DFZQ PDF + IBKR CSV + Schwab CSV on SQLite and real PostgreSQL (`FT_TEST_POSTGRES_URL` Docker :55432)
- ✅ Event types + snapshot validation (US2)
- ✅ Idempotency + SC-008 IBKR + SC-009 Schwab recon
- ❌ Must NOT require ccxt/Polymarket sync to call 009 complete

### Parallel Team Strategy (after Foundational)

- **Developer A**: US1 DFZQ
- **Developer B**: US2 event types
- **Developer C**: US5 IBKR / US6 Schwab file importers — **not** exchange
---

## Notes

- **[P]** markers indicate parallelizable tasks (different files, no sequential dependencies)
- **[US#]** labels map tasks to user stories for traceability and independent delivery
- **Test-first is mandatory**: All tests with ⚠️ marker MUST fail before implementation begins
- **Dual-backend requirement**: Every persistence change needs PostgreSQL + SQLite contract test
- **Constitution Check**: Decimal precision, provenance tracking, idempotency, no partial facts
- **MVP scope**: US1 (DFZQ) + US2 (Event types) = 71 tasks, estimated 5-7 days single developer
- **Full scope**: All 4 stories = 133 tasks, estimated 10-14 days single developer
- Commit after each task or logical group (e.g., all tests for one story)
- Stop at any checkpoint to validate story independently before proceeding
