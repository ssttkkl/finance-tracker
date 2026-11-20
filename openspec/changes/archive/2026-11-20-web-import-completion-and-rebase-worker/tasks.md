## 1. 思考

- [x] 核对导入及基线拒绝规格、旧版 Chrome 大批测量、批次加密存储和平台实现。以 B 类处理范围明确的 Web 调度变更，沿用现有写入与恢复边界。

## 2. 计划

- [x] 确认选定范围为大批导入确认收尾和账本基线变化后的完整重算，原入口继续处理提交与恢复。

## 3. 任务拆分

- [x] 先增加调度回归，再完成确认收尾及基线重算，覆盖失败恢复、浏览器及原生验证。

## 4. 构建

- [x] 后台处理大批确认后的编解码，复用真实收据和已验证归档。
- [x] 后台重算和冻结下一尝试，验证明确拒绝、取消及 Worker 故障边界。

## 5. 审查

- [x] 独立工程、范围及契约复核覆盖工作区权限、精确事实、旧批次兼容、原键优先、提交 CAS、取消与原生路径。工作线程区分处理器异常与传输故障，安全阶段和错误类别可用于诊断。
- 复核问题已落实为按同一 SQL 行验证归档的三项身份及正文、复用已校验归档、完整结果在 Worker 聚合校验、首次记录已知收据的传输故障保护，以及清理完成前保留原批次恢复引用。后置引用提升的原有存储错误继续走错误边界，原引用仍能打开结果。用旧客户端提前写成收据引用的存量批次可以读取已归档结果，旧批次清理兼容不在本次范围。
- 契约专项验证确认旧文件数组、v1 批次、v1 归档以及 SQL 表、HTTP 正文兼容。无组件、文案、设计 token 或新操作。重新打开沿用原结果视图和整理入口。
- Web 使用专用 Worker。Android、iOS 使用本地 Kotlin 计算并共享原批次恢复引用的生命周期修正。发布不涉及后端持久化行为，双后端矩阵不适用。

## 6. 测试与 QA

- [x] 严格校验、Node 回归、生产 Web 构建及真实 Chrome 导入、恢复与大批测量。
- [x] Android、iOS 受影响编译及单元测试。
- 2026-11-20 北京时间，验证基线与实施前 HEAD 均为 `cfa678783e7c1c1fed7eaaa6305be8b1982c3306`。OpenSpec 严格校验和 `git diff --check` 通过。Compose 生产构建运行 `./gradlew :webApp:wasmJsBrowserDistribution --max-workers=1 --offline --console=plain`。`./gradlew :webApp:compileKotlinJs --max-workers=1 --console=plain` 和 `npm run test:node` 通过，Node 共 36 项。
- `./gradlew :shared:testAndroidHostTest --tests '*Cash*' --tests '*LedgerSession*' --tests '*LedgerSnapshot*' --tests '*ImportTaskViewModelTest*' --tests '*SqlLedgerImportFilesTest*' :shared:iosSimulatorArm64Test :business-domain:jvmTest :androidApp:assembleDebug :shared:compileKotlinIosArm64 --continue --max-workers=2 --offline --console=plain` 通过。Android 694 项通过，2 项原有运行条件跳过，生成 `FT_KOTLIN_CLIENT_CONTRACT_DIR` 可补两项响应录制测试。iOS 模拟器单测 990 项，共享规则 JVM 908 项，Android 应用与 iOS 真机架构共享模块编译通过。设备交互不在本次验证范围。
- Chrome 157.0.8103.79 访问本地生产预览 `http://127.0.0.1:5191`。Playwright 运行 `compose-local-import.e2e.ts`、`compose-local-first-cash.e2e.ts`、`compose-cash-month-summary.e2e.ts`，15 项通过。视口 390、768、1440，导入使用 1440 × 1100。验证明确拒绝、原键重算故障、无关账本变更的同结论重派、需要重新核对、已成功后收据和快照 Worker 故障、重开后无额外收据查询或写入及断网冷启动。验证结果与截图见 `/tmp/ft-rebase-chrome-final`。仅出现各用例预期的故障注入与 HTTP 拒绝。Safari 和 Firefox 未实测。

- Chrome 同源本地预览分别导入 3,000 行合成 CSV 和 XLSX，均写入一次，返回 3,000 条新增收支并显示结果。服务端确认到结果显示这段，主线程大于 50 毫秒的任务由每种格式 4 项降为 1 项，最大值由约 746 毫秒降为约 263／248 毫秒。包含 HTTP、Worker 消息和存储的端到端总时间在这些单次样本里没有缩短。原确认及 HTTP 处理期间仍有主线程长任务。独立重跑的 CSV 观察到 235 毫秒的最长收尾任务，并用实际 Canvas 操作验证可从结果返回账本。
- 3,000 行 CSV 另注入无关账户变更，原提交明确失败，Worker 完整重算约 22.5 秒。该 Worker 请求窗口有 1,350 次动画帧回调，主线程没有超过 50 毫秒的任务。最终使用下一尝试键一次保存 3,000 条。指标、截图及追踪见 `/tmp/ft-import-profile.dWlpXo/completion-worker`，对照见同目录树的 `next-worker`。

## 7. 发布

- 已核对导入与总功能地图、词表、原 HTTP 和运行命令。产品选择沉淀在本次现金导入规格，暂无适用于其他模块的规则增补。回滚使用上一版客户端资源，现有导入材料及归档可继续读取。
- [x] 核对现状资料、同步规格并归档，按当前任务分支授权交付。
