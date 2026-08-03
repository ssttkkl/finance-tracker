# Transaction Relations

## Purpose
User description: "重新设计账单导入后的去重、退款核销、转账配对、跨平台消费合并逻辑。核心原则：所有导入产生的原始事实都保留，不因配对/去重/退款核销而物理删除或改写原始记录；系统只追加记录事实之间的关系与判断证据/状态，再由报表和派生投影读取这些关系来避免重复统计或计算净额。配对规则可参考 main 分支当前实现。 本能力的行为契约由迁移后的需求与场景持续维护。

## Requirements

### Requirement: 导入后自动建立跨平台消费关系
系统 MUST 作为记账用户，我分别导入支付宝/微信账单和银行卡账单后，同一笔外部消费会在系统中留下两条正式事实；系统识别它们是同一次消费的两个视角，建立 `payment_mirror`（跨平台消费镜像）关系，而不是删除其中一条。消费报表只统计一次，但扣款账户余额仍反映真实流水。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 内部转账配对且不计入外部收支
系统 MUST 作为记账用户，我在账户 A 转出、账户 B 转入后，两边事实都保留；系统建立 `transfer_pair` 关系。两边余额都正确变化，但外部收入/支出报表不把这笔内部调拨算成消费或收入。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 退款核销不改写原消费金额
系统 MUST 作为记账用户，我有一笔消费和后续全额/部分退款；系统保留两条事实，建立 `refund_offset` 关系，报表据此计算原始消费、退款额与净消费，而不是把原消费金额改写成净额或删除退款。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 信用还款关系
系统 MUST 作为记账用户，我从储蓄卡还款到信用卡后，两边事实都保留；系统建立带还款 subtype 的 `transfer_pair`（证据标明 credit repayment），避免把还款误计为普通消费或收入，同时保留储蓄卡余额减少与信用卡负债减少。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 历史重复事实允许可审计的手动删除
系统 MUST 作为运维/记账用户，行级导入幂等仍优先防止重复发布（仅对**活跃**正式事实）。若因历史原因已经存在两条实质重复的正式事实，系统**不**用 `duplicate_of` 或任何 duplicated/canonical 关系标识在报表层排重；我可以**手动逻辑删除**其中错误/多余的正式事实。删除通过追加墓碑/事件表达，保留 Formal Fact、RawRecord 与 revisions；被删事实退出当前投影且相关活跃关系被 supersede。删除必须可审计，且不得被自动关系检查静默执行。若我之后再次导入同一 source identity，系统应**正常发布新的活跃正式事实**，而不是把旧删除实例静默复活，也不得因“曾经删除过”而永久拒绝该 identity。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 在关系审查列表中审查弱匹配
系统 MUST 作为用户，我可以在审查入口看到所有 `pending_review` 候选：关系类型、两条事实关键字段、证据、置信度；我可以 accept、reject 或稍后处理。accept 后影响报表；reject 后不得反复推荐同一候选；操作可审计。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 收敛待配对关系的候选数量
系统 MUST 作为用户，当系统识别到退款或转账意图但无法唯一确定对侧时，我在审查入口看到的是 **一条** 挂在锚点事实上的待办（对侧为空，可带建议候选列表），而不是笛卡尔积式的多条双边候选；我随后选择对侧并确认后，关系才生效。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 账户别名增强候选但不劫持导入
系统 MUST 作为用户，我可以维护卡尾号、支付方式文本、亲属卡/虚拟卡等账户别名，用于增强关系候选匹配；别名命中必须进入 evidence，冲突可见；别名不得无审计地覆盖导入账户路由。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。

### Requirement: 转账关系的本人账户标识证据
系统 MUST 在转账、提现和还款的转出方具有来源直接提供的对方账号时，以当前工作区用户显式登记的本人账户标识收敛或排除既有候选。该证据只能筛选已满足金额、币种、时间、方向和记录类型规则的候选，且不得单独使关系自动确认。系统 MUST 在证据中只记录 `counterparty_account_match=exact`、`tail` 或空字符串，不得保存任何账号原文。

