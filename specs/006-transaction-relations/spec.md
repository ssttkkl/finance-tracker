# Feature Specification: Transaction Relations

**Feature Branch**: `006-open-leg-pending` (extends `006-transaction-relations`)

**Created**: 2026-07-21

**Status**: Complete

**Input**: User description: "重新设计账单导入后的去重、退款核销、转账配对、跨平台消费合并逻辑。核心原则：所有导入产生的原始事实都保留，不因配对/去重/退款核销而物理删除或改写原始记录；系统只追加记录事实之间的关系与判断证据/状态，再由报表和派生投影读取这些关系来避免重复统计或计算净额。配对规则可参考 main 分支当前实现。"

## Clarifications

### Session 2026-07-21

- Q: 单腿内部调拨如何建模？ → A: 不得建立无对侧 **accepted** 关系，也不改写该侧 category；用户补录/选择对侧后才可 accepted。v1 允许高信号进入 **开放单腿 pending**（见开放单腿澄清），与“禁止单腿 accepted”不矛盾。
- Q: 退款核销发生在哪一层？ → A: 导入只发布原始消费/退款事实；核销仅在导入后关系层通过 `refund_offset` 完成，convert/import 不得净额化改写消费金额。
- Q: 信用还款关系 kind 如何建模？ → A: 复用 `transfer_pair`，用 subtype/证据区分还款（如 subtype=`credit_repayment`）；用户可见语义与报表影响仍须可区分还款与普通转账。
- Q: 投资事件是否参与 v1 关系？ → A: 现金事实为主；投资事件仅可作为银证等 `transfer_pair` 的对侧正式事实参与，不把投资买卖/持仓事件做成 payment_mirror/refund/duplicate 主体。
- Q: 导入成功后的关系检查如何执行？ → A: 导入事务先提交正式事实；关系检查在提交后执行（同步或可重试任务均可），检查失败不得回滚已导入事实，且必须可重试、幂等。
- Q: 历史重复正式事实如何处理？ → A: 不引入 `duplicate_of` 关系标识；行级幂等仍是主防线。若因历史原因已存在实质重复正式事实，允许用户**手动逻辑删除**（须可审计），而不是用 duplicated/canonical 关系在报表层排重。
- Q: 关系检查何时触发？ → A: 每次成功导入一批后必须登记/执行一次关系检查；也允许对既有事实范围重跑（幂等）。
- Q: 候选与最终关系是否分表？ → A: 规格不强制分表；统一关系对象或候选/关系分表均可，但必须能表达 pending/accepted/rejected/superseded 与审计链。
- Q: 配对规则来源？ → A: 业务匹配信号与置信度分层优先复用 main 分支 `dedup` / `reconcile` / `transfer_rules` / convert 退款配对语义，但持久化与报表语义改为“关系 + 投影”，禁止 CSV 时代物理删除/改分类。
- Q: 账户别名是否覆盖导入路由？ → A: 否。别名仅增强关系候选与验证，不得无审计地替代导入 mapping 路由。
- Q: 本 feature 是否包含完整 Web UI？ → A: 必须提供 Review Inbox 的可审查入口（至少 CLI/查询契约可完成 accept/reject）；完整 Web 交互可作为同 feature 的可选交付，但审查状态机与审计不可缺。
- Q: v1 是否允许一笔退款事实分摊核销多笔消费？ → A: 不允许；每笔退款最多关联一笔消费，一笔消费可由多笔退款核销；无法唯一归属时进入 pending_review。
- Q: 触发关系检查时的数据范围如何定义？ → A: 使用“触发种子 + 跨批次候选”：导入后以本批新增正式事实为种子，在同 workspace 的全部既有事实中按关系类型的可索引条件与规则窗口查找候选，只创建或重评至少包含一个种子事实的关系；手动范围仅限定种子，候选可在范围外但必须满足规则。
- Q: 同一事实能否同时参与多个不同 kind 的 accepted 关系？ → A: 可以；规格必须定义跨 kind 兼容矩阵与确定性投影顺序，确保组合关系不会造成重复计次或重复核销。
- Q: 跨 kind 兼容矩阵与投影顺序是什么？ → A: `payment_mirror + refund_offset` 兼容；`transfer_pair + payment_mirror/refund_offset` 在同一事实上不兼容。收支投影先形成 payment_mirror 逻辑事件组，再排除 transfer_pair，最后按逻辑事件组应用 refund_offset；余额读取全部未逻辑删除的原始事实。
- Q: 可审计手动删除采用什么语义？ → A: 采用逻辑删除：保留 Formal Fact、RawRecord 与 revisions，追加删除墓碑/事件；投影排除该事实并原子 supersede 相关活跃关系。删除只让该事实实例退出当前投影，不永久封禁其 source identity。
- Q: 逻辑删除后同一 source identity 再导入如何处理？ → A: **正常导入发布新的活跃正式事实**。不得把旧的已删除事实静默“复活/取消删除”；旧墓碑与审计保留。行级幂等仅阻止“已存在活跃正式事实”的同 identity 重复发布。
- Q: 金额“可接受误差”如何处理？ → A: 不设金额容差；预期等额的同币种关系必须以 Decimal 严格相等才可自动 accepted，任意非零差额只能 pending。退款按精确剩余余额区分全额、部分与超额；跨币种关系不使用等额条件。
- Q: pending 与静默的召回原则？ → A: **pending 高召回优先于高精确**：不得漏掉任何「仍有合理配对机会」的 platform×bank 候选。分层为 auto（高置信）/ pending（近强或 main 会处理但我们不愿 auto）/ silent（连候选形态都不成立）。**凡 main 会自动处理、但本 spec 因过强而不 auto 的规则，MUST 降级为 pending，不得 skip。** 例：同账户同日 exact-2 超出 60s 短窗 → pending 而非静默；平台略晚于银行的近窗 → pending 而非静默。裸「不同账户、无文本、仅同日同额」仍可 silent（main 亦无此键）。
- Q: main 分支配对时间窗与规则族如何落到 v1？ → A: `payment_mirror` 仅 platform×bank、**同一 account_id**、全局 1:1。**(A) 强 auto**：等额 + |Δt|≤10s + 文本子串或卡尾/别名 + 唯一。**(B) 同账户短窗 exact-2 auto**：同一 account_id、等额、同号、恰好 1+1、平台≤银行、lag∈[0,**60s**]，可无文本。**(C) 短窗+文本 auto**：同账户 + ≤60s+文本/卡尾+唯一。**(P) pending 高召回（仅同账户）**：同日 exact 但 lag>60s 或平台更晚；有文本同日/短窗外未达 auto；金额精确差额近窗；多候选不唯一。**跨账户一律不 mirror**。`transfer_pair`/`refund_offset` 同理：main 会命中但我们不 auto 的 → pending。
- Q: `payment_mirror` 的 source 与账户约束？ → A: MUST 一侧为支付平台源（alipay/wechat 等）、一侧为银行通道源（icbc/ccb 等）。MUST NOT 将 bank×bank 或 platform×platform 建为 `payment_mirror`（后者若为调拨应走 `transfer_pair`）。**两侧 MUST 同一 account_id**（通常 mapping 把平台账单落到扣款卡账户）；跨账户（如微信零钱 vs 建行）MUST NOT 建 mirror，应静默或走 `transfer_pair`。
- Q: 镜像 weak/pending 的目标量级？ → A: 不确定时优先静默不推荐；pending 仅保留近强未达（如 10 秒内等额但文本不足、或强时间窗内唯一但金额有精确差额、或多近强候选冲突）。全量真实账本上 `payment_mirror` pending 应保持可审查量级（目标数十～低百条量级，而非「同日同额全进 inbox」）。
- Q: 多条支付平台/银行事实 mirror 时如何计次？ → A: accepted `payment_mirror` 可形成连通组；组内只计一次外部消费，且必须有确定性 canonical；若无法确定唯一 canonical 则 pending。自动匹配采用全局 1:1 greedy，任一事实最多参与一条新建 mirror 边（连通组可经后续边扩展，但单次检查不双配同一事实）。
- Q: main 规则何时视为正确？ → A: **除已与 constitution/本 spec 冲突者外**（物理删行、改 category/amount、浮点 0.01 容差、单腿改分类、无审计覆盖），若某条 main 规则在现网数据中**找不到可复核反例**，则应视为业务正确并汲取（可改造成关系落盘）。若找到系统性质反例，则**不得**原样汲取，必须收紧或改写。
- Q: main「同日同账户同额恰好 2 条跨源自动」如何汲取？ → A: **应汲取，但收窄时间窗并加平台≤银行**。用户澄清：银行仅通道名、平台有商户是同一支付的双记录，不是假阳性。实现：platform×bank、同一 account_id、等额、同号、键内恰好 1+1、**平台 occurred_at ≤ 银行**、Δt∈[0,**60s**] → auto `payment_mirror`（可无文本）。**不用自然日整天**。真实时延：exact-2 中 ~90% 已在 0–10s，平台更早约占 93%。平台更晚（常为银行 16:00 UTC 日切）默认不 auto。
- Q: main 镜像 10s+文本+1:1？ → A: **应汲取**。与当前 accepted 集合高度重合（约 1281/1335），真实数据未发现系统性质反例；落盘改为 relation 而非删行。
- Q: 逻辑删除后能否恢复？ → A: v1 不要求对已删除事实实例做用户自助“取消删除”。正确再入账路径是再次导入并发布**新**活跃正式事实；不得静默复活旧实例或其已 supersede 关系。若未来提供对旧实例的 undelete，必须另开 feature 且可审计。
- Q: “活跃正式事实”如何定义？ → A: 已发布且**未**被用户逻辑删除的正式事实实例。仅活跃事实进入余额/收支投影，并作为关系检查候选主体；已逻辑删除实例保留审计但不参与当前投影与自动匹配。
- Q: 逻辑删除后再导入同 identity 时，RawRecord / digest 如何处理？ → A: 文件级 digest 幂等不变——同一文件 digest 已成功导入则不再整文件重放。行级：若该 identity 无活跃正式事实，再导入（通常来自新文件或未消化重复路径）MUST 允许发布新活跃正式事实；可为新事实建立新的 raw→formal 关联或复用既有 RawRecord 身份，但 MUST 产生可区分的新正式事实实例，且旧删除实例保持删除。不得通过“改写旧实例为活跃”完成再入账。
- Q: 关系候选的业务幂等键是什么？ → A: 至少 `(workspace, kind, ordered_fact_pair, subtype_if_any)` 对活跃（非 superseded）关系唯一；同一键不得并存多个活跃 pending/accepted/rejected。rejected 占用该键以阻止自动重推荐；supersede 后新版本可持有新 revision 但仍可追溯旧键。
- Q: 已逻辑删除事实能否继续参与关系匹配？ → A: 否。自动关系检查与人工审查候选的主体 MUST 仅为活跃事实；已删除事实上的旧关系保持 superseded/历史，不得为已删除事实新建 accepted 关系。
- Q: 并发关系检查如何处理？ → A: 同一 workspace 内关系检查 MUST 可串行化到等价结果；并发重跑不得产生重复活跃关系或互相覆盖人工决策。实现可用锁/任务队列，但用户可见结果必须幂等且双后端等价。

