# shared/design-system Specification

## Purpose

为 Finance Tracker 建立由 Git token、HeroUI 官方代码/CSS、Pen 参考、组件合同和 Compose 实现共同组成的可审计设计系统；HeroUI 提供视觉与 token 语义参考，Finance UI 拥有产品组件，Compose Unstyled 只承担交互原语。

## ADDED Requirements

### Requirement: Finance UI owns product components

产品页面 MUST 通过 `Finance/*` 组件或已登记的金融组件使用按钮、字段、选择器、表格和金额展示；页面不得直接依赖 `HeroUI/*` 组件名称，也不得为已有 Finance 组件创建页面私有副本。

#### Scenario: Cash Ledger renders a primary action

- **WHEN** Cash Ledger 需要新增记录操作
- **THEN** 页面 MUST 使用 Registry 中登记的 `Finance/Button` 到 `FinanceButton` 映射
- **AND** 视觉状态 MUST 来自 Finance token，交互行为 MUST 来自 Compose Unstyled button 原语

### Requirement: Semantic tokens have one source and compatibility mapping

设计系统 MUST 以 Git 中的结构化 token 文件为唯一输入，提供 `background`、`foreground`、`surface`、`overlay`、`accent`、`danger`、`field` 和 `focus` 等 semantic role，并保留现有 token 到 semantic role 的兼容映射；Pen 变量和生成的 Kotlin 文件 MUST 是消费副本。

#### Scenario: Light and dark themes resolve the same role

- **WHEN** Finance UI 在浅色或深色主题渲染同一组件
- **THEN** 组件 MUST 读取相同的 semantic role 名称
- **AND** 每个主题 MUST 提供确定的颜色、对比度和禁用/焦点状态

### Requirement: HeroUI code is the upstream reference

设计系统 MUST 以 HeroUI 官方代码、`@heroui/styles` 和 CSS variables 作为上游视觉与 token 语义参考；临时 Gallery 的真实 DOM MUST 经 Pen Browser Import 形成可编辑的 `heroui-reference.pen`，再组件化为 `heroui.lib.pen`，供 `finance-ui.lib.pen` 构建产品组件。Pen variables MUST 映射版本固定的浅色、深色 CSS variables。Figma Kit MUST 只用于视觉核对，不得替代真实 DOM 或作为 token 来源。

#### Scenario: Browser Import is unavailable

- **WHEN** Pen Browser Import 无法取得真实组件的可编辑图层
- **THEN** 设计资产与依赖其确认的 UI 实施 MUST 标记为未完成
- **AND** tasks MUST 记录阻断原因与补跑条件，不得将 Figma 导入或手工占位稿计为等价结果

### Requirement: Component contracts and Registry are addressable

每个首批 Finance 组件 MUST 记录名称、变体、尺寸、状态、插槽、语义 ID 和 Compose 符号；Registry MUST 能从 `Finance/Button`、`Finance/TextField`、`Finance/Select`、`Finance/DataTable`、`Finance/Amount` 查询到唯一映射。

#### Scenario: Agent resolves a design component

- **WHEN** Screen Contract 引用 `Finance/AccountSelector`
- **THEN** Registry MUST 返回对应 Compose 组件和所需状态/插槽合同
- **AND** 生成或实现页面不得猜测为任意通用输入框

### Requirement: Unstyled primitives own interaction semantics

存在对应 Compose Unstyled 原语的 Finance Primitive MUST 使用 `2.10.0` 原语承载焦点、键盘、选择、禁用和无障碍语义；Finance 层只负责 token、布局、文案和产品 API。复杂数据表格、金额展示和金融选择器可以组合 Foundation 与已登记原语。

#### Scenario: Disabled field preserves semantics

- **WHEN** Finance TextField 被业务状态禁用
- **THEN** Compose Unstyled 的禁用语义 MUST 传递到控件
- **AND** Finance 样式 MUST 同时展示禁用视觉状态，不得只降低透明度而保留可操作性
