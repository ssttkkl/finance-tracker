# Feature Specification: 实时资产估值接口

**Feature Branch**: `017-asset-valuation-quote`

**Created**: 2026-07-25

**Status**: Draft

**Input**: User description: "实时资产估值接口：输入资产标识与类型（股票/加密/预测市场/现金），输出当前实时估值（单价及可选市值）；统一 port + adapter；含 coverage/stale/unsupported 状态。恢复 yfinance（HK/US ticker 规范化）、Polymarket gamma-api、crypto 三类取价。非目标：历史时间序列、期初/期末边界估值、投资收益率归因、Connector。编号使用下一个可用 sequential（016 之后，勿用 011）。"

## Context

Phase 1 投资事实导入（`009` 及后续券商/行幂等/字段收口）已在 `refactor/web` 可用，但产品化路线图要求的 **正式实时估值接口** 尚未以 Spec Kit feature 交付。现有 `MarketDataProvider.get_prices` 仅为组合查询的内部取价适配，缺少：

- 统一的资产标识与类型模型；
- 可测试的 Application Service / port 合同；
- 对调用方可见的 **coverage / stale / unsupported**（及与财富域一致的状态语义）；
- 明确的成功/失败/部分结果合同。

本 feature 交付 **transport-neutral** 的实时估值能力，供组合查询、后续只读账单 Web（路线图 Phase 2）等消费方复用。编号使用 **`017`**，避免与已完成的 `011-usmart-hk-import` 及路线图旧名 `011-asset-valuation-quote` 混淆；在 Context 中作为该路线图能力的正式实现入口。

**Extends / 关系**:

- **Implements（路线图）**: `docs/productization-refactor-plan.md` Phase 1 条目「实时估值接口」（文中仍可能写作 `011-asset-valuation-quote`；以本目录为实施权威）。
- **Consumes / 规范化参考**: 既有投资持仓标识习惯（证券 ticker、加密代码、`pm:` 预测市场、现金 ticker）。
- **Does not supersede**: `003-wealth-attribution-core` 的边界估值、`valuation_observations` 历史观测与财富 coverage 重建；本 feature 不写入财富读模型。
- **Non-goals overlap**: Connector 自动同步仍归独立延后 feature（路线图 connector 条目，勿与本 `017` 混淆）。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 按标识查询当前单价与状态 (Priority: P1)

作为同时持有证券、加密与预测市场仓位的用户（或消费组合/报表的调用方），我希望用统一入口、按资产标识与类型查询 **当前单价** 及 **数据状态**，这样我能区分「可信现价」「过期价」「不支持」与「暂时取不到」，而不是只得到空数字或静默失败。

**Why this priority**: Phase 1 关门与 Phase 2 可选市值展示的硬依赖；没有状态语义的取价不能进入可审计产品表面。

**Independent Test**: 对已知夹具标识（mock 外部源）分别请求证券、加密、预测市场与现金；断言返回单价（精确十进制）、报价币种、观测时间与状态；对未知/不支持标识返回 `unsupported` 且无虚构价格。

**Acceptance Scenarios**:

1. **Given** 调用方提供证券类资产标识（如美股裸代码或已规范化的港股/A 股形式）且外部源返回有效最新价，**When** 请求实时估值，**Then** 系统返回有限精确十进制单价、报价币种、时区感知的观测时间，状态为 `complete`（在 freshness 窗口内）或 `stale`（超出 freshness 但仍在最大可用窗内，见 FR）。
2. **Given** 调用方提供已支持的加密资产标识且外部源返回有效价，**When** 请求实时估值，**Then** 返回单价与状态，语义同证券（freshness 阈值可按资产类型不同）。
3. **Given** 调用方提供预测市场标识（`pm:{slug}:{yes|no}` 形态）且外部源返回对应 outcome 价格，**When** 请求实时估值，**Then** 返回该 outcome 的单价（通常为 0–1 概率价）与状态。
4. **Given** 调用方提供现金类标识（账户基础货币或持仓中的现金 ticker），**When** 请求实时估值，**Then** 不依赖外部行情源，单价为 `1`，报价币种为该现金本身，状态为 `complete`。
5. **Given** 标识属于系统明确不支持的类型或无法路由到任何估值能力，**When** 请求，**Then** 状态为 `unsupported`，**不得**编造单价；错误信息可操作（说明标识与类型）。
6. **Given** 标识可识别但外部源超时、空响应或不可解析，**When** 请求，**Then** 状态为 `partial`（单笔场景表示「已知资产但本次无可靠价」），**不得**用 `0` 冒充市价。

