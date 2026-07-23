# Tasks: Investment Account Import

**Input**: Design documents from `/specs/009-investment-account-import/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Tests are MANDATORY for executable behavior, financial logic, data, migration, compatibility, and interface changes. Write failing tests before the corresponding implementation. Persistence changes require the same contract matrix against SQLite and real PostgreSQL; neither backend may be represented only by mocks.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `- [ ] T### [P?] [US#?] Description with file path`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[US#]**: User story label (US1=DFZQ import P1, US2=Event types P1, US3=Exchange P2, US4=Polymarket P3)
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

- [ ] T018 Create src/ft/application/investment_import.py with InvestmentImportService class
- [ ] T019 Implement import_statement() method: batch → raw_records → investment_events → snapshot update (atomic transaction)
- [ ] T020 Integrate validate_investment_snapshot() call before transaction commit
- [ ] T021 Add idempotency: check existing batch by source_digest, return existing batch_id if status='completed'

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
- [X] T033 [US1] Add commission mapping: fee → commission field, handle stamp_tax + transfer_fee aggregation
- [X] T034 [US1] Add CHECKIN event from final balance line in DFZQ statement

### Application Service - DFZQ Import Flow

- [ ] T035 [US1] Implement DFZQ-specific import flow in src/ft/application/investment_import.py: parse_dfzq() wrapper
- [ ] T036 [US1] Integrate with ImportBatch creation: source_kind='dfzq', source_digest=SHA256(file_content), source_ref=filename
- [ ] T037 [US1] Implement RawFile creation: save file metadata (path, digest, size, media_type='application/pdf')
- [ ] T038 [US1] Implement RawRecord batch creation: iterate parsed rows, construct source_identity, save payload
- [ ] T039 [US1] Implement InvestmentEvent creation: map each raw_record to event via apply_investment_command() or direct creation
- [ ] T040 [US1] Implement snapshot update: replay events via apply_investment_event(), call validate_investment_snapshot(), commit

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

- [ ] T051 [P] [US2] Extend tests/unit/domain/test_investment_event_replay.py with comprehensive SWAP scenarios (buy, sell, crypto swap)
- [ ] T052 [P] [US2] Add DEPOSIT/WITHDRAW test cases to test_investment_event_replay.py (cash in/out, position unchanged)
- [ ] T053 [P] [US2] Add DIVIDEND test cases to test_investment_event_replay.py (cash increase, position unchanged, from_ticker audit)
- [ ] T054 [P] [US2] Add CHECKIN test cases to test_investment_event_replay.py (position replacement, cash replacement)
- [ ] T055 [P] [US2] Create tests/integration/test_event_types_integration.py with multi-event sequences (buy → dividend → sell)

### Domain Logic - SWAP Event Handling

- [ ] T056 [US2] Verify SWAP buy logic in src/ft/domain/investment_projection.py: from=cash, to=ticker, commission deducted from cash
- [ ] T057 [US2] Verify SWAP sell logic: from=ticker, to=cash, released_cost calculation (proportional cost basis)
- [ ] T058 [US2] Verify SWAP crypto-to-crypto logic: from=ticker1, to=ticker2, released_cost transferred
- [ ] T059 [US2] Verify commission_asset handling: third-party fee (e.g., BNB) deducted from correct position
- [ ] T060 [US2] Add edge case handling: zero shares, zero cost, missing position (create on first event)

### Domain Logic - Other Event Types

- [ ] T061 [P] [US2] Verify DEPOSIT logic in src/ft/domain/investment_projection.py: to_ticker cash increase
- [ ] T062 [P] [US2] Verify WITHDRAW logic: from_ticker cash decrease
- [ ] T063 [P] [US2] Verify DIVIDEND logic: to_ticker cash increase, from_ticker for audit (position unchanged)
- [ ] T064 [P] [US2] Verify CHECKIN logic: position replacement (not delta), used for statement reconciliation

### Snapshot Validation Integration

- [ ] T065 [US2] Add validate_investment_snapshot() call to src/ft/application/investment.py InvestmentService methods (buy, sell, swap, etc.)
- [ ] T066 [US2] Add validation to investment_import.py after event replay (before commit)
- [ ] T067 [US2] Test snapshot validation rejection: inject NaN via corrupted event, verify transaction rollback
- [ ] T068 [US2] Test snapshot validation rejection: inject Infinity via corrupted event, verify error message includes field name

### CLI Command Extensions

- [ ] T069 [P] [US2] Verify src/ft/cli/investment.py buy/sell/swap/deposit/withdraw/dividend/checkin commands work with validation
- [ ] T070 [P] [US2] Add commission_asset parameter to swap command (optional, defaults to from_ticker)
- [ ] T071 [P] [US2] Update CLI help text to explain SWAP single-row model vs BUY/SELL legacy commands

