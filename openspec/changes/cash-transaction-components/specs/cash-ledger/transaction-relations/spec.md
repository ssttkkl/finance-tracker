## MODIFIED Requirements

### Requirement: 原始账本记录与交易关系分离保存
系统 MUST 保留每条现金流水父记录及其支付组成项，不得因同笔支付、转账、还款或退款判断而物理删除或改写来源金额。`payment_mirror`、`transfer_pair` 和 `refund_offset` 的现金端点 MUST 是支付组成项；收支投影只消费已确认关系并按父流水聚合展示。

#### Scenario: 组合支付的部分镜像
- **WHEN** 平台父流水包含多个组成项，其中一个银行卡组成项与银行流水相符
- **THEN** 系统 MUST 只关联对应组成项，保留两个父流水和所有原始金额

### Requirement: 关系种类具有明确业务影响
系统 MUST 支持 `payment_mirror`、`transfer_pair` 和 `refund_offset` 的组成项端点。内部转账和信用账户还款必须保留各组成项余额变化但不计入外部收支；退款必须按组成项金额冲销原消费而不改写父流水金额。

#### Scenario: 组合支付退款原路返回
- **WHEN** 平台退款组成项冲销原消费组成项，并与银行退款组成项建立镜像
- **THEN** 系统 MUST 分别保存 `refund_offset` 和 `payment_mirror`，收支投影只显示正确的父级净消费

### Requirement: 自动确认只接受唯一且精确的候选
组成项关系的自动确认 MUST 使用精确十进制 `applied_amount`、币种、方向、账户和结构化来源证据。部分金额、多对一或一对多候选无法唯一确定时 MUST 保留待审核关系，不得猜测。

#### Scenario: 组成项金额不相等
- **WHEN** 两个候选组成项金额不相等且不存在明确分摊额度
- **THEN** 系统 MUST NOT 自动确认关系
