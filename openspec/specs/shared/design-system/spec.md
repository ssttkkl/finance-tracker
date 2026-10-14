# design-system Specification

## Purpose

为 Compose Finance Tracker 提供以 Material 3、`FinanceTheme` 和 Git token 为基础的可审计设计系统。Compose 页面直接使用 Material 3 交互控件；FinanceTheme 保留项目颜色、字体与语义 token。

## Requirements

### Requirement: Compose pages call Material 3 controls directly

Compose 页面 MUST 在调用位置直接使用 Material 3 按钮和文本输入控件，不得使用 Finance 按钮/输入框包装层或 Compose Unstyled 交互控件。按钮 MUST 使用适当的 Material 3 filled、outlined、tonal 或 text 控件；文本输入 MUST 使用 Material 3 `TextField` 或 `OutlinedTextField`。调用点 MUST 显式表达业务回调、状态、字段语义和校验行为。控件尺寸 MUST 沿用 Material 3 默认行为并按内容自适应；调用点 MUST NOT 仅为统一尺寸而固定控件宽高或最小宽高。父布局可按需要约束控件填充空间，响应式容器可使用最大宽度。

#### Scenario: Cash Ledger renders a primary action

- **WHEN** 收支账本需要新增记录操作
- **THEN** 页面 MUST 直接调用 Material 3 按钮
- **AND** 按钮 MUST 保留业务回调、启用状态、标签、无障碍信息与稳定语义 ID

#### Scenario: Control dimensions follow Material 3 defaults and content

- **WHEN** Material 3 控件在不同文案长度和窗口宽度下布局
- **THEN** 控件 MUST 使用 Material 3 默认尺寸与内容自适应行为
- **AND** 调用点 MUST NOT 用固定宽高或最小宽高压缩标签或覆盖组件默认尺寸
- **AND** 父布局 MUST 只施加完成当前布局所需的填充或响应式最大边界

#### Scenario: Text input retains field behavior

- **WHEN** 使用者在认证、账本、导入或管理页面输入内容
- **THEN** 调用点 MUST 直接使用 Material 3 文本输入控件并保留标签、输入类型、校验、错误和禁用行为
- **AND** 输入 MUST 保留原有单行/多行约束、状态更新、语义 ID、失焦处理与可访问名称
- **AND** 普通字段 MUST 明确设置为单行；备注与描述等原多行字段 MUST 保持多行

### Requirement: FinanceTheme preserves project colors and typography

`FinanceTheme` MUST 将现有 Finance 颜色、字体和 Material 3 color scheme/typography 提供给 Compose 控件。调用点可读取 Git token 表达 Material 3 默认方案未涵盖的业务状态；不得另造独立的全局色彩或字体系统。

#### Scenario: Light and dark themes use shared semantic roles

- **WHEN** Finance UI 在浅色或深色主题下渲染同一控件
- **THEN** 控件 MUST 从当前 `FinanceTheme` 解析颜色与字体
- **AND** 错误、成功、警告、禁用和焦点状态 MUST 使用确定的语义角色

### Requirement: Git token files are the only token input

设计 token MUST 以 Git 中的结构化 token 文件为唯一输入，提供 `background`、`foreground`、`surface`、`overlay`、`accent`、`danger`、`warning`、`field` 和 `focus` 等语义角色。生成的 Compose Kotlin token MUST 是运行时消费副本；页面结构、样式和 Material 3 控件选择 MUST 以 Compose 实现为事实源。

#### Scenario: Token changes are consumed by Compose

- **WHEN** token 文件修改浅色或深色主题中的语义颜色
- **THEN** token 生成结果 MUST 更新 Compose Kotlin 消费副本
- **AND** Compose 控件 MUST 使用同一语义角色，不得依赖并行设计资源作为运行时来源

### Requirement: Shared Finance display helpers remain addressable

设计注册表 MUST 记录仍由 Compose 多页面共享的 FinanceTheme、选择器、过滤区、数据表和金额展示辅助函数，并能解析其代码位置与状态合同。注册表 MUST NOT 要求按钮或文本输入使用 Finance 包装组件。

#### Scenario: Resolve a shared Finance display helper

- **WHEN** 页面需要使用共享数据表或金额展示
- **THEN** 注册表 MUST 返回其 Compose 实现与适用状态
- **AND** 页面 MUST 继续直接使用 Material 3 控件完成按钮和输入任务
