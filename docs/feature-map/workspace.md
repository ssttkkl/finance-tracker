# 工作区模块

[返回产品总览](../feature-map.md) · [跨端覆盖与待办](../feature-map.md#跨端覆盖与待办)

本模块包含 **3 项页面级功能**：F-02 工作区选择与创建、F-06 邀请预览与接受、F-10 管理。F-10 中的分类任务归入 [现金账本 F-07](cash-ledger.md#f-07-收支分类管理)。

## F-02 工作区选择与创建

**入口：** 认证后的工作区入口，不是独立路由。

**业务路径：**

- 有可访问工作区：浏览列表 → 选择工作区 → 进入账本；也可创建另一个工作区。
- 没有工作区：显示首次创建表单 → 输入名称 → 创建成功 → 进入新工作区账本。
- 切换工作区：选择目标工作区，保留当前子页面路径。

**失败与恢复：** 名称无效时在表单内修正；读取、切换或创建失败时保留可用内容并提供重试。

### 实现与验证证据

工作区选择、创建和恢复已实现。首次创建与已有工作区选择共用登录式容器、账户头部和居中卡片，保留列表选择、名称校验、请求错误及重试。

- [`App.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/app/App.kt)
- [`AccessScreens.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AccessScreens.kt)
- [`app-shell-entry-presentation`](../../openspec/changes/archive/2026-10-12-app-shell-entry-presentation)

## F-06 邀请预览与接受

**入口：** 邀请链接 `?invite=<token>`；Native 也可从 `finance-tracker://invite/<token>` 进入。

**主路径：** 打开链接 → 读取工作区与角色预览 → 接受邀请 → 加入并进入工作区。未登录时先登录或注册，再返回邀请。

**分支与恢复：** 取消后返回原页面或默认账本；预览请求失败可重试。无效、过期或已不能接受的邀请显示结果，不进入工作区。

### 实现与验证证据

邀请预览、接受、取消及内部 URI 路由已实现。未登录邀请沿用认证入口卡片，已登录邀请保留 Scaffold 标题与接受流程。Web／Native 既有 QA 见 [入口呈现任务](../../openspec/changes/archive/2026-10-12-app-shell-entry-presentation/tasks.md)。HTTPS Universal Links 仍需部署域名与签名配置。

- [`WorkspaceScreens.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/WorkspaceScreens.kt)
- [`AppRouting.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/core/AppRouting.kt)

## F-10 管理

**入口：** `/w/<workspaceId>/workspace-management`。

**页面分组：** 工作区资料 → 基本信息／危险操作；访问权限 → 成员与权限／邀请成员；收支账本 → 收支分类。各分组在同一页面定位，不为投资增加占位分组。

**业务路径与权限：**

| 任务 | 操作与结果 |
|---|---|
| 工作区资料 | 管理员修改工作区名称，或复制固定 ID |
| 成员管理 | 管理员调整角色或移除成员 |
| 邀请成员 | 管理员选择角色，创建并复制邀请链接 |
| 删除工作区 | 管理员输入当前工作区名称确认；删除成功后进入另一个可访问工作区，没有则进入工作区创建 |
| 收支分类 | 从收支账本分组进入 F-07；`editor` 可维护，`viewer` 只读 |

`editor` 和 `viewer` 不能修改工作区资料、成员或邀请，也不能删除工作区。管理详情加载失败时在页面重试。

### 实现与验证证据

管理页使用 Material 3 文字角色、平面分组和细分隔。`REGULAR`／`WIDE` 在右侧显示目录，`COMPACT` 通过工具栏打开全屏目录；共享两级同页定位和当前项。

既有 Web E2E 与 Android 管理流程 QA 通过；iOS 已验证改名、成员角色和邀请，iPhone、iPad 11 regular、iPad 13 wide 复核过邮箱布局。新管理导航在 Android 390／768／1440 dp 已验证目录、分类模态保存和分类深链接定位；iOS 新布局仅完成共享编译，实际新布局及删除流程仍需设备验收。

- [`WorkspaceScreens.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/WorkspaceScreens.kt)
- [`ManagementLayout.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/ManagementLayout.kt)
- [`ManagementNavigation.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/ManagementNavigation.kt)
- [`tasks.md`](../../openspec/changes/archive/2026-10-21-unify-management-settings/tasks.md)

## 相关规格

- [工作区入口](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AccessScreens.kt)
- [管理页面](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/WorkspaceScreens.kt)
- [工作区路由](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/core/AppRouting.kt)
- [工作区入口规格](../../openspec/specs/workspace/workspace-entry/spec.md)
- [工作区路由规格](../../openspec/specs/workspace/workspace-context-routing/spec.md)
- [工作区管理规格](../../openspec/specs/workspace/workspace-management/spec.md)
