## MODIFIED Requirements

### Requirement: Compose Web is the Web source of truth for current Native parity

当前 Web 产品目标 MUST 指 `compose/webApp` 和共享 Compose 页面；Compose Web 的启动、预览和浏览器 QA 工具 MUST 位于 `compose/qa/web/`。第一阶段涉及的 Native 页面 MUST 以 Compose Web 已验证行为作为事实源；同一任务的用户可见文案、信息区域顺序、主要操作、加载/空/错误/成功状态和业务结果 MUST 与 Compose Web `compact` 的合同一致。平台渲染器可以使用不同的控件，但不得以实现差异改变产品语义。

#### Scenario: Compare a first-phase screen

- **WHEN** 使用者在 Compose Web `compact` 和 Android 或 iOS Native 打开认证、工作区、收支账本、手工记账、凭证或账单导入中的同一页面
- **THEN** 两端 MUST 展示相同的页面标题、核心区域顺序、主要操作、状态语义和服务端结果
- **AND** 允许的控件差异 MUST 只影响交互表面，不得改变字段含义、确认时机或结果

### Requirement: Confirmed Finance pages preserve shared workflow and responsive structure

F-01、F-02、F-04 至 F-10 的 Compose Web 和 Native 页面 MUST 保持既有业务操作、状态、用户文案及稳定语义 ID；当前 Compose 实现与 OpenSpec MUST 定义区域顺序和验收关系。`compact` MUST 为无页面级横向滚动的单列，`wide` MUST 保持 Compose Web 大屏的筛选、列表、详情与操作关系。

#### Scenario: 使用者在不同窗口尺寸完成页面任务

- **WHEN** 使用者在 compact、regular 或 wide 窗口打开任一已确认页面
- **THEN** 加载、空、错误和成功状态 MUST 具有相同的业务结果与恢复入口
- **AND** 主操作、筛选和详情 MUST 保持相同的可达性与确认语义

### Requirement: Compose authentication screens use the scoped Material 3 appearance

Compose 登录和注册界面 MUST 在现有 FinanceTheme 下使用 Material 3 描边输入框、主按钮、文字按钮和图标按钮，并保留 FinanceTheme 的颜色与字形。认证输入框和主按钮 MUST 局部使用 Material 3 小形状，桌面认证卡片 MUST 局部使用中等形状；不得改变 FinanceTheme 全局形状方案或认证既有字段、文案、校验、语义和提交流程。

#### Scenario: 登录与注册的空输入字段使用相同样式

- **WHEN** 使用者打开空的登录或注册表单
- **THEN** 描边输入框 MUST 在输入框内部显示标签
- **AND** 同一响应式等级下两种模式的首个输入框 MUST 从相同垂直位置开始
- **AND** 字段、按钮、形状、颜色、字形、字段顺序、标签、语义 ID 和无障碍含义 MUST 符合 Compose 实现、FinanceTheme 与本规格验收合同

#### Scenario: 输入框聚焦或已有内容

- **WHEN** 使用者聚焦输入框或输入框已有内容
- **THEN** 标签 MUST 浮到轮廓上方，轮廓和标签 MUST 反映当前焦点或校验状态
- **AND** 输入值 MUST 按既有字段类型保持可见或隐藏

#### Scenario: 显示字段帮助或校验反馈

- **WHEN** 字段需要显示帮助、校验成功或校验错误信息
- **THEN** 反馈 MUST 显示在对应描边输入框下方的说明区域
- **AND** 反馈 MUST 保留既有文案、实时区域语义、颜色含义及对应字段关系，且后续字段和提交操作在 compact、regular、wide 下不得重叠

#### Scenario: 认证请求忙碌或失败

- **WHEN** 认证请求正在处理或请求失败
- **THEN** 字段和操作 MUST 保留既有禁用、进度和请求错误行为
- **AND** 标签、文案、请求数据和认证结果 MUST 保持不变

### Requirement: Compose authentication title and first-field positions are aligned

Compose 登录与注册的首个描边输入框 MUST 在同一响应式等级中从相同垂直位置开始。注册页的返回按钮 MUST 使用 Material 3 图标按钮默认尺寸和触控目标，并保留现有无障碍名称。

#### Scenario: 在登录和注册模式间切换

- **WHEN** 使用者在相同窗口尺寸等级下切换认证模式
- **THEN** 两种模式的首个描边输入框 MUST 从相同垂直位置开始
- **AND** 注册页返回按钮 MUST 保留现有无障碍名称及 Material 3 默认触控目标

