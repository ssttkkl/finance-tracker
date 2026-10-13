## 1. 思考与需求范围

- [x] 1.1 核对导入现状、路由、四步状态和 `canCommit()` 行为；确认待确认关系仍可提交。
- [x] 1.2 记录现有六个一级入口保持平级、导入从全局导航移入收支账本、离开清理临时会话的范围与非目标。
- [x] 1.3 完成 Web、Native 与 shared presentation 的 Cross-platform Impact Check。
- [x] 1.4 澄清二级页面范围：本次建立页面级通用容器，导入为首个使用者；所有后续页面级二级目的地 MUST 复用。一级页面和既有记账详情抽屉不在本次改造范围。

## 2. 设计与产品确认

- [x] 2.1 按 Finance UI tokens 与 Hallmark 方向完成三类窗口 Pen 设计，并在共享 Pen 组件库建立 `Finance/SecondaryPageContainer`；补充画布外的流水核对与反馈状态参考。
- [x] 2.2 完成并按使用者反馈更新 HTML 独立交互原型，覆盖四步、90% 桌面浮层、唯一配对下拉框、冗余信息移除、错误、焦点、禁用、加载、成功和离开清理；示例数据标注在设计记录中。
- [x] 2.3 记录最新浮层尺寸、内容宽度、Pen 节点到 Compose 的映射、删除的冗余信息、下拉框交互、响应式规则和回滚风险。
- [x] 2.4 启动本地静态服务并使用真实浏览器验证原型；记录 320/375/390/414/599/600/768/899/900/1023/1024/1280/1440 px 布局、90% 模态尺寸、主流程、错误/空状态、焦点/键盘、截图路径和控制台/网络错误。
- [x] 2.6 按 Hallmark `audit.md`、`slop-test.md` 对更新后的 HTML 与 Pen 画板执行人工替代审查；记录技能动作不可用及 findings。复核视口尺寸、窄屏溢出、冗余文字移除、单一下拉框可达性和错误/空/成功状态；未解决 finding 为 0。
- [x] 2.5 使用者确认原型修改可视为同意，Pen 设计稿和 HTML 原型进入实现基线。

## 3. 一致性与任务拆分

- [x] 3.1 对齐 proposal、导入行为 delta、共享容器 delta、设计稿和原型的四步与尺寸合同。
- [x] 3.2 拆分为导航/尺寸纯逻辑回归、共享容器、账本与直接 URL 接入、功能地图更新、独立审查、浏览器与 Native 验证；UI 容器尺寸、背景隔离、Esc/返回、焦点归还和离开清理由真实浏览器流程检查。Open Questions：无。

## 4. 构建

- [x] 4.1 增加导航/尺寸逻辑回归，并在浏览器回归中覆盖容器边界、背景隔离、Escape、焦点、返回/关闭和离开清理。
- [x] 4.2 在共享 presentation 层新增 `SecondaryPageContainer`，实现 Compact 全屏和 Regular/Wide 90% 模态容器、内容/固定页脚插槽与键盘关闭。
- [x] 4.3 从一级导航移除账单导入；保持 `/cash-import` 直接 URL 和账本入口共用容器；四步流程的主操作移至容器页脚，离开时清除临时导入状态。
- [x] 4.4 按实际实现更新 `docs/feature-map.md`，登记共用容器和账单导入的实现证据。

## 5. 审查

- [x] 5.1 复核产品范围、路由和导入清理语义；结果与实现一致，无阻断 finding。
- [x] 5.2 对照 Pen、生产截图和 Compose 实现复核三个尺寸、焦点与无障碍。运行时没有可调用的 Hallmark `audit` 动作；按 `audit.md` / `slop-test.md` 人工替代审查，0 critical、0 major、0 minor。
- [x] 5.3 复核最终 diff、功能地图、术语、实现范围及遗漏回归风险；无超出本 change 的已暂存改动，无阻断 finding。

## 6. 测试与 QA

