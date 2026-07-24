# Feature Specification: uSmart HK (盈立证券香港) Monthly Statement Import

**Feature Branch**: `011-usmart-hk-import`

**Created**: 2026-07-23

**Status**: Complete

**Input**: User description: "盈立证券香港（usmart-hk）月结单 PDF 导入器；密码保护 PDF；字段普查基于真实 2026-06 月结单。"

**Context**: Flow-Forward investment source after **009** (DFZQ / IBKR / Schwab file import) and **010** (row-level `source_identity` idempotency). Extends investment file import with **uSmart Securities Limited (盈立证券有限公司, Hong Kong)** encrypted monthly PDF statements. Reuses 009 event model only (`swap` / `deposit` / `withdraw` / `dividend` / `checkin`) — **no new event actions**. Single-row SWAP + commission, dual-backend, and investment import orchestration. Does **not** reopen 009 complete scopes for DFZQ/IBKR/Schwab source mappings; does **not** implement connector platform (012) or live valuation.

**Extends / Supersedes**: Extends `009-investment-account-import` (new source only). Idempotency rules follow `010-row-idempotent-import` (business row identity is sole “already booked” rule; file digest is audit metadata only).

**Clarifications resolved (2026-07-23)**:
1. **换汇** → 现金↔现金 **`swap`**（配对后单笔；见 FR-008a）。
2. **转账类**（含「转入到日内融账户」等）→ **`withdraw` / `deposit`**（按金额符号；见 FR-008b）；**不**引入 `transfer` 动作。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Import uSmart HK monthly PDF (Priority: P1)

作为持有盈立证券香港（uSmart Securities Limited）保证金账户的用户，我希望能用 `ft import` 直接导入加密月结单 PDF，系统自动解析交易明细、非交易资金与期末结余/持仓核对，写入统一投资事件并更新证券账户快照，这样我不必手录美股/港股成交与费用。

**Why this priority**: 真实用户月结单已提供；与 DFZQ/IBKR/Schwab 并列的港股券商文件入口。费用与「资金出入」结构独特，必须单独费用合同与双源去重规则。

**Independent Test**: 使用去标识文本夹具（及本地加密 PDF 校准）执行  
`ft import <usmart-hk-month.pdf> --source usmart-hk --account 盈立证券 --password-file <pw>`  
→ 产生 batch / raw_records / investment_events；USD/HKD 期末现金 CHECKIN 后等于页眉「期末账户结余」；持仓股数等于「持仓明细」；权益费无双计；再导 novel count=0。

**Acceptance Scenarios**:

1. **Given** 用户有一份密码保护的 uSmart HK 月结单 PDF（含页眉三市场汇总、交易明细、持仓明细、资金出入），且已有 type=`security` 账户，**When** 执行 `ft import statement.pdf --source usmart-hk --account 盈立证券 --password-file pw.txt`，**Then** 系统解密并解析 PDF、在同一事务中写入 import batch、raw_records（`source_type=usmart_hk_pdf`）、investment_events 并更新 LedgerSnapshot。
2. **Given** 交易明细中同一订单组含多笔 fill 与一组「变动金额合计」，**When** 映射投资事件，**Then** 每个订单组生成 **一笔** 权益 SWAP（fills 数量与金额合并），不按 fill 拆成多笔独立收费 SWAP。
3. **Given** 订单组满足费用恒等式 `fees = | |交易金额| − |变动金额合计| |` 且 `fees ≥ 0`（佣金、平台费、交收费、证监会规费、交易活动费、综合审计追踪费等合计），**When** 映射 SWAP，**Then** 现金腿 = `|交易金额|`（gross），`commission = fees`（非负），`commission_asset` = 交易币种小写（如 `usd`）；买入 `swap` 现金→证券（投影现金流出 = gross + commission = `|net|`）；卖出 `swap` 证券→现金（投影现金流入 = gross − commission = `|net|`）；净现金变动方向与「变动金额合计」一致；**禁止** 现金腿已用 `|变动金额合计|` 时再写非零 commission（双计）。
4. **Given** 资金出入中出现与交易明细重叠的「买/卖股票」「买/卖股票手续费」「买入股票」「卖出股票」等标志，**When** 导入，**Then** 这些行 **不得** 再生成投资事件（仅由交易明细记账）；非重叠标志（出金、IPO 认购退款、融资利息等）按映射表记账。
5. **Given** 页眉给出各市场「期末账户结余」，**When** 流水事件应用完毕，**Then** 系统对有数值的币种各追加一条现金 CHECKIN（如 HKD / USD；CNY 为 `--` 或缺省则跳过该币种）；CHECKIN 金额等于该市场期末账户结余，日期为结单月份末日（结单日期 `YYYY-MM` 的月末）。
6. **Given** 「持仓明细」列出证券与持有数量且 **无成本价字段**，**When** 导入，**Then** 对每个持有数量≠0 的证券追加持仓 CHECKIN，**仅对齐股数**；MUST NOT 发明成本价或市价作为成本；成本保留流水回放结果（或文档化不可信）。
7. **Given** 用户重复导入同一业务行（含重叠文件），**When** 按 `source_identity` 判断，**Then** 已存在行不重复事件、不改快照；仅 novel 行入账（010 行幂等）。
8. **Given** 在 PostgreSQL 与 SQLite 上用同一夹具与账户配置导入，**When** 比较结果，**Then** 事件数量、金额（Decimal）、ticker、期末现金 CHECKIN、持仓股数 CHECKIN 一致。