---

### User Story 2 - 批量估值与部分成功 (Priority: P1)

作为组合或列表视图的调用方，我希望一次提交多个资产标识并得到 **逐项结果**，这样部分资产失败不会拖垮整批，我仍能展示有价的仓位并标明缺口。

**Why this priority**: 真实组合几乎总是多资产；全有或全无会迫使调用方串行降级。

**Independent Test**: 一批请求中混合「可成功」「unsupported」「源失败」三类标识；断言每项独立状态，成功项有价，失败项无虚构价，整批调用本身不因单项失败而抛成「全部失败」（除非输入整体非法）。

**Acceptance Scenarios**:

1. **Given** 一批含至少一种成功、一种 `unsupported`、一种源失败的标识，**When** 批量估值，**Then** 返回与输入一一对应（或可按标识对齐）的结果列表；成功项 `complete`/`stale` 带价；其余项无价格数值。
2. **Given** 批量中全部标识均 `unsupported` 或全部源失败，**When** 批量估值，**Then** 仍返回逐项结果（全为对应失败状态），不抛未文档化的内部异常给调用方。
3. **Given** 输入列表为空，**When** 批量估值，**Then** 返回空结果集，状态可判定为成功的空批。

---

### User Story 3 - 可选数量得到市值 (Priority: P2)

作为需要展示持仓市值的调用方，我希望在已知单价的前提下传入数量，获得 **市值 = 单价 × 数量**（精确十进制），这样 UI 不必各自实现乘法与舍入。

**Why this priority**: 降低 Web/CLI 重复逻辑；非查询单价所必需，故为 P2。

**Independent Test**: 同一标识分别不带数量与带数量请求；带数量时市值等于单价×数量；数量非法时 fail-closed。

**Acceptance Scenarios**:

1. **Given** 某资产估值状态为 `complete` 或 `stale` 且提供有限非负数量，**When** 请求，**Then** 返回市值 = 单价 × 数量（精确十进制），币种与单价报价币种一致。
2. **Given** 估值状态为 `unsupported` 或 `partial`（无单价），**When** 仍传入数量，**Then** 不返回市值数值（市值缺失与单价缺失一致）。
3. **Given** 数量为 NaN、无穷、或非法字符串，**When** 请求，**Then** 该请求 fail-closed（可操作错误），不写入任何账本事实。

---

### User Story 4 - 既有组合查询消费统一估值 (Priority: P2)

作为使用组合/持仓查询的用户，我希望现有「查看持仓市值」路径改为消费本 feature 的统一估值结果与状态，这样持仓列表能暴露 stale/unsupported，而不是静默 `price=None` 且无解释。

**Why this priority**: 避免双轨取价；但本 feature 的 MVP 可先交付独立估值服务，再切换消费方。

**Independent Test**: 在 mock 估值 port 下加载含证券/加密/现金的组合快照；断言持仓项价格与状态来自统一估值，现金为 1，不支持的 ticker 带 `unsupported`。

**Acceptance Scenarios**:

1. **Given** 组合中含现金腿与可估值证券，**When** 查询组合，**Then** 现金现价为 1 且 complete；证券现价与状态来自统一估值接口。
2. **Given** 组合中含不可识别 ticker，**When** 查询组合，**Then** 该持仓不显示虚构市价，并带有可区分的 unsupported（或等价对外状态），其余持仓不受影响。

---

### Edge Cases

