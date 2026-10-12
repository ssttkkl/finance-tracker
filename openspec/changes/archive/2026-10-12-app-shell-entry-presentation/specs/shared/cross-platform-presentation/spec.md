## ADDED Requirements

### Requirement: Compose access entry pages share a responsive presentation shell

Compose 登录/注册、工作区选择或创建、未登录邀请页面 MUST 使用同一响应式入口容器、页面背景和居中单列表单卡片。`COMPACT` MUST 保持可纵向滚动的单列卡片；`REGULAR` 和 `WIDE` MUST 将主要内容居中，表单卡片宽度 MUST 不超过 `440` dp。页面特有的品牌、邮箱、退出、表单、邀请信息和操作 MUST 保留在各自页面中；共用视觉容器不得改变业务流程或状态反馈。

#### Scenario: Open each access entry page at supported widths

- **WHEN** 使用者在 Compose `COMPACT`、`REGULAR` 或 `WIDE` 窗口打开登录/注册、工作区入口或未登录邀请页面
- **THEN** 三类页面 MUST 呈现相同的入口背景、居中卡片层级和主要内容宽度关系
- **AND** 各页 MUST 继续展示完成当前任务所需的页面内容和操作
- **AND** 页面 MUST 不出现横向滚动或内容溢出

#### Scenario: Show entry flow state feedback

- **WHEN** 认证、工作区访问/创建或邀请预览/接受处于加载、禁用、错误或成功状态
- **THEN** 对应状态和恢复操作 MUST 出现在共享入口布局内
- **AND** 共用布局 MUST 不隐藏错误、伪造成功或改变提交确认语义

### Requirement: Authenticated Compose destinations use one shared page-title hierarchy

所有已登录 Compose 路由 MUST 由同一应用外框组织导航、页面标题、工作区切换/退出和路由内容。`COMPACT`、`REGULAR`、`WIDE` 均 MUST 在顶栏显示当前路由的唯一页面标题；正文 MUST 不重复该路由标题。各路由的主要内容、操作、状态、返回语义和业务结果 MUST 保持不变。

#### Scenario: Open a main destination at any window size

- **WHEN** 使用者在任一逻辑窗口等级打开收支账本、导入、分类、投资、工作区管理或已登录邀请页面
- **THEN** 页面 MUST 显示当前路由标题以及工作区切换和退出入口
- **AND** 页面 MUST 保留该路由需要的导航与正文内容
- **AND** 标题 MUST 只显示一次

#### Scenario: Navigate on compact, regular, and wide windows

- **WHEN** 使用者在 COMPACT、REGULAR 或 WIDE 窗口切换已登录路由
- **THEN** 当前页面标题 MUST 随路由更新
- **AND** 返回操作（适用时）、工作区切换、退出登录和响应式导航 MUST 继续可用
- **AND** 宽屏导航与内容、窄屏导航与内容 MUST 不发生遮挡或横向溢出

## MODIFIED Requirements

### Requirement: Compose authentication modes share a responsive shell

Compose 登录与注册 MUST 共用一个适用于 compact、regular 和 wide 的内部响应式表单结构，`COMPACT`、`REGULAR` 和 `WIDE` 均 MUST 使用宽度受限的居中白色表单卡片；`COMPACT` MUST 保留纵向滚动和窄屏边距。

#### Scenario: 不同响应式等级打开认证页

- **WHEN** 登录或注册页面在 compact、regular 或 wide 窗口中呈现
- **THEN** 两种模式 MUST 使用相同的响应式表单结构与居中白色卡片；`COMPACT` MUST 可纵向滚动且不得发生横向溢出

### Requirement: 登录与注册认证表单保持跨端一致

Web、Android 与 iOS 的登录和注册表单 MUST 不显示「Finance Tracker」品牌标识、「工作区访问」标签或邮箱占位文字。认证页 MUST 按 Compose `WindowSizeClass` 响应布局：`COMPACT`、`REGULAR` 与 `WIDE` 均使用灰色页面背景和居中的白色表单卡片；卡片宽度 MUST 不超过 `440` dp，`COMPACT` MUST 保持可纵向滚动。Native 启动器名称和认证后页面品牌不受影响。登录页标题 MUST 为「登录」，注册页标题 MUST 为「创建账户」。注册页标题左侧 MUST 提供仅显示左箭头的返回登录按钮，无障碍名称 MUST 为「返回登录」，触控区域至少为 `44` dp；注册页 MUST 不显示底部「已有账户？登录」入口，登录页继续显示「还没有账户？注册」入口。浅色和深色主题 MUST 使用相同信息层级与布局，深色主题沿用现有颜色角色。

#### Scenario: 在 compact 窗口打开登录页

- **WHEN** 未认证使用者在 `COMPACT` 窗口打开登录页
- **THEN** 登录表单 MUST 位于居中的白色表单卡片中，页面 MUST 使用灰色背景并保持可纵向滚动
- **AND** 卡片宽度 MUST 不超过 `440` dp，窄视口 MUST 保留可见的水平边距
- **AND** 页面 MUST 不显示品牌、邮箱占位文字或工作区标签

#### Scenario: 在 regular 或 wide 窗口打开登录页

- **WHEN** 未认证使用者在 `REGULAR` 或 `WIDE` 窗口打开登录页
- **THEN** 页面 MUST 保持灰色背景，白色登录表单卡片在可用视口中居中
- **AND** 页面 MUST 不显示品牌、邮箱占位文字或工作区标签