**Checkpoint**: All event types supported with snapshot validation - investment domain model complete

---

## Phase 5: User Story 3 - Import Exchange Trades via API (Priority: P2)

**Goal**: Sync Binance/OKX trades via ccxt API to investment events

**Independent Test**: Configure Binance test credentials, sync trades to both backends, verify idempotency

### Tests for User Story 3 (MANDATORY) ⚠️

- [ ] T072 [P] [US3] Create tests/unit/importers/test_exchange_parser.py with ccxt trade mapping (buy, sell, crypto swap, commission)
- [ ] T073 [P] [US3] Create tests/integration/test_exchange_import.py with mock ccxt client (avoid live API in tests)
- [ ] T074 [P] [US3] Create tests/integration/test_exchange_import_idempotency.py to verify trade ID deduplication
- [ ] T075 [P] [US3] Create tests/contract/test_dual_backend_exchange.py parametrized for PostgreSQL and SQLite

### Parser - Exchange Trade Mapping

- [ ] T076 [US3] Create src/ft/importers/exchange.py with ExchangeStatementParser class
- [ ] T077 [US3] Implement ccxt client initialization: load credentials from env vars or ~/.ft/credentials.json
- [ ] T078 [US3] Implement fetch_trades() wrapper: pagination, rate limiting, since parameter support
- [ ] T079 [US3] Implement trade_to_investment_event() mapping: side=buy → SWAP(quote→base), side=sell → SWAP(base→quote)
- [ ] T080 [US3] Implement commission mapping: fee.cost → commission, fee.currency → commission_asset
- [ ] T081 [US3] Implement source_identity: f"ccxt:{provider}:trade:{trade.id}"

### Credentials Management

- [ ] T082 [US3] Create src/ft/config/credentials.py with load_credentials(provider) function
- [ ] T083 [US3] Implement environment variable loading: FT_EXCHANGE_{PROVIDER}_API_KEY, FT_EXCHANGE_{PROVIDER}_API_SECRET
- [ ] T084 [US3] Implement config file loading: ~/.ft/credentials.json with provider → {api_key, api_secret, password?} mapping
- [ ] T085 [US3] Add security checks: warn if credentials.json not 0600, verify .gitignore includes credentials.json
- [ ] T086 [US3] Add test credentials: TEST_ prefix, separate fixture for integration tests

### Application Service - Exchange Import Flow

- [ ] T087 [US3] Add import_exchange() method to src/ft/application/investment_import.py
- [ ] T088 [US3] Implement exchange-specific batch creation: source_kind='ccxt_{provider}', source_digest=hash(query_params), source_ref=date_range
- [ ] T089 [US3] Implement incremental sync: --since parameter filters trades by timestamp, existing trade IDs skipped
- [ ] T090 [US3] Implement error handling: API timeout, rate limit, invalid credentials, network failure (all fail-closed, no partial facts)

### CLI - Exchange Import Command

- [ ] T091 [US3] Add --source binance support to src/ft/cli/import_cmd.py
- [ ] T092 [US3] Add --source okx support to src/ft/cli/import_cmd.py
- [ ] T093 [US3] Add --since parameter: ISO date (e.g., 2026-01-01) for incremental sync
- [ ] T094 [US3] Add account validation: must be type='crypto', clear error if type='security'
- [ ] T095 [US3] Add credential validation: check provider credentials exist before API call, provide setup instructions on error

**Checkpoint**: Exchange sync functional - can import Binance/OKX trades with incremental sync

---

## Phase 6: User Story 4 - Import Polymarket Prediction Market Activities (Priority: P3)

**Goal**: Sync Polymarket trades and resolutions via Activity API

**Independent Test**: Configure Polymarket wallet, sync activities to both backends, verify idempotency

### Tests for User Story 4 (MANDATORY) ⚠️

- [ ] T096 [P] [US4] Create tests/unit/importers/test_polymarket_parser.py with Activity API response mapping
- [ ] T097 [P] [US4] Create tests/integration/test_polymarket_import.py with mock Activity API client
- [ ] T098 [P] [US4] Create tests/integration/test_polymarket_import_idempotency.py to verify tx_hash deduplication
- [ ] T099 [P] [US4] Create tests/contract/test_dual_backend_polymarket.py parametrized for PostgreSQL and SQLite

### Parser - Polymarket Activity Mapping

