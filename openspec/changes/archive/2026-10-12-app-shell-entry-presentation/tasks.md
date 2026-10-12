# Tasks

## 1. 思考

- [x] 阅读 `openspec/project-context.md`、当前 workspace-entry / cross-platform-presentation 主规格、登录/工作区/邀请实现、页面骨架及 `docs/ui-design-rules.md`。
- [x] 明确目标、非目标、验收、边界与风险；用户确认三类访问入口共用 `AuthResponsiveShell` 和居中表单卡片，所有已登录页面标题统一位于 `TopAppBar`。
- [x] 完成 Hallmark 设计预检记录；保留 FinanceTheme 品牌蓝和现有表单语言，不添加装饰插画、虚构指标或重复说明。

## 2. 计划与设计

- [x] 在 `proposal.md` 记录目标、范围、非目标和验收标准。
- [x] 在 workspace-entry 与 cross-platform-presentation delta 规格记录入口布局及主页面标题合同。
- [x] 在 `design.md` 记录信息架构、删除的冗余信息、状态、风险和 Pen 到 Compose 的映射。
- [x] 使用 pen.dev 官方 CLI 创建 `design/app-shell-entry.pen`：入口页 COMPACT/REGULAR/WIDE、邀请入口、工作区状态和主 Scaffold COMPACT/REGULAR/WIDE。
- [x] 创建可独立打开的 `prototype/index.html`，覆盖首次创建、已有工作区、未登录邀请、已登录路由和可切换状态。
- [x] 用户确认原型方向后进入实现；工作区入口卡片标题和主要操作按用户决定定为「新工作区」「创建工作区」。
- [x] 原型 Chromium QA：`file://.../prototype/index.html`，320/375/390/414/768/1440 px 均无横向溢出；卡片宽度依次 288/343/358/382/440/440 px。无控制台错误；截图见 `screenshots/prototype-final/access-{390,768,1440}.png`。

## 3. 任务拆分与一致性

- [x] 覆盖认证/工作区/未登录邀请入口、已有工作区和首次创建、已登录邀请、六条主要路由标题及响应式导航。
- [x] 确认 `TopAppBar` 是已登录路由标题唯一位置；过滤标题、区块标题和业务详情标题保留。
- [x] Cross-platform Impact Check：

  | 层 / 平台 | 受影响 | 理由与证据 |
  |---|---|---|
  | Compose Web | 是 | 共享 `commonMain` 页面壳；生产预览 Playwright 覆盖认证、工作区、邀请、导航和标题。 |
  | Native Android | 是 | 同一 Compose UI；设备测试覆盖 phone、768dp regular 和 1440dp wide。 |
  | Native iOS | 是 | 同一 Compose UI；iOS framework 和宿主 app 编译，iPhone 17 Pro 模拟器启动并保存认证错误态截图。 |
  | 共享 presentation | 是 | 入口壳、路由外框、标题、语义 ID 与页面标题区域同步调整。 |
  | Legacy React Web / Expo | 否 | 明确非目标；不改变旧端路由或 presentation。 |

- [x] 分类为 A 类重大 UI / 跨模块 presentation 重构；不涉及财务计算、持久化、API、权限或数据库语义。

## 4. 构建与实现

- [x] 新增 `AuthResponsiveShell` 与共享 `AuthEntryCard`；登录、工作区访问和未登录邀请复用响应式入口背景、卡片宽度及居中结构。
- [x] 保留品牌、邮箱、退出登录、工作区列表/选择、首次创建、邀请预览/接受及既有导航和错误恢复语义；首次创建卡片只呈现一个「新工作区」标题、名称字段和创建主操作。
- [x] 登录/注册继续使用已确认表单结构；注册返回按钮在卡片内保持可见触控区域。
- [x] 已登录路由标题统一移入 `TopAppBar`，正文重复路由标题隐藏；保留账本筛选/操作、持仓刷新等页面操作。
- [x] 为入口卡片、顶栏标题和正文标题添加稳定语义 ID，并更新 Android device tests、Web Playwright 覆盖。
- [x] 更新 `docs/feature-map.md`、`design/README.md`、Hallmark preflight 与 OpenSpec artifacts。

## 5. 审查

- [x] 产品/范围复核：三类入口任务和首次创建/已有工作区分支一致；未改业务操作与登录、退出、邀请确认语义。
- [x] 工程复核：共享外框承担响应式入口和顶栏职责；路由内容与错误/忙碌状态保留；无持久化迁移，回滚边界为本 change 的代码、测试和文档文件。
- [x] 规格一致性复核：已确认原型包含 COMPACT 居中卡片，与旧认证主规格中的 compact 无卡片规则冲突；按用户已确认设计，在 delta 中完整更新认证响应式外壳和登录/注册布局要求，并同步共享主规格。
- [x] 范围化 diff 复核：检查全部 Compose 页面、语义 ID、设备测试、Web E2E、OpenSpec 与功能地图；响应式测试中一次视口切换后的旧坐标问题已改为等待布局稳定后重新定位，注册返回按钮布局对齐卡片，复跑通过。
- [x] UI 目视复核生产 Compose Web 的登录、工作区和账本截图，视口 390/768/1440；共享设计复核涵盖 320/375/390/414/768/1440。未发现 critical/major/minor 视觉问题，未形成待处理 finding。
- [x] Hallmark `audit` 调用能力在本次运行时不可用；按其 audit rubric 完成人工范围与视觉复核，结果和限制已记录。未声称执行了正式 Hallmark audit。

