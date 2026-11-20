## 1. 思考

- [x] 按现有 Chrome CPU 观测和已确认的 50 毫秒预算确定范围。

## 2. 计划

- [x] 记录后台复用、协议、持仓惰性读取和写入恢复。

## 3. 任务拆分

- [x] 先补调度及范围回归，再完成各入口和性能复测。

## 4. 构建

- [x] 完成流水、快照、现金导入和持仓各路径。

## 5. 审查

- [x] 独立复核工程、契约及适用的 UI 范围。
- 纠正 Worker 故障阻断撤权擦除，在写集封装后重新核对原会话代次，区分传输和处理器失败，保留旧导入结果交易 ID 的数字词法及逐文件错误分类。检查作用域、原写集正文、收据、旧式文件数组和权限。
- 投资页面复用现有视觉层级、间距、控件、布局和文案。优化只改变行组合时机，保留已有稳定标识、视口和刷新反馈。相同筛选的刷新期间保留可见持仓，独立计算重试覆盖等值网络响应。
- Web 使用计算 Worker 与浏览器原生大 JSON 传输。Android、iOS 使用原有 HTTP 与本地 Kotlin 业务计算，且共享持仓惰性呈现，需相称构建和单测。后端数据行为不变，双后端矩阵不适用。

## 6. 测试与 QA

- [x] 严格验证、Node、Web 生产构建及实浏览器性能、原生编译和受影响单测。

- 本次比较基线 `0646c32a`，规范提交和验证工作区的父提交为 `f20f3755`，验证于 2026-11-20 北京时间。`openspec validate --all --strict`、`git diff --check`、`./compose/gradlew -p compose :webApp:wasmJsBrowserDistribution --max-workers=1 --offline --console=plain` 及 `compose/qa/web` 的 `npm run test:node` 通过，Node 共 37 项。
- 使用 Chrome 157.0.8103.79 及本地生产预览 `http://127.0.0.1:5191`。Playwright 的流水、月汇总、导入和应用壳回归 24 项通过。最终构建另跑导入端到端回归 9 项通过，覆盖 Worker 恢复、冷启动、原写集重算以及封装期间会话变化阻止 POST。在 390×844 与 1440×844 以 100 个合成持仓验证深滚动、详情和关闭，首行截图与基线一致。持仓截图和控制台记录见 `/tmp/ft-budget-ui-review.697q9p/test-results`，屏幕阅读器、Safari 和 Firefox 未实测。
- 3,000 与 10,000 条流水详情的热 Worker 往返约 9／16 毫秒，复用已加载快照。复测点击详情阶段均未出现大于 50 毫秒的任务。不同输入无相同长任务保证。
- 真实本地后端成功保存 3,000 行合成 CSV 一次。写集在 Worker 封装，浏览器 fetch 的同步调用从基线约 148 毫秒降至约 1.5 毫秒，确认后的轨迹仍观测到约 64 毫秒的页面任务。该次样本及 Worker 往返用于定位和对照，无全流程 P95 达标声明。资料见 `/tmp/ft-budget-bench`。
- Chrome 另验证 300／3,000 行合成服务端 PDF／XLS 解析响应在 Worker 捕获，验证 3,000 行归档首尾分页及越界拒绝。源文件内容和服务端解析耗时未作为该测试结果。
- Chrome 使用 2,000 个合成持仓确认 Worker 响应和可见持仓。Worker 往返约 189 毫秒，页面最大长任务为 80 毫秒。该轨迹及上述流水和确认复测均为每档单次样本，运行于本机原速，后续按规范补测目标低配档位与完整 P95。
- `./compose/gradlew -p compose :shared:testAndroidHostTest --tests '*Cash*' --tests '*LedgerSession*' --tests '*LedgerSnapshot*' --tests '*ImportTask*' --tests '*InvestmentDisplay*' --tests '*InvestmentHoldings*' --tests '*SqlLedgerImportFilesTest*' :shared:iosSimulatorArm64Test :business-domain:jvmTest :androidApp:assembleDebug :shared:compileKotlinIosArm64 :webApp:compileKotlinJs --continue --max-workers=2 --offline --console=plain` 通过。Android 主机测试 741 项通过、2 项在缺少外部合同测试材料时跳过，iOS 模拟器单测 990 项，领域 JVM 单测 908 项。单独的投资网络合同测试需配置 `FT_KOTLIN_INVESTMENT_READ_CONTRACT_DIR`。原生设备 UI 未实测。

## 7. 发布

- [x] 核对功能地图、规格和性能证据，归档并提交推送。

阈值来自本次用户确认，写入项目上下文。其他取舍沿现有工作区及导入合同，无新跨模块产品规则。客户端可回滚到上一版，数据库和缓存格式兼容。
