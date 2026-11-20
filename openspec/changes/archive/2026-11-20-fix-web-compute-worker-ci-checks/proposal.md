## Why

PR #123（Web 计算迁移到 Kotlin/Wasm Worker）的必需检查暴露三处与本次迁移直接相关的缺口：

- `Compose client` 与 `Compose shared and native checks` 在 `:shared:wasmJsTest` 共 88 项失败。浏览器测试环境只代理了账本存储资源，没有提供 `/cash-import-worker/client.mjs` 及版本化 Worker 产物，生产代码按设计报 `cash_import_worker_failed`。
- 不支持 Wasm 的浏览器启动场景被既有测试断言为零 Console 错误，但启动诊断把已处理的兼容性分支记为 `console.error`；该失败自 #120 起已存在于 `main`。
- 管理页布局用例在 Web 字体加载完成前读取语义节点边界，CI 偶发读到字体回退布局的瞬时宽度，出现 3 像素重叠。

## What Changes

- 浏览器集成测试从与生产 Web 相同的 `:webApp:stageCashImportWorker` 版本化产物提供计算 Worker，并声明任务依赖与测试输入，使 `:shared:wasmJsTest` 真正覆盖 Worker 调用。
- 启动诊断对已处理的不支持浏览器分支使用普通 Console 记录；同一条件仍通过 `reportError` 进入 Sentry，诊断条目与字段不变。
- Web 浏览器用例在读取布局边界前等待 `document.fonts.ready`、两帧渲染并确认连续采样稳定；点击、输入与只读边界检查复用同一等待。
- 更新导入测试的本地存储替身以支持 Worker 分页归档；让依赖真实 Worker 往返的 ViewModel 用例等待事件循环轮次；按平台显式记录预览计划标识的分配差异。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。`shared/deployment-diagnostics` 已要求诊断可通过 Console 查看并排除已处理分支的误报；`import/local-import-pipeline` 与 `cash-ledger/cash-ledger-browser` 已规定 Worker 失败语义。本次只补齐验证环境与测试时序，不改变对外行为、财务语义或数据格式。

## Impact

影响 Compose 浏览器测试构建、Web 启动诊断的 Console 级别及三个 E2E 用例的等待方式。生产 Worker 计算、主线程预算、后端、数据库和原生端不变。回滚可恢复测试构建依赖与原日志级别；测试改动不进入生产包。