- 标识仅空白、类型与标识明显矛盾（例如声明 `cash` 却给 `pm:…`）→ fail-closed 或 `unsupported`，不得猜测改写类型后静默成功。
- 同一标识在批量中重复出现 → 每项独立结果或去重策略必须文档化且确定；默认允许重复并返回相同语义结果。
- 外部源返回非有限数值（NaN/Inf）→ 视为源失败 → `partial`，不得透传非有限十进制。
- 证券 ticker 的 HK/US/A 股书写变体 → 必须经确定性规范化后再请求外部源；规范化失败 → `unsupported`。
- 加密标识不在已知映射表 → `unsupported`（v1 不自动猜测 CoinGecko id）。
- 预测市场 slug 不存在或 outcome 侧缺失 → `partial` 或 `unsupported`（源明确无此市场为 unsupported；临时故障为 partial）；不得用 0.5 等默认概率。
- 进程通过 `FT_DATABASE_URL` 选择 PostgreSQL 或 SQLite 时，**估值结果与状态语义必须一致**（本能力不依赖后端方言）；允许的运行差异仅限网络/外部源可用性，不得因后端选择改变价格算法。
- 本 feature **默认不将实时价写入** 正式账本或 `valuation_observations`；重复查询不产生重复账务事实。
- 报价币种与调用方期望展示币种不一致时，v1 **不强制**做 FX 折算（见 Assumptions）；不得假装已折算。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 提供统一的实时估值能力（Application 层入口），接受资产标识、资产类型，并可选接受数量与请求上下文（如「截至」时刻默认「现在」）。
- **FR-002**: 系统 MUST 支持至少以下资产类型：`security`（股票/ETF 等可规范化交易所标识）、`crypto`、`prediction_market`、`cash`。
- **FR-003**: 对每次估值结果，系统 MUST 返回：状态、资产标识、资产类型；当且仅当状态为 `complete` 或 `stale` 时包含有限精确十进制单价与报价币种；可选包含观测时间（时区感知）。
- **FR-004**: 状态枚举 MUST 至少包含并对外稳定使用：`complete`、`stale`、`partial`、`unsupported`。语义：
  - `complete`：获得可信现价且观测时刻在类型对应的 freshness 窗口内；
  - `stale`：获得价但观测时刻超出 freshness、仍在最大可用窗口内；
  - `partial`：资产可识别但本次无可靠价（源故障、空数据、非有限值等），或批量中需表达「缺价」；
  - `unsupported`：标识/类型不在支持矩阵内，或规范化后无法路由到任何估值路径。
- **FR-005**: freshness / 最大可用窗口 MUST 按资产类型定义且确定性；默认与财富域历史估值新鲜度对齐意图：`crypto` 更短，`security` / `prediction_market` 更长；`cash` 不因时间变为 stale。具体阈值在 plan 中写死并测试。
- **FR-006**: 证券路径 MUST 对美股、港股、A 股标识做确定性规范化（美股不保留错误 `.US` 后缀；港股规范化为约定位数+`.HK`；A 股交易所后缀大写等），再请求外部行情能力。
- **FR-007**: 加密路径 MUST 仅对明确支持的代码映射取价；未映射代码 → `unsupported`。
- **FR-008**: 预测市场路径 MUST 解析 `pm:{slug}:{yes|no}`（大小写策略确定性），取对应 outcome 价；禁止默认中间价。
- **FR-009**: 现金路径 MUST 单价为 `1`，状态 `complete`，不调用外部行情。
- **FR-010**: 当提供合法数量且存在单价时，系统 MUST 返回市值 = 单价 × 数量（精确十进制，无二进制浮点）；数量非法 MUST fail-closed。
- **FR-011**: 批量接口 MUST 逐项返回结果，单项失败不得删除其他项的成功结果。
- **FR-012**: 实时估值 MUST NOT 作为正式账本事实写入；MUST NOT 实现历史时间序列 API、期初/期末边界估值选择、投资收益率归因或 Connector 同步。
- **FR-013**: 领域规则 MUST 与具体 HTTP 供应商 SDK 解耦：通过 port 注入行情源，便于测试用假源替换；生产适配器承载 yfinance 类证券源、crypto 源、Polymarket gamma 类源。
- **FR-014**: 在 PostgreSQL 与 SQLite 任一运行时选择下，同一假源输入 MUST 得到相同估值 DTO 与状态（双后端等价；本 feature 无后端专用价格表）。
- **FR-015**: 既有组合/持仓查询路径 MUST 迁移为消费本估值能力（或等价适配），不得长期保留第二套无状态语义的静默取价作为唯一路径。
- **FR-016**: 金额与价格 MUST 使用精确十进制语义；非有限值不得进入成功单价。
- **FR-017**: 外部源错误 MUST 失败关闭到状态语义（`partial`/`unsupported`），不得抛出未捕获异常导致进程崩溃；不得在日志中泄露凭据或完整隐私账单。
- **FR-018**: 若提供面向用户的 CLI 查询，MUST 展示单价、币种与状态；该 CLI 为可选便利，Application 合同仍是唯一业务权威。

