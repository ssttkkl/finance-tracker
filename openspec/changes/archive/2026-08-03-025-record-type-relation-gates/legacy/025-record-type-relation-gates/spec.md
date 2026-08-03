# Feature Specification: 关系配对使用正式记录类型

**Feature Branch**: `025-record-type-relation-gates`
**Status**: 已完成
**Extends**: `024-normalized-cash-record-type`

## Context

现金导入已经在 `cash_transactions.record_type` 保存标准记录类型，但关系配对仍在部分 Phase 中通过 `summary`、交易描述或退款词重新判断“这是退款/转账/还款”。这会让导入分类与关系分类出现分歧，也会把普通收入或消费误放入候选池。

本 Feature 让关系配对把 `record_type` 作为正式类型来源。原始字段仍保留，但只用于商户、订单号、平台硬键、P2P 子类和时间金额等配对证据，不再决定现金记录的一级类型。

## Clarifications

### Session 2026-08-02

- P2P 转账、红包和群收款的退回属于 `transfer_reversal`，不是 `refund` 或 `reversal`；`refund` 只表示消费退款，`reversal` 保留给一般撤销或冲正。
- 正式业务库不在本 Feature 的验证过程中被替换；全量重导入和关系重建只在临时 SQLite 数据库进行，替换业务库需要单独授权。
- 多候选退款在正式类型、同账户、金额和时间窗口过滤后，先按订单/交易号、标题、标准化商户和金额证据分级；同一最高证据等级仍有多个候选时，将已确认的 `payment_mirror` 流水折叠为一个经济事件，再选择退款时间差最小且唯一的候选自动配对。部分退款和全额退款均适用；最近时间并列、超额、超出自动窗口或没有合法候选时继续待审核。
- 退款金额大于消费当前可退余额时，该消费在候选生成阶段直接排除；它不得进入优先级排序、`candidate_count`、候选事实 ID 或任何待审核关系。

## User Stories

### US1 - 关系层读取正式类型

作为关系扫描器，我希望从现金事实读取导入时生成的 `record_type`，这样所有 Phase 使用同一套类型语义。

验收：

1. `FactView` 和关系扫描读取结果包含 `record_type`。
2. 生产关系代码不存在以 `summary` 判断一级 `refund`、`transfer_*` 或 `repayment` 的路径。
3. `record_type=income` 的行即使描述包含“退款”也不能成为退款种子。

### US2 - 转账和还款候选使用正式类型

验收：

1. Phase C 只允许负向 `transfer_out`、专用提现路径中的 `withdrawal_out` 或负向 `repayment` 作为转出种子；`withdrawal_in` 只能作为专用提现到账对侧，二者都不视为普通转账类型。
2. 普通负向 `consumption` 即使文本包含“转账”也不能成为转账种子。
3. 信用账户的正向 `income` 可以作为还款入账对侧，但必须同时满足现金账户到贷款账户、金额、币种和时间等既有配对条件。
4. 普通转账只能由 `transfer_out` 配对 `transfer_in`；信用账户正向 `income` 不得进入普通转账候选池。
5. 提现到账只能由支付平台 `withdrawal_out` 配对不同账户的银行来源 `withdrawal_in` 或 `transfer_in`；支付平台余额入账不得被识别为提现到账。

### US3 - 退款和支付镜像使用正式类型

验收：

1. Phase D 的退款种子必须是正向 `refund`。
2. 退款候选只从负向 `consumption` 和明确的已退款原消费角色中产生，并继续执行同账户限制；P2P 转出不属于消费退款对侧。
3. Phase B 的普通消费镜像和退款镜像按 `record_type` 分池；转账、还款、普通收入、投资、费用和利息不得作为普通消费/退款镜像。
4. 原始文本仍可用于商户、订单号和 P2P 子类匹配，但不能把 `income` 提升为 `refund`，也不能把 `consumption` 提升为 `transfer_out`。
5. `record_type=reversal` 或 `record_type=transfer_reversal` 不得进入消费退款关系；`record_type=withdrawal_out` 只能进入显式的提现到账配对路径，`withdrawal_in` 只能作为该路径的正向对侧。
6. 银行日期型流水仅凭同账户、同金额和同业务日不得自动形成同笔支付关系；自动确认还必须有交易对方、订单、卡尾号或可信时间等同笔证据，否则保留待审核关系。
7. 个人转账、红包和群收款的退回在导入时必须为 `transfer_reversal`，且不生成 `refund_offset`、支付镜像、普通转账候选或平台退款硬键关系。
8. 同账户内存在多个正式匹配消费候选时，系统 MUST 先折叠已确认 `payment_mirror` 的镜像流水，再按订单/交易号、退款标题、标准化对手方和同账户金额证据划分优先级；同一最高优先级仍有多个经济事件时，部分退款和全额退款都必须选择退款发生前时间最近且唯一的候选。最近时间并列时必须保留待审核，不能按事实 ID 任意决胜。
9. 普通退款候选的消费时间 MUST 位于退款前 15 天内（含边界）；普通候选自动确认也 MUST 位于 15 天内。订单号、交易号等锁定证据可将候选和自动确认窗口扩展到 30 天。
10. 退款金额 MUST 不大于消费当前可退余额；超额消费不得进入候选集合、优先级排序、`candidate_count`、候选事实 ID 或待审核关系。
11. `refund_offset` 的 `candidate_count` MUST 按经济事件计数，不得把同一 `payment_mirror` 组中的多条镜像流水重复计为多个候选；关系端点只能落到镜像组中的一条标准事实。

## Non-goals

- 不改变金额、币种和既有同账户约束；本次会把缺少同笔证据的银行日期型镜像从自动确认降为待审核。
- 本次延伸会补充导入分类函数和 `record_type` 枚举中的 `reversal`、`withdrawal_in`、`withdrawal_out`；不提供旧数据库兼容或历史回填。
- 不把 `record_type` 单独当作最终配对证据；最终配对仍需金额、时间、账户和来源证据。
- 不提供旧数据库兼容或关系表回填；关系表在新规则下重新扫描。

## Success Criteria

- 所有关系 Phase 的一级类型门槛来自 `record_type`。
- 旧的 summary/文本类型判断回归测试被移除或改为证明其不能绕过 `record_type`。
- P2P 退回重导入后全部为 `transfer_reversal`，且不参与 `refund_offset`、支付镜像或普通转账配对。
- 多候选部分退款与全额退款在镜像折叠后可以选择最近且唯一的经济事件；最近并列仍为 pending。
- 普通退款候选和自动确认窗口为 15 天，锁定证据窗口为 30 天。
- 超额退款候选在候选阶段被排除，不生成双边或开放待审核关系。
- SQLite 临时全量关系重建可完成，并输出新旧关系数量、类型和状态差异；业务库不被验证流程替换。
