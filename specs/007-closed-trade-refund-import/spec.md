# Feature Specification: Import No-Skip, Raw Payload, Unified Relation Scan

**Feature Branch**: `007-import-no-skip`  
**Spec Directory**: `specs/007-closed-trade-refund-import`  
**Created**: 2026-07-21  
**Updated**: 2026-07-22  
**Status**: Ready for Implementation  

**Input**（当前阶段已收敛的需求）:

1. **导入默认 no-skip**：支持账单源的源交易明细默认必须导入；禁止**无文档、无计数**的静默丢行与静默失败。  
2. **白名单业务跳过（须注释+计数）**：
   - **未支付关闭**（FR-008a）；
   - **还款失败且未扣款**（FR-008c）；
   其余状态禁止历史式静默 `continue`。
3. **不做 `funding_status` 字段**：已支付向关单按支出金额入账；退款按正金额入账；成对后余额自然对消。  
4. **导入不写关系**：Import 只发布正式事实 + **原始账单字段**（`raw_records.payload` 契约）；**MUST NOT** 在导入期写 `refund_offset` / `payment_mirror` / `transfer_pair`。  
5. **统一扫描编排**：所有源导入完成后，由 **一次** `relations check`（sync）建关系，阶段顺序固定：  
   - **Phase A** — 支付宝/微信平台硬键退款（及免押→解冻）→ `refund_offset`  
   - **Phase B** — `payment_mirror`（platform×bank）  
   - **Phase C** — **`transfer_pair` / 信用还款**（先分类闸门再精细配对；见附件 transfer 分类表）  
   - **Phase D** — 银行消费退货 `refund_offset`、弱匹配、open-leg 及其余  
6. **支付宝订单键规则**（真实账单验证）：`refund.txn == origin.txn` 或 `startswith(origin + "_")` 或 `startswith(origin + "*")`；禁止仅 `rsplit("_",1)`；标题不得优先于订单键；不得把关单退款 auto 配到不同订单号的重拍成功单。  
7. **微信退款双行**：支出原单行与收入退款行 MUST 都导入；扫描 Phase A 按微信规则写 `refund_offset`；**禁止** convert 净额改写原单金额。  
8. **银行退款不在导入期做**：银行无订单硬键；消费退货在 **Phase D** 处理；导入仅保证事实 + 信号字段可恢复。  
9. **微信无支付宝式未支付关闭白名单（本库）**：默认全导入；禁止无注释 silent continue。

## Clarifications

### Session 2026-07-21 — 关单与导入

- Q: 「交易关闭」是否一律入库？ → A: **分型**。**已支付向**（`支出` + 通常有付款方式）MUST 入库；**未支付关闭**（见专节判定）MUST **跳过**并注释+计数。
- Q: 是否引入 `funding_status`？ → A: **否**。
- Q: 关单无退款会不会误记支出？ → A: **`交易关闭|支出` 在正确订单前缀下 121/121 有退款**。无退款的是未支付关闭——**跳过**。
- Q: 关单 + 全额退款余额？ → A: **-A + A = 0**；重拍成功单 B 独立。
- Q: 0 元明细？ → A: 必须导入（非未支付关闭）。

### Session 2026-07-21 — no-skip 范围

- Q: 「所有条目」？ → A: 每一条**源交易明细**；不是页眉/页脚/表头/空行/页合计。  
- Q: 状态 `continue` 丢行？ → A: **禁止**（除白名单）。  
- Q: mapping `default: skip`？ → A: **禁止**；未匹配 MUST 失败关闭。  
- Q: 幂等重复？ → A: 不重复发布 = 已接纳。  
- Q: 解析失败？ → A: 失败关闭；禁止「跳过坏行当成功」。  
- Q: 覆盖源？ → A: 支付宝、微信、工行信用卡/借记卡、建行借记卡、东方证券及已接线同级源。

### Session 2026-07-21 — 未支付关闭 / 还款失败

- Q: 未支付关闭？ → A: **跳过**。判定：`状态∈{交易关闭,已关闭}` **且** `收/支≠支出` **且** 付款方式空。计数 `skipped_unpaid_closed`。  
- Q: `交易关闭|支出`？ → A: **必须导入**（已支付向）。  
- Q: 还款失败？ → A: **跳过**当 `还款失败` + 不计收支 + 付款方式空。计数 `skipped_failed_repay`。`还款成功` MUST 导入。

### Session 2026-07-22 — 统一编排：导入存原始字段，扫描统一配对

