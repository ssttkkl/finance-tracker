## 1. 思考
- [x] 确认仅脱敏错误、本地模拟接收端，核对 P0 和最新主分支。
## 2. 计划
- [x] 定义接入开关、数据允许列、运行故障与回滚。
## 3. 任务拆分
- [x] 先验证接收端载荷及无配置行为，再实施客户端与后端。
## 4. 构建

- [x] JVM 与 Web 诊断回归先失败，新增 Java 和 Browser SDK 后转绿。沿用请求 ID 和 P0 本地诊断。
## 5. 审查

- [x] 独立复核 Sentry 集成、跨层请求 ID、监控状态与既有 HTTP 合同。
- 重要发现：Browser SDK 在关闭默认 PII 后仍附加 `infer_ip: auto`。双端显式禁止采集用户信息，Web 真机回环事件验证为 `never`。
- 一般发现：构建指纹漏记 Web DSN，事件标签可能保留经字符串转换匹配的对象。已将 DSN 纳入指纹和回归，并按类型筛选标签。
- 跨平台影响：仅 Wasm Web 启动和错误诊断、Kotlin API 受影响。Android、iOS 及跨平台业务合同及视图无变化，Hallmark 和原生设备截图不适用。
## 6. 测试与 QA

- [x] 验证双端实际 envelope、生产浏览器及 OpenSpec。

执行日期 2026-11-19（UTC+8）。`HEAD` 和主分支比较基线为 `68e905294e64a5587b68c7bbb722a7b97c7b507c`。P0 尚未合入，作为本地前置改动带入新 worktree。

- `./gradlew :backend:installDist :backend:test --no-configuration-cache` 在 SQLite 和 PostgreSQL 18.6 的本机专用库上通过 1,507 项，无跳过。
- `./gradlew :backend:test --tests '*SentryReportingTest' --tests '*DeploymentDiagnosticsTest' --no-configuration-cache` 通过。真正的 Ktor 500 响应与 SDK 发送的 envelope 使用同一请求 ID。接收端断言无原始路径、查询参数、异常消息及用户对象。
- `./gradlew :webApp:wasmJsBrowserDistribution --no-configuration-cache` 在配置本地 DSN 和留空两种情况下均成功。留空产物不包含 SDK，配置后同源提供经 SHA-256 校验的 Browser SDK。`npm run test:node` 通过 40 项。
- Chrome 157.0.8103.79 和 Playwright 生产预览以 `http://127.0.0.1:5187` 为 Web、`http://127.0.0.1:18240` 为 API。390 × 844 与 1440 × 900 均验证了错误密码 401、修正登录、进入账本与跨源请求 ID，未出现意外控制台或网络错误。
- Chrome 将合成运行错误、认证 503 和本地认证错误发往 `http://127.0.0.1:5189` 模拟接收端。三个事件均通过允许列检查，401 不上报，Web envelope 的 IP 推断为 `never`，无 Cookie 和 Referer。模拟接收方失败时，本地诊断仍可用。剥除隔离头的独立 HTTP 代理（端口 5191）验证 `startup / isolation_unavailable` 实际送达。证据含 `envelopes.log`、`e2e.log`、`results/` 内截图及 `backend.log`，目录为 `/tmp/ft-sentry-qa.bdrBlQ`。
- `openspec validate --all --strict`、`openspec doctor` 与 `git diff --check` 通过。

## 7. 发布

- [x] 补齐 Compose 部署说明与共享功能地图。原地核对 Render 跨源头声明。两端撤除各自 DSN 并重启或重新构建即可回滚。线上接收端、项目和部署没有变更。
## 8. 反思

- [x] 用户选择本地接收端、仅脱敏错误，已写入本次能力合同。该选择没有扩展到新通用工程规则。
