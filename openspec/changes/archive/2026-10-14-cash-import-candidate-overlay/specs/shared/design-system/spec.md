## MODIFIED Requirements

### Requirement: Semantic tokens have one source and compatibility mapping

设计系统 MUST 以 Git 中的结构化 token 文件为唯一输入，提供 `background`、`foreground`、`surface`、`overlay`、`accent`、`danger`、`warning`、`field` 和 `focus` 等语义角色，并保留现有 token 到语义角色的兼容映射；Pen 变量和生成的 Kotlin 文件 MUST 是消费副本。状态色 MUST 为错误、成功和警告状态提供可区分的背景与前景角色。

#### Scenario: 浅色和深色主题使用相同语义角色

- **WHEN** Finance UI 在浅色或深色主题下渲染同一组件
- **THEN** 组件 MUST 读取相同的语义角色名称
- **AND** 每种主题 MUST 为颜色、对比度、禁用和焦点状态提供确定值

#### Scenario: 待确认关系使用警告状态色

- **WHEN** Compose 账单导入页显示待确认的关系
- **THEN** 状态标签 MUST 使用语义警告前景色和警告背景色
- **AND** 两种颜色 MUST 从浅色或深色 token 文件生成