- Q: 多候选/无唯一对侧时如何避免 pending 扇出？ → A: 对 `refund_offset` 与 `transfer_pair`（含 credit_repayment），当规则判定应对侧不唯一（≥2 合法候选）或锚点形态成立但 0 候选时，MUST 落 **开放单腿 pending**：只锚定一侧事实，对侧为空；建议对侧（若有）写入 evidence.candidate_fact_ids（排序 top-K，默认 20）与 candidate_count。唯一对侧的近强未达仍可双边 pending；唯一强匹配仍双边 auto accepted。`payment_mirror` 不采用单腿 pending。
- Q: 开放单腿 pending 与「单腿内部调拨」如何区分？ → A: **开放单腿 pending** 是关系对象的 `pending_review` 形态（对侧 fact 可空），供用户后选对侧并一步 accept 为双边；**永不**以单腿 accepted 影响报表。**单腿内部调拨**（FR-019）仍禁止无对侧 accepted，也禁止伪造对侧；可与开放单腿 pending 结合——高信号转账/退款锚点可进开放 pending 等人补对侧正式事实后绑定。
- Q: 单腿锚点如何确定？ → A: 由规则决定，不是任意 seed。`refund_offset` 锚 **退款腿**（正金额 + 退款信号）。`transfer_pair` 锚带更强转账/还款信号的一侧，evidence 记录 `anchor_role`；两侧信号相当时用确定性约定（计划阶段固化，须可测）。
- Q: 开放单腿如何审查与幂等？ → A: Review 展示锚点摘要、kind、evidence（含候选 id 列表）。用户 accept 时 **必须指定 other_fact_id**（一步绑定并 accepted，须通过合法性校验）。reject 占用 **开放锚点键** `(workspace, kind, subtype?, anchor_fact_id, open)`，自动重跑不得再扇出同锚点第二条开放 pending。双边业务键仍为 ordered_fact_pair。accepted 对侧 MUST NOT 为空。单腿 pending MUST NOT 参与投影。
- Q: 哪些 seed 不得再写多边扇出？ → A: 多候选时每个锚点最多 1 条开放单腿 pending。`refund_offset` 的 **消费 seed** MUST NOT 再为同一退款写入多条多候选双边 pending；退款侧统一收敛为单腿或唯一双边。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 导入后自动建立跨平台消费关系 (Priority: P1)

作为记账用户，我分别导入支付宝/微信账单和银行卡账单后，同一笔外部消费会在系统中留下两条正式事实；系统识别它们是同一次消费的两个视角，建立 `payment_mirror`（跨平台消费镜像）关系，而不是删除其中一条。消费报表只统计一次，但扣款账户余额仍反映真实流水。

**Why this priority**: 这是最常见的双计来源；不解决则收支报表不可信，且直接违反“原始事实不可变”原则。

**Independent Test**: 仅导入一笔支付宝消费与对应银行扣款；完成后存在两条正式事实与一条 accepted 或 pending 关系；accepted 时消费统计只计一次，pending 时继续不改变报表；双后端结果等价。

**Acceptance Scenarios**:

1. **Given** 支付宝事实：2026-06-13 23:15，麦当劳，-30.00 CNY，付款方式含建行卡尾号 1234；银行事实：同秒附近，-30.00 CNY，卡尾号 1234，文本含支付通道或商户交叉，**When** 两笔事实均已入库并完成关系检查，**Then** 两条事实都保留，并存在 accepted `payment_mirror`（platform×bank）。
2. **Given** 已 accepted 的 `payment_mirror` 关系，**When** 计算消费报表，**Then** 该外部消费只计入一次；余额类投影仍保留双方账户的真实流水影响。
3. **Given** 同一候选关系检查已执行过，**When** 对同一批新增事实重复执行关系检查，**Then** 不重复创建相同 accepted 关系或相同 pending 候选（幂等）；全局 1:1 不使同一事实双配。
4. **Given** 同账户、platform×bank、等额、平台时间 ≤ 银行、Δt=12s、银行对方仅为「支付宝（中国）网络技术有限公司」无商户字、且该键恰好 2 条，**When** 关系检查，**Then** auto-accept `payment_mirror`（短窗 exact-2，允许无文本）。
5. **Given** 同上但 Δt=2 小时（仍同日）或平台时间晚于银行，**When** 关系检查，**Then** MUST NOT 因 exact-2 自动 accepted。
6. **Given** 仅同日同额、无文本/卡尾、不同账户或非恰好-2，**When** 关系检查，**Then** MUST NOT 灌 pending。
7. **Given** 等额 + ≤10s + 文本/卡尾 + 唯一，**When** 关系检查，**Then** auto-accepted。
8. **Given** 等额 + 文本 + Δt=30s + 唯一 platform×bank，**When** 关系检查，**Then** 可 auto-accepted（≤60s 有文本分支）。
9. **Given** 两条均为银行通道（转账支取 vs 银转证），**When** 关系检查，**Then** MUST NOT 建立 `payment_mirror`。
10. **Given** accepted mirror 连通组，**When** 报表，**Then** 外部消费只计一次且 canonical 确定。

---

### User Story 2 - 内部转账配对且不计入外部收支 (Priority: P1)

作为记账用户，我在账户 A 转出、账户 B 转入后，两边事实都保留；系统建立 `transfer_pair` 关系。两边余额都正确变化，但外部收入/支出报表不把这笔内部调拨算成消费或收入。

**Why this priority**: 转账双计是第二大报表污染源；且与跨平台镜像语义不同——转账必须保留双边余额。

**Independent Test**: 导入或存在一对一正一负、不同账户、同币种、时间接近且带转账信号的事实；建立 `transfer_pair`；余额双边生效、收支排除；双后端等价。

**Acceptance Scenarios**:

1. **Given** 账户 A：-1000.00 CNY「转账支取」；账户 B：+1000.00 CNY「转账存入」，时间接近且候选唯一，**When** 关系检查，**Then** 两条事实保留，建立 accepted `transfer_pair`（或弱匹配 pending），证据含金额差、时间差、信号与规则标识。
2. **Given** accepted `transfer_pair`，**When** 查看账户余额，**Then** A 减少、B 增加均生效。
3. **Given** accepted `transfer_pair`，**When** 查看外部收入/支出报表，**Then** 该对不计入外部消费或收入。
4. **Given** 跨币种转账两侧金额/币种不同，**When** 建立关系，**Then** 双方金额与币种原样保留在各自事实中；汇率（若有）仅作为关系证据/元数据，不得改写事实金额。
5. **Given** 同币种转账两侧除金额外均为强匹配，但绝对金额存在任意非零差额（例如 -100.00 与 +99.99 CNY），**When** 关系检查完成，**Then** 可创建含精确 `amount_delta` 的 pending 候选，但不得自动 accepted；差额不得被舍入或吞掉。
6. **Given** 同币种、不同账户、一正一负、绝对金额严格相等、有效时间差 ≤10 秒、文本含转账强信号且候选唯一，**When** 关系检查，**Then** 可自动 accepted `transfer_pair`。
7. **Given** 同日同额现金账户间“银联入账/电子汇入”与“无卡付/转账支取”组合且候选唯一，**When** 关系检查，**Then** 可自动 accepted（即使入账腿时间为 00:00:00）；缺少该信号组合或候选不唯一时不得 auto-accept。

---

### User Story 3 - 退款核销不改写原消费金额 (Priority: P1)

作为记账用户，我有一笔消费和后续全额/部分退款；系统保留两条事实，建立 `refund_offset` 关系，报表据此计算原始消费、退款额与净消费，而不是把原消费金额改写成净额或删除退款。

**Why this priority**: 退款是财务正确性核心；main 的 convert 会改写消费金额，本 feature 明确禁止入库前净额化，只通过关系层核销。

**Independent Test**: 消费 -100 与退款 +30 入库后建立 `refund_offset`；原消费金额仍为 -100；净消费为 -70；支持多笔退款累计且不超额自动核销；双后端等价。

**Acceptance Scenarios**:

1. **Given** 消费 2026-01-01 商家 A -100.00 CNY；退款 2026-01-05 商家 A +30.00 CNY，强匹配（商户/订单/时间/金额可核销），**When** 关系检查，**Then** 两条事实保留，建立 accepted 或 pending 的 `refund_offset`，原消费金额仍为 -100.00。
2. **Given** 同一消费上已有部分退款关系，**When** 再来一笔不超额退款且可匹配，**Then** 允许多条 `refund_offset` 指向同一消费，累计核销不超过原消费可核销余额。
3. **Given** 退款金额将超过原消费剩余可核销金额，**When** 自动规则评估，**Then** 不得自动 accepted 超额核销；进入 `pending_review` 或失败关闭该候选，且不改写事实。
4. **Given** accepted `refund_offset` 集合，**When** 报表计算，**Then** 可得到原始消费、退款合计、净消费；pending 不参与净额。
5. **Given** 消费事实与银行扣款已组成 accepted `payment_mirror`，且退款事实与该消费组成 accepted `refund_offset`，**When** 计算收支与余额投影，**Then** 收支先把镜像事实归并为一个逻辑消费事件并仅应用一次退款核销，余额仍保留每条原始事实的真实影响。
6. **Given** 退款时间晚于消费不超过 14 天、商户/订单强信号、剩余可核销余额足够且候选唯一，**When** 关系检查，**Then** 可自动 accepted；若仅弱描述匹配、多候选、或超过 14 天且无订单/交易号锁定，**Then** 必须 `pending_review`。
7. **Given** 退款时间晚于消费 15–30 天但具备订单号/交易号锁定且其余强条件满足，**When** 关系检查，**Then** 仍可自动 accepted；超过 30 天窗口的候选不得 auto-accept。
8. **Given** 导入路径发布带 `offset_*`/`proposed_action` 历史字段痕迹的事实，**When** 关系检查与报表运行，**Then** 核销结果只以独立 `refund_offset` 关系为准，不得因行内 offset 字段静默净额化。

