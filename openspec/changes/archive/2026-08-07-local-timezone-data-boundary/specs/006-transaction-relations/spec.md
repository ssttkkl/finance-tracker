## ADDED Requirements

### Requirement: 关系时间比较不依赖固定地区时区

关系匹配 MUST 将带 offset 的 `occurred_at` 转换为 UTC 进行时间差、排序和日期分桶；无 offset 的非导入器时间 MUST 按 UTC 处理或拒绝，不得按固定地区时区补全。

#### Scenario: 相同瞬时跨 offset 参与配对

- **WHEN** 两条流水使用不同合法 offset 表示同一瞬时，并满足其他关系条件
- **THEN** 关系匹配 MUST 将它们视为相同 UTC 时间，不得因服务器或固定地区时区不同而改变结果
