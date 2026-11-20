## 1. 思考
- [x] 复核文件解析、缓存和页面调用链。B 类，范围为既有导入 Worker 的本地文件职责，无持久化合同变化。

## 2. 计划
- [x] 确认单文件恢复、端侧与服务端分流和验收。

## 3. 任务拆分
- [x] 拆分失败回归、后台处理、单文件恢复、跨端验证及发布。

## 4. 构建
- [x] Node 基线缺少 `startFile`，失败回归后接入文件读取、原字节分流、共享归一、捕获摘要及单文件恢复。

## 5. 审查
- [x] 独立复核 Worker 响应支路、原文件引用和捕获一致性。保留已知解析拒绝，Worker 启动／通讯失败按文件重试，程序错误沿现有全局停止路径。缓存格式与外部接口维持既有合同，无新双库测试。Web 沿用既有文件行、错误位置及重试按钮，原生仅受共享捕获和错误合同影响。

## 6. 测试与 QA
- [x] 同批选择两份 CSV，第二份触发 Worker 故障。Chrome 确认首份仍可见，单独重试后两份都准备就绪，随后验证正常确认与离线恢复。增强用例和直接 Worker 用例共 2 项通过，复用同一浏览器命令按用例名筛选。截图与追踪见 `/tmp/ft-worker-playwright.mGdBFL/multi-file-traces`。
- 核对基线为 `5a25a8c5a037ddd83c43ef17dddd6c8630b0440e`，验证日期为 2026-11-19。仓库外日志与浏览器追踪仅使用合成账单。
- 在 `compose/` 离线运行 `./gradlew :shared:testAndroidHostTest --tests '*LedgerSessionImportsTest' --tests '*LocalCashStatementReaderTest' --tests '*LocalCashImport*' --tests '*ImportTaskViewModelTest' :shared:iosSimulatorArm64Test :shared:compileKotlinJs :business-domain:jvmTest --offline --console=plain --continue`，Android 267 项、iOS 模拟器 990 项、领域 JVM 908 项均通过。另运行 `./gradlew :androidApp:compileDebugKotlin :shared:compileKotlinIosArm64 --offline` 通过。原生真机交互超出本次验收范围。
- `node --test compose/qa/web/tests/cash-import-worker-client.node-test.mjs` 通过，`compose/qa/web/` 的 `npm run test:node` 共 36 项通过。直接运行生产 Wasm 校对两个 3,000 行文件的归一结果与原字节，并验证签名为 PDF 和旧 XLS 的输入分流。
- Chrome 157.0.8103.79、1440 × 1100、`http://127.0.0.1:5191`，登录隔离工作区后关闭网络，跟踪选择本地文件、后台解析、账户准备与核对。CSV／XLSX 的文件阶段最长主线程任务从 519／1,148 毫秒降至 219／215 毫秒。该组数据为相同合成输入各一次的浏览器观测。性能 trace 在 `/tmp/ft-import-profile.dWlpXo/paged-worker`。
- 生产包 Chrome 初次验收 4 项通过，含 Worker 终止后重新创建、单文件重试、离线核对、上线确认、冲突重新核对和离线冷启动。命令在 `compose/qa/web/` 指向本地生产预览配置执行 `npx playwright test --config /tmp/ft-worker-playwright.mGdBFL/playwright.config.ts compose-local-import.e2e.ts`。应用验收检查请求和控制台错误，证据见 `/tmp/ft-worker-playwright.mGdBFL/final-traces`。

## 7. 发布
- [x] 已核对 Compose 发布说明、导入及总功能地图、词表和规范。用户选择的单文件重试写入 `import/local-import-pipeline`，其适用范围为 Web 本地文件 Worker 故障，共享规范无需增订。缓存和 API 格式未变，回滚可恢复上一版 Web 静态产物。当前交付范围为任务分支和本地预览。
