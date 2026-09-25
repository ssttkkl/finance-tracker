# Web / Native 功能地图

> 盘点日期：2026-09-25。本文记录当前 React Web / Expo 客户端状态，以及 Compose Multiplatform 迁移实现进度；它不是产品行为规格。代码改动仍以 `openspec/specs/` 和对应变更记录为行为事实源。

本表前半部分的「Web / Native」统计描述现有 React Web 与 Expo 客户端。迁移中的 Compose 实现状态单独列在下方；三端平台验收完成前，不把迁移实现计为已验收覆盖。

## 统计结论

按用户可访问的页面级功能统计：

| 指标 | 数量 |
|------|------:|
| Web 页面级功能 | 10 |
| Native 页面级功能 | 5 |
| Web 与 Native 都已实现 | 5 |
| 仅 Web 已实现 | 5 |
| 仅 Native 已实现 | 0 |
| 去重后的页面级功能总数 | 10 |

“都有实现”表示两端都具备可运行的客户端入口，并已接入真实 API；仅有后端接口、共享文案、原型、规格或 Native 的“暂不可用”导航均不算已实现。

## Compose Multiplatform 迁移状态

F-01 至 F-10 的共享页面、状态和 API 流程已放入 `compose/shared/src/commonMain`；Web、Android、iOS target 使用同一套页面实现。旧 React Web 与 Expo 客户端仍保留作为回退。各 target 的已验收范围和待补 QA 记录在下表。

