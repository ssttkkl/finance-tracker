# Tasks: 投资连接器同步

**Input**: Design documents from `specs/018-investment-connector-sync/`

**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/cli-sync.md, quickstart.md

**Tests**: Tests are MANDATORY for executable behavior, financial logic, data,
migration, compatibility, and interface changes. Write failing tests before the
corresponding implementation. Persistence changes require the same contract matrix
against SQLite and real PostgreSQL; neither backend may be represented only by mocks.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- Source: `src/ft/` at repository root
- Tests: `tests/` at repository root
- Migrations: `migrations/versions/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 创建目录结构、fixture 文件和领域类型定义

- [x] T001 Create connector adapters directory structure: `src/ft/adapters/connectors/__init__.py`
- [x] T002 [P] Create test fixture `tests/fixtures/ccxt_trades.json` with mock Binance BUY/SELL/crypto-to-crypto trades (含 fee 各种情况)
- [x] T003 [P] Create test fixture `tests/fixtures/polymarket_activities.json` with mock TRADE/non-TRADE activities (含缺字段边界)
- [x] T004 [P] Define `ConnectorPort` Protocol, `ConnectorResult` dataclass, `ConnectorError`/`ConnectorAuthError`/`ConnectorDataError` exceptions in `src/ft/domain/connector_port.py`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 凭据加载、SyncCursor 持久化、SyncService 编排和 CLI 骨架——所有 user story 的共享基础

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Tests for Foundational ⚠️

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [x] T005 [P] Unit test for CredentialProvider: load/missing/malformed/permissions in `tests/unit/test_credentials.py`
- [x] T006 [P] Extend SyncService unit tests with global fail-closed coverage: pagination/mapping failure leaves no events, snapshot, or cursor; preserve happy path, chunk splitting, and idempotent skip in `tests/unit/test_sync_service.py`
- [x] T007 [P] Integration test for `sync_cursors` table CRUD + UPSERT on SQLite in `tests/integration/test_sync_cursor_sqlite.py`
- [x] T008 [P] Integration test for `sync_cursors` table CRUD + UPSERT on real PostgreSQL in `tests/integration/test_sync_cursor_postgres.py`

### Implementation for Foundational

- [x] T009 Implement `CredentialProvider` in `src/ft/credentials.py`: load YAML, validate fields, chmod 600, gitignore, no secret leaking
- [x] T010 [P] Add `SyncCursorModel` to `src/ft/adapters/relational/models.py`: table `sync_cursors` with `(workspace_id, account_id, source_type)` unique constraint
- [x] T011 Create Alembic migration `migrations/versions/20260726_10_sync_cursors.py`: dual-dialect (PG/SQLite), bump `SCHEMA_REVISION`
- [x] T012 Add cursor read/write/upsert methods to `ImportRepository` protocol in `src/ft/repositories/protocols.py` and relational adapter in `src/ft/adapters/relational/imports.py`
- [x] T013 Implement `SyncService` in `src/ft/application/sync_service.py`: load credentials → validate account → read cursor → call connector → process chunks in one atomic import transaction (reuse `_import_transactions` pattern) → upsert cursor in that transaction → report
- [x] T014 Add `ft sync` subcommand skeleton in `src/ft/cli.py`: argparse `--source`/`--account`/`--full`/`--batch-size`, wire to `SyncService`
- [x] T015 Update `SCHEMA_REVISION` in `src/ft/adapters/relational/runtime.py` to match new migration head

**Checkpoint**: Foundation ready — connector adapters can now be implemented and tested independently

---

## Phase 3: User Story 1 - 同步加密交易所交易历史 (Priority: P1) 🎯 MVP

**Goal**: 通过 ccxt 库全量拉取交易所私有交易历史，映射为 swap 投资事件，fail-closed + 幂等

**Independent Test**: 用 mock ccxt client 模拟 Binance `fetch_my_trades` 返回，执行 `ft sync --source binance --account 币安`，验证事件映射、幂等、快照一致

### Tests for User Story 1 (MANDATORY) ⚠️

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [x] T016 [P] [US1] Unit test for ccxt trade → investment event mapping (BUY/SELL/crypto-to-crypto/fee variants) in `tests/unit/test_ccxt_connector.py`
- [x] T017 [P] [US1] Unit test for ccxt `fetch_my_trades` pagination (multi-page, empty, single-page) in `tests/unit/test_ccxt_connector.py`
- [x] T018 [P] [US1] Unit test for ccxt retry logic (429/timeout/auth error) in `tests/unit/test_ccxt_connector.py`
- [x] T019 [P] [US1] Unit test for ccxt data validation (missing id, bad symbol, bad side, non-finite amount) → `ConnectorDataError` in `tests/unit/test_ccxt_connector.py`
- [x] T020 [P] [US1] Integration test for exchange sync end-to-end on SQLite (mock ccxt, real DB) in `tests/integration/test_sync_exchange_sqlite.py`
- [x] T021 [P] [US1] Integration test for exchange sync end-to-end on real PostgreSQL in `tests/integration/test_sync_exchange_postgres.py`

### Implementation for User Story 1

- [x] T022 [US1] Implement `CcxtExchangeConnector` in `src/ft/adapters/connectors/ccxt_exchange.py`: ccxt client init, `fetch_trades` with `since`-based pagination, trade validation, mapping to investment event dicts, retry with exponential backoff
- [x] T023 [US1] Wire `binance`/`kraken`/`okx` source names to `CcxtExchangeConnector` in `SyncService` and CLI (`src/ft/application/sync_service.py`, `src/ft/cli.py`)
- [x] T024 [US1] Run US1 tests and verify: mapping correctness, global fail-closed on pagination/bad data, idempotent re-sync, SQLite/PG equivalence

**Checkpoint**: Exchange sync fully functional — `ft sync --source binance --account 币安` works end-to-end

---

## Phase 4: User Story 2 - 同步 Polymarket 交易活动 (Priority: P1)

**Goal**: 通过公开 Activity API 拉取 Polymarket TRADE 活动，映射为 swap (pm:<slug>:<outcome> ↔ usd)，fail-closed + 幂等

**Independent Test**: 用 mock Activity API 模拟返回，执行 `ft sync --source polymarket --account Polymarket`，验证 swap 事件映射、非 TRADE 跳过、幂等

### Tests for User Story 2 (MANDATORY) ⚠️

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [x] T025 [P] [US2] Unit test for Polymarket activity → investment event mapping (BUY/SELL/non-TRADE skip) in `tests/unit/test_polymarket_connector.py`
- [x] T026 [P] [US2] Unit test for Polymarket API pagination (multi-page, empty) in `tests/unit/test_polymarket_connector.py`
- [x] T027 [P] [US2] Unit test for Polymarket data validation (missing slug/outcome/txHash) → `ConnectorDataError` in `tests/unit/test_polymarket_connector.py`
- [x] T028 [P] [US2] Unit test for proxy wallet resolution (login → proxy address) in `tests/unit/test_polymarket_connector.py`
- [x] T029 [P] [US2] Integration test for Polymarket sync end-to-end on SQLite in `tests/integration/test_sync_polymarket_sqlite.py`
- [x] T030 [P] [US2] Integration test for Polymarket sync end-to-end on real PostgreSQL in `tests/integration/test_sync_polymarket_postgres.py`

### Implementation for User Story 2

- [x] T031 [US2] Implement `PolymarketConnector` in `src/ft/adapters/connectors/polymarket.py`: Activity API fetching, offset pagination, proxy wallet resolution, TRADE filtering, activity → investment event mapping, retry
- [x] T032 [US2] Wire `polymarket` source name to `PolymarketConnector` in `SyncService` and CLI
- [x] T033 [US2] Run US2 tests and verify: mapping correctness, non-TRADE skip, global fail-closed, idempotent, SQLite/PG equivalence

**Checkpoint**: Polymarket sync fully functional — `ft sync --source polymarket --account Polymarket` works

---

## Phase 5: User Story 3 - 凭据配置与安全 (Priority: P2)

**Goal**: 凭据管理边界场景完备——缺失/错误/权限问题都给出可操作错误，不泄漏密钥

**Independent Test**: 在无凭据、缺字段、格式错误的情况下执行 sync 命令，验证错误信息清晰且安全

### Tests for User Story 3 (MANDATORY) ⚠️

- [x] T034 [P] [US3] Unit test for credential edge cases: empty file, invalid YAML, extra fields ignored, nested error messages never contain key values in `tests/unit/test_credentials.py`
- [x] T035 [P] [US3] Integration test for CLI error output: no credentials → example config; wrong type → type hint; missing field → field name in `tests/integration/test_sync_cli_errors.py`

### Implementation for User Story 3

- [x] T036 [US3] Harden credential error messages in `src/ft/credentials.py`: ensure example configs are provider-specific, error paths never log/display `api_key`/`api_secret` values
- [x] T037 [US3] Add Polymarket-specific credential validation (wallet address format `0x[a-fA-F0-9]{40}`) in `src/ft/credentials.py`
- [x] T038 [US3] Run US3 tests and verify all credential edge cases pass

**Checkpoint**: Credential management robust — all error messages actionable and secure

---

## Phase 6: User Story 4 - 增量游标优化 (Priority: P3)

**Goal**: 系统记住上次同步位置，下次只拉取新交易；`--full` 强制全量

**Independent Test**: 首次全量同步后记录游标；添加新 mock 交易后再次同步，验证只拉取游标之后的交易

### Tests for User Story 4 (MANDATORY) ⚠️

- [x] T039 [P] [US4] Unit test for cursor read/write lifecycle in SyncService: first sync → cursor saved; incremental → cursor used; `--full` → cursor ignored in `tests/unit/test_sync_service.py`
- [x] T040 [P] [US4] Integration test for cursor persistence + incremental sync on SQLite in `tests/integration/test_sync_cursor_incremental_sqlite.py`
- [x] T041 [P] [US4] Integration test for cursor persistence + incremental sync on real PostgreSQL in `tests/integration/test_sync_cursor_incremental_postgres.py`

### Implementation for User Story 4

- [x] T042 [US4] Implement cursor integration in `SyncService`: read cursor before fetch, pass `since` to connector, upsert cursor after successful commit in `src/ft/application/sync_service.py`
- [x] T043 [US4] Implement `--full` flag handling in CLI and SyncService: skip cursor read when `--full` is passed
- [x] T044 [US4] Handle stale cursor (API returns error for old cursor value) → fallback to full fetch with warning in `src/ft/application/sync_service.py`
- [x] T045 [US4] Run US4 tests and verify: cursor saved, incremental works, `--full` works, stale cursor fallback works

**Checkpoint**: Incremental sync operational — daily syncs pull only new trades

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: 文档更新、双 DB 等价最终验证、代码清理

- [x] T046 [P] Update CLI help text and README for `ft sync` command
- [x] T047 [P] Run full test suite (`uv run pytest tests/ -v`) and verify no regressions (2026-07-28: `FT_TEST_POSTGRES_URL=postgresql+psycopg://finance_tracker:finance_tracker_test@127.0.0.1:55432/finance_tracker_test` + `FT_REQUIRE_TEST_POSTGRES=1` → **975 passed, 9 skipped, 0 failed** in 333.71s. Fixed four pre-existing regressions uncovered by the full matrix: (1) investment `acct add --currency` now seeds `metadata.base_currencies` so portfolio cash legs mark `is_cash`; (2) `tests/test_market_data.py` retargeted to `PredictionMarketQuoteProvider` after 017 removed `_fetch_polymarket`; (3) IBKR offline replay no longer expects a residual `hkd` leg after FX net-P&L-only mapping; (4) wealth publish orphan fixture uses integer `owner_account_id=1` post-016.)
- [x] T048 Run quickstart.md validation scenarios end-to-end (exchange + Polymarket + errors + dual DB)
- [x] T049 Final diff review: no credentials in logs/test output, no unused imports, consistent error messages

