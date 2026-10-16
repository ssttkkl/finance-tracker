# Compose / Legacy 功能地图

> 盘点日期：2026-10-14。本文记录 Compose Multiplatform 主线的页面级功能、实现证据和跨端覆盖关系；`web/src` 中的旧 React UI 仅作为迁移参考。本文不是产品行为规格，代码改动仍以 `openspec/specs/` 和对应变更记录为行为事实源。

Compose Web、Android、iOS 共用 `compose/shared` 的页面、状态和 API 流程。Compose Web 工程位于 `compose/webApp`，启动器、预览服务和 Playwright QA 位于 `compose/qa/web/`。旧 React UI 位于 `web/src`，保持原样并只供迁移参考；其实现证据保留在文档末尾，不代表当前 UI 规范、启动、构建、测试或发布入口。

桌面侧栏目的地的焦点边框遵循输入方式：键盘 Tab 导航时显示，鼠标或触屏点击后隐藏，同时保留选中标记；浏览器验证记录见 [`navigation-focus-ring-modality`](../openspec/changes/archive/2026-10-13-navigation-focus-ring-modality/tasks.md)。

## 统计结论

历史 UI 设计稿只用于审计迁移过程。当前设计合同由 Material 3、`FinanceTheme`、Git token 和 Compose 实现定义；当前代码与演示模式中的截图、交互证据记录在活跃 OpenSpec 变更中。共享 Compose 控件沿用 Material 3 默认尺寸并按内容增长；调用点不写死控件宽高，响应式最大边界与必要页面结构尺寸除外，审查记录见活跃 OpenSpec 变更。

按用户可访问的页面级功能统计：

| 指标 | 数量 |
|------|------:|
| Compose 页面级功能 | 10 |
| Compose 共享实现覆盖 | 10 |
| Compose 活跃目标 | Compose Web / Android / iOS |
| Legacy React Web 页面级功能（参考） | 10 |
| Legacy Expo 页面级功能（参考） | 5 |
| 去重后的页面级功能总数 | 10 |

“Compose 共享实现覆盖”表示页面具备可运行的 Compose 入口，并接入真实 API；仅有后端接口、共享文案、原型、规格或 legacy 端导航均不算当前主线实现。

## Compose Multiplatform 主线

F-01 至 F-10 的共享页面、状态和 API 流程已放入 `compose/shared/src/commonMain`；Compose Web、Android、iOS target 使用同一套页面实现。旧 React UI 位于 `web/src`，保持原样并仅作为迁移参考；本功能地图末尾保留其历史实现证据。各 target 的已验收范围和待补 QA 记录在下表。跨页面入口壳与已登录标题层级已由 [`app-shell-entry-presentation`](../openspec/changes/archive/2026-10-12-app-shell-entry-presentation/) 对齐：F-01/F-02/F-06 共用登录式响应布局和居中卡片，F-03 至 F-10 由共享 `DestinationScaffold` 在顶栏显示唯一页面标题；桌面侧栏默认显示图标，鼠标进入侧栏自动展开、离开后收回，鼠标点击导航产生的焦点不会阻止收回，展开时点击收起控制可立即收回；键盘焦点仍可展开侧栏，头像悬停打开账号面板且在停留期间保持稳定，Enter/Space 可从键盘焦点打开面板，Escape 可关闭并将焦点返回头像；工作区切换、管理和退出集中在侧栏底部；侧栏交互与跨端 QA 证据记录于 [`navigation-rail-collapse`](../openspec/changes/archive/2026-10-13-navigation-rail-collapse/tasks.md) 和 [`account-menu-hover-stability`](../openspec/changes/archive/2026-10-13-account-menu-hover-stability/tasks.md)。