- [x] 6.1 运行受影响 Compose/Wasm 回归、Web Vitest、Playwright、Compose Web 与 Web 构建；最终 `git diff --check`、目标 change strict 校验、specs strict 校验和 `openspec doctor` 均通过。全仓 `openspec validate --all --strict` 有 27 项通过、1 项失败，失败来自未跟踪的外部 change `hero-ui-compose-finance-ui` 缺少 delta spec；本次未触碰该文件夹。
- [x] 6.2 使用生产预览和真实浏览器走账本入口、四步导入、错误恢复、离开丢弃、Escape/焦点归还及直接 URL。
- [x] 6.3 在 320、375、390、414、768、1440 px 检查溢出；在 390/768/1440 px 截图并对照 Pen。
- [x] 6.4 Native 验证结果与限制见「实施与最终 QA 证据」：Android 包构建并在 AVD 启动；iOS 模拟器应用构建、安装和启动；Native 导入页交互因没有可用的已认证测试会话而未完成。
- [x] 6.5 记录浏览器、URL、视口、步骤、截图、诊断、比较基线与结果；本次未改存储或 API，SQLite/PostgreSQL 契约矩阵不适用。

## 7. 发布准备

- [x] 7.1 记录发布准备、可回滚的代码范围和观察项；本变更不需要数据库迁移。

## 8. 反思

- [x] 8.1 记录可复用容器 API 的复发防护、跨平台行为差异和本次审查结论。

## 实施与最终 QA 证据

- 基线：分支 `refactor/kmp`，实现与验证基于 `HEAD=1e7cfb161cda9c595b84297e8c9513b38ade44d9` 的工作树。执行日期：2026-10-13（Asia/Shanghai）。
- Web：`cd web && npm test` 通过（15 个文件、152 项）；`cd web && npm run build` 通过。
- Compose：`cd compose && ./gradlew :shared:wasmJsBrowserTest :webApp:wasmJsBrowserDistribution --no-configuration-cache` 通过；`ANDROID_HOME=/Users/huangwenlong/Library/Android/sdk ./gradlew :androidApp:assembleDebug --no-configuration-cache` 通过；`./gradlew :shared:iosSimulatorArm64Test :shared:linkDebugFrameworkIosSimulatorArm64 --no-configuration-cache` 通过。Android 编译只有既有 `rememberModalBottomSheetState` 弃用警告；Gradle 有 wasm 配置性能提示。
- Native 启动：Xcode 27.1 对 iPhone Duo 模拟器执行 `xcodebuild -project compose/iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -destination 'platform=iOS Simulator,id=871F9446-8573-48CA-990E-01C1D2E0C2C4' -derivedDataPath /tmp/ft-ios-derived CODE_SIGNING_ALLOWED=NO build` 成功，安装启动到登录页；Android `codex_api36` AVD 安装启动到登录页。两个客户端均未使用真实财务数据。模拟器登录未建立已认证会话，因此 Native 内的 Compact 返回、Regular/Wide 关闭、系统返回和导入状态清理未做端到端操作验证；Android/iOS 共用 Kotlin 逻辑的测试与构建通过。补测条件：在不含真实财务数据的 Native 测试后端准备可登录夹具后，走完上述流程。
- Compose Web 生产预览：Playwright 1.62.1 / Chromium 151.0.7922.34，`http://127.0.0.1:5188/w/workspace-1/`，命令 `FT_COMPOSE_PREVIEW_PORT=5188 npx playwright test -c playwright.compose.config.ts --grep 'F-05'`；4/4 通过。覆盖账本入口与直接 URL、四步选择/扫描/映射/核对/关系/确认、错误恢复、鼠标配对候选选择、筛选计数更新、Esc/关闭/返回、焦点归还、离开后丢弃会话，以及 320/375/390/414/768/1440 px 无横向溢出。Playwright 测试对 console error、failed request、静态 HTTP error 断言均为空。
- 布局截图：[`browser-production-390.png`](prototype/screenshots/browser-production-390.png)、[`browser-production-768.png`](prototype/screenshots/browser-production-768.png)、[`browser-production-1440.png`](prototype/screenshots/browser-production-1440.png)；关系候选打开态：[`browser-production-relation-picker-390.png`](prototype/screenshots/browser-production-relation-picker-390.png)、[`browser-production-relation-picker-1440.png`](prototype/screenshots/browser-production-relation-picker-1440.png)。E2E 对 768 × 1024 与 1440 × 900 的模态宽高分别断言为视口 90%，390 × 844 为全屏；关系候选在 compact 和 wide 可见、可点。人工对照 Pen，尺寸、主要层级和内容顺序一致，未发现超出 2 dp/token 差异的未解释偏差。
- 残余验证风险：Android/iOS 的真实导入交互仍待通过 Native 已认证测试会话复测；本轮后端、导入服务、存储和迁移均未变化，因此 SQLite/PostgreSQL 契约矩阵不适用。回滚范围仅为本 change 中的 Compose 壳/导入页、共享容器、文案、测试、设计和规格文件；无数据回滚步骤。
- OpenSpec 全量门禁：`openspec validate --change cash-import-secondary-surface --type change --strict` 通过；`openspec validate --specs --strict` 通过（25 项）；`openspec doctor` 与 `git diff --check` 通过。`openspec validate --all --strict` 的单项失败是未跟踪 change `openspec/changes/hero-ui-compose-finance-ui/` 没有 delta spec（CLI 提示需添加 delta 或 `skip_specs: true`）；保留该非本任务内容，未改动或纳入提交。
- 字符串自检：移除核对标题下流水总数、配对解释和顶部重复汇总、分散的待选提示；筛选计数仍在筛选控件中。生产流程中的唯一候选选择入口名称为「选择流水」。