- Q: 是否在导入期落 `refund_offset`？ → A: **否（修订）**。导入 **只** 发布正式事实并持久化原始账单字段；关系一律在 **relations check / sync** 建立。  
- Q: 为何修订？ → A: 与银行路径对齐，形成「先全量导入、再一次性扫描」；平台硬键规则仍用，但触发时机后置到扫描 Phase A。跨批（先银行后支付宝）一次 check 即可完成退款+镜像。  
- Q: 原始字段存在哪？ → A: **`raw_records.payload`（JSON）** 为权威载体；MUST 满足本 spec **Raw Payload 契约**。可另将硬键投影到 formal 可读字段，但不得只存展示用 description 而丢掉 status/txn/type/pay。  
- Q: 扫描顺序？ → A: **固定** Phase A（支付宝/微信硬键退款 + 免押解冻）→ Phase B（`payment_mirror`）→ Phase C transfer → Phase D 银行退货/弱匹配。A 必须在 B 前，避免银行通道入账先被错误 mirror。  
- Q: 银行退款？ → A: **不在导入期做**；Phase C 处理。导入 MUST 保留足够信号（如 summary「消费退货」、工行 raw「退货」）供扫描。  
- Q: 匹配规则是否改变？ → A: 支付宝订单键、微信双行/residual/对方已退还、免押解冻等 **语义不变**；仅 **落点从 import 改为 scan Phase A**。rule_id 建议 `scan.alipay.order_prefix.v1` / `scan.wechat.*.v1`（实现可兼容旧 `import.*` 已落边为已存在关系）。  
- Q: 已有 import 期写入的关系？ → A: 扫描 MUST 跳过已有活跃同业务键边；不强制历史回填。  
- Q: convert `_pair_refunds`？ → A: MUST NOT 改金额；tracking 不得作为权威核销；权威只在 `transaction_relations`。



### Session 2026-07-22 — 微信零钱提现账户语义（产品更正）

- Q: 微信「零钱提现」应记到哪个账户？ → A: **微信零钱（出账）**，金额为**负**（asset leave 零钱）。到账卡写在 counterparty/证据中；**MUST NOT** 因支付方式=建设银行储蓄卡而 mapping 到建行并记 +income（那是错误的「银行双记」）。  
- Q: 与银行「银联入账」如何配对？ → A: **异账户** `transfer_pair.withdraw_to_bank`：微信零钱 −A ↔ 建行 +A（等额+同日/短窗）。  
- Q: 旧 dual-source same-account mirror？ → A: 在错误 mapping 下的补丁；正确 mapping 后主路径为 transfer，不再依赖同账户双源 mirror 作为正确模型（实现可保留作兼容，但新导入不应产生同账户双 +A）。

### Session 2026-07-22 — 转账 Phase C 审计后收紧（真实全量导入）


- Q: 审计发现？ → A: 支付宝提现→工行 **6/6** accepted；建行跨卡/借记还信用卡多组正确；**微信零钱提现 4/4 未挂上**；**credit_repayment pending 噪声**（京东「还款」摘要、月付出腿配到无关小额入账/退款）。  
- Q: 微信提现如何修？ → A: **先分账户语义**。若 mapping 把微信「零钱提现/提现已到账」落到**银行账户**（本库建行），与 CCB「银联入账/支付机构提现」为**同账户双源入账** → MUST 走 **payment_mirror（同号）** 或幂等去重，**不是** transfer_pair（transfer 要求不同 account_id）。若微信事实在微信零钱、银行在建行（异账户）→ Phase C `withdraw_to_bank` 等额+同日。审计：4 笔微信提现均 mapping 到建行，其中 2100 与 CCB 银联入账同账户差约 1 日；其余 3 笔库内无第二条银行第二事实。  
- Q: 同账户双源 +2100 为何未 mirror？ → A: 银行 date-only 常落在 UTC 前一日 16:00，日历日与微信不一致；Phase B MUST 对「微信提现类 + 银行银联入账/支付机构提现」允许 **相邻自然日** 或 **|Δt|≤36h** 的同号 mirror（仍 platform×bank、等额、唯一）。  
- Q: 信用还款如何收紧？ → A: 出腿 MUST 含明确还款信号（信用卡还款/购汇还款/自动还款/花呗*还款/月付】主动还款等）；入腿 MUST 为 **loan/credit 账户** 或信用卡侧「还款/转帐收入」且 **禁止** 以退款/消费退货/营销刷卡金/小额商户入账为对侧；建行 summary 仅「还款」+ 商户 counterparty（如京东）MUST NOT 当作 credit_repayment 出腿。多候选或金额显著不等（非 FX 规则）→ pending/open-leg 或 skip，不得乱配。  
- Q: 购汇还款多候选如何配？ → A: 拉取出腿业务日（Asia/Shanghai）市场中间价；对每个 loan 入账候选算隐含汇率偏差 `rate_error`；**仅当恰好 1 个**高置信候选（默认 rate_error≤1.5% 且显著优于次优）auto-accept；汇率不可得或置信不足/并列 → pending（见 `attachments/transfer-source-taxonomy.md` FX 表与 006 FR-018）。禁止无汇率时把任意 ≤10s loan 入账 auto-accept。  
- Q: 优化停止条件？ → A: 当继续放宽会把 P2P/消费/退款吸入 transfer，或 credit_repayment 假配率上升时停止；本轮只修审计已证实缺口。

