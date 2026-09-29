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

### Requirement: Web 与 Native 的会话恢复保持一致

Web 与 Native MUST 对保存的登录令牌使用相同的会话恢复结果语义：恢复期间不把未完成状态展示为未登录，认证失败进入登录，暂时性失败最多恢复 3 次；两端的恢复加载态文案 MUST 为「加载中...」。

#### Scenario: 两端均在恢复期间保持中性加载态

- **WHEN** Web 或 Native 启动时存在登录令牌且认证会话请求仍在进行
- **THEN** 对应平台 MUST 展示「加载中...」恢复加载态，不得展示登录表单

#### Scenario: 两端收到无效令牌

- **WHEN** Web 或 Native 收到 `authentication_required` 或 HTTP `401`
- **THEN** 对应平台 MUST 立即进入登录页面，不得把无效令牌当作暂时性网络失败重复请求

#### Scenario: 两端收到暂时性恢复错误

- **WHEN** Web 或 Native 的会话请求因网络或服务端暂时不可用而失败
- **THEN** 对应平台 MUST 自动恢复，最多总共发起 3 次请求；若仍失败则进入登录页面

#### Scenario: 两端没有保存令牌

- **WHEN** Web 或 Native 启动时没有保存的登录令牌
- **THEN** 对应平台 MUST 直接进入登录页面，且不得发起会话恢复请求

### Requirement: 多文件导入选择保持跨端语义一致

Web 与 Android/iOS Native 的账单选择步骤 MUST 允许使用者在同一次导入会话中添加最多 20 份文件、删除已选文件并在选择完成前继续添加；客户端 MUST 对每个文件内容在本地计算 SHA-1，仅对 SHA-1 相同的文件去重，其他文件 MUST 全部保留。每份文件仍 MUST 遵守 100 MB 限制。选择步骤 MUST 只显示低存在感的文件名和删除操作，点击「下一步」前不得开始服务端扫描。

#### Scenario: 继续添加文件

- **WHEN** 使用者已选择一份或多份文件并再次点击「选择账单文件」
- **THEN** Web 与 Native MUST 把新文件追加到当前选择集合
- **AND** 不得清空原有文件或提前进入账户映射

#### Scenario: SHA-1 去重

- **WHEN** 使用者添加一份内容 SHA-1 与已选文件相同的文件
- **THEN** 客户端 MUST 只保留第一次选择的文件
- **AND** 内容不同但文件名相同的文件 MUST 分别保留

#### Scenario: 下一步后显示逐文件密码

- **WHEN** 使用者点击「下一步」且服务端返回部分文件需要密码
- **THEN** 两端 MUST 按文件显示独立密码输入框和对应文件名
- **AND** 在所有需要密码的文件完成扫描前 MUST 不得进入账户映射

#### Scenario: 文件失败阻止后续流程

- **WHEN** 选择集合中任一文件无法识别或解析失败
- **THEN** 两端 MUST 指出对应文件的错误并保留删除或重试入口
- **AND** 不得让其他文件单独进入账户映射、预览或确认

### Requirement: 多文件导入使用相同的聚合状态和操作顺序

Web 与 Android/iOS Native MUST 按选择文件、统一扫描、账户映射、流水核对、关系配对和确认导入的相同顺序呈现多文件导入；账户映射、流水摘要、关系候选、加载、错误、禁用和成功状态 MUST 表示同一组文件的聚合结果。DOM 与 Native 控件可以不同，但不得改变确认时机、错误范围或最终业务结果。

#### Scenario: 混合渠道进入账户映射

- **WHEN** 所有已选文件扫描成功且渠道包含多个类型
- **THEN** Web 与 Native MUST 进入同一个账户映射步骤并显示合并后的来源账户分组
- **AND** 不得按渠道拆成多个需要分别确认的导入流程

#### Scenario: 最终提交失败

- **WHEN** 多文件确认返回失败
- **THEN** 两端 MUST 保留可识别的失败状态并允许按当前会话重试
- **AND** 不得显示部分成功或局部完成状态