### Requirement: 登录与注册认证表单保持跨端一致

Web、Android 与 iOS 的登录和注册表单 MUST 不显示「Finance Tracker」品牌标识、「工作区访问」标签或邮箱占位文字。认证页 MUST 按 Compose `WindowSizeClass` 响应布局：`COMPACT`、`REGULAR` 与 `WIDE` 均使用灰色页面背景和居中的白色表单卡片；卡片宽度 MUST 不超过 `440` dp，`COMPACT` MUST 保持可纵向滚动。Native 启动器名称和认证后页面品牌不受影响。登录页标题 MUST 为「登录」，注册页标题 MUST 为「创建账户」。注册页标题左侧 MUST 提供仅显示左箭头的返回登录按钮，无障碍名称 MUST 为「返回登录」，沿用 Material 3 图标按钮默认尺寸与触控目标；注册页 MUST 不显示底部「已有账户？登录」入口，登录页继续显示「还没有账户？注册」入口。浅色和深色主题 MUST 使用相同信息层级与布局，深色主题沿用现有颜色角色。

#### Scenario: 在 compact 窗口打开登录页

- **WHEN** 未认证使用者在 `COMPACT` 窗口打开登录页
- **THEN** 登录表单 MUST 位于居中的白色表单卡片中，页面 MUST 使用灰色背景并保持可纵向滚动
- **AND** 卡片宽度 MUST 不超过 `440` dp，窄视口 MUST 保留可见的水平边距
- **AND** 页面 MUST 不显示品牌、邮箱占位文字或工作区标签

#### Scenario: 在 regular 或 wide 窗口打开登录页

- **WHEN** 未认证使用者在 `REGULAR` 或 `WIDE` 窗口打开登录页
- **THEN** 页面 MUST 保持灰色背景，白色登录表单卡片在可用视口中居中
- **AND** 页面 MUST 不显示品牌、邮箱占位文字或工作区标签

### Requirement: Compose cash import follows the confirmed secondary-page design

Compose 账单导入页 MUST 以 Compose 实现为视觉事实源，并遵循本规格定义的信息层级和响应式布局。`REGULAR` 和 `WIDE` MUST 先显示页面标题，再显示右侧关闭按钮；`COMPACT` MUST 先显示左侧返回按钮，再显示标题。二级页面标题栏高度 MUST 按 `COMPACT` / `REGULAR` / `WIDE` 分别为 72 dp、78 dp 和 84 dp。`REGULAR` 与 `WIDE` 模态框圆角 MUST 分别为 22 dp 和 24 dp，且不得添加额外阴影。关闭或返回按钮 MUST 遵循 Material 3 焦点行为，并使用 FinanceTheme 焦点颜色。

四步指示器 MUST 使用等宽步骤、编号或完成状态圆形标记及其下方标签，并独立于受 720 dp 宽度限制的阶段内容，`REGULAR` / `WIDE` 两侧内边距 MUST 为 36 dp，`COMPACT` MUST 为 12 dp。文件选择、账户映射和流水核对内容 MUST 居中且最大宽度为 720 dp；关系核对 MUST 使用可用容器宽度，并以来源到目标的卡片或行展示每项关系。页面 MUST 保留既有扫描、账户映射、流水核对、分配、关系类型、候选选择、拒绝与恢复、错误处理及确认语义。

完整账单导入路由 MUST 始终使用 FinanceTheme 的浅色语义角色，不受系统外观影响，且包含导航栏、顶栏、路由背景和导入界面。`REGULAR` / `WIDE` 模态框背景遮罩 MUST 使用现有深色遮罩 token 的 38% 透明度；模态框 MUST 阻止背景交互、保持模态键盘焦点并支持 Escape 或返回操作关闭。所有共享组件颜色 MUST 从当前 Compose 主题作用域解析。使用者离开账单导入页后，应用 MUST 恢复已配置的系统外观。

