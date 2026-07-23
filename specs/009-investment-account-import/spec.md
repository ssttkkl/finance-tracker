# Feature Specification: Investment Account Import

**Feature Branch**: `009-investment-account-import`

**Created**: 2026-07-23

**Status**: Draft

**Input**: User description: "从 main 恢复投资事件领域模型与文件/手动导入。main 已有完整投资体系（SWAP/DEPOSIT/WITHDRAW/DIVIDEND/CHECKIN、多券商解析），但产品化迁移过程中仅保留了 DFZQ 单一 PoC；本 feature 将其恢复到 PostgreSQL-only + 双 DB + 关系架构中，覆盖多券商 PDF/CSV 解析与投资事件领域模型。买入卖出统一用 SWAP 表示（现金↔资产交换），手续费通过 commission 字段记录。"

**Context**: Restores the investment event domain model and **file/manual** import path into the current hexagonal architecture (PostgreSQL + SQLite dual backend). Scope follows `docs/productization-refactor-plan.md` investment chain:

- **In 009**: DFZQ PDF + IBKR Activity CSV file import; unified events (SWAP/DEPOSIT/WITHDRAW/DIVIDEND/CHECKIN); dual-backend parity; snapshot validation.
- **Not in 009**: live quotes / valuation → **`010-asset-valuation-quote`**; exchange (ccxt) / Polymarket / other **Connector auto-sync** → **`011-investment-connector-sync`** (may be deferred; does not block Phase 2).

Living updates (2026-07-23): IBKR US5; DFZQ peel (FR-001a); US3/US4 → 011; **Schwab US6** file import added.

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

### User Story 3 - Import exchange trades via API — **DEFERRED → 011** (was P2)

> **Living Spec 2026-07-23**: Removed from 009 acceptance. Aligns with
> `docs/productization-refactor-plan.md`: exchange/Polymarket **Connector auto-sync**
> belongs to **`011-investment-connector-sync`**. Historical draft acceptance text
> retained below only as handoff notes for 011 — **not required to complete 009**.

作为加密货币投资者，我希望能够通过 API 同步交易所（如 Binance、OKX）的历史交易记录到 Finance Tracker……（完整验收见未来 `specs/011-…`）。

**009 status**: Out of scope. CLI may still list `binance`/`okx` as reserved source names; MUST fail clearly if invoked until 011 implements them.

---

### User Story 4 - Import Polymarket activities — **DEFERRED → 011** (was P3)

> **Living Spec 2026-07-23**: Activity/API **trade sync** → **011**. Polymarket **live quotes**
> (gamma-api) → **010-asset-valuation-quote**, not this feature.

作为预测市场投资者，我希望能够同步 Polymarket 账户的交易活动……（完整验收见未来 `specs/011-…`）。

**009 status**: Out of scope. Reserved CLI source `polymarket` must not be claimed complete under 009.

---

### User Story 5 - Import Interactive Brokers (盈透) Activity CSV (Priority: P1)

作为投资账户用户，我希望能够直接导入 Interactive Brokers（盈透证券）Activity Statement 的 **Transaction History CSV** 到 Finance Tracker，系统按统一投资事件模型记账（SWAP/DEPOSIT/DIVIDEND/WITHDRAW/CHECKIN），并在导入后用对账单「期末现金」做现金 CHECKIN，以便美股/多币种证券账户与 DFZQ 共用同一导入与投影链路。

**Why this priority**: 真实用户已提供 1Y 交易 CSV 样本；IBKR 是与 DFZQ 并列的证券券商入口。Living 扩展 009（2026-07-23）将 IBKR 从原 Out of Scope 提升为验收内 P1 数据源。费用语义与 DFZQ 不同（总额+佣金字段，而非净额嵌入），必须单独写死合同以防双计费。

**Independent Test**: 用 `tests/fixtures/ibkr/transactions_1y_sample.csv`（或等价真实导出）执行  
`ft import <file> --source ibkr --account 盈透 --currency USD`，验证 batch/raw_records/investment_events、期末 USD 现金等于 总结.期末现金、权益费无双计、重复导入幂等。

**Acceptance Scenarios**:

