# Feature Specification: Investment Account Import

**Feature Branch**: `009-investment-account-import`

**Created**: 2026-07-23

**Status**: Draft

**Input**: User description: "从 main 恢复投资事件领域模型与文件/手动导入。main 已有完整投资体系（SWAP/DEPOSIT/WITHDRAW/DIVIDEND/CHECKIN、多券商解析），但产品化迁移过程中仅保留了 DFZQ 单一 PoC；本 feature 将其恢复到 PostgreSQL-only + 双 DB + 关系架构中，覆盖多券商 PDF/CSV 解析与投资事件领域模型。买入卖出统一用 SWAP 表示（现金↔资产交换），手续费通过 commission 字段记录。"

**Context**: Restores the full investment event domain model from `main` branch into the current hexagonal architecture (PostgreSQL + SQLite dual backend). The current branch has minimal investment infrastructure (domain projection logic, `investment_events` table, InvestmentService, and DFZQ parser) but lacks: direct import flow from broker statements, multi-broker parsers (exchange sync, Polymarket), comprehensive event types, snapshot validation, and dual-backend equivalence tests.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Import DFZQ broker statement directly (Priority: P1)

作为投资账户用户，我希望能够直接导入东方证券（DFZQ）的 PDF 对账单到 Finance Tracker，系统自动解析并创建投资事件记录，这样我就不需要手动逐笔输入交易或先转换为 CSV 预览文件。

**Why this priority**: 这是投资记账的入口能力。当前分支有 DFZQ 解析器但只能生成 CSV 预览（`ft stock convert`），无法直接导入到数据库。这是 Phase 1 投资链的首要交付。

**Independent Test**: 用真实 DFZQ PDF 对账单执行 `ft import <dfzq.pdf> --source dfzq`，验证系统创建 ImportBatch、RawFile、RawRecord 和 InvestmentEvent 记录，且快照（LedgerSnapshot）正确更新持仓；重复导入同一文件应幂等（通过 source_identity 去重）。

**Acceptance Scenarios**:

1. **Given** 用户有一份 DFZQ PDF 对账单（包含买入、卖出、分红、银证转账等操作），**When** 执行 `ft import statement.pdf --source dfzq --account 东方证券`，**Then** 系统解析 PDF、创建 import batch、保存原始记录与投资事件、更新账户快照，所有操作在一个数据库事务中完成。
2. **Given** 用户重复导入同一 DFZQ 对账单，**When** 系统检查 raw_records.source_identity（基于文件哈希与记录唯一键），**Then** 系统拒绝重复记录并返回幂等结果，不创建重复的投资事件。
3. **Given** DFZQ 对账单包含不支持的操作类型（如未映射的业务代码），**When** 导入时遇到该记录，**Then** 系统失败整个导入事务并给出明确错误（列出无法识别的操作类型与原始行内容），不发布部分事实。
4. **Given** 用户在 PostgreSQL 后端导入 DFZQ 对账单成功，**When** 在 SQLite 后端用相同对账单和账户配置重复相同导入，**Then** 两个后端产生的投资事件数量、金额、ticker、账户快照持仓一致（Constitution IV 双后端等价要求）。

---

### User Story 2 - Restore full investment event types from main (Priority: P1)

作为维护者，我希望当前分支支持完整投资事件类型（SWAP/DEPOSIT/WITHDRAW/DIVIDEND/CHECKIN），以便覆盖证券、加密货币、预测市场的完整生命周期操作，而不仅仅是当前的简化 PoC。SWAP 事件用于表示资产交换（包括买入=现金→资产、卖出=资产→现金、币币交换），手续费通过 commission 字段与 commission_asset 记录。

**Why this priority**: 当前分支的 `investment_projection.py` 已有事件回放骨架，但需要确认事件类型定义完整、SWAP 单行处理逻辑、commission 字段语义、快照验证逻辑与 main 对齐。这是数据模型完整性的基础。

**Independent Test**: 编写单元测试覆盖每种事件类型的回放逻辑（SWAP → 资产交换含买入卖出、DEPOSIT → 增加现金、WITHDRAW → 减少现金、DIVIDEND → 增加现金、CHECKIN → 核对快照）；验证快照中 positions（持仓）、cash（现金）、total_value（总市值，需 010 估值接口）的计算正确性。

**Acceptance Scenarios**:

