# Data Model: 关系类型角色

本 Feature 不新增数据库列。关系层使用已有 `cash_transactions.record_type`，在内存 `FactView` 上增加同名字段。

## Relationship roles

| 关系角色 | 条件 |
|---|---|
| `refund_in` | `record_type=refund` 且 `amount>0` |
| `transfer_reversal` | `record_type=transfer_reversal`；不产生任何 P0 配对角色 |
| `merchant_expense` | `record_type=consumption` 且 `amount<0` |
| `repayment_out` | `record_type=repayment` 且 `amount<0` |
| `transfer_out` | `record_type=transfer_out` 且 `amount<0` |
| `withdrawal_out` | `record_type=withdrawal_out` 且 `amount<0`，仅用于显式提现到账路径，不等同于普通转账出账 |
| `withdrawal_in` | `record_type=withdrawal_in` 且 `amount>0`，仅作为显式提现到账路径的正向对侧 |
| `transfer_in` | `record_type=transfer_in` 且 `amount>0` |
| `loan_repayment_in` | `account_type=loan`、`amount>0` 且 `record_type` 为 `income`/`transfer_in`/`repayment`，仅用于信用账户还款路线 |

负向 `record_type=refund` 只有在来源快照明确其为原消费角色时才进入 `merchant_expense`，用于覆盖微信全额退款的双行导入。
`record_type=reversal` 或 `record_type=transfer_reversal` 不进入退款角色；个人转账、红包和群收款的退回在导入时即为 `transfer_reversal`。`record_type=withdrawal_in/out` 不进入普通消费、退款或普通转账角色。

## 退款候选事件组

退款匹配使用已接受的 `payment_mirror` 边构造候选事件组。一个事件组可以包含微信、支付宝或银行对同一经济流水的多个来源事实，但在退款候选中只计为一个候选，并选择现有镜像标准事实作为关系端点。候选事件组必须继续满足同账户、同币种、金额方向、15 天普通窗口（锁定证据最多 30 天）和剩余金额约束。

候选事件先按订单/交易号、去前缀标题、标准化对手方、同账户金额和商户包含关系分级；同级多事件再以退款与消费的时间差排序。部分退款与全额退款都要求最近事件唯一，时间差并列则进入 `pending_review`。