1. **Given** 用户有一份 IBKR Activity CSV（含买/卖/存款/股息/预扣税/借方利息/外汇交易组成部分与 总结 期末现金），**When** 执行 `ft import statement.csv --source ibkr --account 盈透`，**Then** 系统解析 CSV、创建 import batch、raw_records（source_type=`ibkr_csv`）、投资事件并在同一事务中更新快照；末尾追加 base 货币现金 CHECKIN = 总结.期末现金。
2. **Given** 权益买卖行满足 `净额 = 总额 + 佣金`（佣金≤0），**When** 映射为 SWAP，**Then** 现金腿金额 = `abs(总额)`，`commission = abs(佣金)` 且 `commission_asset = base`，投影后现金变动等于 `abs(净额)`，**不得**再对净额与佣金同时全额扣减（双计费）。
3. **Given** 行类型为 存款 / 股息 / 外国预扣税 / 借方利息 / 外汇交易组成部分，**When** 导入，**Then** 分别映射为 deposit / dividend / withdraw / withdraw / multi-ccy swap（规则见 research.md）；未知 交易类型 MUST 整批失败并报告行内容。
4. **Given** 用户重复导入同一 CSV，**When** 检查 source_digest 与 source_identity，**Then** 返回幂等结果，不重复事件、不改快照。
5. **Given** 在 PostgreSQL 与 SQLite 上用同一 fixture 与账户配置导入，**When** 比较结果，**Then** 事件数量、金额（Decimal）、ticker、期末现金 CHECKIN 一致。

---

### User Story 6 - Import Charles Schwab (嘉信) Transaction History CSV (Priority: P1)

作为投资账户用户，我希望能够直接导入 Charles Schwab（嘉信理财）Transaction History CSV 到 Finance Tracker，系统按统一投资事件记账（SWAP/DEPOSIT/DIVIDEND/WITHDRAW/CHECKIN），并用最新一行「余额」做现金 CHECKIN，以便美股嘉信账户与 DFZQ/IBKR 共用同一导入链路。

**Why this priority**: 用户提供真实导出；属 **文件导入**（productization 009），非 011 connector。费用列为「金额 + 杂费」与 IBKR/DFZQ 不同，须单独合同。

**Independent Test**:  
`ft import tests/fixtures/schwab/transaction_history_sample.csv --source schwab --account 嘉信`  
→ 事件数 = 流水 + 1 cash CHECKIN；USD 现金 = 文件最新行余额；开放持仓与离线回放一致；重导幂等。

**Acceptance Scenarios**:

1. **Given** Schwab CSV（列：日期/类型/说明/参照号码/杂费/佣金/金额/余额；含 TRD/DOI/JRN/WIN），**When** `ft import … --source schwab --account 嘉信`，**Then** 解析、batch、raw_records（`source_type=schwab_csv`）、investment_events、快照在同一事务完成；末尾 cash CHECKIN = **按时间最新一行的余额**。
2. **Given** TRD 行满足余额恒等式 `Δ余额 = 金额 + 杂费`（佣金样本常为 0），**When** 映射 SWAP，**Then** 现金腿 = `abs(金额)`，`commission = abs(杂费)+abs(佣金)`，`commission_asset=usd`；投影现金 = 金额+杂费；**禁止** 现金腿用 `abs(金额+杂费)` 再写非零 commission。
3. **Given** 类型映射：TRD BOT/SOLD→swap；WIN 入金→deposit；DOI 正额股息→dividend、负额利息→withdraw；JRN 负额预扣→withdraw、REFUND/正额→deposit，**When** 导入，**Then** 符合 research.md；未知 类型 fail-closed。
4. **Given** 重复导入同一文件，**When** source_digest / source_identity（参照号码），**Then** 幂等、无重复事件。
5. **Given** SQLite 与真实 PostgreSQL 同一 fixture，**When** 比较，**Then** 事件与期末现金 CHECKIN 一致。

---

### Edge Cases

