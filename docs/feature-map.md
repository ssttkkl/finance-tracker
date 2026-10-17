# Compose 功能地图

> 盘点日期：2026-10-18。本文记录当前 Compose Multiplatform 页面级功能、共享实现证据和已知覆盖缺口。行为事实以 `openspec/specs/` 与代码为准。

Compose Web、Android、iOS 共用 `compose/shared` 中的页面、状态和业务流程。Web、Android、iOS 工程入口分别位于 `compose/webApp`、`compose/androidApp` 和 `compose/iosApp`。旧 `web/src`、`mobile/src` 与 TypeScript shared packages 不属于当前客户端主线。

## 页面级功能

| ID | 页面级功能 | Compose 共享实现证据 | 当前实现 |
|---|---|---|---|
| F-01 | 登录与注册 | [`AccessScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AccessScreens.kt)、[`AuthResponsiveShell.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AuthResponsiveShell.kt) | 登录、注册与认证状态恢复 |
| F-02 | 工作区选择与创建 | [`App.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/app/App.kt)、[`AccessScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AccessScreens.kt) | 选择、创建、进入失败恢复 |
| F-03 | 收支账本 | [`CashLedgerScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerScreen.kt) | 搜索、筛选、分页、流水列表和操作入口 |
| F-04 | 现金流水 | [`CashLedgerDetails.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerDetails.kt)、[`CashLedgerViewModel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashLedgerViewModel.kt) | 新建、详情、编辑、删除及关系查看 |
| F-05 | 现金账单导入 | [`CashImportScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashImportScreen.kt)、[`ImportWorkflow.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/ImportWorkflow.kt) | 多文件扫描、账户映射、预览、关系审核与确认 |
| F-06 | 工作区邀请 | [`WorkspaceScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/WorkspaceScreens.kt)、[`AppRouting.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/core/AppRouting.kt) | 邀请预览、接受与取消 |
| F-07 | 现金分类管理 | [`CashCategoryScreen.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCategoryScreen.kt)、[`CashCategoryViewModel.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/CashCategoryViewModel.kt) | 分类目录、搜索、编辑、排序和删除确认 |
| F-08 | 投资持仓 | [`InvestmentScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentScreens.kt)、[`InvestmentDisplayLogic.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentDisplayLogic.kt) | 账户筛选、估值、表现和持仓详情 |
| F-09 | 投资事件 | [`InvestmentScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentScreens.kt)、[`InvestmentDisplayLogic.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/InvestmentDisplayLogic.kt) | 事件筛选、分页和证据详情 |
| F-10 | 工作区管理 | [`WorkspaceScreens.kt`](../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/WorkspaceScreens.kt) | 工作区资料、成员角色、邀请和删除 |

以上 10 项均有共享 Compose 页面实现，并由 Compose Web、Android、iOS 三个目标平台承载。共享代码存在不代表每个 target 的设备 QA 均已覆盖。

## 当前已知覆盖缺口

- F-05：iOS 文件选择器选中文件后交回应用、直至完成导入的端到端流程仍需实机验证。
- F-06：HTTPS Universal Links 需要部署域名与签名配置。
- F-07：iOS 分类管理的完整操作集仍需设备验证。
- F-10：iOS 工作区删除流程仍需设备验证。
- 尚无一份覆盖全部页面、平台与窗口尺寸的完整跨平台覆盖矩阵。

影响实现或覆盖状态的变更必须更新本地图，并在对应 OpenSpec `tasks.md` 留下具体 QA 证据。平台特有控件可以不同，但值、标签、确认语义、错误处理和业务结果必须遵守共享行为合同。
