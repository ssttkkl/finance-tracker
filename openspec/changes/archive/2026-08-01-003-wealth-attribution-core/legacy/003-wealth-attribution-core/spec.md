# Feature Specification: Wealth Attribution Core

**Feature Branch**: `codex/wealth-attribution-core`

**Created**: 2026-07-19

**Status**: Complete

**Input**: User description: "实现 wealth-attribution-core spec"

## Context

Finance Tracker 已具备 PostgreSQL 与 SQLite 双数据库运行时、稳定 workspace/account identity、现金与投资事实、导入来源追踪和修订信息，但尚不能回答“这一段时间净资产为什么变化”。本 feature 建立唯一的财富归因内核：以规范每日桶为原子结果，用同一套口径生成自然月 breakdown 和日/周/月 series，并让每个金额、缺口和状态都可追溯到证据。

本 feature 只交付领域模型、Application Service、repository/query ports、双数据库读模型与可执行契约，不交付 HTTP API、Web 页面、认证、关系审查列表、Connector、AI 或 MCP。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Explain a Complete Wealth Change (Priority: P1)

作为拥有完整账户、持仓、行情和汇率事实的 workspace 使用者，我希望查看一个自然月或时间序列桶的期初净资产、期末净资产与六项变化来源，以便确认财富变化恒等式并区分外部收支、投资市场收益、FX、负债重估、已解释调整和未解释残差。

**Why this priority**: 这是财富解释产品的核心价值；没有可信的完整归因，其余状态、聚合和证据能力都没有可用基础。

**Independent Test**: 使用纯现金流、现金加投资、多币种与负债的 golden fixtures 查询自然月 breakdown 和每日 series；每个结果均能独立验证规范恒等式、符号、金额精度、收益率与 component identity。

**Acceptance Scenarios**:

1. **Given** 一个 CNY workspace 在完整自然月内拥有完整边界估值和事实，**When** 查询该月 breakdown，**Then** 返回期初、期末、六个规范组成项、解释比例和 complete 状态，且未舍入恒等式严格成立。
2. **Given** 工资、消费退款、内部转账、投资入出金、股息和交易费同时存在，**When** 归因该区间，**Then** 每类事件只进入规范组成项一次，内部转账不改变 workspace 净资产，投资入出金不被误算为市场收益。
3. **Given** 非 CNY 投资、外币现金或外币负债，**When** 归因投资的计价币种市值、外币现金与负债的原币价值及期间流量，**Then** 投资收益与 FX 按固定顺序分解，负债绝对值上升降低净资产，公式两侧保持一致。
4. **Given** 任一完整自然月，**When** 分别查询 monthly series point 与 month breakdown，**Then** opening、closing、六项组成、status、coverage、component identity 和 evidence 集合完全一致。

---

### User Story 2 - Expose Incomplete or Unsupported Coverage Honestly (Priority: P1)

作为数据并不完整的 workspace 使用者，我希望系统明确区分 complete、stale、partial 和 unsupported，并展示已知部分、排除项和缺失证据，而不是把部分资产伪装成完整净资产。

**Why this priority**: 财务可信度要求失败关闭；错误的完整数字比没有数字更危险。

**Independent Test**: 使用缺失边界估值、过期行情、缺失 FX、不支持持仓、完全缺少可配对边界账户和 coverage 变化 fixtures，验证 nullable 完整字段、known 恒等式、warning、excluded item、状态传播和不可构造错误。

**Acceptance Scenarios**:

1. **Given** 一个已知账户或持仓缺少任一边界估值，**When** 生成结果，**Then** 完整 opening、closing、六项组成与 explained ratio 返回 null，结果返回 partial/unsupported 的 known 字段、coverage、excluded items 与缺口证据。
2. **Given** 估值超过 freshness threshold 但未超过 maximum usable age，**When** 生成结果，**Then** 金额仍参与计算且 component/report 标记 stale；超过 maximum usable age 时按缺失处理并标记 partial。
3. **Given** 不支持的资产或事件，**When** 生成结果，**Then** 系统不猜测估值，报告状态为 unsupported，相关事实按闭包规则排除，并提供稳定的 unsupported evidence。
4. **Given** 没有任何账户同时具备两个边界，**When** 查询结果，**Then** 失败关闭并返回 `REPORT_NOT_CONSTRUCTIBLE`，不返回伪造的零值或部分总额。
5. **Given** 相邻日期的同一预期 identity 由 supported 变为 missing/unsupported/unvalued，**When** 查询 series，**Then** 后一 point 标记 `comparable_to_previous=false`、产生 `COVERAGE_CHANGED`，且跨断线变化不进入归因或收益率。

