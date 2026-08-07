## ADDED Requirements

### Requirement: 收支账本按浏览器本地时区展示时间

收支账本 MUST 使用浏览器本地时区格式化投影条目的发生时间和月份分割行；Web API 返回的 `occurred_at`、证据记录时间和 cursor 时间 MUST 携带明确 offset。日期筛选 MUST 额外携带浏览器的有效 IANA 时区，月度汇总的月份键 MUST 与该时区一致。

#### Scenario: 浏览器本地月份与 UTC 月份不同

- **WHEN** 一条投影的 UTC 发生时间在浏览器本地时间已进入下一个月份
- **THEN** 页面 MUST 按浏览器本地月份插入分割行并显示该本地时间

#### Scenario: 日期筛选按浏览器本地日生效

- **WHEN** 使用者通过日期输入筛选账本并携带浏览器本地 IANA 时区
- **THEN** API MUST 返回该时区自然日范围内的投影，不能按服务器时区或固定 `Asia/Shanghai` 范围解释

#### Scenario: 前端拒绝固定展示时区

- **WHEN** 代码执行发生时间或月份格式化
- **THEN** 格式化 MUST 使用浏览器默认时区，不得传入固定 `timeZone` 选项