### Session 2026-07-22 — 转账单独 Phase C（分类闸门 + 精细配对）

- Q: 转账是否与银行退款同一 phase？ → A: **否**。`transfer_pair` 为 **Phase C**；银行消费退货与其它弱配对为 **Phase D**（在 C 之后）。  
- Q: 是否全库扫「转账」词？ → A: **否**。MUST 先按各源**原生分类**（支付宝 status×方向+文案族；微信 status×type；建行 summary；工行支付方式/对手）打标进候选池，再在池内等额/时间/账户精细配对（与退款「先分类再配对」同一套路）。  
- Q: 哪些算 transfer？ → A: **自有账户调拨**（提现到银行卡、卡间银联、平台内余额宝/余利宝到卡、银证）、**信用还款**（信用卡/花呗/月付）。**不是**：P2P 微信转账/红包/二维码收款、商户消费、退款、通道消费（走 mirror）。  
- Q: 真实数据锚点？ → A: 支付宝提现→工行 6/6 秒级；建行跨卡支取↔银联入账多组；微信提现已到账↔建行入账（常 date-only）；银证常单腿。  
- Q: 「提现」信号？ → A: MUST 纳入 Phase C 强规则（当前仅通用 transfer 词表会漏提现）。  
- Q: 落点？ → A: 仅 scan Phase C 写 `transfer_pair`；import 不写 transfer 关系，但 MUST 保留原始 status/type/summary 于 payload。

### Session 2026-07-21 — 订单键 / 预授权 / 微信（规则保留，落点改为扫描）

- Q: 仅 `rsplit("_",1)`？ → A: **不够**；MUST 前缀 `_` / `*`。  
- Q: 商家订单号？ → A: 仅辅助；Steam 不得唯一依赖 mer。  
- Q: 芝麻免押/解冻？ → A: 必须导入；**Phase A** 建 `refund_offset`（同日唯一等）。  
- Q: 微信真单腿？ → A: 本库 0；假单腿来自时间窗/拆退/状态白名单过窄。  
- Q: 微信能否用支付宝 txn 前缀？ → A: **否**。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 任何账单条目不得被静默跳过 (Priority: P1)

作为用户，导入任意支持账单时，每一条交易明细要么进入账本，要么白名单跳过可计数，要么导入明确失败。

**Independent Test**: 构造含成功/关闭/退款/0 元的样本；成功时 `源行=新发布+幂等+白名单跳过`。

### User Story 2 - 导入保留原始字段供扫描 (Priority: P1)

作为系统，导入后每条正式事实可追溯到 RawRecord，且 payload 含该源配对所需原始字段。

**Independent Test**: 导入支付宝/微信各一行；`raw_records.payload` 含 `platform_status`/`txn_id`（支付宝）或 status/type/pay（微信）；正式事实金额未被净额化。

### User Story 3 - 统一扫描 Phase A 平台退款 (Priority: P1)

作为用户，我先导入全部账单，再跑一次关系检查；支付宝/微信退款在 Phase A 按硬键建立 `refund_offset`，无需导入期写关系。

**Independent Test**: 仅 import 不 check → 无平台 `refund_offset`；check 后关单+退款 / 微信双行出现正确关系。

### User Story 4 - 扫描顺序 A→B→C (Priority: P1)

作为用户，系统先核销平台退款，再做支付镜像，再处理银行退货与转账，减少错误 mirror。

**Independent Test**: 同批含支付宝退款、银行通道入账、消费退货；check 后平台退款边 rule 属 Phase A；mirror 边存在且不与已关联退款冲突到错误腿。

### User Story 5 - 已支付关单入库 (Priority: P1)

**Independent Test**: `交易关闭|支出` 导入为正式事实；未支付关闭不在库。

### User Story 6 - 关单退款不配重拍单 (Priority: P1)

**Independent Test**: 退款订单键连 A；B 独立成功消费。

### User Story 7 - 微信双行与拆退 (Priority: P1)

**Independent Test**: 京东一单多退、味多美 30 天、对方已退还；Phase A 后多边/正确边；amount 未改。

### User Story 8 - 银行退货在 Phase D (Priority: P2)

**Independent Test**: 建行「消费退货」导入后无 import 关系；check Phase D 后出现 `refund_offset` 或 pending/open-leg；fallback 级不得 auto。

### User Story 9 - 免与转账仍由扫描 (Priority: P2)

