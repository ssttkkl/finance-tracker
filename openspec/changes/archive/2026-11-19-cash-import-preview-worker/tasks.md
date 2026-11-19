## 1. 思考

- [x] 查明 Compose Wasm 调度、预览调用、现有 SQLite Worker 与离线合同。按 B 类处理，范围限于预览调度、消息适配和静态产物，复用既有业务规则和写入事务。

## 2. 计划

- [x] 明确后台线程、离线缓存、原选择重试和验收。文件解析、提交后的冲突恢复及数据迁移不在本次范围。Web 重试沿用“重新核对”和既有错误反馈。

## 3. 任务拆分

- [x] 对齐后台计算、精确传输、断网、故障、取消、跨端编译和 Web 演示。

## 4. 构建

- [x] 基线 Chrome 请求预览 Worker 入口返回 HTML，独立计算回归失败。Android 测试先复现原“重新核对”清除未发布选择，Node 测试先复现旧请求取消终止同一 Worker 的后续请求。
- [x] 将 Web 预览计算接入独立 Wasm Worker，等待加载就绪后发送请求，保留原快照和会话围栏。产物按内容指纹以同源静态资源发布，正常复用 Worker，取消和故障终止并可重建。
- [x] 将 Worker 故障连到已有重试入口；重试不重新装载或清除原输入。

## 5. 审查

- [x] 工程与契约独立复核覆盖缓存批次读取、标准化文档、快照、选择、写集、核对上下文、会话围栏、发布以及异常边界。修复三个阻断点：编译产物使用命名导出，取消须核对请求身份，离线重建须依赖同版本缓存。实测另发现初始化挂起时消息可能丢失，补就绪握手及回归。域错误保留既有码及诊断，Worker 通讯失败只返回安全错误，不输出账单数据。无数据库或外部接口变更。
- [x] UI 复用、Hallmark audit 和独立设计复核核对现有错误、“重新核对”、忙碌状态及后续可确认资格。使用 Material 3 《Buttons》和《States》的动作与状态语义，沿用相同控件、标签和 token。Chrome 代表视口 1440 × 1100 观察故障与恢复。范围内无新增视觉 finding；原 Compose Canvas 弹层无障碍树滞后由现有 QA 坐标路径处理。
- [x] 跨端检查。Web Wasm 运行独立 Worker；JS、Android、iOS 通过共享计算适配沿用 `Dispatchers.Default`，共享重试状态与核对编解码完成对应验证。原生设备交互与渲染不在范围，持久化读写和 API 合同未变，因此不触发新增双库矩阵。

## 6. 测试与 QA

验证于 2026-11-19 执行。比较基线与验证时 HEAD 均为 `68e905294e64a5587b68c7bbb722a7b97c7b507c`，对象为本 change 的工作树差异。

- [x] `./gradlew :webApp:wasmJsBrowserDistribution --offline` 成功，确认实际 Worker 入口、命名导出、Wasm、内容指纹和缓存响应头。Node Worker 客户端及 Compose 构建新鲜度测试通过。
- [x] Chrome 157.0.8103.79，通过同源 `http://127.0.0.1:5191/` 和临时 SQLite 后端运行 `compose-local-import.e2e.ts`。三条 1440 × 1100 导入流程通过，覆盖实际登录、离线核对、Worker 崩溃后无写入并重新核对、基线冲突、联网确认和已保存结果整理。测试内的意图性断网请求按原诊断规则处理。截图为 Playwright 输出目录中的 `local-csv-worker-failed.png` 和 `local-csv-offline-restored-review.png`。
- [x] 同文件的 1,000 行浏览器测试通过。关闭实际网络后终止初始 Worker，使用缓存的指纹产物重建；验证全量写集金额为 `-10.0001`、基线版本为 `9007199254740993`、页面帧回调和点击仍执行。Chrome CDP 轨道另确认计算调用位于 `DedicatedWorker thread`，约 4.30 秒内记录 259 个页面帧回调和 1 次点击，输出与同模块直接计算逐字相同。诊断轨迹 `/tmp/ft-cash-import-worker-chrome-trace.json`，未存入仓库。
- [x] `./gradlew :shared:testAndroidHostTest --tests '*ImportTaskViewModelTest' --tests '*LocalCashImport*' --tests '*LedgerSessionImportsTest' --offline` 通过 256 项。全量 Android Host 尝试中 17 个独立 HTTP 合同测试因缺少 `FT_KOTLIN_CLIENT_CONTRACT_DIR`、投资及持仓合同夹具目录与 live host 文件未通过；补跑这些测试需要对应的合成 HTTP 夹具环境。
- [x] `./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:iosSimulatorArm64Test :shared:compileKotlinJs :business-domain:jvmTest --offline --continue` 中各列任务成功，iOS 模拟器 990 项、领域 JVM 908 项零失败。原生设备交互不在默认验证范围。
- [x] `./gradlew :androidApp:compileDebugKotlin :shared:compileKotlinIosArm64 --continue` 成功，`python3 -m unittest discover -s compose/tests -p 'test_build_wasm_web.py'` 通过 11 项。
- [x] `openspec validate --all --strict`、`openspec doctor`、`git diff --check` 通过。

## 7. 发布

- [x] 核对 Render 静态配置、Compose 构建文档、导入功能地图与总览；现有词表覆盖导入核对的业务含义。用户确认 Worker 不可用时保留任务并通过原流程重试，此决定已写入导入规格，适用范围为后台预览，无需沉淀到通用规则。
- [x] 本地演示在 `http://127.0.0.1:5191/` 提供，使用临时合成后端，复用原页面交互。生产发布由现有同源静态分发目录承载；回滚可重新部署对应的前一版完整 Web 产物。页面编码、解码和缓存文件校验仍可能耗时；离线重建依赖浏览器保留已预热的静态资源。
- [x] 同步主规格并归档变更。
