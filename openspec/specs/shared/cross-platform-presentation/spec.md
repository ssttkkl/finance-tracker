# cross-platform-presentation Specification

## Purpose

为 Web 与 Android/iOS Native 提供同一份可验证的 presentation 合同，统一用户可见文案、页面结构、状态转换、响应式等级和语义测试入口，同时保留 DOM 与 Native 控件各自的渲染方式。

## Requirements

### Requirement: Compose Web is the Web source of truth for current Native parity

当前 Web 产品目标 MUST 指 `compose/webApp` 和共享 Compose 页面；Compose Web 的启动、预览和浏览器 QA 工具 MUST 位于 `compose/qa/web/`。第一阶段涉及的 Native 页面 MUST 以 Compose Web 已验证行为作为事实源；同一任务的用户可见文案、信息区域顺序、主要操作、加载/空/错误/成功状态和业务结果 MUST 与 Compose Web `compact` 的合同一致。平台渲染器可以使用不同的控件，但不得以实现差异改变产品语义。

#### Scenario: Compare a first-phase screen

- **WHEN** 使用者在 Compose Web `compact` 和 Android 或 iOS Native 打开认证、工作区、收支账本、手工记账、凭证或账单导入中的同一页面
- **THEN** 两端 MUST 展示相同的页面标题、核心区域顺序、主要操作、状态语义和服务端结果
- **AND** 允许的控件差异 MUST 只影响交互表面，不得改变字段含义、确认时机或结果

### Requirement: Shared presentation semantics are addressable

跨端页面 MUST 为每个核心区域、操作和状态提供稳定的语义标识；Web 使用 `data-testid` 或等价语义属性，Native 使用 `testID` 或等价语义入口。标识 MUST 不依赖中文文案、数组位置或具体渲染控件。Cash Ledger 的 Finance 组件及其 Web/Native 实现 MUST 共享文案、状态、语义 ID、业务结果和确认时机；DOM 与 Compose 控件可以不同。

#### Scenario: A parity journey addresses a primary action

- **WHEN** 自动化测试需要打开收支账本的新增流水操作
- **THEN** Web 与 Native MUST 都能通过同一个语义标识找到该操作
- **AND** 修改按钮文案或把按钮替换为 Native 控件不得改变该标识

#### Scenario: 跨端测试定位收支账本筛选

- **WHEN** 跨端测试打开收支账本筛选
- **THEN** Web 与 Native MUST 通过同一个语义 ID 找到筛选入口
- **AND** 选择、清除、加载和错误结果 MUST 保持同一合同

### Requirement: Responsive classes map logical window sizes across platforms

系统 MUST 按逻辑窗口宽度而不是设备型号划分布局等级：小于 `600` 的宽度为 `compact`，`600` 至小于 `1024` 为 `regular`，`1024` 及以上为 `wide`。Web `compact` MUST 对应 Native phone，Web `regular` MUST 对应 Native tablet portrait 或窄窗口，Web `wide` MUST 对应 Native tablet landscape 或大窗口。收支账本 MUST 在 Web compact/regular/wide 与 Native phone/regular/wide 中保持相同的信息区域顺序、状态语义和操作关系。

#### Scenario: Android runs on multiple device forms

- **WHEN** Android 在手机、折叠屏、平板或自由窗口中改变可用逻辑宽度
- **THEN** Native MUST 根据当前窗口宽度切换布局等级，不得读取或分支具体设备型号
- **AND** 内容 MUST 保持单列、双列或大屏关系与对应 Web 等级一致，不能出现页面级横向滚动

#### Scenario: iPad uses a large window

- **WHEN** iPad 以 portrait、landscape 或分屏窗口显示第一阶段页面
- **THEN** Native MUST 根据当前窗口宽度使用 `regular` 或 `wide` presentation
- **AND** `wide` 页面 MUST 保留 Web 的导航层级、信息块顺序、筛选/详情/操作区域关系和相近密度

#### Scenario: 收支账本在不同窗口等级保持区域关系

- **WHEN** 使用者在 compact、regular 或 wide 窗口打开收支账本
- **THEN** 筛选、记录、详情和操作 MUST 按对应 Web 布局等级保持已登记的顺序与关系
- **AND** compact MUST 使用无页面级横向滚动的单列或可折叠区域，wide MUST 保留导航、筛选、表格和详情/操作区域关系

### Requirement: Confirmed Finance pages preserve shared workflow and responsive structure

