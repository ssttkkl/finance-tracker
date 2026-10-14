## MODIFIED Requirements

### Requirement: Semantic tokens have one source and compatibility mapping

设计系统 MUST 以 Git 中的结构化 token 文件为唯一输入，提供 `background`、`foreground`、`surface`、`overlay`、`accent`、`danger`、`field` 和 `focus` 等语义角色，并保留现有 token 到语义角色的兼容映射。生成的 Compose Kotlin token MUST 是运行时消费副本；页面结构和样式 MUST 以 Compose 实现为事实源。

#### Scenario: 浅色和深色主题使用相同语义角色

- **WHEN** Finance UI 在浅色或深色主题下渲染同一组件
- **THEN** 组件 MUST 读取相同的语义角色名称
- **AND** 每种主题 MUST 为颜色、对比度、禁用和焦点状态提供确定值

### Requirement: HeroUI code is the upstream reference

设计系统 MUST 以 HeroUI 官方代码、`@heroui/styles` 和 CSS variables 作为上游视觉与 token 语义参考；Finance 产品组件 MUST 通过已记录的组件合同和 Compose 实现维护，不得依赖 Pen Browser Import、`.pen` 组件库或 Pen variables。Figma Kit MAY 用于视觉参考，但 MUST NOT 取代 Git token 文件或 Compose 实现成为项目事实源。

#### Scenario: Implement a Finance component from the upstream reference

- **WHEN** 新增或调整 Finance 组件
- **THEN** 实现 MUST 使用 Git token 和已记录的 Finance 组件合同
- **AND** 页面视觉结果 MUST 由 Compose 实现表达并通过演示构建和 QA 截图检查