#### Scenario: 账号证据不放宽转账门槛
- **WHEN** 对方账号命中一个候选账户但该候选不满足既有金额、币种、时间、方向或记录类型门槛
- **THEN** 系统 MUST 不创建或自动确认该转账关系

#### Scenario: 账号证据收敛强候选
- **WHEN** 多个既有强转账候选中只有一个目标账户命中来源直接提供的对方账号
- **THEN** 系统 MUST 仅对该命中候选按既有规则创建关系，并在证据中记录命中种类

### Requirement: 规则升级可 supersede 旧关系
系统 MUST 作为系统维护者，规则版本升级后可以产生新关系版本，将旧关系标记为 `superseded`，而不是原地覆盖历史判断；审计可解释为何升级。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 后到账单补齐跨批关系
系统 MUST 作为记账用户，我先导入银行账单、几天后再导入支付宝账单；系统不需要我重导旧文件，也能以新批新增事实为种子，与先前批次中的事实建立 `payment_mirror` / `transfer_pair` / `refund_offset` 等关系。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 功能需求基线
系统 MUST 保持以下迁移前功能需求；后续行为变更必须通过 OpenSpec change 明确修改。

- - **FR-001**: System MUST NOT 因自动去重、配对、退款核销或关系检查而物理删除或逻辑删除正式事实（`CashTransaction` / `InvestmentEvent` 等）或 RawRecord。跨平台镜像、转账、退款等场景 MUST 保留相关正式事实。若存在错误的实质重复正式事实，用户 MAY 通过**可审计的手动逻辑删除**将多余事实排除出当前投影；Formal Fact、RawRecord 与 revisions MUST 保留，自动流程 MUST NOT 静默删除。
- - **FR-002**: System MUST NOT 把两条真实流水物理合并成一条正式事实。
- - **FR-003**: System MUST NOT 在自动配对后直接改写原事实的金额、账户、分类、来源或币种来表达关系结果。
- - **FR-004**: 既有导入链路合同 MUST 保持：workspace 隔离、文件级幂等（workspace+source+digest）、行级幂等（workspace+source_type+source_identity，**仅对活跃正式事实**）、raw→formal 关联、原始记录不可变、正式事实追加式 revision 审计、Decimal 金额、统一时区可比较时间。
- - **FR-005**: 同一 RawRecord 若被路由到不同账户，System MUST 拒绝，不得静默改归属。
- - **FR-006**: 本 feature MUST NOT 恢复 CSV 时代的物理删除式 dedup/reconcile，也 MUST NOT 把 pending CSV 作为候选审查机制。
- - **FR-007**: System MUST 以数据库原生对象持久化事实间关系（或候选与关系的等价模型），至少包含：workspace、kind、锚点/对侧事实引用、status、rule_id、confidence、evidence、创建者/来源、时间、版本/revision。对 `refund_offset` 与 `transfer_pair`，**待配对关系** `pending_review` 允许对侧事实引用为空；`accepted` 的对侧 MUST 非空。`payment_mirror` 的两侧 MUST 始终非空。
- - **FR-008**: 关系 kind 至少支持：`payment_mirror`（跨平台消费镜像）、`transfer_pair`、`refund_offset`。信用还款不使用独立顶层 kind，而以 `transfer_pair` + subtype/证据（如 `credit_repayment`）表达；用户可见语义与报表影响 MUST 仍可区分普通内部转账与信用还款。MUST NOT 引入 `duplicate_of` 或等价 duplicated/canonical 关系 kind 来表达“重复事实只统计一条”。
- - **FR-009**: 关系状态 MUST 支持：`pending_review`、`accepted`、`rejected`、`superseded`。
- - **FR-010**: `pending_review`（含待配对关系）与 `rejected` MUST NOT 影响报表与当前派生投影；仅 **双边** `accepted` 影响当前报表；`superseded` 不参与当前报表但保留审计。待配对关系 MUST NOT 因“有锚点”而提前排除转账或核销退款。
- - **FR-011**: 自动 accepted MUST 保存 rule_id、confidence 与 evidence；人工 accept/reject MUST 保存操作者、时间与原因。
- - **FR-012**: 人工决策 MUST NOT 被后续自动规则静默覆盖；替换 MUST 通过新版本 + `superseded` 审计链完成。
- - **FR-013**: 同一 workspace 内，同一关系业务键上的活跃（非 superseded）表达 MUST 唯一。**双边**业务键至少包含 `(workspace, kind, ordered_fact_pair, subtype_if_any)`。**待配对关系**业务键至少包含 `(workspace, kind, subtype_if_any, anchor_fact_id, open)`，同一锚点不得并存多条有效待配对关系。两类键均不得并存多个活跃 pending/accepted/rejected。
- - **FR-014**: rejected 决策 MUST 持久化，使同一候选业务键在后续自动检查中不再重复推荐为 pending（除非显式 supersede 重开）。
- - **FR-015**: 每条自动生成的候选或 accepted 关系 MUST 保存可解释 evidence（如金额差、时间差、币种一致、卡尾号、账户别名命中、counterparty 相似度、source 对、rule_id 等）。
- - **FR-016**: 跨平台镜像 `payment_mirror` 匹配 MUST 对齐 main 分支跨源去重的**精度与静默策略**，并适配多账户模型。具体 MUST：
- - **FR-017**: 内部转账匹配信号 MUST 参考 main 分支 reconcile 转账语义：一正一负、不同账户、同币种绝对金额严格相等、有效时间接近、转账强信号词（如转账支取/存入、银联入账、手机银行、提现等）；并支持同日银联现金类已验证规则族。同币种强匹配 auto-accept 时间窗为有效时间差 ≤10 秒且候选唯一；同日宽窗口 auto-accept 仅允许“银联入账/电子汇入 ↔ 无卡付/**无卡支付**/云闪付/转账支取”强信号组合且候选唯一。**当任一侧为银行 date-only 导出**（raw_payload `date` 为 `YYYY-MM-DD`，无真实钟点；formal 常落 16:00 UTC）时，MUST 回退用 **raw 业务日**（与 payment_mirror FR-052/053 同一 `business_day_shanghai` / `fact_is_bank_date_only`）判定同日，且 MUST NOT 用 formal 16:00 哨兵时刻计算 Δt 来否决同日银联桥；同业务日 + 等额 + 银联/转账强信号 + 唯一候选 → MAY auto-accept。同币种两侧任意非零金额差额 MUST NOT auto-accept，但 MAY 以精确 `amount_delta` 证据进入 pending；不得把差额舍入、吸收或隐式解释为手续费。当转账/还款合法对侧 ≥2 或不唯一时，MUST 落 1 条待配对关系（锚点由信号规则决定），而非 N 条双边 pending；唯一近强对侧 MAY 双边 pending。
- - **FR-018**: 信用还款匹配 MUST 覆盖 cash→loan 同币种与跨币种（含购汇还款）形态。信用还款以 `transfer_pair` subtype（如 `credit_repayment`）表达；用户可见语义与报表影响 MUST 可区分普通内部转账与信用还款。
- - **FR-019**: 对仅一侧出现在账本内的内部调拨信号，System MUST NOT 自动建立 **accepted** 双边关系，也 MUST NOT 伪造缺失的对侧流水或改写已有流水的分类。高信号锚点 MAY 创建状态为 `pending_review` 的 `transfer_pair` **待配对关系**（对侧为空），待用户补录或选择对侧流水后再绑定并确认。main `transfer_rules` 文本信号族仅可作为补录提示、候选搜索或待配对关系线索，不得单独成为 accepted 关系。
- - **FR-020**: 退款匹配 MUST 支持全额与部分、多退款对一消费；每笔退款事实最多关联一笔消费，MUST NOT 在 v1 中把一笔退款分摊给多笔消费。**退款流水 MUST 有明确退款文本信号**（不得把任意 income 当退款种子）。
- - **FR-021**: `payment_mirror` MUST 声明 primary/canonical 或提供确定性选择规则（例如支付平台详情优先于银行通道摘要；同源时信息量更高者优先），仅用于报表“外部消费只计一次”，双方/多方事实仍保留。accepted `payment_mirror` MAY 形成连通组；组内 MUST 只有一个确定性 canonical，外部消费 MUST 只计一次；冲突 canonical 候选 MUST `pending_review`。MUST NOT 用 `duplicate_of` 处理错误重复事实。
- - **FR-022**: 规则版本升级时，System MUST 能 supersede 旧关系并保留旧证据，而不是覆盖写历史。
- - **FR-023**: 每次成功导入一批账单后，System MUST 在导入事务提交之后登记或执行一次“账单关联检查”，并以该批新增**活跃**正式事实作为触发种子；不得重新解析或重新导入文件，也 MUST NOT 把关系检查放进会因匹配失败而回滚正式事实的同一导入事务。
- - **FR-024**: 关系检查 MUST 幂等、可重试；失败 MUST NOT 破坏或回滚已导入事实。同步执行或登记可重试任务均可；若检查未完成/失败，正式事实仍保持已导入状态，并可再次触发检查。同一 workspace 内并发检查 MUST 收敛到等价结果，不得产生重复活跃关系或静默覆盖人工决策。
- - **FR-025**: 对每个种子事实，System MUST 在同一 workspace 的全部既有**活跃**正式事实中，按关系类型使用有界、可索引的候选条件（金额、币种、时间窗口、账户类型、支付方式、卡尾号、别名、文本、source 组合、外部 id 等）筛选候选；候选 MAY 来自任意既有导入批次。增量检查 MUST 只创建或重评至少包含一个种子事实的关系，**不得以全库无界双重扫描（O(n²) 笛卡尔式）作为正确性或默认实现路径**。
- - **FR-026**: 关联账单后到才导入时，后到批次的新增正式事实作为种子，MUST 仍能与先前任意批次中符合规则的活跃事实建立关系。手动按批次、事实或日期范围重跑时，指定范围 MUST 只限定种子事实；候选 MAY 位于该范围之外，但 MUST 满足关系类型的规则。全 workspace 重算 MUST 由显式全量重跑触发。
- - **FR-027**: System MUST 提供审查入口列出 pending 候选，展示 kind、锚点事实关键字段、对侧字段（待配对关系可空）、evidence（含 candidate_fact_ids/candidate_count/anchor_role 若有）、confidence。
- - **FR-028**: 用户 MUST 能 accept、reject、ignore/later；accept/reject MUST 写审计。ignore/later MUST NOT 创建独立终态，候选保持 `pending_review`，可记录稍后处理意图，继续不影响报表，并仍出现在审查入口。
- - **FR-029**: 状态迁移至少支持：`pending_review→accepted`、`pending_review→rejected`、`accepted→superseded`；`rejected→superseded` 仅在新规则/新证据明确重开时允许。ignore/later 不改变 `pending_review` 状态。待配对关系仅存在于 `pending_review`；进入 accepted 时 MUST 已是双边。
- - **FR-030**: System MUST 支持账户别名（卡尾号、支付方式文本、历史卡号、亲属卡/虚拟卡等）用于候选增强与验证。
- - **FR-031**: 别名命中 MUST 进入 evidence；别名冲突 MUST 可见；别名 MUST NOT 无审计地替代导入 mapping 路由。
- - **FR-032**: 别名变更后 MUST 能触发相关候选重算（幂等、可审计）。
- - **FR-033**: 收支类报表/投影 MUST 基于「活跃正式事实 + **双边** accepted 关系」计算，而不是修改事实后的净额字段，也不是读取行内 `offset_*`/`transfer_account` 权威字段；对侧为空的 pending MUST 忽略。
- - **FR-034**: 收支投影 MUST 按以下顺序解释 accepted 关系：先将 accepted `payment_mirror` 连通事实归一为逻辑事件组并确定一次外部计次，再排除 accepted `transfer_pair`（含 credit repayment subtype）两侧，最后以逻辑事件组标识应用 accepted `refund_offset`，得到原始消费、退款与净消费。同一退款镜像组 MUST 只贡献一次核销；组合关系 MUST NOT 重复计次、重复排除或重复核销。错误重复事实通过用户手动逻辑删除后不再进入统计，而不是通过 duplicated 关系排重。
- - **FR-035**: 余额类投影 MUST 保留真实账户流水影响，不得因镜像/去重关系丢弃任一侧真实扣款或入账；逻辑删除事实 MUST 被排除。
- - **FR-036**: 投影 MUST 可从活跃事实与 accepted 关系确定性重建。
- - **FR-037**: 金额比较、关系判定与核销 MUST 使用原始币种的精确 Decimal 语义；禁止二进制浮点、隐式舍入或金额容差作为账务判定依据。对预期等额的同币种关系，只有金额严格相等才可 auto-accept；候选搜索 MAY 使用金额范围，但该范围 MUST NOT 改变 exact `amount_delta`、关系状态或投影金额。跨币种关系不使用等额条件，也不得隐式换算后强行匹配。main 分支历史代码中的 `0.01` 浮点阈值 MUST NOT 作为本 feature 的账务容差。
- - **FR-038**: 所有关系与审查操作 MUST 在 workspace 内隔离。
- - **FR-039**: PostgreSQL 与 SQLite MUST 对上述用户可见行为、幂等、失败合同与报表结果提供等价证据；禁止自动回退、双写、隐式跨后端迁移。允许的运行差异仅限锁实现、并发吞吐、底层驱动错误信息与任务调度时延。
- - **FR-040**: 文档与操作说明 MUST 说明：自动流程不得删改事实、用户手动逻辑删除及其审计语义、逻辑删除后再导入发布新活跃事实、关系状态机、审查入口、报表如何读取 accepted 关系、与旧 CSV reconcile 及行内 offset 字段的差异。
- - **FR-041**: 逻辑删除后再导入同 identity 时，文件级 digest 幂等 MUST 继续阻止同一已成功文件整文件重放；行级 MUST 在无活跃同 identity 事实时允许发布新活跃正式事实，且新旧实例可审计区分。
- - **FR-042**: System MUST 支持 `refund_offset` 与 `transfer_pair`（含 `credit_repayment`）的 **待配对关系** `pending_review`：锚点事实非空，对侧事实为空，建议对侧（若有）仅存在于 evidence，不落多条关系行。
- - **FR-043**: 待配对关系触发条件 MUST 为：在既有匹配规则下，锚点形态成立，且（合法对侧候选 ≥2，或候选为 0 但仍需人工处理的高信号锚点）。唯一对侧的近强未达 MAY 仍建双边 pending；唯一强匹配 MUST 仍双边 auto-accepted（若达标）。
- - **FR-044**: `payment_mirror` MUST NOT 使用待配对关系模型。
- - **FR-045**: 待配对关系的 `evidence` MUST 可包含：`open_leg=true`、`anchor_role`、`candidate_count`、`candidate_fact_ids`（按规则排序的 top-K，默认 K=20；没有候选时列表可空）。
- - **FR-046**: 用户为待配对关系指定对侧并 accept 后，结果 MUST 与「系统一开始就唯一匹配到该对侧并 accepted」在投影语义上等价（在通过同一合法性校验的前提下）。
- - **FR-047**: System MUST NOT 创建占位/虚假正式事实充当对侧以回避可空对侧。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：对该能力进行修改时，验证结果 MUST 覆盖迁移后的功能需求清单。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 可度量验收结果
系统 MUST 继续满足以下可度量结果；它们是迁移后的验收回归基线。

