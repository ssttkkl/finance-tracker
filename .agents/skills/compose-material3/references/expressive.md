# Material 3 Expressive

Material 3 Expressive 是本仓库所有触及 UI 改动的默认方向，包括小范围修复。它扩展 M3 的视觉和交互表达：更灵活的类型、鲜明但有秩序的形状与颜色、弹簧运动，以及更新的组件变体。它不改变用户任务、财务规则或项目行为合同。

## 设计取舍

- 让 Expressive 强化已有信息层级：重点标题、主要操作、选中反馈和结果变化可获得更明确的形状、大小、颜色或动效。
- 控制强调数量。单个视区应有明确主次；不要为了 Expressive 同时加大所有标题、按钮、圆角、动画和空白。
- 保留 Finance 数据的扫描效率、金额精度与语义颜色。余额、涨跌、警告、成功、错误等信息使用项目既有角色，不用彩色装饰替代含义。
- 使用具体 Expressive 变体时，同时核对 baseline 与 Expressive 行为差别、内容适配、响应式和辅助技术语义。组件目录的每项都链接到当前 M3 细则。
- 动效帮助理解操作、位置或状态变化，保持可中断、短促且不过度。减少动态效果的系统偏好、Web 动效能力及实际平台交互都要纳入检查。
- 以 Material 默认尺寸和内容自适应为基础；不通过给按钮或输入框设置固定宽高来“统一 Expressive 尺寸”。

## Compose API 与稳定性检查

Material 官方设计页说明设计方向，不保证 Compose Multiplatform 中已有对应 API。新增或修改 Expressive 组件前，逐项检查：

1. 读取仓库版本目录（`compose/gradle/libs.versions.toml`）、实际使用该 API 的源码集与当前调用点，确认依赖版本和依赖坐标。
2. 查 [Compose Multiplatform Material3 API](https://kotlinlang.org/api/compose-multiplatform/material3/)、[AndroidX Material 3 API](https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary) 和[发布记录](https://developer.android.com/jetpack/androidx/releases/compose-material3)，确认 API 是否存在、参数签名是否变化、状态是否 stable、是否要求 `@OptIn`。
3. 确认 Compose Multiplatform 的 common API 与 Android、iOS、Web 目标状态。AndroidX 当前版本的 API 不必然已进入 JetBrains 对应的 multiplatform artifact；官网展示也不证明三个目标交互相同。
4. 优先用当前依赖中已经存在的 API。若使用实验 API，在实现说明中记录 API/注解、当前版本、稳定性、可能的兼容和跨端差异，并按项目验证门禁验证受影响目标。
5. 不因为设计网站出现新特性而自动增加或升级依赖。若所需 API 不可用，优先保留用户任务和 Compose 共用语义，选择已有 M3 API 或记录确切限制与替代方案。

本仓库已选用 `org.jetbrains.compose.material3:material3:1.12.0-alpha03`；JetBrains 发布表将其映射到 AndroidX Material3 1.5.0-alpha22。这只是当前核对快照，不能代替下一次实现时的版本检查。仓库已有 `ExperimentalMaterial3ExpressiveApi` opt-in 使用点；不据此推断所有 Expressive API 都在任意源码集或平台可用。见[版本与平台说明](compose-multiplatform.md)。