| ID | Compose 共享实现 | Web / Android / iOS 工程入口 | 当前状态 |
|----|------------------|------------------------------|----------|
| F-01 | [`AccessScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/AccessScreens.kt) | [`webApp`](../compose/webApp/build.gradle.kts)、[`androidApp`](../compose/androidApp/src/main/kotlin/com/finance/tracker/MainActivity.kt)、[`iosApp`](../compose/iosApp/iosApp/ContentView.swift) | 登录 / 注册已实现；Web Chrome 与 Android phone 登录 QA 通过；iOS 已运行原生会话和 API 流程，认证边界场景仍需补验 |
| F-02 | [`App.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/App.kt)、[`AccessScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/AccessScreens.kt) | 同上 | 工作区选择、创建和恢复已实现；Chrome 生产 E2E 与 Android 旧深链接拒绝后选择可访问工作区回归通过；iOS 邀请接受后返回账本，选择/创建/恢复边界仍需补验 |
| F-03 | [`CashLedgerScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/CashLedgerScreen.kt) | 同上 | 收支筛选、分页、摘要和状态已实现；Web Chrome 与 Android compact/regular/wide 页面 QA 通过；iPhone 17 已验筛选、离线错误与重试，iPad 11 已验账本视图 |
| F-04 | [`CashLedgerScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/CashLedgerScreen.kt) | 同上 | 流水新建、编辑、详情、证据和关系操作已实现；Web E2E、Android 新建/编辑流程通过；iPhone 17 已验详情、编辑和取消返回，完整 CRUD 仍需补验 |
| F-05 | [`CashImportScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/CashImportScreen.kt)、[`ImportWorkflow.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/ImportWorkflow.kt) | 同上 | 文件选择、扫描、映射、预览、关系审查和确认已实现；Web E2E 与 Android 系统 CSV 选择器流程通过；iOS 已显示并选中本地虚构 CSV，但点「打开」后未确认文件回到 Compose 页面，端到端导入仍待验证 |
| F-06 | [`WorkspaceScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/WorkspaceScreens.kt)、[`AppRouting.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/AppRouting.kt) | 同上 | 邀请预览、接受、取消及内部 URI 路由已实现；Web E2E、Android 邀请 URI 与 iPhone 17 `finance-tracker://invite/<token>` 预览/接受通过；HTTPS Universal Links 仍需域名和签名配置 |
| F-07 | [`CashCategoryScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/CashCategoryScreen.kt)、[`CashCategoryLogic.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/CashCategoryLogic.kt) | 同上 | 分类目录、层级编辑、搜索、排序和删除确认已实现；Web Chrome 与 Android 搜索/创建 QA 通过；iPad 11 已验无结果搜索和创建，其他操作仍需补验 |
| F-08 | [`InvestmentScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/InvestmentScreens.kt)、[`InvestmentDisplayLogic.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/InvestmentDisplayLogic.kt) | 同上 | 持仓筛选、估值、表现和详情已实现；Web E2E 与 Android compact/regular/wide 主题布局 QA 通过；iPad 11 已验估值/详情，iPad 13 wide 已验深色布局 |
| F-09 | [`InvestmentScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/InvestmentScreens.kt)、[`InvestmentDisplayLogic.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/InvestmentDisplayLogic.kt) | 同上 | 事件筛选、分页和证据详情已实现；Web E2E 与 Android 详情/分页 QA 通过；iPad 11 已验详情与加载更多 |
| F-10 | [`WorkspaceScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/WorkspaceScreens.kt) | 同上 | 名称、成员、角色、邀请和删除流程已实现；Web E2E 与 Android 管理流程 QA 通过；iOS 已验改名、成员角色和邀请，iPhone/iPad 11 regular/iPad 13 wide 复核了邮箱布局，删除流程仍需补验 |

浏览器用例位于 [`compose-access.e2e.ts`](../web/tests/compose-access.e2e.ts) 和 [`compose-pages.e2e.ts`](../web/tests/compose-pages.e2e.ts)，当前共 24 项；Playwright 路由提供 API fixture，Chrome 153 的最新生产 Wasm 回归 24/24 通过。此前 Chrome 153、Edge 154、Playwright WebKit 26.5 的结果属于早期检查点；用户指定本次 Web 验收使用 Chrome，不使用 Safari。Android 原生 F-01 至 F-10 QA 已通过；iOS 已完成 iPhone 17、iPad 11、iPad 13 的功能抽查及 compact/regular/wide 关键页面审查。iOS F-05 已用虚构 CSV 验证文件可见和选中，但点击「打开」后的系统截图仍停留在文件选择器，未确认文件交回应用；端到端导入仍待补验。iOS 其余页面操作和完整三端×窗口尺寸 parity matrix 仍待补验。Web Vitest 152 项、共享 Kotlin 65 项、Android device 67 项通过；Wasm 测试及 JS/Wasm production 构建已通过，最新复验记录见 Compose 变更的 `tasks.md`。F-03/F-04/F-07 在 320、375、390、414、768、1440 px 下检查无水平溢出；浅/深色 390×1800 和 1440×1000 截图经目视复核，包含 F-05 关系卡片与 F-08 持仓页面。Web 字体使用同源 Noto Sans SC 资源，Chrome 截图未发现缺字，未请求 `fonts.gstatic.com`。Playwright 用例通过本地 API fixture 检查 Compose UI 与请求流程，不替代 FastAPI 后端契约测试。

原 `ALIGN-N-001` 至 `ALIGN-N-005` 继续表示当前 Expo 客户端缺少的功能。Compose 迁移实现已补齐这些页面，但在三端验收并完成切换前不能删除旧端 TODO 或退场文件。

## 统计口径

- 一个用户可以独立完成任务的页面或等价交互 surface 计为一个页面级功能。
- Web 的抽屉式记账详情与 Native 的独立记账页面属于同一个页面级功能，不因承载形态不同重复计数。
- 认证中的登录/注册模式、记账中的新建/查看/编辑模式、导入中的选择/映射/预览/关系/成功步骤，均属于各自页面级功能的内部状态或流程。
- 计入邀请页、工作区管理、分类管理和投资的持仓/事件页面；不把它们合并成一个泛化的“工作区”或“投资账本”页面。
- 不计入 `index.tsx` 重定向、`_layout.tsx` 路由守卫、导航 shell、加载态、错误态、确认弹窗和其他不能独立完成任务的容器。

## 页面级功能清单

| ID | 页面级功能 | Web 实现 | Native 实现 | 覆盖状态 |
|----|------------|----------|-------------|----------|
| F-01 | 认证：登录 / 注册 | `Auth`，根入口的登录/注册模式；[`web/src/AccessApp.tsx`](../web/src/AccessApp.tsx) | `/(auth)/login`，登录/注册模式；[`mobile/src/app/(auth)/login.tsx`](<../mobile/src/app/(auth)/login.tsx>) | 双端已实现 |
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
- F-04 在 Web 中使用抽屉、在 Native 中使用独立页面；只要字段、操作、状态和业务结果一致，不再重复登记为缺口。
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
