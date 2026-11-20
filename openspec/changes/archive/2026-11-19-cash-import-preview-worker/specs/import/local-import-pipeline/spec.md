## ADDED Requirements

### Requirement: Web 现金导入预览在后台计算

Compose Wasm 的现金导入 MUST 在浏览器后台线程生成预览和写集，按原有端侧规则使用同一版本的账本事实和用户选择，支持离线核对。

#### Scenario: 核对大批账单

- **WHEN** 用户在 Compose Wasm 中请求现金导入预览
- **THEN** 客户端 MUST 在后台线程执行预览计算，页面可继续绘制并处理输入，同一输入得到与共享领域规则一致的预览、写集和核对结论

#### Scenario: 后台计算不可用

- **WHEN** Worker 无法启动或预览计算中断
- **THEN** 客户端 MUST 保留当前导入任务和用户选择，让用户通过现有流程重试，且 MUST NOT 在页面线程重做同一计算或提交写集