1. **Given** 系统收到一笔 SWAP 事件（买入股票，from_ticker=CNY, to_ticker=600000.sh），**When** 应用到账户快照，**Then** CNY 现金减少 from_amount + commission，股票持仓增加 to_amount。
2. **Given** 系统收到一笔 SWAP 事件（卖出股票，from_ticker=600000.sh, to_ticker=CNY），**When** 应用到账户快照，**Then** 股票持仓减少 from_amount，CNY 现金增加 to_amount - commission。
3. **Given** 系统收到一笔 SWAP 事件（加密货币交换，from_ticker=USDT, to_ticker=BTC, commission_asset=BNB），**When** 应用到账户快照，**Then** USDT 持仓减少 from_amount，BTC 持仓增加 to_amount，BNB 持仓减少 commission。
4. **Given** 系统收到一笔 DIVIDEND 事件（股息入账），**When** 应用到账户快照，**Then** 账户现金增加 dividend_amount，持仓不变。
5. **Given** 账户快照包含无穷大、NaN 或负数持仓/现金，**When** 快照验证运行，**Then** 系统拒绝快照并报告具体异常字段与值。

---

### User Story 3 - Import exchange trades via API (Priority: P2)

作为加密货币投资者，我希望能够通过 API 同步交易所（如 Binance、OKX）的历史交易记录到 Finance Tracker，系统自动映射为投资事件（SWAP + commission），这样我就不需要手动下载 CSV 或逐笔输入。

**Why this priority**: 多券商支持是投资链的扩展能力。main 分支有交易所同步支持多个交易所；当前分支已删除。恢复后可覆盖加密货币投资场景，但不阻塞 DFZQ（P1）的交付。

**Independent Test**: 配置交易所 API 凭据（如 Binance API key），执行 `ft import --source binance --account Binance现货 --since 2026-01-01`，验证系统通过 API 获取 trades、映射为投资事件（SWAP，买入=quote→base、卖出=base→quote）、保存 raw_records（trade ID 作为 source_identity）、更新快照；重复同步应通过 trade ID 去重。

**Acceptance Scenarios**:

1. **Given** 用户配置了 Binance API 凭据（读取自 credentials 存储，具体机制待定），**When** 执行交易所同步命令，**Then** 系统通过 API 获取指定账户的历史 trades、将每笔 trade 映射为 SWAP 事件（根据 base/quote pair，买入=quote→base、卖出=base→quote）、保存 raw_records（trade.id 作为 source_identity）、创建 investment_events、更新快照。
2. **Given** 交易所返回的 trade 包含手续费（commission），**When** 映射为投资事件，**Then** 系统记录 commission 和 commission_asset（可能与 base/quote 不同，如 BNB）。
3. **Given** 用户重复执行交易所同步，**When** 系统检查 raw_records 中已存在的 trade ID，**Then** 系统跳过已导入的 trades，仅处理新增 trades（增量同步）。
4. **Given** 交易所 API 返回错误（如凭据失效、网络超时），**When** 同步执行，**Then** 系统报告具体错误并不发布部分事实（事务回滚）。

---

### User Story 4 - Import Polymarket prediction market activities (Priority: P3)

作为预测市场投资者，我希望能够同步 Polymarket 账户的交易活动（买入/卖出 YES/NO 仓位、市场结算），系统自动映射为投资事件，这样我的预测市场投资也纳入统一账本。

**Why this priority**: Polymarket 是 main 分支支持的特殊资产类型（预测市场）。恢复优先级低于 DFZQ 和通用交易所，但对完整投资链有价值。

**Independent Test**: 配置 Polymarket 账户（可能需要 wallet address 或 API key），执行 Polymarket 同步，验证系统获取 activities、映射为 BUY/SELL 事件（ticker 为市场 slug + YES/NO）、保存 raw_records（activity.id 作为 source_identity）、更新快照；市场结算后的 win/loss 映射为 CHECKIN 事件。

**Acceptance Scenarios**:

1. **Given** 用户配置了 Polymarket 账户标识，**When** 执行 Polymarket 同步，**Then** 系统通过 Polymarket Activity API 获取历史 activities、将买入/卖出映射为 BUY/SELL 事件（ticker 格式如 `polymarket:election-2024:YES`）、保存 raw_records、创建 investment_events。
2. **Given** Polymarket 市场已结算（resolved），**When** 同步获取 resolution activity，**Then** 系统映射为 CHECKIN 事件（核对最终持仓与实际收益）。
3. **Given** 用户重复执行 Polymarket 同步，**When** 系统检查已存在的 activity ID，**Then** 系统跳过已导入的 activities（幂等）。

---

### Edge Cases