F-01、F-02、F-04 至 F-10 的 Compose Web 和 Native 页面 MUST 保持既有业务操作、状态、用户文案及稳定语义 ID；当前 Compose 实现与 OpenSpec MUST 定义区域顺序和验收关系。`compact` MUST 为无页面级横向滚动的单列，`wide` MUST 保持 Compose Web 大屏的筛选、列表、详情与操作关系。

#### Scenario: 使用者在不同窗口尺寸完成页面任务

- **WHEN** 使用者在 compact、regular 或 wide 窗口打开任一已确认页面
- **THEN** 加载、空、错误和成功状态 MUST 具有相同的业务结果与恢复入口
- **AND** 主操作、筛选和详情 MUST 保持相同的可达性与确认语义

### Requirement: First-phase Native screens preserve Web state and action contracts

第一阶段 Native MUST 覆盖登录、工作区选择、收支账本浏览、手工记账、凭证查看、账单选择/扫描、账户映射、流水核对、关系审查和导入完成任务。每个任务 MUST 支持与 Web 对应的正常、加载、空、错误、禁用和成功状态；写入仍 MUST 由服务端确认，金额和数量 MUST 以字符串语义传输。

#### Scenario: A viewer opens a write action

- **WHEN** `viewer` 在 Web Compact 或 Native 打开记账或导入入口
- **THEN** 两端 MUST 以相同的只读/无权限语义禁用或拦截写操作
- **AND** 不得创建本地成功状态或提交不完整数据

#### Scenario: Import reaches relation review

- **WHEN** 用户完成账单扫描和账户映射并进入关系审查
- **THEN** Web 与 Native MUST 展示相同的关系状态、候选含义、拒绝/撤销动作和确认导入结果
- **AND** 取消文件选择、密码错误、请求错误和导入成功 MUST 分别落入可识别状态，不得显示空白页面

### Requirement: Platform differences and unsupported surfaces are explicit

平台差异 MUST 登记在共享合同或对应 OpenSpec 文档中，并同时声明两端必须保持的业务不变量。未提供或尚未实现的 target surface MUST 被明确标记为缺口，不得渲染成看似可用但无法完成任务的伪页面。

#### Scenario: Date and file controls use native surfaces

- **WHEN** Web 使用 popover、HTML file input 或表格，而 Native 使用 sheet、系统文件选择器或列表
- **THEN** 两端 MUST 保持相同的可选值、初始值、标签、确认语义和错误处理
- **AND** 这种控件表面差异 MUST 记录为允许的平台差异

#### Scenario: A user opens an unsupported Native surface

- **WHEN** Native 导航指向当前 target 尚未提供的分类、投资或工作区管理能力
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

Web、Android、iOS MUST 支持同次最多 20 文件、每份 100 MB、追加移除及按内容摘要去重。选择完成才发起解析；逐文件显示状态和密码重传。单文件失败不阻止可用文件继续；原文件和密码不得长期留存，需密码重试必须重新传该文件。客户端读取 MUST 有明确上限，不整批缓存原字节；本地文件失败保留安全原因且不阻断其他文件，请求结果未知时先核实。

#### Scenario: 文件名相同内容不同

- **WHEN** 追加同名但内容不同文件
- **THEN** 两份均保留；内容摘要相同只保留第一次

#### Scenario: 部分文件失败

- **WHEN** 一个文件无法识别，另一个可用
- **THEN** 两端都允许处理可用部分并显示错误

#### Scenario: 本地文件失败不阻断其他文件

- **WHEN** 同一任务包含不可读、超限或类型不支持的本地文件及合法文件
- **THEN** 客户端保留各失败文件的安全原因并继续上传合法文件到同一会话，不伪造服务端文件引用；读取有明确上限，原字节不整批缓存或长期保存
- **AND** 网络、权限或请求结果未知时保留已知会话并先核实，不当作普通文件失败跳过后继续写入

#### Scenario: 普通微信账单无需账号输入

- **WHEN** 用户导入没有本人微信号的普通微信账单
- **THEN** 使用稳定默认单一来源，不要求补微信号或额外来源标识；多账号时才按需进入文件设置区分

#### Scenario: 密码状态不重复显示原因

- **WHEN** 文件状态已显示“需要文件密码”，文件问题明细包含同一密码要求文案
- **THEN** 只显示状态、密码输入和文件重新选择操作，不重复显示问题明细中的同一句提示

### Requirement: 多文件导入使用相同的聚合状态和操作顺序

Web、Android、iOS MUST 共享“选择并解析文件 → 选择对应账户 → 重点核对预计收支 → 一次确认”的操作顺序。来源账户可以暂不选择，核对流水和关联为同一列表内的只读子动作。部分完成、仅草稿、无新增、全局回滚和未知提交状态必须可区分；同键重试返回同一结果。不得按渠道或关系拆成多个必经确认。

