# Feature Specification: 实时资产估值与持仓市值

**Feature Branch**: `017-asset-valuation-quote`

**Created**: 2026-07-25

**Updated**: 2026-07-25

**Status**: Implemented (pending converge / PR)

**Input（初始）**: 实时资产估值接口：统一 port + adapter；coverage/stale/unsupported；yfinance / Polymarket / crypto；非目标含历史序列、边界估值、收益率归因、Connector。编号 017。

**Living Spec（2026-07-25）**: 产品主目标调整为——**现有「查看持仓市值」路径必须消费本 feature 的统一估值结果与状态**。须同时支持：

1. **本币估值**：各持仓按各自计价（行情）货币估值，**不**折算；
2. **指定币种汇总估值**：各持仓先按本币估值，再统一折算为用户传入的展示货币。

按标识查询当前单价与状态保留为 **可复用原子能力**（供组合与后续系统调用），优先级低于组合市值主路径。

## Context

Phase 1 投资事实导入已可用，但组合/持仓查询仍走无状态的 `get_prices`，无法解释 stale/unsupported，也无法在「分币种市值」与「统一展示币种」之间给出可审计合同。

本 feature：

- 交付 **transport-neutral** 的统一估值原子能力（标识 + 类型 → 单价/状态）；
- 以 **组合持仓市值** 为 **P0 用户可见交付**（本币模式 + 可选指定展示币种折算）；
- 编号 **`017`**（实施权威）；路线图旧名 `011-asset-valuation-quote` 仅交叉引用。

**Extends / 关系**:

- **Implements**: 路线图 Phase 1「实时估值」+ 组合消费。
- **FX**: 折算可复用/扩展既有只读 FX 能力（如 Frankfurter 类 mid）；**不得**用汇率改写账本正式金额。
- **Does not supersede**: `003` 边界估值与财富 rebuild；本 feature 默认不写 `valuation_observations`。
- **Non-goals**: Connector 同步、历史 K 线、收益率归因、税务 lot。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 组合持仓：本币市值与状态 (Priority: P0) 🎯 主交付

作为多市场投资者，我打开持仓/组合视图时，希望每一笔非零持仓都通过 **统一估值** 得到 **本币单价、本币市值与数据状态**（complete/stale/partial/unsupported），现金腿单价为 1，这样我能按美元/人民币/港币等 **各自计价货币** 理解仓位，而不是静默空价或假 0。

**Why this priority**: 本 feature 的产品主目标；原子 quote 若不同时接到组合路径，用户仍看不到状态语义。

**Independent Test**: Fake 估值 + 含 USD 美股、CNY/A 股或现金、HKD 港股与不可识别 ticker 的快照；`get_portfolio`（或等价）每条持仓带 `quote_status`；成功项本币价/市值正确；失败项无虚构市价。

**Acceptance Scenarios**:

1. **Given** 组合含现金腿与可估值证券，**When** 以本币模式查询组合（不传展示币种，或明确 `display_currency=None`），**Then** 现金 `unit_price=1`、status=`complete`；证券本币单价与市值来自统一估值；每条持仓暴露 `quote_status`（及可选 reason）。
2. **Given** 持仓分属不同计价货币（如美股 USD、港股 HKD、A 股 CNY），**When** 本币模式查询，**Then** 各持仓 `quote_currency` / 市值币种保持其计价货币，**不**被折成单一币种。
3. **Given** 某 ticker 不可识别或源失败，**When** 查询组合，**Then** 该持仓无虚构市价，status 为 `unsupported` 或 `partial`，其余持仓不受影响。
4. **Given** 零股持仓，**When** 查询，**Then** 可不请求外部估值；不出现在非零列表或价格字段为空且不报错。

---

### User Story 2 - 组合持仓：折算为指定展示货币 (Priority: P0)

作为用户，我希望传入一个 **展示货币**（如 CNY），系统在 **本币估值成功** 的持仓上，用可审计的只读汇率将市值折成该货币，这样我能用单一数字比较全球仓位；折算失败时不得假装已折算。

**Why this priority**: 与 US1 同级产品能力；「分别按本币」与「统一指定币种」是同一主路径的两种模式。

**Independent Test**: Fake 估值 + Fake FX；传入 `display_currency=CNY`；断言本币字段仍保留，并增加展示币种市值/汇率字段；FX 缺失时展示市值为空且 status/reason 可区分（不得用 1:1 默默折算异币）。