- **DFZQ PDF 格式变化**：券商更新对账单格式导致解析失败时，系统必须报告具体失败位置（页码、行号）与原始文本片段，不得静默跳过或猜测。
- **IBKR CSV 变体**：缺 总结/Transaction History、未知 交易类型、科学计数法金额、空佣金 `-`、外汇行 `净额==总额` 与权益行 `净额=总额+佣金` 混用时，系统必须按 research.md 费用合同处理或 fail-closed，不得静默跳过未知类型。
- **Schwab CSV 变体**：表头空白、金额 `$`/`()` 格式、杂费 `-`、说明无法解析 BOT/SOLD、未知 类型、文件按时间倒序时，MUST 规范化排序与 fail-closed，不得静默跳过未知类型。
- **投资事件与现金账户混淆**：用户尝试将投资对账单导入现金账户（account.type != 'security'/'crypto'），系统必须拒绝并明确提示账户类型不匹配。
- **快照不一致**：导入后账户快照的持仓数量为负数、现金为 NaN、或总市值溢出，系统必须拒绝整个导入事务（恢复 main 的 `_validate_security_snapshot_finite` 逻辑）。
- **重复导入边界**：同一文件不同路径重复导入、文件内容相同但文件名不同、文件轻微修改（如添加空行）导致哈希变化时，系统通过 source_identity（文件哈希 + 记录业务键如交易日期+ticker+金额）识别重复，而非仅依赖文件哈希。
- **PostgreSQL 与 SQLite 差异**：
  - **等价行为**：相同导入输入下，两个后端产生的投资事件数量、金额精度（Decimal）、ticker、账户快照持仓、幂等判断结果必须一致。
  - **允许差异**：事务隔离级别实现（PostgreSQL 用 SERIALIZABLE，SQLite 用 WAL + IMMEDIATE）、并发写入性能、investment_events.id 的具体 UUID 值可不同，但业务键（workspace + raw_record_id）唯一性必须等价。
  - **禁止行为**：不得因某一后端不支持某特性（如 PostgreSQL 的 JSONB 索引）而静默降级导入逻辑或改变事件回放结果。
- **SWAP 两阶段处理**：当前分支用单行 SWAP（from/to 统一 schema），main 用 SWAP_OUT + SWAP_IN 两行；需决策并文档化：若采用单行 SWAP，如何在审计链中追溯释放成本（released cost）；若采用两行，如何保证 SWAP_OUT 与 SWAP_IN 的原子性与关联（如通过 note 字段的 `swap:<id>` 链接）。
- **FEE 独立事件 vs commission 字段**：main 有独立 FEE action（如交易所提币费），当前分支用 commission 字段；需决策并确保两种表示在快照计算与审计链中等价。
- **凭据管理**：交易所 API key、Polymarket wallet 等归 **011**；009 不实现 credentials 平台。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 支持通过 `ft import <file> --source dfzq --account <account_name>` 直接导入 DFZQ PDF 对账单，解析为投资事件并保存到 `investment_events` 表，整个过程（batch → raw_records → investment_events → snapshot update）在一个数据库事务中完成（参考 007 的导入契约）。

- **FR-001a**: 对 DFZQ 证券买卖，系统 MUST 采用 **peel 费用合同**（与 IBKR 的「总额+commission」不同，但共享「同一分钱只记一次」约束）：
  - 源字段「总发生金额」为 **净额**（已含手续费/印花税/过户费后的资金变动）。
  - 当「手续费」可拆分时：买入 `from_amount = |净额| - 手续费`、`commission = 手续费`、`commission_asset = cny`（投影现金流出 = from + commission = |净额|）；卖出 `to_amount = |净额| + 手续费`、`commission = 手续费`（投影现金流入 = to − commission = |净额|）。
  - 当手续费缺失或买入侧无法干净拆分（手续费 ≥ |净额|）时：现金腿 = |净额| 且 `commission = 0`。
  - 印花税/过户费默认留在现金腿（不强制 peel）。
  - MUST NOT 在现金腿已含某笔手续费的同时再把同一笔手续费写入非零 `commission`（双计禁止）。

- **FR-002**: 系统 MUST 使用 `raw_records.source_identity`（基于文件哈希与记录业务键如日期+ticker+金额组合）进行幂等去重；重复导入同一对账单时，系统 MUST 拒绝重复记录并返回幂等结果，不创建重复的 `investment_events` 或修改快照。

- **FR-003**: 系统 MUST 支持完整投资事件类型：SWAP（资产交换，用于替代传统 BUY/SELL 操作）、DEPOSIT（入金）、WITHDRAW（出金）、DIVIDEND（分红）、CHECKIN（快照核对），每种事件类型 MUST 在 `domain/investment_projection.py` 中有明确的快照应用逻辑（apply_investment_event）。说明：买入表示为现金→资产 SWAP，卖出表示为资产→现金 SWAP；手续费通过 commission 字段记录而非独立 FEE action。

- **FR-004**: 系统 MUST 恢复 main 分支的快照验证逻辑（`_validate_security_snapshot_finite`），在每次快照更新后检查：持仓数量非负、现金非 NaN/Infinity、总市值有限；验证失败时 MUST 拒绝整个导入事务并报告具体异常字段。