---

### User Story 4 - 信用还款关系 (Priority: P2)

作为记账用户，我从储蓄卡还款到信用卡后，两边事实都保留；系统建立带还款 subtype 的 `transfer_pair`（证据标明 credit repayment），避免把还款误计为普通消费或收入，同时保留储蓄卡余额减少与信用卡负债减少。

**Why this priority**: 与 transfer 相关但语义更具体；报表误分类会严重扭曲消费结构。

**Independent Test**: 储蓄卡 -5000 与信用卡 +5000 配对为还款关系；双边余额正确；外部收支排除；同币种与跨币种均有证据；双后端等价。

**Acceptance Scenarios**:

1. **Given** 储蓄卡 -5000.00 CNY「信用卡还款」与信用卡 +5000.00 CNY「还款入账」，高置信且唯一，**When** 关系检查，**Then** 建立 accepted `transfer_pair`（subtype/证据=`credit_repayment`），两条事实保留。
2. **Given** accepted 还款关系，**When** 计算外部消费/收入，**Then** 不把该还款计为普通消费或收入。
3. **Given** 跨币种还款（如 CNY 还 USD 卡），**When** 建立关系，**Then** 双方金额/币种保留；证据记录时间差、账户类型关系与（若可得）市场汇率及隐含汇率偏差，不改写事实。
4. **Given** cash→loan 同币种、绝对金额严格相等、有效时间差 ≤600 秒、文本含还款信号且候选唯一，**When** 关系检查，**Then** 可自动 accepted 为 `transfer_pair` + `credit_repayment`。
5. **Given** cash→loan **购汇/跨币种还款**（出腿含「购汇还款」等 FX 信号，或两侧币种不同）、有效时间差 ≤60 秒、多个 loan 入账候选，且当日市场汇率可获取，**When** 关系检查对每个候选计算隐含汇率相对市场汇率的偏差并得到置信度，**Then**：
   - 若**恰好 1 个**候选达到高置信阈值且显著优于其余候选 → auto-accept 双边 `transfer_pair` + `credit_repayment`；
   - 若汇率不可获取、无候选达高置信、或多个候选同属高置信难分胜负 → 降级为 `pending_review`（优先双边近邻；多候选不唯一时可开放单腿），evidence MUST 含双方金额/币种、时间差、（若有）市场汇率、隐含汇率、偏差与候选列表。
6. **Given** 用户查看关系列表/审查入口，**When** 存在普通 `transfer_pair` 与 subtype=`credit_repayment` 的关系，**Then** 用户可见语义可区分（标签/证据/subtype），不得混同为不可区分的“转账”。

---

### User Story 5 - 历史重复事实允许可审计的手动删除 (Priority: P2)

作为运维/记账用户，行级导入幂等仍优先防止重复发布（仅对**活跃**正式事实）。若因历史原因已经存在两条实质重复的正式事实，系统**不**用 `duplicate_of` 或任何 duplicated/canonical 关系标识在报表层排重；我可以**手动逻辑删除**其中错误/多余的正式事实。删除通过追加墓碑/事件表达，保留 Formal Fact、RawRecord 与 revisions；被删事实退出当前投影且相关活跃关系被 supersede。删除必须可审计，且不得被自动关系检查静默执行。若我之后再次导入同一 source identity，系统应**正常发布新的活跃正式事实**，而不是把旧删除实例静默复活，也不得因“曾经删除过”而永久拒绝该 identity。

**Why this priority**: 导入幂等是主防线；对已漏网的重复，用户明确选择“删掉错误事实”而不是“两条都留着靠关系伪装成一条”。关系层只处理镜像、转账、退款等“多视角/多腿仍应保留”的情况，不处理“本不该存在的重复事实”。逻辑删除是对错误实例的治理，不是对 source identity 的永久封禁。

**Independent Test**: 活跃同 identity 再导入不重复发布；人为准备两条重复正式事实后，用户可删除其中一条并留下审计；删除后报表不再双计；删除后再导入同 identity 会发布新活跃事实；自动关系检查不得创建 `duplicate_of`；双后端等价。

**Acceptance Scenarios**:

1. **Given** 同一 provider/source identity 对应的**活跃**正式事实已存在，**When** 在不同文件中再次出现并导入，**Then** 不重复发布正式事实（行级幂等继续有效）。
2. **Given** 已存在两条实质重复正式事实，**When** 用户手动逻辑删除其中一条，**Then** Formal Fact、RawRecord 与 revisions 仍保留，追加含操作者、时间、原因/引用的删除墓碑/事件；被删事实不再参与余额/收支统计，其相关活跃关系在同一操作中被 supersede，另一条保留。
3. **Given** 某正式事实已逻辑删除且仅该删除实例持有该 source identity（无其他活跃同 identity 事实），**When** 再次导入包含同一源记录的文件，**Then** System MUST **正常发布新的活跃正式事实**；MUST NOT 静默取消删除/复活旧实例；旧删除墓碑与审计 MUST 保留。
4. **Given** 已存在两条实质重复正式事实，**When** 自动关系检查运行，**Then** MUST NOT 创建 `duplicate_of` 或等价 duplicated 标识来“保留双事实只统计其一”。
5. **Given** 无用户删除授权的自动流程，**When** 关系检查或规则升级，**Then** MUST NOT 静默删除或逻辑删除正式事实。
6. **Given** 逻辑删除后又成功导入同 identity 的新活跃事实，**When** 计算投影，**Then** 仅新活跃事实进入余额/收支；旧删除实例仍排除且可审计追溯。

---

### User Story 6 - Review Inbox 审查弱匹配 (Priority: P1)

作为用户，我可以在审查入口看到所有 `pending_review` 候选：关系类型、两条事实关键字段、证据、置信度；我可以 accept、reject 或稍后处理。accept 后影响报表；reject 后不得反复推荐同一候选；操作可审计。

**Why this priority**: 自动规则无法覆盖全部弱匹配；没有审查入口则只能错误自动确认或永久漏配。

**Independent Test**: 制造弱匹配 pending；accept/reject 后状态与审计正确；reject 后重跑不重生同候选；accept 后报表变化；双后端等价。

**Acceptance Scenarios**:

1. **Given** 存在 pending 候选，**When** 打开 Review Inbox / 列表查询，**Then** 看到 kind、双方事实摘要、evidence、confidence、rule_id。
2. **Given** pending 候选，**When** 用户 accept 并给出原因（可选），**Then** 状态变为 accepted，记录操作者与时间，派生报表开始使用该关系。
3. **Given** pending 候选，**When** 用户 reject 并给出原因，**Then** 状态变为 rejected 并持久化；后续同一事实对+同类关系不得再自动推荐为 pending。
4. **Given** 用户已 accept/reject 的关系，**When** 后续自动规则重跑，**Then** 不得静默覆盖人工决策；若确需替换，必须 supersede 旧关系并保留审计链。
5. **Given** pending 候选，**When** 用户选择 ignore/later，**Then** 候选状态仍为 `pending_review`（可记录稍后处理意图），继续出现在审查入口，且继续不影响报表，直至 accept、reject 或被 supersede。
6. **Given** 审查入口仅提供 CLI/查询契约而无完整 Web UI，**When** 用户对 pending 执行 accept/reject，**Then** 状态、审计与报表影响与具备 Web UI 时等价。
7. **Given** 一笔退款存在多笔同商户可核销消费候选，**When** 关系检查完成，**Then** 仅存在 **1** 条 `refund_offset` 开放单腿 `pending_review`（锚点=退款腿，对侧为空），evidence 含 `candidate_fact_ids`/`candidate_count`，**不得**为每个消费候选各写一条双边 pending。
8. **Given** 开放单腿 `refund_offset` pending，**When** 用户 accept 并指定合法消费 fact id，**Then** 关系变为双边 accepted，对侧非空，净消费投影开始使用该关系；未指定对侧 MUST 拒绝 accept。
9. **Given** 开放单腿 `transfer_pair` pending（多候选或无唯一对侧），**When** 用户指定合法对侧 accept，**Then** 成为双边 accepted 转账/还款；单腿阶段外部收支/余额投影不得按已配对排除。
10. **Given** 用户 reject 某锚点的开放单腿 pending，**When** 自动关系检查重跑，**Then** 不得再自动创建同一开放锚点键的 pending。

---

### User Story 6b - 开放单腿 pending 收敛扇出 (Priority: P1)

作为用户，当系统识别到退款或转账意图但无法唯一确定对侧时，我在审查入口看到的是 **一条** 挂在锚点事实上的待办（对侧为空，可带建议候选列表），而不是笛卡尔积式的多条双边候选；我随后选择对侧并确认后，关系才生效。

**Why this priority**: 多候选双边 pending 使 Review Inbox 不可用；单腿收敛是可审查性的前提，且不牺牲“后补对侧”能力。

**Independent Test**: 构造 1 退款 × N 同商户消费 → 仅 1 条开放 pending；accept 指定对侧后投影正确；0 候选锚点可进开放 pending；唯一弱对侧仍可双边 pending；mirror 不产生单腿；双后端等价。

**Acceptance Scenarios**:

1. **Given** 退款锚点形态成立且合法消费候选 ≥2，**When** 检查完成，**Then** 1 条开放单腿 pending，candidate_count≥2，对侧 fact 为空。
2. **Given** 退款锚点形态成立且 0 消费候选，**When** 检查完成，**Then** 1 条开放单腿 pending，candidate 列表可空，供用户后选任意合法消费。
3. **Given** 转账/还款信号锚点且对侧不唯一，**When** 检查完成，**Then** 1 条 `transfer_pair`（或 subtype credit_repayment）开放单腿 pending。
4. **Given** 唯一近强对侧（未达 auto），**When** 检查完成，**Then** MAY 双边 pending（对侧已填），不必强制单腿。
5. **Given** 开放单腿 pending，**When** 报表运行，**Then** 与无该关系时净额/排除结果相同（单腿不生效）。
6. **Given** 开放单腿 pending，**When** 用户 accept 时指定的对侧不合法（符号/币种/时间序/超额/跨 kind 冲突等），**Then** accept 失败关闭，关系仍 pending 或保持未绑定，不得部分写入 accepted。

---

### User Story 7 - 账户别名增强候选但不劫持导入 (Priority: P2)

