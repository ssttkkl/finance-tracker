# Relation Contract: Formal Record Type Gates

关系扫描必须满足：

1. `FactView.record_type` 与 `cash_transactions.record_type` 一致。
2. `record_type=income` 不因文本包含“退款”成为退款种子。
3. `record_type=consumption` 不因文本包含“转账”成为转账种子。
4. 负向 `record_type=repayment` 可进入信用还款转出路径；正向贷款账户 `income` 只作为该路径的合法对侧。
5. 关系候选生成阶段不得把不同账户的退款行列入退款候选。
6. 正向 `record_type=reversal` 或 `record_type=transfer_reversal` 不能成为退款种子，也不能因摘要包含“退款”进入退款候选。
7. `record_type=withdrawal_out` 不能通过普通 `transfer_out` 类型闸门；只有显式提现到账规则可以使用 `withdrawal_out` → `withdrawal_in` 发起 `transfer_pair`。
8. 普通转账只能使用 `transfer_out` → `transfer_in`；贷款账户正向 `income` 只可作为 `repayment` 的对侧。
9. 提现到账的入账对侧必须来自银行渠道，且类型为 `withdrawal_in` 或 `transfer_in`；支付平台余额入账不得形成提现关系。
10. 个人转账、红包和群收款的退回必须是 `transfer_reversal`，不参与 `refund_offset`、支付镜像、普通转账候选或平台退款硬键关系。
11. 银行日期型同笔支付候选没有交易对方、订单、卡尾号或可信时间证据时必须为待审核，不得自动确认。
12. 普通退款候选和自动确认窗口为 15 天（含边界）；订单号或交易号锁定证据可将窗口扩展至 30 天。
13. `refund_offset` 候选必须先按订单/交易号、退款标题、标准化对手方和金额证据分级；同一最高等级仍有多个经济事件时，比较退款与消费的时间差。
14. 部分退款和全额退款均可在最高等级候选中选择时间差最小且唯一的经济事件；时间差并列必须保持 `pending_review`。
15. 已接受的 `payment_mirror` 关系必须在退款候选中折叠为一个经济事件；镜像流水不得重复计入 `candidate_count`，也不得同时成为退款关系的两个端点。
16. 退款金额大于消费当前剩余金额的候选不得自动配对；同账户、同币种、方向、退款晚于消费和剩余金额仍是硬闸门，不是候选优先级。
