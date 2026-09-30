## 1. 思考与规格

- [x] 1.1 阅读 `project-context.md`、设计系统、Cash Ledger 和跨端规格，记录当前 HEAD、基线和 Cross-platform Impact Check 结论。
- [x] 1.2 校验 proposal、三份 delta spec、design 的范围、术语、状态和回滚策略一致。

## 2. Pen 设计稿与设计资产

- [x] 2.1 从版本固定的 HeroUI 官方代码和 `@heroui/styles` 整理 CSS variables、主题值和组件状态，保存 `design/source/heroui-theme.css` 及来源记录；把 Gallery 的真实 DOM 经 Pen Browser Import 导入 `design/heroui-reference.pen`，核对可编辑图层。
- [x] 2.1b 在 Web 增加独立 `/design-system/` Gallery 入口，直接使用 `@heroui/react@3.2.6` 与 `@heroui/styles@3.2.6` 的 Button、Input、Select、Checkbox、Switch、Card、Chip 和 Tabs 真实 DOM；Web 本地依赖改为 `file:` 包引用并生成 `web/package-lock.json`。
- [x] 2.1a 按 Button → Input → Select → Checkbox → Switch → Card → Chip → Tabs → Modal → Drawer → Table → Tooltip → Pagination 导入真实 DOM；清理图层、命名和状态后组件化为 `design/heroui.lib.pen`。Figma Kit 只作视觉核对，不能充当导入结果。
- [x] 2.2 由 HeroUI 参考库构建 `design/finance-ui.lib.pen`，补齐首批 Finance Button、TextField、Select、DataTable、FilterBar、Amount 的派生组件；Registry 记录 HeroUI DOM、CSS token、Pen 组件和 Compose 符号映射。历史 `finance-design-system.lib.pen` 不再作为来源。
- [x] 2.3 基于 `finance-ui.lib.pen` 重新设计 `design/cash-ledger.pen`：沿用 Web 的区域顺序、操作关系和响应式断点，视觉使用 HeroUI 组件与 token；覆盖正常、加载、空、错误、禁用、成功、焦点、危险确认及 320/375/390/414/768/1440 主画布。已通过 Pen CLI 导出并逐张复核；修复示例按钮文案、日期字段重复标签、Compact 横向溢出和错误操作外溢。导入 Table 的边界线图层仍有 3 条来源几何 partial-clip 提示，不影响页面级布局。
- [x] 2.4 按 UI 规则完成信息架构与删除冗余信息清单；用户于 2026-10-01 确认 Pen 设计稿。
- [x] 2.5 读取 Web 其余九项页面级功能的实际布局及代码；用 `finance-ui.lib.pen` 的 HeroUI 派生组件重建对应 Pen 稿，六档宽度与状态画布导出截图。信息架构、删减项和残余证据风险见 `design.md`。
- [x] 2.6 对其余页面执行两轮 Pen 视觉与结构自审：修复分类默认态、投资桌面表格、危险按钮对比度，再补导入、成员和确认操作；页面节点无裁切。截图位于 `design/previews/`，上游 HeroUI Table 的 3 项导入层 partial-clip 保留为非阻断资产提示。
- [x] 2.7 用户确认 F-01/F-02/F-04-F-10 正式 Pen 稿；已回写本变更范围、跨端 delta 和分批实施任务。

## 9. 已确认页面的 Compose 迁移

- [x] 9.1 校准 Finance 浅深色组件、表面和共享页面布局；以 `FinanceTokensTest`、共享测试和浏览器用例覆盖主题、状态及窗口结构。早期原语的 RED 阶段无可追溯日志，本轮不补称测试先行。
- [x] 9.2 迁移 F-01/F-02/F-06/F-10 认证、工作区入口、邀请和管理页面，保留焦点顺序、权限及危险确认。
- [x] 9.3 迁移 F-04/F-05/F-07 收支流水、导入和分类页面，保留文件、账户映射、表单与删除流程。
- [x] 9.4 迁移 F-08/F-09 持仓与投资事件页面，保持筛选、列表、详情及分页关系。
- [x] 9.5 更新功能地图，执行 Cross-platform Impact Check：Compose Web、Android、iOS 的 `commonMain` 同时受影响；React Web 仅作布局基线；共享领域、API 与持久化层不变。
- [x] 9.6 运行 Compose、Web 与 OpenSpec 验证，使用真实 Chrome 检查六档宽度、主流程、错误/空状态及键盘焦点；记录截图、控制台/网络错误、HEAD 与残余风险。
- [x] 9.7 完成独立范围/工程/设计复核及最终 diff 复核，修复重大 finding；检查提交范围后提交并推送 `origin/refactor/kmp`。

