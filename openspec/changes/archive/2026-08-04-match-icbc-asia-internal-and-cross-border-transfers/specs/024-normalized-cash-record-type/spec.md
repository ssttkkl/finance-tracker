## MODIFIED Requirements

### Requirement: 主要来源使用来源原生字段分类

系统 MUST 支持以下用户目标：作为账单导入用户，我希望微信、支付宝、工行和建行账单按各自导出的交易类型、收支方向和摘要分类，尤其正确区分还款、转账和换汇。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：**Given** 微信的 `商户消费`、`扫二维码付款`、`充值` 或 `缴费`，**When** 分类，**Then** 为 `consumption`。
- THEN 系统满足该条件，并保留可复核的验证证据。

#### Scenario: 验收场景 2
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：**Given** 微信的 `转账`、`群收款` 或 `微信红包`，**When** 按 `收/支` 分类，**Then** 收入为 `transfer_in`，支出为 `transfer_out`；若来源状态表达该 P2P 交易已退回，**Then** 为 `transfer_reversal`。
- THEN 系统满足该条件，并保留可复核的验证证据。

#### Scenario: 验收场景 3
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：**Given** 支付宝的 `信用借还`，**When** 分类，**Then** 为独立的 `repayment`，不归入 `transfer_in` 或 `transfer_out`。
- THEN 系统满足该条件，并保留可复核的验证证据。

#### Scenario: 验收场景 4
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：**Given** 工行或建行摘要为还款语义，或建行摘要为 `代理收款`，**When** 分类，**Then** 为 `repayment`。
- THEN 系统满足该条件，并保留可复核的验证证据。

#### Scenario: 验收场景 5
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：**Given** 来源明确表达消费退款或退货，**When** 分类，**Then** 为 `refund`；来源明确表达撤销或冲正时，**Then** 为 `reversal`；退款正式信号仍按既有来源规则保存。
- THEN 系统满足该条件，并保留可复核的验证证据。

#### Scenario: 验收场景 6
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：**Given** 来源明确表达工资或奖金，**When** 分类，**Then** 为 `income`。
- THEN 系统满足该条件，并保留可复核的验证证据。

#### Scenario: 验收场景 7
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：**Given** 来源明确表达提现或取现，**When** 按来源方向分类，**Then** 支出为 `withdrawal_out`，收入为 `withdrawal_in`，不归入普通转账类型。
- THEN 系统满足该条件，并保留可复核的验证证据。

#### Scenario: 工行借记卡跨境汇款按转账分类
- **WHEN** 工行借记卡摘要为 `跨境汇款`
- **THEN** 系统 MUST 按来源收支方向分类为 `transfer_out` 或 `transfer_in`，不得分类为 `fx_out` 或 `fx_in`

#### Scenario: 工行借记卡购汇仍按换汇分类
- **WHEN** 工行借记卡摘要表达 `购汇`、`个人购汇`、`预约购汇`、`外汇` 或 `汇兑`
- **THEN** 系统 MUST 按来源收支方向分类为 `fx_out` 或 `fx_in`