#### Scenario: 混合渠道

- **WHEN** 多个渠道可用且有待确认项
- **THEN** 一次确认保存允许内容，待确认回主页详情处理

#### Scenario: 事务回滚与部分完成

- **WHEN** 服务端分别返回全局回滚或部分成功收据
- **THEN** 两端分别显示未保存或实际成功，不混为全批失败

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

### Requirement: Compose authentication modes share a responsive shell

Compose 登录与注册 MUST 共用一个适用于 compact、regular 和 wide 的内部响应式表单结构，`COMPACT`、`REGULAR` 和 `WIDE` 均 MUST 使用宽度受限的居中白色表单卡片；`COMPACT` MUST 保留纵向滚动和窄屏边距。

#### Scenario: 不同响应式等级打开认证页

- **WHEN** 登录或注册页面在 compact、regular 或 wide 窗口中呈现
- **THEN** 两种模式 MUST 使用相同的响应式表单结构与居中白色卡片；`COMPACT` MUST 可纵向滚动且不得发生横向溢出

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

### Requirement: 注册表单展示实时、可区分的凭据校验项

注册表单 MUST 按邮箱、密码、确认密码的顺序呈现字段。字段校验反馈 MUST 位于字段标题下方、输入框上方，并与字段标题使用不同的次级文字样式。即使字段为空，强提示也 MUST 以灰色中性文案显示；弱提示在字段为空时 MUST 隐藏文案、图形符号及占位反馈，输入后才显示校验结果。通过状态 MUST 同时显示绿色对勾与文案；失败状态 MUST 同时显示红色叉号与文案，不得只依赖颜色。输入变化时 MUST 即时重新计算相关校验。

- 邮箱格式和确认密码一致性属于弱提示；密码至少 12 个字符属于强提示。
- 邮箱 MUST 使用现有共享格式规则校验；为空时隐藏反馈，非空且格式错误时显示失败状态。
- 密码为空或不足 12 个字符时 MUST 显示灰色中性强提示；满足规则后显示通过状态。
- 确认密码非空时 MUST 与当前密码完全匹配并即时显示结果；密码或确认密码变化时均 MUST 重新判断。
- 登录模式 MUST 不显示注册校验项或确认密码字段。

#### Scenario: 注册字段为空

- **WHEN** 注册表单中的邮箱、密码或确认密码为空
- **THEN** 邮箱格式与确认密码一致性弱提示 MUST 隐藏
- **AND** 密码字段 MUST 显示灰色「至少 12 个字符」强提示，且任何字段不得呈现红色失败状态

#### Scenario: 输入有效邮箱和符合规则的密码

- **WHEN** 使用者输入符合现有格式规则的邮箱且密码达到至少 12 个字符
- **THEN** 对应字段 MUST 即时显示绿色对勾及通过状态

#### Scenario: 输入格式错误的邮箱或过短密码

- **WHEN** 使用者输入非空但格式无效的邮箱，或输入少于 12 个字符的密码
- **THEN** 对应字段 MUST 即时显示红色叉号及失败状态

#### Scenario: 确认密码与密码不一致

- **WHEN** 确认密码非空且与当前密码不一致
- **THEN** 确认密码字段 MUST 即时显示红色叉号及失败状态
- **WHEN** 使用者继续输入直至两次密码完全一致
- **THEN** 确认密码字段 MUST 即时切换为绿色对勾及通过状态

#### Scenario: 密码变化时更新确认密码状态

- **WHEN** 使用者修改密码且已有确认密码
- **THEN** 确认密码状态 MUST 按新的密码即时重新判断

### Requirement: 注册确认密码和表单级错误保持安全语义

注册提交 MUST 要求邮箱、密码和确认密码均通过现有校验；确认密码 MUST NOT 发送至认证服务。提交校验错误及认证服务错误 MUST 显示在表单级通用错误位置，不得伪装成实时字段规则结果。密码和确认密码 MUST 保持隐藏输入语义。

#### Scenario: 注册确认密码不一致时提交

- **WHEN** 使用者提交两次密码不一致或缺少必需注册字段的表单
- **THEN** 客户端 MUST 阻止认证请求
- **AND** 表单 MUST 在通用错误位置提供提交校验反馈

#### Scenario: 注册信息校验通过

- **WHEN** 使用者提交有效邮箱、符合规则的密码和完全一致的确认密码
- **THEN** 客户端 MUST 仅将邮箱和密码发送至现有注册认证接口