关系筛选器 MUST 显示全部、待确认和已匹配的数量。关系卡片 MUST 以既有的紧凑层级展示来源与目标流水，不得常驻展示账户名称或重复的字段标题；关系时间 MUST 使用本地时间的 `MM 月 dd 日 · HH:mm` 格式。待确认的目标流水 MUST 使用一个显示“选择流水”的候选下拉框，不得再添加说明文案或独立的可见选择操作。候选选项 MUST 锚定在选择框附近以浮层展示，打开或关闭时 MUST NOT 改变关系卡片及列表的尺寸或位置。选择候选项 MUST 关闭浮层并更新目标流水与关系数量；按 Escape 或点击浮层外部 MUST 关闭浮层且保留当前选择。待确认状态 MUST 使用 FinanceTheme 的警告语义 token。`COMPACT` 关系卡片 MUST 将来源和目标并列展示，并把状态放在下方；`REGULAR` / `WIDE` MUST 将关系状态放在关系行末尾。较少使用的关系类型以及拒绝/恢复操作 MAY 放入可通过键盘操作的辅助操作入口，并以状态标签呈现。

二级页面页脚 MUST 只在 `COMPACT` 显示分隔线；`REGULAR` / `WIDE` 不显示分隔线。`COMPACT` 页脚说明 MUST 显示在操作行上方，顶部与底部内边距分别为 8 dp 和 16 dp；`REGULAR` / `WIDE` 页脚内容的顶部与底部内边距 MUST 分别为 16 dp 和 24 dp。映射、核对、配对阶段的“上一步” MUST 使用描边次按钮；`REGULAR` / `WIDE` 的操作按钮 MUST 靠右对齐，`COMPACT` 的“上一步”和主操作 MUST 分别靠左和靠右对齐。`COMPACT` 文件选择阶段 MUST 将放弃提示显示在下一步操作上方。界面展示的文件格式 MUST 为 `CSV · XLSX · PDF`，同时保留现有 XLS 导入支持。

#### Scenario: 在 Wide 尺寸查看账单导入

- **WHEN** 使用者在 `WIDE` 窗口打开账单导入页
- **THEN** 页面 MUST 显示左侧标题、右侧关闭按钮和带有步骤标签的四步指示器
- **AND** 系统外观为深色时，导航栏、顶栏和导入界面 MUST 继续使用FinanceTheme 的浅色语义角色
- **AND** 模态框背景 MUST 使用现有的 38% 遮罩 token
- **AND** 文件选择、账户映射和流水核对内容 MUST 居中且最大宽度为 720 dp，关系核对 MUST 使用可用容器宽度
- **AND** 待确认关系 MUST 在来源到目标的行中提供一个候选选择器；其浮层 MUST 覆盖在内容之上且不撑开关系行，选中后关系类型与拒绝/恢复操作 MUST 仍然可用
- **AND** 已匹配筛选 MUST 包含自动匹配和用户确认的关系，被拒绝的关系 MUST 仍显示在全部筛选中
- **AND** 页脚 MUST 没有分隔线，底部内边距 MUST 为 24 dp
- **AND** 映射、核对和配对阶段的页脚操作按钮 MUST 靠右对齐
- **AND** “上一步” MUST 使用描边次按钮

#### Scenario: 在 Regular 尺寸查看账单导入

- **WHEN** 使用者在 `REGULAR` 窗口打开账单导入页
- **THEN** 标题栏 MUST 高 78 dp，模态框 MUST 使用 22 dp 圆角且没有额外阴影
- **AND** 页面标题 MUST 位于关闭按钮之前
- **AND** 候选选择浮层 MUST 覆盖在关系内容之上；打开时关系卡片尺寸 MUST 保持不变，选择候选后 MUST 更新关系目标
- **AND** 页脚 MUST 没有分隔线，底部内边距 MUST 为 24 dp
- **AND** 映射、核对和配对阶段的页脚操作按钮 MUST 靠右对齐
- **AND** “上一步” MUST 使用描边次按钮

#### Scenario: 在 Compact 尺寸查看账单导入

- **WHEN** 使用者在 `COMPACT` 窗口打开账单导入页
- **THEN** 页面 MUST 使用全屏界面，并先显示返回按钮再显示标题
- **AND** 文件选择阶段的放弃提示 MUST 显示在下一步操作上方
- **AND** 阶段内容和关系卡片 MUST 保持单列或适当重排，页面 MUST 没有横向溢出
- **AND** 所有导入决策和结果 MUST 与 `REGULAR` / `WIDE` 一致
- **AND** 关系卡片 MUST 横向并列显示来源与目标，且不得造成横向溢出
- **AND** 候选选择浮层 MUST 锚定在选择框附近且不改变卡片高度；选择后 MUST 更新目标和关系数量
- **AND** 在映射、核对和配对阶段，“上一步” MUST 靠左、“下一步”或主操作 MUST 靠右；“上一步” MUST 使用描边次按钮

