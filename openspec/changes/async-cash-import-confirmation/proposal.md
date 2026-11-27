## Why

现金导入确认会等待账本事务提交后才返回 HTTP 响应。19 文件合成批次在五台 GitHub runner 上均在约 90 秒时断开，随后才凭原操作键查到收据。关闭 CLI、受理失败、取消和确认入账需要让用户看到各自的实际结果。

## What Changes

- 对现金账单完整写集提供持久受理、状态查询、显式取消及原收据恢复。
- Compose 与 CLI 通过共享操作层使用相同的确认与恢复合同。收到受理回执后即可停止等待，后续仍能按原批次查询。
- 分别验证受理、查询、取消和整批入账的耗时及原子性。文件准备和端侧核对继续沿用原流程。

## Capabilities

### Modified Capabilities

- `import/local-import-pipeline` 定义确认写集的受理、执行、取消与结果恢复。
- `shared/ledger-cli` 定义现金导入确认的默认退出时点和显式提前返回方式。
- `shared/runtime-database` 定义本变更引入的 55 基线和显式 PostgreSQL 54→55 升级。

## Impact

涉及后端 HTTP 与 PostgreSQL 持久化、共享客户端操作、Compose 和 ft 的现金确认路径。其余导入来源、同步、扫描、快照和性能门禁任务均在本变更范围外。
