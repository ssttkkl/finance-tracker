## Why

Compose 的登录、工作区访问和未登录邀请入口由不同容器组织，导致同一访问流程的页面边界不一致。已登录业务页面虽已有 Material 3 `Scaffold`，但标题同时出现在顶栏和页面正文，且宽屏顶栏不显示路由标题；统一两类页面骨架能让入口流程连续，并让已登录页面有单一、稳定的标题层级。

## What Changes

- 登录/注册、工作区选择与创建、未登录邀请页面共用响应式容器和居中表单卡片；compact 窗口也使用卡片并可纵向滚动，保留各页面已有字段、操作、状态和业务结果。
- 已登录的所有路由继续由共享 Material 3 `Scaffold` 承载；`TopAppBar` 在 COMPACT、REGULAR、WIDE 都显示当前页面标题，正文移除重复标题。
- 保留现有导航策略：COMPACT/REGULAR 使用模态抽屉，WIDE 使用常驻侧栏；保留返回、工作区切换和退出登录语义。
- 设计与实现限定在 Compose Multiplatform 共享页面（Web、Android、iOS）；不改旧 React Web 或 Expo 页面、路由、权限、文案语义和后端接口。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `workspace/workspace-entry`：认证后工作区选择、首次创建和邀请访问入口采用与登录/注册一致的 Compose 响应式卡片容器，同时保留各自流程与状态。
- `shared/cross-platform-presentation`：Compose 入口壳和已登录 Scaffold 的标题、导航、正文层级在三类窗口宽度下遵循统一 presentation 合同。

## Impact

- Compose 共享 UI：`AuthResponsiveShell.kt`、`AccessScreens.kt`、`WorkspaceScreens.kt`、`App.kt`、`AppShell.kt`、`FeatureComponents.kt` 及业务页面标题区域。
- 设计与正式记录：新增 Pen 页面稿和 HTML 原型，更新 `design/README.md`、`docs/feature-map.md` 及对应 OpenSpec artifacts。
- 无 API、数据模型、依赖或财务语义变化；Compose Web、Android、iOS 共用实现，因此三个 target 均受影响。

## Decisions Captured

- 三类入口共用 `AuthResponsiveShell` 和居中表单卡片；compact、regular、wide 均显示卡片，compact 可纵向滚动，卡片最大宽度 440 dp。登录/注册既有 compact 无卡片样式同步调整为已确认的统一入口布局。
- 已登录路由统一由 `TopAppBar` 承担页面标题，移除页面正文中重复标题；宽屏也显示顶栏标题。
- 登录卡片的 FinanceTheme、颜色和排版是入口页视觉基准；不改变现有主导航模式。