**Independent Test**: Phase B/C 产生 mirror/transfer；不依赖 import 写这些 kind。

### User Story 10 - 白名单跳过可审计 (Priority: P2)

**Independent Test**: 未支付关闭、还款失败进入 skip 计数；代码有注释。

### Edge Cases

- 版式噪声不计源交易行。  
- 白名单跳过可计数。  
- 解析/mapping 失败 → 失败关闭。  
- Phase A 多候选 → pending/open-leg，不静默。  
- 已有活跃关系 → 跳过重复推荐。  
- 银行借记通道入账无「退货」词 → 优先 Phase B mirror，不作 bank refund 种子。  
- convert 不得净额化。

## Requirements *(mandatory)*

### Functional Requirements

#### A. 导入接纳（no-skip）

- **FR-001**: 源交易明细默认 MUST 接纳；白名单跳过见 FR-008a/c。  
- **FR-002**: 成功批次：`源交易行 = 新发布 + 幂等 + 白名单跳过`。  
- **FR-003**: 解析/金额非法 MUST 失败关闭。  
- **FR-004**: Mapping 未命中 MUST 失败关闭。  
- **FR-005**: 同 source identity 活跃事实 → 幂等命中。  
- **FR-006**: 导入结果 MUST 暴露源行/新发布/幂等/skipped_unpaid_closed/skipped_failed_repay/失败。  
- **FR-007**: 适用于支付宝、微信、工行信用卡/借记卡、建行借记卡、东方证券及同级源。

#### B. 关闭/失败态与金额

- **FR-008**: 已支付向关单 MUST 发布正式事实。  
- **FR-008a**: 未支付关闭 MUST 跳过并计数+注释。  
- **FR-008b**: 他源等价未支付关闭同一原则。  
- **FR-008c**: 还款失败未扣款 MUST 跳过并计数+注释；还款成功 MUST 导入。  
- **FR-009**: MUST NOT 新增必选 `funding_status`。  
- **FR-010**: 已支付关单负向、退款正向；余额为活跃事实汇总。  
- **FR-011**: 0 元非未支付关闭 MUST 导入。

#### C. 原始字段契约（导入期）

- **FR-012**: 每条成功发布的正式事实 MUST 关联 `raw_records` 行；`payload` MUST 为 JSON 对象且含该 `source_type` 的**必填原始键**（见附录 Raw Payload 契约）。  
- **FR-013**: 支付宝 payload MUST 至少可恢复：`platform_status`（或交易状态）、完整 `txn_id`（交易订单号）、商家订单号（若有）、收/支、付款方式、商品说明、金额、时间。  
- **FR-014**: 微信 payload MUST 至少可恢复：当前状态、交易类型、收/支、支付方式、金额、时间、交易单号、商户单号（若有）、对手方/描述。  
- **FR-015**: 银行源 payload MUST 至少可恢复：足以识别消费退货/通道的原文（建行 summary/location；工行 raw 对方/摘要/退货标记等）。  
- **FR-016**: Import MUST NOT 写入 `refund_offset` / `payment_mirror` / `transfer_pair`（或任何替代「权威核销」的 offset 改写）。  
- **FR-017**: Import MUST NOT 将消费/退款 `amount` 改为净额。

#### D. 统一扫描编排

- **FR-018**: `relations check` MUST 按顺序执行：  
  1. **Phase A — 平台硬键退款**：支付宝订单键 `refund_offset`；免押→解冻；微信双行/residual/转账退还；  
  2. **Phase B — `payment_mirror`**；  
  3. **Phase C — `transfer_pair` / 信用还款**：先源生分类闸门再精细配对（FR-040+；附件）；  
  4. **Phase D — 其余**：银行消费退货 `refund_offset`、弱匹配、open-leg。  
- **FR-019**: Phase A 支付宝匹配 MUST 使用：`==` / `startswith(origin+"_")` / `startswith(origin+"*")`；唯一才 auto-accept；标题不得优先；不得配错重拍单 B。  
- **FR-020**: Phase A 微信匹配 MUST 使用 FR-029 优先级（见下，规则同前，触发在扫描）；MUST NOT 用支付宝 txn 前缀。  
- **FR-021**: Phase A 免押→解冻：优先订单/商户键；否则同日状态对+唯一；多候选 open-leg。  
- **FR-022**: Phase D 银行退货：同卡+商户簇+金额/剩余+时间；**禁止** `refund_desc_fallback` 级 auto-accept（最多 pending/open-leg）。  
- **FR-023**: 已有活跃关系 MUST 跳过重复自动推荐。  
- **FR-024**: MUST NOT 用「退款早于任意成功消费 N 小时」替代原单或订单键。  
- **FR-025**: 核销只追加关系，不删事实、不改金额。

