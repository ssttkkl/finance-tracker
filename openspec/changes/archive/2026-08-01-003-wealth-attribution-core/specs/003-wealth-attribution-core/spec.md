## Purpose
User description: "实现 wealth-attribution-core spec 本能力的行为契约由迁移后的需求与场景持续维护。

## ADDED Requirements

### Requirement: Explain a Complete Wealth Change
系统 MUST 作为拥有完整账户、持仓、行情和汇率事实的 workspace 使用者，我希望查看一个自然月或时间序列桶的期初净资产、期末净资产与六项变化来源，以便确认财富变化恒等式并区分外部收支、投资市场收益、FX、负债重估、已解释调整和未解释残差。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: Expose Incomplete or Unsupported Coverage Honestly
系统 MUST 作为数据并不完整的 workspace 使用者，我希望系统明确区分 complete、stale、partial 和 unsupported，并展示已知部分、排除项和缺失证据，而不是把部分资产伪装成完整净资产。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: Compare Daily, Weekly, and Monthly Trends
系统 MUST 作为需要比较财富趋势的使用者，我希望查询最多 366 天的日、ISO 周或自然月序列，并确信所有聚合都来自同一份规范每日桶，不存在另一套报表算法。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: Audit Components and Immutable Evidence
系统 MUST 作为需要复核数字的使用者或审计者，我希望每个规范组成项都有稳定逻辑 identity、版本化结果 identity 和可分页证据，且历史结果不会被后续重建悄悄改写。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: Rebuild and Serve a Revision-Safe Read Model
系统 MUST 作为本地运行 Finance Tracker 的使用者，我希望财富序列缓存可以安全重建并在 PostgreSQL 与 SQLite 上返回相同业务结果；失败的重建不得发布半成品或混合新旧日期。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 功能需求基线
系统 MUST 保持以下迁移前功能需求；后续行为变更必须通过 OpenSpec change 明确修改。