---

## Phase 8: Polymarket 非交易活动补导

**Purpose**: 按已确认的账务语义导入历史 `REDEEM` 与 `YIELD` 活动，并继续跳过未定义类型。

- [x] T050 [P] [US2] Extend `tests/unit/test_polymarket_connector.py` with failing `REDEEM` → swap、`YIELD` → USD dividend、unknown-type skip and missing transactionHash fail-closed cases.
- [x] T051 [P] [US2] Extend SQLite and PostgreSQL Polymarket sync integration tests for `REDEEM` / `YIELD` import and idempotent re-sync in `tests/integration/test_sync_polymarket_sqlite.py` and `tests/integration/test_sync_polymarket_postgres.py`.
- [x] T052 [US2] Update `src/ft/adapters/connectors/polymarket.py` to map `REDEEM` and `YIELD` exactly as specified; preserve transactionHash provenance and fail closed on missing required fields.
- [x] T053 [US2] Execute the SQLite/PostgreSQL Polymarket matrix, then run `ft sync --source polymarket --account Polymarket --full` against the user-approved `~/.ft` SQLite ledger to idempotently backfill the new activity kinds.

---

## Phase 9: 交易所全量 Ledger 活动与严格手续费 (US1)

**Purpose**: 交易所以 trade + ledger 覆盖全部可识别活动；严格拒绝异常手续费和未知 ledger 类型。

