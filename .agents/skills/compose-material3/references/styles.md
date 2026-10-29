# Material 3 Styles

Material 样式系统包含颜色、字体、图标、运动、形状、间距与 elevation。本页覆盖官网 Styles 当前目录；具体规范、token 名称和图例以官网页面为准。已核对日期：2026-10-25。

## Color

| 内容 | 设计判断 | 官方细则 |
|---|---|---|
| 颜色系统与 roles | 用 `primary`、`secondary`、`tertiary` 承载不同强调级别；`surface`、`surface container` 与对应 `on*` 角色建立层级和文字对比。只使用合理的前景/背景配对。 | [Color roles](https://m3.material.io/styles/color/roles)、[System overview](https://m3.material.io/styles/color/system/overview)、[How the system works](https://m3.material.io/styles/color/system/how-the-system-works) |
| 选择与生成方案 | 颜色方案由关键色、色调调色板和 light/dark scheme 组成；品牌方案应检查全部角色的对比。 | [Choosing a scheme](https://m3.material.io/styles/color/choosing-a-scheme)、[Resources](https://m3.material.io/styles/color/resources)、[Baseline](https://m3.material.io/styles/color/static/baseline)、[Custom brand](https://m3.material.io/styles/color/static/custom-brand) |
| Dynamic color | 支持平台和产品合同允许时，可从系统个性化或内容来源产生方案；不要假设 iOS 与 Web 提供 Android 同样的系统动态色 API。 | [Dynamic color](https://m3.material.io/styles/color/choosing-a-scheme)、[Choosing a source](https://m3.material.io/styles/color/dynamic/choosing-a-source)、[User-generated source](https://m3.material.io/styles/color/dynamic/user-generated-source)、[Content-based source](https://m3.material.io/styles/color/dynamic/content-based-source) |
| 进阶调整 | 调色板或已有颜色的调整须维持角色关系、浅色/深色映射和无障碍对比；项目业务状态优先使用既有语义 token。 | [Advanced color](https://m3.material.io/styles/color/advanced/overview)、[Apply colors](https://m3.material.io/styles/color/advanced/apply-colors)、[Define new colors](https://m3.material.io/styles/color/advanced/define-new-colors)、[Adjust colors](https://m3.material.io/styles/color/advanced/adjust-existing-colors) |

项目的 `FinanceTheme` 与 token 规则见 [UI 设计规范](../../../../docs/shared/ui-design-rules.md) 和 [设计系统主规格](../../../../openspec/specs/shared/design-system/spec.md)。M3 颜色角色可说明通用角色，但不可覆盖项目成功、警告、错误、焦点等语义颜色映射。

## Typography

M3 type scale 以 `display`、`headline`、`title`、`body`、`label` 五类角色表达用途，并提供 `large`、`medium`、`small`。显示和标题层级可表达重点；长正文与密集数据以易读性为先。使用字体时检查语言字形覆盖、字号缩放、行高、裁切与跨平台字重差异；表格、余额等易变化数值优先采用等宽数字以减少视觉跳动。

- [Typography overview](https://m3.material.io/styles/typography/overview)
- [Type scale tokens](https://m3.material.io/styles/typography/type-scale-tokens)
- [Applying type](https://m3.material.io/styles/typography/applying-type)
- [Fonts](https://m3.material.io/styles/typography/fonts)
- [Editorial treatments](https://m3.material.io/styles/typography/editorial-treatments)

本项目沿用 `FinanceTheme` 字体和现有 token。Expressive 字体处理应有意突出少数重点，不把装饰性字体用于长文本或关键金额。

## Icons

图标承担直观识别与短距离扫描，非通用操作仍需文字标签；仅有图标的按钮须有明确无障碍名称。图标的视觉重量、网格、笔画和光学对齐要与周边类型匹配。

- [Icons overview](https://m3.material.io/styles/icons/overview)
- [Designing icons](https://m3.material.io/styles/icons/designing-icons)
- [Applying icons](https://m3.material.io/styles/icons/applying-icons)

## Motion

| 主题 | 应用方式 | 官方细则 |
|---|---|---|
| Physics 与方案 | Expressive springs 是默认方向；空间移动可有受控回弹，颜色/透明度等效果动画避免不合适的过冲。统一运动风格，少数特殊时刻再按任务调整。 | [Motion physics](https://m3.material.io/styles/motion/overview/how-it-works)、[Specs](https://m3.material.io/styles/motion/overview/specs) |
| Transitions | 转场表达页面、层级和对象的连续关系；只在帮助理解状态变化时使用。 | [Transition patterns](https://m3.material.io/styles/motion/transitions/transition-patterns)、[Applying transitions](https://m3.material.io/styles/motion/transitions/applying-transitions) |
| Easing 与 duration | 既有 duration/easing 规范与新 spring 方案都要按当前组件 API 状态核实；不要将旧 token 规则直接套到新实验 API。 | [Applying easing and duration](https://m3.material.io/styles/motion/easing-and-duration/applying-easing-and-duration)、[Specs](https://m3.material.io/styles/motion/easing-and-duration/tokens-specs) |

Finance 操作中的运动须保持及时、可中断、可预测，不得延迟确认结果、遮挡金额变化或增加重复步骤。动效偏好和平台输入方式见 [Expressive](expressive.md) 与 [跨端指引](compose-multiplatform.md)。

## Shape

形状可以提示组件角色、分组和状态。采用形状尺度维持整体节奏；Expressive 的不对称、对比形状或 morph 用于强化重点，避免每个容器都竞争注意力。

- [Shape principles](https://m3.material.io/styles/shape/overview-principles)
- [Corner radius scale](https://m3.material.io/styles/shape/corner-radius-scale)
- [Shape morph](https://m3.material.io/styles/shape/shape-morph)

Compose 的可用 shape 参数与 `MaterialTheme.shapes` 以实际 Material3 API 为准，项目样式事实源仍是 Compose 与 `FinanceTheme`。

## Spacing 与 elevation

用一致间距建立分组，组件内部间距依组件默认值，页面间距服从项目布局合同。Elevation 主要通过表面色调区分层次，阴影用于真实浮层关系；不要为了“有层次”把每个卡片都抬高。

- [Spacing overview](https://m3.material.io/styles/spacing/overview)、[Applying spacing](https://m3.material.io/styles/spacing/applying-spacing)、[Spacing tokens](https://m3.material.io/styles/spacing/tokens)
- [Elevation overview](https://m3.material.io/styles/elevation/overview)、[Applying elevation](https://m3.material.io/styles/elevation/applying-elevation)、[Elevation tokens](https://m3.material.io/styles/elevation/tokens)
