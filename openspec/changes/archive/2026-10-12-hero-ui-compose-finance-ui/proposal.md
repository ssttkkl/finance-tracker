## Why

当前 Compose Multiplatform 客户端的核心控件直接依赖 Material3，视觉 token、交互原语和业务组件没有清晰的所有权边界。HeroUI 的真实事实源是 `@heroui/styles`、CSS variables 和实际组件 DOM；直接把 Figma Kit 当作 Pen 组件库会丢失变量、variants 和 slots 语义。现在以 Cash Ledger 作为首个闭环，建立“HeroUI 代码/CSS → Pen 参考与变量 → Finance UI → Compose Unstyled”的可审计路线。

## What Changes

- 以 HeroUI v3 官方代码和 `@heroui/styles` CSS 为上游事实源；从 CSS variables 提取浅色、深色语义值，再同步到项目 token 和 Pen variables。
- 从临时 HeroUI Gallery 的真实 DOM 执行 Pen Browser Import，形成可编辑的 `heroui-reference.pen`；清理并组件化为 `heroui.lib.pen`，再构建产品所有的 `finance-ui.lib.pen`。Figma Kit 仅作视觉核对，不能替代真实 DOM 导入。
- 将 `Finance UI` 明确为业务组件库，新增 semantic token、Foundation、Primitive、Composite 和 Cash Ledger 所需的金融组件合同。
- 固定引入 Compose Unstyled `2.10.0`，按需使用 button、text-field、dropdown、dialog 等模块承载交互状态、焦点、键盘和无障碍行为。
- 新增 Screen/Component Contract 与 Registry，维护 HeroUI 参考、Finance 组件到 Compose 符号的可审计映射。
- 将 Cash Ledger 迁移到首批 Finance 组件，覆盖 Button、Input、Select、Table、Filter、Amount，以及加载、空、错误、禁用、成功和响应式状态。
- 以 Web 当前布局为基准，为其余九项页面级功能完成 HeroUI 风格 Pen 设计稿和审查；用户已确认设计稿，本变更继续迁移 F-01、F-02、F-04 至 F-10 的 Compose 页面。
- Finance Primitive 不依赖 Material3；迁移页面通过 Finance 组件承载主要操作、字段、选择器和表面，尚无 Unstyled 对应物的日期、时间及系统对话框保留兼容实现。
- 补充组件级测试、跨端语义测试、Web 生产预览浏览器 QA 和截图审查记录。

## Capabilities

### New Capabilities

- `shared/design-system`: 定义 HeroUI 参考库、Finance UI semantic token、组件合同、Registry 和 Compose Unstyled 原语边界。

### Modified Capabilities

- `cash-ledger/cash-ledger-browser`: Cash Ledger 使用 Finance UI 组件并保持既有账本语义，同时补齐首批组件状态和响应式验收。
- `shared/cross-platform-presentation`: 将同一组件与响应式合同扩展至 F-01、F-02、F-04 至 F-10，保留既有业务流程与语义 ID。

## Impact

- 设计资产：`design/source/heroui-theme.css`、`design/heroui-reference.pen`、`design/heroui.lib.pen`、`design/finance-ui.lib.pen` 和 `design/` 下 F-01 至 F-10 的页面稿；HeroUI Figma Kit 仅作为视觉校验资料。
- Compose：`compose/shared` 的 token、Theme、Finance UI 组件，以及认证、工作区、收支、导入、分类和投资页面；Material3 仅用于尚未替代的系统控件。
- 构建：新增 `com.composables:composeunstyled-*:*` `2.10.0` 依赖及其版本锁定。
- 文档与规格：新增组件/页面契约、Registry、OpenSpec delta、功能地图和验证证据。
- 迁移与回滚：Finance UI 组件按页面边界接入；出现兼容问题时可按页面回退到原控件，不改变领域或持久化行为。
