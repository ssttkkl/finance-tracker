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

F-01、F-02、F-04 至 F-10 的 Compose Web 和 Native 页面 MUST 保持既有业务操作、状态、用户文案及稳定语义 ID，并采用已确认 Pen 稿登记的区域顺序；compact MUST 为无页面级横向滚动的单列，wide MUST 保持 Web 大屏的筛选、列表、详情与操作关系。

#### Scenario: 使用者在不同窗口尺寸完成页面任务

- **WHEN** 使用者在 compact、regular 或 wide 窗口打开任一已确认页面
- **THEN** 加载、空、错误和成功状态 MUST 具有相同的业务结果与恢复入口
- **AND** 主操作、筛选和详情 MUST 保持相同的可达性与确认语义

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

### Requirement: Compose authentication screens use the scoped Material 3 appearance

Compose 登录和注册界面 MUST 在现有 FinanceTheme 下使用 Material 3 描边输入框、主按钮、文字按钮和图标按钮，并保留 FinanceTheme 的颜色与字形。认证输入框和主按钮 MUST 局部使用 Material 3 小形状，桌面认证卡片 MUST 局部使用中等形状；不得改变 FinanceTheme 全局形状方案或认证既有字段、文案、校验、语义和提交流程。

#### Scenario: 登录与注册的空输入字段使用相同样式

- **WHEN** 使用者打开空的登录或注册表单
- **THEN** 描边输入框 MUST 在输入框内部显示标签
- **AND** 同一响应式等级下两种模式的首个输入框 MUST 从相同垂直位置开始
- **AND** 字段、按钮、形状、颜色、字形、字段顺序、标签、语义 ID 和无障碍含义 MUST 符合已确认的 Pen 设计

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

Compose 登录与注册的首个描边输入框 MUST 在同一响应式等级中从相同垂直位置开始。注册页的返回按钮 MUST 保留至少 `44` dp 的触控区域及现有无障碍名称。

#### Scenario: 在登录和注册模式间切换

- **WHEN** 使用者在相同窗口尺寸等级下切换认证模式
- **THEN** 两种模式的首个描边输入框 MUST 从相同垂直位置开始
- **AND** 注册页返回按钮 MUST 保留现有无障碍名称及 `44` dp 触控区域

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

### Requirement: 认证状态设计稿展示完整页面

Pen 设计稿 MUST 按 `COMPACT`、`REGULAR`、`WIDE` 三类窗口尺寸，为每个认证状态提供包含完整页面结构的独立画板，不得仅用局部状态卡片或控件片段代替完整页面状态。登录 MUST 覆盖默认、登录中和服务端错误；注册 MUST 覆盖默认、字段校验全部通过、字段校验失败、注册中和服务端错误。共 24 张主页面画板 MUST 保持对应布局类别、品牌移除、背景和字段顺序；深色主题沿用相同层级与布局，并使用现有颜色角色，无需重复绘制深色状态画板。

#### Scenario: 评审完整认证状态画板

- **WHEN** 设计评审查看任一认证状态和窗口尺寸类别
- **THEN** Pen MUST 展示该状态对应的完整登录或注册页面
- **AND** 注册页面状态 MUST 包含邮箱、密码和确认密码字段，并按强提示/弱提示规则展示空值反馈

#### Scenario: 深色主题打开认证页面

- **WHEN** 使用者在系统深色主题下打开任一认证状态
- **THEN** 页面 MUST 保持相同的信息层级、状态和窗口尺寸布局
- **AND** MUST 使用现有深色主题颜色角色，不得强制显示浅色主题色

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

### Requirement: Compose cash import follows the confirmed secondary-page design

