## 1. 思考

- [x] 完成范围澄清，核对认证、启动、跨源隔离、诊断及部署事实源。

## 2. 计划

- [x] 记录请求关联、安全诊断边界、无第三方遥测和回滚设计。

## 3. 任务拆分与一致性

- [x] 对齐 HTTP 合同、Web 本地诊断、隐私回归与生产预览验收。

## 4. 构建

- [x] 后端和 Web 回归先失败，在最新基线实现后转绿。

初始 `ca9490c2` 基线上，HTTP 集成测试及 3 项 Web 诊断单测复现缺口。用户要求更新基线后，执行 `git fetch origin main`，从 `e2a16c7f` 创建 `feat/p0-deployment-diagnostics` 的独立 worktree，并重新验证。

## 5. 审查

- [x] 独立复核范围、工程、安全及 HTTP 合同，并检查迁移到最新基线后的差异。

- 复核覆盖 Ktor 异常选择、CORS、日志输入、保密字段、构建版本及 Web 诊断失败隔离。
- 重要发现：已有导入错误事件只记录领域错误编号。已补充 `request_id`，保留原诊断号合同。
- 一般发现：刷新和多个标签页共享存储键。文档说明当前文档范围，Console 可查看当前标签页记录。
- 人工日志检视已转为自动断言，覆盖注入的异常消息、路径、查询参数和请求头哨兵。
- 跨平台影响：Wasm Web 启动和 HTTP 诊断受影响。Android/iOS 收到附加响应头，原生代码、共享业务及应用内视图无改动。无 Hallmark 或原生设备截图适用项。

## 6. 测试与 QA

- [x] 完成 OpenSpec、后端、Web 生产分发及真实浏览器 QA。

验证日期为 2026-11-19（UTC+8），当前 `HEAD` 和比较基线均为 `e2a16c7f5b7e4a75fd37b6edc61638ccc4264f95`。

- `./gradlew :backend:installDist :backend:test --no-configuration-cache` 在 SQLite 与本机 PostgreSQL 18.6 专用库 `finance_diagnostics_test` 上通过 1,506 项，无跳过。最终日志配置调整依赖日志输出到标准错误以隔离 PDF Worker 的标准输出协议，再按相同构建命令重跑 `DeploymentDiagnosticsTest`、`PdfProcessTest` 和 `IcbcPdfDocumentGoldenTest`，共 16 项通过。
- `FT_API_ORIGIN=http://127.0.0.1:18240 node compose/qa/web/scripts/start-compose-demo.mjs --build-only` 完成 Wasm 生产分发及资源核对。`npm run test:node` 通过 38 项。
- 通过 Playwright 在 Chrome 157.0.8103.79 对 `http://127.0.0.1:5187`（Web）和 `http://127.0.0.1:18240`（本地 API）测试。390 × 844 与 1440 × 900 均覆盖合成账户错误密码提交（401）、修正密码提交（200）、会话及账本快照加载。验证两个登录响应 ID 与浏览器诊断一致，后端日志记录跨源预检及关联请求。无意外控制台错误、请求失败或静态资源错误。
- QA 证据目录 `/tmp/ft-p0-qa.eamang`，含 `e2e.log`、`backend.log`，以及 `results/` 内各视口的 `login-rejected.png`、`ledger.png`。浏览器启动能力缺失与禁用存储由 Node 浏览器及诊断单测覆盖。
- `git diff --check`、`openspec validate --all --strict` 和 `openspec doctor` 通过。验证使用本地生产包与合成数据。线上 Render 日志及部署后的头部由实际发布后确认。

## 7. 发布

- [x] 更新 Compose 运维说明及共享功能地图。回滚 web/API 到此前的构建即可撤回诊断。

## 8. 反思

- [x] 用户选择让本地生产构建覆盖主流程，推迟复制入口。该取舍适用于本次 P0，无需新增通用工程规则。
