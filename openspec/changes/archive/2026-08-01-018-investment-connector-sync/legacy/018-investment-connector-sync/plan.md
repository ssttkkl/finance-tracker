# Implementation Plan: 投资连接器同步

**Branch**: `018-investment-connector-sync` | **Date**: 2026-07-26 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/018-investment-connector-sync/spec.md`

## Summary

通过 ccxt 库与 Polymarket Activity API/当前 Polygon pUSD 余额，将交易所交易、交易所资金账本活动和预测市场活动自动拉取并映射为统一投资事件，复用现有 `InvestmentImportService`、UnitOfWork 事务和 `source_type × record_id` 幂等机制。新增 `ConnectorPort` 领域接口、`CcxtExchangeConnector`/`PolymarketConnector` adapter、`sync_cursors` 表和 `ft sync` CLI 命令。

Polymarket adapter 除 `TRADE` 外还映射 `REDEEM` 为结果仓位到 USD 的 `swap`，并映射 `YIELD` 为 USD `dividend`；三类活动均保留原始 payload，`REDEEM` / `YIELD` 使用唯一 `transactionHash` 幂等。

Ccxt adapter 同时分页 `fetch_my_trades` 与 `fetch_ledger`：交易是 `swap` 的唯一规范来源；非 trade ledger entry 映射为 deposit/withdraw/dividend/transfer/fee。ledger 分页必须显式检测重复页和无法安全推进的 provider 响应并失败关闭，不能误把单页当作全历史。未知 ledger 类型、字段或费用不完整必须让整个同步回滚；内部 transfer 作为审计事实保留但不改变快照。

持仓 CLI 是同步结果的读取验证路径。`PortfolioQueryService` 将为一次查询设置总行情读取预算；从关系快照读取到的所有非零持仓始终进入 DTO。超过预算、超时、空响应或 provider 异常的项目保留为 `partial`/`N/A`，不能阻塞表格渲染，也不得让第三方 provider 的 stderr 直接泄漏到 CLI。该机制只影响只读估值状态，不写入快照或引入后台任务。

## Technical Context

**Language/Version**: Python 3.11+

**Primary Dependencies**: ccxt ≥ 4.0.0（已安装）、PyYAML（已有）、urllib（标准库，Polymarket API）

**Storage**: PostgreSQL / SQLite 双后端（`FT_DATABASE_URL` 显式选择）；新增 `sync_cursors` 表

**Testing**: pytest（单元 + SQLite 集成 + 真实 PostgreSQL 集成）

**Target Platform**: 本地 CLI（macOS/Linux）

**Project Type**: CLI 工具

**Performance Goals**: 1000 笔交易端到端 ≤ 30 秒（不含网络延迟）

**CLI Read Performance Goal**: 含 16 个以上预测市场合约的 `ft stock list` ≤ 5 秒完整输出；行情失败降级为每项 `partial`/`N/A`

**Constraints**: 整批 fail-closed（单条异常回滚整批事务）；单用户本地运行

**Scale/Scope**: 单用户，预期 ≤ 50,000 笔历史交易

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 状态 | 说明 |
|------|------|------|
| I. 财务正确性与可审计性 | ✅ 通过 | 精确十进制（ExactDecimal）、`source_payload` 保留原始 API 响应、`source_type × record_id` 幂等、fail-closed 异常处理 |
| II. Spec Kit 规格驱动 | ✅ 通过 | 完整 spec → clarify → plan 流程 |
| III. 测试先行与验证证据 | ✅ 通过 | 计划先写失败测试再实现；mock connector 测试 + SQLite 集成 + 真实 PG 集成 |
| IV. 显式数据库选择与行为等价 | ✅ 通过 | `sync_cursors` 表双后端 schema；同一 Alembic 迁移入口；等价性测试矩阵 |
| V. 清晰边界与最小复杂度 | ✅ 通过 | ConnectorPort 解耦领域与外部 API；凭据不进入日志/仓库；复用现有 import 架构，不建通用平台 |

### PostgreSQL / SQLite 等价矩阵

| 维度 | PostgreSQL | SQLite | 验证 |
|------|-----------|--------|------|
| Schema (`sync_cursors`) | `BIGINT` PK, `TIMESTAMP WITH TIME ZONE` | `INTEGER` PK, `TEXT` (ISO datetime) | Alembic 方言分支 |
| 事务隔离 | SERIALIZABLE | WAL + BEGIN IMMEDIATE | 等价性集成测试 |
| 并发写 | 支持（行锁） | 串行（文件锁） | 允许差异 |
| `ExactDecimal` 金额 | `NUMERIC(38,18)` | `String(96)` | 精度等价测试 |
| 幂等查询 | `ix_investment_events_workspace_source_record` | 同索引 | 等价 mock 数据测试 |
| 游标读写 | UPSERT (`ON CONFLICT`) | UPSERT (`ON CONFLICT`) | 等价行为 |
| 错误合同 | `IntegrityError` → `StorageError` | 同映射 | fail-closed 等价测试 |

### 持久化 Constitution 声明

- 不引入跨后端迁移、双写或自动回退。
- `sync_cursors` 表通过 Alembic 统一入口管理，方言实现限于 `SurrogatePK` 和 `UTCDateTime` 差异。
- 凭据文件 (`~/.ft/credentials.yaml`) 不进入数据库或仓库产物。

**违例**: 无。Complexity Tracking 不适用。

### 架构评审结论（2026-07-26）

- 已解决：API 分页失败不得保留任何先前处理分块。连接器必须完整拉取成功后，`SyncService` 才在一个 UnitOfWork 中处理全部事件分块、校验快照、写入游标并一次提交。
- 理由：保证事件、快照和游标的可审计原子性；失败重试不会暴露部分同步结果或推进游标。
- 验证：SQLite 与真实 PostgreSQL 均须覆盖分页/API 错误、映射错误和校验错误时的零写入，以及成功时的事件/快照/游标同提交。
- Ledger 扩展：交易与 ledger 使用同一 `since` 时间游标并全部成功后才进入 UnitOfWork；已由 trade endpoint 表达的 ledger `trade` 分录不重复建账，其余 ledger ID 必须生成主事件，非零 fee 生成 `<ledger_id>:fee` 事件。
- 持仓 CLI 修复（2026-07-27）：以 `PortfolioQueryService` 的单调时钟查询级 deadline 为边界；内部报价预算固定为 4 秒，为 CLI 启动与表格渲染保留余量以满足端到端 5 秒目标。每项只读报价在有界 daemon worker 中等待至剩余预算，预算耗尽后转 `partial`，账本持仓 DTO 仍完整返回。worker 不得写入账本；provider 的 HTTP/yfinance timeout 不得超过该预算，并以 logger guard 收束第三方诊断。验证包括实际阻塞 provider 的时间预算单测，以及 SQLite/真实 PostgreSQL 的持仓集合等价集成测试。

### Polymarket pUSD 当前现金校准设计（2026-07-27）

- 资金地址：复用 `PolymarketConnector._resolve_wallet()` 的 proxy/funder 地址；当前 `~/.ft/credentials.yaml` 配置与 `polymarket-vibe-arb` 的 funder 一致。绝不读取或记录私钥。
- 来源与范围：每次同步调用 Polygon RPC 读取当前 block、其 timestamp，以及 pUSD (`0xC011a7E12a19f7B1f670d46F03B03f3342E82DFB`, 6 decimals) 的 `balanceOf(funder)`。不调用 `eth_getLogs`，不扫描区块，不重建或导入历史出入金。
- 映射：金额为整数 base units / `10^6` 的 `Decimal`；以该 block timestamp 生成 `checkin(to=usd)`、`record_id=checkin:<block>`。投影 checkin 替换 USD 现金、绝不改变市场合约仓位。
- RPC 合同：当前 block、timestamp 或余额任一读取失败均抛 `ConnectorError`/`ConnectorDataError`，不返回部分 `ConnectorResult`。这是一次 `eth_call` 加少量元数据读取，无需注册 RPC 或历史扫描。
- 游标：保留既有纯 Activity timestamp 游标；不创建链上复合游标或数据库迁移。
- 存储：不新增 schema 或迁移；`source_payload` 保存余额观察字段。SQLite / PostgreSQL 映射和事务行为相同。

## Project Structure

### Documentation (this feature)

```text
specs/018-investment-connector-sync/
├── spec.md              # Feature specification
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   └── cli-sync.md      # CLI contract
└── tasks.md             # Phase 2 output (speckit-tasks)
```

### Source Code (repository root)

```text
src/ft/
├── domain/
│   └── connector_port.py          # ConnectorPort Protocol + ConnectorResult dataclass
├── adapters/
│   ├── connectors/
│   │   ├── __init__.py
│   │   ├── ccxt_exchange.py       # CcxtExchangeConnector adapter
│   │   └── polymarket.py          # PolymarketConnector adapter
│   └── relational/
│       └── models.py              # SyncCursorModel (新增)
├── application/
│   ├── sync_service.py            # SyncService（编排 connector → import 事务）
│   └── investment.py              # PortfolioQueryService（有界只读估值）
├── credentials.py                 # CredentialProvider（凭据加载/验证）
└── cli.py                         # ft sync 子命令

