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

## REMOVED Requirements

### Requirement: Platform differences and unsupported surfaces are explicit

**Reason**: 用户已把目标范围调整为 Compose Web、Android 和 iOS 均覆盖 Web 当前全部 10 项页面级功能。原来把分类管理、投资账本和工作区管理登记为 Native 缺口的要求不再符合目标合同。

**Migration**: 由 `compose-multiplatform-client` 的「三个平台都提供 Web 的全部页面级功能」及「平台控件差异保留相同用户语义」替代。迁移期间旧 Expo 客户端仍作为回退；Compose 验收后才退场。