---

### User Story 2 - Multi-market header and empty sections (Priority: P2)

作为同一盈立账户同时持有港股/美股（及可能 A 股通）的用户，我希望月结单中某一市场无交易或字段为 `--` 时导入仍成功，有数据的市场照常记账与 CHECKIN。

**Why this priority**: 样本月美股有交易、港股仅有期末持仓与结余、A 股通为空；parser 必须容忍空市场，否则每月导入脆弱。

**Independent Test**: 夹具含 HKD 持仓+结余、USD 交易+结余、CNY 全 `--`；导入成功且仅对有数值的结余/持仓发 CHECKIN。

**Acceptance Scenarios**:

1. **Given** 页眉 A 股通/CNY 列为 `--` 或无交易/无持仓，**When** 导入，**Then** 不因空列失败，且不对该币种发明虚假结余事件（无数值则不发该币种 cash CHECKIN）。
2. **Given** 港股市场当月无「交易明细」成交但有持仓与结余，**When** 导入，**Then** 仍发出 HKD 现金 CHECKIN 与港股持仓 CHECKIN（若有持仓行）。
3. **Given** 「证券提存」为「暂无数据」，**When** 导入，**Then** 不失败、不生成提存事件。

---

### User Story 3 - Non-trade cash movements (Priority: P2)

作为用户，我希望月结单「资金出入」中的入金/出金/IPO 退款/融资利息等非成交资金被正确记账；**换汇**记为现金↔现金 `swap`；**转账/日内融调拨**只用既有 `withdraw` / `deposit`（按金额符号），不引入新事件类型。

**Why this priority**: 样本含 IPO 认购退款、换汇、多次出金、融资利息、转入到日内融账户；漏记会破坏期末现金对齐；用户决议：换汇用 swap，转账用 withdraw/deposit。

**Independent Test**: 夹具仅含资金出入非交易行 + 页眉结余；导入后非交易事件与 CHECKIN 符合映射表；换汇产出 cash↔cash swap；日内融/转账产出 withdraw 或 deposit；未知标志 fail-closed。

**Acceptance Scenarios**:

1. **Given** 业务标志为出金（或提取），金额为负或绝对值出金语义，**When** 导入，**Then** 映射为 `withdraw`，币种与金额正确。
2. **Given** 业务标志为 IPO 认购退款，**When** 导入，**Then** 映射为 `deposit`，备注保留 IPO Refund 等信息。
3. **Given** 业务标志为融资利息，**When** 导入，**Then** 映射为 `withdraw`（现金减少）。
4. **Given** 业务标志为换汇，且解析器能将相关行配对为两币种腿，**When** 导入，**Then** 生成 **一笔** `action=swap`，from/to 为两现金 ticker（如 `hkd`↔`usd`），from_amount/to_amount 为各腿绝对值，commission=0；note 标明换汇。
5. **Given** 业务标志为换汇但无法在容差内配对对侧腿，**When** 导入，**Then** fail-closed 并报告未配对行（不得发明汇率凑平；不得静默改成 deposit/withdraw）。
6. **Given** 业务标志为「转入到日内融账户」或其它转账类标志，**When** 导入，**Then** 按金额符号映射为 `withdraw`（金额为负或出账）或 `deposit`（金额为正或入账），note 保留原文；**不得**引入 `transfer` 动作或虚构子口袋 ticker。
7. **Given** 未知业务标志，**When** 导入，**Then** 整批失败并报告标志原文与行上下文，不发布部分事实。

