## Purpose

为 Web、Android 和 iOS 提供一套以 Web 现有行为为事实源的统一客户端，使三端共享页面任务、业务流程和 Material 3 设计，同时保留各平台必要的系统能力适配。

## ADDED Requirements

### Requirement: 三个平台都提供 Web 的全部页面级功能

Compose 客户端 MUST 在 Web、Android 和 iOS 提供功能地图中的全部 10 项页面级功能：认证、工作区入口、收支账本、收支流水、账单导入、工作区邀请、收支分类、投资持仓、投资事件和工作区管理。每项功能 MUST 有真实页面入口、调用既有 API 的完整流程和可观察结果；不得以“暂不可用”、伪成功或静默跳转代替实现。各页面的领域行为、角色限制、状态和结果 MUST 遵循相应 OpenSpec 主规格。

#### Scenario: 使用者在三端打开完整功能导航
- **WHEN** 使用者在 Web、Android 或 iOS 进入已认证客户端
- **THEN** 该平台 MUST 提供与当前工作区角色相符的 10 项页面级功能入口
- **AND** `viewer` 不得获得可执行的账本、导入、分类、投资事件或工作区管理写操作

#### Scenario: Native 用户接受工作区邀请
- **WHEN** Android 或 iOS 收到一个有效邀请链接并由已登录用户确认接受
- **THEN** 客户端 MUST 展示邀请工作区与角色，调用既有邀请接受 API，并在成功后进入可访问工作区
- **AND** 无效、过期、已使用或取消的邀请 MUST 保留对应错误或取消语义，不得加入工作区

#### Scenario: Native 用户完成仅 Web 已有的管理任务
- **WHEN** Android 或 iOS 用户搜索、维护收支分类，查看投资持仓或事件，或管理工作区成员
- **THEN** 客户端 MUST 提供真实的查询、筛选、详情和有权限的写入流程
- **AND** 金额、数量、估值状态、分页、角色和错误 MUST 与 Web 的对应结果一致

#### Scenario: 页面出现加载、空、错误或成功结果
- **WHEN** 任一页面正在读取、没有匹配数据、API 请求失败或写入成功
- **THEN** 三端 MUST 显示与 Web 语义相同的加载、空、可恢复错误或成功状态
- **AND** 请求尚未成功时 MUST NOT 显示写入成功或伪造账本结果

### Requirement: Material 3 视觉保留 Cobalt 品牌并跟随系统主题

Web、Android 和 iOS 的页面 MUST 使用 Material 3 的颜色角色、组件层级、形状、排版和交互状态，并沿用现有 Cobalt 品牌色。三端 MUST 跟随设备或浏览器的系统浅色/深色设置；主题切换 MUST NOT 丢失当前未提交表单、导入会话或导航状态。重新设计 MUST 保留已批准的信息架构和业务文案，不得添加教学区、实现术语或重复说明。

#### Scenario: 跟随系统深色主题
- **WHEN** 使用者把系统主题从浅色切换为深色
- **THEN** 三端 MUST 切换到同一套 Cobalt 深色 Material 3 配色角色
- **AND** 当前页面、筛选、未提交表单和导入步骤 MUST 保持原状

#### Scenario: 保留 Cobalt 品牌色
- **WHEN** 三端以浅色或深色主题渲染主要操作、选中状态和品牌标识
- **THEN** 主要色角色 MUST 来源于现有 Cobalt 品牌 token
- **AND** 文字、控件和焦点 MUST 在两种主题中保持可读与可辨

### Requirement: 三个平台按逻辑 WindowSizeClass 自适应

Web、Android 和 iOS MUST 按可用逻辑窗口宽度使用同一套 `WindowSizeClass`：`compact` 为小于 `600`，`regular` 为 `600` 至小于 `1024`，`wide` 为 `1024` 及以上。页面结构和导航 MUST 优先复用；随窗口等级改变的排布不得改变字段、信息区域顺序、筛选/详情/操作关系、操作时机或业务结果。移动窄屏 MUST 单列且无页面级横向滚动。

#### Scenario: Web 窗口跨越布局阈值
- **WHEN** 使用者把浏览器窗口调整到 `599`、`600`、`1023` 或 `1024` 逻辑像素宽
- **THEN** 页面 MUST 在对应阈值切换 `compact`、`regular` 或 `wide` 布局
- **AND** 已选筛选、表单值、详情和导入会话 MUST 保持

