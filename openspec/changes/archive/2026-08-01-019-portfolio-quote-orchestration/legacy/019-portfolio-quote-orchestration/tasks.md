# Tasks: 持仓行情报价编排

**Input**: `specs/019-portfolio-quote-orchestration/` 中的 plan、spec、research、data-model、contracts 和 quickstart。

**前提**：不新增数据库 schema 或迁移。所有可执行行为必须测试先行；持仓报价契约须在 SQLite 与真实 PostgreSQL 中验证。

## Phase 1: Setup

**Purpose**：固定本 feature 的接口合同和验证入口。

- [X] T001 校验 `specs/019-portfolio-quote-orchestration/{spec.md,plan.md,research.md,data-model.md,contracts/quote-batch-port.md,quickstart.md}` 与术语表一致，且不含未决占位符。证据：2026-07-28 逐项复核，requirements checklist 全部完成，无未决占位符，术语与 `DOMAIN_GLOSSARY.md` 一致。

---

## Phase 2: User Story 1 - 在预算内取得更多持仓行情报价（Priority: P1）

**Goal**：一次持仓查询先收集、规范化并去重所有报价请求，再在共同 4 秒预算内执行，确保慢源不阻塞独立源。

**Independent Test**：对包含重复证券、现金、零数量、慢源和快速源的假快照查询持仓；验证调用次数、结果状态和返回时间。

### Tests for User Story 1 (MANDATORY)

- [X] T002 [US1] 在 `tests/unit/application/test_portfolio_valuation.py` 添加先失败的测试：同一规范化标的跨账户只请求一次，现金和零数量不请求外部报价。证据：`uv run pytest tests/unit/application/test_valuation_service.py tests/unit/application/test_portfolio_valuation.py -q` 先红（重复 `AAPL.US`/`aapl.us` 都被调用），实现后转绿。
- [X] T003 [US1] 在 `tests/unit/application/test_portfolio_valuation.py` 添加先失败的测试：慢源到期仅使对应标的为 `partial`，快速源仍在全局预算内返回可用结果，且未启动工作被取消。证据：同一命令先红（BTC 为 `partial`），实现后转绿；慢源后续证券未启动。
- [X] T004 [US1] 在 `tests/unit/application/test_valuation_service.py` 添加先失败的逐项批量结果测试：同一批次可混合成功、空数据、`unsupported` 和错误，结果按输入标识可检索。证据：同一命令先红（批量 provider 被忽略），实现后转绿。

### Implementation for User Story 1

- [X] T005 [US1] 在 `src/ft/application/valuation.py` 扩展报价 service/port 的批量结果编排，保持单项 `quote` 的 Decimal、freshness 和错误状态合同不变。证据：批量路径按输入标识转换有效、空、`unsupported` 和错误结果；单项既有测试仍通过。
- [X] T006 [US1] 在 `src/ft/application/investment.py` 实现“收集 → 请求键去重 → 按数据源调度 → 回填仓位”的持仓报价流程；使用固定上限 worker、共享单调截止时间、取消未启动工作，并保留 FX 折算路径。证据：US1 单元测试覆盖去重、跨源并行、预算返回及 FX 既有回归。
- [X] T007 [US1] 运行 T002-T004 的测试，确认它们先失败于旧串行实现、在 T005-T006 后转绿，并在 `specs/019-portfolio-quote-orchestration/tasks.md` 更新状态与证据。证据：`uv run pytest tests/unit/application/test_valuation_service.py tests/unit/application/test_portfolio_valuation.py -q`，先红 3 项，后绿 `10 passed in 0.30s`。

**Checkpoint**：持仓查询在固定预算内保留全部非零仓位；重复标的不重复取价；单一慢源不会阻塞其他数据源。

---

## Phase 3: User Story 2 - 数据源批量请求受控且可部分成功（Priority: P2）

**Goal**：为支持的源减少网络往返；不支持原生批量的源严格限制并发，且所有逐项结果保持独立。

**Independent Test**：用可记录 URL、输入和调用次数的假适配器验证证券和加密资产合并读取；预测市场在受限并发下完成部分成功和失败。

### Tests for User Story 2 (MANDATORY)

- [X] T008 [US2] 在 `tests/test_market_data.py` 添加先失败的证券批量测试：多代码只发起一次下载，能映射多代码行情、缺失代码和计价币种。证据：实现前 `AttributeError: raw_quote_many`；实现后批量下载调用数为 1，包含 3 个代码。
- [X] T009 [US2] 在 `tests/test_market_data.py` 添加先失败的加密批量测试：多个已知 `CRYPTO_IDS` 合并为一次请求，缺失项不污染有效项。证据：实现前 `AttributeError: raw_quote_many`；实现后 CoinGecko 合并请求调用数为 1，BTC 有效且 ETH 缺失不受污染。
- [X] T010 [US2] 在 `tests/test_market_data.py` 添加先失败的预测市场受限并发测试：最大同时调用数固定，超时或缺失合约仅返回本项 `partial`。证据：实现前构造器不接受 `max_in_flight`；实现后最大同时调用数不超过 2，缺失合约为逐项空结果。