**Acceptance Scenarios**:

1. **Given** 本币估值 complete/stale 且提供展示货币 C，**When** 查询组合，**Then** 对每条有本币市值的持仓：若计价货币=C，折算市值=本币市值、汇率=1；若计价货币≠C，使用只读 mid 汇率将本币市值转为 C（精确十进制）。
2. **Given** 本币估值成功但 FX 不可用，**When** 查询，**Then** 本币价/市值/状态仍返回；**展示币种市值缺失**；不得用错误汇率或 0 填充；对该项给出可区分 reason（如 `fx_unavailable`），组合级可汇总 partial。
3. **Given** 本币估值为 partial/unsupported，**When** 指定展示货币，**Then** 不进行折算，展示市值亦缺失。
4. **Given** 非法展示货币（非 3 字母等），**When** 查询，**Then** fail-closed（可操作错误），不返回部分假折算结果。
5. **Given** 同一假源与假 FX，**When** 在 SQLite 与 PostgreSQL 运行时查询，**Then** 本币与折算结果语义一致。

---

### User Story 3 - 原子能力：按标识查询单价与状态 (Priority: P1)

作为后续系统或其他 Application 的调用方，我希望用统一入口按 **资产标识 + 类型**（可选数量）查询当前单价与状态，作为组合估值的底层原子能力，而无需理解持仓快照。

**Why this priority**: 可复用基石；**不是**本 feature 的首要用户故事，但必须可测可注入，供 US1/US2 与后续 Web 调用。

**Independent Test**: 假源下四类资产单笔/批量；unsupported/partial 无虚构价。

**Acceptance Scenarios**:

1. **Given** 证券/加密/预测市场/现金标识与正确类型且源成功，**When** `quote`，**Then** 返回有限单价、报价币种、观测时间与 complete/stale（现金恒 complete、价=1）。
2. **Given** 不支持或源失败，**When** `quote`，**Then** unsupported 或 partial，无虚构单价。
3. **Given** 一批混合标识，**When** `quote_many`，**Then** 逐项结果、部分成功、顺序对齐。
4. **Given** 合法数量且有单价，**When** `quote`，**Then** 市值=单价×数量（本币）。

---

### Edge Cases

- 展示货币与计价货币相同 → 汇率 1，不访问 FX 源。
- FX 源返回非正/非有限 → 视为 FX 失败，不折算。
- 预测市场 outcome 价：本币语义为合约报价单位；折算到法币时 **仅当** 合同定义了其 `quote_currency`（v1 冻结为 USD 名义）且 FX 可得；否则可 partial 于展示腿。
- 标识空白、数量非法 → 原子 API fail-closed；组合路径跳过零股、对非法内部状态不得崩溃。
- 类型与标识矛盾 → 原子项 `unsupported`；组合推断 kind 使用确定性规则（见 plan/research）。
- 实时估值 **不写** 正式账本；重复查询不产生账务事实。
- 汇率 **不得** 回写成本或改写 historical 正式金额。

## Requirements *(mandatory)*

### Functional Requirements

#### 组合主路径（P0）

- **FR-001**: 系统 MUST 将既有组合/持仓查询（`PortfolioQueryService` 及同等账户市值聚合路径）迁移为消费统一估值，不得长期保留无状态静默 `get_prices` 作为唯一路径。
- **FR-002**: 本币模式下，每条非零持仓 MUST 暴露：本币单价（若有）、本币市值（若有）、`quote_currency`（计价货币）、`quote_status`（及可选 reason）；现金单价为 1、status=`complete`。
- **FR-003**: 本币模式 MUST NOT 将不同计价货币的市值默默加总为单一币种总数而不标注；若提供账户级「混合合计」，MUST 仅在同一币种内合计，或明确标为不完整。
- **FR-004**: 当调用方传入合法 `display_currency` 时，系统 MUST 在本币市值成功的持仓上尝试折算为该货币的展示市值，并保留本币字段以便审计。
- **FR-005**: 折算 MUST 使用只读 FX mid（可注入假源）；计价货币=展示货币时汇率为 1；FX 失败 MUST 使展示市值缺失并给出可区分原因，**禁止** 异币 1:1 默认。
- **FR-006**: 本币 partial/unsupported 的持仓 MUST NOT 产生展示币种市值。
- **FR-007**: 非法 `display_currency` MUST fail-closed。
- **FR-008**: 组合估值 MUST NOT 将实时价或汇率写入正式账本事实。