#### Scenario: Android 折叠屏和平板窗口改变
- **WHEN** Android 在手机、折叠屏、分屏或自由窗口中改变可用宽度
- **THEN** 客户端 MUST 仅按当前逻辑宽度选择布局等级，不得按设备型号分支
- **AND** `wide` 布局 MUST 保留与 Web 大屏相同的导航、信息顺序和任务关系

#### Scenario: iPad 分屏和旋转
- **WHEN** iPad 旋转或进入分屏并跨越布局阈值
- **THEN** 页面 MUST 按新的窗口宽度调整排布
- **AND** 不得丢失页面状态、产生页面级横向滚动或隐藏主要操作

### Requirement: 平台控件差异保留相同用户语义

平台可以使用不同的文件选择器、日期选择器、剪贴板接口、返回导航和登录令牌存储；每个适配 MUST 保持相同的选项和值、标签、确认时机、取消结果、校验、错误和业务结果。浏览器使用现有 `localStorage` 令牌合同，Android 与 iOS 使用各自安全存储；令牌均不得进入日志或普通页面状态持久化。

#### Scenario: 同一导入任务使用不同文件选择器
- **WHEN** Web、Android 和 iOS 使用各自系统能力添加同一组账单文件
- **THEN** 各端 MUST 保留同一文件集合、顺序、SHA-1 去重、大小限制、密码提示和扫描时机
- **AND** 取消选择 MUST 保留已有选择且不得开始扫描

#### Scenario: 不同日期控件提交同一日期
- **WHEN** 三端分别通过浏览器或系统日期控件选定同一日期范围
- **THEN** 请求 MUST 使用相同本地日期边界语义
- **AND** 展示值、时区换算和错误处理 MUST 与现有时间规格一致

#### Scenario: Native 令牌安全存储不可用
- **WHEN** Native 安全存储读取或写入失败
- **THEN** 客户端 MUST 给出可恢复的认证错误
- **AND** MUST NOT 明文保存令牌、伪造会话或继续执行受保护请求

#### Scenario: Web 和 Native 使用不同选择器表面
- **WHEN** 用户在 Web、Android 或 iOS 选择筛选项、账户或分类
- **THEN** Web MUST 使用可展开的 Material 3 单选卡片，Native 可以用 Material 3 下拉菜单
- **AND** 各端 MUST 提供相同的选项和值、标签与顺序，并在选中后立即应用
- **AND** 关闭 Web 卡片或 Native 下拉菜单而未选择时 MUST 保留原值，后续筛选结果、请求参数和业务结果 MUST 一致

### Requirement: Compose Web 保留既有路径、邀请链接和浏览器历史

Web MUST 保留当前客户端已公开的 URL，包括 `/`、`/w/<workspace-id>/`、工作区子路径 `/w/<workspace-id>/<child-path>`、`/cash-import`、`/cash-categories`、`/investment-holdings`、`/investment-events`、`/workspace-management` 及包含 `?invite=<token>` 的邀请链接。直接打开或刷新这些地址 MUST 呈现对应功能；导航 MUST 通过浏览器历史保持 Back、Forward、查询参数和工作区路径语义，不得把 canonical URL 改写为 `#` 路由。

#### Scenario: 直接打开工作区下的分类页面
- **GIVEN** 当前用户可以访问该工作区
- **WHEN** 浏览器直接打开或刷新 `/w/<workspace-id>/cash-categories`
- **THEN** Web MUST 恢复指定工作区并显示分类管理页面
- **AND** 地址栏 MUST 保留同一工作区路径

#### Scenario: 直接打开邀请链接并登录后接受
- **WHEN** 未登录用户打开包含 `?invite=<token>` 的有效邀请链接并完成登录
- **THEN** Web MUST 保留邀请 token 并继续展示邀请预览与确认接受流程
- **AND** 页面刷新或浏览器前进/后退 MUST NOT 丢失、消费或重复接受邀请

#### Scenario: 浏览器返回和前进
- **WHEN** 用户在工作区内依次打开账本、导入、分类或投资页面，再使用浏览器 Back 或 Forward
- **THEN** Web MUST 恢复对应页面与工作区范围
- **AND** MUST NOT 在 URL 中附加路由哈希或触发无意的重复服务端写入