作为用户，我可以维护卡尾号、支付方式文本、亲属卡/虚拟卡等账户别名，用于增强关系候选匹配；别名命中必须进入 evidence，冲突可见；别名不得无审计地覆盖导入账户路由。

**Why this priority**: 卡尾号与支付方式是跨平台镜像的关键信号；但导入路由必须保持现有 mapping 合同。

**Independent Test**: 配置尾号别名后，镜像候选 evidence 含 alias 命中；别名冲突可见；导入路由不因别名静默改账户。

**Acceptance Scenarios**:

1. **Given** 卡尾号 1234 映射到账户「建行储蓄」，**When** 关系检查发现支付方式含 1234 的平台账单与该账户银行账单，**Then** evidence 记录 alias 命中，并可用于提高置信度。
2. **Given** 同一别名映射到多个账户（冲突），**When** 匹配，**Then** 冲突可见，不得静默任选其一为唯一真相。
3. **Given** 导入阶段 mapping 将行路由到账户 A，**When** 存在别名暗示账户 B，**Then** 导入账户归属仍以 mapping 为准；别名仅影响关系层候选/验证。

---

### User Story 8 - 规则升级可 supersede 旧关系 (Priority: P3)

作为系统维护者，规则版本升级后可以产生新关系版本，将旧关系标记为 `superseded`，而不是原地覆盖历史判断；审计可解释为何升级。

**Why this priority**: 保证可审计与可回放；避免“最新结果抹掉历史决策”。

**Independent Test**: 对同一事实对先有 v1 accepted，再以 v2 替换；旧记录 status=`superseded`，新记录 accepted，历史可查。

**Acceptance Scenarios**:

1. **Given** 已有自动 accepted 关系（rule v1），**When** 更高版本规则以新证据 supersede，**Then** 旧关系变为 superseded，新关系写入并带新 rule_id/evidence。
2. **Given** 人工 accepted/rejected，**When** 自动规则运行，**Then** 不得静默覆盖；仅在明确的 supersede 流程下替换并保留审计。
3. **Given** 旧关系已被 supersede，**When** 查询审计/历史，**Then** 仍可看到旧关系的 status、evidence、rule_id 与被替换原因/新关系引用。

---

### User Story 9 - 后到账单补齐跨批关系 (Priority: P1)

作为记账用户，我先导入银行账单、几天后再导入支付宝账单；系统不需要我重导旧文件，也能以新批新增事实为种子，与先前批次中的事实建立 `payment_mirror` / `transfer_pair` / `refund_offset` 等关系。

**Why this priority**: 关联账单经常不同时到达；若只检查同批内关系，报表双计/漏配会长期存在。

**Independent Test**: 先导银行扣款，后导对应支付宝消费；后批检查后出现跨批 `payment_mirror`；旧批事实不被改写；双后端等价。

**Acceptance Scenarios**:

1. **Given** 批次 A 已导入银行扣款事实，**When** 批次 B 导入对应支付平台消费并完成关系检查，**Then** 以 B 的新增事实为种子，与 A 中符合规则的事实建立关系（accepted 或 pending）。
2. **Given** 仅手动指定“重跑批次 B 的种子范围”，**When** 执行关系检查，**Then** 候选仍可来自 A 或其他范围外批次，只要满足规则窗口。
3. **Given** 跨批关系已建立，**When** 再次对 B 执行关系检查，**Then** 幂等，不重复创建相同活跃关系。

---

### Edge Cases

- 关系检查在导入提交后执行；失败不得回滚或破坏已成功导入的事实；检查本身可重试且幂等。
- 重复执行同一批新增事实的关系检查必须幂等：不重复创建相同 accepted/pending/rejected 业务键。
- 活跃关系业务键至少为 `(workspace, kind, ordered_fact_pair, subtype_if_any)`；同一键不得并存多个活跃 pending/accepted/rejected。
- 同一 workspace 并发关系检查必须收敛到等价结果，不得产生重复活跃关系或静默覆盖人工决策。
- 导入触发的检查以本批新增正式事实为种子；候选可来自同 workspace 的任意既有批次，不得把候选限制在本批内。
- 手动按批次、事实或日期范围重跑时，所选范围只限定种子事实；候选可位于所选日期范围之外，但必须满足关系类型的时间窗口或外部标识等规则。
- 候选搜索必须使用关系类型的有界、可索引条件（金额、币种、时间窗口、账户类型、卡尾号、source 组合、外部 id 等）从同 workspace 既有**活跃**事实中筛选；不得以全库无界扫描作为增量检查的正确性前提。
- 已逻辑删除事实 MUST NOT 作为自动匹配主体或新 accepted 关系的端点；其历史关系仅作审计。
- 增量检查只创建或重评至少包含一个种子事实的关系；全 workspace 重算必须由显式全量重跑触发。
- 弱匹配多候选：不得自动任选；应 pending 或放弃自动确认。
- 历史错误重复正式事实：允许用户可审计逻辑删除；Formal Fact、RawRecord、revisions 与 source identity 均保留，当前投影排除该事实，相关活跃关系原子 supersede；禁止 `duplicate_of`/duplicated 标识排重，禁止自动静默删除。
- 逻辑删除只作用于具体事实实例：投影排除旧实例，不永久封禁 source identity。无其他活跃同 identity 事实时，再次导入 MUST 正常发布新活跃正式事实；MUST NOT 静默复活旧实例。
- 再导入新活跃事实时，MUST 产生可区分的新正式事实实例；可为新 raw→formal 关联或复用 RawRecord 身份，但不得把旧删除实例改回活跃。
- 文件级 digest 幂等保持：同一已成功 digest 不因逻辑删除而自动整文件重放。
- v1 不要求对已删除事实实例做用户自助“取消删除”；再入账路径是重新导入发布新实例。
- 行级幂等仅阻止“已存在活跃正式事实”的同 identity 重复发布；已逻辑删除实例不占用活跃幂等槽位。
- 退款时间早于消费：不得自动 accepted。
- 入库前净额化消费金额、删除退款行、或双写“净额事实 + 关系”均被禁止。
- 每笔退款最多关联一笔消费，一笔消费可匹配多笔退款；退款存在多个可能消费且无法唯一归属时必须 pending，不得拆分或自动任选。
- 不存在金额“可接受误差”：对预期等额的同币种关系，任意非零 Decimal 差额都禁止自动 accepted，可作为含精确 `amount_delta` 的 pending 候选；候选搜索范围不得被解释为账务容差。main 分支代码中的 `0.01` 浮点比较不得作为本 feature 的账务容差依据。
- 退款以精确剩余可核销余额判断：退款额等于剩余余额才是全额，低于剩余余额是部分退款，高于剩余余额是超额；不得用容差把差额归零。跨币种关系不使用金额等额条件。
- 退款候选窗口与 auto-accept 边界：默认候选 ≤30 天；auto-accept 默认 ≤14 天；仅当订单号/交易号等锁定信号存在时，15–30 天仍可 auto-accept；超过 30 天不得 auto-accept。
- 红包/转账/收款/提现类事实对 `refund_offset` 采用**非对称**规则（不得整类双边封禁）：
  1. **正金额（收入）侧**：裸「微信红包 / 转账备注:微信转账 / 群收款入账 / 银联入账」等 **无退款词** 的收入 MUST NOT 作为退款腿/退款种子；应留给 `transfer_pair` 或人工。
  2. **负金额（支出）侧**：红包/转账/群收款/二维码收款等支出 **可以** 作为退款关系的消费腿，当且仅当退款腿是**同族 P2P 退款**（文本同时含退款信号与红包/转账族标记，如「微信红包-退款」），并满足金额/窗口/唯一等匹配条件；此类配对 MAY auto-accept（强规则），不得因消费腿属于红包/转账族而静默丢弃。
  3. **商品退款**（「退款-商品名」等非 P2P 退款）MUST NOT 以红包/转账/收款/提现支出为消费腿；此类支出对商品退款保持排除，避免 pending 洪水。
  4. 「消费退货」等银行独有退款信号仍为合法退款种子（见下条）。
- 银行侧独有退款（如建行描述「消费退货」+ 商户名）即使无支付宝/微信对应行，仍可作为退款种子参与 `refund_offset`；不得因缺少 platform 侧而拒绝。
- 裸银行通道名入账（如仅「支付宝（中国）网络技术有限公司」无退款词）优先走 `payment_mirror` 到平台退款，不得单独作为无退款词的 refund 种子。
- 跨币种转账/还款：禁止隐式换算改写事实金额。
- `payment_mirror` 不是“重复事实可删”：镜像两侧都是真实流水视角，必须保留；真正的错误重复正式事实通过用户可审计手动逻辑删除处理，不建 `duplicate_of`。
- `payment_mirror` MUST 为 platform×bank；bank×bank（如转账支取 vs 银转证）不得建 mirror。
- 裸同日同额无短窗条件：不建 mirror pending；同账户 [0,60s] exact-2 且平台≤银行可无文本 auto。
- 镜像自动匹配 1:1 greedy；不确定优先静默而非灌满 Review Inbox。
- accepted `payment_mirror` 可形成 n 元连通组（例如支付宝 + 微信 + 银行）；组内外部消费只计一次，且必须有确定性 canonical；canonical 冲突时 pending，不得静默任选。
- 同一事实可同时参与兼容的不同 kind accepted 关系：`payment_mirror + refund_offset` 兼容；`transfer_pair + payment_mirror`、`transfer_pair + refund_offset` 在同一事实上不兼容。不得仅因事实已有 `payment_mirror` 就拒绝 `refund_offset`；自动规则遇到不兼容 accepted 关系时不得覆盖或并存，必须保留冲突证据并进入 pending，人工接受前必须先 supersede 冲突关系。
- 收支投影顺序固定为：先将 accepted `payment_mirror` 连通事实归一为逻辑事件组，再排除 accepted `transfer_pair`（含 credit repayment）两侧，最后以逻辑事件组身份应用 accepted `refund_offset`。同一退款镜像组只能贡献一次核销；余额投影始终读取全部未逻辑删除的原始正式事实，不按上述关系删腿。
- `transfer_pair` 承载普通内部转账与信用还款：还款通过 subtype/证据区分；用户可见语义与报表影响仍必须可区分。
- 单腿内部调拨（仅一侧在账本内，如货基申赎、银证一侧缺失）：不得建立无对侧的 **accepted** 双边关系，不得伪造对侧事实，不得改写该侧 category；高信号锚点 MAY 进入 **开放单腿 pending** 供用户补录/选择对侧正式事实后绑定。main `transfer_rules` 文本信号仅可作为补录提示/候选搜索线索，不得单独 accepted。
- **开放单腿 pending**（`refund_offset` / `transfer_pair`）：对侧 fact 可空仅当 status=`pending_review`；accepted/rejected 业务结果中对侧为空非法。单腿 MUST NOT 参与投影。
- 多候选扇出：同一退款/转账锚点 MUST NOT 因 N 个候选消费/对侧而写入 N 条双边 pending；MUST 收敛为 1 条开放单腿（或 1 条唯一双边）。
- 开放单腿 accept 必须一步提供合法 other_fact_id；系统 MUST 校验对侧与 kind 规则（退款：消费负金额、不超额、时间序等；转账：对向账户与信号/金额规则）及跨 kind 兼容后再 accepted。
- `payment_mirror` MUST NOT 使用开放单腿 pending；多候选 mirror 仍按 FR-016 处理（greedy/pending 双边或静默）。
- 不得创建占位/虚假正式事实充当对侧。
- locked/人工锁定事实：不得被自动关系检查改写事实字段；关系仍可建立，但人工决策优先。
- rejected 后再出现更强新证据：仅允许通过 supersede 明确重新提出，不得无审计复活。
- ignore/later 不是独立状态：候选保持 `pending_review`，继续不影响报表，仍可被审查，直到 accept/reject/supersede。
- 既有行内 `offset_*`、`proposed_action`、`transfer_account` 字段不得成为 accepted 关系或净额的权威来源；关系检查不得为表达配对结果而改写这些字段；新导入不得依赖它们完成核销/转账标记。
- PostgreSQL 与 SQLite：关系持久化、状态机、幂等、报表影响、审查操作、逻辑删除、再导入发布新活跃事实的用户可见结果必须等价；禁止自动回退、双写、隐式跨后端迁移。
- 允许的运行差异：锁实现、并发吞吐、底层驱动错误信息、任务调度时延；不得造成账务关系、报表净额或审查状态分叉。