- [ ] T100 [US4] Create src/ft/importers/polymarket.py with PolymarketStatementParser class
- [ ] T101 [US4] Implement fetch_activities() wrapper: pagination, proxy wallet resolution, timestamp filtering
- [ ] T102 [US4] Implement activity_to_investment_event() mapping: TRADE.BUY → SWAP(usdc→ticker), TRADE.SELL → SWAP(ticker→usdc)
- [ ] T103 [US4] Implement ticker construction: f"polymarket:{slug}:{outcome}" (e.g., "polymarket:election-2024:yes")
- [ ] T104 [US4] Implement CHECKIN mapping: market resolution → CHECKIN event with final position/payout
- [ ] T105 [US4] Implement source_identity: f"polymarket:tx:{transaction_hash}" (blockchain finality)

### Application Service - Polymarket Import Flow

- [ ] T106 [US4] Add import_polymarket() method to src/ft/application/investment_import.py
- [ ] T107 [US4] Implement Polymarket-specific batch creation: source_kind='polymarket', source_digest=hash(wallet+date_range)
- [ ] T108 [US4] Implement error handling: API timeout, invalid wallet, network failure (fail-closed, no partial facts)

### CLI - Polymarket Import Command

- [ ] T109 [US4] Add --source polymarket support to src/ft/cli/import_cmd.py
- [ ] T110 [US4] Add wallet parameter: read from credentials or --wallet CLI argument
- [ ] T111 [US4] Add account validation: must be type='security' (Polymarket treated as securities, not crypto)
- [ ] T112 [US4] Add credential validation: check proxy wallet exists before API call

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

- [ ] T117 Run quickstart.md Scenario 1 (DFZQ import to PostgreSQL) manually with redacted real PDF
- [ ] T118 Run quickstart.md Scenario 2 (Dual-backend equivalence) with automated contract tests
- [ ] T119 Run quickstart.md Scenario 3 (Exchange sync) with mock Binance API (avoid real API key in CI)
- [ ] T120 Run quickstart.md Scenario 4 (Snapshot validation error) with injected NaN test case

### Integration & Verification

- [ ] T121 Run full test suite on PostgreSQL: pytest tests/ --backend=postgresql -v
- [ ] T122 Run full test suite on SQLite: pytest tests/ --backend=sqlite -v
- [ ] T123 Run code coverage: verify ≥85% for domain/application layers, ≥70% overall
- [ ] T124 Run type checking: mypy src/ft/ --strict
- [ ] T125 Run linting: ruff check src/ tests/
- [ ] T126 Verify all migrations apply cleanly: alembic upgrade head on both backends

### Performance & Optimization

- [ ] T127 [P] Profile DFZQ import with 1000-transaction statement: verify <10s end-to-end
- [ ] T128 [P] Profile snapshot replay with 1000 events: verify <2s
- [ ] T129 [P] Add batch insert optimization if needed (500 records per batch for large statements)

### Final Verification

- [ ] T130 Review final diff: check for debug prints, commented code, test credentials in non-fixture files
- [ ] T131 Verify .gitignore: credentials.json, real PDFs, test databases
- [ ] T132 Run constitution check: verify all MUST constraints satisfied (Decimal precision, dual-backend, test-first, provenance)
- [ ] T133 Check for untracked files: git status, ensure no accidental local config or sensitive data

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

## Implementation Strategy

### MVP First (US1 + US2 Only)

**Recommended for initial delivery**:

1. Complete Phase 1: Setup (T001-T006)
2. Complete Phase 2: Foundational (T007-T024) - **CRITICAL GATE**
3. Complete Phase 3: User Story 1 - DFZQ Import (T025-T050)
4. Complete Phase 4: User Story 2 - Event Types (T051-T071)
5. Complete Phase 7: Polish (T113-T133)
6. **STOP and VALIDATE**: Import real DFZQ statement on both backends, verify dual-backend equivalence
7. Deploy/demo MVP

**MVP Success Criteria**:
- ✅ DFZQ PDF import functional on PostgreSQL and SQLite
- ✅ All event types (SWAP, DEPOSIT, WITHDRAW, DIVIDEND, CHECKIN) working
- ✅ Snapshot validation prevents data corruption
- ✅ Idempotency proven (repeat import returns count=0)
- ✅ Dual-backend contract tests passing

### Incremental Delivery (Add Exchange & Polymarket)

After MVP validated:

1. Add Phase 5: User Story 3 - Exchange (T072-T095)
2. Test independently, deploy
3. Add Phase 6: User Story 4 - Polymarket (T096-T112)
4. Test independently, deploy

### Parallel Team Strategy

With multiple developers (after Foundational complete):

- **Developer A**: User Story 1 (DFZQ) - T025-T050
- **Developer B**: User Story 2 (Event types) - T051-T071 (domain focus)
- **Developer C**: User Story 3 (Exchange) - T072-T095 (once US1 proven)

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