#### E. 微信匹配细则（Phase A 使用）

- **FR-027**: 微信明细默认 MUST 导入；禁止无注释 silent continue。  
- **FR-027a**: 中性 `/` 行（提现/充值/理财/信用卡还款等）MUST 导入。  
- **FR-027b**: MUST NOT convert 净额化。  
- **FR-028**: 原单腿/退款腿识别同前（已全额退款、已退款(¥x)、对方已退还；收入退款状态/类型）。  
- **FR-029**: 匹配优先级：红包 mer=txn → 全额等额同 pay → 部分嵌 x==收入 → residual 拆退 → 转账退还；时间窗须覆盖 ≥30 天真部分退。  
- **FR-030**: 唯一（或 residual 明确同原单）→ Phase A 写 `refund_offset`（rule 如 `scan.wechat.*`）。  
- **FR-031**: 多原单 → open-leg/pending；禁止假单腿。  
- **FR-032**: Phase B/C 不得破坏已 accepted 的 Phase A 边；跨源 mirror/transfer 仍可进行。


#### G. 转账 Phase C（分类闸门 + 精细配对）

- **FR-040**: Phase C MUST 在 Phase B 之后、Phase D 之前执行；MUST NOT 与银行退货混为同一无序阶段。  
- **FR-041**: Phase C MUST 两阶段：**(1) 源生分类闸门**；（2）池内精细配对。MUST NOT 仅因含「转账」二字进候选。详见 `attachments/transfer-source-taxonomy.md`。  
- **FR-042**: 闸门至少覆盖：支付宝提现/转账到银行卡/余利宝转出到卡/花呗还款/月付还款；微信提现已到账×零钱提现、支付成功×信用卡还款；建行 summary∈{转账支取,无卡自助,银联入账,支付机构提现,转账存入,电子汇入,银转证,证转银}（**不含** 仅「还款」+商户名）；工行提现入账与真·信用卡还款入账。  
- **FR-043**: MUST 排除：二维码收付款、微信 P2P（对方已收钱×转账、已存入零钱×转账）、红包、商户消费、退款/消费退货、通道消费（走 mirror）。  
- **FR-044**: 精细优先级（唯一 auto）：  
  1. 平台提现→银行：支付宝负向提现 Δt≤60s/同日；**微信提现已到账/零钱提现（常为正）与银行银联入账/支付机构提现等额同自然日（date-only）** → `transfer_pair.withdraw_to_bank.v1`；  
  2. 卡间银联：不同 account_id，等额同日/≤10s；  
  3. **信用还款（收紧）**：出腿明确还款信号；入腿 loan/credit 或信用卡还款/转帐收入；禁止京东等商户「还款」摘要当出腿；禁止退款/退货/刷卡金当入腿；  
  4. 银证：无对侧 open-leg；  
  5. 通用弱 transfer 仅闸门内+含提现等信号。  
- **FR-045**: 「提现」MUST 为强信号。  
- **FR-046**: Import MUST NOT 写 `transfer_pair`。  
- **FR-047**: 多候选 → pending/open-leg，不静默。  
- **FR-048**: 微信提现类事实：  
  (a) 与银行提现入账 **不同 account_id** → Phase C `withdraw_to_bank`（等额+同日/≤60s）；  
  (b) **同一 account_id**（mapping 到银行卡）且存在 CCB/ICBC 银联入账/支付机构提现同额 → Phase B **payment_mirror** 同号配对，允许相邻日/≤36h；  
  (c) 仅单侧事实 → 不强制建边。  
- **FR-051**: 支付宝 **余额宝**、**余利宝** MUST 作为独立账户名（mapping/accounts）。  
  - `余额宝-转出到银行卡` / `余利宝-转出到银行卡`：正式事实 **expense 负金额**，`payment_method` 为 `余额宝`/`余利宝`（**禁止**用到账银行卡作 mapping 账户记 +income）。  
  - `余额宝-转出到余额`：expense 自 `余额宝`。  
  - `支付宝转入到余利宝`：expense 自资金来源（账户余额等）。  
  - `余额宝` 收益发放 / `卖出至余额宝`：income 至 `余额宝`。  
  - 消费以 `收/付款方式=余额宝` 支付：debit `余额宝` 账户。  
- **FR-050**: 微信 `txn_type=零钱提现`（及状态提现已到账）MUST：  
  1. 正式事实金额为**负**（expense），账户为**微信零钱**（或 mapping `零钱`）；  
  2. 到账银行卡信息保留在 counterparty/payload，**不得**用支付方式列覆盖为银行账户入账；  
  3. 与银行侧等额入账（银联入账/支付机构提现等）在 Phase C 建 `transfer_pair`（不同 account_id）。  