- - **FR-001**: 系统 MUST 以 Asia/Shanghai 本地自然日 `[00:00, 次日 00:00)` 生成规范 DailyWealthPoint，并将其作为 breakdown 和所有 series 粒度的唯一原子计算结果。
- - **FR-002**: 系统 MUST 使用 CNY 作为本 feature 的基础币种，并返回 `calculation_version=wealth-attribution-v0.1` 与显式 `valuation_policy_version`；调用方不得覆盖未知版本。
- - **FR-003**: 系统 MUST 使用精确 Decimal 保存和计算金额、汇率、资本权重与收益率；中间计算不得舍入，canonical 金额只在展示边界按币种精度输出无指数十进制字符串。
- - **FR-004**: 系统 MUST 满足 `closing - opening = external_cashflow + investment_return + fx_impact + liability_revaluation + explained_other_adjustment + unexplained_adjustment`，所有符号以增加净资产为正。
- - **FR-005**: 系统 MUST 按规范事件表分类工资、消费、退款、内部转账、投资入出金、股息、利息、费用、已知修正和残差，并保证同一经济价值不被重复计入。
- - **FR-006**: 系统 MUST 对非 CNY 投资按期末 FX 固定顺序分解计价币种市场收益和 FX 影响，并对外币现金、外币负债应用相应 FX 公式；公式必须在未舍入精度上闭合。
- - **FR-007**: 系统 MUST 对负债使用负余额净资产语义；借款本金发放/偿还是内部流量，明确利息/费用为负 external cashflow，非现金本金修正为 liability revaluation。
- - **FR-008**: 系统 MUST 按已定义分母计算 explained ratio，并返回 0 到 1 的规范 Decimal；unexplained 金额必须独立返回且不改变 complete/stale/partial/unsupported 状态。
- - **FR-009**: 系统 MUST 使用 boundary check-in 优先、完整事件与合格行情确定性回放次之的估值顺序；两者均不可用时不得估算。
- - **FR-010**: 系统 MUST 对上市证券/FX 使用 5 日 freshness 与 30 日 maximum usable age，对加密资产使用 24 小时 freshness 与 7 日 maximum usable age，并按阈值传播 stale 或 partial。
- - **FR-011**: 系统 MUST 只支持现金、银行存款、普通借贷、现货多头股票/ETF、现货加密资产及规范事件白名单；空头、期权、保证金、复杂公司行动、锁仓/质押衍生收益和未结算预测市场头寸 MUST 为 unsupported。
- - **FR-012**: 任一已知账户/持仓缺少边界估值时，系统 MUST 将完整净资产、完整组成与完整 explained ratio 返回 null，并返回可审计的 known fields、coverage、excluded items 和 excluded coverage adjustment。
- - **FR-013**: 系统 MUST 对已覆盖事实集合维持 `known_closing - known_opening = sum(known_components) + excluded_coverage_adjustment + known_unexplained_adjustment`，且完整字段和 known 字段不得混加。
- - **FR-014**: 如果没有任何账户同时具备两个边界，系统 MUST 返回稳定的 `REPORT_NOT_CONSTRUCTIBLE` 错误并且不发布结果。
- - **FR-015**: 系统 MUST 为 expected coverage universe 中每个 account/instrument identity 在每个本地日期记录 supported、missing、unsupported、unvalued 或 not_applicable disposition，并据此生成与查询范围无关的 coverage fingerprint。
- - **FR-016**: 同一预期 identity 的 coverage disposition 发生真实缺失/不支持/不可估值变化时，系统 MUST 产生 `COVERAGE_CHANGED`，将后一 point 标记不可与前一点比较，并禁止跨变化计算 change、组成项或收益率；生命周期外 not_applicable 不触发变化。
- - **FR-017**: 系统 MUST 接受 inclusive `date_from`、exclusive `date_to` 与 day/week/month 粒度，最长 366 天；非法范围、超限或非法粒度分别返回稳定错误，不得截断。
- - **FR-018**: 系统 MUST 以 ISO Monday-Sunday 聚合周、以自然月聚合月；不完整首尾周期必须返回并标记 `is_partial_period=true`。
- - **FR-019**: coverage 连续且字段完整时，周/月 opening 取首日、closing 取末日、组成项与 unexplained 求和、状态取最严重每日状态；不得平均净资产或另写一套归因算法。
- - **FR-020**: 缺少每日 point、coverage 变化或必需完整字段缺失时，系统 MUST 按规范传播 null、known fields、warning、status 和断线端点；不得插值、跳过坏日或把剩余日冒充完整周期。
- - **FR-021**: 系统 MUST 计算 daily Modified Dietz linked return：先按各币种计算，再用日初 FX 固定换算资本权重汇总；外部资金只指 investment universe 边界流量，FX 影响不得进入收益率。
- - **FR-022**: 任一实质敞口币种 capital 不大于零、总加权资本不大于零、缺少日初 FX、边界缺失或 portfolio partial/unsupported 时，系统 MUST 返回 null 收益率；周/月使用 `product(1+daily_rate)-1`，任一天 null 则周期为 null。
- - **FR-023**: 每个完整或 known 公式组成项 MUST 返回固定 kind 顺序的 component，包含稳定 component key、版本化 component ID、result revision、金额、状态和 transport-neutral immutable evidence reference；核心不得生成 HTTP URL 或状态码。
- - **FR-024**: component key MUST 由 workspace identity、规范 period identity、granularity、kind 和 grouping identity 确定；result revision MUST 绑定 calculation、valuation 与 source revision；component ID MUST 绑定 component key 与 result revision，不能使用数据库自增 identity。
- - **FR-025**: 事实、缺失、stale、conflict、unsupported 和 residual evidence MUST 共享一个稳定分页合同，按 occurred_at、source identity、evidence kind、evidence identity 全序排序；cursor MUST 绑定 component ID、result revision 和 ordering version，重复 source 在 result scope 内按稳定 contribution fold 去重，gap evidence 可以没有金额贡献但不得被丢弃。
- - **FR-026**: 完整自然月的 breakdown 与 monthly series point MUST 复用完全相同的 period identity、result revision、component identities 和 evidence 集合，并对共享字段保持 canonical parity。
- - **FR-027**: canonical DTO MUST 固定金额、时间、component、warning、evidence 与 excluded item 的序列化和排序；前端或 adapter 不得重算财务指标。
- - **FR-028**: DailyWealthPoint、component result、evidence manifest 和 generation manifest MUST 可由正式事实重建而不是新的事实源；旧 result revision 与 evidence manifest MUST append-only 保留。
- - **FR-029**: 每次 rebuild MUST 生成独立 build revision，从最早受影响日期重算到当前日期，并只在全保留期索引完整后原子切换 workspace active manifest；失败不得改变活动 generation。
- - **FR-030**: 相同 workspace、source revision 和 build inputs 的重复构建 MUST 幂等；并发构建/查询不得暴露半成品、新旧日期混合或跨 workspace 数据。
- - **FR-031**: series envelope source revision MUST 由所含 daily source revisions 按日期规范聚合，且与单日 revision 区分；缓存命中 MUST 校验 calculation、valuation、source 和 build revision。
- - **FR-032**: PostgreSQL 与 SQLite MUST 共享同一 Application Service、逻辑 schema 和迁移入口，并对 canonical DTO、查询、错误、事务、幂等、revision、workspace 隔离和来源审计提供等价行为。
- - **FR-033**: PostgreSQL 与 SQLite 的锁、并发能力、数据库原生错误文本和运维方式 MAY 不同，但差异只能存在于 persistence adapter 内，并映射为同一稳定应用错误合同。
- - **FR-034**: 任何运行时 MUST 只通过 `FT_DATABASE_URL` 显式选择 PostgreSQL 或 SQLite；本 feature MUST NOT 引入自动回退、双写、shadow compare、CSV/YAML backend 或隐式跨后端迁移。
- - **FR-035**: schema/build 失败 MUST 在事务边界失败关闭并保留上一活动 generation；错误和日志不得泄露数据库凭据、完整路径、原始隐私事实或 evidence payload。
- - **FR-036**: 系统 MUST 提供纯现金流、现金加投资、多币种加残差、缺失边界和 unsupported 持仓至少五套去标识 golden fixtures，以及多账户、多币种、日内流量/换汇、负/零资本和 coverage 变化 fixtures。
- - **FR-037**: 系统 MUST 提供 PostgreSQL 与 SQLite 的相同契约矩阵，覆盖 schema、迁移、查询、事务、并发、重建失败、幂等、workspace 隔离、canonical serialization 与稳定错误。
- - **FR-038**: 本 feature MUST 保持现有账户、现金流、投资与导入事实接口的兼容性；不修改既有正式事实的含义。回滚仅需停止使用/删除可重建财富读模型，不能删除或重写正式事实。
- - **FR-039**: 每次 rebuild MUST 在开始时捕获覆盖整个构建输入的不可变 source watermark/事实快照，并在一致输入上计算全部 generation；发布 MUST 使用单调 CAS/锁 fencing，陈旧构建不得覆盖较新 active manifest，构建期间到达的新事实只能进入下一 build。
- - **FR-040**: evidence page MUST 对每个 result scope 使用全序 ordering version 和稳定 contribution fold；分页重复读取不得重复或遗漏，聚合 evidence 的贡献折叠和 gap evidence 集合 MUST 能核对到 component 金额与缺口语义。
- - **FR-041**: 性能门禁 MUST 在 SQLite 与真实 PostgreSQL 上分别执行固定数据种子、固定查询 mix、预热/样本数、cache reset/hit 证明和环境元数据记录；两个后端均须满足相同用户可见查询预算，允许把数据库运维差异记录为解释而不得跳过。
- - **FR-042**: 系统 MUST 以可审计、append-only 的账户生命周期事实确定 identity 在某日是 applicable 还是 not_applicable；仅凭当前 `active` 布尔值或可覆盖的更新时间不得推断历史开户、销户或重新启用区间。
- - **FR-043**: source watermark MUST 解析为不可变、workspace-qualified 的 source manifest，列出每个参与或预期的 fact、valuation、lifecycle identity 与 revision/content digest；DailyWealthPoint、generation 和 evidence MUST 能追溯到该 manifest。
- - **FR-044**: 系统 MUST 以 `(workspace_id, owner_account_id, identity_kind, identity)` 识别账户拥有的 coverage identity。`cash_account` 与 `position` valuation MUST 显式提供同 workspace 的 `owner_account_id`，其中 cash owner 必须等于 cash account identity；共享 `instrument_quote` 与 `currency_pair` observation MUST 不携带 owner。position ownership 可由同 workspace formal investment fact 的 `account_id` 与规范 `from_ticker`/`to_ticker`（或显式 position identity）建立，并从最早 owning fact/observation 起进入 expected universe；账户生命周期同时约束其所有 owned identities。迁移和运行时 MUST NOT 猜测 owner 或 position closure；owner 缺失/冲突、跨 workspace 引用或 valuation 与 formal fact 不一致时必须返回 unsupported coverage 与稳定 `OWNERSHIP_MISSING`/`OWNERSHIP_CONFLICT` evidence，不能发布 complete 结果。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：对该能力进行修改时，验证结果 MUST 覆盖迁移后的功能需求清单。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 可度量验收结果
系统 MUST 继续满足以下可度量结果；它们是迁移后的验收回归基线。

