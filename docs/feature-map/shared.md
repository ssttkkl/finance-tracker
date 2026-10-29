# 共享规则与工程证据

[返回产品总览](../feature-map.md) · [跨端覆盖与待办](../feature-map.md#跨端覆盖与待办)

本模块当前 **没有独立页面**。账本记录、时间、跨端展示、设计系统和演示模式由已有页面共同使用，见 [共享规则规格](../../openspec/specs/shared/README.md)。

## 工程与导航入口

Compose Web、Android、iOS 共用 `compose/shared/src/commonMain`，工程入口分别为：

- [Web 工程](../../compose/webApp/) 与 [构建配置](../../compose/webApp/build.gradle.kts)
- [Android](../../compose/androidApp/src/main/kotlin/com/finance/tracker/MainActivity.kt)
- [iOS](../../compose/iosApp/iosApp/ContentView.swift)

页面路由和主要导航由 [路由解析](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/core/AppRouting.kt)、[主导航](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/app/AppNavigation.kt) 与 [页面分派](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/app/AppShell.kt) 决定。页面代码入口见各模块的实现证据，Web QA 位于 [浏览器测试目录](../../compose/qa/web/tests)。

## 跨页面呈现

F-01／F-02／未登录 F-06 共用登录式响应布局与居中卡片；已登录 F-06 保留 Scaffold 标题。F-03 至 F-10 的应用内任务由共享 `DestinationScaffold` 承载，F-05 使用全屏对话框与独立标题／关闭入口，其余任务使用应用顶栏标题。入口一致性见 [入口呈现](../../openspec/changes/archive/2026-10-12-app-shell-entry-presentation)。

桌面侧栏默认显示图标，鼠标进入展开、离开收回；鼠标点击导航产生的焦点不阻止收回，展开时可立即点击收起。键盘焦点可展开侧栏。头像悬停打开账号面板且停留时保持稳定，Enter／Space 可打开，Escape 关闭并把焦点返回头像。工作区切换、管理和退出位于侧栏底部，证据见 [侧栏折叠](../../openspec/changes/archive/2026-10-13-navigation-rail-collapse/tasks.md) 和 [账号面板](../../openspec/changes/archive/2026-10-13-account-menu-hover-stability/tasks.md)。

全局 `FinanceTheme` 将现有 token 映射到 `surfaceContainer*`，影响各 Compose 平台的 Material 3 默认容器色。按钮和文本输入直接使用 Material 3，保留 `FinanceDataTable`／`Amount` 展示辅助函数，见 [组件 Registry](../design-system/registry.yaml) 与 [UI 设计规范](../ui-design-rules.md)。具体视觉尺寸与实现细节集中于设计规范和各模块链接的实现记录。

## 跨端合同与验收边界

Web、Android 和 iOS 共用行为、文案、状态、语义和业务结果。导航、文件选择器及窗口呈现可以不同，差异和共同不变量以 [跨端展示规格](../../openspec/specs/shared/cross-platform-presentation/spec.md) 与各业务主规格为准。共享实现覆盖要求真实 Compose 入口及对应 API 流程；仅有后端接口、文案、原型、规格或不可达导航均不算实现。共享代码不代表每个 target 的设备 QA 已覆盖。

## Web 已知缺陷与历史验证

当前 Compose Web `1.12.1` 关闭详情弹层后无障碍树不恢复，关联 [CMP-10623](https://youtrack.jetbrains.com/issue/CMP-10623)；另保留禁用状态语义缺陷。保持当前依赖版本，Web 返回与无障碍 QA 保留未通过状态，Native 另行验证。

浏览器用例位于 [`compose-access.e2e.ts`](../../compose/qa/web/tests/compose-access.e2e.ts)、[`compose-pages.e2e.ts`](../../compose/qa/web/tests/compose-pages.e2e.ts)、[`compose-live-demo.e2e.ts`](../../compose/qa/web/tests/compose-live-demo.e2e.ts) 和 [`compose-locale.e2e.ts`](../../compose/qa/web/tests/compose-locale.e2e.ts)。2026-10-17 Compose Web 生产预览完整运行 67 项 Playwright 用例并通过，包含账本三尺寸、分页和导入浅色／夜间流程；诊断无浏览器 console error、failed request 或静态资源 HTTP 错误，Wasm MIME 为 `application/wasm`。详细截图、视口、命令与限制见 [`compose-cash-import-account-mapping-redesign/tasks.md`](../../openspec/changes/archive/2026-10-17-compose-cash-import-account-mapping-redesign/tasks.md)。Web 字体使用同源 Noto Sans SC 资源；Compose Playwright fixture 检查 UI／API 请求，本地 registration E2E 另通过 FastAPI demo 实测，两者都不替代 FastAPI 后端契约测试。

历史浏览器结果不能替代各模块当前记录的待验收范围，也不能据此认定代码已部署。