## 6. 测试与 QA

- [x] 原型 QA：Playwright Chromium 实际打开独立 `prototype/index.html`；首次创建与主界面检查 320/375/390/414/768/1440，记录卡片宽度且无溢出。原型无控制台错误或远程请求。
- [x] Compose Web 构建：`./gradlew :shared:wasmJsBrowserDistribution --no-configuration-cache` — 成功。
- [x] 生产预览浏览器 QA（Chrome / Playwright，`http://127.0.0.1:5187/`）：
  `FT_COMPOSE_PREVIEW_PORT=5187 npm run test:e2e:compose -- --grep 'Compose 登录|Compose 认证|Compose 注册|Compose 工作区名称|Compose 访问页|viewer 登录|F-01 登录成功|F-02 可创建|响应式导航|工作区主导航|未登录邀请|F-06 可预览' --grep-invert '本地 Compose 注册请求'` — **18 passed**。
  覆盖认证登录/注册校验、错误、忙碌、焦点/键盘、首次创建成功和错误、工作区成员选择/失败、未登录和已登录邀请、路由标题及正文无重复标题、路由历史和响应式导航。各断言视图覆盖 320/375/390/414/768/1440；工作区、邀请和已登录页面重点覆盖 390/768/1440。通过测试的浏览器控制台、页面错误和静态资源检查无异常。
- [x] 生产截图并目视复核：`screenshots/compose-web/login-{390,768,1440}.png`、`workspace-{390,768,1440}.png`、`app-{390,768,1440}.png`。390px 账本截图等待数据和顶栏标题稳定后重拍。
- [x] Android 完整设备回归：`ANDROID_HOME=/Users/huangwenlong/Library/Android/sdk ./gradlew :shared:connectedAndroidDeviceTest` — API 36 `codex_api36`，126/126 通过，无失败/跳过；默认设备逻辑宽度约 411dp。
- [x] Android 入口布局：`WorkspaceAccessScreenDeviceTest` 在逻辑 768dp regular 与 1440dp wide 各运行 7/7 通过；测试结束后恢复 emulator 原始尺寸和密度。
- [x] iOS shared framework：`./gradlew :shared:compileKotlinIosSimulatorArm64` — 成功。
- [x] iOS 宿主：Xcode 26.1.1 在 iPhone 17 Pro 模拟器构建并启动 `com.finance.tracker.FinanceTracker` 成功；认证页出现可重试的服务连接错误，因为本机 FastAPI `127.0.0.1:5174` 未运行。截图 `screenshots/compose-ios/iphone-17-pro.png` 记录此环境错误态；不得将其计为 iOS 后端流程通过。
- [x] OpenSpec：`openspec validate app-shell-entry-presentation --type change --strict --no-interactive` — change valid；delta/main 对比确认 2 项新增 presentation requirement、2 项认证 requirement 更新及 1 项 workspace requirement 更新已同步。归档前 `openspec validate --all --strict` — 26 passed，0 failed；归档后同命令 — 25 passed，0 failed，CLI 不再把已归档 change 列作 active item。
- [x] 数据库 SQLite/PostgreSQL 矩阵不适用：改动仅为 presentation、语义标识和测试，无存储行为变化。
- [x] 正式审查阶段 `git diff --check` 与最终 OpenSpec 校验在归档前重跑并记录于下方交付证据。

## 7. 发布准备

- [x] 发布边界：本地分支 `refactor/kmp`，基线 `05712d7332770e8f19004833635447bb779b73c7`；不涉及 schema/API 迁移。可通过回滚本 change 的提交恢复旧 presentation。
- [x] 用户未为当前 change 授权提交或推送；只归档仓库内 OpenSpec 记录，不提交、推送、部署或写入第三方系统。

## 8. 反思

- [x] 可复用决策：访问入口共用响应式认证壳，已登录路由把唯一页面标题放在 Material 3 `TopAppBar`；稳定语义 ID 与布局合同同时覆盖 Web 和 Native。
- [x] 验证期间发现的测试脆弱点（视口切换后复用旧坐标）已改成等待布局稳定并重新定位；修正后针对性浏览器套件通过。
- [x] 残余风险：本次没有本机后端，iOS 宿主仅验证启动和认证错误态；完整 Native iOS 服务端成功流程需在 `127.0.0.1:5174` 可访问时补跑。功能主流程已由生产 Compose Web 和 Android device tests 覆盖。

## 当前检查点

- 分支：`refactor/kmp`；变更前基线：`05712d7332770e8f19004833635447bb779b73c7`。
- 实施与适用验证完成，delta 已同步，change 已归档至 `openspec/changes/archive/2026-10-12-app-shell-entry-presentation/`；归档后全量校验通过。
- 工作树包含本 change 的代码、测试、文档、设计稿和截图；没有提交或推送。