- [x] T054 [P] [US1] Add failing unit tests for ccxt ledger pagination (including repeated/no-progress page failure), deposit/withdraw/reward/staking/transfer mapping, strict unknown-type and malformed-fee failures in `tests/unit/test_ccxt_connector.py`.
- [x] T055 [P] [US1] Add SQLite and real PostgreSQL integration tests for combined trade + ledger import, fee child events, idempotent re-sync, and whole-sync rollback in `tests/integration/test_sync_exchange_sqlite.py` and `tests/integration/test_sync_exchange_postgres.py`.
- [x] T056 [US1] Extend `src/ft/adapters/connectors/ccxt_exchange.py` to fetch and page ledger entries with trades, map all specified activity types, retain source payloads, and fail closed for invalid fee or unsupported types.
- [x] T057 [US1] Extend `src/ft/domain/investment_projection.py` to accept `transfer` as a no-position-change auditable event, with focused domain tests in `tests/unit/domain/test_investment_event_replay.py`.
- [x] T058 [US1] Run the affected SQLite/PostgreSQL matrix and use the user-approved Kraken credentials to perform a `--full` import into `~/.ft/finance-tracker.db`; report activity counts without exposing credentials or raw private data.
- [x] T059 [US1] Add failing aliases-and-atomicity tests: CCXT trade/ledger ticker aliases must match the file-import normalizer, and a mapped ledger event that fails during replay must roll back events, snapshot, and cursor on SQLite and real PostgreSQL in `tests/unit/test_ccxt_connector.py`, `tests/integration/test_sync_exchange_sqlite.py`, and `tests/integration/test_sync_exchange_postgres.py`.
- [x] T060 [US1] Add the minimal canonical `normalize_crypto_ticker` helper in `src/ft/importers/ticker_normalize.py` using `schema.CRYPTO_IDS` plus explicit exchange aliases, call it from `src/ft/adapters/connectors/ccxt_exchange.py`, then complete the combined raw trade+ledger integration/rollback matrix and update the verification evidence in `specs/018-investment-connector-sync/tasks.md`.