## Requirements *(mandatory)*

### Functional Requirements

#### 不可变与导入基础

- **FR-001**: System MUST NOT 因自动去重、配对、退款核销或关系检查而物理删除或逻辑删除正式事实（`CashTransaction` / `InvestmentEvent` 等）或 RawRecord。跨平台镜像、转账、退款等场景 MUST 保留相关正式事实。若存在错误的实质重复正式事实，用户 MAY 通过**可审计的手动逻辑删除**将多余事实排除出当前投影；Formal Fact、RawRecord 与 revisions MUST 保留，自动流程 MUST NOT 静默删除。
- **FR-001a**: “活跃正式事实”定义为已发布且未被用户逻辑删除的正式事实实例。余额/收支投影、关系检查候选主体与行级活跃幂等占用 MUST 仅统计活跃正式事实。
- **FR-002**: System MUST NOT 把两条真实流水物理合并成一条正式事实。
- **FR-003**: System MUST NOT 在自动配对后直接改写原事实的金额、账户、分类、来源或币种来表达关系结果。
- **FR-004**: 既有导入链路合同 MUST 保持：workspace 隔离、文件级幂等（workspace+source+digest）、行级幂等（workspace+source_type+source_identity，**仅对活跃正式事实**）、raw→formal 关联、原始记录不可变、正式事实追加式 revision 审计、Decimal 金额、统一时区可比较时间。
- **FR-005**: 同一 RawRecord 若被路由到不同账户，System MUST 拒绝，不得静默改归属。
- **FR-006**: 本 feature MUST NOT 恢复 CSV 时代的物理删除式 dedup/reconcile，也 MUST NOT 把 pending CSV 作为候选审查机制。
- **FR-006a**: 对行级幂等未挡住或历史已存在的实质重复正式事实，System MUST 提供用户触发的可审计逻辑删除能力：追加含操作者、时间、原因/引用的删除墓碑/事件，保留 Formal Fact、RawRecord、revisions 与原 source identity，将该**事实实例**排除出当前投影，并在同一原子操作中 supersede 其相关活跃关系。逻辑删除 MUST NOT 永久封禁该 source identity。当不存在其他活跃同 identity 正式事实时，再次导入同一 source identity MUST **正常发布新的活跃正式事实**；MUST NOT 静默取消删除或复活旧实例；旧墓碑与审计 MUST 保留。新活跃事实 MUST 是可区分的新正式事实实例（可新建 raw→formal 关联或复用 RawRecord 身份），不得通过改写旧删除实例为活跃完成再入账。MUST NOT 以 `duplicate_of`/duplicated 标识代替删除，也 MUST NOT 由关系检查自动删除。v1 MUST NOT 要求对已删除实例做用户自助 undelete。
- **FR-006b**: System MUST NOT 将 CashTransaction 行内的 `offset_group` / `offset_role` / `offset_strength` / `offset_source` / `offset_rule_hint` / `offset_match_type` / `proposed_action` / `transfer_account` 作为 accepted 关系、净消费或内部转账排除的权威来源。关系检查 MUST NOT 为表达配对/核销结果而改写这些字段；新导入路径 MUST 发布原始事实并由关系层表达核销与转账，不得依赖上述字段完成业务结果。历史残留字段 MAY 只读保留以兼容旧数据展示，但投影 MUST 以独立关系对象为准。
- **FR-006c**: 行级幂等 MUST 定义为：同一 workspace + source_type + source_identity 在已存在**活跃**正式事实时不得再发布另一条活跃正式事实。仅存在已逻辑删除的同 identity 事实时，导入 MUST 允许发布新活跃事实。文件级 digest 幂等不受本条改变：同一已成功 digest 不得因逻辑删除而自动整文件重放。
- **FR-006d**: 自动关系检查与人工审查候选的主体 MUST 仅为活跃正式事实。System MUST NOT 为已逻辑删除事实新建 accepted 关系；已删除事实上的历史关系仅保留审计/superseded 轨迹。

#### 关系模型与状态

- **FR-007**: System MUST 以数据库原生对象持久化事实间关系（或候选与关系的等价模型），至少包含：workspace、kind、锚点/对侧事实引用、status、rule_id、confidence、evidence、创建者/来源、时间、版本/revision。对 `refund_offset` 与 `transfer_pair`，**开放单腿** `pending_review` 允许对侧事实引用为空；`accepted` 的对侧 MUST 非空。`payment_mirror` 的两侧 MUST 始终非空。
- **FR-008**: 关系 kind 至少支持：`payment_mirror`（跨平台消费镜像）、`transfer_pair`、`refund_offset`。信用还款不使用独立顶层 kind，而以 `transfer_pair` + subtype/证据（如 `credit_repayment`）表达；用户可见语义与报表影响 MUST 仍可区分普通内部转账与信用还款。MUST NOT 引入 `duplicate_of` 或等价 duplicated/canonical 关系 kind 来表达“重复事实只统计一条”。
- **FR-009**: 关系状态 MUST 支持：`pending_review`、`accepted`、`rejected`、`superseded`。
- **FR-010**: `pending_review`（含开放单腿）与 `rejected` MUST NOT 影响报表与当前派生投影；仅 **双边** `accepted` 影响当前报表；`superseded` 不参与当前报表但保留审计。开放单腿 MUST NOT 因“有锚点”而提前排除转账或核销退款。
- **FR-011**: 自动 accepted MUST 保存 rule_id、confidence 与 evidence；人工 accept/reject MUST 保存操作者、时间与原因。
- **FR-012**: 人工决策 MUST NOT 被后续自动规则静默覆盖；替换 MUST 通过新版本 + `superseded` 审计链完成。
- **FR-013**: 同一 workspace 内，同一关系业务键上的活跃（非 superseded）表达 MUST 唯一。**双边**业务键至少包含 `(workspace, kind, ordered_fact_pair, subtype_if_any)`。**开放单腿**业务键至少包含 `(workspace, kind, subtype_if_any, anchor_fact_id, open)`，同一锚点不得并存多条活跃开放 pending。两类键均不得并存多个活跃 pending/accepted/rejected。
- **FR-013a**: 同一正式事实 MAY 同时参与多个不同 kind 的 accepted 关系。`payment_mirror + refund_offset` MUST 兼容；`transfer_pair + payment_mirror` 与 `transfer_pair + refund_offset` MUST NOT 在同一事实上同时 accepted。自动规则发现不兼容的既有 accepted 关系时 MUST 保留冲突证据并产生 `pending_review`，不得自动覆盖或并存；人工接受新关系前 MUST 先 supersede 冲突关系。
- **FR-013b**: 同一事实最多参与一个活跃 accepted `transfer_pair`；同一退款事实最多作为一个活跃 accepted `refund_offset` 的退款端，同一消费事实 MAY 作为多条 accepted `refund_offset` 的消费端；accepted `payment_mirror` MAY 以多条边形成一个逻辑事件连通组，但该组 MUST 只有一个确定性 canonical，冲突 canonical 候选 MUST `pending_review`。
- **FR-014**: rejected 决策 MUST 持久化，使同一候选业务键在后续自动检查中不再重复推荐为 pending（除非显式 supersede 重开）。

#### 证据与规则

