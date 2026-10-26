## 1. 思考

- [x] 1.1 通过 `grill-me → grilling` 明确仅重写 `/cash-import`，采用账本表格＋侧滑只读详情；按用户后续纠正将文件选择、账户映射和草稿优先核对列为连续任务，复核业务流程、active change、后端合同和跨端边界。
- [x] 1.2 按 A 类复核产品取舍、默认决定、恢复与账户补偿；确认不改后端、DTO、Repository 公共合同、命令状态机、路由、数据库或依赖；第一版允许展示默认筛选与当前页详情读取编排。

## 2. 计划

- [x] 2.1 回写 proposal、design 和 delta：选择并解析文件 → 指定来源账户映射 → 草稿优先的只读核对 → 一次确认 → 实际结果，删除导入内字段／来源／关系决定。
- [x] 2.2 执行 OpenSpec strict 与 doctor；记录 UI 方向、M3 侧板／列表依据、非目标及回滚。2026-10-25：35 项 strict 通过，doctor 正常。

## 3. 任务拆分与一致性

- [x] 3.1 将 requirement 映射到准备分区、账本表格、只读侧板、空默认选择、结果与恢复测试；保留八阶段及跨端影响检查。
- [x] 3.2 修改 Web 回归并观察旧实现失败：15 项中 5 项失败，包含旧编辑操作与三尺寸只读合同；新增断言覆盖详情无输入与保存／采用／关系操作，以及提交仅含账户选择。

## 4. 构建

- [x] 4.1 重写 `CashImportTaskScreen` 编排与固定主动作，将文件选择与账户映射拆为连续任务，入口改为覆盖账本的全屏对话框；保留账户和退出行为。
- [ ] 4.2 重整 `CashImportReviewList` 摘要、默认全部且草稿优先的排序、流水行内自动／手动配对、筛选、分页和处理明细，继续复用账本表头与流水行；先核实全量草稿和配对关系可见所需的读取合同。
- [ ] 4.3 完整重写 `CashImportPreviewPanel` 为只读摘要／问题／差异／完整成员／原文，接入 `CashRecordModalPanel`；确认无引用后删除专属关系编辑器。
- [ ] 4.4 更新结果呈现、三语言文案和离线演示，不修改协议或状态机；最终搜索旧编辑入口与无用导入专属键。

- [x] 4.6 将账户创建入口放入每个来源组下拉菜单；预填名称／币种并沿用旧 Web 类型推断，创建后返回并选中新账户；移除映射区新建／编辑按钮并显示币种缺项提示。真实 Chromium 创建路径已验证请求参数、成功回选和后续预览选择；旧 Web 推断位于 `web/src/pages/CashImportPage.tsx`，名称含“花呗”或“信用卡”时为 `loan`，其余为 `cash`。

- [x] 4.5 按用户确认的 A／A 范围完成仓库外第三版只读主流程原型，保留第二版；恢复默认全部与草稿优先、流水行内配对及依据，验证计划失效、提交冻结与合成收据。此项不代表 4.1–4.4 的 Compose 实现完成。

## 5. 审查

- [ ] 5.1 独立复核产品范围、工程边界、失败／恢复、隐私和最终 diff；阻断项修复后重审并原位记录。
- [ ] 5.2 执行可见文字必要性自检、范围化术语搜索、Hallmark audit 和独立设计复核；对照实际页面与截图清零 critical／major。

## 6. 测试与 QA

- [ ] 6.1 在 `compose/` 执行 `:shared:wasmJsTest`、`:shared:testAndroidHostTest`、`:androidApp:assembleDebug`、`:shared:compileKotlinIosSimulatorArm64` 和 Web 开发构建，记录 HEAD、命令与结果。
- [ ] 6.2 在 `compose/qa/web/` 执行 `npm run test:node`、受影响及完整 Compose Playwright 回归。
- [ ] 6.3 用真实 Chromium 在 390×900、1440×900 走上传、账户映射、草稿优先核对／详情、确认、结果和返回；768×900 复核表格与侧板，记录 console／network 和必要截图。
- [x] 6.4 Native 仅执行相称编译和单元测试，不宣称设备 QA；本轮无存储行为变化，双后端矩阵不适用，性能与安全按展示层风险检查。
- [ ] 6.5 提供 Compose 开发／QA 演示供用户确认；未取得确认前不归档、不宣称发布完成。执行 OpenSpec strict、链接和 `git diff --check`。

