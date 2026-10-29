# 认证模块

[返回产品总览](../feature-map.md) · [跨端覆盖与待办](../feature-map.md#跨端覆盖与待办)

本模块包含 **1 项页面级功能**：F-01 登录与注册。认证与工作区入口的页面外壳见 [共享规则](shared.md)。

## F-01 登录与注册

**入口：** 未登录时从应用入口打开。登录和注册是同一页面的两种模式。

**主路径：** 启动或刷新 → 恢复有效会话 → 进入当前工作区；没有有效会话 → 登录或切换注册 → 认证成功 → 进入已有工作区，或进入 F-02 选择／创建工作区。

**失败与恢复：** 认证失败保留表单，可修改输入或重试。

### 实现与验证证据

登录／注册使用 `FinanceTheme` 下的标准 Material 3 浮动标签、辅助文本和控件形状，与工作区和未登录邀请共用响应式外壳与居中卡片。Web Chrome 和 Android 的既有入口 QA 记录见 [入口呈现任务](../../openspec/changes/archive/2026-10-12-app-shell-entry-presentation/tasks.md)；历史结果不代表所有平台和状态已验收。

- [`AccessScreens.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AccessScreens.kt)
- [`AuthResponsiveShell.kt`](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AuthResponsiveShell.kt)
- [`webApp`](../../compose/webApp/build.gradle.kts)
- [`androidApp`](../../compose/androidApp/src/main/kotlin/com/finance/tracker/MainActivity.kt)
- [`iosApp`](../../compose/iosApp/iosApp/ContentView.swift)
- [`auth-page-spacing-shell`](../../openspec/changes/archive/2026-10-12-auth-page-spacing-shell)

## 相关规格

- [认证页面](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AccessScreens.kt)
- [认证与工作区访问规格](../../openspec/specs/authentication/user-workspace-access/spec.md)