#### Scenario: 登录或注册服务端返回错误

- **WHEN** 现有认证服务返回登录或注册错误
- **THEN** 错误 MUST 显示在表单级通用错误位置
- **AND** 字段级实时校验状态 MUST 继续只反映本地字段规则

### Requirement: 注册页以返回按钮承载返回登录导航

注册页 MUST 在标题左侧提供仅显示左箭头并返回登录页的按钮；按钮 MUST 具有「返回登录」无障碍名称，且注册页 MUST 不显示底部「已有账户？登录」入口。

#### Scenario: 从注册页返回登录

- **WHEN** 使用者在任一窗口尺寸的注册页触发标题左侧返回按钮
- **THEN** 页面 MUST 返回登录表单
- **AND** 返回按钮 MUST 具有「返回登录」无障碍名称但界面上不得显示文字标签，注册页 MUST 不显示底部登录入口

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

所有已登录 Compose 路由 MUST 由同一应用外框组织一级导航、页面标题、账号操作和路由内容。收支账本、收支分类、当前持仓、投资事件和工作区管理 MUST 保持为五个同级导航目的地；账单导入 MUST 是从收支账本打开的二级页面，不得出现在全局侧栏。`REGULAR` 和 `WIDE` MUST 默认显示可展开的图标侧栏；支持鼠标悬停时，指针进入侧栏 MUST 自动展开，离开侧栏及账号面板后 MUST 自动收起。鼠标点击导航项造成的焦点 MUST NOT 阻止指针离开后收起。侧栏已展开时，激活顶部控制 MUST 立即收起侧栏，并在指针或键盘焦点仍处于侧栏/账号面板内时抑制自动展开；指针和焦点离开后 MUST 恢复悬停展开。无悬停能力的设备 MUST 保留通过控制点击展开或收起的能力；键盘焦点仍 MUST 展开侧栏以支持键盘导航。悬停头像 MUST 打开账号面板；鼠标进入面板后 MUST 保持打开，离开头像与面板后 MUST 关闭。`COMPACT` MUST 使用可从顶栏打开的模态抽屉。一级目的地 MUST 在顶栏显示唯一页面标题，二级页面 MUST 在自身容器标题栏显示唯一页面标题，正文 MUST 不重复当前页面标题。身份与工作区操作 MUST 从侧栏底部的头像入口访问，顶栏 MUST 不再放置工作区切换或退出登录入口。头像 MUST 使用会话邮箱首字母，不得伪装成可上传或编辑的个人头像。

#### Scenario: 打开一级目的地

- **WHEN** 使用者在任一逻辑窗口等级打开收支账本、收支分类、当前持仓、投资事件、工作区管理或已登录邀请页面
- **THEN** 页面 MUST 显示当前目的地标题，五个导航目的地 MUST 作为同级入口展示
- **AND** 页面 MUST 保留该目的地需要的导航与正文内容
- **AND** 标题 MUST 只显示一次

#### Scenario: 在 regular 和 wide 窗口导航

- **WHEN** 使用者在 `REGULAR` 或 `WIDE` 窗口首次进入已登录页面
- **THEN** 侧栏 MUST 默认收起并显示五个一级目的地的图标
- **AND** 展开操作 MUST 显示每个目的地的文字，收起操作 MUST 恢复图标侧栏
- **AND** 导航切换 MUST 更新选中项和顶栏页面标题
- **AND** 图标项 MUST 提供可感知的名称与焦点样式

#### Scenario: 悬停展开侧栏与账号面板

- **WHEN** 使用者在支持悬停的 `REGULAR` 或 `WIDE` 窗口将鼠标移入侧栏任意区域
- **THEN** 侧栏 MUST 自动展开并显示目的地名称
- **AND** 鼠标离开侧栏及其账号面板后，侧栏 MUST 自动收起
- **AND** 鼠标悬停底部头像 MUST 自动打开账号面板，鼠标从头像移入面板时 MUST 保持打开
- **AND** 触屏 MUST 可通过侧栏控制按钮展开或收起；键盘 MUST 可通过聚焦和激活相同操作

#### Scenario: 鼠标导航不会阻止侧栏收起

- **WHEN** 使用者用鼠标点击侧栏目的地后将指针移出侧栏和账号面板
- **THEN** 侧栏 MUST 自动收起，即使点击产生的焦点仍留在目的地上
- **AND** 使用键盘移动焦点时，侧栏 MUST 在焦点位于侧栏内时保持展开