- **DFZQ PDF 格式变化**：券商更新对账单格式导致解析失败时，系统必须报告具体失败位置（页码、行号）与原始文本片段，不得静默跳过或猜测。
- **投资事件与现金账户混淆**：用户尝试将投资对账单导入现金账户（account.type != 'security'/'crypto'），系统必须拒绝并明确提示账户类型不匹配。
- **快照不一致**：导入后账户快照的持仓数量为负数、现金为 NaN、或总市值溢出，系统必须拒绝整个导入事务（恢复 main 的 `_validate_security_snapshot_finite` 逻辑）。
- **重复导入边界**：同一文件不同路径重复导入、文件内容相同但文件名不同、文件轻微修改（如添加空行）导致哈希变化时，系统通过 source_identity（文件哈希 + 记录业务键如交易日期+ticker+金额）识别重复，而非仅依赖文件哈希。
- **PostgreSQL 与 SQLite 差异**：
  - **等价行为**：相同导入输入下，两个后端产生的投资事件数量、金额精度（Decimal）、ticker、账户快照持仓、幂等判断结果必须一致。
  - **允许差异**：事务隔离级别实现（PostgreSQL 用 SERIALIZABLE，SQLite 用 WAL + IMMEDIATE）、并发写入性能、investment_events.id 的具体 UUID 值可不同，但业务键（workspace + raw_record_id）唯一性必须等价。
  - **禁止行为**：不得因某一后端不支持某特性（如 PostgreSQL 的 JSONB 索引）而静默降级导入逻辑或改变事件回放结果。
- **SWAP 两阶段处理**：当前分支用单行 SWAP（from/to 统一 schema），main 用 SWAP_OUT + SWAP_IN 两行；需决策并文档化：若采用单行 SWAP，如何在审计链中追溯释放成本（released cost）；若采用两行，如何保证 SWAP_OUT 与 SWAP_IN 的原子性与关联（如通过 note 字段的 `swap:<id>` 链接）。
- **FEE 独立事件 vs commission 字段**：main 有独立 FEE action（如交易所提币费），当前分支用 commission 字段；需决策并确保两种表示在快照计算与审计链中等价。
- **凭据管理**：交易所 API key、Polymarket wallet 等凭据的存储方式（main 用 `~/.ft/credentials.json`）是否复用、如何安全存储、是否支持环境变量覆盖，本 feature 可简化为"凭据管理待定，可延后到 011"，但必须明确当前实现的临时方案（如硬编码测试凭据仅用于集成测试）。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 支持通过 `ft import <file> --source dfzq --account <account_name>` 直接导入 DFZQ PDF 对账单，解析为投资事件并保存到 `investment_events` 表，整个过程（batch → raw_records → investment_events → snapshot update）在一个数据库事务中完成（参考 007 的导入契约）。

- **FR-002**: 系统 MUST 使用 `raw_records.source_identity`（基于文件哈希与记录业务键如日期+ticker+金额组合）进行幂等去重；重复导入同一对账单时，系统 MUST 拒绝重复记录并返回幂等结果，不创建重复的 `investment_events` 或修改快照。

- **FR-003**: 系统 MUST 支持完整投资事件类型：SWAP（资产交换，用于替代传统 BUY/SELL 操作）、DEPOSIT（入金）、WITHDRAW（出金）、DIVIDEND（分红）、CHECKIN（快照核对），每种事件类型 MUST 在 `domain/investment_projection.py` 中有明确的快照应用逻辑（apply_investment_event）。说明：买入表示为现金→资产 SWAP，卖出表示为资产→现金 SWAP；手续费通过 commission 字段记录而非独立 FEE action。

- **FR-004**: 系统 MUST 恢复 main 分支的快照验证逻辑（`_validate_security_snapshot_finite`），在每次快照更新后检查：持仓数量非负、现金非 NaN/Infinity、总市值有限；验证失败时 MUST 拒绝整个导入事务并报告具体异常字段。

- **FR-005**: 投资事件 MUST 链接 `raw_record_id`（外键到 `raw_records` 表），保持来源审计链；手动创建的投资事件（如 `ft stock buy` CLI 命令）的 `raw_record_id` 为 NULL，但仍需记录 created_at 与 revision。

- **FR-006**: 系统 MUST 采用单行 SWAP 模式（保留当前分支的 from/to 统一 schema），SWAP 事件用于替代传统 BUY/SELL 操作（买入视为现金→资产 SWAP，卖出视为资产→现金 SWAP）。释放成本通过快照中保留的成本基础信息与 from_amount 计算。

- **FR-007**: 系统 MUST 采用 commission 字段处理手续费，commission 作为所有交易事件（BUY/SELL/SWAP/DEPOSIT/WITHDRAW）的附加属性。系统 MUST 提供 commission_asset 字段标识手续费单位（可能与交易主币种不同，如用 BNB 支付手续费）。本 feature 不引入独立 FEE action；独立费用（如提币费、账户管理费）可在后续 feature 中按需扩展。