- **FR-005**: 投资事件 MUST 链接 `raw_record_id`（外键到 `raw_records` 表），保持来源审计链；手动创建的投资事件（如 `ft stock buy` CLI 命令）的 `raw_record_id` 为 NULL，但仍需记录 created_at 与 revision。

- **FR-006**: 系统 MUST 采用单行 SWAP 模式（保留当前分支的 from/to 统一 schema），SWAP 事件用于替代传统 BUY/SELL 操作（买入视为现金→资产 SWAP，卖出视为资产→现金 SWAP）。释放成本通过快照中保留的成本基础信息与 from_amount 计算。

- **FR-007**: 系统 MUST 采用 commission 字段处理手续费，commission 作为所有交易事件（BUY/SELL/SWAP/DEPOSIT/WITHDRAW）的附加属性。系统 MUST 提供 commission_asset 字段标识手续费单位（可能与交易主币种不同，如用 BNB 支付手续费）。本 feature 不引入独立 FEE action；独立费用（如提币费、账户管理费）可在后续 feature 中按需扩展。

- **FR-008** *(DEFERRED → 011)*: ~~交易所 ccxt 交易同步~~ — **not required for 009 completion**. Superseded by productization plan: implement under `011-investment-connector-sync`.

- **FR-009** *(DEFERRED → 011)*: ~~Polymarket Activity API 交易同步~~ — **not required for 009**. Quotes/valuation for Polymarket markets → **010**; activity import → **011**.

- **FR-010** *(DEFERRED → 011)*: ~~交易所/Polymarket API 凭据存储~~ — **not required for 009**. Documented long-term under 011.

- **FR-011**: 双后端（PostgreSQL 与 SQLite）MUST 对相同**文件**导入输入（DFZQ PDF / IBKR CSV / Schwab CSV）产生等价的投资事件（数量、金额精度、ticker、快照持仓一致），满足 Constitution IV 的行为等价要求；schema 迁移、事务原子性、幂等判断、快照验证逻辑 MUST 在两个后端保持一致。

- **FR-012**: 系统 MUST 在 DFZQ 解析失败时（如券商格式变化、PDF 损坏、PDF 处理工具缺失）报告具体失败位置（页码、行号）与原始文本片段，不得静默跳过或猜测数据。

- **FR-013**: 系统 MUST 拒绝将投资对账单导入非投资账户（account.type 不为 'security' 或 'crypto'），并明确提示账户类型不匹配错误。

- **FR-014**: 系统 MUST 支持通过 `ft import <file> --source ibkr --account <account_name>` 导入 Interactive Brokers Activity Statement 风格的 Transaction History CSV，解析为投资事件并写入 `investment_events`，batch → raw_records → events → snapshot 在同一事务中完成；`raw_records.source_type` MUST 为 `ibkr_csv`。

- **FR-015**: 对 IBKR 权益「买/卖」，系统 MUST 采用 **总额 + commission** 费用合同：SWAP 现金腿 = `abs(总额)`，`commission = abs(佣金)`（空佣金视为 0），`commission_asset` = 账户/总结基础货币小写 ticker；MUST NOT 在现金腿已使用 `abs(净额)` 时再写入非零 commission（双计费禁止）。投影后单笔现金影响 MUST 等于该行 `净额` 的绝对值方向一致结果。

- **FR-016**: IBKR 非权益类型映射 MUST 为：`存款`→`deposit`，`股息`→`dividend`（现金分红），`外国预扣税`→`withdraw`，`借方利息`→`withdraw`，`外汇交易组成部分`→`swap`。FX 规则：代码 `BASE.QUOTE`（如 `USD.HKD`）；左腿数量 = abs(数量)，右腿数量 = abs(数量)×价格（Price Currency）；买卖方向由数量/净额符号决定（买左/卖右或相反须与样本一致并单测锁定）；若该行 `净额 == 总额`（佣金已嵌在总额内），MUST `commission=0` 且佣金写入 note——**不得**再对 commission 字段扣减。无法解析 pair 或缺少数量/价格 MUST fail-closed。未知 `交易类型` MUST fail-closed。验收以 base 货币现金 CHECKIN 为准；非 base 货币仓位（如 hkd）允许非零残差，不得为对齐而发明金额。

- **FR-017**: IBKR 导入 MUST 在流水事件之后追加 **一条** base 货币现金 CHECKIN，金额取自 CSV「总结」`期末现金`；本 CSV 无持仓成本表时 MUST NOT 发明持仓 CHECKIN。`source_identity` MUST 使用稳定业务键（见 research.md `ibkr:…` 配方）。