### Implementation for User Story 2

- [X] T011 [US2] 在 `src/ft/adapters/market_data.py` 实现证券与加密资产的批量报价适配，并把剩余总预算传递给 yfinance 与 JSON 网络超时；保持单项适配兼容。证据：批量 yfinance/JSON 路径接收剩余 `timeout`；原有预测市场单项兼容测试通过。
- [X] T012 [US2] 在 `src/ft/adapters/market_data.py` 为预测市场实现固定上限的受控并发和逐项回退，禁止在截止时间后继续提交搜索或请求。证据：受控 executor 以固定 `max_in_flight` 提交；截止后未提交项保留为空结果。
- [X] T013 [US2] 运行 T008-T010 的测试，确认先红后绿并在 `specs/019-portfolio-quote-orchestration/tasks.md` 记录测试命令和调用次数证据。证据：`uv run pytest tests/unit/application/test_valuation_service.py tests/unit/application/test_portfolio_valuation.py tests/test_market_data.py -q`，先红 3 项，后绿 `14 passed in 0.39s`；证券/加密调用数各为 1，预测市场上限为 2。

**Checkpoint**：批量源以少于逐标的的外部请求完成查询；预测市场工作数量有上限；部分响应不影响其他标的。

---

## Phase 4: User Story 3 - 保持原有估值合同（Priority: P2）

**Goal**：优化后的报价编排不改变金额、币种、展示币种折算和状态语义，并在两个正式后端上等价。

**Independent Test**：用相同持仓与假报价源在 SQLite 与真实 PostgreSQL 查询并逐字段比较输出。

### Tests for User Story 3 (MANDATORY)

- [X] T014 [P] [US3] 在 `tests/integration/test_portfolio_query_sqlite.py` 添加先失败的去重、批量部分成功、展示币种折算和状态契约测试。证据：SQLite 契约通过，批量调用数为 1；重复标的的市值、USD 计价币种、CNY 折算市值与缺失项 `partial` 均符合合同。
- [X] T015 [P] [US3] 在 `tests/integration/test_portfolio_query_postgres.py` 添加与 SQLite 相同的先失败契约测试，并要求真实 PostgreSQL。证据：已添加同构测试；本机 Unix socket 的专用 `finance_tracker_phase2_test` 数据库以当前系统用户运行 `FT_REQUIRE_TEST_POSTGRES=1 FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_phase2_test' uv run pytest tests/integration/test_portfolio_query_postgres.py -q`，`2 passed in 0.80s`。

### Implementation for User Story 3

- [X] T016 [US3] 调整 `src/ft/adapters/relational/runtime.py` 的依赖装配（如 T005-T012 所需）以保证两个后端注入同一报价编排路径，且不增加数据库读写或回退逻辑。证据：现有 composition root 已将同一 `ValuationService(CompositeQuoteProvider())` 注入 `PortfolioQueryService`；无需增加后端分支、数据库读写或回退逻辑。
- [X] T017 [US3] 运行 SQLite 与真实 PostgreSQL 契约矩阵，修复任何 Decimal、市值、计价币种、展示币种折算或状态不一致，并更新任务证据。证据：SQLite 契约通过；真实 PostgreSQL 使用本机 `finance_tracker_phase2_test` 运行同一测试文件，`2 passed in 0.80s`，去重、计价币种市值、展示币种折算和 `partial` 状态均符合合同。

**Checkpoint**：两个后端在同一假源输入下用户可见结果完全一致。

---

## Phase 5: Polish & Cross-Cutting Verification

- [X] T018 [P] 复核 `DOMAIN_GLOSSARY.md` 和本 feature 中文文档，确保使用“行情报价”“计价币种”“展示币种”“估值状态”等规范术语，必要时同步更新词表。证据：按 `$domain-glossary` 与 `$chinese-documentation` 检查，feature artifacts 与词表一致，无新增术语或需更新词条。
- [X] T019 在 `tests/test_application_investment.py` 运行或补充 CLI 渲染回归：`ft stock list` 在慢源下仍渲染 Finance Tracker 自有表格，且不泄露第三方诊断。证据：`uv run pytest tests/test_application_investment.py -q`，`14 passed in 1.02s`；覆盖慢源后的表格输出和第三方诊断抑制。
- [X] T020 按 `specs/019-portfolio-quote-orchestration/quickstart.md` 运行受影响单元测试、SQLite/真实 PostgreSQL 集成矩阵和 `uv run pytest tests/ -q`，记录未执行项的原因与风险。证据：受影响单元测试 `35 passed in 1.67s`；SQLite 契约通过；真实 PostgreSQL 使用本机 `finance_tracker_phase2_test` 运行同一契约文件，`2 passed in 0.80s`。完整 `uv run pytest tests/ -q -x` 在 `542 passed, 47 skipped` 后因范围外财富 SQLite schema 缺少 `__mc_wealth_source_manifests` 失败；排除该文件后在 `680 passed, 47 skipped` 后于另一财富测试复现相同基线失败。风险：全量回归仍未通过。
- [X] T021 审查最终 diff、未跟踪文件、所有任务状态和 `git diff --check`，然后运行 `speckit-converge`；若追加任务，继续实施直至收敛。证据：`git diff --check` 通过；收敛追加 T022、T023 和 T024 并均已完成，最终审查无新增任务。真实 PostgreSQL 契约已在本机专用 `_test` 数据库通过。