migrations/versions/
└── 20260726_10_sync_cursors.py    # Alembic 迁移

tests/
├── unit/
│   ├── test_ccxt_connector.py     # mock ccxt 映射测试
│   ├── test_polymarket_connector.py # mock Activity API 测试
│   └── test_credentials.py        # 凭据加载测试
├── integration/
│   ├── test_sync_sqlite.py        # SQLite 端到端同步
│   └── test_sync_postgres.py      # 真实 PG 端到端同步
└── fixtures/
    ├── ccxt_trades.json           # mock ccxt 交易数据
    └── polymarket_activities.json # mock Polymarket activity 数据
```

**Structure Decision**: 新增 `src/ft/adapters/connectors/` 目录放置 connector adapters，与现有 `adapters/market_data.py`（quote providers）分离，因为 connectors 是写入路径（拉取交易→写入事件），market_data 是读取路径（查询当前价格）。`connector_port.py` 放在 `domain/` 中作为 Protocol 定义，与 `repositories/protocols.py` 中的 repository protocols 保持一致的分层。

## Complexity Tracking

> 无 Constitution 违例。此节不适用。

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| Eng Review | `plan-eng-review` | 有界行情读取、CLI 错误合同、Polygon pUSD 当前余额 checkin 与双后端测试 | 2 | CLEAR | 已纳入总 deadline/daemon worker 风险，以及一次只读余额观察、checkin 替换语义、完整失败回滚与 SQLite/PG 契约矩阵。 |

**VERDICT:** ENG CLEARED — 最小方案只扩展既有 `PolymarketConnector`；Activity 与链上日志先完整读取，再由既有单一 UoW 原子导入和推进游标。
NO UNRESOLVED DECISIONS