## 7. 发布

- [ ] 7.1 按最终实现核对 README、导入流程／合同、功能地图和词表，清理 Compose 导入内编辑的旧宣称，检查链接；记录无需修改项的理由。
- [ ] 7.2 记录交付范围、依赖版本、回退和遗留风险；提交、推送、PR、合并和部署均需单独明确授权。

## 8. 反思

- [ ] 8.1 记录只读导入的防复发回归与复用公共账本组件的边界；按最终实现同步 delta，不在演示确认前归档。

### 第一版演示进度

用户已要求按第三版原型进入 Compose 实现，完成第一版后先确认；仅修复阻止构建或演示主流程的问题，其他细节记录待办。先验证默认全部的失败回归，再拆分文件／映射、核对摘要和行内关系、只读详情、实际收据；第一版完成后展示 Web 演示，最终验收不提前勾选。读取合同缺口和本轮边界见 design 的“第一版 Compose 演示边界”。

### 当前审查与验证

2026-10-27 00:12 CST，当前交付为 Compose 第一版、全屏对话框、统一步骤按钮、步骤业务说明及与正文对齐的布局，等待演示确认。文件和账户映射内容列最大宽度为 720 dp，进度轨道向两侧各延伸 24 dp，最大宽度为 768 dp。HEAD 为 `fc546128f234ceb00ce8e7b390372b3d0803c29c`，比较基线 `main` 为 `3b3bbe25b4dbd6007be024fdb7b35473a0ea229f`。现有工作树改动保留，未提交、推送、归档或部署。

| 当前检查 | 实际命令／范围 | 结果与限制 |
| --- | --- | --- |
| 测试先行 | 默认全部的 `ImportTaskStateTest` 修复前 20 项中 2 项失败；全屏模态浏览器断言在旧构建失败 | 失败原因分别为仍默认 pending、导入仍为主内容页；修复后转绿 |
| 共享状态与收据 | 在 `compose/` 执行 `:shared:testAndroidHostTest --tests com.finance.tracker.ImportTaskStateTest --tests com.finance.tracker.ImportTaskViewModelTest --tests com.finance.tracker.ImportTaskResultTest` | 第一版 61 项通过；本轮容器调整未修改这些状态和协议 |
| 本轮共享编译 | 在 `compose/` 执行 `FT_COMPOSE_DEMO_MODE=true FT_API_ORIGIN='' ./gradlew :shared:compileKotlinWasmJs :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64 --no-configuration-cache --console=plain` | 步骤说明和布局变更后，Wasm、Android Main、iOS Simulator Arm64 编译通过。只有既有弃用／Wasm 互操作警告，无本次代码警告 |
| Web 最新演示 | 在 `compose/` 执行 `FT_COMPOSE_DEMO_MODE=true FT_API_ORIGIN='' ./gradlew clean :webApp:wasmJsBrowserDevelopmentRun -Pkotlin.js.webpack.port=5202 --no-configuration-cache --console=plain` | 清理 QA 生产构建与开发演示共用目录产生的不匹配产物后，顺序构建通过；`http://localhost:8080/` 当前返回 200 并运行于演示模式，可从账本打开“导入账单”全屏对话框 |
| Web 上传状态与宽度 | 真实 Chromium，390×900、700×900、720×900、721×900、768×900、769×900、1440×900；从账本点操作 FAB →“导入账单”→“添加文件” | 截图 `/tmp/finance-compose-progress-gutter-{390,700,720,721,768,769,1440}.png`。文件行内容列最大 720 dp；轨道向两侧各延伸 24 dp，最大宽度 768 dp。在所测 390、700、720、721、768 dp 逐像素确认轨道铺满窗口；769 dp 约 0.5 dp 居中留白；1440 dp 轨道 x=336–1104、宽 768 dp，内容列 x=360–1080。页面异常 0、失败请求 0。控制台只有 Noto Sans SC 资源 404 和 Wasm memory 弃用提示 |
| 三步交互回归 | 在 `compose/qa/web/` 执行 `FT_COMPOSE_PREVIEW_PORT=5187 npm run test:e2e -- --grep "cash import reviews all items"` | 1 项通过；覆盖三步说明、两处旧标题不显示、草稿优先核对与实际结果收据；诊断无控制台错误和失败请求。此项在最后一次正文列对齐调整前运行 |
| 本轮视觉与对齐 | 真实 Chromium 使用 390×900、1440×900，从账本打开导入、添加合成账单、进入账户映射与核对；截图 `/tmp/finance-compose-step-aligned-{files,accounts,review}-{390,1440}.png` | 六组坐标逐一比较说明与对应正文左边缘，差值均为 0 px：手机各步 x=24；桌面文件／映射 x=360，核对 x=24。三步文案各显示一次，两个旧标题均不显示；页面异常 0 |
| 工具与规格 | 在 `compose/qa/web/` 执行 `npm run test:node`；在仓库根执行 `openspec validate compose-cash-import-workflow --strict` 与 `git diff --check` | Node 工具 3 项通过；change 严格校验通过；diff 检查通过。完整 E2E 回归和 Wasm 单元测试仍未运行，按剩余任务补跑 |