- **FR-008**: 系统 MUST 支持同步主流加密货币交易所的交易记录（覆盖至少一个交易所如 Binance），将交易所 trades 映射为投资事件（BUY/SELL/SWAP + commission），使用 trade ID 作为 `source_identity` 进行去重。

- **FR-009**: 系统 MUST 支持 Polymarket 预测市场活动同步，将 activities 映射为投资事件（BUY/SELL + 市场结算的 CHECKIN），使用 activity ID 作为 `source_identity` 进行去重。

- **FR-010**: 交易所与 Polymarket 同步所需的 API 凭据 MUST 有明确的存储与读取机制；本 feature 可采用简化临时方案（如环境变量或测试固定凭据），但 MUST 文档化凭据管理的长期方案（延后到 011 Connector 自动同步 feature）。

- **FR-011**: 双后端（PostgreSQL 与 SQLite）MUST 对相同导入输入产生等价的投资事件（数量、金额精度、ticker、快照持仓一致），满足 Constitution IV 的行为等价要求；schema 迁移、事务原子性、幂等判断、快照验证逻辑 MUST 在两个后端保持一致。

- **FR-012**: 系统 MUST 在 DFZQ 解析失败时（如券商格式变化、PDF 损坏、PDF 处理工具缺失）报告具体失败位置（页码、行号）与原始文本片段，不得静默跳过或猜测数据。

- **FR-013**: 系统 MUST 拒绝将投资对账单导入非投资账户（account.type 不为 'security' 或 'crypto'），并明确提示账户类型不匹配错误。

### Key Entities

- **InvestmentEvent**：投资事件，记录一次投资操作（买入、卖出、入金、出金、分红、交换、手续费、核对）。属性包括：occurred_at（发生时间）、kind（'security' | 'crypto'）、action（BUY/SELL/DEPOSIT/WITHDRAW/DIVIDEND/SWAP/FEE/CHECKIN）、ticker（资产标识，如股票代码、加密货币符号）、amount/price/commission（金额/价格/手续费，精确 Decimal）、currency（计价货币）、raw_record_id（来源记录外键，可为 NULL）、payload（JSON，完整事件详情如 from/to ticker、shares、note）。

- **LedgerSnapshot**：账户快照，记录某一时刻账户的持仓与现金。属性包括：account_id、snapshot_date、positions（持仓列表，每个 position 包含 ticker + quantity）、cash（现金余额，按币种分组）、total_value（总市值，需 010 估值接口）。快照由投资事件回放生成，是可重建读模型。

- **RawRecord**：原始导入记录，记录从文件或 API 获取的一行原始数据。属性包括：source_identity（幂等键，如文件哈希+记录业务键）、source_type（'dfzq_pdf' | 'ccxt_trade' | 'polymarket_activity'）、payload（JSON，原始数据）、batch_id（所属导入批次）。

- **ImportBatch**：导入批次，记录一次导入操作的元数据。属性包括：workspace_id、source_type、started_at、completed_at、status（'pending' | 'completed' | 'failed'）、error_message。

- **Account**：账户，已在 005 建模。投资账户的 type 为 'security'（证券）或 'crypto'（加密货币），base_currencies（基础币种列表，如 ['CNY', 'USD']）存储在 metadata 中（005 已移除单一 currency 字段）。

- **Credentials**（简化，待 011 完善）：API 凭据，用于交易所与 Polymarket 同步。本 feature 可简化为环境变量或配置文件读取，长期方案（如加密存储、vault 集成）延后。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 用户能够在 5 分钟内完成 DFZQ PDF 对账单的首次导入（包括账户创建、文件上传、解析、验证、快照生成），系统自动识别交易类型并更新持仓，无需手动逐笔输入或 CSV 预览中转。

- **SC-002**: 双后端（PostgreSQL 与 SQLite）对相同 DFZQ 对账单的导入结果 100% 一致（投资事件数量、金额、ticker、快照持仓、幂等判断结果），通过自动化契约测试矩阵验证（参考 002 双数据库运行时的测试策略）。

- **SC-003**: 系统支持至少 3 种投资数据源（DFZQ 券商、至少 1 个 ccxt 交易所如 Binance、Polymarket 预测市场），每种数据源有独立的解析器模块（importers/dfzq.py、importers/exchange_*.py、importers/polymarket.py）和集成测试覆盖。

- **SC-004**: 重复导入相同对账单或交易记录时，系统 100% 幂等（通过 source_identity 去重），不创建重复的投资事件，不修改已有快照，用户可安全重试导入而不担心重复记账。

