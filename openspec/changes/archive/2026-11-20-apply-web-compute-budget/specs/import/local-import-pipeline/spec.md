## ADDED Requirements

### Requirement: Web 导入准备按后台计算预算执行

Compose Wasm MUST 在后台扫描已捕获账单以准备账户核对，并在后台归一化服务端返回的旧 XLS 原始行。金额、行数、账户选择和逐文件恢复沿原合同。

#### Scenario: 大量账单准备

- **WHEN** 添加大批账单或继续选择对应账户
- **THEN** 客户端 MUST 使用原文件及同一已提交账本准备账户建议，后台计算失败按原任务或受影响文件恢复
