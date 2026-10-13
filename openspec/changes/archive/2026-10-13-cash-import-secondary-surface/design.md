# Design: 共用二级页面容器与账单导入

## 状态

设计稿和 HTML 交互原型已按使用者反馈更新；使用者确认原型更新后可直接视为同意并开始实现。当前没有未决的产品问题。

## 目标与信息架构

账单导入从收支账本打开，呈现为账本上的二级页面。统一容器在标题栏提供返回或关闭控制，正文插槽承载导入步骤，页脚承载当前步骤操作。Regular/Wide 将账本作为不可交互背景，显示居中模态浮层；Compact 使用覆盖窗口的全屏页。关闭、返回或离开确认前的导入时回到账本并重置临时会话。

信息层级：

1. 容器标题「导入账单」及尺寸对应的返回/关闭控制。
2. 四步进度：文件、账户、核对、配对。
3. 当前步骤标题、表单或流水/关系内容，以及就地错误反馈。
4. 固定在容器底部的上一步/下一步/确认导入操作。

**将删除的冗余信息：**删除全局侧栏中的账单导入一级入口；不再用一级目的地顶栏重复容器标题；移除核对页标题下的流水总数，移除配对说明以及筛选器上方的自动匹配/待确认汇总。筛选器自身的计数保留。待配对流水使用一个始终可见的下拉框，不重复显示空态标题、说明文字和独立选择按钮。保留各步骤执行任务必需的标题、文件信息、校验反馈和最终确认。

## Hallmark 方向与设计自检

- 预检发现仓库已有 Finance token 文件、Finance 组件库和 `Noto Sans SC` 字体；沿用它们，不引入新字体、色板或视觉主题。
- 方向：清晰克制、边线分区；沿用冷白表面、炭色正文和少量蓝色作为主操作、当前步骤与键盘焦点信号。页面强调导入任务本身，不加入品牌装饰、渐变、冗余说明或多余卡片层级。
- 宏观结构选择 **Narrative Workflow**：导入是实际四阶段任务，顺序是选择文件、映射账户、核对流水、关系配对；阶段指示和按步推进承载这一工作流。最近一次 Hallmark 记录使用 `Index-First`，本稿因此采用不同的结构类型，而不是只替换配色。
- 自评（Hallmark 六轴）：`P5 H5 E4 S5 R5 V4`。执行细节仍需通过真实浏览器、键盘和三种尺寸截图检查；变化度来自四阶段处理流，不靠新增装饰。
- 自检：一个当前任务标题、一个主操作、一次性的离开丢弃说明；空文件、焦点、禁用、字段错误、加载、关系待确认、无待确认结果与成功状态有清楚差异；配对操作提供至少 44 dp 的选择控件，小屏变为单列。
- 原型中的文件名、账户名、日期、金额与记录数均为示意占位；列表共 3 条示例流水，筛选计数与其一致。正式页面显示当前导入文件解析所得内容。
- 维护 Finance UI token，不采用 Hallmark 通用营销站模板中的异色或字体。当前运行时没有可调用的 Hallmark 专用技能动作；本次在更新的原型、Pen 画板与生产截图上按 `audit.md` 和 `slop-test.md` 执行人工替代审查，并在 `tasks.md` 记录结果，不将其表述为 Hallmark 工具已执行。生产 UI 未见未解决 finding。

## Pen 设计稿