Compose 账单导入页 MUST 遵循已确认的 Pen 与独立原型所定义的信息层级和响应式布局。`REGULAR` 和 `WIDE` MUST 先显示页面标题，再显示右侧关闭按钮；`COMPACT` MUST 先显示左侧返回按钮，再显示标题。二级页面标题栏高度 MUST 按 `COMPACT` / `REGULAR` / `WIDE` 分别为 72 dp、78 dp 和 84 dp。`REGULAR` 与 `WIDE` 模态框圆角 MUST 分别为 22 dp 和 24 dp，且不得添加额外阴影。页面打开时，关闭或返回按钮 MUST 显示原型中的蓝色焦点样式。

四步指示器 MUST 使用等宽步骤、编号或完成状态圆形标记及其下方标签，并独立于受 720 dp 宽度限制的阶段内容，`REGULAR` / `WIDE` 两侧内边距 MUST 为 36 dp，`COMPACT` MUST 为 12 dp。文件选择、账户映射和流水核对内容 MUST 居中且最大宽度为 720 dp；关系核对 MUST 使用可用容器宽度，并以来源到目标的卡片或行展示每项关系。页面 MUST 保留既有扫描、账户映射、流水核对、分配、关系类型、候选选择、拒绝与恢复、错误处理及确认语义。

完整账单导入路由 MUST 始终使用原型中的浅色 Finance 调色板，不受系统外观影响，且包含导航栏、顶栏、路由背景和导入界面。`REGULAR` / `WIDE` 模态框背景遮罩 MUST 使用现有深色遮罩 token 的 38% 透明度；模态框 MUST 阻止背景交互、保持模态键盘焦点并支持 Escape 或返回操作关闭。所有共享组件颜色 MUST 从当前 Compose 主题作用域解析。使用者离开账单导入页后，应用 MUST 恢复已配置的系统外观。

关系筛选器 MUST 显示全部、待确认和已匹配的数量。关系卡片 MUST 以原型中的紧凑层级展示来源与目标流水，不得常驻展示账户名称或重复的字段标题；关系时间 MUST 使用本地时间的 `MM 月 dd 日 · HH:mm` 格式。待确认的目标流水 MUST 使用一个显示“选择流水”的候选下拉框，不得再添加说明文案或独立的可见选择操作。候选选项 MUST 锚定在选择框附近以浮层展示，打开或关闭时 MUST NOT 改变关系卡片及列表的尺寸或位置。选择候选项 MUST 关闭浮层并更新目标流水与关系数量；按 Escape 或点击浮层外部 MUST 关闭浮层且保留当前选择。待确认状态 MUST 使用原型中的警告色标签。`COMPACT` 关系卡片 MUST 将来源和目标并列展示，并把状态放在下方；`REGULAR` / `WIDE` MUST 将关系状态放在关系行末尾。较少使用的关系类型以及拒绝/恢复操作 MAY 放入可通过键盘操作的辅助操作入口，并以状态标签呈现。

二级页面页脚 MUST 只在 `COMPACT` 显示分隔线；`REGULAR` / `WIDE` 不显示分隔线。`COMPACT` 页脚说明 MUST 显示在操作行上方，顶部与底部内边距分别为 8 dp 和 16 dp；`REGULAR` / `WIDE` 页脚内容的顶部与底部内边距 MUST 分别为 16 dp 和 24 dp。映射、核对、配对阶段的“上一步” MUST 使用描边次按钮；`REGULAR` / `WIDE` 的操作按钮 MUST 靠右对齐，`COMPACT` 的“上一步”和主操作 MUST 分别靠左和靠右对齐。`COMPACT` 文件选择阶段 MUST 将放弃提示显示在下一步操作上方。界面展示的文件格式 MUST 与原型的 `CSV · XLSX · PDF` 文案一致，同时保留现有 XLS 导入支持。

#### Scenario: 在 Wide 尺寸查看账单导入

- **WHEN** 使用者在 `WIDE` 窗口打开账单导入页
- **THEN** 页面 MUST 显示左侧标题、右侧关闭按钮和带有步骤标签的四步指示器
- **AND** 系统外观为深色时，导航栏、顶栏和导入界面 MUST 继续使用原型的浅色调色板
- **AND** 模态框背景 MUST 使用原型中的 38% 遮罩
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