#### Scenario: 收起悬停展开的侧栏

- **WHEN** 使用者在侧栏因悬停展开时激活顶部收起控制
- **THEN** 侧栏 MUST 立即收起，并在指针或键盘焦点仍处于侧栏或账号面板内时保持收起
- **AND** 指针与焦点离开后再次将鼠标移入侧栏 MUST 恢复自动展开

#### Scenario: 在 compact 窗口导航

- **WHEN** 使用者在 `COMPACT` 窗口打开导航抽屉
- **THEN** 抽屉 MUST 按同一级顺序列出五个目的地及其可见名称
- **AND** 抽屉 MUST 在底部提供头像入口，且不得遮挡页面标题或造成横向溢出

#### Scenario: 打开账号与工作区面板

- **WHEN** 使用者点击侧栏底部头像
- **THEN** 账号操作面板 MUST 显示当前认证邮箱、可访问工作区列表、活动工作区标记与每个工作区的角色
- **AND** 面板 MUST 提供工作区管理入口和退出登录操作
- **AND** 选择另一工作区时 MUST 沿用现有工作区访问、路由更新和错误处理语义
- **AND** 面板 MUST 在当前窗口内完整可见，并支持键盘聚焦、激活、Escape 关闭及焦点返回头像

#### Scenario: 返回、切换工作区或退出登录

- **WHEN** 使用者打开任一已登录页面或账号操作面板
- **THEN** 适用时的返回操作、工作区切换、工作区管理、退出登录和响应式导航 MUST 继续可用
- **AND** 退出登录 MUST 沿用现有会话清除与登录入口语义
- **AND** 导航、正文与账号操作面板 MUST 不发生遮挡、焦点丢失或横向溢出

### Requirement: Secondary page container is shared across window sizes

所有页面级二级目的地 MUST 通过共用二级页面容器承载。`REGULAR` 和 `WIDE` MUST 使用隔离背景的模态浮层，并提供可访问的标题及关闭按钮；浮层宽、高 MUST 各占可用视口约 90%，同时保留视口边距和内部滚动空间。`COMPACT` MUST 使用全屏页面，并提供返回按钮和唯一标题。容器 MUST 管理背景不可交互、键盘焦点进入与返回，以及离开时由当前页面合同定义的未完成状态清理。新建的页面级二级目的地 MUST 复用同一容器，不得各自实现不一致的尺寸切换模式。

#### Scenario: 二级页面按窗口尺寸呈现

- **WHEN** 使用者从一级目的地打开页面级二级目的地
- **THEN** `REGULAR` 和 `WIDE` MUST 显示带关闭按钮的模态浮层，`COMPACT` MUST 显示带返回按钮的全屏页面
- **AND** 三种尺寸 MUST 保持相同的页面标题、内容顺序、业务动作、状态和结果语义
- **AND** `REGULAR` / `WIDE` 模态浮层的宽、高 MUST 约占可用视口的 90%，`COMPACT` MUST 铺满窗口

#### Scenario: 关闭二级页面并返回来源

- **WHEN** 使用者通过关闭按钮、键盘 Escape 或 Compact 返回按钮退出二级页面
- **THEN** 页面 MUST 返回其来源一级目的地并将键盘焦点交还给原入口
- **AND** 未完成状态 MUST 按该页面的业务合同处理

#### Scenario: 创建后续二级页面

- **WHEN** 后续功能新增页面级二级目的地
- **THEN** 该页面 MUST 复用相同的二级页面容器与窗口尺寸切换合同
- **AND** 页面不得被加入全局侧栏，除非它被明确设计为新的一级目的地

### Requirement: Compose 现金导入使用全屏对话框

Compose 导入 MUST 沿用 FinanceTheme、Material 3、现有配色字体及输入方式对应焦点语义；从账本打开覆盖当前视口的 Material 3 全屏对话框，COMPACT 单列，REGULAR/WIDE 保留表格。后台账本保持挂载且不可交互，导入不占据顶级页面；保留兼容地址与恢复入口。标题栏高 56 dp、左侧关闭、正文留白 24 dp、底部操作区高 56 dp，容器无圆角；关闭、Escape 与系统返回遵守既有任务退出保护。区域顺序为标题与离开入口、文件选择与解析、单独的“选择对应账户”区域、待确认优先的只读核对、当前主要操作及导入结果；不保留旧四步指示器、双栏或独立详情页。文件任务和“选择对应账户”任务 MUST 分开呈现，使用平面分隔行。“选择对应账户”中每组的账户菜单提供“＋新建账户”，表单预填来源名称与币种，并按既有名称规则推断类型；成功后回到同组并选中新账户。身份匹配的账户建议不因币种不足被隐藏；点击下一步时按目标账户合并补入所选来源币种，失败留在映射步骤显示错误并允许重试，未知结果先核实。核对 MUST 复用主页流水行、连续分页和右侧序号滚动条；有待确认项时默认只显示待确认，可切换全部，无待确认项时自动显示全部。滚动条定位时按绝对序号读取未加载项；返回详情保留筛选和列表位置。详情 MUST 复用主页 Regular/Wide 末端全高模态面板、Compact 全屏页面。详情只读展示摘要、问题、来源差异、完整关联成员和原文，不提供字段、来源或关系编辑操作。窄屏不横向滚动，账户表单最大宽度为 720 dp。浅／深色、键盘返回、系统返回及未完成退出合同跨端一致。