- 页面稿：[`design/cash-import-secondary-surface.pen`](../../../design/cash-import-secondary-surface.pen)。三个代表画板分别是 `RwvTd`（COMPACT 390 × 844）、`N9VR9`（REGULAR 768 × 1024）、`YGZoE`（WIDE 1440 × 900）；状态参考为 `T3hnGi`。页面导入使用共享组件库中的 `Finance/SecondaryPageContainer`（组件节点 `kIuwj`）。
- 四步内容状态：Compact 画板显示文件选择空态，Regular 画板显示账户映射错误与焦点，Wide 画板显示关系配对；`Review step content reference` 与 `Cash import feedback state reference` 放在画布外的状态参考区，不重复增加窗口尺寸画板。
- 共享库：[`design/finance-ui.lib.pen`](../../../design/finance-ui.lib.pen)。容器子节点：`D:vwsV7` 标题栏、`D:INDJu` 返回按钮、`D:U2t03` 标题、`D:wu0zD` 关闭按钮、`D:p88n3y` 分隔线、`D:X77ia` 内容插槽。Compact 实例显示返回控制并铺满窗口；Regular/Wide 实例显示关闭控制和模态浮层。
- Pen 截图证据：[`prototype/screenshots/pen-390.png`](prototype/screenshots/pen-390.png)、[`prototype/screenshots/pen-768.png`](prototype/screenshots/pen-768.png)、[`prototype/screenshots/pen-1440.png`](prototype/screenshots/pen-1440.png)、[`prototype/screenshots/pen-review-state.png`](prototype/screenshots/pen-review-state.png)、[`prototype/screenshots/pen-feedback-states.png`](prototype/screenshots/pen-feedback-states.png)。Regular/Wide 画板体现约 90% 视口模态尺寸；Wide 关系列表使用完整可用宽度，待确认项合并为一个下拉框。
- 原型：[`prototype/index.html`](prototype/index.html)，只依赖本地文件，不请求网络、后端或凭据。默认从文件步骤开始；`?step=accounts` 与 `?step=relations` 是对应 Pen 画板状态的可复现入口。
- 浏览器截图：[`prototype/screenshots/browser-390.jpg`](prototype/screenshots/browser-390.jpg)、[`browser-768.jpg`](prototype/screenshots/browser-768.jpg)、[`browser-1440.jpg`](prototype/screenshots/browser-1440.jpg)、[`browser-1280x600.jpg`](prototype/screenshots/browser-1280x600.jpg)、[`browser-review.jpg`](prototype/screenshots/browser-review.jpg)、[`browser-relation-picker.jpg`](prototype/screenshots/browser-relation-picker.jpg) 与 [`browser-relation-empty.jpg`](prototype/screenshots/browser-relation-empty.jpg)。

### 视觉与尺寸映射

| 元素 | Pen 节点/属性 | Compose 目标属性 |
|---|---|---|
| 页面表面与正文 | `kIuwj` 白色表面；Noto Sans SC；正文 `#18181B`，次级文字 `#71717A` | `SecondaryPageContainer` Surface、Typography 与 ContentDescription |
| 边线与焦点 | `D:p88n3y`，边线 `#E4E4E7`；焦点/当前步骤 `#0485F7` | Finance `surface.raised`、`border.default`、`semantic.focus` tokens |
| 标题栏 | `D:vwsV7` 44 dp 控制 + 标题 + 1 dp 分隔线；Compact 高 72 dp；桌面高 78–84 dp | 共用标题栏；Compact 显示语义名为「返回收支账本」的 44 dp 返回按钮；桌面显示 44 dp 关闭按钮 |
| 内容插槽 | `D:X77ia`；四步进度和当前步骤内容可纵向滚动 | `presentation/SecondaryPageContainer.kt` 提供统一 `content` / `footer` 插槽，负责标题栏、可用尺寸、滚动边界和固定页脚 |
| Regular 浮层 | `N9VR9`：768 × 1024 dp 代表窗口；模态宽约 691 dp、高约 922 dp（各占窗口 90%），圆角 22 dp，居中 | `WindowSizeClass.REGULAR` 模态呈现；约占可用视口宽、高 90%，居中、边线、圆角、背景隔离 |
| Wide 浮层 | `YGZoE`：1440 × 900 dp 代表窗口；模态宽 1296 dp、高 810 dp（各占窗口 90%），圆角 24 dp，居中 | `WindowSizeClass.WIDE` 模态呈现；约占可用视口宽、高 90%，关系列表使用浮层宽度 |
| 主要内容宽度 | Regular 与 Wide 的文件、账户、核对、结果区域最大 720 dp；关系配对列表铺满容器内容区域 | 表单和核对列表保持易读行宽；配对表利用桌面空间 |
| Compact 页面 | `RwvTd`：390 × 844 代表视口；宽度填满，标题栏高 72 dp，边到边 | `WindowSizeClass.COMPACT` 全屏呈现；内容单列，安全区和垂直滚动边界清晰 |
| 操作和状态 | Finance Primary/Tertiary Button；禁用 `#E4E4E7`；错误 `#B42318`（`#FBEAE8` 底色）；成功 `#087443`（`#E8F5EE` 底色） | 使用 Finance 按钮封装与现有导入状态；不以 Material 默认样式替代设计组件 |