#### Scenario: 请求未知或无权访问的路径
- **WHEN** 浏览器请求未知子路径或当前成员无权访问的工作区路径
- **THEN** Web MUST 展示可理解的错误或按既有工作区入口规则规范化路径
- **AND** MUST NOT 绕过 API 权限边界或泄露工作区数据

### Requirement: 客户端继续使用既有 API 和精确金额语义

Compose 客户端 MUST 调用现有 Python/FastAPI API，不得要求后端或数据库迁移来完成本变更。金额、数量、价格和汇率 MUST 在 API 与共享计算边界保持十进制字符串语义，不得用二进制浮点保存、比较或提交账务值。服务端仍是权限、写入、幂等、关系、导入确认和估值状态的事实源。

#### Scenario: 金额输入和提交
- **WHEN** 用户输入、编辑、筛选或提交现金金额、投资数量、价格或汇率
- **THEN** 客户端 MUST 保留现有小数位、币种、符号和舍入合同
- **AND** 请求与展示计算 MUST 不得因平台或窗口等级而改变精确值

#### Scenario: 客户端写入请求失败
- **WHEN** 服务端拒绝或未确认账本写入、分类变更、邀请、成员变更或导入
- **THEN** 客户端 MUST 显示对应错误并保留安全重试所需输入
- **AND** MUST NOT 提前更新为服务端未确认的成功结果

### Requirement: Web demo 可在本地启动并在真实浏览器验证

Compose Web MUST 提供可复现的本地构建与启动方式。本地预览 MUST 提供正确的应用入口、Wasm 与资源文件，并对客户端路径提供回退；真实浏览器验收按本次用户指定使用 Chrome。所有页面 MUST 可通过键盘完成主要任务，具有可见焦点、稳定的语义测试标识及可访问名称；关键流程 MUST 检查屏幕阅读器语义、文件选择、输入和滚动。

#### Scenario: 本地预览加载 Wasm 资源和深链
- **WHEN** 使用本地启动入口直接打开工作区、投资、分类、导入或邀请链接
- **THEN** 应用入口和 Wasm/静态资源 MUST 返回正确内容与 MIME 类型
- **AND** 页面 MUST 成功启动且保留请求路径

#### Scenario: 本地预览代理注册 POST 请求
- **WHEN** 使用者在 Compose Web 本地预览中提交注册或登录请求
- **THEN** 预览服务器 MUST 将 `POST` 请求及其路径、请求体和必要请求头转发到配置的 FastAPI origin
- **AND** MUST 将后端响应状态和内容返回给 Compose 客户端，不得在代理层误报 `405`

#### Scenario: 未单独配置 API origin 时使用本地预览代理
- **WHEN** `FT_API_ORIGIN` 未设置且本地 demo 配置完成
- **THEN** Compose 客户端 MUST 使用本地预览同源地址发送 API 请求
- **AND** 预览服务器 MUST 将 `/api/*` 请求代理到 `FT_API_PROXY_ORIGIN` 指定的 FastAPI origin

#### Scenario: 键盘完成核心任务
- **WHEN** 用户只使用键盘访问认证、创建流水、分类管理、导入确认和工作区管理
- **THEN** 所有主要操作 MUST 可聚焦、焦点可见且顺序可理解
- **AND** 对话框、Sheet 或详情关闭后焦点 MUST 回到触发入口

#### Scenario: 浏览器超出最低 Wasm 支持版本
- **WHEN** 浏览器不能提供应用所需的 WasmGC 或异常处理能力
- **THEN** 页面 MUST 显示可操作的兼容提示或进入经验证的 Web 回退构建
- **AND** 不得渲染空白页面或假装已进入应用

### Requirement: Compose 是唯一主动客户端工作流，旧端源码保留为迁移参考

Compose Web、Android 和 iOS MUST 作为仓库唯一主动开发、构建、测试和功能地图实现入口。根 Node workspace、默认依赖、启动命令、CI 和开发容器 MUST NOT 安装或执行 Expo/React 客户端。React Web、Expo Native 和 TypeScript 共享包的源码及各自 manifest MUST 暂时保留在仓库中作为迁移参考，但不属于主动实现统计；删除旧源码、切换线上入口或修改托管配置必须另行决策。