## 原型验证证据

- 浏览器：Playwright 1.62.1 + Chromium 151.0.7922.34，独立静态原型 `http://127.0.0.1:4179/`；另外用 Codex In-app Browser 完成原生下拉框鼠标/键盘选择复核。比较基线/当前 HEAD 均为 `1e7cfb161cda9c595b84297e8c9513b38ade44d9`。执行日期：2026-10-13。QA 命令：`node /tmp/ft-cash-import-prototype-qa.cjs`、`node /tmp/ft-cash-import-supplement-qa.cjs`。
- 尺寸：320、375、390、414、599、600、768、899、900、1023、1024、1280、1440 px；另测 1280 × 600。各尺寸 `document.documentElement.scrollWidth` 均不大于视口宽度。Compact（小于 600 px）全屏；其余宽度的模态框宽、高均约占视口 90%。测量例：600 × 900 → 540 × 810；768 × 1024 → 691 × 922；1440 × 900 → 1296 × 810；1280 × 600 → 1152 × 540。文件、账户、核对、结果内容最大宽度 720 px；关系列表使用完整容器宽度。关系下拉框、筛选和页脚操作触控目标满足 44 px 要求。
- 状态与流程：空文件时「下一步」禁用；虚构 CSV 文件进入加载状态和账户映射；账户错误显示字段提示并聚焦 `account-two`；选账户进入核对与配对；配对下拉框始终可见，选择候选后待确认计数为 0、已匹配计数为 3，空筛选显示「没有待确认的流水」；确认导入进入成功态并返回账本。核对标题下不再显示流水总数；配对页面不显示重复说明和顶部统计。桌面 `Tab`/`Shift+Tab` 在模态框内循环，Escape 关闭并归还焦点；Compact 全屏返回按钮可回到账本并归还焦点。Playwright 通过 `selectOption` 验证候选选择；Codex In-app Browser 中点击下拉框并按方向键/Enter 也成功更新选择与计数。Console error/warn、pageerror、requestfailed 均为 0。
- 截图：Pen `prototype/screenshots/pen-{390,768,1440}.png`、`pen-review-state.png`、`pen-feedback-states.png`；浏览器 `prototype/screenshots/browser-{390,768,1440}.jpg`、`browser-1280x600.jpg`、`browser-review.jpg`、`browser-relation-picker.jpg` 和 `browser-relation-empty.jpg`。390 × 844 为全屏文件空态；768 × 1024 为账户映射错误/禁用/焦点；1440 × 900 为扩大后的配对列表和唯一下拉框；1280 × 600 验证窄高度下关系列表在内容区纵向滚动。
- 网络：原型只引用内嵌 HTML/CSS/JavaScript，无外部脚本、样式、图片或 API 调用；Chromium 请求失败数为 0。
- 人工 Hallmark 替代审查：当前运行时没有 Hallmark 专用 audit 技能动作，因此按 `.agents/skills/hallmark/references/verbs/audit.md` 与 `slop-test.md` 审查更新后的 `prototype/index.html`、Pen 代表画板、Finance UI token 和生产 UI 截图。检查重点为 90% 模态的视口留白、窄高视口的滚动、关系筛选行与唯一下拉框、删除的冗余文字、键盘/焦点、文案必要性与示意数据诚实性；未解决 finding：0 critical、0 major、0 minor。此记录不是 Hallmark 工具执行证明。
- PostgreSQL/SQLite 不适用于当前阶段；尚无生产代码或存储改动。实现后若存储仍未变化，在验证记录中保留不适用理由。