- **FR-049**: 建行 summary 仅为「还款」且 counterparty 呈商户消费态 MUST NOT 进入 credit_repayment auto；微信信用卡还款 MUST NOT 配非还款入账。

#### F. 精度与双后端

- **FR-033**: Decimal 金额。  
- **FR-034**: workspace 隔离与幂等。  
- **FR-035**: PG/SQLite 用户可见等价（接纳集合、payload 契约字段、关系、错误合同）。  
- **FR-036**: 正式事实不可变、逻辑删除、raw→formal 链继续适用。

### Key Entities

- **Source Transaction Line**  
- **RawRecord.payload**：源行原始字段契约  
- **Formal Cash Fact**  
- **Whitelist Skip Counts**  
- **Scan Phase A/B/C Relations**

## Success Criteria *(mandatory)*

- **SC-001**: 除白名单跳过外源行接纳率 100%。  
- **SC-002**: 解析/mapping 失败时 0%「丢行却成功」。  
- **SC-003**: **仅 import、不 check** → 平台/银行 **0** 条新 `refund_offset`（本 feature 路径）。  
- **SC-004**: import + check 后，支付宝关单/成功单+订单键退款 **100%** 正确 `refund_offset`（基线 151/151 非0）。  
- **SC-005**: 退款 **0%** auto 连重拍成功单 B。  
- **SC-006**: 关单+全额退款余额净 0。  
- **SC-007**: 微信收入退款腿 Phase A 后 **100%** 挂原单（基线 82/82 有对侧；含拆退）。  
- **SC-008**: 京东拆退：1 原单 N 边；amount 未改。  
- **SC-009**: 味多美 ~30 天部分退、对方已退还样本配对成功。  
- **SC-010**: check 阶段顺序可观测（日志/rule_id 前缀或测试钩子证明 A 在 B 前）。  
- **SC-011**: 每条新发布事实的 raw payload 含该源必填键（契约测试）。  
- **SC-012**: 未支付关闭/还款失败跳过计数与注释。  
- **SC-013**: 免押+解冻 Phase A 后有 `refund_offset`。  
- **SC-014**: PG/SQLite 矩阵等价。  
- **SC-015**: 银行消费退货不在 import 落边；check 后 Phase C 可建边或 pending。  
- **SC-016**: convert/import **不得**把支付 amount 改为净额。


- **SC-017**: `relations check` 阶段顺序为 A→B→**C(transfer)**→**D(银行退货/弱)**（测试或 rule_id/phase 证据可观测）。  
- **SC-018**: 支付宝「提现-实时提现」↔ 工行等额入账（Δt≤60s）样本 Phase C 后 **accepted** `transfer_pair`（基线 6/6 形态）。  
- **SC-019**: 建行跨卡「转账支取↔银联入账」等额同日样本 Phase C 可 accepted。  
- **SC-020**: 微信 `扫二维码付款` / `对方已收钱×转账` / `已存入零钱×转账` MUST NOT 仅因「转账」字样成为 transfer auto 边。  
- **SC-021**: 银行「消费退货」关系若产生，MUST 不早于 Phase C 完成（属 Phase D）。  
- **SC-022**: 微信提现 mapping 到银行账户且存在第二源同额银联入账时，MUST 以 **payment_mirror**（或显式同账户双源规则）关联，而非错误要求异账户 transfer。  
- **SC-022b**: 微信提现在微信零钱、银行在借记卡（异账户）且等额同日时，Phase C MUST `withdraw_to_bank` accepted。  
- **SC-027**: 建行 date-only 与平台同账户等额同业务日消费镜像，check 后 accepted（`bank_date_only`），不依赖 occurred_at 秒差。  
- **SC-028**: 平台退款入账与银行消费退货等额同业务日 → accepted `refund_dual_source` mirror。  
- **SC-031**: 同账户同业务日等额存在 2 条银行「消费」候选时，仍应 accepted 一条（最近时间），不得双双 near.weak。  
- **SC-030**: 同账户等额同业务日 platform×bank（含银行仅「消费」、Δt≈1s、无文本交叉）check 后 accepted，不得仅因此落 `near.weak`。  
- **SC-029**: 完整菱形链唯一时，银行退货 open-leg 可升格为 accepted `diamond_via_platform`。  
- **SC-026**: `余额宝-转出到银行卡` / `余利宝-转出到银行卡` 导入后账户为余额宝/余利宝且 amount<0；不得 mapping 到网商储蓄卡并记正数。  
- **SC-025**: 微信零钱提现导入后账户为微信零钱且 amount<0；同笔建行银联入账在建行账户 amount>0；Phase C 后存在 connecting `transfer_pair`（当银行侧存在时）。  
- **SC-023**: 建行 summary「还款」+ 商户名（如京东）MUST NOT 产生 accepted `credit_repayment` 到无关小额入账；微信「信用卡还款」MUST NOT 配到非还款入账（如酒店退款/消费退回）。  
  


