## ADDED Requirements

### Requirement: 月份分割行遵循展示时区

系统 MUST 按主记录发生时间在 `Asia/Shanghai` 的年月插入收支账本月份分割行。月份归属 MUST 与页面展示的发生时间日期一致，不得直接使用 UTC 时间戳字符串的月份。

#### Scenario: UTC 月末时间在上海跨入下月

- **WHEN** 投影条目的发生时间为 UTC 月末，但按 `Asia/Shanghai` 展示后日期落在下一个自然月
- **THEN** 该条目显示在下一个自然月的月份分割行下
- **AND** 页面显示的发生日期与月份分割行月份一致