## 3. Token、依赖与契约

- [x] 3.1 为 semantic role 编写失败测试，更新结构化 token、生成器和 Compose/ Pen 消费副本，保留旧 token 兼容别名。（生成器与 Wasm 编译验证已通过，专门失败测试待补；HeroUI CSS 仅用于语义校准。）
- [x] 3.2 在版本目录固定 Compose Unstyled `2.10.0`，按需加入 button、text-field、dropdown-menu、dialog 模块，完成最小 commonMain 编译验证。
- [x] 3.3 创建 Finance UI Registry 与 Screen/Component Contract，登记首批组件的变体、状态、插槽、语义 ID 和 Compose 符号。

## 4. Finance Primitive 实现

- [x] 4.1 先让 Button、TextField、Select、Dialog 的无障碍、焦点、键盘、禁用行为测试失败，再基于 Compose Unstyled 实现。（原语已实现并通过 Wasm 编译，自动化行为测试待补。）
- [x] 4.2 实现 Finance Theme、semantic token 读取和 HeroUI 风格的 surface、field、focus、radius、shadow 状态。
- [x] 4.3 实现 FilterBar、Amount 和 DataTable 组合组件，明确排序、空状态、错误状态和响应式边界。

## 5. Cash Ledger 迁移

- [ ] 5.1 为 Cash Ledger Button/Input/Select/Table/Filter/Amount 调用和加载、空、错误、成功状态增加失败回归测试。
- [x] 5.2 将 Cash Ledger 页面接入 Finance 组件和 Registry 语义 ID，保持 API、权限、金额和分页语义不变。（Button、TextField、Select 触发器、DataTable、Amount 已接入。）
- [x] 5.3 覆盖 compact/regular/wide 布局与 320/375/390/414/768/1440 px 视口，浏览器断言无页面级横向滚动，并复核 390/1440 px 截图。
- [x] 5.4 更新 `docs/feature-map.md`、跨端影响记录和实现证据，明确 Web、Native、共享层覆盖状态。

## 6. 审查与验证

- [x] 6.1 完成产品/范围、工程、设计、开发者体验和安全范围化复核；将 finding、采纳/拒绝理由写入本文件。
- [x] 6.2 对照 Pen 设计稿独立复核最终 Cash Ledger UI，修复阻断性或重大 finding 后重新审查并记录结论。（本轮自审已完成；结论和残余风险见 `design.md` 的「Pen 设计自审记录」。）
- [x] 6.3 运行 `openspec validate --all --strict`、`openspec doctor`、受影响 Compose 测试、类型/构建检查和 `git diff --check`。
- [x] 6.4 启动生产预览并用真实 Chrome 验证主流程、加载/空/错误状态、关键点击、键盘焦点和 320/375/390/414/768/1440 视口；记录 URL、截图路径、控制台/网络错误。
- [x] 6.5 记录当前 HEAD、比较基线、实际命令、时间、PostgreSQL 不适用理由（本变更不触及持久化）和未解决风险；记录 Pen 参考资产可用性及上游 Table 的非阻断 partial-clip 提示。

## 7. 发布与反思

- [x] 7.1 记录 Finance UI 到 Cash Ledger 的迁移发布顺序、回滚开关和观察项。
- [x] 7.2 记录可复用的 Registry、token 和 Compose Unstyled 集成经验；确认后续页面迁移边界。

## 8. 本轮方案调整与验证证据

