# Implementation Plan: 关系配对使用正式记录类型

## Summary

将导入阶段持久化的 `record_type` 传入 `FactView`，新增按关系路线划分的类型角色和候选索引，替换 Phase B/C/D 中用于确定一级类型的 summary/文本判断。文本只作为同笔证据；银行日期型镜像缺少同笔证据时保留待审核，不再自动确认。

## Technical Context

**Language**: Python 3.11+
**Storage**: SQLite 与 PostgreSQL 共享 `cash_transactions.record_type`
**Tests**: 关系单测、导入/仓储回归、SQLite 全量关系重建；真实 PostgreSQL 契约在环境可用时执行

## Data Flow

```text
cash_transactions.record_type
        ↓
_fact_view_from_row → FactView.record_type
        ↓
record-role predicates + typed candidate indexes
        ↓
Phase A → Phase B mirror → Phase C transfer/repayment → Phase D refund
        ↓
RelationProposal / transaction_relations
```

## Role Policy

- `refund_in`: `record_type=refund` 且金额大于零。
- `expense`: 负向 `consumption`；微信已退款原消费行允许通过来源快照中的 `offset_role=expense` 进入消费角色。
- `transfer_out`: 负向 `transfer_out`。
- `withdrawal_out`: 负向 `withdrawal_out`，仅进入专用提现到账路径，不等同于普通 `transfer_out`。
- `withdrawal_in`: 正向 `withdrawal_in`，仅作为专用提现到账路径的对侧，不等同于普通 `transfer_in`。
- `repayment_out`: 负向 `repayment`。
- `transfer_in`: 正向 `transfer_in`。
- `loan_repayment_in`: 贷款账户正向 `income`、`transfer_in` 或 `repayment`，仅作为还款入账对侧。

## Route compatibility

| 路线 | 出账角色 | 入账角色 | 额外硬条件 |
|---|---|---|---|
| 普通转账 | `transfer_out` | `transfer_in` | 不允许贷款账户还款入账进入该池。 |
| 提现到账 | 支付平台 `withdrawal_out` | 银行来源 `withdrawal_in` 或 `transfer_in` | 不同账户；平台余额或非银行来源入账不得成为对侧。 |
| 信用账户还款 | `repayment_out` | `loan_repayment_in` | 现金账户到贷款账户，保留金额、币种和时间规则。 |
| 消费退款 | `consumption` 或明确原消费角色 | `refund_in` | 同账户；P2P 退回为 `transfer_reversal`，不进入该路线。 |

## Import classification corrections

- 微信或支付宝的个人转账、红包、群收款等 P2P 记录一旦来源状态表达退回/退款，导入类型为 `transfer_reversal`，不再为 `refund` 或一般的 `reversal`。
- 建行借记卡 `summary=无卡自助交易` 或 `summary=无卡支付` 为 `consumption`；`ATM取款`、`支付机构提现` 等明确提现摘要继续按方向使用 `withdrawal_in` / `withdrawal_out`。

## Constitution Check

- 金额与精度：不重算金额，不改变部分退款剩余金额。
- 持久化：通过新增迁移更新 `record_type` 约束；关系层只读取导入时持久化的非空 `record_type`。
- 关系安全：`transfer_reversal` 直接排除出消费退款、支付镜像和普通转账候选；其他类型只做候选闸门，最终关系仍经过账户、币种、金额、时间和来源证据。
- 无兼容：不从旧 payload 推断缺失 `record_type`，不回填旧关系；测试数据显式提供类型。
- 验证隔离：用临时 SQLite 重导入和重建关系；本轮不得覆盖 `/Users/huangwenlong/.ft/finance-tracker.db`。

## Risks

- 现有单测中的 `FactView` fixture 没有类型，需要显式补齐，避免测试继续依赖文本猜测。
- 信用卡还款入账在真实账单中可能分类为 `income`，必须保留贷款账户对侧特例，不能简单要求两边同类型。
- 日期型银行账单缺少可信时间；若没有同笔证据，宁可保留待审核，也不能按金额和日期猜测自动关系。

## 多候选退款策略

退款匹配在正式类型、同账户、商户/订单证据和剩余金额门槛之后增加确定性选择：

1. 退款候选先按正式类型、同账户、同币种、退款晚于消费、15 天候选窗口和剩余金额过滤；锁定订单/交易号可使用 30 天窗口。退款金额大于当前剩余金额的消费必须在候选生成阶段排除，不得进入排序、候选计数或待审核关系。
2. 依据订单/交易号、去除退款前缀后的标题、标准化对手方和同账户金额证据划分优先级；同一 `payment_mirror` 组的流水先折叠为一个经济事件，`candidate_count` 按经济事件计数。
3. 在最高证据等级候选中，若经济事件唯一则按原有自动窗口确认；若仍有多个经济事件，则选择退款时间与消费时间差最小且唯一者。部分退款和全额退款均适用；最近时间并列时继续待审核。
4. 选中的部分退款记录 `partial_nearest_unique`，全额退款记录 `full_nearest_unique`；证据同时记录候选数量、代表事实 ID 和时间差。
5. 既有已确认退款扣减 `remaining_by_expense`；当前扫描产生的选择必须同步更新剩余金额和占用集合，避免同一批次中合法的后续部分退款被前一笔退款排除。

## 候选优先级与镜像事件

候选优先级从高到低为：订单/交易号完全一致、退款标题完全一致、标准化对手方完全一致、同账户同金额或当前剩余金额、商户包含关系。账户、币种、方向、时间窗口和剩余金额是硬过滤，不是优先级。泛化对手方（如“消费”“支付”或空值）不得单独形成高优先级证据。同来源和同业务日不得单独触发自动确认。

退款阶段接收 Phase B 已确认的 `payment_mirror` 关系并构造镜像连通分组。组内流水只产生一个退款候选；关系端点使用现有镜像标准事实选择，避免微信和银行两条同笔流水同时占用或重复计数。
