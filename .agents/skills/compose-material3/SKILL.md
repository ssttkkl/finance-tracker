---
name: compose-material3
description: "为本仓库 Compose Multiplatform 页面设计、实现或审查 Material 3 UI；以 Material 3 Expressive 为默认方向，并核对 Android、iOS、Web 的 API 与行为差异。"
---

# Compose Material 3

在本仓库进行 Compose UI 设计、原型、实现或审查时自动应用；也可显式调用 `$compose-material3`。本技能覆盖 Compose Multiplatform 的共享 Material 3 语义，以及 Android、iOS、Web 的实现差异；不覆盖 Flutter 或 `@material/web`。

设计规则以 [Material 3 官方站点](https://m3.material.io/) 为准，API、平台支持和稳定状态以当前 JetBrains Compose Multiplatform / AndroidX 文档与仓库实际依赖为准。官方目录在 [Foundations](https://m3.material.io/foundations/)、[Styles](https://m3.material.io/styles/) 和 [Components](https://m3.material.io/components/)；参考资料按任务需要读取：

- [Foundations](references/foundations.md)：可访问性、内容、状态、布局、适配与 token。
- [Styles](references/styles.md)：颜色、字体、图标、运动、形状、间距和 elevation。
- [Components](references/components.md)：完整官方组件页目录、用途、变体、常用 Compose API、最小调用与状态。
- [Expressive](references/expressive.md)：默认视觉方向、使用边界和实验 API 检查方式。
- [Compose Multiplatform](references/compose-multiplatform.md)：版本核对、Android/iOS/Web 支持边界和跨端审查。

**官方页面阅读门禁（必做）：**开始设计或审查前，必须打开并阅读与任务直接相关的 Material 3 官方页面正文；只看本地摘要、链接标题或搜索摘要不算。组件任务要读对应组件的 overview 与 specs；Foundations 或 Styles 任务要读涉及的主题页；实现 API 时还要读当前 JetBrains Compose Multiplatform / AndroidX API 或发布记录。Material 网站依赖 JavaScript，普通抓取只返回导航壳时须用真实浏览器加载正文；仅收到 HTTP 200 不算完成阅读。失效链接从官方站内导航定位并修正，不根据路径猜内容。读完后在对应 `design.md` 或 `tasks.md` 简记页面标题和本次采用的规则。每项任务只需读实际涉及的页面，无需遍历整站。页面无法访问时记录未完成；若设计决策依赖该内容，不得声称已核验或完成该项设计审查。

所有触及的 UI 改动（含小范围修复）默认采用 M3 Expressive 的视觉与交互原则，同时保留原任务范围、财务信息密度和项目行为合同。实现前先读 [项目 UI 设计规范](../../../docs/ui-design-rules.md)、相关 OpenSpec 与现有 token；这些仓库规则优先于通用 Material 建议。按钮与输入直接调用 Material 3，不增加 Finance 包装层；沿用组件默认尺寸，不为统一外观固定控件宽高。Compose 页面是结构与样式事实源，`FinanceTheme` 和 Git token 继续承担项目颜色、字体及语义角色。

动手前检查 `compose/gradle/libs.versions.toml` 与当前调用点，确认 API 是否已由实际依赖提供、是否为实验 API、是否在 `commonMain` 可用，以及 Web/iOS 是否需要差异处理。可使用已有依赖支持的实验 API，但须说明稳定性和跨端风险；不为追求 Expressive 自动新增或升级依赖。实现中优先使用 Material 默认行为与状态语义，明确组件用途、变体、选中/禁用/焦点/加载/错误等相关状态，并核对无障碍名称、键盘或触控操作与响应式布局。

组件逐项细则见组件目录对应 M3 官方页面。官方设计页与当前 Compose API 不是同一事实源：若实现能力、稳定性或平台目标不确定，查对应 JetBrains API 页面及 [AndroidX Material 3 发布记录](https://developer.android.com/jetpack/androidx/releases/compose-material3)，再以当前项目编译目标作判断。不要照抄官网文本或将其转成另一套本地组件库。