---

### Edge Cases

- **PDF 加密 / 工具缺失**：密码错误、缺 qpdf/mutool、超时或提取超限时，MUST 失败并给出可操作提示（安装工具、检查 `--password-file`），不得把 PDF 当 UTF-8 文本直接打开。
- **订单组解析失败**：证券名跨行折行、页脚/页码插入、费用块与 fill 行错位、缺「变动金额合计」或「交易金额」时，MUST fail-closed 并报告片段，不得静默跳过成交。
- **费用不一致**：`|变动金额合计|` 与 `交易金额 + 明细费用之和` 在容差外（建议默认 0.02 并文档化）时 MUST fail-closed，不得静默改写金额。
- **资金出入与交易明细双源**：重叠交易/手续费行 MUST 忽略；若忽略集合遗漏导致双记，视为缺陷。
- **强平标志**：`是否强平=是` 仍按买卖记账，note 可标注强平；不得丢弃。
- **多币种现金**：同一 security 账户内用 `hkd` / `usd` / `cny` 等现金 ticker 持仓表示多币种结余；默认账户展示币种不改变事件币种。
- **持仓无成本**：不得用收市价/市值反推成本写入 CHECKIN price。
- **卖出超过流水回放持仓**：历史不全时可能阻塞；应用期末持仓 CHECKIN 对齐股数，或文档化 soft-start；不得静默改写成交数量。
- **行幂等（010）**：同一 `source_identity` 跨文件只入账一次；文件 digest 不得单独短路跳过 novel 行。
- **账户类型**：导入非 `security`/`crypto` 账户 MUST 拒绝。
- **双后端**：
  - **等价**：事件数、金额、ticker、CHECKIN、幂等结果一致。
  - **允许差异**：UUID、隔离级别实现、性能。
  - **禁止**：因方言静默降级解析或投影语义。
- **隐私**：真实 PDF/个人姓名地址不得入库 git；夹具去标识。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 支持通过  
  `ft import <file> --source usmart-hk --account <account_name> [--password-file <path>]`  
  导入盈立证券香港月结单 PDF，解析为投资事件并写入 `investment_events`；batch → raw_records → events → snapshot 在同一事务中完成。CLI 规范 source 字符串为 **`usmart-hk`**（内部可规范化为 `usmart_hk`）；别名 `usmart_hk` / `usmart` 若支持 MUST 文档化且行为相同。

- **FR-002**: `raw_records.source_type` MUST 为 `usmart_hk_pdf`。幂等 MUST 遵循 010：仅 `source_type` + `source_identity`（workspace 内）决定是否已入账；MUST NOT 仅因 `source_digest` 已有完成 batch 而跳过 novel 行。

- **FR-003**: 解密与文本提取 MUST 使用项目既有安全 PDF 路径（密码经 password-file / 临时文件，不进入 argv 明文策略与 DFZQ 一致）；提取编码使用 UTF-8 replace，禁止将 PDF 当文本直接打开。

- **FR-004**: 解析 MUST 覆盖并区分至少以下区段：页眉市场汇总、交易明细（订单组）、持仓明细、资金出入；证券提存为空 MUST 可接受。区段标题/CJK 兼容字形变体（如 `⾦`/`金`）MUST 可识别或在预处理中规范化。

- **FR-005**: 交易明细 MUST 以 **订单组** 为记账单元：同一组内多笔 fill 合并数量与成交金额；组级字段「交易金额」「变动金额合计」及费用明细用于费用合同。MUST NOT 默认按 fill 各记一笔并分摊费用（除非未来 Living Spec 明确变更）。