### Key Entities

- **AssetRef（资产引用）**: 资产标识字符串 + 资产类型；可选数量；标识在类型内应唯一解释。
- **QuoteResult（估值结果）**: 对应一次 AssetRef 的状态、单价、报价币种、观测时间、可选市值、可选人类可读原因码（如 `unsupported_identity`、`provider_error`）。
- **QuoteBatchResult（批量结果）**: 有序列表或与输入对齐的结果集合；可含批级摘要（成功数/失败数）但不替代逐项状态。
- **QuoteProvider（行情提供方 port）**: 按类型解析并返回原始价与观测时间；不负责账本事务。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 在使用可重复假源的自动化验收中，四类资产（证券/加密/预测市场/现金）各自至少 1 条成功路径在 1 次调用内返回正确单价与 `complete`（现金）或 `complete`/`stale`（其余）。
- **SC-002**: 对不支持标识，100% 的自动化用例返回 `unsupported` 且不含虚构单价；对源故障用例，100% 返回 `partial` 且不含 `0` 冒充市价。
- **SC-003**: 批量 10 项混合成功/失败夹具中，成功项与失败项状态隔离正确率 100%（无交叉污染）。
- **SC-004**: 合法数量场景下市值与「单价×数量」一致率 100%（十进制精确比较）。
- **SC-005**: 切换 `FT_DATABASE_URL` 至 SQLite 与真实 PostgreSQL 时，同一假源估值结果字节级字段语义一致（状态、金额字符串化约定、类型）。
- **SC-006**: 组合查询在接入后，对 mock 组合中不可识别 ticker 不再表现为「无字段可区分的静默空价」——调用方能读到明确 unsupported（或文档化等价对外字段）。
- **SC-007**: 本 feature 交付物不包含历史序列端点、边界估值写入或 Connector 同步；范围审查清单 0 项越界。

## Assumptions

- 用户与调用方已能通过既有导入得到持仓标识；本 feature 不负责纠正错误导入的 ticker。
- v1 **不**将实时价折算为任意展示币种；返回源报价币种（加密常见 USD；预测市场为合约报价单位；证券为行情源币种；现金为自身）。后续 FX 展示可另开 feature。
- v1 加密仅覆盖项目已维护的已知代码映射表；扩表可作为本 feature 内小步或后续 living 增补。
- 实时估值默认 **不落库**；与 `003` 的 `valuation_observations` 无写入耦合。
- 外部真实网络在 CI 中默认不依赖：自动化以假源/录制夹具为主；可选标记的网络测试不阻塞合并门禁。
- 「观测时间」在源未提供时，允许使用「成功取得响应的时刻」，并在 plan 中固定，避免状态抖动无定义。
- 路线图文中的 `011-asset-valuation-quote` 与本 `017` 指同一产品能力；实施与任务以 `specs/017-asset-valuation-quote/` 为准。

## Non-Goals

- 历史 K 线、区间收益、XIRR、税务 lot。
- 财富归因期初/期末边界选价与 immutable generation（`003`）。
- Connector / ccxt / Polymarket 活动同步写账。
- 多用户鉴权、托管行情缓存平台、付费行情源账号体系。
- 自动把实时价写成账本 checkin 或覆盖成本。

## Dependencies

- 投资持仓与现金 ticker 约定来自既有投资/账户模型（`009+`）。
- 运行时双后端选择来自 `002`；本 feature 不新增跨后端迁移。
- 状态用词与财富域公开状态对齐，降低 Phase 2/3 UI 认知负担，但不要求本 feature 调用财富 Application Service。
