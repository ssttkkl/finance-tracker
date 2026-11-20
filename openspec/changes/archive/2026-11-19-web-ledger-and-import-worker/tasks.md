## 1. 思考

- [x] 核对现有浏览器追踪、缓存范围、共享查询、确认写入与收据恢复。归入 A 类。基线 `6016e51aef2f9bc358234965742ba20c0ffc6690`。

## 2. 计划

- [x] 确定三个后台边界和跨端范围，确认流水 Worker 故障时保留内容并通过现有入口重试。

## 3. 任务拆分

- [x] 按核对输入、流水查询、确认和结果分段，先安排故障及精度用例。

## 4. 构建

- [x] Web 从现有缓存取出原编码，在 Worker 准备核对输入。Node 协议测试在添加入口前失败。
- [x] 迁移现金流水查询与汇总，按范围和版本保留后台索引。Chrome 回归在旧分发包因没有流水 Worker 先失败。
- [x] 后台冻结确认材料并整理结果，保留原子保存、原提交键和真实收据。预览协议先新增后台冻结测试，确认故障与收据恢复由真实浏览器验证。

## 5. 审查

- [x] 独立复核范围、实现、故障、精度和读写安全，完成对外合同与既有错误入口审查。
- 独立工程及契约评审确认写集、归档文本、原键、权限范围及 CAS 与基线相同。修复保存后 Worker 故障被归为不可恢复，以及核对和确认前为比对版本而完整解码文件。结果明细页解析和后端收据 HTTP 未列入本次迁移。
- 独立工程及 UI 复用评审确认现有错误区域与低强调重试按钮、无新增设计 token。修复筛选后旧结果仍可选择、版本轮询重复派生、Worker 缺少安全失败阶段。新增显示列表的版本检查也覆盖已打开的批量对话框。
- Hallmark 范围审查查看首次失败及确认前中断截图，独立设计复核核对旧结果、重试位置、状态与控件复用。沿用当前 Material 3 按钮、等待指示和错误区域，没有新增文案或页面结构。完成范围内未发现阻断视觉问题。

## 6. 测试与 QA

- [x] 运行受影响 Web、原生、共享和构建验证。真实浏览器验证离线、版本边界、失败恢复与确认，记录合成数据性能。
- 2026-11-19 基线 `6016e51aef2f9bc358234965742ba20c0ffc6690`。`./gradlew :webApp:wasmJsBrowserDistribution --max-workers=1 --offline --console=plain` 通过。与 iOS 并行编译时 Wasm 链接曾耗尽 JVM heap，独立构建恢复。产物与本地预览于验收前核实。
- Android Host 选择 `--tests '*Cash*' --tests '*LedgerSession*' --tests '*LedgerSnapshot*' --tests '*DiagnosticLogger*'`，580 项通过、2 项按原外部夹具条件跳过。未筛选全量测试有 17 项历史 HTTP 集成因缺少当前后端响应目录及投资 live 测试主机而失败。对应复跑条件是生成 `FT_KOTLIN_CLIENT_CONTRACT_DIR`、`FT_KOTLIN_INVESTMENT_READ_CONTRACT_DIR` 与 `FT_KOTLIN_PORTFOLIO_CONTRACT_DIR`，并设置 `FT_KOTLIN_PORTFOLIO_HOST_FILE`。无服务端存储或接口变化，双库矩阵不适用。
- `:shared:iosSimulatorArm64Test` 990 项和 `:business-domain:jvmTest` 908 项通过。Android 应用、iOS Arm64 共享模块、Kotlin/JS 编译通过。生产 Browser 覆盖 Web，原生保持本地业务实现，未运行设备交互。
- `compose/qa/web/` 的 `npm run test:node` 共 36 项通过。Chrome 157.0.8103.79 访问 `http://127.0.0.1:5191`，`compose-local-first-cash.e2e.ts`、`compose-cash-month-summary.e2e.ts`、`compose-local-import.e2e.ts` 共 12 项通过。覆盖 390／768／1440 视口的汇总、首次失败、保留既有流水和本地重试、实际断网 Worker 重建、精确版本、导入冻结前故障零次写入、保存后归档故障只一次写入、冲突与离线冷启动。首次生产包证据见 `/tmp/ft-next-worker-final-chrome`。
- 最终产物重跑 6 项流水与月份场景，并以实际 Canvas 点击重跑保留旧行的第 7 项，全部通过。验证延迟期间旧行不进入选择模式、Worker 崩溃后行保留、重试未调用 API。证据见 `/tmp/ft-next-ledger-verified.log` 与 `/tmp/ft-next-ledger-canvas.log`。截图及浏览器控制台未见非注入的功能故障。Safari、Firefox 未实测。
- 同组 3,000 行文件从点击核对到发出 Worker 请求，CSV 约 523 → 204 毫秒，XLSX 约 484 → 176 毫秒。两者在这段各 5 个超过 50 毫秒的主线程任务降至 0。直接断网重建 3,000 行流水查询约 440 毫秒，期间有 28 次帧回调，复用索引读取第二页约 15 毫秒。
- 两份合成账单均走实际页面及本地后端，提交一次、生成 3,000 条新增收支并呈现结果。在 Chrome 中确认材料的后台请求往返约 682／699 毫秒，归档准备往返约 61／52 毫秒。确认后的收据与缓存处理仍有主线程长任务，此次样本最长约 746 毫秒。账单编码原样跨线程，测量以主线程响应为准，未把计算和存储耗时合并成提速数据。原始浏览器追踪、报告和截图见 `/tmp/ft-import-profile.dWlpXo/next-worker`。

## 7. 发布

- [x] 核对 Compose README、两个模块及总功能地图、词表、规则、配置和运行命令。用户确认的流水故障恢复写入原业务规格，暂无跨模块通用规则补充。可通过上一版 Web 静态资源回滚，客户端缓存和服务端合同无需迁移。归档后提交并推送当前任务分支。