Compose 响应等级继续使用现有 `WindowSizeClass.fromWidthDp`：Compact 小于 600 dp，Regular 为 600–1023 dp，Wide 从 1024 dp 起。页面级二级目的地只提供标题、内容插槽、页脚操作、关闭回调与其自身的离开清理规则；通用 `SecondaryPageContainer` 负责尺寸切换、模态焦点和背景隔离。所有后续新增的页面级二级目的地 MUST 复用该容器，不复制尺寸判断与标题栏。现有记账详情抽屉属于局部记录操作，不属于页面级二级目的地，仍在本次非目标内。

## 交互与状态

- 账本中的「导入账单」是唯一应用内入口；保留 `/w/{workspaceId}/cash-import` 直接链接。直接链接在 Compact 打开全屏页，在 Regular/Wide 打开浮层。
- Regular/Wide 的关闭按钮、Escape 都退出浮层；焦点限制在浮层内，退出后归还原账本入口。点击遮罩不关闭，以免意外丢弃导入。
- Compact 用有可访问名称的返回按钮回到账本。触屏目标至少 44 dp。桌面背景在浮层打开期间不可操作。
- 保持四步及现有扫描、映射校验、预览、分配和关系建议语义。加载状态禁用重复提交；映射错误在字段下展示；文件不可读时提供换文件恢复方式。核对步骤不在标题下重复显示总数。
- 配对页只保留筛选器自身的计数，不显示说明段落或筛选器上方的自动匹配/待确认汇总。待确认行以唯一的常驻下拉框选择已有流水；选择后仍显示已选项并同步行状态与筛选计数。筛选后没有匹配项时显示简短空状态。账户映射错误将焦点移到无效字段。
- 关系步骤中的待确认关系不是提交阻断条件；只有现有 `canCommit()` 判定的未完成分配或不支持项目阻止确认。成功后展示结果并返回账本。
- 离开未确认页面会销毁文件、账户映射、预览和关系决定；不额外询问或创建草稿。

## 跨平台影响检查

- **Web：受影响。** Compose Web 使用共享页面与状态；Compact 需显示全屏返回页，Regular/Wide 使用模态容器。实际宽度、焦点、Escape 和直接 URL 均纳入浏览器 QA。
- **Native：受影响。** Android/iOS 使用同一 Compose 层；Phone/Compact 为全屏二级页，Tablet/Regular/Wide 为模态浮层；返回/关闭、系统返回和关闭清理语义保持一致。
- **共享层：受影响。** `AppNavigation`、`AppShell`、`CashLedgerScreen`、`CashImportScreen` 与通用 Compose 容器共用状态/文案/路由语义。Web HTML 文件选择与 Native 系统文件选择器可采用已登记控件差异，但业务结果与校验一致。

本次明确不迁移收支流水详情抽屉、一级页面、账户菜单或其他现有操作弹层。数据库、API、导入提交、计算和持久化行为不变。使用者已确认更新后的 Pen 设计和 HTML 交互原型；实现以两者为视觉与交互基线。

## 风险、回滚与开放问题

- 风险集中在桌面模态焦点归还、Escape 与 Compose 浏览器历史路由的协调，以及离开时正确丢弃临时状态；由交互回归和三个尺寸的真实浏览器流程覆盖。
- 回滚可撤销统一容器和导入入口改动，并恢复导入一级导航；不需要数据库迁移或数据修复。
- Native 模拟器已安装并启动应用，但处于未登录态；由于没有独立 Native 认证测试会话，本轮未完成 Native 导入页点击流程。恢复完整验证需要 Native 测试后端中的非真实账本测试用户。
- Open Questions：无。具体视觉稿和交互原型已由使用者确认。