#### 原子估值能力（P1，支撑）

- **FR-009**: 系统 MUST 提供 Application 层 `quote` / `quote_many`：输入标识、类型、可选数量；输出状态与（complete/stale 时）有限精确十进制单价与报价币种。
- **FR-010**: 状态枚举 MUST 稳定包含：`complete`、`stale`、`partial`、`unsupported`（语义同前版：freshness / 最大窗 / 源失败 / 不支持矩阵）。
- **FR-011**: freshness 阈值 MUST 按类型确定性（crypto 更短；security/prediction_market 更长；cash 不 stale）；具体值见 plan。
- **FR-012**: 证券 MUST 做账本 ticker→外部符号的确定性映射（美股/港股/A 股）；加密仅已知表；预测市场 `pm:{slug}:{yes|no}`；现金价 1。
- **FR-013**: 批量 MUST 逐项部分成功；整批仅对输入级非法预校验失败。
- **FR-014**: 合法数量且有单价时 MUST 返回本币市值=单价×数量。
- **FR-015**: 领域与供应商 MUST 经 port 解耦；生产适配证券/加密/预测市场源。
- **FR-016**: 同一假源（及假 FX）下，PostgreSQL 与 SQLite 运行时用户可见估值语义 MUST 一致。
- **FR-017**: 金额与价格 MUST 为精确十进制；非有限值不得进入成功单价或成功汇率。
- **FR-018**: 外部源错误 MUST 落到状态语义，不得拖垮进程；日志不得泄露凭据与完整隐私账单。
- **FR-019**: 可选 CLI：持仓市值查询（本币/展示币）与/或原子 `quote`；Application 为唯一业务权威。

### Key Entities

- **AssetRef / QuoteResult / QuoteBatchResult**: 原子估值（见 data-model）。
- **PositionValuation**: 单持仓本币结果 + 可选展示币折算（市值、汇率、fx_status/reason）。
- **PortfolioValuationQuery**: 可选 `display_currency`；默认本币模式。
- **FxRateRef**: 只读 day/base/quote → mid；不入账。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 假源下，含至少两种不同计价货币的组合在本币模式一次查询中，100% 非零可估值持仓带有正确本币价/市值/status；不可估值项 100% 无虚构市价且 status 可区分。
- **SC-002**: 假估值+假 FX 下，指定展示货币时：可折算项展示市值 = 本币市值 × 汇率（精确）；计价=展示时汇率 1；FX 失败项 100% 无展示市值且无 1:1 默折。
- **SC-003**: 原子 `quote` 四类资产成功路径与 unsupported/partial 合同在自动化中 100% 满足（支撑 US1/US2）。
- **SC-004**: 批量原子混合 10 项隔离正确率 100%。
- **SC-005**: SQLite 与真实 PostgreSQL 下同一假源/假 FX 组合结果语义一致。
- **SC-006**: 交付后组合路径不再以「无 status 的静默空价」作为唯一表现。
- **SC-007**: 无历史序列、无账本写入、无 Connector；范围审查 0 越界。

## Assumptions

- 持仓标识与账户 `base_currencies` / 成本币种来自既有投资模型；组合侧用确定性规则推断 `AssetKind`（plan 写死表）。
- 证券本币报价币种：由行情适配器在可知时给出 ISO；未知时不得假装 CNY。
- 加密 v1 报价币种 USD；预测市场 v1 `quote_currency=USD`（合约价名义锚，便于可选折算）。
- FX 默认「当前/今日」业务日 mid（时区在 plan 固定，建议与现有 FX helper 一致可测）；不要求历史任意日持仓回溯。
- 原子 quote **不做** 展示币折算；折算仅组合（或显式 portfolio valuation）路径。
- CI 默认假源/假 FX；真网可选。

## Non-Goals

- 财富归因边界选价、immutable generation、投资收益率。
- Connector / 自动同步写账。
- 用汇率重写成本基础或正式流水。
- 多用户鉴权、付费行情账号体系、通用缓存平台。
- 自动 checkin 市价入账。

## Dependencies

- 投资持仓与现金 ticker（`009+`）。
- 双后端选择（`002`）。
- 只读 FX 适配可基于现有 `fx_rates` 思路扩展为「今日 mid + 可注入」。