#### Scenario: 从账本打开和关闭全屏导入

- **WHEN** 用户从账本打开导入或直接访问兼容导入地址
- **THEN** 三端均呈现全屏模态任务，应用导航和后台账本不能被直接操作
- **AND** 关闭后恢复账本，已完成导入触发账本刷新；存在账户改动或未知提交时遵守原退出合同

#### Scenario: 预览列表使用主页同款分页与滚动条

- **WHEN** Web、Android 或 iOS 的导入预览包含多页记录
- **THEN** 以连续虚拟列表显示并可按序号定位未加载页；宽屏使用主页同款右侧竖直滚动条，手机沿用主页紧凑窗口滚动条交互
- **AND** 筛选与详情返回保留当前位置和筛选状态

#### Scenario: 三类窗口

- **WHEN** 390、768、1440 px 查看统一导入
- **THEN** 区域和动作顺序一致，主列表确认状态可见，详情不新增必经步骤

#### Scenario: 未确认关系

- **WHEN** 两条正式流水只有待确认关系
- **THEN** 尚未合入的正式流水保持独立，已有确认组不被拆开；当前计划可提交时确认导入可用，已保存的待确认关系以后在主页详情维护

#### Scenario: 关闭任务

- **WHEN** 尚未提交且存在独立账户改动
- **THEN** 同一退出询问提供撤销并退出、保留并退出或继续导入

### Requirement: Persistent Compose navigation focus indication follows input modality

在桌面 Compose 导航侧栏中，目的地通过键盘导航获得焦点时 MUST 显示焦点边框。目的地通过鼠标或触屏点击获得焦点时 MUST NOT 显示焦点边框；选中状态 MUST 保持可见。

#### Scenario: Pointer activation does not leave a destination outline

- **WHEN** 使用者在展开或收起的桌面侧栏中点击一个目的地
- **THEN** 选中的目的地 MUST NOT 留下蓝色焦点边框
- **AND** 选中标记和路由切换 MUST 保持正常

#### Scenario: Keyboard focus retains the destination outline

- **WHEN** 使用者在展开或收起的桌面侧栏中通过键盘将焦点移动到一个目的地
- **THEN** 该目的地 MUST 显示蓝色焦点边框，直到焦点移到其他位置

### Requirement: Desktop account menu hover remains stable during rail expansion

在支持悬停的 `REGULAR` 和 `WIDE` 窗口中，桌面账号面板 MUST 在指针停留于头像期间保持打开；侧栏 MUST 将悬停打开的账号面板视为同一临时悬停区域并保持展开。侧栏展开、收起或重新布局 MUST NOT 单独导致面板关闭。指针从头像进入账号面板时，面板 MUST 保持打开；只有指针离开头像和账号面板后，悬停打开的面板才 MUST 关闭。

#### Scenario: Keep the account panel open while the hovered rail expands

- **WHEN** 使用者将指针移到侧栏底部头像并保持不动，且侧栏正因悬停展开
- **THEN** 账号面板 MUST 持续打开，不得反复关闭和重新打开
- **AND** 账号面板处于悬停打开状态时，侧栏 MUST 保持展开
- **AND** 指针进入账号面板后，面板 MUST 保持打开
- **AND** 指针离开头像与账号面板后，面板 MUST 关闭

#### Scenario: Activate the account panel with the keyboard

- **WHEN** 使用者聚焦侧栏底部头像并按 Enter 或 Space
- **THEN** 账号面板 MUST 打开
- **AND** 使用者按 Escape 后，账号面板 MUST 关闭且焦点 MUST 返回头像

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

### Requirement: Compose 单值选择字段使用锚定弹出菜单

