## Why

现金导入核对时，Web 上的关联扫描和写集生成会占用页面主线程。大批账单计算期间，页面可能无法绘制或及时响应输入。

## What Changes

- Compose Wasm 的现金导入预览通过独立 Worker 运行共享计算。
- Worker 启动或运行失败时保留导入任务，由用户通过现有操作重试。
- 验收以同一输入的预览、写集和核对上下文一致，以及真实浏览器中计算运行在 Worker 为准。
- 文件解析、提交后的冲突恢复计算、提交事务和原生端调度在首版范围之外。

## Capabilities

### Modified Capabilities

- `import/local-import-pipeline` 增加 Web 预览的后台执行与失败恢复要求。

## Impact

影响共享客户端的预览调用边界、Web Worker 产物和 Web 发布构建。无需数据库、接口或数据迁移。回滚可恢复原 Web 预览调用方式。
