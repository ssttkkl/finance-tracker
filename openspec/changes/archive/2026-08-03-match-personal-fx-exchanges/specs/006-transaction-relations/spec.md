## ADDED Requirements

### Requirement: 个人购汇关系的安全确认
系统 MUST 仅在负额 `fx_out` 与正额 `fx_in` 的类型、方向、币种、来源和时间形态均合法时确认 `transfer_pair(currency_exchange)`。自动确认 MUST 额外满足非空无冲突的同一来源键、可靠钟点不超过 60 秒、双向唯一候选与端点未被其他已确认 `transfer_pair` 占用。来源缺失或冲突、仅日期精度、超窗或任一方向候选不唯一时系统 MUST NOT 自动确认；候选歧义 MUST 以 `fx_out` 为锚点创建一条开放端 `pending_review`，并记录候选、来源和时间证据。

#### Scenario: 已确认购汇保持双边审计
- **WHEN** 一对 `fx_out` / `fx_in` 满足所有自动确认条件
- **THEN** 系统 MUST 创建双边 `accepted transfer_pair(currency_exchange)`，保留两条原始事实和确认依据

#### Scenario: 歧义购汇不改变收支
- **WHEN** 购汇的正向或反向候选不唯一，或来源/时间证据不足
- **THEN** 系统 MUST 仅创建待审核关系或不创建关系，且不得改变任一现金事实的当前收支投影

### Requirement: 个人购汇的人工确认边界
系统 MUST 在 `pending_review → accepted` 时验证 `currency_exchange` 的子类型端点合同：锚点为负额 `fx_out`、对侧为正额 `fx_in`、双方币种不同、来源键非空无冲突且一致、对侧在合法候选范围内且两个端点均未被已确认转账占用。人工确认 MAY 消解多个合法候选，但 MUST NOT 将普通收入、退款或普通转账绑定为个人购汇。

#### Scenario: 人工确认拒绝非法购汇对侧
- **WHEN** 用户为待配对购汇指定不满足端点合同、来源、候选范围或端点占用约束的对侧
- **THEN** 系统 MUST 拒绝确认并保持待审核关系及原投影不变