- **FR-015**: 每条自动生成的候选或 accepted 关系 MUST 保存可解释 evidence（如金额差、时间差、币种一致、卡尾号、账户别名命中、counterparty 相似度、source 对、rule_id 等）。
- **FR-016**: 跨平台镜像 `payment_mirror` 匹配 MUST 对齐 main 分支跨源去重的**精度与静默策略**，并适配多账户模型。具体 MUST：
  1. **Source 约束**：仅 platform×bank（支付平台源如 alipay/wechat × 银行通道源如 icbc/ccb）；MUST NOT 对 bank×bank 或 platform×platform 建立 `payment_mirror`。
  2. **金额**：同币种两侧 Decimal 严格相等才可 auto-accept；任意非零差额 MUST NOT auto-accept；近强场景 MAY 以精确 `amount_delta` 进入 pending。
  3. **强 auto-accept**：等额 + 有效时间差 ≤10 秒 +（main 式 counterparty/description 双向子串交叉 **或** 卡尾号/账户别名）+ 候选唯一。
  4. **同账户短窗 exact-2 auto-accept（对齐 main cross_source，收窄窗）**：platform×bank、**同一 account_id**、等额、同号、该键恰好 1 平台+1 银行、**平台时间 ≤ 银行时间**、Δt=银行−平台 ∈ **[0, 60]** 秒 → MUST 可 auto-accept，**不要求**商户文本（银行通道摘要信息少于平台属预期）。**禁止以自然日整天为 auto 窗**。
  5. **有文本/卡尾的短窗 auto-accept**：等额 + platform×bank + **同一 account_id** + 文本子串或卡尾/别名 + 候选唯一 + |Δt|≤60s → MAY auto-accept。
  6. **账户硬约束（同账户）**：`payment_mirror` MUST 要求两侧 `account_id` 相同。跨账户（例如微信零钱 vs 建行储蓄、支付宝余额 vs 工行借记）MUST NOT 建立 accepted 或 pending `payment_mirror`，应静默；若资金在账户间真实移动，走 `transfer_pair`。
  7. **静默（不建 relation）**：候选形态不成立时——非 platform×bank、**跨账户**、符号/金额结构不可能、无文本且仅远日等同账户弱形态不足等。**禁止**把「main 会处理但我们不 auto」的**同账户**案例静默跳过。
  8. **Pending 高召回（MUST，仅同账户）**：宁可多召回，不可漏可能配对。至少包括：
     - **P1** 同账户、等额、platform×bank、**同日**，未达 B auto（含 lag>60s 或平台更晚）；
     - **P2** 有文本/卡尾、等额、platform×bank、同账户、同日或近窗，未达 A/C auto；
     - **P4** 有文本/卡尾、同账户、近窗，金额有**精确非零差额**；
     - **P5** 等额、同账户、近窗/同日，但**平台晚于银行**（未由业务日规则覆盖时）；
     - **P6** 本可 auto 但**候选不唯一**；
     - **P7** 其它 main 会命中、本 spec 故意不 auto 的**同账户**镜像形态 → **pending 而非 skip**。
  9. **基数**：全局 1:1 greedy；不唯一时 P6 pending，不得静默任选。
  10. **Canonical**：支付平台详情优先于银行通道摘要。
  11. **时间序**：auto 要求平台≤银行（B/C）；平台更晚 → pending，不 auto（同账户业务日 exact 规则除外）。
  12. **召回原则**：pending 高召回（同账户）；auto 保持严；main 过强规则降级 pending 不丢弃。
  13. **main 汲取纪律**：冲突项永不汲取；「银行仅通道名」不是假阳性；跨账户假镜像永不汲取。
- **FR-016a**: 多账户模型下镜像腿 MUST 映射到同一 `account_id`（通常为扣款卡账户）；卡尾/别名/文本交叉用于**同账户**候选排序与证据，不得用于跨账户 mirror。
- **FR-016b**: Review Inbox 对 **同账户** mirror 以高召回为目标；精确过滤交给人工 accept/reject。跨账户镜像不得进入 inbox。
- **FR-017**: 内部转账匹配信号 MUST 参考 main 分支 reconcile 转账语义：一正一负、不同账户、同币种绝对金额严格相等、有效时间接近、转账强信号词（如转账支取/存入、银联入账、手机银行、提现等）；并支持同日银联现金类已验证规则族。同币种强匹配 auto-accept 时间窗为有效时间差 ≤10 秒且候选唯一；同日宽窗口 auto-accept 仅允许“银联入账/电子汇入 ↔ 无卡付/**无卡支付**/云闪付/转账支取”强信号组合且候选唯一。**当任一侧为银行 date-only 导出**（raw_payload `date` 为 `YYYY-MM-DD`，无真实钟点；formal 常落 16:00 UTC）时，MUST 回退用 **raw 业务日**（与 payment_mirror FR-052/053 同一 `business_day_shanghai` / `fact_is_bank_date_only`）判定同日，且 MUST NOT 用 formal 16:00 哨兵时刻计算 Δt 来否决同日银联桥；同业务日 + 等额 + 银联/转账强信号 + 唯一候选 → MAY auto-accept。同币种两侧任意非零金额差额 MUST NOT auto-accept，但 MAY 以精确 `amount_delta` 证据进入 pending；不得把差额舍入、吸收或隐式解释为手续费。当转账/还款合法对侧 ≥2 或不唯一时，MUST 落 1 条开放单腿 pending（锚点由信号规则决定），而非 N 条双边 pending；唯一近强对侧 MAY 双边 pending。
- **FR-018**: 信用还款匹配 MUST 覆盖 cash→loan 同币种与跨币种（含购汇还款）形态。信用还款以 `transfer_pair` subtype（如 `credit_repayment`）表达；用户可见语义与报表影响 MUST 可区分普通内部转账与信用还款。
  1. **同币种**：auto-accept 要求绝对金额严格相等、有效时间差 ≤600 秒、还款相关文本信号、候选唯一。
  2. **跨币种 / 购汇还款**：
     - 出腿 MUST 含明确 FX/还款信号（至少包含 `购汇还款`，或 `购汇`+`还款`，或既有强还款信号且两侧币种不同）；入腿 MUST 为 loan/credit 账户正金额入账，且非退款/营销入账。
     - 时间窗：候选搜索有效时间差 ≤**60** 秒（覆盖手机银行购汇连续入账）；不得仅因“多币种候选同时存在”而静默跳过。
     - **市场汇率**：系统 MUST 尝试获取**出腿业务日（Asia/Shanghai）**的出币种→入币种市场中间价（或等价交叉盘）。汇率仅作匹配证据，MUST NOT 改写任一正式事实金额。
     - **隐含汇率与置信度**：对每个合法候选，`implied_rate = abs(cash_out_amount) / abs(loan_in_amount)`（以 cash 币种 per 1 loan 币种，或对称记录双方）。`rate_error = |implied_rate / market_rate - 1|`（market 不可得时不得 auto-accept）。
     - **高置信 auto-accept**：当且仅当 (a) 市场汇率可得，(b) 恰好 1 个候选 `rate_error ≤ 1.5%`（默认阈值，可配置但须写入 evidence），(c) 该候选 `rate_error` 比次优候选至少好 **0.5 个百分点**（或次优不存在/不可比），(d) 时间差在窗内 → auto-accept 双边关系；evidence MUST 含 `market_rate`、`implied_rate`、`rate_error`、`fx_source`、双方金额/币种。
     - **降级 pending**：汇率不可得、零候选、无一达阈值、或多个候选同达高置信且无法显著区分 → MUST `pending_review`（唯一近邻可双边 pending；≥2 合法候选可开放单腿 + `candidate_fact_ids` 按 `rate_error` 升序、再按时间差）。
     - **禁止**：用浮点容差把不等额同币种硬配成还款；用汇率改写事实；在无汇率时把“≤10s 任意 loan 入账”一律 auto-accept（旧 v1 无汇率 auto 路径 MUST 废止或降级为仅在唯一候选+强文本时仍 pending，直到汇率路径可用）。
- **FR-018a**: v1 关系主体以现金类正式事实为主。`InvestmentEvent` MUST 仅在银证转账等已有对侧正式事实的 `transfer_pair` 场景作为对侧参与；MUST NOT 将投资买卖/持仓类事件作为 `payment_mirror`、`refund_offset` 或 `duplicate_of` 的主体。
- **FR-019**: 对仅一侧出现在账本内的“单腿内部调拨”信号，System MUST NOT 自动建立 **accepted** 双边关系，也 MUST NOT 伪造缺失对侧事实或改写该侧事实分类。高信号锚点 MAY 创建 **开放单腿** `transfer_pair` `pending_review`（对侧空），待用户补录/选择对侧正式事实后绑定 accept。main `transfer_rules` 文本信号族仅可作为补录提示/候选搜索/开放 pending 线索，不得单独成为 accepted 关系。
- **FR-020**: 退款匹配 MUST 支持全额与部分、多退款对一消费；每笔退款事实最多关联一笔消费，MUST NOT 在 v1 中把一笔退款分摊给多笔消费。**退款腿 MUST 有明确退款文本信号**（不得把任意 income 当退款种子）。
  - **P2P/转账族非对称规则**（红包、转账、群收款、二维码收款、提现、银联入账等）：
    - **收入侧（正金额）**：无退款词的红包/转账/收款/提现入账 MUST NOT 作为退款腿；留给 `transfer_pair` 或人工。
    - **退款种子例外**：文本同时含退款信号与 P2P 族标记时 **是** 合法退款种子（如「微信红包-退款」）。
    - **支出侧（负金额）**：P2P/转账族支出 MAY 作为消费腿，**仅当**退款腿为同族 P2P 退款（上条），且 **细分子类一致**（红包↔红包、转账↔转账、群收款/二维码收款↔收款、提现/银联入账↔提现；跨子类不得强 auto）；满足金额/窗口/唯一等条件时 MUST 允许 **强 auto**（例如「微信红包（单发）」−50 ↔「微信红包-退款」+50），不得因支出属于红包/转账族而拒绝。
    - **商品退款**（非 P2P 退款文案，如「退款-商品名」）的消费腿 MUST NOT 为 P2P/转账/收款/提现支出；此类组合 MUST 静默不配对，防止 pending 洪水。
  - 强 auto 需商户/订单锁 **或**（P2P 同族退款 + 同账户/同对方/可核销金额）+ 窗口 + 唯一。
  - **去前缀整描述精确唯一（title_exact）**：将退款描述去掉前导「退款-」/「退款」/「退款：」等前缀并 trim 后，若与某笔消费描述**整串相等**，且在候选集中该精确命中**恰好 1 笔**，则 MUST 视为决定性强锁并 **auto-accept** 该对（仍须满足时间窗、退款不早于消费、不超额等）；即使同时存在其它仅商户名/「美团订单-」前缀级松匹配，也 MUST NOT 因松匹配多候选而降为 open-leg。精确命中 0 或多笔时不适用本条。
  - **弱 pending 高召回**仅当：以**退款事实为种子**、同账户、退款信号、且金额与消费全额或精确剩余余额**严格相等**、无商户/订单锁；MUST NOT 把「同账户且消费额 ≥ 退款额」的任意更大支出纳入 pending。以**消费事实为种子**时 MUST 仅在商户/订单/P2P 同族强链接下提出关系，不得再写同账户弱 pending（避免一笔退款被每个历史消费种子复制成 N 条 pending）。
  - 强信号可含 order/txn id、商户一致、P2P 同族、退款不早于消费、同账户/同币种、剩余可核销金额；多候选 MUST 收敛为 **1 条开放单腿** `pending_review`（锚点=退款腿，对侧空；evidence 记 `candidate_count` 与排序后的 `candidate_fact_ids` top-K，默认 K=20），MUST NOT 为每个消费候选写双边 pending。退款候选时间窗 MUST ≤30 天；auto-accept 默认要求退款不晚于消费后 14 天，仅当订单号/交易号等锁定信号存在时，15–30 天内仍可 auto-accept；超过 30 天 MUST NOT auto-accept。退款额与剩余可核销余额 MUST 以 Decimal 精确比较：相等才是全额，较小是部分退款，较大是超额且不得 auto-accept；不得使用金额容差改变判断或净额。
