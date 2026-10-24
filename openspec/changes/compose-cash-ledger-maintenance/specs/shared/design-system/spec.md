## MODIFIED Requirements

### Requirement: FinanceTheme preserves project colors and typography

`FinanceTheme` MUST 将现有 Finance 颜色、字体和 Material 3 color scheme/typography 提供给 Compose 控件。调用点可读取 Git token 表达 Material 3 默认方案未涵盖的业务状态；不得另造独立的全局色彩或字体系统。交互蓝 MUST 保留现有蓝色风格，按浅深主题分别配置与前景配对；按钮小字及页面操作文字对其背景至少达到 4.5:1，焦点边界对字段背景至少达到 3:1。

#### Scenario: Light and dark themes use shared semantic roles

- **WHEN** Finance UI 在浅色或深色主题下渲染同一控件
- **THEN** 控件 MUST 从当前 `FinanceTheme` 解析颜色与字体
- **AND** 错误、成功、警告、禁用和焦点状态 MUST 使用确定的语义角色

#### Scenario: 蓝色操作在浅深主题中可读

- **WHEN** 蓝色按钮、操作文字和焦点边界在当前主题中展示
- **THEN** 蓝色与其前景及页面背景分别满足文字与边界对比要求
- **AND** 所有颜色从 Git token 和生成副本消费，不在页面硬编码补丁或将浅色蓝直接套入深色文字
