## ADDED Requirements

### Requirement: 财富现金流读取组成项来源
财富归因的现金流输入 MUST 从有效支付组成项按账户、币种和金额生成；父流水金额不得作为第二份现金流。组成项新增、修改、删除或关系变化 MUST 推进财富来源 revision。

#### Scenario: 组合支付进入财富归因
- **WHEN** 一个 aggregate 父流水包含多个现金账户组成项
- **THEN** 财富现金流 MUST 分别记录各账户组成项金额，并保持期间外部现金流总额等于父流水总额

#### Scenario: 组成项写入触发重建
- **WHEN** 组成项金额或账户发生变化
- **THEN** 财富读模型 MUST 识别新的来源 revision，不能继续使用旧现金流快照
