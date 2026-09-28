## MODIFIED Requirements

### Requirement: Responsive classes map logical window sizes across platforms

Web、Android 和 iOS MUST 使用同一套由逻辑窗口宽度决定的 `WindowSizeClass`：小于 `600` 的宽度为 `compact`，`600` 至小于 `1024` 为 `regular`，`1024` 及以上为 `wide`。布局选择不得读取设备型号。三端 MUST 保持同一页面划分、导航层级、信息区域顺序和主要操作；宽度变化只调整排布，不改变任务、筛选/详情/操作关系或业务结果。

#### Scenario: Android runs on multiple device forms

- **WHEN** Android 在手机、折叠屏、平板或自由窗口中改变可用逻辑宽度
- **THEN** Native MUST 根据当前窗口宽度切换布局等级，不得读取或分支具体设备型号
- **AND** 内容 MUST 保持与相同宽度 Web 一致的单列、双列或大屏信息关系，不能出现页面级横向滚动

#### Scenario: iPad uses a large window

- **WHEN** iPad 以 portrait、landscape 或分屏窗口显示页面
- **THEN** Native MUST 根据当前窗口宽度使用 `regular` 或 `wide` presentation
- **AND** `wide` 页面 MUST 保留 Web 大屏的导航层级、信息区域顺序、筛选/详情/操作关系和相近密度

#### Scenario: Web and Native cross the same logical width boundary

- **WHEN** 任一平台窗口宽度跨越 `600` 或 `1024` 逻辑像素
- **THEN** 页面 MUST 在相同边界选择对应 `WindowSizeClass`
- **AND** 切换等级 MUST 保留导航、筛选、未提交输入、详情和导入会话状态

### Requirement: Shared journeys validate observable parity without pixel equality

跨端 parity journey MUST 验证 Web、Android 和 iOS 的页面区域、语义操作、状态转换、用户可见文案及服务端结果，不得要求截图像素完全一致。测试矩阵 MUST 覆盖 10 项页面级功能、`compact`、`regular`、`wide` 三种逻辑窗口等级，以及 Web `390×844`、`768×1024`、`1440×900` 和 Native phone、tablet、large 对应窗口。

#### Scenario: Run the full three-platform parity matrix

- **WHEN** 运行迁移验收的跨端 parity 检查
- **THEN** 检查 MUST 能报告 10 项功能在 Web、Android、iOS 的入口、区域、操作、状态和结果是否存在
- **AND** 视觉检查可以允许系统状态栏、字体栅格化和平台控件表面差异，但不得忽略信息层级或核心操作缺失

#### Scenario: A parity journey fails on one target

- **WHEN** 一项共享用户任务在任一平台缺少页面区域、操作、错误状态或服务端结果
- **THEN** 迁移验收 MUST 将该任务标记为未完成
- **AND** 不得以其他平台通过或静态页面存在替代真实流程验证

## ADDED Requirements

### Requirement: 三端本地化文案与操作反馈保持语义一致

Compose Web、Android 和 iOS MUST 为同一页面区域、操作、表单字段、辅助技术名称及加载、空、成功、错误状态提供语义等价的简体中文和英语文案。用户选择的系统或浏览器语言相同且属于已支持语言时，三端 MUST 显示同一语义的对应翻译；平台控件表面不同不得改变选项标签、确认提示、错误恢复动作或结果说明。

#### Scenario: 同一语言下比较三端页面文案

- **WHEN** parity journey 在 Web、Android 和 iOS 上以同一种受支持语言打开同一功能
- **THEN** 相同语义的标题、操作、字段、状态和辅助技术名称 MUST 使用语义等价的翻译
- **AND** 不得出现一端遗漏、使用另一种语言或暴露原始资源键的固定文案

#### Scenario: 同一 API 错误在三端显示

- **WHEN** 同一操作在 Web、Android 和 iOS 收到同类可恢复 API 错误
- **THEN** 每端 MUST 使用当前语言显示语义相同的恢复提示
- **AND** 平台控件不同不得改变重试、取消或保留输入的时机和结果

### Requirement: 设计合同跨端共享，视觉实现允许平台差异

Web、Android 和 iOS MUST 共享同一页面合同、token 名称、组件语义 ID、状态集合和响应式不变量；`.pen` 视觉设计作为跨端参考，允许系统状态栏、原生选择器和字体栅格化存在平台差异。任何差异 MUST 在页面合同或平台差异清单中声明，不得改变字段、操作、确认语义、错误恢复或业务结果。

#### Scenario: 登录页在三端使用同一合同
- **WHEN** Web、Android 和 iOS 打开登录页
- **THEN** 三端 MUST 提供同一组邮箱/密码字段、登录/注册切换、提交、校验和错误恢复语义
- **AND** compact/regular/wide 的信息顺序和主要操作 MUST 与 `design/login.pen` 及页面合同一致

## REMOVED Requirements

### Requirement: Platform differences and unsupported surfaces are explicit

**Reason**: 用户已把目标范围调整为 Compose Web、Android 和 iOS 均覆盖 Web 当前全部 10 项页面级功能。原来把分类管理、投资账本和工作区管理登记为 Native 缺口的要求不再符合目标合同。

**Migration**: 由 `compose-multiplatform-client` 的「三个平台都提供 Web 的全部页面级功能」及「平台控件差异保留相同用户语义」替代。旧 React Web、Expo Native 和 TypeScript 共享包保留为迁移参考，但立即移出主动工作流；删除旧源码或切换线上入口另行决策。