#### Scenario: Compose 工作流成为主动入口
- **WHEN** 开发者安装依赖、启动本地客户端或触发仓库 CI
- **THEN** 默认流程 MUST 只安装、构建和测试 Compose 工程
- **AND** 不得因为旧端源码仍在仓库而重新启用 Expo/React 工作流

#### Scenario: 需要查阅旧端迁移参考
- **WHEN** 开发者需要对照历史 React/Expo 行为或迁移未覆盖的边界
- **THEN** 可以直接读取保留的旧端源码和 manifest
- **AND** 读取旧端不得改变 Compose 的构建、测试、设计和发布入口

### Requirement: 设计合同、pen.dev 文件和 Compose 实现保持可追踪

每个重新设计的 Compose 页面 MUST 有对应的 `ui-spec/screens/<screen>.yaml` 语义合同和 `design/<screen>.pen` 设计文件；共享视觉组件 MUST 优先来自 `design/finance-design-system.lib.pen`。合同 MUST 描述字段、操作、权限、正常/加载/空/错误/禁用/成功状态和 `compact`/`regular`/`wide` 不变量，`.pen` MUST 表达视觉层级与组件组合，Compose MUST 复用现有 `commonMain` 组件或先补齐组件实现。

#### Scenario: 设计登录页
- **WHEN** 设计者或 Agent 开始修改登录/注册页面
- **THEN** MUST 先读取登录合同和 token 源，再通过 pen.dev Desktop/IDE MCP 或官方 CLI 修改 `.pen`
- **AND** 设计 MUST 覆盖认证切换、输入校验、服务错误、提交中/禁用以及三种窗口等级和两种主题

#### Scenario: 设计文件与实现提交
- **WHEN** 页面设计进入代码评审
- **THEN** PR MUST 能从页面合同链接到 `.pen` 和 Compose 实现
- **AND** 不得以手写 `.pen` 内部 JSON 或像素截图替代语义合同和真实 Compose 验证

### Requirement: token 源生成 Compose 和 pen.dev 消费变量

`ui-spec/tokens/*.json` MUST 是跨平台颜色、字号、间距、圆角、触控目标和主题别名的唯一事实源。确定性生成器 MUST 从 token 源生成 Compose Kotlin token 代码，并生成供 pen.dev CLI/MCP 应用的变量清单；任何平台和 `.pen` 设计不得创建未登记的硬编码 token。生成器 MUST 可重复运行且在源未变化时产生稳定输出。

#### Scenario: 更新主题 token
- **WHEN** 设计者修改 token 源中的浅色或深色值
- **THEN** 生成器 MUST 更新 Compose Kotlin token 和 pen.dev 变量同步输入
- **AND** 旧的生成文件不得静默保留与源不一致的值

### Requirement: pen.dev 本地 MCP 与 CI CLI 接入不泄露凭据

仓库 MUST 提供 pen.dev Desktop/IDE MCP 的本地 bootstrap 文档和固定版本 CLI 的可复现安装入口。CI MUST 在存在 `PEN_CLI_KEY` 时执行 `.pen` 状态检查、结构校验和 PNG 导出；缺少该 secret 时 MUST 明确跳过并将原因写入任务记录，不得伪报通过。CLI key、模型 API key、session 文件和真实设计 workspace 信息 MUST 不进入 Git。

#### Scenario: 本地通过 MCP 编辑设计
- **WHEN** 开发者在 pen.dev 中打开仓库设计文件并启用 Codex MCP
- **THEN** MCP MUST 能读取设计库、登录页 `.pen` 和 token 同步说明
- **AND** 修改后文件可以由 Git 追踪，Compose 仍以源码和合同作为运行事实

#### Scenario: CI 无 pen.dev 凭据
- **WHEN** Pull Request 没有 `PEN_CLI_KEY`
- **THEN** Compose 构建和测试 MUST 继续执行
- **AND** pen CLI 校验 MUST 标记为未配置并返回非阻断的明确结果，不得尝试交互登录

### Requirement: Compose 用户可见文案支持中文和英语

Compose Web、Android 和 iOS MUST 为所有固定用户可见文案提供简体中文与英语，包括页面标题、导航项、按钮、字段标签、辅助技术名称、加载/空/成功/错误状态和表单校验提示。客户端 MUST 按系统或浏览器语言选择文案；语言无法匹配时 MUST 使用简体中文。工作区名称、分类名称等使用者或服务端提供的业务数据仍按原值显示，不属于产品文案翻译。