- - **SC-001**: 任意完成关系处理后的样本中，相关正式事实条数与关系建立前一致（无因配对导致的物理删除）。
- - **SC-002**: 同一跨平台消费导入支付平台与银行两份账单后，系统中存在 2 条正式事实 + 1 条 accepted 或（近强时）pending `payment_mirror` 关系（不是删除其中一条，也不是 `duplicate_of`）；bank×bank 不得出现 accepted `payment_mirror`。
- - **SC-003**: 高置信跨平台消费在消费报表中只出现 1 次；对应扣款账户余额仍反映真实流水。
- - **SC-004**: 内部转账双边余额正确变化，且外部收入/支出报表不计入该对。
- - **SC-005**: 退款场景中原消费与退款事实金额均保持导入原值；净消费仅通过 accepted `refund_offset` 得到，并与手工验算一致。
- - **SC-006**: 弱匹配 100% 进入 pending 审查列表且不改变报表；reject 后再次关系检查不再产生同一 pending 候选；ignore/later 后候选仍可见且仍不改变报表。
- - **SC-007**: 对同一批新增事实连续执行 2 次关系检查，活跃关系/候选集合不变（幂等）。
- - **SC-008**: 每条自动关系都能展示至少 1 组可理解证据字段，足以回答“为什么匹配”。
- - **SC-009**: 人工 accept/reject 均可追溯操作者与时间；自动规则无法在无 supersede 的情况下改变人工结论。
- - **SC-010**: 同一验收矩阵在 PostgreSQL 与 SQLite 上得到等价的关系状态与报表净额。
- - **SC-011**: 用户逻辑删除重复事实后，该事实实例、RawRecord、revisions、source identity 与删除审计仍可追溯；余额/收支投影不再包含该实例，相关活跃关系均已 superseded；其后对同一 source identity 的再导入会发布新活跃事实且进入投影，旧删除实例仍排除；PostgreSQL 与 SQLite 结果等价。
- - **SC-012**: 对预期等额的同币种 `payment_mirror` 与 `transfer_pair` 测试样本，两侧金额严格相等时才可能自动 accepted；加入任意非零 Decimal 差额后 100% 不会自动 accepted，且 evidence 保留精确差额。退款的全额、部分、超额及净额均与 Decimal 手工验算完全一致。
- - **SC-013**: 满足规格时间窗与唯一性条件的强匹配样本可 auto-accept；故意放宽到仅同日弱匹配、超过窗口、或多候选时 100% 不 auto-accept。
- - **SC-014**: 对含历史行内 `offset_*`/`proposed_action` 痕迹的样本，报表净额与转账排除结果只随独立关系状态变化，不因这些字段本身变化。
- - **SC-015**: 对“活跃同 identity 再导入”样本 100% 不重复发布；对“仅存在已逻辑删除同 identity 后再导入”样本 100% 成功发布新活跃事实且不静默复活旧实例。
- - **SC-016**: 先导入银行、后导入对应支付平台的跨批样本中，后批检查后 100% 能建立跨批 `payment_mirror`（accepted 或 pending），且不改写旧批事实。
- - **SC-018**: 在含 ≥10_000 条活跃现金事实（约 3 年个人账本量级）的库上，单次全量关系检查 wall clock ≤ 60 秒；且实现路径不依赖对全部事实的无界双重全表扫描。
- - **SC-019**: 在「1 个退款锚点 × N 个同商户合法消费候选」（N≥2）样本上，关系检查后活跃 `refund_offset` pending 条数 MUST 为 1（待配对关系），且 evidence.candidate_count≥N 或 candidate_fact_ids 长度反映截断前的意图；MUST NOT 产生 N 条双边 pending。
- - **SC-020**: 用户对待配对关系指定合法对侧 accept 后，净消费/转账排除结果与手工验算一致；accept 前投影与无该关系一致。
- - **SC-021**: 0 候选高信号退款/转账锚点 MUST 可进入待配对关系；用户后续选定合法对侧后可完成 accepted。
- - **SC-022**: 驳回待配对关系后，自动重跑时同一待配对锚点键 100% 不再出现新关系（除非 supersede 重开）。
- - **SC-023**: `payment_mirror` 全量检查不得产生对侧为空的关系行。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：运行该能力的验收矩阵时，结果 MUST 满足迁移后的成功标准。
- THEN 系统满足该条件，并保留可复核的验证证据。

## Source
完整迁移来源与原始验证证据：[006-transaction-relations/spec.md](../../changes/archive/2026-08-01-006-transaction-relations/legacy/006-transaction-relations/spec.md)。
本文件是 OpenSpec 的行为导向投影；实现细节、研究记录和历史任务保留在对应 change 的 `legacy/` 目录。