共享 Compose 页面中的单值选择字段 MUST 展示字段标签与当前值，并在触发时于字段附近弹出选项菜单。菜单 MUST 覆盖页面内容，不得撑开或改变字段、卡片、对话框及其父区域的尺寸或位置。Web、Android 和 iOS MUST 保持相同的选项、标签、当前值、选择结果和关闭语义；选择选项后菜单 MUST 关闭并提交对应值，点击菜单外部或按 Escape / Native 系统返回 MUST 关闭菜单且保留当前值。禁用字段 MUST 不得展开菜单。

#### Scenario: 所有值选择字段以弹出菜单选择

- **WHEN** 使用者打开任一共享 Compose 单值选择字段
- **THEN** 字段附近 MUST 显示选项菜单，菜单内容 MUST NOT 进入页面原有布局流
- **AND** 展开前后字段及其父区域的几何尺寸 MUST 保持不变
- **AND** 选择、点击外部或按 Escape 关闭后，字段 MUST 显示正确值且页面可继续操作

#### Scenario: 禁用的值选择字段不能展开

- **WHEN** 单值选择字段处于禁用状态
- **THEN** 字段 MUST 保留禁用语义
- **AND** 点击字段 MUST NOT 打开选项菜单或提交新值

### Requirement: Compose 账单导入映射以统一弹出菜单和账户摘要呈现目标

账户准备 MUST 使用锚定菜单显示已生效账户的名称类型币种；菜单关闭不丢选择、不撑开布局。来源缺映射允许暂不处理。自动建议只依据来源身份及目标账户有效类型／状态，不因币种差异隐藏唯一匹配账户。每组菜单提供“＋新建账户”，选择后打开独立创建表单，预填来源名称和币种，账户类型沿用旧 Web 名称规则；保存成功后返回并选中新账户，取消不改变映射。已选账户映射成功后，在用户点击“下一步”时按目标账户合并补入来源币种，不按来源组重复修改账户；失败留在当前步骤并显示错误，未知结果先核实。“选择对应账户”界面不提供已有账户编辑按钮。

#### Scenario: 从来源组下拉菜单创建并自动选择

- **WHEN** 用户从来源组下拉菜单选择“＋新建账户”并成功创建
- **THEN** 创建表单使用该来源的名称、币种及名称推断类型预填，返回后该组自动选中新账户

#### Scenario: 取消表单

- **WHEN** 未提交账户表单点击取消
- **THEN** 无账户改动，返回当前导入选择

#### Scenario: 下一步为已选账户补充来源币种

- **WHEN** 同一目标账户映射多个币种不完整的来源组
- **THEN** 保留账户原有币种并一次补入所有缺项，然后以最新修订进入预览；不为每个来源组重复更新账户

### Requirement: 账单导入主要操作显示匹配图标

账单导入的主要操作按钮 MUST 同时保留可见文字标签并显示与其操作含义匹配的图标；图标 MUST NOT 替代按钮文字。收支账本主页的「导入账单」FAB 菜单项 MUST 使用清楚表达导入/收件的图标，不得呈现为文件导出动作。

#### Scenario: 导入操作按钮保留文字并显示匹配图标

- **WHEN** 使用者在导入流程查看可执行操作
- **THEN** 选择文件、移除文件、返回、继续、确认导入、完成和编辑等主要操作 MUST 同时显示文字及含义匹配的图标
- **AND** Compact 布局中的图标 MUST NOT 遮挡文字或导致页面水平溢出
- **AND** 收支账本主页的「导入账单」FAB 菜单图标 MUST 表达文件导入/收件方向

### Requirement: 现金账单关键账户与密码操作保持跨端语义一致

Compose Web、Android 和 iOS MUST 为现金账单导入提供相同的来源账号分组、合并开关默认值与选择结果；平台可使用适合自身的渲染控件，但不得改变分组标签、目标选择或冲突清空语义。需要密码的账单 MUST 显示“提交”文字及其左侧的向右箭头图标，不能只显示无明确动作的通用重试按钮。关键操作 MUST 提供稳定语义标识，不依赖中文文案或渲染控件类型。

#### Scenario: 账号分组跨端一致

- **WHEN** 用户在 Web、Android 或 iOS 的同一次导入会话打开账户选择步骤
- **THEN** 各端按同一渠道和来源账号分组合同显示微信昵称组、支付宝脱敏账号组和文件回退组，且合并开关默认开启
- **AND** 相同来源组选择及冲突处理结果一致

#### Scenario: 密码提交动作显示文字和方向图标