#### Scenario: 使用简体中文系统或浏览器语言
- **WHEN** 任一平台启动时语言为简体中文或通用中文
- **THEN** Compose 页面和交互提示 MUST 使用简体中文
- **AND** 页面状态、辅助技术名称和错误提示 MUST 使用同一语言

#### Scenario: 使用英语系统或浏览器语言
- **WHEN** 任一平台启动时语言为英语
- **THEN** Compose 页面和交互提示 MUST 使用英语
- **AND** 页面状态、辅助技术名称和错误提示 MUST 使用英语

#### Scenario: 系统或浏览器语言不受支持
- **WHEN** 任一平台启动时语言不属于简体中文或英语
- **THEN** Compose 页面和交互提示 MUST 回退到简体中文
- **AND** 不得混用缺失资源键、原始错误文本或另一种语言的固定文案

### Requirement: 错误按可恢复性分类并提供友好提示

Compose 客户端 MUST 将认证、输入、服务不可用和意外异常区分处理。可由使用者修正或重试的错误 MUST 提供简短、可操作且已本地化的提示；登录凭据错误 MUST 保持不泄露账户存在性的通用提示；无法识别或不可恢复的异常 MUST 统一显示未知错误提示。HTTP 状态、网络错误和代理错误 MUST NOT 被误报为邮箱或密码错误。

#### Scenario: 注册输入可以修正
- **WHEN** 注册请求因必填项、格式或密码规则未通过而被拒绝
- **THEN** 页面 MUST 指出可修正的输入项并保留安全的表单内容
- **AND** MUST NOT 将输入错误显示为登录凭据错误

#### Scenario: 登录凭据无效
- **WHEN** 服务端因邮箱或密码无效而拒绝登录
- **THEN** 页面 MUST 显示相同的通用认证失败提示
- **AND** MUST NOT 暴露邮箱是否已注册

#### Scenario: 服务不可用或请求无法送达
- **WHEN** 注册、登录或其他操作因网络不可用、服务端失败或 HTTP 错误未成功完成
- **THEN** 页面 MUST 显示可重试或稍后重试的提示并保留安全重试所需输入
- **AND** MUST NOT 显示注册或登录成功

#### Scenario: 客户端遇到意外异常
- **WHEN** 错误无法归类为已知且可恢复的用户错误
- **THEN** 页面 MUST 显示统一的未知错误提示
- **AND** 不得将原始异常消息、调用栈或实现细节显示给使用者

### Requirement: 错误写入受限的本地诊断日志

Compose 客户端 MUST 将应用错误同时写入当前平台开发者日志与平台本地持久化存储。日志记录仅包含时间、固定的功能操作标识、可用的错误码或 HTTP 状态、异常类型；不得保存错误消息、调用栈、邮箱、密码、登录令牌、金额、账单内容、文件名或其他请求/响应数据。Web 使用浏览器本地存储，Android 与 iOS 使用应用私有存储。日志累计保留时间不得超过 30 天、占用不得超过 1 MB；任一上限先达到时 MUST 删除最旧记录，且 MUST NOT 提供应用内查看或导出界面。

#### Scenario: 记录认证或业务错误
- **WHEN** Web、Android 或 iOS 捕获一个认证、API、平台能力或意外异常
- **THEN** 对应平台 MUST 同时写入开发者日志和本地持久化日志
- **AND** 记录字段仅包含时间、功能操作标识、可用错误码/HTTP 状态和异常类型

#### Scenario: 日志触及任一保留上限
- **WHEN** 本地日志包含超过 30 天的记录或总量超过 1 MB
- **THEN** 客户端 MUST 删除最旧记录，直到同时满足两个上限
- **AND** 最新错误记录在可写入时 MUST 保留

#### Scenario: 错误上下文含敏感业务值
- **WHEN** 失败请求或异常上下文包含邮箱、密码、登录令牌、金额或账单内容
- **THEN** 开发者日志和本地持久化日志 MUST 不包含这些上下文值
- **AND** 日志记录 MUST NOT 包含请求/响应正文、原始异常文本或调用栈