### Session 2026-07-22 — Phase B date-only 业务日 / 退款双视角 / Phase D 菱形

- Q: 弱镜像因银行 `occurred_at=…16:00:00` 假时刻卡住？ → A: 配对 MUST 用 **raw payload 业务时间** + **Asia/Shanghai 日历日**；date-only 银行只比同业务日，不伪造秒差。  
- Q: 平台退款+ ↔ 银行消费退货+ 是否 mirror？ → A: **是**（退款双视角），应 auto 为 `payment_mirror.refund_dual_source`，不是噪声。  
- Q: refund open-leg 能否菱形消歧？ → A: **是**。链 `bank_ref–mirror–plat_ref–refund–plat_pay–mirror–bank_pay` 唯一时升格银行 `refund_offset`。

#### H. Phase B 业务日镜像 + Phase D 菱形（FR-052+）

- **FR-052**: 关系匹配 MUST 从 `raw_payload.date`（或等价）解析 **业务时间**；时区 **Asia/Shanghai**。date-only（`YYYY-MM-DD` 或无时分）MUST 仅比较业务日，MUST NOT 依赖 UTC 哨兵 `16:00:00` 的秒差。  
- **FR-053**: **Bank date-only mirror**（`payment_mirror.bank_date_only.v1`）：与 FR-056 同一匹配条件；当银行 raw 为 date-only 时仅 **rule_id 标签** 记为 bank_date_only（审计用），匹配逻辑不另开分支。  
- **FR-054**: **Refund dual-source mirror**（`payment_mirror.refund_dual_source.v1`）：双方金额同号为正、一侧平台退款文案、一侧银行消费退货/退款、等额、同账户或同业务日、1–1 → **accepted**。  
- **FR-057**: 同账户等额 mirror 多候选时 MUST **按评分+时间最近** 取一对 accepted（1–1 greedy 全局仍各腿只用一次），MUST NOT 仅因 candidate_count>1 降为 pending。建行 date-only 且摘要同为「消费」时，任选唯一配对可接受。  
- **FR-056**: **Same-account business-day mirror**（`payment_mirror.same_account.exact.business_day.v1`）：platform×bank、等额、同号、同 `account_id`、同一上海业务日（raw）、全局 1–1 → **accepted**。**不要求** 文本交叉/卡尾；**不要求** bank lag ≥ 0。覆盖银行摘要仅「消费」、与平台秒级对齐却无文本的真镜像。  
- **FR-055**: **Diamond refund**（`refund_offset.diamond_via_platform.v1`，Phase D）：对银行退款腿，若存在唯一链 bank_ref↔plat_ref（mirror）+ plat_ref↔plat_pay（accepted refund_offset）+ plat_pay↔bank_pay（mirror）→ 写 bank_pay–bank_ref `refund_offset` accepted；仅用 accepted 平台退款边；弱 mirror 不得单独支撑菱形角；多 bank_pay → 仍 open-leg。


## Assumptions

- `raw_records.payload` 已存在；本 feature 强化契约与写入完整性。  
- 平台硬键在 scan 上与 import 上等价可对齐（字段在 payload/fact 可还原）。  
- 银行退货继续偏模糊，适合 Phase D；transfer 为 Phase C。  
- 006 open-leg / mirror / transfer 引擎复用，本 feature 定编排与 payload/no-skip。

## Non-Goals

- 不在导入期写关系（修订后明确禁止作为主路径）。  
- 不新增 funding_status。  
- 不把未支付关闭做成正式事实。  
- 不强制历史库回填 payload/关系。  
- 不重写 006 全部规则细节（时间窗数值等以 006 为准，本 feature 定阶段顺序与平台硬键）。  
- 不以改金额/删行核销。
- 不把微信 P2P 转账/二维码/红包当作自有账户 `transfer_pair` auto。
- 不在 Phase C 之前做银行退货主路径。  
- 不把银联入账/刷卡金当 bank refund auto。

## 附录：Raw Payload 契约（最低必填）

| source_type | 必填键（名称可等价映射，语义必须可恢复） |
|---|---|
| alipay | `platform_status`/`txn_status`, `txn_id`, `merchant_order_id`（可空串）, `direction`/`收/支`, `payment_method`, `description`/`商品`, `amount`, `occurred_at`/`date` |
| wechat | `status`, `txn_type`/`type`, `direction`/`收/支`, `pay_method`/`支付方式`, `amount`, `occurred_at`/`date`, `txn_id`, `merchant_order_id`（可空）, `counterparty`/`description` |
| icbc_credit / icbc_debit | `amount`, `occurred_at`/`date`, `counterparty`/`_raw_cp`, `description`/`summary`, 退货可辨标记（如 raw「退货」或 `_refund_signal`） |
| ccb_debit | `amount`, `date`, `summary`, `location`, `counterparty`, 消费退货可辨（summary 含退货/退款等） |