---

### User Story 3 - Compare Daily, Weekly, and Monthly Trends (Priority: P2)

作为需要比较财富趋势的使用者，我希望查询最多 366 天的日、ISO 周或自然月序列，并确信所有聚合都来自同一份规范每日桶，不存在另一套报表算法。

**Why this priority**: 趋势比较是后续只读 Web 报告的主要输入，但必须建立在 P1 的单桶归因与缺口语义之上。

**Independent Test**: 生成连续 366 天 fixtures，将每日结果聚合为周/月并与服务结果比较；同时覆盖不完整首尾周期、缺失日期、partial、stale、unsupported 和 coverage 变化。

**Acceptance Scenarios**:

1. **Given** coverage 连续且每日字段完整，**When** 聚合为周或月，**Then** opening 等于首日 opening、closing 等于末日 closing、各组成项等于每日之和、状态为每日最严重状态，且净资产不被平均。
2. **Given** 查询范围切到不完整周或月，**When** 返回聚合 point，**Then** point 使用实际范围边界并标记 `is_partial_period=true`。
3. **Given** 任一天缺少 DailyWealthPoint，**When** 查询包含该日的序列，**Then** 返回显式空档、partial 状态和 `DAILY_POINT_MISSING`，不推断或插值。
4. **Given** 任一天发生 coverage 变化或必需完整字段为 null，**When** 聚合其周/月，**Then** change、组成项和投资收益率按规则返回 null，并保留可用的断线端点。
5. **Given** 超过 366 天或非法范围/粒度，**When** 查询 series，**Then** 以稳定错误拒绝请求，不静默截断。

---

### User Story 4 - Audit Components and Immutable Evidence (Priority: P2)

作为需要复核数字的使用者或审计者，我希望每个规范组成项都有稳定逻辑 identity、版本化结果 identity 和可分页证据，且历史结果不会被后续重建悄悄改写。

**Why this priority**: 可审计性是财务产品的不可妥协原则，也是后续 Web 下钻的必要契约。

**Independent Test**: 对同一 source revision 重建两次并在事实更正后再次重建，验证 component key 稳定、result/component revision 更新、旧 evidence 仍可读取、排序与分页稳定，聚合 evidence 能核对到金额。

**Acceptance Scenarios**:

1. **Given** 相同 workspace、period、granularity、kind 和 grouping identity，**When** 对相同 revision 重建，**Then** component key、component ID、canonical DTO 和 evidence 顺序完全确定且幂等。
2. **Given** 迟到事实或更正改变 source revision，**When** 重建受影响范围，**Then** component key 保持稳定、result revision 与 component ID 改变，旧 result/evidence 仍为不可变历史。
3. **Given** 周/月 component 聚合多个每日 component，**When** 读取其 evidence，**Then** 返回按稳定 identity 去重的并集，金额能核对到聚合 component，排序为 occurred_at、source identity、evidence kind。
4. **Given** 缺失、过期、冲突或不支持数据，**When** 读取 component evidence，**Then** 缺口本身以与事实证据相同的合同被分页和审计。

---

### User Story 5 - Rebuild and Serve a Revision-Safe Read Model (Priority: P3)

作为本地运行 Finance Tracker 的使用者，我希望财富序列缓存可以安全重建并在 PostgreSQL 与 SQLite 上返回相同业务结果；失败的重建不得发布半成品或混合新旧日期。

**Why this priority**: 这是性能、迟到事实修正和双数据库正式支持的基础，但不改变前四个故事的财务语义。

**Independent Test**: 在两个后端运行同一 golden/contract/rebuild 矩阵，注入中途失败和并发读取，验证活动 manifest 原子切换、旧 generation 保留、canonical DTO parity、无自动回退或双写。

