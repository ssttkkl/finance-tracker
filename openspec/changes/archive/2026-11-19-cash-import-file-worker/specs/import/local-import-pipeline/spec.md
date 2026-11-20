## ADDED Requirements

### Requirement: Web 本地文件处理在后台执行

Compose Wasm MUST 在后台线程有界读取、解析并归一浏览器选取的 CSV／XLSX 现金账单。格式由原字节和渠道规则识别，继续支持离线。文件捕获须保留原字节摘要及标准化账单。

#### Scenario: 选择可本地解析的文件

- **WHEN** 用户选择受支持的 CSV 或 XLSX 账单
- **THEN** 客户端 MUST 在后台完成文件准备并沿原流程显示同样的识别结果

#### Scenario: 单文件后台失败

- **WHEN** Worker 无法完成本地文件处理
- **THEN** 客户端 MUST 只将受影响文件交给现有单文件重试流程，保留同批其他已完成文件
