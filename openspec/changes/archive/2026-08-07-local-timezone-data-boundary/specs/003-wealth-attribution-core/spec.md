## ADDED Requirements

### Requirement: 财富规范日桶使用 UTC 边界

财富规范日桶、月度边界、估值新鲜度比较和 read model 查询 MUST 使用 UTC aware 边界；用户输入的带时区时间 MUST 先转换为 UTC，系统不得固定绑定 `Asia/Shanghai`。

#### Scenario: 带 offset 估值落入 UTC 日桶

- **WHEN** 估值时间带有非 UTC offset 且跨越其来源日期与 UTC 日期边界
- **THEN** 财富重建 MUST 按 UTC 日期确定日桶和边界，并在 SQLite 与 PostgreSQL 上返回相同结果
