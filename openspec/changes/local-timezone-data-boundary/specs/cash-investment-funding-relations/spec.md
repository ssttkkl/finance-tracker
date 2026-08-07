## ADDED Requirements

### Requirement: 资金调拨窗口使用明确 UTC 日历

现金—投资资金调拨候选的时间窗口 MUST 基于 UTC aware 时间计算；带 offset 的现金流水和投资事件 MUST 先归一化为 UTC，再判断自然日窗口，且不得固定使用 `Asia/Shanghai`。

#### Scenario: 不同 offset 的候选按 UTC 窗口判断

- **WHEN** 现金流水和投资事件带不同合法 offset，且归一化后的 UTC 日期差在允许窗口内
- **THEN** 系统 MUST 保留该候选并在两个正式数据库后端得到相同匹配结果