## Dependencies & Execution Order

- Phase 1 在所有代码任务前完成。
- US1 的 T002-T004 必须先失败，才可执行 T005-T006；T007 完成后才能开始 US2。
- US2 的 T008-T010 必须先失败，才可执行 T011-T012；T013 完成后才能开始 US3。
- US3 的 T014-T015 可并行；T016 完成后执行 T017。
- Phase 5 依赖所有用户故事完成。

## Parallel Opportunities

- T014 与 T015 修改不同后端集成测试文件，可并行。
- T018 可在实现期间独立检查文档术语；T019 可在核心行为转绿后与双后端矩阵并行。

## Implementation Strategy

1. 先完成 US1，获得去重、全局截止时间和数据源隔离这一可演示的最小改进。
2. 再完成 US2，减少实际外部调用并限制预测市场并发。
3. 最后以 US3 的双后端矩阵和全量回归确认未改变估值合同。

## Phase 6: Convergence

- [X] T022 修复 `ValuationService.quote_many` 与单项 `quote` 的标识/资产类型不匹配合同：先添加失败测试，确保批量路径在访问数据源前返回 `unsupported`，再以最小实现使其转绿（contracts/quote-batch-port.md，partial）。证据：`uv run pytest tests/unit/application/test_valuation_service.py -q` 先红（错误返回 `complete`），修复后受影响矩阵 `17 passed in 0.88s`。
- [X] T023 修复总查询预算后的后台工作：先添加独立进程级失败测试，证明慢速数据源不会使 `ft stock list` 在 `get_portfolio()` 返回后仍等待非守护工作线程；再保证未完成的行情报价工作不会延长 CLI 进程退出时间，并保留既有 `partial` 估值状态。证据：外层慢源与预测市场嵌套批量路径先红，进程分别在约 `1.06 s` 和 `1.09 s` 后退出；改为有界 daemon 线程调度后，进程级测试 `2 passed in 0.25s`，focused 矩阵 `33 passed in 1.63s`。

## Phase 7: Convergence

- [X] T024 添加可控规模回归，验证至少 30 个非零持仓、3 类数据源与重复标的在 4 秒预算内均返回确定估值状态；同时验证同一批量数据源的 10 个标的外部调用数少于 10（SC-001、SC-004，partial）。证据：30 仓位三源测试验证全部 `complete`、重复证券仅请求一次且在 4 秒内返回；10 证券批量测试验证下载调用数为 1。focused 矩阵 `35 passed in 1.67s`。

## Phase 8: 完成后测试稳定性勘误（受控）

- [X] T025 经用户明确授权，将 `023-deterministic-portfolio-valuation-test` 的唯一测试装配修复归属回写至已 Complete 的 019；023 创建基线为 `3e1e4b3`，现已吸收删除。先红：未注入测试 `clock` 时，固定 `ProviderTick.observed_at` 会随真实 UTC 日期超过新鲜窗口，证券行情为 `stale` 而与 `complete` 断言冲突。后绿：在 `tests/test_application_investment.py` 向 `ValuationService` 注入与固定观测时间相同的 UTC `clock` 后，`uv run pytest tests/test_application_investment.py::test_portfolio_query_uses_valuation_and_never_prices_configured_currency -q` 得到 `1 passed in 0.02s`；`uv run pytest tests/unit/domain/test_valuation_quote.py tests/unit/application/test_valuation_service.py tests/unit/application/test_portfolio_valuation.py tests/test_application_investment.py -q` 得到 `32 passed in 1.58s`；`FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test' FT_REQUIRE_TEST_POSTGRES=1 uv run pytest tests/integration/test_portfolio_query_sqlite.py tests/integration/test_portfolio_query_postgres.py -q` 得到 `4 passed in 1.68s`。完整 `uv run pytest -q` 的已知无关失败为 `tests/test_wealth_performance.py::test_fixed_100k_fact_rebuild_and_active_cache_meet_budgets[sqlite]`：023 原始记录的冷路径 P95 为 `10.53 s`，本次复跑为 `7.43 s`，均超过 `5 s` 预算；用户授权将其作为非阻断记录，不修复或扩大本次范围。风险：完整回归并非全绿，须后续处理该性能回归；准确补跑命令为 `uv run pytest -q`。本任务仅改测试装配，不改生产时钟、报价状态或持久化合同。