离线演示使用合成数据，目前只有普通收支和缺账户草稿，不宣称自动／手动配对已通过演示验收。演示数据和截图均在仓库外，不读取真实账单。第一版已拆分文件／映射任务、默认全部并将已加载草稿排前、只读详情、整份确认及实际收据；分页不完整时不冒充全量草稿／配对统计。

独立产品／工程复核确认本轮模态接线无新增阻断：后台账本保持同一组合分支；关闭与结果返回都经过 `requestExit`；父层 Escape 在子模态打开时禁用，保留原恢复与账户退出保护。Hallmark audit 在本上下文对照第一版源码和代表截图检查任务结构、文案、M3 容器、层级及响应式：应用顶栏重复标题和正文重复退出入口已移除；FinanceTheme、默认 Material 3 控件和既有表格继续复用。步骤条下方仅保留一句业务说明：文件、账户映射、核对三步文案分别与各自正文左边缘对齐；不显示“指定账户映射”及“核对预计结果”大标题。按钮图标不重复朗读，原动作和启用条件不变；Hallmark 范围审查未新增 critical／major。此次只形成演示检查点，独立最终设计 QA、完整 diff 和异常分支验收仍未完成，5.1、5.2 不勾选。

按优先级保留的待办：

- major：完整列表的全量草稿／关系读取与识别依据合同未齐；候选组仅含锚点时，行内可能只显示一侧；详情对 `ignore` 的状态文案仍需区分。默认合成主流程不包含这些分支，用户确认后补齐。
- major：Web 子弹层关闭后语义镜像未刷新，第一版已复现；可见画面和鼠标确认仍能工作，语义可达性不得记通过。全屏容器调整后的嵌套详情和键盘关闭仍待完整复测。
- minor：账单原文区的重复标题和详情／列表的进一步间距、文案检查，按用户反馈修整。当前页详情顺序读取遇单项失败后的整页重试、配对演示夹具仍为待办。

Cross-platform Impact Check：Web、Android、iOS 共用展示层、模态容器、文案、退出和确认语义，三端均受影响；Web 已在 390×900、1440×900 完成代表主流程和对齐校验，Android／iOS 仅编译与单元测试，不宣称设备 QA。本轮短说明及布局同样由三端共享；无新领域概念，无需修改词表。无后端、DTO、持久化、迁移或依赖变化，双后端与存储性能检查不适用。

文档收尾范围为 proposal、design、两份 delta、功能地图、词表和工程约束。`AGENTS.md` 已定义 UI 第一版编译、Web 视觉校验后立即演示，每轮反馈修改后重新构建和启动最新演示；UI 规范链接该流程。README、API／配置／迁移文档没有新增命令或协议需要变更。完整现状文档核对和遗留旧编辑宣称清理留待最终实现完成，不因演示而勾选 7.1。回退只恢复本组共享展示和 AppShell 接线，不改任务协议或恢复证据。
