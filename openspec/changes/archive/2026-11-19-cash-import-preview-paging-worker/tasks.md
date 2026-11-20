## 1. 思考
- [x] 核对页面、计划、分页、时区、确认与恢复调用。按 B 类按需读取调整，风险集中于媒介与会话边界，财务规则不变。

## 2. 计划
- [x] 明确首屏与按需读取合同、旧计划失效及提交安全。

## 3. 任务拆分
- [x] 拆分消息、生命周期、分页、确认验证及大账单测量。

## 4. 构建
- [x] 旧版缺少开放分页预览的 Worker 入口，离线测试先失败。实现首屏、共享内存分区读取、后续页、详情和明确确认路径。保留已冻结写入的幂等流程。

## 5. 审查
- [x] 独立复核发现 Blob 可能落盘，已切换 SharedArrayBuffer。Firefox/Safari 未覆盖。保留原计划范围、索引、游标、筛选、时区和精度，跨线程读后复核会话。Web 首屏和后续页走按需访问，Android／iOS／JS 复用原读取器。原生失败前分配计划编号的回归已修复。

## 6. 测试与 QA
- [x] 2026-11-19 在基线 `5a25a8c5a037ddd83c43ef17dddd6c8630b0440e` 上构建生产包。执行 `./gradlew :webApp:wasmJsProductionExecutableCompileSync --rerun :webApp:wasmJsBrowserProductionWebpack --rerun :webApp:wasmJsBrowserDistribution --offline` 通过。同步本地增量构建遗留的 Wasm 加载脚本后，核对分发包以普通 Uint8Array 副本解码共享内存。
- [x] Chrome 157.0.8103.79、1440 × 1100、`http://127.0.0.1:5191`，运行 `compose-local-import.e2e.ts` 的 4 个用例全部通过。真实 Worker 断网终止后能从缓存重建并复读同一预览。首屏、后续页、详情、精确金额和序号均有断言。应用冒烟覆盖重试、冻结并提交原写集、冲突后重新核对、保存后恢复结果与离线冷启动。控制台和网络按用例断言。证据见 `/tmp/ft-worker-playwright.mGdBFL/final-traces`。
- [x] 使用同一 3,000 行的合成 CSV／XLSX 在 Chrome 跑实际应用页面，显示完整核对总数。首次回包正文从约 2,869 万字符降至约 59 万字符，回包附近最长主线程任务由 415／373 毫秒降至 129／117 毫秒，回包至稳定页面从 1.63／1.57 秒降至 1.08／1.02 秒。Worker 计算耗时 13.80／13.49 秒，内存分区约 31.7 MB。另以直接浏览器路径验证 3,000 行首屏与第二页各 50 条、离线重建、金额与序号精度。性能 trace 与指标见 `/tmp/ft-import-profile.dWlpXo/paged-worker`，直接读取记录见同目录上一级的 `paged-direct.log`。
- [x] Android 267 项、iOS 模拟器 990 项、领域 JVM 908 项通过。Android 应用、iOS Arm64 共享模块和 JS 编译通过，命令及共同环境见同期文件后台处理变更。没有存储行为变化。Firefox、Safari 及原生设备交互未运行。

## 7. 发布
- [x] 已同步 `import/local-import-pipeline`，并核对 Compose 发布说明、导入及总功能地图、词表和相关命令。保持用户已确认的任务及选择恢复行为，规则仅适用于该平台导入。回滚可恢复上一版 Web 静态产物，已冻结写集沿原流程。发布时需继续提供缓存原有的跨源隔离响应头；当前交付范围为任务分支和本地预览。
