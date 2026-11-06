## Why

PR #115 的 Backend Kotlin 检查中，`PortfolioValuationLifecycleTest.ignoredSingleQuoteDoesNotKeepProcessAlive` 在其进程输出断言处失败；相同测试在本机通过。测试用 50 毫秒共享查询预算启动异步行情线程，并要求子进程已打印 `BLOCKING_STARTED`，因此在 CI 调度较慢时，预算可能在线程实际调用 provider 前耗尽。该失败属于回归测试时序不稳定，尚无证据表明估值服务行为错误。

## What Changes

- 为两个共享辅助函数的生命周期子进程测试提供足够的启动预算，使测试先实际进入忽略取消的 provider，再验证其不会阻止 JVM 退出。
- 保留现有 `partial` 结果、provider 已启动以及子进程在 provider 继续阻塞时退出的断言；不放宽业务断言或取消测试。

范围仅限 `ignoredSingleQuoteDoesNotKeepProcessAlive`、同 helper 的 `ignoredPredictionMarketBatchDoesNotKeepProcessAlive` 及其共同测试时序根因；最新 CI 在原单引号用例通过后，将相同断言失败暴露在批量用例。用户确认把修复扩展至这两个并列回归用例。不改生产估值逻辑、其他测试、API、数据、财务语义或 CI 工作流。验收为两个用例在重复本地运行中稳定通过、完整后端测试通过，PR #115 所有必需 CI 检查通过。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。投资组合估值主规格已有有界查询预算和局部失败要求；本次仅校正其既有合同的回归测试时序。

## Impact

影响后端估值生命周期测试，不改运行时实现或存储。回滚测试时序参数即可，无数据或部署影响。PR CI 还包含 Compose Web E2E 与 Compose 共享／原生检查，需等待其最终结果；本次仅处理用户确认的两个生命周期回归用例及其共同直接根因。
