# cross-platform-presentation Specification

## Purpose

为 Web 与 Android/iOS Native 提供同一份可验证的 presentation 合同，统一用户可见文案、页面结构、状态转换、响应式等级和语义测试入口，同时保留 DOM 与 Native 控件各自的渲染方式。

## Requirements

### Requirement: Web is the source of truth for the first Native rewrite

第一阶段涉及的 Native 页面 MUST 以当前 Web 已验证行为作为事实源；同一任务的用户可见文案、信息区域顺序、主要操作、加载/空/错误/成功状态和业务结果 MUST 与 Web Compact 的合同一致。平台渲染器可以使用不同的控件，但不得以实现差异改变产品语义。

#### Scenario: Compare a first-phase screen

- **WHEN** 使用者在 Web Compact 和 Android 或 iOS Native 打开认证、工作区、收支账本、手工记账、凭证或账单导入中的同一页面
- **THEN** 两端 MUST 展示相同的页面标题、核心区域顺序、主要操作、状态语义和服务端结果
- **AND** 允许的控件差异 MUST 只影响交互表面，不得改变字段含义、确认时机或结果

### Requirement: Shared presentation semantics are addressable

跨端页面 MUST 为每个核心区域、操作和状态提供稳定的语义标识；Web 使用 `data-testid` 或等价语义属性，Native 使用 `testID` 或等价语义入口。标识 MUST 不依赖中文文案、数组位置或具体渲染控件。

#### Scenario: A parity journey addresses a primary action

- **WHEN** 自动化测试需要打开收支账本的新增流水操作
- **THEN** Web 与 Native MUST 都能通过同一个语义标识找到该操作
- **AND** 修改按钮文案或把按钮替换为 Native 控件不得改变该标识

### Requirement: Responsive classes map logical window sizes across platforms

系统 MUST 按逻辑窗口宽度而不是设备型号划分布局等级：小于 `600` 的宽度为 `compact`，`600` 至小于 `1024` 为 `regular`，`1024` 及以上为 `wide`。Web `compact` MUST 对应 Native phone，Web `regular` MUST 对应 Native tablet portrait 或窄窗口，Web `wide` MUST 对应 Native tablet landscape 或大窗口。

#### Scenario: Android runs on multiple device forms

- **WHEN** Android 在手机、折叠屏、平板或自由窗口中改变可用逻辑宽度
- **THEN** Native MUST 根据当前窗口宽度切换布局等级，不得读取或分支具体设备型号
- **AND** 内容 MUST 保持单列、双列或大屏关系与对应 Web 等级一致，不能出现页面级横向滚动

#### Scenario: iPad uses a large window

- **WHEN** iPad 以 portrait、landscape 或分屏窗口显示第一阶段页面
- **THEN** Native MUST 根据当前窗口宽度使用 `regular` 或 `wide` presentation
- **AND** `wide` 页面 MUST 保留 Web 的导航层级、信息块顺序、筛选/详情/操作区域关系和相近密度

### Requirement: First-phase Native screens preserve Web state and action contracts

第一阶段 Native MUST 覆盖登录、工作区选择、收支账本浏览、手工记账、凭证查看、账单选择/扫描、账户映射、流水核对、关系审查和导入完成这些当前 Expo 纵向切片已有的任务。每个任务 MUST 支持与 Web 对应的正常、加载、空、错误、禁用和成功状态；写入仍 MUST 由服务端确认，金额和数量 MUST 以字符串语义传输。

#### Scenario: A viewer opens a write action

- **WHEN** `viewer` 在 Web Compact 或 Native 打开记账或导入入口
- **THEN** 两端 MUST 以相同的只读/无权限语义禁用或拦截写操作
- **AND** 不得创建本地成功状态或提交不完整数据

#### Scenario: Import reaches relation review

- **WHEN** 用户完成账单扫描和账户映射并进入关系审查
- **THEN** Web 与 Native MUST 展示相同的关系状态、候选含义、拒绝/撤销动作和确认导入结果
- **AND** 取消文件选择、密码错误、请求错误和导入成功 MUST 分别落入可识别状态，不得显示空白页面

### Requirement: Platform differences and unsupported surfaces are explicit

平台差异 MUST 登记在共享合同或对应 OpenSpec 文档中，并同时声明两端必须保持的业务不变量。第一阶段尚未重写到 Native 的分类管理、投资账本和工作区管理页面 MUST 被标记为缺口，不得渲染成看似可用但无法完成任务的伪页面。

#### Scenario: Date and file controls use native surfaces

- **WHEN** Web 使用 popover、HTML file input 或表格，而 Native 使用 sheet、系统文件选择器或列表
- **THEN** 两端 MUST 保持相同的可选值、初始值、标签、确认语义和错误处理
- **AND** 这种控件表面差异 MUST 记录为允许的平台差异

#### Scenario: A user opens an unsupported Native surface

- **WHEN** Native 导航指向尚未重写的分类、投资或工作区管理能力
- **THEN** 客户端 MUST 明确显示暂不可用或保持未提供入口
- **AND** 不得伪造成功、写入不完整数据或静默跳到语义不同的页面

### Requirement: Shared journeys validate observable parity without pixel equality

跨端 parity journey MUST 验证页面区域、语义操作、状态转换、文案和业务结果，不得要求 Web 与 Native 截图像素完全相同。测试矩阵 MUST 至少覆盖 Web `390×844`、`768×1024`、`1440×900` 和 Native phone/tablet/large 的对应布局等级。

#### Scenario: Run the first-phase parity matrix

- **WHEN** 运行跨端 parity 检查
- **THEN** 检查 MUST 能报告每个第一阶段页面的区域、操作、状态和结果是否在 Web 与 Native 两端存在
- **AND** 视觉检查可以允许系统状态栏、字体栅格化和 DOM/Native 控件差异，但不得忽略信息层级或核心操作缺失