- **FR-006**: 权益买卖费用合同 MUST 为 **gross + commission**（对齐 IBKR 语义，非 DFZQ peel；**分侧**）：
  - 令 `gross = |交易金额|`，`net = 变动金额合计`（带符号），`abs_net = |net|`
  - `commission = |gross − abs_net|`（等价：买入 `abs_net − gross`，卖出 `gross − abs_net`）；MUST ≥ 0；若为负超容差（默认 0.02）fail-closed
  - `commission_asset` = 交易币种小写
  - 买入（`net < 0` 或侧=买）：`action=swap`，from=现金 ticker，to=证券，from_amount=`gross`，to_amount=合并数量；投影现金流出 = gross + commission = `abs_net`
  - 卖出（`net > 0` 或侧=卖）：from=证券，to=现金，from_amount=合并数量，to_amount=`gross`；投影现金流入 = gross − commission = `abs_net`
  - **禁止** 使用无符号公式 `commission = abs_net − gross` 作为卖出佣金（样本 DELL 卖出会得到 −4.00，非法）
  - 同一分钱费用 MUST 只出现在 commission **或** 现金腿之一，禁止双计
  - 组内费用明细可写入 note/payload 审计，不得再扣一次

- **FR-007**: 证券 ticker 规范化 MUST 稳定可测：美股 MUST 加 `.us`（如 `mrvl.us`）；港股 MUST 加 `.hk`（如 `00700.hk`）；与 IBKR/Schwab/DFZQ 共用 `ticker_normalize` 约定；中文名可进 note。市场字段（美股/港股/A股通）MUST 进入 payload 或 note 以便审计。

- **FR-008**: 资金出入映射 MUST 至少包括：
  | 业务标志（含常见变体） | 动作 |
  |---|---|
  | 出金 / 提取 | withdraw |
  | 入金 / 存款（若出现） | deposit |
  | IPO认购退款 | deposit |
  | 融资利息 | withdraw |
  | 买股票 / 卖股票 / 买入股票 / 卖出股票 / 买股票手续费 / 卖股票手续费 等成交镜像 | **忽略（不入账）** |
  | 换汇 | **swap**（现金↔现金，见 FR-008a） |
  | 转入到日内融账户 / 转账 / 转入 / 转出（非换汇、非成交镜像） | **withdraw 或 deposit**（见 FR-008b） |
  未知标志 MUST fail-closed。忽略的重叠行 MUST 可计数审计，不得完全不可观测。

- **FR-008a（换汇 → swap）**: 系统 MUST 将「换汇」资金行配对为 **单笔** 多币种现金 `swap`：
  - 配对规则（research 锁定细节）：同结单内符号相反、币种不同的换汇行；允许交收日差（样本 ±1 日）；金额比率应落在合理 FX 区间时可记录于 note，但 **不得** 为配对而改写任一侧金额。
  - 映射：`action=swap`，`from_ticker`/`to_ticker` = 两现金 ticker 小写，`from_amount`/`to_amount` = 各腿 `|金额|`，`commission=0`，`currency` = 结单主展示币或 from 侧币种（research 锁定，全夹具一致）。
  - 无法配对 MUST fail-closed；MUST NOT 把换汇降级为独立 deposit/withdraw 双计或单边漏记。
  - 样本锚点：`HKD -3161.18` 与 `USD +402.32` 应配对为一笔 swap（比率约 7.86，仅审计）。

- **FR-008b（转账 → withdraw/deposit）**: 系统 MUST 将「转入到日内融账户」及其它转账类标志映射为既有动作，**不**引入 `transfer`：
  - 金额为负或出账语义 → `withdraw`（`from_amount = |金额|`，现金 ticker = 行币种小写）
  - 金额为正或入账语义 → `deposit`（`to_amount = |金额|`）
  - note/payload MUST 保留原始业务标志（如「转入到日内融账户」）以便审计
  - MUST NOT 创建子口袋 ticker、MUST NOT 新增投资事件 action
  - 本 feature **不**建模完整日内融子账户；期末现金以页眉 CHECKIN 为准对齐主账户结余