- - **SC-001**: 所有 complete golden fixtures 和所有连续完整日/周/月范围的未舍入财富恒等式通过率为 100%。
- - **SC-002**: 所有 partial/unsupported fixtures 均不把已知部分伪装成完整总额；known 恒等式通过率为 100%，所有缺口都有稳定 evidence。
- - **SC-003**: 任意完整自然月的 breakdown 与 monthly series point 在共享财务字段、status、coverage、component IDs 和 evidence 集合上的 canonical parity 为 100%。
- - **SC-004**: Modified Dietz golden fixtures 覆盖两个投资账户、日内多次流入流出、USD→EUR 换汇、外币资产计价币种上涨 10% 同时 FX 大幅变化、零/负资本和缺失数据；每个 fixture 都返回规定数值或显式 null。
- - **SC-005**: 日→周→月性质测试在所有生成的完整连续范围内组成项零漂移；partial、missing 和 coverage 变化范围从不产生跨缺口的伪造 change 或 return。
- - **SC-006**: 相同输入重建至少两次得到字节等价 canonical DTO；迟到事实重建只发布完整新 generation，100% 的失败注入仍保留上一活动 generation。
- - **SC-007**: PostgreSQL 与真实 SQLite/真实 PostgreSQL 契约矩阵对所有业务结果和稳定错误实现 100% parity；不得存在未解释的存储测试跳过。
- - **SC-008**: 在 10 个账户、50 个持仓、100,000 条事实、366 个每日桶的固定基准上，开发者级笔记本冷查询 p95 小于 5 秒，有效缓存命中 p95 小于 300 毫秒。
- - **SC-009**: 所有 published component/evidence identities 在后续 revision 重建后仍可读取原不可变结果；稳定排序与 cursor 分页重复读取无重复或遗漏。
- - **SC-010**: 完整受影响测试、完整测试套件、迁移、类型/静态检查、lint（若配置）和构建全部通过，最终 tasks 无未完成项且 gstack review 无阻断 finding。
- - **SC-011**: 两个并发 builder、构建中途到达事实、发布前后崩溃注入的 100% 测试均不产生 active manifest 回退、跨 revision 混合或部分 generation 可见性。
- - **SC-012**: SQLite 与真实 PostgreSQL 各自按 quickstart 固定 seed/query mix/warmup/sample/cold-hot 规则完成性能测量，并在同一预算内返回 canonical 等价结果；任何缺少环境证据的运行不得标记通过。
- - **SC-013**: SQLite 与真实 PostgreSQL 的 ownership golden/contract 矩阵对同一账户多持仓、同一 ticker 跨账户、账户关闭/重新启用、owner 缺失、owner 冲突和跨 workspace 引用返回 100% 等价的 coverage、status、evidence 与稳定错误；任何缺失/冲突场景均不得成为 complete。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：运行该能力的验收矩阵时，结果 MUST 满足迁移后的成功标准。
- THEN 系统满足该条件，并保留可复核的验证证据。
