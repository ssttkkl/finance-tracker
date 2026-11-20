## ADDED Requirements

### Requirement: Web 流水读取共享后台账本索引

Compose Wasm 的流水列表、详情、来源证据和条目定位 MUST 基于同一工作区的已提交快照在后台读取。页面读取账户、分类和筛选选项 MUST 避免为每次读取解码同一全量快照。

#### Scenario: 打开大账本中的流水详情

- **WHEN** 浏览已加载账本中的流水或来源明细
- **THEN** 客户端 MUST 复用当前版本的后台索引，保持稳定身份、权限及来源事实
- **AND** 后台中断时 MUST 沿原错误入口恢复