- **FR-009**: 导入 MUST 在流水后追加：
  1. 每个有数值的市场 **期末账户结余** → 一条现金 `checkin`（to_ticker=币种小写，to_amount=结余，date=结单月末）
  2. 持仓明细中每个持有数量 ≠ 0 的证券 → 一条持仓 `checkin`（股数对齐；无成本价则不写虚构 price/cost）  
  页眉「期初*」字段 MAY 用于校验但不强制生成 opening CHECKIN（除非 research 另选）。

- **FR-010**: `source_identity` MUST 稳定、跨文件一致，推荐配方（实施锁定于 research.md）：
  - 交易组：`usmart_hk:trade:{trade_date}:{ticker}:{side}:{qty}:{gross}:{net}:{ccy}`
  - 资金非交易：`usmart_hk:cash:{date}:{flag}:{ccy}:{amount}:{note_hash?}`
  - 现金 CHECKIN：`usmart_hk:checkin:cash:{statement_period}:{ccy}:{amount}`
  - 持仓 CHECKIN：`usmart_hk:checkin:pos:{statement_period}:{ticker}:{shares}`  
  碰撞合并不同业务事实视为 parser 缺陷。

- **FR-011**: 投资账户类型门禁、快照有限性验证、`raw_record_id` 审计链、单行 SWAP + commission 模型 MUST 复用 009；本 feature **不**引入独立 FEE/BUY/SELL/`transfer` 等新 action。

- **FR-012**: 双后端（PostgreSQL 与 SQLite）对相同 uSmart 夹具输入 MUST 产生等价投资结果（Constitution IV）。

- **FR-013**: 单元测试 MUST 使用 **去标识** 文本夹具（不得提交真实 PDF、真实姓名、真实地址、真实完整账号）；本地 `exports/` 仅开发校准。

- **FR-014**: 解析/映射失败 MUST 事务回滚、无 partial facts，错误信息含区段/上下文片段与建议（密码、工具、格式）。

- **FR-016（多币种现金投影）**: 系统 MUST 支持同一 security 账户内多现金 ticker（`hkd`/`usd`/`cny` 等）各自以 **本币** 为 `cost_currency`。跨币种现金 `swap`（换汇）MUST NOT 因单一 event `currency` 字段导致 `cost currency conflict`。权益腿仍以事件 `currency` 为成本币种（009）。现金↔现金时各腿面值入账。细则见 research D13。

### Key Entities

- **UsmartHkStatement**：一次月结单解析结果：结单期间、账户号（可脱敏）、各市场汇总（期初/期末净资产、市值、账户结余）、交易订单组列表、持仓行列表、资金出入行列表。
- **UsmartHkTradeGroup**：订单组：证券代码/名称、市场、方向（买/卖）、合并数量、币种、交易金额（gross）、变动金额合计（signed net）、费用明细、成交日/交收日、fill 列表（审计）。
- **UsmartHkCashMovement**：资金出入一行：业务标志、币种、金额、时间、备注、是否与交易重叠（忽略标志）。
- **InvestmentEvent / RawRecord / ImportBatch / LedgerSnapshot / Account**：同 009；本 feature 新增 source 取值 `usmart-hk` / `usmart_hk_pdf`。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 用户能在 5 分钟内完成首次 uSmart HK 月结单导入（建 security 账户、password-file、import、核对），无需手录成交。
- **SC-002**: 校准样本（2026-06 结构）导入后：USD 期末现金 = **4750.17**，HKD 期末现金 = **2021.09**（CHECKIN 后）；持仓股数 **00700.hk=100、mrvl.us=3、spcx.us=5**；权益费双计笔数 = **0**。
- **SC-003**: 同一业务行重复导入 novel 事件数 = **0**；重叠文件仅追加新 identity 行（010）。
- **SC-004**: PostgreSQL 与 SQLite 对同一去标识夹具导入结果 100% 业务字段一致（事件数、金额、ticker、CHECKIN）。
- **SC-005**: 交易订单组 100% 有对应 SWAP 或显式失败；无未计数的 silent skip。
- **SC-006**: 资金出入中成交镜像行 100% 不产生第二套事件；非交易映射表内标志 100% 按 FR-008 入账（换汇=swap，转账/日内融=withdraw|deposit，出金=withdraw）。
- **SC-007**: 导入失败路径（错密码、未知标志、费用不平衡、换汇无法配对、缺工具）100% 回滚且错误可操作（用户可据消息修复后重试）。
- **SC-008**: 校准样本中换汇配对后 USD/HKD 在 CHECKIN 后等于页眉期末结余；「转入到日内融账户」记为 withdraw（样本负额），无 `transfer` action、无虚构口袋 ticker。