## 附录：支付宝 `状态×收/支×金额` 导入映射

### 白名单跳过（仅 2 类）

| 跳过码 | 判定 | 约条数 |
|---|---|---:|
| `skipped_unpaid_closed` | 关闭/已关闭 + 非支出 + 付款方式空 | 42 |
| `skipped_failed_repay` | 还款失败 + 不计收支 + 付款方式空 | 2 |

**应导入 3060 / 3104。**

### 完整映射表（19 种）— 导入列只表示是否入库；关系一律扫描

| # | 状态 | 收/支 | 金额 | n | 导入？ | 扫描关系提示 |
|---|---|---|---|---:|---|---|
| 1 | 交易成功 | 支出 | ≠0 | 1665 | 导入 | 可作退款原单（Phase A） |
| 2 | 交易成功 | 支出 | 0 | 620 | 导入 | — |
| 3 | 交易成功 | 不计收支 | ≠0 | 155 | 导入 | transfer 等 Phase C |
| 4 | 退款成功 | 不计收支 | ≠0 | 151 | 导入 | **Phase A** → 关单支出/交易成功支出 |
| 5 | 支付成功 | 支出 | 0 | 138 | 导入 | 0 元退款原单 Phase A |
| 6 | 交易关闭 | 支出 | ≠0 | 121 | 导入 | 已支付关单；Phase A 原单 |
| 7 | 支付成功 | 支出 | ≠0 | 98 | 导入 | — |
| 8 | 交易成功 | 收入 | ≠0 | 48 | 导入 | — |
| 9 | 交易关闭 | 不计收支 | ≠0 | 37 | **跳过** | — |
| 10 | 退款成功 | 不计收支 | 0 | 28 | 导入 | Phase A → 支付成功\|支出\|0 |
| 11 | 还款成功 | 不计收支 | ≠0 | 24 | 导入 | — |
| 12 | 转出成功 | 不计收支 | ≠0 | 5 | 导入 | Phase C transfer |
| 13 | 已关闭 | 不计收支 | ≠0 | 4 | **跳过** | — |
| 14 | 芝麻免押下单成功 | 不计收支 | 0 | 2 | 导入 | Phase A 原单→解冻 |
| 15 | 解冻成功 | 不计收支 | 0 | 2 | 导入 | Phase A 释放 |
| 16 | 还款失败 | 不计收支 | ≠0 | 2 | **跳过** | — |
| 17 | 还款成功 | 支出 | ≠0 | 2 | 导入 | — |
| 18 | 交易关闭 | 收入 | ≠0 | 1 | **跳过** | — |
| 19 | 代付成功 | 支出 | ≠0 | 1 | 导入 | — |

### 退款路由（Phase A，非导入期）

| 退款腿 | 对侧 | 覆盖 |
|---|---|---|
| 退款成功≠0 | 关单支出或交易成功支出 | 151/151 |
| 退款成功=0 | 支付成功支出=0 | 28/28 |
| 解冻成功 | 芝麻免押下单成功 | 2 对 |

## 附录：微信导入映射（3331 行）

业务跳过本库 **0**。双行全导入；**Phase A** 写 `refund_offset`（规则 FR-029）。  
禁止支付宝 txn 前缀；禁止改 amount。

| 退款腿 | 原单腿 | 要点 |
|---|---|---|
| 已全额退款收入 | 已全额退款支出 | 等额+同 pay |
| 已退款¥x 收入 | 已退款(¥x) 支出 | 嵌金额；窗≥30 天 |
| 多收入 | 一支出已退款(¥T) | residual |
| 红包退款 | 红包支出 | mer==txn |
| 转账-退款 | 对方已退还 | 等额+同 pay |

## 附录：银行（退货 Phase D；transfer 入账 Phase C；导入不落关系）

| 源 | 导入 | 退款/关系 |
|---|---|---|
| 工行信用卡「退货」 | 事实+信号 | Phase D `refund_offset` |
| 建行「消费退货」 | 事实+summary | Phase D；fallback 不 auto |
| 工行借记通道入账 | 事实 | Phase B mirror（非 bank refund 主路径） |
| 银联入账/证转银/利息 | 事实 | Phase C transfer / 非 refund |

## 扫描阶段一览

```text
Import all sources → facts + raw payload
        ↓
relations check
  Phase A: alipay/wechat hard-key refund_offset (+ auth unfreeze)
  Phase B: payment_mirror
  Phase C: transfer_pair / credit_repayment (taxonomy gate → fine match)
  Phase D: bank refund_offset, weak/open-leg
```