---

## Phase 10: User Story 5 - 有界持仓 CLI 渲染 (Priority: P1)

**Purpose**: 账本已有持仓时，行情供应商的慢响应、失败或诊断输出不得让 `ft stock list` 长时间空白或遗漏持仓。

- [x] T061 [P] [US5] Add a failing unit test for a multi-position portfolio with an actually blocking/raising quote provider: a monotonic query deadline returns every nonzero position within budget and failed/expired quotes are partial/N/A in `tests/unit/application/test_portfolio_valuation.py`.
- [x] T062 [P] [US5] Add a failing CLI rendering test that asserts `ft stock list` emits its own table without provider diagnostic leakage in `tests/test_application_investment.py`.
- [x] T063 [P] [US5] Add SQLite and real PostgreSQL integration coverage for an identical nonzero holding set and partial quote statuses in `tests/integration/test_portfolio_query_sqlite.py` and `tests/integration/test_portfolio_query_postgres.py`.
- [x] T064 [US5] Implement the smallest monotonic query deadline with a daemon bounded quote worker/failure downgrade in `src/ft/application/investment.py`, plus provider timeout and yfinance logger containment in `src/ft/adapters/market_data.py`; preserve all nonzero DTO positions and avoid ledger writes.
- [x] T065 [US5] Run the unit, CLI, SQLite and real PostgreSQL portfolio matrix; manually verify `FT_DATABASE_URL=sqlite+pysqlite:////Users/huangwenlong/.ft/finance-tracker.db FT_WORKSPACE_ID=default uv run ft stock list` outputs the imported Kraken and Polymarket holdings within five seconds (2026-07-27: 5-test matrix passed; real SQLite CLI completed in 4.67s with all Kraken and 16 Polymarket positions rendered, and no provider diagnostics).

---

## Phase 11: User Story 6 - 校准 Polymarket 当前现金 (Priority: P1)

**Purpose**: 在 Activity API 同步后读取 funder 当前 pUSD `balanceOf` 并写 USD checkin；不扫描区块，也不导入历史出入金。