## Dependencies

- **009-investment-account-import**：投资事件模型、InvestmentImportService、PDF 工具、CLI import 分支。
- **010-row-idempotent-import**：行级 `source_identity` 幂等（不得回退 file-digest 短路）。
- **005-multi-currency-accounts**：多币种 security 账户。
- **002-dual-database-runtime** / Constitution IV：双后端契约。
- **External**: qpdf、mutool（与 DFZQ 相同类别）。

## Out of Scope

- Connector 自动同步、凭据 vault、定时拉取（012）
- 实时行情与总市值估值（估值 feature）
- 投资关系 / lot / FIFO / 已实现盈亏
- 新投资事件 action（含曾讨论的 `transfer`）；转账只用 `withdraw`/`deposit`
- 日内融/融资融券完整子账户产品模型、保证金强平策略、利息计提引擎、子口袋 ticker
- 港股印花税/交易所征费的独立税种 peel（全部进 commission 合计即可）
- Web/MCP 导入 UI、多用户权限
- 其他券商（富途、老虎等）
- 将真实客户 PDF 或未脱敏导出提交版本库
- 修改 009 已完成 DFZQ/IBKR/Schwab **源映射**
- 现金账户 `transfer_pair` / 消费关系（006/007）— 与本投资导入无关

## Assumptions

- CLI 主名称 **`usmart-hk`**；文档与 `--help` 一致。
- 订单组一笔 SWAP（有「变动金额合计」）优于按 fill 拆分。
- 结单日期字段为 `YYYY-MM` 时，CHECKIN 使用该月最后一天；若仅有印单日则 research 可选用印单日但须全夹具一致。
- **换汇**用现金↔现金 `swap`；配对失败 fail-closed；**不得**发明汇率改金额。
- **转账/日内融**用 **`withdraw` / `deposit`**（按符号）；**出金**、**融资利息** 亦为 `withdraw`；note 区分业务标志。
- 样本月无股息/公司行动时，parser 仍须对未知标志 fail-closed，但不要求覆盖未出现标志的完整宇宙。
- 账户由用户预先创建 type=`security`；导入不自动创建账户、不改 base_currencies。
- 页眉「期末净资产 ≈ 期末账户结余 + 期末证券市值」可作为人工/校准 recon 容差 ≤ 0.02，不强制自动化阻断（除非 plan 选择强制）。

## Native field census (calibration reference)

| 区段 | 字段 | 样本用途 |
|---|---|---|
| 页眉 | 市场/币种 港股HKD 美股USD A股通CNY | 多币种结余/市值 |
| 页眉 | 期末账户结余 | 现金 CHECKIN |
| 页眉 | 期末证券市值 | recon / 非成本 |
| 交易明细 | 证券/编号、市场、买/卖、数量、币种、价格、金额、成交时间、交收日期 | fills |
| 交易明细 | 交易金额、变动金额合计、佣金、平台费、交收费等 | gross + fee 合同 |
| 持仓明细 | 证券、币种、持有数量、收市价、市值 | 股数 CHECKIN；无成本价 |
| 资金出入 | 业务标志、币种、金额、交易时间、备注 | 非交易资金；过滤成交镜像 |

### Fee contract (trade groups)

| Field | Role | In cash leg? | In commission? |
|---|---|---|---|
| 交易金额 | gross notional | **Yes** `abs` | No |
| 变动金额合计 | signed net cash | Derived only | Sizes commission via `\|gross − \|net\|\|` |
| 佣金+平台费+监管类费用 | fee stack | No (once in commission) | **Yes** (non-negative) |

Sample proofs:

- BUY MRVL: gross `1990.40`，net `-1994.32` → commission `3.92`；cash out `1994.32`
- SELL DELL: gross `3699.41`，net `+3695.41` → commission `4.00`；cash in `3695.41`
