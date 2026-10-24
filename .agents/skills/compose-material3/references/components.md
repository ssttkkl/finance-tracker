# Material 3 Components

以下目录覆盖 Material 3 官方 Components 导航中的全部 36 个组件主题页，并补充 Compose Material3 包中与页面有关、但官网未设独立组件页的常见 API。内容是面向实现的摘要，不复刻官方文案。目录已按官网 sitemap 核对至 2026-10-25；官网更新后重新检查索引。

[Material 3 Components 总览](https://m3.material.io/components/) · [所有按钮总览](https://m3.material.io/components/all-buttons)

## 如何读平台标记与 API

表中核心 Material3 API 按 Android、iOS、Web 的 `commonMain` 实现标注；具体符号须在当前项目依赖中确认后才能使用。不要将 AndroidX API Reference 上的最新内容直接当作当前 JetBrains artifact 已提供的 API。Web 的 Compose Multiplatform 仍为 Beta，除编译外还要检查浏览器的焦点、键盘、滚动和语义行为。

Compose API 索引：[JetBrains Material3 common API](https://kotlinlang.org/api/compose-multiplatform/material3/androidx.compose.material3/)、[carousel 包](https://kotlinlang.org/api/compose-multiplatform/material3/androidx.compose.material3.carousel/)、[pulltorefresh 包](https://kotlinlang.org/api/compose-multiplatform/material3/androidx.compose.material3.pulltorefresh/) · [AndroidX API](https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary)。每项的 API 调用是示例骨架；参数或 state holder 以当前 API 页为准。Material 3 Expressive 变体是否稳定、是否需 @OptIn，见[版本状态说明](expressive.md)。

## 页面框架与导航

### App bars

[官网概览](https://m3.material.io/components/app-bars/overview) · [Specs](https://m3.material.io/components/app-bars/specs)
页头显示标题、导航和当前页面操作。常见 API：`TopAppBar`、`CenterAlignedTopAppBar`、`MediumTopAppBar`、`LargeTopAppBar`、`AppBarWithSearch`；底部操作区使用 `BottomAppBar`。按标题长度和滚动结构选择，不重复放置相同操作。
示例：<code>TopAppBar(title = { Text(title) }, navigationIcon = { /* back */ }, actions = { /* actions */ })</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）。状态：滚动折叠、导航可用、标题布局、焦点顺序。

### Navigation bar

[官网概览](https://m3.material.io/components/navigation-bar/overview) · [Specs](https://m3.material.io/components/navigation-bar/specs)
窄窗口的顶层目的地导航。用 `NavigationBar` 与 `NavigationBarItem`；目的地过多时评估 rail 或 drawer。
示例：<code>NavigationBar { NavigationBarItem(selected, onClick, icon = { Icon(...) }, label = { Text(label) }) }</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）。状态：当前目的地选中、未选中、焦点和标签显隐。

### Navigation drawer

[官网概览](https://m3.material.io/components/navigation-drawer/overview) · [Specs](https://m3.material.io/components/navigation-drawer/specs)
窄窗口的模态导航，或大窗口常驻/可关闭导航。常见 API：`ModalNavigationDrawer`、`DismissibleNavigationDrawer`、`PermanentNavigationDrawer`、对应 `DrawerSheet` 与 `NavigationDrawerItem`。
示例：<code>ModalNavigationDrawer(drawerContent = { ModalDrawerSheet { NavigationDrawerItem(...) } }) { content() }</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）。状态：展开/关闭、选中项、scrim、返回键/手势、焦点陷阱和焦点恢复。

### Navigation rail

[官网概览](https://m3.material.io/components/navigation-rail/overview) · [Specs](https://m3.material.io/components/navigation-rail/specs)
中等窗口的纵向顶层导航；使用 `NavigationRail` 与 `NavigationRailItem`。宽版 rail 或 modal 变体属于 Expressive/版本敏感 API。
示例：<code>NavigationRail { NavigationRailItem(selected, onClick, icon = { Icon(...) }, label = { Text(label) }) }</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）；宽版与 modal 变体须核版本。状态：选择、展开/关闭、焦点和项目顺序。

### Toolbars

[官网概览](https://m3.material.io/components/toolbars/overview) · [Specs](https://m3.material.io/components/toolbars/specs)
聚合常用操作；可使用 `HorizontalFloatingToolbar`、`VerticalFloatingToolbar`，并按版式组合按钮、分隔和 FAB。顶部/底部应用栏见 App bars。
示例：<code>HorizontalFloatingToolbar(expanded = expanded, content = { /* actions */ })</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）；当前仓库对应 AndroidX alpha 快照中的 FloatingToolbar 已稳定，仍须查当前声明。状态：展开、滚动收起、焦点和遮挡。

### Bottom sheets

[官网概览](https://m3.material.io/components/bottom-sheets/overview) · [Specs](https://m3.material.io/components/bottom-sheets/specs)
在当前页面下方显示补充任务。模态形态用 `ModalBottomSheet`；与页面内容共存用 `BottomSheetScaffold`。
示例：<code>ModalBottomSheet(onDismissRequest = onDismiss) { sheetContent() }</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）；锚点、部分展开 API 随版本演进。状态：展开、收起、拖动、取消与焦点返回。

### Side sheets

[官网概览](https://m3.material.io/components/side-sheets/overview) · [Specs](https://m3.material.io/components/side-sheets/specs)
宽窗口里从侧边进入的辅助内容面板。此设计主题目前没有对应的通用 Material3 core composable；不要把 `ModalNavigationDrawer` 或项目普通侧栏误称为通用 side sheet。
示例：先使用 Compose 布局实现信息结构；需要 modal / dismissible 行为时按设计页和产品合同实现。
平台：官网设计指导适用于适配布局；当前 core API 查询未找到独立通用 SideSheet API。状态：焦点、关闭行为、窗口变化时主内容可达性。

## 操作与命令

### Buttons

[官网概览](https://m3.material.io/components/buttons/overview) · [Specs](https://m3.material.io/components/buttons/specs)
提交或触发操作。强调从高到低可选 `Button`、`FilledTonalButton`、`ElevatedButton`、`OutlinedButton`、`TextButton`。
示例：<code>Button(onClick = onSave, enabled = canSave) { Text("保存") }</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）。状态：enabled、disabled、focused、pressed、loading 与操作结果；不固定按钮尺寸。

### Button groups

[官网概览](https://m3.material.io/components/button-groups/overview) · [Specs](https://m3.material.io/components/button-groups/specs)
将彼此相关的操作放在同一组；表达优先级、压缩布局或可展开的溢出操作。使用 `ButtonGroup` 与其 scope。
示例：<code>ButtonGroup(overflowIndicator = { menuState -> /* overflow menu */ }) { Button(onClick = first) { Text("编辑") }; Button(onClick = second) { Text("更多") } }</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）。`ButtonGroup` 在仓库对应 AndroidX alpha 快照已稳定；检查当前声明。状态：焦点顺序、压缩/溢出和操作层级。

### Split button

[官网概览](https://m3.material.io/components/split-button/overview) · [Specs](https://m3.material.io/components/split-button/specs)
一个主操作加同组的次级菜单操作。使用 `SplitButtonLayout` / `SplitButtonDefaults` 包含两个有明确标签的 M3 按钮；适合用户可辨别且相关的一组动作。
示例：<code>SplitButtonLayout(leadingButton = { Button(onClick = primary) { Text("保存") } }, trailingButton = { IconButton(onClick = openMenu) { Icon(moreIcon, "更多操作") } })</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）。API 在仓库对应 AndroidX alpha 快照已脱离实验状态；检查主操作、菜单展开/关闭、焦点与禁用状态。

### Floating action button

[官网概览](https://m3.material.io/components/floating-action-button/overview) · [Specs](https://m3.material.io/components/floating-action-button/specs)
页面主要创建操作。`SmallFloatingActionButton`、`FloatingActionButton`、`LargeFloatingActionButton` 按层级选用。
示例：<code>FloatingActionButton(onClick = onCreate) { Icon(createIcon, contentDescription = "新建") }</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）。状态：滚动显示/隐藏、展开、禁用、遮挡与可访问名称。

### Extended FAB

[官网概览](https://m3.material.io/components/extended-fab/overview) · [Specs](https://m3.material.io/components/extended-fab/specs)
重要创建操作需要图标和文字时使用。`ExtendedFloatingActionButton` 可含 icon、text，避免仅为突出而占据过多区域。
示例：<code>ExtendedFloatingActionButton(onClick = onCreate, icon = { Icon(createIcon, null) }, text = { Text("新建记录") })</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）。状态：滚动收缩、标签、禁用和小窗口遮挡。

### FAB menu

[官网概览](https://m3.material.io/components/fab-menu/overview) · [Specs](https://m3.material.io/components/fab-menu/specs)
从一个创建入口展开同层级的相关动作。查 `FloatingActionButtonMenu` 与 `FloatingActionButtonMenuItem`；避免隐藏低频但必要的操作。
示例：<code>FloatingActionButtonMenu(expanded = expanded, button = { /* FAB */ }) { /* menu items */ }</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认），属新 API；逐版本检查稳定标记与目标可用性。状态：展开/收起、焦点次序、Escape/返回与每项结果。

### Icon buttons

[官网概览](https://m3.material.io/components/icon-buttons/overview) · [Specs](https://m3.material.io/components/icon-buttons/specs)
高频或紧凑的图标操作。含标准、`FilledIconButton`、`FilledTonalIconButton`、`OutlinedIconButton` 与 toggle 变体。
示例：<code>IconButton(onClick = onRefresh) { Icon(refreshIcon, contentDescription = "刷新") }</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）。状态：toggle 的 checked/unchecked，以及 focus、pressed、disabled 和可访问名称。

### Segmented buttons

[官网概览](https://m3.material.io/components/segmented-buttons/overview) · [Specs](https://m3.material.io/components/segmented-buttons/specs)
同一维度中的互斥或多选选项。用 `SingleChoiceSegmentedButtonRow` 或 `MultiChoiceSegmentedButtonRow` 包含 `SegmentedButton`。
示例：<code>SingleChoiceSegmentedButtonRow { SegmentedButton(selected = selected, onClick = onSelect, shape = shape, label = { Text("月") }) }</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）。状态：单选/多选语义、选中反馈、焦点与窄窗口溢出。

## 选择与输入

### Chips

[官网概览](https://m3.material.io/components/chips/overview) · [Specs](https://m3.material.io/components/chips/specs)
紧凑展示过滤、建议、输入值或轻量操作。`AssistChip`、`SuggestionChip`、`FilterChip`、`InputChip` 各有 elevated 变体。
示例：<code>FilterChip(selected = selected, onClick = onToggle, label = { Text("本月") })</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）。状态：selected、enabled、拖移/移除、焦点及 chip 集合换行。

### Checkbox

[官网概览](https://m3.material.io/components/checkbox/overview) · [Specs](https://m3.material.io/components/checkbox/specs)
非互斥选项，可有未选、选中、父级部分选中。使用 `Checkbox` 与 `TriStateCheckbox`。
示例：<code>Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）。状态：checked、unchecked、indeterminate、disabled、焦点和关联标签。

### Radio button

[官网概览](https://m3.material.io/components/radio-button/overview) · [Specs](https://m3.material.io/components/radio-button/specs)
一组选项里只能选择一个。`RadioButton` 常与可点击标签一起构成整行操作。
示例：<code>RadioButton(selected = selected, onClick = onSelect)</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）。状态：选中/未选中、禁用、组内键盘导航、标签点击和读屏分组。

### Switch

[官网概览](https://m3.material.io/components/switch/overview) · [Specs](https://m3.material.io/components/switch/specs)
立即切换一个持续生效的开/关设置。
示例：<code>Switch(checked = enabled, onCheckedChange = onChange)</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）。状态：on/off、disabled、拖动、焦点和状态变化的文本/语义反馈。

### Sliders

[官网概览](https://m3.material.io/components/sliders/overview) · [Specs](https://m3.material.io/components/sliders/specs)
在连续或离散范围中调整数值；`Slider` 单值，`RangeSlider` 区间。
示例：<code>Slider(value = value, onValueChange = onChange, valueRange = range)</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）；指针、键盘和触控精度需分端核验。状态：端点、步进、当前值、拖动和无障碍可编辑值。

### Text fields

[官网概览](https://m3.material.io/components/text-fields/overview) · [Specs](https://m3.material.io/components/text-fields/specs)
收集、校验和编辑文本；baseline/outlined 及单行、多行、前后图标、辅助/错误文案等配置。使用 `TextField` 或 `OutlinedTextField`。
示例：<code>OutlinedTextField(value = value, onValueChange = onChange, label = { Text("名称") }, isError = hasError, singleLine = true)</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）。仓库直接使用 M3 `TextField`；状态：focus、输入/光标、错误、disabled、IME、文本缩放和单/多行合同。

### Search

[官网概览](https://m3.material.io/components/search/overview) · [Specs](https://m3.material.io/components/search/specs)
页面搜索入口与匹配建议；可折叠/展开或全屏变体。使用 `SearchBarState`、`SearchBar`、`ExpandedDockedSearchBar` 或 `ExpandedFullScreenSearchBar` 与 `SearchBarDefaults.InputField`。
示例：<code>SearchBar(state = searchState, inputField = { SearchBarDefaults.InputField(/* query and callbacks */) })</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）；搜索 API 近期更改过参数。状态：空/输入/提交/展开/收起/无结果、键盘、焦点与清除。

### Menus

[官网概览](https://m3.material.io/components/menus/overview) · [Specs](https://m3.material.io/components/menus/specs)
触发后显示一组离散命令或选择项。常用 `DropdownMenu` / `DropdownMenuItem`；输入建议菜单使用 `ExposedDropdownMenuBox`。
示例：<code>DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) { DropdownMenuItem(text = { Text("编辑") }, onClick = onEdit) }</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）。状态：展开/关闭、滚动、选中或禁用项、锚点定位、Escape/返回及焦点恢复。

### Date pickers

[官网概览](https://m3.material.io/components/date-pickers/overview) · [Specs](https://m3.material.io/components/date-pickers/specs)
选择日期或日期范围；含输入型/日历型 picker 与 dialog 承载方式。使用 `DatePicker`、`DateRangePicker`、`DatePickerDialog`。
示例：<code>val state = rememberDatePickerState(); DatePicker(state = state)</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）。状态：日历/文本模式、选中、范围端点、无效日期、限制日期、月份导航、键盘和本地化。

### Time pickers

[官网概览](https://m3.material.io/components/time-pickers/overview) · [Specs](https://m3.material.io/components/time-pickers/specs)
选择时刻；常用 `TimePicker` 与 `TimeInput`，由 `rememberTimePickerState` 保存状态。
示例：<code>val state = rememberTimePickerState(); TimePicker(state = state)</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）。状态：小时/分钟、AM/PM 或 24 小时、本地化、输入模式、无效输入和焦点。

## 展示与容器

### Badges

[官网概览](https://m3.material.io/components/badges/overview) · [Specs](https://m3.material.io/components/badges/specs)
在图标或其他组件旁显示少量计数或状态提示。`Badge` 放进 `BadgedBox`。
示例：<code>BadgedBox(badge = { Badge { Text("3") } }) { Icon(inboxIcon, contentDescription = "收件箱") }</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）。状态：有/无 badge、小计数与 dot 形态、裁切及读屏描述。

### Cards

[官网概览](https://m3.material.io/components/cards/overview) · [Specs](https://m3.material.io/components/cards/specs)
将相关内容和可选操作聚合；`Card`、`ElevatedCard`、`OutlinedCard` 表达不同容器强调。
示例：<code>Card(onClick = onOpen) { content() }</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）。状态：静态/可点击、选中、焦点、按压反馈、内容增长与嵌套操作。

### Carousel

[官网概览](https://m3.material.io/components/carousel/overview) · [Specs](https://m3.material.io/components/carousel/specs)
横向浏览一组相关卡片/媒体；`HorizontalMultiBrowseCarousel`、`HorizontalUncontainedCarousel` 面向不同可见数量和边缘处理。
示例：<code>HorizontalMultiBrowseCarousel(state = state, itemWidth = itemWidth, itemSpacing = spacing) { index -> item(index) }</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）；Expressive API 版本敏感。状态：起始/末尾、当前项、拖动/滚动、键盘和非横向手势入口。

### Tabs

[官网概览](https://m3.material.io/components/tabs/overview) · [Specs](https://m3.material.io/components/tabs/specs)
在同一页面内容区域间切换。`PrimaryTabRow` 用于主要分区，`SecondaryTabRow` 用于次级分区，`Tab` 表示可切换项。
示例：<code>PrimaryTabRow(selectedTabIndex = selectedIndex) { tabs.forEachIndexed { index, title -> Tab(selected = index == selectedIndex, onClick = { onSelect(index) }, text = { Text(title) }) } }</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）。状态：选中/未选中、焦点、滚动到可见区、禁用及内容切换后的状态保留。

### Lists

[官网概览](https://m3.material.io/components/lists/overview) · [Specs](https://m3.material.io/components/lists/specs)
连续排列便于查找和扫描的条目，可使用 standard / segmented 样式与 headline、supporting、leading、trailing slots。使用 `ListItem`；分组列表可与 `Divider` 组合。
示例：<code>ListItem(headlineContent = { Text(title) }, supportingContent = { Text(detail) }, trailingContent = { Text(amount) })</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）。Expressive list slots/segmented variants 在仓库 alpha 快照可能仍为实验 API。状态：选中、hover/focus、禁用、长文案、行数高度和分组。

### Divider

[官网概览](https://m3.material.io/components/divider/overview) · [Specs](https://m3.material.io/components/divider/specs)
分隔连续内容或不同区域，使用 `HorizontalDivider` 或 `VerticalDivider`；避免在已由留白清楚分组的内容中增加装饰分割。
示例：<code>HorizontalDivider()</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）。状态：无交互状态；检查方向、语义装饰属性、对比和缩放下的视觉连续性。

## 反馈、进度与辅助信息

### Dialogs

[官网概览](https://m3.material.io/components/dialogs/overview) · [Specs](https://m3.material.io/components/dialogs/specs)
暂停当前任务以确认决策或展示重要信息。`AlertDialog` 有标题、正文、确认/取消操作；`BasicAlertDialog` 支持自定义布局。
示例：<code>AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onClick = onConfirm) { Text("确定") } }, title = { Text(title) }, text = { Text(message) })</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）。状态：modal 语义、关闭规则、焦点进入/恢复、返回键、按钮含义和内容滚动。

### Snackbar

[官网概览](https://m3.material.io/components/snackbar/overview) · [Specs](https://m3.material.io/components/snackbar/specs)
对刚完成或可撤销的操作作短暂反馈；使用 `SnackbarHost` 与 `SnackbarHostState`，仅在适当时提供 action。
示例：<code>SnackbarHost(hostState = hostState)</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）。状态：排队/消失、行动按钮、长文案、读屏宣布、焦点，以及离线/错误场景的真实语义。

### Progress indicators

[官网概览](https://m3.material.io/components/progress-indicators/overview) · [Specs](https://m3.material.io/components/progress-indicators/specs)
表达有已知或未知完成进度的操作；使用 `LinearProgressIndicator` 或 `CircularProgressIndicator`。
示例：<code>LinearProgressIndicator(progress = { progress })</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）。状态：determinate/indeterminate、数值与进度信息、起止时机和较长等待的文本反馈。

### Loading indicator

[官网概览](https://m3.material.io/components/loading-indicator/overview) · [Specs](https://m3.material.io/components/loading-indicator/specs)
用 Expressive 的形状和动效呈现加载过程，可选容器型 `ContainedLoadingIndicator`；与可量化进度区分。
示例：<code>LoadingIndicator()</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）；Expressive API 与 reduced motion 行为要查当前版本。状态：加载开始/完成、持续时间和读屏状态。

### Tooltips

[官网概览](https://m3.material.io/components/tooltips/overview) · [Specs](https://m3.material.io/components/tooltips/specs)
为难以从控件本身理解的图标或功能提供按需补充信息；`PlainTooltip`、`RichTooltip`。不重复显而易见的可见文案。
示例：<code>TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(help) } }, state = rememberTooltipState()) { target() }</code>
平台：Android / iOS / Web 的 `commonMain` API（具体符号按当前版本确认）。状态：hover、focus、长按、自动隐藏、屏幕边缘定位、键盘触达和读屏关系。

## 官网目录外的常用 Compose Material3 API

这些 API 属于 Material3 实现，但当前 M3 组件导航没有一一对应的独立 component overview。API 是否可用于当前项目仍按[依赖版本](compose-multiplatform.md)核对。

| API | 用途、Compose 示例与状态 | 平台边界 / 官方参考 |
|---|---|---|
| `Scaffold` | 页面级 top bar、bottom bar、FAB、snackbar host 与内容 inset 的结构容器。示例：<code>Scaffold(topBar = { TopAppBar(...) }) { innerPadding -> content(innerPadding) }</code> | Android / iOS / Web 的 `commonMain` API；系统 insets 和滚动联动逐端检查。见 [Compose Material3 API](https://kotlinlang.org/api/compose-multiplatform/material3/androidx.compose.material3/)。 |
| `Surface` | M3 语义容器，组合颜色、形状、tonal/shadow elevation；不把它当作每一层布局的默认卡片。示例：<code>Surface(color = MaterialTheme.colorScheme.surface) { content() }</code> | Android / iOS / Web 的 `commonMain` API；检查颜色角色、对比和层级。见 [M3 theming](https://developer.android.com/develop/ui/compose/designsystems/material3)。 |
| Pull to refresh | `PullToRefreshBox` 与 `rememberPullToRefreshState` 为刷新手势与刷新指示提供 API；页面应同时保留可发现的刷新方式。 | 位于独立 `androidx.compose.material3.pulltorefresh` 包；逐版本检查 common 目标与状态。见 [Compose Material3 API](https://kotlinlang.org/api/compose-multiplatform/material3/androidx.compose.material3.pulltorefresh/)。 |
| Swipe to dismiss | `SwipeToDismissBox` / `SwipeToDismissBoxState` 提供滑动关闭或移除交互。财务记录的删除、冲正等高影响动作不得只提供滑动入口。 | Material3 common API（具体符号按当前版本确认）；检查 RTL、拖动阈值、撤销路径、键盘替代和目标平台支持。见 [AndroidX Material3 API](https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary)。 |