**Acceptance Scenarios**:

1. **Given** 从最早受影响日期到当前日期的成功重建，**When** 全保留期索引完成，**Then** active manifest 一次性切换到单一 build revision，未受影响历史可内容寻址复用。
2. **Given** 重建中途失败，**When** 查询活动序列，**Then** 仍只读取上一完整 generation，不出现部分新 generation 或跨 revision 混合。
3. **Given** 相同事实、修订和查询分别运行在 PostgreSQL 与 SQLite，**When** 比较规范化结果，**Then** breakdown、series、component/evidence、错误合同和事务可见性等价。
4. **Given** 一个包含 10 个账户、50 个持仓、100,000 条事实和 366 个每日桶的基准 workspace，**When** 进行冷查询和有效缓存命中，**Then** 在开发者级笔记本上分别满足 p95 小于 5 秒和 300 毫秒。
5. **Given** 构建开始后又有事实或估值修订到达，**When** 旧构建尝试发布，**Then** 构建使用开始时捕获的不可变 source watermark，若 active manifest 已由更新构建推进则旧构建被拒绝且不得回退活动指针。

### Edge Cases

- 区间净资产变化接近零但正负贡献互相抵消时，explained ratio 使用稳定分母且限制在 0 到 1。
- API 展示精度舍入造成可见差额时，差额记录为 reason=`rounding` 的 explained other evidence，不引入新的顶层组成项。
- check-in 与完整事件回放同时存在且差异超过 `max(CNY 10, 边界价值的 0.1%)` 时，check-in 仍为边界事实，产生 `VALUATION_CONFLICT`，不自动修正历史。
- 外部资金直接进入投资账户时，同一事件同时是 workspace external cashflow 与 portfolio flow，但不会重复计入总财富变化。
- portfolio 内买卖、股息再投资、账户内换汇和资产换仓不属于 portfolio external flow；交易费用降低投资收益。
- 日内跨币种换汇以配对 Dietz flows 进入各币种资本，workspace 净 external flow 为零。
- 任一有实质敞口币种的 Dietz capital 不大于零、缺少日初 FX、边界估值缺失或 portfolio 为 partial/unsupported 时，该日投资收益率为 null。
- 周/月收益率对每日有效收益率链式连接；任一天收益率为 null 时周期收益率为 null，不以剩余日期冒充完整周期。
- 账户生命周期外的 identity 为 not_applicable，不触发 coverage 断线；真实缺失、unsupported 或 unvalued 才改变 coverage fingerprint。
- 关联价值流一侧支持、另一侧不支持时按闭包排除；无法拆分的已知影响进入 excluded coverage adjustment，不进入 unexplained。
- PostgreSQL 与 SQLite 可以有锁实现、错误原文、部署方式和性能差异，但 canonical result、事务原子性、幂等、来源审计和稳定错误类别不得分叉。
- 任何进程仅使用 `FT_DATABASE_URL` 显式选择一个后端；禁止自动探测、静默回退、双写或隐式跨后端迁移。
- evidence cursor 必须绑定不可变 component ID/result revision 与 ordering version；发生相同时间、来源和种类时以 evidence identity 作为最终排序键，重复 source 在同一 result scope 内按稳定 contribution fold 去重。
- 账户拥有的现金/持仓 coverage identity 必须显式包含稳定 owner account ID；缺少或冲突的 ownership 不得通过名称、ticker 前缀、当前 snapshot 或当前 active 状态猜测，必须 fail closed 为 unsupported 并产生稳定 gap evidence。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 以 Asia/Shanghai 本地自然日 `[00:00, 次日 00:00)` 生成规范 DailyWealthPoint，并将其作为 breakdown 和所有 series 粒度的唯一原子计算结果。
- **FR-002**: 系统 MUST 使用 CNY 作为本 feature 的基础币种，并返回 `calculation_version=wealth-attribution-v0.1` 与显式 `valuation_policy_version`；调用方不得覆盖未知版本。
- **FR-003**: 系统 MUST 使用精确 Decimal 保存和计算金额、汇率、资本权重与收益率；中间计算不得舍入，canonical 金额只在展示边界按币种精度输出无指数十进制字符串。
- **FR-004**: 系统 MUST 满足 `closing - opening = external_cashflow + investment_return + fx_impact + liability_revaluation + explained_other_adjustment + unexplained_adjustment`，所有符号以增加净资产为正。
- **FR-005**: 系统 MUST 按规范事件表分类工资、消费、退款、内部转账、投资入出金、股息、利息、费用、已知修正和残差，并保证同一经济价值不被重复计入。
- **FR-006**: 系统 MUST 对非 CNY 投资按期末 FX 固定顺序分解计价币种市场收益和 FX 影响，并对外币现金、外币负债应用相应 FX 公式；公式必须在未舍入精度上闭合。
- **FR-007**: 系统 MUST 对负债使用负余额净资产语义；借款本金发放/偿还是内部流量，明确利息/费用为负 external cashflow，非现金本金修正为 liability revaluation。
- **FR-008**: 系统 MUST 按已定义分母计算 explained ratio，并返回 0 到 1 的规范 Decimal；unexplained 金额必须独立返回且不改变 complete/stale/partial/unsupported 状态。
- **FR-009**: 系统 MUST 使用 boundary check-in 优先、完整事件与合格行情确定性回放次之的估值顺序；两者均不可用时不得估算。
- **FR-010**: 系统 MUST 对上市证券/FX 使用 5 日 freshness 与 30 日 maximum usable age，对加密资产使用 24 小时 freshness 与 7 日 maximum usable age，并按阈值传播 stale 或 partial。
- **FR-011**: 系统 MUST 只支持现金、银行存款、普通借贷、现货多头股票/ETF、现货加密资产及规范事件白名单；空头、期权、保证金、复杂公司行动、锁仓/质押衍生收益和未结算预测市场头寸 MUST 为 unsupported。
- **FR-012**: 任一已知账户/持仓缺少边界估值时，系统 MUST 将完整净资产、完整组成与完整 explained ratio 返回 null，并返回可审计的 known fields、coverage、excluded items 和 excluded coverage adjustment。
- **FR-013**: 系统 MUST 对已覆盖事实集合维持 `known_closing - known_opening = sum(known_components) + excluded_coverage_adjustment + known_unexplained_adjustment`，且完整字段和 known 字段不得混加。
- **FR-014**: 如果没有任何账户同时具备两个边界，系统 MUST 返回稳定的 `REPORT_NOT_CONSTRUCTIBLE` 错误并且不发布结果。
- **FR-015**: 系统 MUST 为 expected coverage universe 中每个 account/instrument identity 在每个本地日期记录 supported、missing、unsupported、unvalued 或 not_applicable disposition，并据此生成与查询范围无关的 coverage fingerprint。
- **FR-016**: 同一预期 identity 的 coverage disposition 发生真实缺失/不支持/不可估值变化时，系统 MUST 产生 `COVERAGE_CHANGED`，将后一 point 标记不可与前一点比较，并禁止跨变化计算 change、组成项或收益率；生命周期外 not_applicable 不触发变化。
- **FR-017**: 系统 MUST 接受 inclusive `date_from`、exclusive `date_to` 与 day/week/month 粒度，最长 366 天；非法范围、超限或非法粒度分别返回稳定错误，不得截断。
- **FR-018**: 系统 MUST 以 ISO Monday-Sunday 聚合周、以自然月聚合月；不完整首尾周期必须返回并标记 `is_partial_period=true`。
- **FR-019**: coverage 连续且字段完整时，周/月 opening 取首日、closing 取末日、组成项与 unexplained 求和、状态取最严重每日状态；不得平均净资产或另写一套归因算法。
- **FR-020**: 缺少每日 point、coverage 变化或必需完整字段缺失时，系统 MUST 按规范传播 null、known fields、warning、status 和断线端点；不得插值、跳过坏日或把剩余日冒充完整周期。
- **FR-021**: 系统 MUST 计算 daily Modified Dietz linked return：先按各币种计算，再用日初 FX 固定换算资本权重汇总；外部资金只指 investment universe 边界流量，FX 影响不得进入收益率。
- **FR-022**: 任一实质敞口币种 capital 不大于零、总加权资本不大于零、缺少日初 FX、边界缺失或 portfolio partial/unsupported 时，系统 MUST 返回 null 收益率；周/月使用 `product(1+daily_rate)-1`，任一天 null 则周期为 null。
- **FR-023**: 每个完整或 known 公式组成项 MUST 返回固定 kind 顺序的 component，包含稳定 component key、版本化 component ID、result revision、金额、状态和 transport-neutral immutable evidence reference；核心不得生成 HTTP URL 或状态码。
- **FR-024**: component key MUST 由 workspace identity、规范 period identity、granularity、kind 和 grouping identity 确定；result revision MUST 绑定 calculation、valuation 与 source revision；component ID MUST 绑定 component key 与 result revision，不能使用数据库自增 identity。
- **FR-025**: 事实、缺失、stale、conflict、unsupported 和 residual evidence MUST 共享一个稳定分页合同，按 occurred_at、source identity、evidence kind、evidence identity 全序排序；cursor MUST 绑定 component ID、result revision 和 ordering version，重复 source 在 result scope 内按稳定 contribution fold 去重，gap evidence 可以没有金额贡献但不得被丢弃。
- **FR-026**: 完整自然月的 breakdown 与 monthly series point MUST 复用完全相同的 period identity、result revision、component identities 和 evidence 集合，并对共享字段保持 canonical parity。
- **FR-027**: canonical DTO MUST 固定金额、时间、component、warning、evidence 与 excluded item 的序列化和排序；前端或 adapter 不得重算财务指标。
- **FR-028**: DailyWealthPoint、component result、evidence manifest 和 generation manifest MUST 可由正式事实重建而不是新的事实源；旧 result revision 与 evidence manifest MUST append-only 保留。
- **FR-029**: 每次 rebuild MUST 生成独立 build revision，从最早受影响日期重算到当前日期，并只在全保留期索引完整后原子切换 workspace active manifest；失败不得改变活动 generation。
- **FR-030**: 相同 workspace、source revision 和 build inputs 的重复构建 MUST 幂等；并发构建/查询不得暴露半成品、新旧日期混合或跨 workspace 数据。
- **FR-031**: series envelope source revision MUST 由所含 daily source revisions 按日期规范聚合，且与单日 revision 区分；缓存命中 MUST 校验 calculation、valuation、source 和 build revision。
- **FR-032**: PostgreSQL 与 SQLite MUST 共享同一 Application Service、逻辑 schema 和迁移入口，并对 canonical DTO、查询、错误、事务、幂等、revision、workspace 隔离和来源审计提供等价行为。
- **FR-033**: PostgreSQL 与 SQLite 的锁、并发能力、数据库原生错误文本和运维方式 MAY 不同，但差异只能存在于 persistence adapter 内，并映射为同一稳定应用错误合同。
- **FR-034**: 任何运行时 MUST 只通过 `FT_DATABASE_URL` 显式选择 PostgreSQL 或 SQLite；本 feature MUST NOT 引入自动回退、双写、shadow compare、CSV/YAML backend 或隐式跨后端迁移。
- **FR-035**: schema/build 失败 MUST 在事务边界失败关闭并保留上一活动 generation；错误和日志不得泄露数据库凭据、完整路径、原始隐私事实或 evidence payload。
- **FR-036**: 系统 MUST 提供纯现金流、现金加投资、多币种加残差、缺失边界和 unsupported 持仓至少五套去标识 golden fixtures，以及多账户、多币种、日内流量/换汇、负/零资本和 coverage 变化 fixtures。
- **FR-037**: 系统 MUST 提供 PostgreSQL 与 SQLite 的相同契约矩阵，覆盖 schema、迁移、查询、事务、并发、重建失败、幂等、workspace 隔离、canonical serialization 与稳定错误。
- **FR-038**: 本 feature MUST 保持现有账户、现金流、投资与导入事实接口的兼容性；不修改既有正式事实的含义。回滚仅需停止使用/删除可重建财富读模型，不能删除或重写正式事实。
- **FR-039**: 每次 rebuild MUST 在开始时捕获覆盖整个构建输入的不可变 source watermark/事实快照，并在一致输入上计算全部 generation；发布 MUST 使用单调 CAS/锁 fencing，陈旧构建不得覆盖较新 active manifest，构建期间到达的新事实只能进入下一 build。
- **FR-040**: evidence page MUST 对每个 result scope 使用全序 ordering version 和稳定 contribution fold；分页重复读取不得重复或遗漏，聚合 evidence 的贡献折叠和 gap evidence 集合 MUST 能核对到 component 金额与缺口语义。
- **FR-041**: 性能门禁 MUST 在 SQLite 与真实 PostgreSQL 上分别执行固定数据种子、固定查询 mix、预热/样本数、cache reset/hit 证明和环境元数据记录；两个后端均须满足相同用户可见查询预算，允许把数据库运维差异记录为解释而不得跳过。
- **FR-042**: 系统 MUST 以可审计、append-only 的账户生命周期事实确定 identity 在某日是 applicable 还是 not_applicable；仅凭当前 `active` 布尔值或可覆盖的更新时间不得推断历史开户、销户或重新启用区间。
- **FR-043**: source watermark MUST 解析为不可变、workspace-qualified 的 source manifest，列出每个参与或预期的 fact、valuation、lifecycle identity 与 revision/content digest；DailyWealthPoint、generation 和 evidence MUST 能追溯到该 manifest。
- **FR-044**: 系统 MUST 以 `(workspace_id, owner_account_id, identity_kind, identity)` 识别账户拥有的 coverage identity。`cash_account` 与 `position` valuation MUST 显式提供同 workspace 的 `owner_account_id`，其中 cash owner 必须等于 cash account identity；共享 `instrument_quote` 与 `currency_pair` observation MUST 不携带 owner。position ownership 可由同 workspace formal investment fact 的 `account_id` 与规范 `from_ticker`/`to_ticker`（或显式 position identity）建立，并从最早 owning fact/observation 起进入 expected universe；账户生命周期同时约束其所有 owned identities。迁移和运行时 MUST NOT 猜测 owner 或 position closure；owner 缺失/冲突、跨 workspace 引用或 valuation 与 formal fact 不一致时必须返回 unsupported coverage 与稳定 `OWNERSHIP_MISSING`/`OWNERSHIP_CONFLICT` evidence，不能发布 complete 结果。

