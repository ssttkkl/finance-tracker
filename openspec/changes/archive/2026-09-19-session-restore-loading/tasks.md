## 1. 思考与范围

- [x] 1.1 显式完成 `grill-me` → `grilling` 澄清；确认 Web 与 Native 同步、总共 3 次恢复请求、无令牌直接登录、无效令牌立即登录、暂时性失败自动重试。
- [x] 1.2 阅读 `openspec/project-context.md`、`DOMAIN_GLOSSARY.md`、`docs/ui-design-rules.md`、认证入口代码、测试和现有 `workspace-entry` / `cross-platform-presentation` 主规格。
- [x] 1.3 完成 Cross-platform Impact Check：Web 启动路由和加载态受影响；Native 启动恢复和加载态受影响；共享层的恢复重试语义与认证文案受影响；API、数据库、财务计算、令牌格式和持久化格式不受影响。

## 2. 计划与一致性

- [x] 2.1 创建 `proposal.md`、`specs/workspace-entry/spec.md`、`specs/cross-platform-presentation/spec.md` 和 `design.md`，记录范围、非目标、决策、风险及回滚。
- [x] 2.2 复核 UI 规则；本次只增加中性恢复加载状态，不改变页面结构、信息架构或核心交互，因此不创建 A 类 UI 原型；最终 UI 仍需 Hallmark `audit`。
- [x] 2.3 运行 `openspec validate --all --strict`：42 项通过，确认 delta requirement、设计和任务之间一致后进入实现。

## 3. 失败测试与任务拆分

- [x] 3.1 先为共享会话恢复重试函数增加失败测试：认证错误立即停止、暂时性失败最多总共 3 次、第三次成功和三次失败；实现前运行时按预期失败（缺少 `restoreSession`）。
- [x] 3.2 先为 Web 增加失败回归：有令牌恢复期间只显示「加载中...」、无令牌不请求会话、无效令牌进入登录、暂时性失败恢复成功或最终失败；实现前新增 5 项回归按预期失败。
- [x] 3.3 为 Native 增加恢复策略和无令牌启动回归，确认不发送无令牌会话请求并共享认证错误分流；实现前测试因缺少恢复模块失败，其余 Native 回归通过。

## 4. 构建

- [x] 4.1 在 `@finance-tracker/core` 增加平台无关的会话恢复重试策略，并补充共享 presentation 文案；Core、Presentation 测试通过。
- [x] 4.2 在 API 客户端提供统一的认证错误判定；Web 与 Native 使用各自令牌存储检查和共享重试策略；API Client 测试和共享类型检查通过。
- [x] 4.3 修复 Web `AccessApp` 的启动状态：无令牌直接登录，有令牌先展示「加载中...」，按错误类型恢复或进入登录；26 项 `AccessApp` 测试通过。
- [x] 4.4 修复 Native `SessionProvider` 及入口布局：无令牌不请求会话，有令牌按相同规则最多恢复 3 次，并展示统一加载文案；Native 类型检查和 26 项测试通过。

## 5. 审查

- [x] 5.1 完成产品/范围复核：确认本变更只解决刷新时登录表单闪现及跨端恢复语义；主动登录、登出、工作区选择、邀请和服务端 API 合同均未改变。
- [x] 5.2 完成工程与安全复核：令牌存在性先于会话请求；`401`/`authentication_required` 立即停止重试并清理无效令牌；网络或服务端暂时失败严格最多 3 次；Web 与 Native 均有活动状态保护；不向用户或日志暴露内部错误，也未改变令牌格式、服务端或回滚边界。
- [x] 5.3 完成最终 diff 复核及 Hallmark `audit`：复核 `web/src/AccessApp.tsx`、`web/src/styles.css` 的既有加载容器、`mobile/src/components/SessionLoading.tsx` 和相关入口。当前运行时未暴露独立 Hallmark audit action，因此按已读取的 audit 规则完成人工 fallback；无 critical、major、minor finding（`0 · 0 · 0`），无需视觉修复。预览夹具新增 `Authorization` CORS 白名单属于真实 Bearer 请求的测试支持，不改变生产 API。

## 6. 测试与 QA

- [x] 6.1 运行共享包、Web 和 Native 受影响单元测试；运行 Web 类型检查、生产构建及必要的 Native 类型检查：`npm run test:shared` 通过（contracts 1、API client 4、core 7、presentation 6，共 18 项）；`npm run test:web` 通过（15 files / 150 tests）；`npm run test --workspace finance-tracker-mobile` 通过（7 files / 26 tests，Expo plugin test 通过）；`npm run typecheck:shared`、`npm run typecheck --workspace finance-tracker-mobile`、`npm run build:web` 均通过。
- [x] 6.2 使用真实 Chromium 验证有令牌刷新、无令牌启动、无效令牌、暂时性失败重试和最终登录状态：`FT_E2E_WEB_PORT=5199 npm run test:e2e --workspace finance-tracker-web` 通过（43/43）；`FT_PREVIEW_WEB_PORT=5200 FT_PREVIEW_API_PORT=8800 npm run test:preview --workspace finance-tracker-web` 通过（16/16）。覆盖正常登录、键盘操作、恢复错误分支、`1440 px` 和 `390 px`；保存截图 `/tmp/session-restore-loading-1440.png`、`/tmp/session-restore-loading-390.png`、`/tmp/session-restore-1440.png`、`/tmp/session-restore-390.png`。相关断言均无控制台错误或请求失败，生产预览 CORS 夹具已允许 `Authorization`。
- [x] 6.3 已运行 `openspec validate --all --strict`（42 passed / 0 failed）和 `openspec doctor`（Root ok、无引用声明问题）；`git diff --check` 通过。当前 `HEAD` 为 `560c8eb5ff6b2ae85e771066e3bfd3f31e573bb6`（`feat(mobile): allow debug API origin override at login`），比较基线为该 `HEAD` 的未提交工作树；本轮没有提交、推送或其他外部写入，最终变更仍留在工作树。
- [x] 6.4 PostgreSQL 契约矩阵不适用：本变更只涉及 Web/Native 客户端启动状态、共享重试辅助函数、presentation 文案和测试夹具，不修改服务端、数据库、迁移、金额或持久化行为；因此无需补跑 SQLite/PostgreSQL 数据库回归，数据库风险由范围复核排除。

## 7. 发布准备

- [x] 7.1 已记录发布前验证证据：共享/Web/Native 测试、类型检查、构建、43 条开发 E2E、16 条生产预览 E2E 均通过；无数据库迁移、无服务端发布配合和无外部写授权。回滚时恢复 Web `AccessApp` 的启动恢复分支、Native `SessionProvider` 的令牌检查/重试分支及共享 `restoreSession`，不需要触碰已有令牌。

## 8. 反思

- [x] 8.1 防复发结论：启动认证必须显式区分无令牌、恢复中、认证失败和暂时性失败；跨端共享重试语义、共享加载文案，并用 Web 浏览器场景和 Native 恢复单元测试锁定请求次数、错误分流和无令牌不请求会话。
