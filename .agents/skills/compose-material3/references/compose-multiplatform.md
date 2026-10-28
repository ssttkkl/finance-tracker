# Compose Multiplatform 中的 Material 3

Material 3 的设计合同由 [m3.material.io](https://m3.material.io/) 定义；Material3 Compose API 则由 AndroidX 与 JetBrains 发布。Compose Multiplatform 使用共享 Compose API，但具体 API 集合和输入/窗口行为会随版本与平台变化。此处版本快照核对日期：2026-10-25。

## 当前仓库基线

| 项 | 当前配置 | 使用时要复核 |
|---|---|---|
| Compose Multiplatform Gradle 插件 | `1.12.1` | 查看版本目录与兼容说明。插件版本和 Material3 版本分开管理。 |
| Material3 | `org.jetbrains.compose.material3:material3:1.12.0-alpha03` | JetBrains release table 对应 AndroidX Material3 `1.5.0-alpha22`。由于是 alpha，API 可能新增、变化或退出实验状态。 |
| 源码集 | 共享 UI 位于 `compose/shared/src/commonMain` | 只有 common API 可直接作为共享页面实现；平台代码应登记语义一致且必要的平台差异。 |
| 目标 | Android、iOS、Web | Web 目标的 Compose Multiplatform 当前为 Beta；浏览器渲染、键盘、滚动、焦点与无障碍须按目标验证。 |
| Adaptive Material3 | 当前版本目录没有 `material3-adaptive` 或 `material3-adaptive-navigation-suite` | 这属于需显式添加的独立依赖。先确认版本/目标支持及任务必要性，不将其描述为已安装。 |

依赖版本以 [仓库版本目录](../../../../compose/gradle/libs.versions.toml) 为准。M3 Expressive 相关 `ExperimentalMaterial3ExpressiveApi` 使用可在 `CashLedgerScreen.kt` 等源码中查证；逐 API 状态仍需查编译实际使用的版本。

## 支持边界

JetBrains 将 multiplatform Material3 API 放在 `androidx.compose.material3` 包名下，并在构建元数据中为 Android 使用 AndroidX artifact、为其他目标提供 JetBrains 发布的实现。使用的模块坐标、版本和目标不能凭包名推断；共享 API 通常应从 `commonMain` 直接可见。

- [Compose Multiplatform Material3 API 目录](https://kotlinlang.org/api/compose-multiplatform/material3/)
- [AndroidX Material3 API Reference](https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary)
- [AndroidX Material3 release notes](https://developer.android.com/jetpack/androidx/releases/compose-material3)
- [Compose Multiplatform 与 Jetpack Compose](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-and-jetpack-compose.html)
- [Multiplatform Jetpack libraries 的打包方式](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-jetpack-libraries.html)
- [Android-only Compose APIs](https://kotlinlang.org/docs/multiplatform/compose-android-only-components.html)

## 平台差异复核

| 目标 | 复核重点 |
|---|---|
| Android | Material 状态层可使用 Android ripple 与系统主题能力；动态色等系统能力需按 Android 版本判断。返回手势、edge-to-edge、Window insets、触控和系统字体缩放影响布局。 |
| iOS | 共享 Compose 组件仍绘制为 Compose UI；检查 UIKit safe area、键盘/IME、返回/关闭习惯、触控手势冲突、读屏和文本字体指标。必要的平台集成使用现有 iOS interop。 |
| Web | 检查浏览器窗口缩放、`Tab`/Shift+`Tab`、Enter/Space、焦点环、菜单定位、滚动容器、浏览器缩放与语义树。不要默认把 M3 API 等同于原生 HTML 控件；Web Beta 状态需在当前官方兼容页复核。 |

Material 组件共享 API 和状态合同，但弹窗、菜单、下拉、对话框、IME、滚动过度效果、字体渲染和系统导航可能有平台差异。任何差异须服从仓库 `presentation` 契约与 [跨端实现规则](../../../../AGENTS.md#ui-与跨平台门禁)；不能静默改变用户可见语义。

## Adaptive API 边界

Material3 Adaptive 与普通 `material3` 是不同依赖边界。`ListDetailPaneScaffold`、`SupportingPaneScaffold`、`NavigationSuiteScaffold` 等能力只有在相应 artifact 已安装、API 已对当前目标发布时才能使用。项目响应式主合同仍为 `COMPACT` / `REGULAR` / `WIDE`，必要时可按布局采用 Compose `WindowSizeClass`；不能仅因官网有 adaptive 示例，就擅自引入依赖或改写项目断点。

需要这类能力时核对 [AndroidX Material3 Adaptive API](https://developer.android.com/reference/kotlin/androidx/compose/material3/adaptive/package-summary)、[`NavigationSuiteScaffold` API](https://developer.android.com/reference/kotlin/androidx/compose/material3/adaptive/navigationsuite/NavigationSuiteScaffold.composable)、[Adaptive release notes](https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive) 与当前版本目录。Compose Multiplatform 对这些独立 artifact 的发布边界见 [JetBrains multiplatform libraries 说明](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-jetpack-libraries.html)。