### Key Entities

- **Wealth Change Query**: 自然月查询，绑定服务端 workspace、CNY、Asia/Shanghai、calculation version 和 valuation policy。
- **Wealth Series Query**: inclusive/exclusive 日期范围与 day/week/month 粒度，最长 366 天。
- **Daily Wealth Point**: 规范本地日原子结果，包含边界价值、六项归因、收益率、coverage、status、freshness、warnings、source/build revision 与 component refs。
- **Wealth Change Breakdown**: 自然月 envelope；与同月 monthly series point 共享 canonical 财务字段和 evidence identity。
- **Wealth Series**: 规范 envelope 与有序 points；其 source revision 是 daily revisions 的规范聚合。
- **Attribution Component**: 六种顶层组成项之一，拥有稳定逻辑 key、不可变版本 ID、状态、金额和 evidence locator。
- **Evidence Item/Manifest**: 事实或缺口的不可变审计引用，拥有稳定 identity、金额贡献、发生时间、来源 identity 与种类。
- **Coverage Universe/Disposition**: workspace/source revision 下所有预期 account/instrument identities 及逐日 disposition，用于 fingerprint、known fields 和断线判断。
- **Excluded Item**: partial/unsupported 结果中明确排除的 identity 与 reason。
- **Build Generation/Active Manifest**: 可重建 read model 的完整 generation 及 workspace 原子活动指针。
- **Source Manifest**: 构建开始时冻结的参与/预期事实、估值和账户生命周期 identity/revision 清单及规范摘要。
- **Valuation Observation**: boundary check-in、回放估值、行情或 FX observation，带 observed/as-of time、来源与 revision。
- **Owned Coverage Identity**: 由 workspace、owner account、kind 与 identity 组成；把持仓/现金估值确定性绑定到账户生命周期，shared quote/FX 不是独立 coverage identity。
- **Account Lifecycle Event**: append-only opened/closed/reactivated 事实，定义 coverage universe 的 applicable 区间。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 所有 complete golden fixtures 和所有连续完整日/周/月范围的未舍入财富恒等式通过率为 100%。
- **SC-002**: 所有 partial/unsupported fixtures 均不把已知部分伪装成完整总额；known 恒等式通过率为 100%，所有缺口都有稳定 evidence。
- **SC-003**: 任意完整自然月的 breakdown 与 monthly series point 在共享财务字段、status、coverage、component IDs 和 evidence 集合上的 canonical parity 为 100%。
- **SC-004**: Modified Dietz golden fixtures 覆盖两个投资账户、日内多次流入流出、USD→EUR 换汇、外币资产计价币种上涨 10% 同时 FX 大幅变化、零/负资本和缺失数据；每个 fixture 都返回规定数值或显式 null。
- **SC-005**: 日→周→月性质测试在所有生成的完整连续范围内组成项零漂移；partial、missing 和 coverage 变化范围从不产生跨缺口的伪造 change 或 return。
- **SC-006**: 相同输入重建至少两次得到字节等价 canonical DTO；迟到事实重建只发布完整新 generation，100% 的失败注入仍保留上一活动 generation。
- **SC-007**: PostgreSQL 与真实 SQLite/真实 PostgreSQL 契约矩阵对所有业务结果和稳定错误实现 100% parity；不得存在未解释的存储测试跳过。
- **SC-008**: 在 10 个账户、50 个持仓、100,000 条事实、366 个每日桶的固定基准上，开发者级笔记本冷查询 p95 小于 5 秒，有效缓存命中 p95 小于 300 毫秒。
- **SC-009**: 所有 published component/evidence identities 在后续 revision 重建后仍可读取原不可变结果；稳定排序与 cursor 分页重复读取无重复或遗漏。
- **SC-010**: 完整受影响测试、完整测试套件、迁移、类型/静态检查、lint（若配置）和构建全部通过，最终 tasks 无未完成项且 gstack review 无阻断 finding。
- **SC-011**: 两个并发 builder、构建中途到达事实、发布前后崩溃注入的 100% 测试均不产生 active manifest 回退、跨 revision 混合或部分 generation 可见性。
- **SC-012**: SQLite 与真实 PostgreSQL 各自按 quickstart 固定 seed/query mix/warmup/sample/cold-hot 规则完成性能测量，并在同一预算内返回 canonical 等价结果；任何缺少环境证据的运行不得标记通过。
- **SC-013**: SQLite 与真实 PostgreSQL 的 ownership golden/contract 矩阵对同一账户多持仓、同一 ticker 跨账户、账户关闭/重新启用、owner 缺失、owner 冲突和跨 workspace 引用返回 100% 等价的 coverage、status、evidence 与稳定错误；任何缺失/冲突场景均不得成为 complete。