| ID | Compose 共享实现 | Web / Android / iOS 工程入口 | 当前状态 |
|----|------------------|------------------------------|----------|
| F-01 | [`AccessScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AccessScreens.kt)、[`AuthResponsiveShell.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AuthResponsiveShell.kt) | [`webApp`](../compose/webApp/build.gradle.kts)、[`androidApp`](../compose/androidApp/src/main/kotlin/com/finance/tracker/MainActivity.kt)、[`iosApp`](../compose/iosApp/iosApp/ContentView.swift) | 登录 / 注册使用 FinanceTheme 下的标准 Material 3 浮动标签、辅助文本和控件形状，并与工作区/未登录邀请共用响应式外壳和居中卡片；间距与控件迁移见 [`auth-page-spacing-shell`](../openspec/changes/auth-page-spacing-shell/)；本轮 Compose Web Chrome、Android device QA 记录见 `app-shell-entry-presentation/tasks.md` |
| F-02 | [`App.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/app/App.kt)、[`AccessScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AccessScreens.kt) | 同上 | 工作区选择、创建和恢复已实现；首次创建与已有工作区选择共用登录式容器、账户头部和居中卡片，保留列表选择、名称校验、请求错误、重试和创建成功导航；布局与 QA 证据见 [`app-shell-entry-presentation`](../openspec/changes/archive/2026-10-12-app-shell-entry-presentation/) |
| F-03 | [`CashLedgerScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScreen.kt)、[`FinanceComponents.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/FinanceComponents.kt) | 同上 | 顶部搜索复用交易对方筛选，类型快捷项固定为全部、支出、收入和转账；详细筛选保留分类等字段，`COMPACT` 使用 Bottom Sheet，`REGULAR` / `WIDE` 使用模态弹窗。列表使用 `COMPACT` 流水卡片、`REGULAR` / `WIDE` 紧凑表格，月份收支汇总位于分组标题；筛选和列表共享 Material 3 前景 Surface，中间用 `outlineVariant` 分隔，页面底色作为下层背景。Compact 默认隐藏复选框并以长按进入选择模式，Regular/Wide 保留行复选框，所有窗口移除全选入口并在选择时显示已选数量。新增、导入及批量动作统一收进 Material 3 FAB 菜单；批量分类通过模态对话框应用，删除沿用现有确认；批量删除入口与确认使用危险色。主要操作保留文字并配有动作图标。全局 FinanceTheme 将现有 token 映射到 `surfaceContainer*`，影响所有 Compose 平台使用 Material 3 默认容器色的组件；旧 React Web 不变。保留筛选、分页和导入能力；按钮与文本输入直接使用 Material 3，FinanceDataTable/Amount 等展示辅助函数保留，Registry 见 [`docs/design-system/registry.yaml`](design-system/registry.yaml)；本次实现和 Web QA 见 [`compose-cash-ledger-redesign/tasks.md`](../openspec/changes/compose-cash-ledger-redesign/tasks.md) |
| F-04 | [`CashLedgerScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScreen.kt)、[`CashLedgerDetails.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerDetails.kt)、[`CashLedgerViewModel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerViewModel.kt) | 同上 | 新增、详情、编辑共用模态信息面板；`COMPACT` 全屏，`REGULAR` / `WIDE` 右侧显示，编辑在详情面板内切换；金额摘要保持在中性面板底色上，主要字段和关联流水分别使用 Material 3 ElevatedCard 默认前景角色 `surfaceContainerLow`；全局 FinanceTheme 从现有浅/深色 token 映射 `surfaceContainer*`，不在组件内重复指定容器颜色；关联流水卡默认收起、展开内容留在同一卡片内，不叠加模态层或嵌套卡片；编辑、关联、保存和删除按钮保留文字并显示动作图标，删除和解散关联使用危险色，删除确认按钮沿用危险色；本轮只执行 Compose Web 复核，范围与证据见 [`compose-cash-ledger-redesign/tasks.md`](../openspec/changes/compose-cash-ledger-redesign/tasks.md) |
| F-05 | [`CashImportScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportScreen.kt)、[`SecondaryPageContainer.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/SecondaryPageContainer.kt)、[`FinanceTheme.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/core/FinanceTheme.kt)、[`ImportWorkflow.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/ImportWorkflow.kt)、[`cash_ledger.py`](../src/ft/application/cash_ledger.py)、[`relations.py`](../src/ft/application/relations.py) | 同上 | 文件选择、扫描、映射、预览、关系审查和确认已实现；待分摊流水保留在预览中，金额不完整时不进入关系匹配；跨来源退款只在对应关系唯一且金额精确相同或退款侧已镜像时替代系统自动银行退款，并先释放旧关系额度再保存新关系及取代链；人工决定保持不变；从收支账本打开，不占一级导航；Compact 全屏返回，Regular/Wide 使用 90% 模态和关闭/Escape；待配对流水使用单一下拉框，Web 候选项以锚定浮层展示，Native 保留原生下拉菜单；批量扫描、密码重试和关闭清理共用导入会话；无 API 后端演示提供内置合成账单并复用同一流程。相关设计验收历史见 [`cash-import-candidate-overlay`](../openspec/changes/archive/2026-10-14-cash-import-candidate-overlay/tasks.md)，演示证据以当前 change 的 `tasks.md` 为准。 |
| F-06 | [`WorkspaceScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/WorkspaceScreens.kt)、[`AppRouting.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/core/AppRouting.kt) | 同上 | 邀请预览、接受、取消及内部 URI 路由已实现；未登录邀请沿用认证入口卡片，已登录邀请保留 Scaffold 标题与接受流程；本轮 Web/Native QA 见 `app-shell-entry-presentation/tasks.md`；HTTPS Universal Links 仍需域名和签名配置 |
| F-07 | [`CashCategoryScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCategoryScreen.kt)、[`CashCategoryViewModel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCategoryViewModel.kt)、[`CashCategoryUseCases.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/domain/CashCategoryUseCases.kt) | 同上 | 分类目录、层级编辑、搜索、排序和删除确认已实现；分类页面已迁移至 Domain Repository、用例和 StateFlow ViewModel，Wasm 单测通过；Web Chrome 与 Android 搜索/创建 QA 通过；iPad 11 已验无结果搜索和创建，其他操作仍需补验 |
| F-08 | [`InvestmentScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentScreens.kt)、[`InvestmentDisplayLogic.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentDisplayLogic.kt) | 同上 | 持仓筛选、估值、表现和详情已实现；Web E2E 与 Android compact/regular/wide 主题布局 QA 通过；iPad 11 已验估值/详情，iPad 13 wide 已验深色布局 |
| F-09 | [`InvestmentScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentScreens.kt)、[`InvestmentDisplayLogic.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentDisplayLogic.kt) | 同上 | 事件筛选、分页和证据详情已实现；Web E2E 与 Android 详情/分页 QA 通过；iPad 11 已验详情与加载更多 |
| F-10 | [`WorkspaceScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/WorkspaceScreens.kt) | 同上 | 名称、成员、角色、邀请和删除流程已实现；Web E2E 与 Android 管理流程 QA 通过；iOS 已验改名、成员角色和邀请，iPhone/iPad 11 regular/iPad 13 wide 复核了邮箱布局，删除流程仍需补验 |

浏览器用例位于 [`compose-access.e2e.ts`](../compose/qa/web/tests/compose-access.e2e.ts)、[`compose-pages.e2e.ts`](../compose/qa/web/tests/compose-pages.e2e.ts)、[`compose-live-demo.e2e.ts`](../compose/qa/web/tests/compose-live-demo.e2e.ts) 和 [`compose-locale.e2e.ts`](../compose/qa/web/tests/compose-locale.e2e.ts)，当前共 29 项；Playwright 路由提供 API fixture。本轮生产 Wasm 完整回归先通过 26/28，F-04 编辑输入和 F-05 导入点击修复后已定向通过，完整复跑结果记录在本变更 `tasks.md`。此前 Chrome 153、Edge 154、Playwright WebKit 26.5 的结果属于早期检查点；用户指定本次 Web 验收使用 Chrome，不使用 Safari。Android 原生 F-01 至 F-10 早期 QA 已通过，本轮 shared 变更后最终复验待完成；iOS 已完成 iPhone 17、iPad 11、iPad 13 的早期功能抽查及 compact/regular/wide 关键页面审查。iOS F-05 已用虚构 CSV 验证文件可见和选中，但点击「打开」后的系统截图仍停留在文件选择器，未确认文件交回应用；端到端导入仍待补验。iOS 其余页面操作和完整三端×窗口尺寸 parity matrix 仍待补验。Wasm shared browser test 和 Android host test 在本轮通过；Web Vitest、Native device 最终结果记录在 `tasks.md`。F-03/F-04/F-07 在 320、375、390、414、768、1440 px 下检查无水平溢出；浅色 390×844 和 1440×1000 截图经目视复核，深色复核结果见 `tasks.md`。Web 字体使用同源 Noto Sans SC 资源，Chrome 截图未发现缺字，未请求 `fonts.gstatic.com`。Playwright fixture 检查 UI/API 请求，本地 registration E2E 另通过 FastAPI demo 实测；两者都不替代 FastAPI 后端契约测试。

原 `ALIGN-N-001` 至 `ALIGN-N-005` 是 Expo 时代的迁移历史记录，说明旧 Native 客户端曾缺少的功能。Compose 主线已补齐这些页面；这些条目保留用于审计旧端行为，不是当前 Compose 工作队列，也不要求恢复 Expo 页面。

## 统计口径

- 一个用户可以独立完成任务的页面或等价交互 surface 计为一个页面级功能。
- Web 的抽屉式记账详情与 Native 的独立记账页面属于同一个页面级功能，不因承载形态不同重复计数。
- 认证中的登录/注册模式、记账中的新建/查看/编辑模式、导入中的选择/映射/预览/关系/成功步骤，均属于各自页面级功能的内部状态或流程。
- 计入邀请页、工作区管理、分类管理和投资的持仓/事件页面；不把它们合并成一个泛化的“工作区”或“投资账本”页面。
- 不计入 `index.tsx` 重定向、`_layout.tsx` 路由守卫、导航 shell、加载态、错误态、确认弹窗和其他不能独立完成任务的容器。

## Legacy 页面级功能证据（迁移参考）

以下清单保留 React Web / Expo 页面入口和历史覆盖状态，帮助 Compose 实现核对已有用户行为。它不改变 Compose 主线的实现状态，也不把 legacy 代码加入根工作流。

| ID | 页面级功能 | 旧 React Web 实现 | Native 实现 | 覆盖状态 |
|----|------------|----------|-------------|----------|
| F-01 | 认证：登录 / 注册 | `Auth`，根入口的登录/注册模式；[`web/src/AccessApp.tsx`](../web/src/AccessApp.tsx) | `/(auth)/login`，登录/注册模式；[`mobile/src/app/(auth)/login.tsx`](<../mobile/src/app/(auth)/login.tsx>) | 双端已实现；Compose 共享认证视图按确认稿提供实时校验、注册确认密码及返回登录，详见 [`login-page-branding`](../openspec/changes/login-page-branding/) |
| F-02 | 工作区入口：选择 / 创建 / 进入失败恢复 | `Create`、工作区切换器和 `WorkspaceSelectionError`；[`web/src/AccessApp.tsx`](../web/src/AccessApp.tsx) | `/(app)/workspace`，选择、自动进入和创建；[`mobile/src/app/(app)/workspace.tsx`](<../mobile/src/app/(app)/workspace.tsx>) | 双端已实现 |
| F-03 | 收支账本：筛选、列表、空态、错误态和操作入口 | `/` 或 `/w/<workspace-id>/`；[`web/src/pages/CashLedgerPage.tsx`](../web/src/pages/CashLedgerPage.tsx) | `/(app)/ledger`；[`mobile/src/app/(app)/ledger.tsx`](<../mobile/src/app/(app)/ledger.tsx>) | 双端已实现 |
| F-04 | 收支流水：新建、查看、编辑、删除、凭证和关系 | Web 由收支账本打开 `RecordDrawer` / `EvidenceDetail`；[`web/src/components/RecordDrawer.tsx`](../web/src/components/RecordDrawer.tsx)、[`web/src/components/EvidenceDetail.tsx`](../web/src/components/EvidenceDetail.tsx) | `/(app)/record`，通过 `mode=create`、`mode=edit` 和流水标识承载新建、详情、编辑；[`mobile/src/app/(app)/record.tsx`](<../mobile/src/app/(app)/record.tsx>) | 双端已实现 |
| F-05 | 账单导入与关系审查：选择、扫描、映射、预览、配对、确认 | `/cash-import`；[`web/src/pages/CashImportPage.tsx`](../web/src/pages/CashImportPage.tsx) | `/(app)/import`；[`mobile/src/app/(app)/import.tsx`](<../mobile/src/app/(app)/import.tsx>) | 双端已实现 |
| F-06 | 工作区邀请：预览、登录后接受、取消和失效处理 | `?invite=<token>`；`Invite` 位于 [`web/src/AccessApp.tsx`](../web/src/AccessApp.tsx) | 无 Native 页面或入口 | 仅 Web 已实现 |
| F-07 | 收支分类管理：目录、搜索、新增、编辑、排序、删除影响确认 | `/cash-categories`；[`web/src/pages/CashCategoriesPage.tsx`](../web/src/pages/CashCategoriesPage.tsx) | 无 Native 页面或入口 | 仅 Web 已实现 |
| F-08 | 投资持仓：账户筛选、估值、表现和持仓详情 | `/investment-holdings`；`InvestmentLedgerPage(view="holdings")` 位于 [`web/src/pages/InvestmentLedgerPage.tsx`](../web/src/pages/InvestmentLedgerPage.tsx) | 无 Native 页面；Native 导航仅标记投资入口暂不可用；[`mobile/src/components/NativeShell.tsx`](../mobile/src/components/NativeShell.tsx) | 仅 Web 已实现 |
| F-09 | 投资事件：事件筛选、列表、分页和证据详情 | `/investment-events`；`InvestmentLedgerPage(view="events")` 位于 [`web/src/pages/InvestmentLedgerPage.tsx`](../web/src/pages/InvestmentLedgerPage.tsx) | 无 Native 页面；Native 导航仅标记投资入口暂不可用；[`mobile/src/components/NativeShell.tsx`](../mobile/src/components/NativeShell.tsx) | 仅 Web 已实现 |
| F-10 | 工作区管理：名称、成员角色、邀请链接和删除 | `/workspace-management`；`WorkspaceManagement` 位于 [`web/src/AccessApp.tsx`](../web/src/AccessApp.tsx) | 无 Native 页面或入口 | 仅 Web 已实现 |

## 跨端对齐 TODO

以下项目来自上表中“仅 Web 已实现”的页面级功能。完成前不能从功能地图中删除；应先补齐 Native 的真实页面和 API 流程，再把覆盖状态改为“双端已实现”。

| TODO | 待补齐端 | 对齐范围 | 完成条件 |
|------|----------|----------|----------|
| ALIGN-N-001 | Native | 工作区邀请预览、角色说明、登录后接受、取消、无效/过期错误和成功回到工作区 | Native 有可访问入口；邀请接受结果、角色和错误语义与 Web 一致；补充 Native 测试与设备 QA 证据 |
| ALIGN-N-002 | Native | 收支分类目录、搜索、层级编辑、新增、排序、删除影响确认和空/错误态 | Native 有分类管理页面；操作结果与 Web/API 一致；覆盖 `compact`、`regular`、`wide` 逻辑窗口 |
| ALIGN-N-003 | Native | 投资持仓列表、账户筛选、估值状态、表现范围、空/错误/加载态和详情 | Native 有持仓页面；金额、数量、币种和估值语义复用共享契约；完成相关设备和响应式验证 |
| ALIGN-N-004 | Native | 投资事件筛选、列表、分页、事件证据详情和错误恢复 | Native 有事件页面；列表字段、筛选值、详情和返回关系与 Web 一致；完成相关设备和响应式验证 |
| ALIGN-N-005 | Native | 工作区名称、成员列表、角色维护、邀请链接、权限限制、删除确认和删除后回退 | Native 有工作区管理页面；管理员/编辑者/查看者权限和危险操作语义与 Web 一致；完成设备 QA |

### 不作为 TODO 的跨端差异

- Web 使用侧边导航和 HTML 控件，Native 使用 rail、顶部菜单、原生选择器或系统文件选择器；这些属于已有 `presentation 契约` 中登记的平台差异。
- 旧 React Web 的 F-04 抽屉与旧 Expo Native 的独立页面是迁移历史差异；Compose Web、Android 和 iOS 共用的当前 F-04 模态面板合同见 [本次账本重构](../openspec/changes/compose-cash-ledger-redesign/design.md)。
- Native 登录页的调试地址控件是构建开关控制的 Native 专属调试能力，不是独立页面级功能。

## 维护规则

1. 所有功能改动（包括后端能力新增客户端入口，以及 Web、Native 或共享 presentation 层改动），必须在同一组改动中更新本功能地图。
2. 更新时至少检查页面总数、双端/单端覆盖状态、实现证据链接和对应的跨端对齐 TODO。
3. 新增页面级功能时，先加入清单并明确两端状态；只实现一端时必须同时新增对齐 TODO。
4. 补齐另一端后，只有在真实客户端入口、API 流程和相称验证完成后，才可将 TODO 标为完成或移除。
5. 纯文档、词表、功能地图、`AGENTS.md` 或 `README.md` 的修改不需要创建 OpenSpec 变更；涉及代码改动时仍遵守仓库的 OpenSpec 门禁。

## 盘点依据

- Web 页面入口：[`web/src/App.tsx`](../web/src/App.tsx)、[`web/src/AccessApp.tsx`](../web/src/AccessApp.tsx)、[`web/src/pages/`](../web/src/pages/)。
- Web 记账详情 surface：[`web/src/components/RecordDrawer.tsx`](../web/src/components/RecordDrawer.tsx)、[`web/src/components/EvidenceDetail.tsx`](../web/src/components/EvidenceDetail.tsx)。
- Native 页面入口：[`mobile/src/app/`](../mobile/src/app/)。
- Native 导航与暂不可用入口：[`mobile/src/components/NativeShell.tsx`](../mobile/src/components/NativeShell.tsx)。
- 跨端页面、状态和语义入口：[`packages/presentation/src/screen-contracts.ts`](../packages/presentation/src/screen-contracts.ts)、[`packages/presentation/src/semantic-ids.ts`](../packages/presentation/src/semantic-ids.ts)、[`packages/presentation/src/platform-differences.ts`](../packages/presentation/src/platform-differences.ts)。
