# Material 3 Foundations

此页按 Material 3 官方 Foundations 导航覆盖通用设计主题。以下内容是面向本仓库 Compose 页面使用的摘要；具体模式、例外与图示以链接页面为准。已核对官方站点目录：2026-10-25。

## 原则、可用性与包容设计

| 主题 | 用于设计判断 | 官方细则 |
|---|---|---|
| Principles 与 usability | 先理解用户要完成的任务，令层级、布局和状态帮助用户判断下一步；不用装饰取代清晰结构。 | [设计原则](https://m3.material.io/foundations/overview/principles)、[可用性概览](https://m3.material.io/foundations/usability/overview) |
| Applying M3 Expressive | 在熟悉、可预测的交互骨架上，用形状、排版、颜色和动效强化重要内容与操作；细则见 [Expressive 专页](expressive.md)。 | [Applying M3 Expressive](https://m3.material.io/foundations/usability/applying-m-3-expressive) |
| Accessibility | 让信息可感知、可操作、可理解；检查文字缩放、颜色对比、触控目标、键盘/焦点、语义标签、读屏次序与动效偏好。Material 控件的默认支持不能替代页面级检查。 | [Assistive technology](https://m3.material.io/foundations/overview/assistive-technology)、[User needs](https://m3.material.io/foundations/building-for-all/user-needs)、[Co-design](https://m3.material.io/foundations/building-for-all/co-design) |
| Contrast | 文字、图标和状态层使用对应的颜色角色；不要仅依赖颜色区分错误、选择或成功。 | [Color contrast](https://m3.material.io/foundations/designing/color-contrast) |

Watch 与 XR 的 Foundations 仍在官网目录中，但不属于此仓库的客户端目标。可把其中的通用可访问性原则作为参考，不据此新增 Wear OS 或 XR 实现。

## 内容与信息结构

| 主题 | 应用方式 | 官方细则 |
|---|---|---|
| Content design | 文案直接帮助用户识别对象、理解状态并完成当前任务；优先使用可扫读的短句。 | [Content design 概览](https://m3.material.io/foundations/content-design/overview) |
| Global writing 与 word choice | 使用地区化且易懂的用词，考虑翻译后的长度、格式和阅读方向。项目中文术语与文案限制仍以仓库规范为准。 | [Global writing](https://m3.material.io/foundations/content-design/global-writing/overview)、[Global word choice](https://m3.material.io/foundations/content-design/global-writing/word-choice)、[Style guide](https://m3.material.io/foundations/content-design/style-guide/ux-writing-best-practices)、[Style-guide word choice](https://m3.material.io/foundations/content-design/style-guide/word-choice)、[Grammar and punctuation](https://m3.material.io/foundations/content-design/style-guide/grammar-and-punctuation) |
| Notifications 与 alt text | 通知应让人迅速判断是否需行动；替代文本描述图像在当前任务中的信息价值，不重复附近文字。 | [Notifications](https://m3.material.io/foundations/content-design/notifications)、[Alt text](https://m3.material.io/foundations/content-design/alt-text) |
| Writing 与文本适配 | 保持短句和清晰语气；缩放与截断要保留任务所需信息，不能让金额、日期或错误原因变得含糊。 | [Writing best practices](https://m3.material.io/foundations/writing/best-practices)、[Text resizing](https://m3.material.io/foundations/writing/text-resizing)、[Text truncation](https://m3.material.io/foundations/writing/text-truncation) |
| Flow、structure、elements | 按任务组织页面区域与步骤，保持主操作可发现，说明必要的内容关系，避免把内部数据模型直接变成界面结构。 | [Designing 概览](https://m3.material.io/foundations/designing/overview)、[Flow](https://m3.material.io/foundations/designing/flow)、[Structure](https://m3.material.io/foundations/designing/structure)、[Elements](https://m3.material.io/foundations/designing/elements) |

## Interaction 与状态

Material 的互动状态包括 enabled、disabled、hovered、focused、pressed、dragged 和 selected 等。只呈现当前组件可达的状态；状态颜色、state layer、反馈时机和读屏语义应一致。选择、开关、输入和拖动还要覆盖状态转换、撤销/取消方式与键盘等价操作。

- [Interaction states 概览](https://m3.material.io/foundations/interaction/states/overview)：状态如何帮助识别当前可操作性。
- [Applying states](https://m3.material.io/foundations/interaction/states/applying-states)：状态与组件交互如何关联。
- [State layers](https://m3.material.io/foundations/interaction/states/state-layers)：状态层如何显示 hover、focus 和 pressed。
- [Gestures](https://m3.material.io/foundations/interaction/gestures)、[Inputs](https://m3.material.io/foundations/interaction/inputs)、[Selection](https://m3.material.io/foundations/interaction/selection)：手势、输入模式与选择交互的基础。

## Layout、适配与 Scaffold

先识别窗口实际可用空间，再安排内容、导航与辅助区域。Material 的断点是通用设计指导；本项目实现必须采用 [UI 设计规范](../../../../docs/ui-design-rules.md)规定的 `COMPACT`、`REGULAR`、`WIDE` 响应式合同及代表宽度，不能照搬另一套断点作为第二个项目系统。

| 子主题 | 设计要点 | 官方细则 |
|---|---|---|
| Layout 与 adaptive design | 区域随可用空间重新编排，而非等比缩放或挤压；列表详情、feed、supporting pane 是常见结构模式。 | [Layout overview](https://m3.material.io/foundations/layout/layout-overview/overview)、[Adaptive design](https://m3.material.io/foundations/layout/layout-overview/adaptive-design)、[Parts of layout](https://m3.material.io/foundations/layout/layout-overview/parts-of-layout)、[Canonical examples](https://m3.material.io/foundations/layout/canonical-examples/overview)、[Feed](https://m3.material.io/foundations/layout/canonical-examples/feed)、[List-detail](https://m3.material.io/foundations/layout/canonical-examples/list-detail)、[Supporting pane](https://m3.material.io/foundations/layout/canonical-examples/supporting-pane) |
| Breakpoints | Material 文档展示 compact、medium、expanded、large、extra-large 等宽度等级；用来理解内容如何改变结构，不替代仓库的三等级合同。 | [Breakpoints 概览](https://m3.material.io/foundations/layout/breakpoints/overview)、[Compact](https://m3.material.io/foundations/layout/breakpoints/compact)、[Medium](https://m3.material.io/foundations/layout/breakpoints/medium)、[Expanded](https://m3.material.io/foundations/layout/breakpoints/expanded)、[Large / extra-large](https://m3.material.io/foundations/layout/breakpoints/large-extra-large) |
| Grid、spacing、density | 用网格、留白和内容密度表达分组与层级；金融数据页面应保留对比和扫描效率，避免为 Expressive 增加无效留白。 | [Grid and spacing](https://m3.material.io/foundations/layout/grids-spacing/overview)、[Grids](https://m3.material.io/foundations/layout/grids-spacing/grids)、[Spacing](https://m3.material.io/foundations/layout/grids-spacing/spacing)、[Density](https://m3.material.io/foundations/layout/grids-spacing/density) |
| `Scaffold` | 结合顶栏、底栏、导航 rail 与 panes 安排页面框架；滚动边界和固定区域必须明确。 | [`Scaffold` 概览](https://m3.material.io/foundations/layout/scaffold/overview)、[Bars](https://m3.material.io/foundations/layout/scaffold/bars)、[Rails](https://m3.material.io/foundations/layout/scaffold/rails)、[Panes](https://m3.material.io/foundations/layout/scaffold/panes) |
| Bidirectionality | 阅读顺序、图标方向、对齐、返回和手势须支持 RTL；不要假定起始边永远是左侧。 | [Bidirectionality / RTL](https://m3.material.io/foundations/layout/bidirectionality-rtl) |

## Customization 与 design tokens

自定义用于延续产品品牌与语义，不应削弱 Material 组件的可识别行为。token 应作为样式决策的可复用名称，避免在每个页面重复散落的数值。此仓库已有单一 Git token 输入与 `FinanceTheme`：不要新增 token 源、重复的组件注册表或生成流程。

- [Customization](https://m3.material.io/foundations/customization)
- [Design tokens 概览](https://m3.material.io/foundations/design-tokens/overview)
- [How to use tokens](https://m3.material.io/foundations/design-tokens/how-to-use-tokens)
- [Material A–Z glossary](https://m3.material.io/foundations/glossary)

## 官网中的专用设备主题

官方 Foundations 还包括 Wear OS 与 XR 的专用页面。它们不扩展本仓库 Android/iOS/Web 目标，但在遇到其跨设备概念时可查阅，避免把平台特例当作通用 Compose M3 合同。

- Wear OS：[概览](https://m3.material.io/foundations/watches/overview)、[Foundations](https://m3.material.io/foundations/watches/foundations)、[Layout](https://m3.material.io/foundations/watches/layout)、[Styles](https://m3.material.io/foundations/watches/styles)。
- XR：[设计概览](https://m3.material.io/foundations/xr/design/overview)、[Accessibility](https://m3.material.io/foundations/xr/design/accessibility)、[Interaction](https://m3.material.io/foundations/xr/design/interaction)、[Layout](https://m3.material.io/foundations/xr/design/layout)、[组件概览](https://m3.material.io/foundations/xr/components/overview)、[App bars](https://m3.material.io/foundations/xr/components/app-bars)、[Dialogs](https://m3.material.io/foundations/xr/components/dialogs)、[Navigation bar](https://m3.material.io/foundations/xr/components/nav-bar)、[Navigation rail](https://m3.material.io/foundations/xr/components/nav-rail)、[Toolbars](https://m3.material.io/foundations/xr/components/toolbars)。