- **FR-018**: 系统 MUST 支持通过 `ft import <file> --source schwab --account <account_name>` 导入 Charles Schwab Transaction History 风格 CSV，解析为投资事件并写入 `investment_events`，batch → raw_records → events → snapshot 在同一事务中完成；`raw_records.source_type` MUST 为 `schwab_csv`。

- **FR-019**: 对 Schwab TRD，系统 MUST 采用 **金额 + 杂费** 费用合同：SWAP 现金腿 = `abs(金额)`；`commission = abs(杂费) + abs(佣金)`（空/`-` 视为 0），`commission_asset = usd`（或账户 base）；MUST NOT 以 `abs(金额+杂费)` 为现金腿同时写入非零 commission。投影后单笔现金影响 MUST 等于该行 `金额 + 杂费`（与余额差分一致）。

- **FR-020**: Schwab 非 TRD 映射 MUST 为：`WIN` 入金→`deposit`；`DOI` 金额>0（股息）→`dividend`，金额<0（利息等）→`withdraw`；`JRN` 金额<0→`withdraw`，金额>0 或说明含 REFUND→`deposit`。未知 `类型` 或 TRD 说明无法解析 BOT/SOLD MUST fail-closed。

- **FR-021**: Schwab 导入 MUST 在流水后追加 **一条** USD 现金 CHECKIN，金额 = 文件中按时间最新一行的 `余额`；无持仓表时 MUST NOT 发明持仓 CHECKIN。`source_identity` 优先 `schwab:{参照号码}:{类型}`（见 research.md）。

### Key Entities

- **InvestmentEvent**：投资事件，记录一次投资操作（买入、卖出、入金、出金、分红、交换、手续费、核对）。属性包括：occurred_at（发生时间）、kind（'security' | 'crypto'）、action（BUY/SELL/DEPOSIT/WITHDRAW/DIVIDEND/SWAP/FEE/CHECKIN）、ticker（资产标识，如股票代码、加密货币符号）、amount/price/commission（金额/价格/手续费，精确 Decimal）、currency（计价货币）、raw_record_id（来源记录外键，可为 NULL）、payload（JSON，完整事件详情如 from/to ticker、shares、note）。

- **LedgerSnapshot**：账户快照，记录某一时刻账户的持仓与现金。属性包括：account_id、snapshot_date、positions（持仓列表，每个 position 包含 ticker + quantity）、cash（现金余额，按币种分组）、total_value（总市值，需 010 估值接口）。快照由投资事件回放生成，是可重建读模型。

- **RawRecord**：原始导入记录，记录从文件获取的一行原始数据。属性包括：source_identity（幂等键）、source_type（009 交付：`dfzq_pdf` | `ibkr_csv` | `schwab_csv`；`ccxt_*` / `polymarket_*` reserved for **011**）、payload（JSON）、batch_id。

- **ImportBatch**：导入批次，记录一次导入操作的元数据。属性包括：workspace_id、source_type、started_at、completed_at、status（'pending' | 'completed' | 'failed'）、error_message。

- **Account**：账户，已在 005 建模。投资账户的 type 为 'security'（证券）或 'crypto'（加密货币），base_currencies（基础币种列表，如 ['CNY', 'USD']）存储在 metadata 中（005 已移除单一 currency 字段）。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 用户能够在 5 分钟内完成 DFZQ PDF 对账单的首次导入（包括账户创建、文件上传、解析、验证、快照生成），系统自动识别交易类型并更新持仓，无需手动逐笔输入或 CSV 预览中转。

- **SC-002**: 双后端（PostgreSQL 与 SQLite）对相同 DFZQ 对账单的导入结果 100% 一致（投资事件数量、金额、ticker、快照持仓、幂等判断结果），通过自动化契约测试矩阵验证（参考 002 双数据库运行时的测试策略）。

- **SC-003**: **009 完成定义** = 文件导入源 **DFZQ + IBKR + Schwab** 可用，且 US2 事件回放/校验达标，双后端契约对上述文件源通过。不得以“未做 ccxt/Polymarket”判定 009 未完成。ccxt/Polymarket **同步** 归 **011**；Polymarket **取价** 归 **010**。