## Assumptions

- `002-dual-database-runtime` 已完成并作为本 feature 的运行时基线；PostgreSQL 与 SQLite 均为正式后端。
- 现有 workspace、account、cashflow、investment event/projection、statement import provenance 和 revision 是正式事实；财富读模型不得改变这些事实的语义。
- 本 feature 是后续 `wealth-report-web` 的稳定核心，不交付 HTTP/OpenAPI、前端或 `ft web`。
- 资产支持白名单、CNY、Asia/Shanghai、`wealth-attribution-v0.1` 和 `valuation-v0.1` 在本 feature 内固定；任何语义变化必须使用新的版本并由独立 feature 驱动。
- 旧 read-model generation 暂不自动 GC；进入托管/多租户阶段前再定义保留和导出策略。
- 性能基准以可重复的去标识 fixture 和同一台开发者级笔记本运行；环境抖动通过多次样本的 p95 统计而非单次壁钟判断。

## Non-Goals

- HTTP API、FastAPI、OpenAPI、Web UI、Next.js、图表、浏览器 QA 或 `ft web`。
- 登录、用户/成员权限、云托管、对象存储、后台 Worker 或多租户保留策略。
- 关系审查列表、Connector 平台、自动补数、AI 解释或 MCP。
- 空头、期权、保证金、复杂公司行动、锁仓/质押衍生收益、未结算预测市场头寸或 XIRR。
- “总财富收益率”、任意日期区间独立 breakdown、当前价格回填历史、跨数据库复制/迁移或 legacy 文件 backend。