- [x] T066 [P] [US6] Add failing unit tests for current pUSD `balanceOf`: exact six-decimal USD `checkin`, `checkin:<block>` identity/payload, no `eth_getLogs` call, and RPC failure atomicity in `tests/unit/test_polymarket_connector.py`.
- [x] T067 [P] [US6] Add SQLite and real PostgreSQL end-to-end tests proving Activity + pUSD checkin commit atomically, replace USD only, preserve pm positions, and same-block re-run idempotency in `tests/integration/test_sync_polymarket_sqlite.py` and `tests/integration/test_sync_polymarket_postgres.py`.
- [x] T068 [US6] Implement one current-block/timestamp RPC read plus pUSD `balanceOf` → USD `checkin` in `src/ft/adapters/connectors/polymarket.py`; remove all `eth_getLogs`, Transfer mapper, history window and compound-cursor behavior.
- [x] T069 [US6] Run the focused unit and SQLite/real-PostgreSQL matrix, then synchronize the user-authorized `.ft/finance-tracker.db`; verify final USD equals pUSD checkin and no market position changed in `specs/018-investment-connector-sync/tasks.md` (2026-07-27: `29 passed`; real SQLite sync added `checkin:90960575` at USD `260.398415`, retained all 16 pm positions, and advanced the Activity cursor to `1785121333`).

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 — BLOCKS all user stories
- **US1 Exchange Sync (Phase 3)**: Depends on Phase 2
- **US2 Polymarket Sync (Phase 4)**: Depends on Phase 2 — **can run in parallel with US1**
- **US3 Credential Security (Phase 5)**: Depends on Phase 2 (credential module exists)
- **US4 Incremental Cursor (Phase 6)**: Depends on Phase 2 + at least one of US1/US2 (cursor needs a working connector to test)
- **Polish (Phase 7)**: Depends on all desired user stories being complete
- **US6 pUSD Checkin (Phase 11)**: Depends on completed US2; reuses the Polymarket connector and atomic SyncService.

### User Story Dependencies

- **US1 (P1)**: Can start after Phase 2 — No dependencies on other stories
- **US2 (P1)**: Can start after Phase 2 — No dependencies on other stories (can parallel with US1)
- **US3 (P2)**: Can start after Phase 2 — Independent edge case hardening
- **US4 (P3)**: Depends on Phase 2 + at least US1 or US2 for end-to-end cursor testing
- **US6 (P1)**: Depends on US2 — Activity and one current pUSD observation must share a fail-closed connector result and UoW.

### Within Each User Story

- Tests MUST be written and FAIL before implementation
- Domain types / adapters before service integration
- Service integration before CLI wiring
- Story complete before checkpoint verification

### Parallel Opportunities

- T002, T003, T004 can run in parallel (different files, no dependencies)
- T005, T006, T007, T008 can run in parallel (different test files)
- T009, T010 can run in parallel (different source files)
- US1 (T016–T024) and US2 (T025–T033) can run in parallel after Phase 2
- All tests within a story marked [P] can run in parallel

---

## Parallel Example: User Story 1

```bash
# Launch all unit tests for US1 together:
Task: "T016 Unit test ccxt trade mapping in tests/unit/test_ccxt_connector.py"
Task: "T017 Unit test ccxt pagination in tests/unit/test_ccxt_connector.py"
Task: "T018 Unit test ccxt retry in tests/unit/test_ccxt_connector.py"
Task: "T019 Unit test ccxt data validation in tests/unit/test_ccxt_connector.py"

# Launch integration tests for US1 together:
Task: "T020 Integration test exchange sync SQLite in tests/integration/test_sync_exchange_sqlite.py"
Task: "T021 Integration test exchange sync PostgreSQL in tests/integration/test_sync_exchange_postgres.py"

# Then implement:
Task: "T022 Implement CcxtExchangeConnector in src/ft/adapters/connectors/ccxt_exchange.py"
Task: "T023 Wire source names in SyncService and CLI"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001–T004)
2. Complete Phase 2: Foundational (T005–T015)
3. Complete Phase 3: US1 Exchange Sync (T016–T024)
4. **STOP and VALIDATE**: Test `ft sync --source binance --account 币安` independently
5. Exchange sync usable — deploy/demo if ready

### Incremental Delivery

1. Setup + Foundational → Foundation ready
2. Add US1 Exchange Sync → Test independently → **MVP!**
3. Add US2 Polymarket Sync → Test independently → Two connectors available
4. Add US3 Credential Security → Harden edge cases → Production-quality errors
5. Add US4 Incremental Cursor → Optimize daily usage → Feature complete
6. Polish → Full validation → Ready for review

### Parallel Strategy

With capacity for 2 parallel streams after Phase 2:

1. Complete Setup + Foundational together
2. Stream A: US1 Exchange Sync | Stream B: US2 Polymarket Sync
3. US3 + US4 sequentially after both connectors ready
4. Polish

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story is independently completable and testable
- Verify tests fail before implementing
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- All persistence tests MUST run on both SQLite and real PostgreSQL