- **SC-005**: 投资事件回放逻辑（apply_investment_event）覆盖 5 种事件类型（SWAP/DEPOSIT/WITHDRAW/DIVIDEND/CHECKIN），每种类型有单元测试验证快照计算正确性（持仓增减、现金变动、手续费扣除）。说明：SWAP 统一表示资产交换（包括买入、卖出、币币交换），commission 字段处理手续费。

- **SC-006**: 快照验证逻辑（_validate_security_snapshot_finite）在每次导入后运行，能够检测并拒绝异常快照（负数持仓、NaN 现金、Infinity 市值），防止数据损坏传播，100% 覆盖边界情况的集成测试通过。

- **SC-007**: 导入失败时（解析错误、验证失败、数据库约束冲突），系统事务回滚，不发布部分事实（no partial facts），用户收到明确错误消息（包含失败位置、原始数据片段、建议修复方案），可操作性评分 ≥ 4/5（用户评估）。

## Dependencies

- **005-multi-currency-accounts**：投资账户的 base_currencies 建模（已完成，账户无单一 currency 字段）。
- **007-closed-trade-refund-import**：导入契约（batch → raw_records → 正式事实 + 幂等）、事务原子性、no partial facts 原则。
- **002-dual-database-runtime**：PostgreSQL 与 SQLite 双后端等价测试框架、显式数据库选择（FT_DATABASE_URL）。
- **Constitution IV**：双后端行为等价要求、显式选择、禁止回退。
- **External dependencies**: PDF 处理工具（解密与文本提取）、加密货币交易所 API 客户端库、Polymarket Activity API 访问。

## Out of Scope Notes for Planning

- **投资关系识别**（如同一资产的买卖配对、FIFO/LIFO 成本基础跟踪、realized gain/loss 计算）不在本 feature；当前阶段只建立投资事件事实基线与快照，关系留给后续 feature。
- **行情与估值接口**（如 yfinance、CoinGecko、Polymarket 实时价格）归 010-asset-valuation-quote feature，本 feature 的快照 total_value 可留空或使用占位符。
- **Connector 自动同步**（如定时任务、增量游标、错误重试、凭据轮换）归 011-investment-connector-sync feature，本 feature 只需实现一次性手动同步的完整链路。
- **CSV/snapshot/Git 文件账本**已被 001-postgres-only-storage 删除，不恢复；main 的文件账本逻辑（CSV append、snapshot.json）不迁移到当前分支。
- **DFZQ 之外的券商**（如富途、雪盈、Interactive Brokers）的 PDF/CSV 解析器不在本 feature 最小范围；可在 plan 中列为可选扩展，但验收不强制。
- **多用户与权限**（如 workspace 成员协作、只读账户、审计日志可见性）不在本 feature；当前假设单用户场景。
- **Web/MCP 接口**（如投资事件 REST API、MCP tool 调用）不在本 feature；当前只交付 CLI 导入命令，Web 展示归 012-transaction-browser-web。

## Assumptions

- DFZQ PDF 对账单格式与现有解析器兼容；券商未大幅改版对账单结构（若改版，需更新解析器但不属于本 feature 回归测试范围）。
- 用户已安装 PDF 处理工具（DFZQ PDF 解析依赖）；安装文档与错误提示由解析器提供，不属于本 feature 的安装自动化范围。
- 交易所 API 与 Polymarket API 在测试期间可用且返回格式稳定；若第三方 API 变更导致解析失败，属于 011 Connector feature 的维护范围，本 feature 只需建立初始集成。
- 投资账户的 base_currencies 由用户在账户创建时明确指定（005 已确立多币种账户建模）；导入时系统不自动推断或修改账户币种。
- 当前阶段数据可丢弃（Constitution 工程约束，未发布 schema），因此本 feature 可以破坏性方式修改 `investment_events` 表结构（如增加 action 枚举值、调整 payload schema）而无需提供数据迁移脚本。
- 用户通过 CLI 执行导入（`ft import ...`），不通过 Web 界面或 API；Web 导入归 012 feature。
- 凭据管理采用临时简化方案（如环境变量、配置文件），长期安全方案（加密存储、secret vault、凭据轮换）延后到 011 feature。
- PostgreSQL 与 SQLite 的事务隔离级别差异不影响投资事件的业务逻辑正确性；并发导入场景由 002 的双后端契约测试覆盖。
- main 分支的投资测试套件作为回放逻辑正确性的参考基线；迁移到当前分支的测试可复用其测试用例结构，但需适配 hexagonal 架构（domain/application/adapters 分层）。