- **SC-008**: 用 `tests/fixtures/ibkr/transactions_1y_sample.csv` 导入后：权益费双计 = 0；快照 base 现金在 CHECKIN 后等于 总结.期末现金（允许 ≤0.01 仅当样本含科学计数法尾差时文档化）；开放持仓股数与离线回放一致；重复导入 count=0。

- **SC-009**: 用 `tests/fixtures/schwab/transaction_history_sample.csv` 导入后：权益费双计 = 0；快照 USD 现金 = 最新行余额 `2865.36`；开放持仓 AVGO 7、MSFT 5；重复导入 count=0。

- **SC-004**: 重复导入相同对账单或交易记录时，系统 100% 幂等（通过 source_identity 去重），不创建重复的投资事件，不修改已有快照，用户可安全重试导入而不担心重复记账。

- **SC-005**: 投资事件回放逻辑（apply_investment_event）覆盖 5 种事件类型（SWAP/DEPOSIT/WITHDRAW/DIVIDEND/CHECKIN），每种类型有单元测试验证快照计算正确性（持仓增减、现金变动、手续费扣除）。说明：SWAP 统一表示资产交换（包括买入、卖出、币币交换），commission 字段处理手续费。

- **SC-006**: 快照验证逻辑（_validate_security_snapshot_finite）在每次导入后运行，能够检测并拒绝异常快照（负数持仓、NaN 现金、Infinity 市值），防止数据损坏传播，100% 覆盖边界情况的集成测试通过。

- **SC-007**: 导入失败时（解析错误、验证失败、数据库约束冲突），系统事务回滚，不发布部分事实（no partial facts），用户收到明确错误消息（包含失败位置、原始数据片段、建议修复方案），可操作性评分 ≥ 4/5（用户评估）。

## Dependencies

- **005-multi-currency-accounts**：投资账户的 base_currencies 建模（已完成，账户无单一 currency 字段）。
- **007-closed-trade-refund-import**：导入契约（batch → raw_records → 正式事实 + 幂等）、事务原子性、no partial facts 原则。
- **002-dual-database-runtime**：PostgreSQL 与 SQLite 双后端等价测试框架、显式数据库选择（FT_DATABASE_URL）。
- **Constitution IV**：双后端行为等价要求、显式选择、禁止回退。
- **External dependencies (009)**: PDF 处理工具（qpdf/mutool，DFZQ）；无交易所/Polymarket API 运行时依赖。
- **Downstream**: `010-asset-valuation-quote`；`011-investment-connector-sync`（exchange/Polymarket sync）。
- **Roadmap**: `docs/productization-refactor-plan.md` 投资链 009/010/011。

## Out of Scope Notes for Planning

- **投资关系识别**（如同一资产的买卖配对、FIFO/LIFO 成本基础跟踪、realized gain/loss 计算）不在本 feature；当前阶段只建立投资事件事实基线与快照，关系留给后续 feature。
- **行情与估值接口**（yfinance、CoinGecko、**Polymarket gamma 实时价格**）归 **010-asset-valuation-quote**；本 feature 快照 total_value 可留空。
- **Connector 自动同步**（ccxt Binance/OKX 交易拉取、Polymarket Activity API、定时任务、增量游标、凭据轮换、错误重试）归 **011-investment-connector-sync**。**009 明确不交付 US3/US4**（对齐 productization plan；原 draft 任务 T072–T112 已 cancel/defer）。
- **CSV/snapshot/Git 文件账本**已被 001-postgres-only-storage 删除，不恢复。
- **富途、雪盈等其他券商** PDF/CSV 解析器仍不在本 feature 最小范围；**IBKR Activity CSV（US5）与 Schwab Transaction History CSV（US6）已纳入**。IBKR Flex/API、Schwab API、英文表头未认证变体、持仓成本导出不在本阶段。
- **多用户与权限**、**Web/MCP 投资导入 UI** 不在本 feature（Web 展示归 012）。

## Assumptions

- DFZQ PDF / IBKR CSV 格式与现有/校准样本兼容；大改版需更新解析器但不扩 009 范围。
- 用户已安装 PDF 处理工具（DFZQ）；IBKR CSV 无外部工具依赖。
- 投资账户 base_currencies 由用户在账户创建时指定；导入不自动改账户币种。
- 当前阶段数据可丢弃，schema 可破坏性调整无需长期迁移剧本。
- 用户通过 CLI 执行文件导入；不实现 credentials vault（011）。
- PostgreSQL 与 SQLite 隔离级别差异不影响投资事件业务正确性；并发由 002 契约覆盖。