- **FR-020a**: 导入/convert 路径 MUST 发布原始消费金额与原始退款金额事实；MUST NOT 在入库前把消费改写为净额或删除退款行来完成核销。净消费仅由 accepted `refund_offset` 在报表/投影层计算。
- **FR-021**: `payment_mirror` MUST 声明 primary/canonical 或提供确定性选择规则（例如支付平台详情优先于银行通道摘要；同源时信息量更高者优先），仅用于报表“外部消费只计一次”，双方/多方事实仍保留。accepted `payment_mirror` MAY 形成连通组；组内 MUST 只有一个确定性 canonical，外部消费 MUST 只计一次；冲突 canonical 候选 MUST `pending_review`。MUST NOT 用 `duplicate_of` 处理错误重复事实。
- **FR-022**: 规则版本升级时，System MUST 能 supersede 旧关系并保留旧证据，而不是覆盖写历史。

#### 导入后关系检查

- **FR-023**: 每次成功导入一批账单后，System MUST 在导入事务提交之后登记或执行一次“账单关联检查”，并以该批新增**活跃**正式事实作为触发种子；不得重新解析或重新导入文件，也 MUST NOT 把关系检查放进会因匹配失败而回滚正式事实的同一导入事务。
- **FR-024**: 关系检查 MUST 幂等、可重试；失败 MUST NOT 破坏或回滚已导入事实。同步执行或登记可重试任务均可；若检查未完成/失败，正式事实仍保持已导入状态，并可再次触发检查。同一 workspace 内并发检查 MUST 收敛到等价结果，不得产生重复活跃关系或静默覆盖人工决策。
- **FR-025**: 对每个种子事实，System MUST 在同一 workspace 的全部既有**活跃**正式事实中，按关系类型使用有界、可索引的候选条件（金额、币种、时间窗口、账户类型、支付方式、卡尾号、别名、文本、source 组合、外部 id 等）筛选候选；候选 MAY 来自任意既有导入批次。增量检查 MUST 只创建或重评至少包含一个种子事实的关系，**不得以全库无界双重扫描（O(n²) 笛卡尔式）作为正确性或默认实现路径**。
- **FR-025a**: 性能：在约 3 年、≥10_000 条活跃现金事实的个人账本上，单次全量关系检查 wall clock MUST ≤ **60 秒**（本地单进程）。实现 MUST 使用内存/存储索引剪枝使平均候选集远小于 n；仅索引剪枝不得改变业务匹配语义（时间窗/金额/platform×bank 等规格不变）。
- **FR-026**: 关联账单后到才导入时，后到批次的新增正式事实作为种子，MUST 仍能与先前任意批次中符合规则的活跃事实建立关系。手动按批次、事实或日期范围重跑时，指定范围 MUST 只限定种子事实；候选 MAY 位于该范围之外，但 MUST 满足关系类型的规则。全 workspace 重算 MUST 由显式全量重跑触发。

#### Review Inbox

- **FR-027**: System MUST 提供审查入口列出 pending 候选，展示 kind、锚点事实关键字段、对侧字段（开放单腿可空）、evidence（含 candidate_fact_ids/candidate_count/anchor_role 若有）、confidence。
- **FR-028**: 用户 MUST 能 accept、reject、ignore/later；accept/reject MUST 写审计。ignore/later MUST NOT 创建独立终态，候选保持 `pending_review`，可记录稍后处理意图，继续不影响报表，并仍出现在审查入口。
- **FR-028a**: 对 **开放单腿** pending，用户 accept MUST 同时指定 `other_fact_id`（一步绑定并 accepted）。未指定或对侧校验失败 MUST 拒绝 accept，不得产生对侧为空的 accepted。对 **双边** pending，accept 行为保持既有（确认已绑定的对侧）。
- **FR-028b**: reject 开放单腿 MUST 持久化并占用开放锚点业务键，使后续自动检查不得再推荐同一锚点的开放 pending（除非显式 supersede 重开）。
- **FR-029**: 状态迁移至少支持：`pending_review→accepted`、`pending_review→rejected`、`accepted→superseded`；`rejected→superseded` 仅在新规则/新证据明确重开时允许。ignore/later 不改变 `pending_review` 状态。开放单腿仅存在于 `pending_review`；进入 accepted 时 MUST 已是双边。
- **FR-029a**: 自动关系检查 MUST NOT 将开放单腿 pending 升级为 accepted；MUST NOT 在已存在同锚点活跃开放 pending 或 reject 占用时再插入第二条开放 pending；MUST NOT 用消费/对侧 seed 把同一多候选问题展开为多条双边 pending。

#### 账户别名

- **FR-030**: System MUST 支持账户别名（卡尾号、支付方式文本、历史卡号、亲属卡/虚拟卡等）用于候选增强与验证。
- **FR-031**: 别名命中 MUST 进入 evidence；别名冲突 MUST 可见；别名 MUST NOT 无审计地替代导入 mapping 路由。
- **FR-032**: 别名变更后 MUST 能触发相关候选重算（幂等、可审计）。

#### 投影与报表

- **FR-033**: 收支类报表/投影 MUST 基于「活跃正式事实 + **双边** accepted 关系」计算，而不是修改事实后的净额字段，也不是读取行内 `offset_*`/`transfer_account` 权威字段；对侧为空的 pending MUST 忽略。
- **FR-034**: 收支投影 MUST 按以下顺序解释 accepted 关系：先将 accepted `payment_mirror` 连通事实归一为逻辑事件组并确定一次外部计次，再排除 accepted `transfer_pair`（含 credit repayment subtype）两侧，最后以逻辑事件组身份应用 accepted `refund_offset`，得到原始消费、退款与净消费。同一退款镜像组 MUST 只贡献一次核销；组合关系 MUST NOT 重复计次、重复排除或重复核销。错误重复事实通过用户手动逻辑删除后不再进入统计，而不是通过 duplicated 关系排重。
- **FR-035**: 余额类投影 MUST 保留真实账户流水影响，不得因镜像/去重关系丢弃任一侧真实扣款或入账；逻辑删除事实 MUST 被排除。
- **FR-036**: 投影 MUST 可从活跃事实与 accepted 关系确定性重建。

#### 精度、隔离与双后端

- **FR-037**: 金额比较、关系判定与核销 MUST 使用原始币种的精确 Decimal 语义；禁止二进制浮点、隐式舍入或金额容差作为账务判定依据。对预期等额的同币种关系，只有金额严格相等才可 auto-accept；候选搜索 MAY 使用金额范围，但该范围 MUST NOT 改变 exact `amount_delta`、关系状态或投影金额。跨币种关系不使用等额条件，也不得隐式换算后强行匹配。main 分支历史代码中的 `0.01` 浮点阈值 MUST NOT 作为本 feature 的账务容差。
- **FR-038**: 所有关系与审查操作 MUST 在 workspace 内隔离。
- **FR-039**: PostgreSQL 与 SQLite MUST 对上述用户可见行为、幂等、失败合同与报表结果提供等价证据；禁止自动回退、双写、隐式跨后端迁移。允许的运行差异仅限锁实现、并发吞吐、底层驱动错误信息与任务调度时延。
- **FR-040**: 文档与操作说明 MUST 说明：自动流程不得删改事实、用户手动逻辑删除及其审计语义、逻辑删除后再导入发布新活跃事实、关系状态机、审查入口、报表如何读取 accepted 关系、与旧 CSV reconcile 及行内 offset 字段的差异。
- **FR-041**: 逻辑删除后再导入同 identity 时，文件级 digest 幂等 MUST 继续阻止同一已成功文件整文件重放；行级 MUST 在无活跃同 identity 事实时允许发布新活跃正式事实，且新旧实例可审计区分。


#### 开放单腿 pending（扇出收敛）

- **FR-042**: System MUST 支持 `refund_offset` 与 `transfer_pair`（含 `credit_repayment`）的 **开放单腿** `pending_review`：锚点事实非空，对侧事实为空，建议对侧（若有）仅存在于 evidence，不落多条关系行。
- **FR-043**: 开放单腿触发条件 MUST 为：在既有匹配规则下，锚点形态成立，且（合法对侧候选 ≥2，或候选为 0 但仍需人工处理的高信号锚点）。唯一对侧的近强未达 MAY 仍建双边 pending；唯一强匹配 MUST 仍双边 auto-accepted（若达标）。
- **FR-044**: `payment_mirror` MUST NOT 使用开放单腿模型。
- **FR-045**: 开放单腿 evidence MUST 可含：`open_leg=true`、`anchor_role`、`candidate_count`、`candidate_fact_ids`（按规则排序的 top-K，默认 K=20；0 候选时列表可空）。
- **FR-046**: 用户为开放单腿指定对侧并 accept 后，结果 MUST 与「系统一开始就唯一匹配到该对侧并 accepted」在投影语义上等价（在通过同一合法性校验的前提下）。
- **FR-047**: System MUST NOT 创建占位/虚假正式事实充当对侧以回避可空对侧。

### Key Entities