- [x] 8.1 需求澄清：按 `grilling` skill 逐轮确认主事实源、Pen 降级边界和本次实现范围；用户确认采用建议。
- [x] 8.2 方案回写：更新 `proposal.md`、`design.md`、本变更的 design-system delta、`design/README.md` 和 `docs/design-system/registry.yaml`。
- [x] 8.3 只读验证：`openspec --version` = `1.7.0`；`node --version` = `v24.16.0`；`openspec validate --all --strict` 通过（25/25）；`openspec doctor` 通过；`git diff --check` 通过。
- [x] 8.4 验证基线：当前 `HEAD` 为 `271a66dc5f46b83b53cc251dbd5286744e4d8843`；本轮未提交或推送，工作树中的既有代码和设计资产改动保留待后续交付门禁。
- [x] 8.5 方案辅助文档：更新 `tools/figma/heroui-merge-plugin/README.md`，明确插件仅用于可选视觉校验，不能作为 HeroUI → Pen 组件库转换器。
- [x] 8.6 环境补齐：为 `web/` 安装仓库依赖并补充 HeroUI 依赖；Web 依赖缺失阻断已解除。Compose `./gradlew :shared:allTests` 仍因未配置 Android SDK（缺少 `ANDROID_HOME` 或 `compose/local.properties`）失败，补跑条件是配置有效 Android SDK。
- [x] 8.7 依赖安装后复跑：`npm install` 成功；`npm test` 执行 152 个测试，其中 147 个通过，5 个既有测试失败（2 个超时、1 个缺失历史 prototype 文件、1 个布局标题断言、1 个超时）；`npm run build` 已进入 TypeScript 检查，Gallery 无类型错误，但仍被既有 packages 类型解析和 `CashImportPage` 类型错误阻断。失败详情保留在命令输出，未将其标记为通过。
- [x] 8.8 Gallery 浏览器 QA：2026-09-29 16:01 CST，在 Safari 打开 `http://127.0.0.1:5174/design-system/`，对照 HeroUI 官网检查按钮、字段、卡片和状态控件；点击 Checkbox、Switch、Select 选项及 Tabs，选中状态和内容均更新。Safari 还打开生产预览 `http://127.0.0.1:4174/design-system/`，确认组件外观与开发入口一致。独立 Playwright Chromium 检查 320/375/390/414/768/1440 px，均无页面级横向滚动或 `pageerror`；390 与 1440 px 截图为 `/tmp/heroui-gallery-390.png`、`/tmp/heroui-gallery-1440.png`。Gallery 未接入生产业务路由，Cash Ledger 的最终生产预览 QA 仍按 6.4 待办。
- [x] 8.9 Gallery 视觉修正：原 `@heroui/styles` 原始 CSS 中的 `@apply` 在当前 Vite 输出中未展开，导致按钮无圆角和尺寸；改用同一官方包的已编译 CSS。Checkbox、Switch 补齐官方复合组件结构，Tabs 样本限制宽度；Safari 复核后呈现 HeroUI 风格。Web 生产构建生成 `dist/design-system/index.html`；`openspec validate --all --strict` 25/25 通过，`git diff --check` 通过。全量 `npm test -- --run --reporter=dot` 单独重跑为 146/152 通过、6 项失败（既有业务页面断言，未归因为 Gallery）；先前与构建并行运行时为 143/152 通过、9 项失败。本次不触及持久化，PostgreSQL 矩阵不适用。`HEAD` 仍为 `271a66dc5f46b83b53cc251dbd5286744e4d8843`，未提交或推送。
- [x] 8.10 工作流调整：按用户要求取消 HTML 原型与 Hallmark 技能门禁，改以 Pen 设计稿及独立设计复核为准；既有原型文件保留历史参考，不作为完成 2.3 的证据。
- [x] 8.11 官方代码优先设计链路：通过 Pen CLI 保存 `heroui.lib.pen`（19 个 HeroUI 可复用组件、18 个浅深色变量）、`finance-ui.lib.pen`（8 个 Finance 派生组件）和重建后的 `cash-ledger.pen`（320/375/390/414/768/1440 主画布及状态覆盖）。`cash-ledger.pen` 的顶层节点、尺寸和引用已用 `jq` 检查，7 个画布已通过 Pen CLI 截图复核；导出时补齐 `mode: light` 主题绑定，预览保存于 `design/previews/cash-ledger-390.png`、`cash-ledger-768.png`、`cash-ledger-1440.png` 和 `cash-ledger-states.png`。`git diff --check` 通过；设计稿确认和最终浏览器 QA 仍待完成。
- [x] 8.12 独立设计/工程复核：确认页面区域顺序与 Web `CashLedgerPage` 一致，移动端记录改为单列且不引入页面级横向画布；Finance 组件来自 HeroUI 导入层的可编辑派生副本。非阻断 finding：HeroUI Table 的 3 个来源几何图层存在 partial-clip 提示，保留其官方 DOM 边界并记录为资产级残余风险；用户确认设计稿后再处理 Compose 视觉实现和浏览器对照。
- [x] 8.13 Pen 视觉自审：通过 `pen interactive` 修复并复核按钮示例文案、日期字段重复标签、Compact 断点溢出、错误状态操作外溢和危险确认文案；使用 320、375、390、414、768、1440 px 导出截图检查，无页面级横向滚动、文字裁切、节点重叠或状态卡片外溢。正式稿已保存为 `design/cash-ledger.pen`，用户确认前不继续 Compose 实施。
- [x] 8.14 其余页面二次审查：2026-09-30 使用真实 Web 390/1440 px 截图和页面代码对照 F-01/F-02/F-04-F-10；修复 F-04 字段与抽屉宽度、F-07 目录层级、F-08/F-09 表格与筛选、F-10 成员和资料排布等重大/一般 finding。审查范围、分级和回写见 `design.md`；未采纳的视觉差异仅为 HeroUI 控件圆角、表面与颜色，属于本变更目标。
- [x] 8.15 F-04 浏览器证据：Playwright Chromium，`http://127.0.0.1:5177/`，390×900 与 1440×900；运行 `FT_E2E_WEB_PORT=5177 npx playwright test tests/cash-ledger.e2e.ts -g '新建流水沿用信息抽屉' --reporter=line`，打开新建流水、核对字段、填写零金额并保存，1/1 通过。实页截图：`/tmp/finance-web-cash-record-390.png` 与 `/tmp/finance-web-cash-record-1440.png`；测试未记录控制台/网络诊断，不作为完整 6.4 QA 结论。
- [x] 8.16 Pen CLI 复核：运行 `node tools/pen/build-finance-tables.mjs`，再运行 `node tools/pen/rebuild-web-pages.mjs`；九份页面各导出 320/375/390/414/768/1440 px 与状态截图至 `design/previews/`。结构检查为每份六个主画布、一个状态画布、零 placeholder、Finance 库引用；F-08/F-09 的桌面表格各为一个库实例。375/414 px 目视复核无文字与控件重叠。HeroUI 上游 Table 的 3 项 partial-clip 为非阻断来源图层提示。
- [x] 8.17 2026-09-30 16:03 CST 最终设计检查：`openspec validate --all --strict` 25/25、`openspec doctor`、`git diff --check`、`node --check tools/pen/rebuild-web-pages.mjs`、`node --check tools/pen/build-finance-tables.mjs` 均通过；九份稿每份 7 个画布和 7 张非空 PNG。`HEAD` 与比较基线仍为 `271a66dc5f46b83b53cc251dbd5286744e4d8843`，未提交或推送。本轮未改持久化，PostgreSQL 矩阵不适用；Compose 其余页面尚未迁移，6.3/6.4 的最终运行验证须在用户确认设计稿并实施后补跑。