- **WHEN** 文件需要密码且用户已输入密码
- **THEN** 重传按钮显示“提交”，左侧显示指向右方的箭头图标，并提供稳定的文件级语义标识

### Requirement: 关系待确认的操作与摘要层级

当详情存在待确认关系时，核对动作 MUST 留在关联建议区域，固定底部仍以“编辑”为主操作。已有关联 MUST 排在建议之前。关联流水卡片 MUST 复用账单原文成员卡片的标题与字段布局：关系标签位于右侧金额之前，字段列表上方有分隔线；关联卡片保留移除操作。关联摘要 MUST 先表达关系或记录身份，不得在标题与行内重复同一关系类型，也不得把多条记录压成一条无法分辨层级的长串。同一“关系待确认”状态 MUST 在所有页面使用同一语义色。

#### Scenario: 存在待确认关系

- **WHEN** 使用者打开含有待确认关系的收支详情
- **THEN** 固定底部 MUST 保留“编辑”为主操作，关联建议卡内 MUST 提供核对入口
- **AND** 关联摘要 MUST 先说明关系，再展示记录明细
- **AND** 已有关联卡的关系标签 MUST 位于右侧金额之前，字段列表上方 MUST 有分隔线

#### Scenario: 关联流水与账单原文的成员卡片

- **WHEN** 使用者在详情中比较已有关联流水与账单原文
- **THEN** 两处 MUST 采用相同的成员标题、标签、金额及字段列表布局
- **AND** 已有关联卡 MUST 保留移除操作，账单原文 MUST 保留来源字段原文

#### Scenario: 关联动作与业务标签可辨认

- **WHEN** 使用者在详情查看转账退回等带业务标签的收支，或准备添加关联
- **THEN** 业务标签 MUST 有明确的用户可见位置，不以内部代码代替
- **AND** 添加关联的动作名称 MUST 与所在区块标题区分，清楚表达将新增关联

#### Scenario: 状态颜色一致

- **WHEN** 同一笔收支在列表与详情显示关系待确认
- **THEN** 两处 MUST 使用相同的 warning 语义色

### Requirement: 关联搜索只展示可选目标

关联搜索结果 MUST 只展示当前关系种类下可选择的目标，不可选流水不得出现。无可选目标时 MUST 提供调整搜索条件或关闭的出口。日期选择与金额校验 MUST 复用既有控件，保留单日与精确金额匹配。搜索结果 MUST 复用账本与导入核对的瀑布流组件，包括时间分组表头、滚动吸顶、右侧滚动条、分组容器、行与列标题；关联搜索关闭可选的月度收支合计。搜索行用于选择，不提供打开动作或末尾操作列。

#### Scenario: 混合可选与不可用候选

- **WHEN** 关联搜索结果中同时存在可选和不可用的目标行
- **THEN** 搜索结果 MUST 只显示可选行并允许选择
- **AND** 不可用候选 MUST 不出现在结果中，也不得占用分页名额

#### Scenario: 没有可选目标

- **WHEN** 当前搜索条件下没有可选目标
- **THEN** 界面 MUST 显示空状态，并保留调整搜索或关闭的出口

#### Scenario: 搜索表格无打开动作

- **WHEN** 用户在宽屏查看关联搜索结果
- **THEN** 表头与数据行 MUST 采用相同的列布局，金额右侧不得保留空操作列
- **AND** 表头与首行 MUST 顺序排列，不能重叠
- **AND** REGULAR 宽度横向滚动表格时，右侧滚动条 MUST 留在可见视口内

### Requirement: UI 审计演示提供可核对场景

显式启用的 Compose 离线演示 MUST 默认提供至少 20 条可见收支和连续分页，涵盖本次 UI 修复的长文本、普通已确认、缺项草稿、关系待确认、多币种月汇总和业务标签；关联核对 MUST 提供完整明确候选，搜索只返回可选目标；导入 MUST 提供多来源账户组。合成数据和操作结果只留当前进程，不读取真实账单或请求生产 API，未知字段不得伪填。

#### Scenario: 打开丰富演示

- **WHEN** 使用者打开本次 Compose 离线演示主页
- **THEN** 默认可见收支 MUST 不少于 20 条，滚动可查看剩余页
- **AND** 可从现有详情、筛选和导入入口查看本次受影响场景

#### Scenario: 核对演示关联

- **WHEN** 使用者打开待确认关联并选择建议或搜索目标
- **THEN** 预览 MUST 保留明确真实成员和选定现金部分，最终确认前不修改合成账本
- **AND** 不可用目标不出现在搜索结果，日期和金额筛选保持现有语义