- **Formal Fact**: 已发布的账务事实（现金交易或投资事件），含账户、时间、金额、币种、对手方、描述、来源等；实例可被逻辑删除。
- **Active Formal Fact**: 未被用户逻辑删除的正式事实实例；唯一进入当前投影、关系匹配主体与行级活跃幂等占用。
- **Transaction Relation**: 正式事实之间的账务关系声明；含 kind、锚点/对侧（对侧在开放单腿 pending 时可空）、status、rule、confidence、evidence、审计与版本；信用还款用 subtype 表达。
- **Relation Evidence**: 解释为何推荐/确认/拒绝的结构化证据快照（含 amount_delta、time_delta、信号、alias 命中、rule_id 等）。
- **Relation Check Run**: 针对某次导入批次或指定范围的关系检查执行记录（可登记为可重试任务）。
- **Review Decision**: 人工对 pending 候选的 accept/reject/later 决策及原因；later 不改变 pending 状态。对开放单腿，accept 决策 MUST 包含所选对侧 fact id。
- **Open-Leg Pending**: `refund_offset`/`transfer_pair` 的 pending 形态：仅锚点事实绑定，对侧待用户选择；不参与投影。
- **Fact Deletion Event**: 用户对错误/多余正式事实**实例**追加的逻辑删除墓碑，含 workspace、事实身份、操作者、时间、原因/引用；保留来源链并驱动当前投影排除及相关关系 supersede。删除不永久封禁 source identity。
- **Account Alias**: 将卡尾号、支付方式文本等映射到规范账户的可审计别名，用于关系层增强。
- **Report Projection**: 由活跃正式事实 + accepted 关系确定性派生的余额/收支/净消费视图。
- **Legacy Inline Offset Fields**: 历史 CashTransaction 行内 offset/transfer 痕迹；非本 feature 权威关系模型，最多只读兼容。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 任意完成关系处理后的样本中，相关正式事实条数与关系建立前一致（无因配对导致的物理删除）。
- **SC-002**: 同一跨平台消费导入支付平台与银行两份账单后，系统中存在 2 条正式事实 + 1 条 accepted 或（近强时）pending `payment_mirror` 关系（不是删除其中一条，也不是 `duplicate_of`）；bank×bank 不得出现 accepted `payment_mirror`。
- **SC-003**: 高置信跨平台消费在消费报表中只出现 1 次；对应扣款账户余额仍反映真实流水。
- **SC-003a**: 同账户 platform×bank 等额、平台≤银行、Δt∈[0,60s]、恰好 1+1、无商户文本时 MUST auto-accept；Δt>60s 或平台更晚时 MUST NOT 因该规则 auto-accept。
- **SC-003b**: 裸同日同额（无短窗/无文本/非 exact-2）不得造成 pending 洪水；pending 保持可审查量级。
- **SC-004**: 内部转账双边余额正确变化，且外部收入/支出报表不计入该对。
- **SC-005**: 退款场景中原消费与退款事实金额均保持导入原值；净消费仅通过 accepted `refund_offset` 得到，并与手工验算一致。
- **SC-006**: 弱匹配 100% 进入 pending 审查列表且不改变报表；reject 后再次关系检查不再产生同一 pending 候选；ignore/later 后候选仍可见且仍不改变报表。
- **SC-007**: 对同一批新增事实连续执行 2 次关系检查，活跃关系/候选集合不变（幂等）。
- **SC-008**: 每条自动关系都能展示至少 1 组可理解证据字段，足以回答“为什么匹配”。
- **SC-009**: 人工 accept/reject 均可追溯操作者与时间；自动规则无法在无 supersede 的情况下改变人工结论。
- **SC-010**: 同一验收矩阵在 PostgreSQL 与 SQLite 上得到等价的关系状态与报表净额。
- **SC-011**: 用户逻辑删除重复事实后，该事实实例、RawRecord、revisions、source identity 与删除审计仍可追溯；余额/收支投影不再包含该实例，相关活跃关系均已 superseded；其后对同一 source identity 的再导入会发布新活跃事实且进入投影，旧删除实例仍排除；PostgreSQL 与 SQLite 结果等价。
- **SC-012**: 对预期等额的同币种 `payment_mirror` 与 `transfer_pair` 测试样本，两侧金额严格相等时才可能自动 accepted；加入任意非零 Decimal 差额后 100% 不会自动 accepted，且 evidence 保留精确差额。退款的全额、部分、超额及净额均与 Decimal 手工验算完全一致。
- **SC-013**: 满足规格时间窗与唯一性条件的强匹配样本可 auto-accept；故意放宽到仅同日弱匹配、超过窗口、或多候选时 100% 不 auto-accept。
- **SC-014**: 对含历史行内 `offset_*`/`proposed_action` 痕迹的样本，报表净额与转账排除结果只随独立关系状态变化，不因这些字段本身变化。
- **SC-015**: 对“活跃同 identity 再导入”样本 100% 不重复发布；对“仅存在已逻辑删除同 identity 后再导入”样本 100% 成功发布新活跃事实且不静默复活旧实例。
- **SC-016**: 先导入银行、后导入对应支付平台的跨批样本中，后批检查后 100% 能建立跨批 `payment_mirror`（accepted 或 pending），且不改写旧批事实。
- **SC-018**: 在含 ≥10_000 条活跃现金事实（约 3 年个人账本量级）的库上，单次全量关系检查 wall clock ≤ 60 秒；且实现路径不依赖对全部事实的无界双重全表扫描。
- **SC-019**: 在「1 个退款锚点 × N 个同商户合法消费候选」（N≥2）样本上，关系检查后活跃 `refund_offset` pending 条数 MUST 为 1（开放单腿），且 evidence.candidate_count≥N 或 candidate_fact_ids 长度反映截断前的意图；MUST NOT 产生 N 条双边 pending。
- **SC-020**: 用户对开放单腿指定合法对侧 accept 后，净消费/转账排除结果与手工验算一致；accept 前投影与无该关系一致。
- **SC-021**: 0 候选高信号退款/转账锚点 MUST 可进入开放单腿 pending；用户后续选定合法对侧后可完成 accepted。
- **SC-022**: reject 开放单腿后，同锚点开放键在自动重跑中 100% 不再出现新的开放 pending（除非 supersede 重开）。
- **SC-023**: `payment_mirror` 全量检查不得产生对侧为空的关系行。


## Assumptions

- 现有 `ft import` 事务链路（ImportBatch → RawFile/RawRecord → Formal Fact → Revision → Projection → complete）是可靠基础，本 feature 在其后增加关系层，不重做解析器。
- main 分支 `dedup.py`、`reconcile.py`、`transfer_rules.py` 与 convert 退款配对中的匹配信号、时间窗、优先级与负例，是自动规则的业务参考，而不是要恢复其“删行/改 category/改 amount”的落盘方式；其中浮点 `0.01` 比较不作为本 feature 容差。
- `payment_mirror`：platform×bank；(A) ≤10s+文本/卡尾；(B) 同账户 exact-2 + 平台≤银行 + Δt∈[0,60s] 可无文本；(C) ≤60s+文本可跨账户；1:1；不以自然日为 auto 窗；落盘为关系。
- 规格级时间窗：`payment_mirror` 强 10s、短窗 exact-2 **60s**；`transfer_pair` 10s；还款 600s/10s；退款 30d/14d。
- canonical 选择默认倾向仅适用于 `payment_mirror` 报表计次：支付平台（支付宝/微信）详情记录优先于银行通道摘要；同源时信息量更高者优先；具体确定性规则在 plan 中固化。错误重复事实不走 canonical 关系，走用户手动逻辑删除。
- Review Inbox 的最小可行交付是可查询 + 可决策的契约（CLI 或 API）；精美 Web UI 不阻塞关系层与报表正确性。ignore/later 保持 pending，不引入第四个活跃业务态。
- 单腿调拨不得 **accepted** 无对侧；高信号可走 **开放单腿 pending**，用户补录/选择对侧后绑定。main 单腿文本信号仅用于提示/检索/开放 pending 线索，不单独 accepted。
- 开放单腿仅适用于 `refund_offset` 与 `transfer_pair`；建议对侧 top-K 默认 20；accept 一步绑定；不造占位事实。
- 关系检查在导入提交后执行；同步或可重试任务均可，但导入成功与检查失败必须解耦，检查失败不得回滚事实。
- 不设金额“可接受误差”：预期等额的同币种关系必须严格相等才可自动 accepted；非零差额只能 pending。退款按精确剩余余额计算；跨币种不自动换算后强行等额匹配。
- 本 feature 不要求第一次导入即发现全部关系；后到账单通过后续检查补齐。
- 既有行内 offset/transfer 字段可能仍存在于 schema 与旧数据中，但权威语义迁移到独立关系对象；字段清理/删除可另开任务，不阻塞关系层交付。
- 行级幂等以“活跃正式事实”为占用单位：逻辑删除释放该 identity 的活跃占用，再导入发布新实例，而不是 undelete 旧实例。
- 文件级 digest 幂等与行级活跃幂等正交：删事实不会自动重放同一 digest 文件。
- 关系活跃业务键至少含 workspace、kind、有序事实对与 subtype（若有）；详细唯一索引实现留给 plan。
- v1 不对已删除事实实例提供用户自助 undelete；再入账靠重新导入。

## Non-Goals

- **不在本 feature 修正账单导入对「交易关闭 / 交易关闭→退款成功→重下单」的语义**（见后续独立 feature）：当前支付宝 convert 跳过「交易关闭」行并保留「退款成功」，会导致库中出现「退款早于另一笔成功支出、订单号不同」的假 orphan；正确导入/关系语义另开规格，不在 006 关系层用时间放宽误配新单。

- 不恢复 CSV 时代物理删除式 dedup/reconcile，不把 pending CSV 当审查机制。
- 不通过修改原始事实金额表达退款净额。
- 不通过删除银行扣款或支付平台记录表达跨平台合并。
- 不物理删除正式事实、RawRecord 或 revisions 来处理历史重复；用户删除仅追加逻辑删除墓碑/事件。
- 不引入 `duplicate_of` / duplicated / canonical 关系来“保留双事实只统计其一”。
- 不把账户别名做成无审计的导入账户覆盖。
- 不要求关系检查全库扫描。
- 不在本 feature 重做账单解析、mapping 语言或投资交易产品语义。
- 不把投资买卖/持仓事件纳入 payment_mirror/refund/duplicate 主体匹配。
- 不引入完整 FX 交易/汇兑损益产品；购汇还款匹配 MAY 拉取**当日市场中间价**仅作候选置信度证据，MUST NOT 改写正式事实金额、MUST NOT 写入独立 FX 事实表作为账本权威。
- 不把仅有一侧事实的单腿文本信号单独记为 **accepted** 双边关系，也不通过改写 category 表达单腿转账；允许其进入开放单腿 pending。
- 不以 N 条双边 pending 表达 N 个候选对侧；多候选 MUST 收敛为开放单腿。
- 不对 `payment_mirror` 使用开放单腿 pending。
- 不创建占位正式事实充当对侧。
- 不自动 accept 开放单腿。
- 不以行内 `offset_*` / `proposed_action` / `transfer_account` 作为新的权威关系或净额模型。
- 不在 v1 提供对已逻辑删除事实实例的用户自助 undelete。
- 不因逻辑删除而永久封禁 source identity 或拒绝后续正常导入。
- 不因逻辑删除而自动重放同一文件 digest。
- 不为已逻辑删除事实自动新建 accepted 关系。
- 不把完整 Web UI 作为关系正确性的前置条件（审查契约必须有）。