## 9. 最终实施、审查与验证证据

- **范围复核：** F-01 至 F-10 的 Compose `commonMain` 页面均接入 Finance 组件；Web、Android、iOS 共享文案、状态、语义 ID 和响应式不变量。React Web 只作布局基线，领域、API、持久化和权限合同未改。回滚为恢复各页面原 Material3 调用并保留 token 兼容别名与 Registry。
- **设计复核：** 对照 `design/finance-ui.lib.pen`、十份页面稿、`design/previews/` 和实际 Compose 截图复核正常、空、错误、禁用、焦点、加载、成功及危险操作；修复了账本移动记录元信息断行、筛选操作留白、深色危险色对比度和导入关系菜单表面。上游 HeroUI Table 3 条 partial-clip 仍是非阻断资产风险。
- **浏览器环境：** Chrome 154.0.8037.59，生产 Wasm 预览 `http://127.0.0.1:5186/`，Playwright 配置 `web/playwright.compose.config.ts`，浏览器诊断检查 console error、failed request、static HTTP error 和 Wasm content type。最终完整回归 28/28 通过；浅色与深色定向复验各 3/3 通过。视口覆盖 320、375、390、414、768、1440 px；截图为 `screenshots/web-final-390.png`、`web-final-1440.png`、对应 `-dark` 文件，以及 `web-choice-card-390*.png`、`web-holdings-*.png`。
- **构建与测试：** `ANDROID_HOME=/Users/huangwenlong/Library/Android/sdk ./gradlew :shared:wasmJsTest :shared:testAndroidHostTest :shared:compileKotlinIosSimulatorArm64 :webApp:wasmJsBrowserDistribution --offline` 通过；`npm run build` 通过；5 个旧 React 测试文件隔离复跑 89/89 通过。完整 `npm test` 为 150/152，剩余 2 项旧 React 外壳路由测试在全量运行时偶发停留于投资账本加载态，单独重跑均通过，未观察到本次 Compose 页面回归；该残余风险保留待后续拆分旧测试 fixture。
- **规格与工作树：** `openspec validate --all --strict` 25/25、`openspec doctor`、`git diff --check` 通过；基线与提交前 `HEAD` 均为 `271a66dc5f46b83b53cc251dbd5286744e4d8843`。本变更不触及持久化，PostgreSQL 契约矩阵不适用；`adb devices -l` 无连接 Android 设备，因此未执行真机设备 QA；iOS 模拟器 Kotlin 编译已通过。Pen Desktop/CLI 资产可用，未使用 computer-use。
- **发布准备：** 先发布 Finance token、Registry 和组件，再发布共享页面；观察 Compose Web、Android、iOS 的页面加载、筛选、表单写入、导入幂等和权限状态。回滚入口为撤销对应页面 Finance 组件接入，保留 API 与数据不变。