#### Scenario: 离开账单导入后恢复系统外观

- **WHEN** 使用者从账单导入页返回其他应用路由
- **THEN** 目标路由 MUST 使用已配置的系统外观

#### Scenario: 关闭候选浮层而不改变选择

- **WHEN** 使用者按 Escape 或点击候选浮层以外的位置
- **THEN** 候选浮层 MUST 关闭
- **AND** 当前目标流水及关系数量 MUST 保持不变

## ADDED Requirements

### Requirement: Legacy React UI is migration reference only

旧 React UI（`web/src`）MUST 仅作为迁移参考；当前 UI 规范、Compose 浏览器 QA 和活跃 Web 行为合同 MUST 使用 Compose Web。Compose Web 的启动、预览和浏览器 QA 工程 MUST 位于 `compose/qa/web/`；此合同不要求修改旧 React 页面。

#### Scenario: Locate active Web presentation behavior

- **WHEN** 维护者查找或验证当前 Web 页面行为
- **THEN** 应使用 `compose/webApp` 和 `compose/qa/web/` 下的 Compose 资源
- **AND** `web/src` MUST 保持为参考实现，不得作为 Compose 样式或交互规范来源

### Requirement: Native full-screen secondary pages respect system safe areas

Native Compose 的紧凑全屏二级页面 MUST 将页头、可交互内容与底部操作放在平台系统安全区内。页面 MUST 保留系统状态区和底部手势区可见，不得让返回/关闭操作、标题或页脚与系统区域重叠。Web 页面与桌面模态页面 MUST 保持原有布局边界。

#### Scenario: 在 iPhone 上打开紧凑导入页

- **WHEN** 使用者在 iPhone 打开导入等紧凑全屏二级页面
- **THEN** 页头 MUST 位于状态栏安全区之下，底部操作 MUST 位于底部手势安全区之上
- **AND** 页面正文 MUST 在剩余安全内容区内滚动

### Requirement: Compose Web primary navigation supports keyboard focus

Compose Web 主导航 MUST 能通过键盘 Tab 导航访问。键盘焦点位于导航目的地时 MUST 显示 Finance 焦点颜色指示；鼠标点击或指针移动不得单独保留该键盘焦点指示。

#### Scenario: 使用键盘浏览主导航

- **WHEN** 使用者通过 Tab 将焦点移动到主导航目的地
- **THEN** 对应目的地 MUST 获得键盘焦点并显示可见焦点边框
- **AND** 按 Enter 或 Space MUST 执行该目的地操作
- **AND** 使用者改用指针时 MUST 清除键盘专用焦点边框

### Requirement: Code-first UI changes use Compose demo review

UI changes MUST record the user task, information architecture, acceptance criteria, and redundant content to remove in the corresponding OpenSpec change. A-class or major UI changes MUST also record Hallmark's design direction and self-review before implementation. Pen files and standalone HTML prototypes MUST NOT be required deliverables; Compose implementation MUST be the source of truth for page structure and appearance, and screenshots MUST serve as QA evidence only. Every UI change MUST receive a final design review against its OpenSpec acceptance criteria; when Hallmark is available, that review MUST use Hallmark audit, and when it is unavailable, the change MUST record that fact and complete an equivalent manual review. Major UI changes MUST be reviewed in the Compose demo after implementation and MUST NOT be marked complete until the user confirms the demonstrated result.

#### Scenario: Plan a major UI change

- **WHEN** a major UI change enters implementation planning
- **THEN** its OpenSpec design record MUST contain the user task, information architecture, content to remove, acceptance criteria, and Hallmark direction/self-review
- **AND** planning MUST NOT require a `.pen` file, Pen node mapping, or `prototype/index.html`

#### Scenario: Review a completed major UI change

- **WHEN** implementation is complete and the affected pages run in a Compose development/QA demo build
- **THEN** the change MUST be reviewed at the affected `COMPACT`, `REGULAR`, and `WIDE` layouts, with responsive screenshots and interaction evidence recorded in `tasks.md`
- **AND** the change MUST receive the final design review and remain incomplete until the user confirms the demonstrated result

## REMOVED Requirements

### Requirement: 认证状态设计稿展示完整页面

**Reason**: Compose 实现已成为页面结构和视觉事实源，Pen 画板不再是维护内容。

**Migration**: 在共享 Compose 页面中验证认证状态，使用仓库演示数据覆盖加载、错误和成功状态，并在 active OpenSpec 变更中记录响应式截图与审查证据。
