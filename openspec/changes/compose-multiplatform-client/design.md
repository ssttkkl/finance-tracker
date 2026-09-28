## Context

See `proposal.md` for scope and `specs/` for the behavior contracts. 当前客户端由 Vite/React Web、Expo/React Native 和 5 个 TypeScript workspace packages 组成，没有 Kotlin 或 Gradle 工程。Web 是 10 项页面级功能的完整实现；Native 已有 5 项入口，其中分类、投资和工作区管理仍是不可用占位。API 由 Python/FastAPI 提供，本变更不修改服务端、API 或数据库。

Web 使用路径路由，当前 React 站点由 Render 托管。本变更不调整线上托管；Compose demo 通过本地 production distribution 和 SPA fallback 预览服务器启动。现有响应式阈值是 `600` 和 `1024` 逻辑像素；设计 token 已有 Cobalt 蓝、收入/支出语义色、Noto Sans SC、IBM Plex Mono、44 px 触控目标、空间与内容宽度。金额与数量沿 API 以十进制字符串传输。

Compose Multiplatform Web 使用 Kotlin/Wasm，官方平台稳定级别仍为 Beta；官方支持表列出 Chrome/Chromium Edge 119+ 和 Safari 18.2+。Compose Web 默认把 UI 绘制到 HTML canvas，Compose 浏览器导航默认使用 `#` URL 片段，因此需要实际验证可访问性与自定义路径历史适配。参见 [Kotlin/Wasm 浏览器版本](https://kotlinlang.org/docs/wasm-configuration.html)、[Compose Web 路由](https://kotlinlang.org/docs/multiplatform/compose-navigation-routing.html)、[Compose Web 视口](https://kotlinlang.org/docs/multiplatform/compose-css-styles.html) 和 [Kotlin 平台稳定级别](https://kotlinlang.org/docs/multiplatform/supported-platforms.html)。

## Goals / Non-Goals

**Goals:**

- 用同一份 Compose 页面和状态实现 Web、Android、iOS 的 10 项页面级功能。
- 将现有共享客户端整体按全局 layer-first 职责分包，并以 Lifecycle ViewModel + `StateFlow` 承载页面状态。
- 让领域模型与序列化/API 依赖隔离，所有页面固定文案进入 Compose Resources，并提供有界脱敏的本地错误日志。
- 保留既有 Web 路径、邀请链接和浏览器历史语义。
- 用 Material 3 重做视觉层级，保留 Cobalt 品牌色、系统深色模式和按窗口宽度适配。
- 继续使用现有 API、权限边界、精确金额语义与持久化事实源。
- 提供本地 Compose Web 一条命令构建并启动 demo；三端都保留本地构建和运行入口。
- 在三端 Compose 验收前保留 React Web、Expo 和 TypeScript 共享包；验收通过后按明确文件清单退场旧客户端，不切换线上入口。

**Non-Goals:**

- Kotlin/Spring 后端、API 形状、数据模型、数据库迁移或导入解析器重写。
- 桌面原生应用；`wide` 只覆盖浏览器大窗口、Android 平板和 iPad。
- 修改 Render/云端托管配置、添加 CI 发布流程、切换线上入口、提交/推送或部署。
- 保留 Native-only 的可编辑 API origin 调试 UI；各 target 改用显式构建配置提供 API origin。

## Decisions

### 1. 以官方 Kotlin Wizard 工程作为独立 Gradle 根

使用 [Kotlin Multiplatform Wizard](https://kmp.jetbrains.com/) 生成的 Compose Multiplatform 三端工程作为迁移起点，放在仓库根下的 `compose/`。保持 Wizard 当前生成的入口结构：`:shared` 是共享 KMP 库，包含 `commonMain`、Android、iOS、Kotlin/JS 与 Kotlin/Wasm 源集；`:androidApp` 是 Android 应用壳；`:webApp` 是 JS/Wasm 浏览器入口；`iosApp/` 是 Xcode 宿主。Android、iOS 和浏览器入口共用 `shared/src/commonMain` 中的 Compose UI。Gradle 根放在 `compose/` 可以和现有 npm workspaces、`web/` 与 `mobile/` 并存，迁移验收前保持旧端可回退。

Wizard 下载时使用项目名 `FinanceTracker`、包名 `com.finance.tracker`、Gradle 构建、Android + Compose UI、iOS + Compose UI、Web + Compose UI 和默认测试源集。初始版本集中在 `compose/gradle/libs.versions.toml`：Kotlin `2.4.20`、Compose Multiplatform `1.12.1`、Android Gradle Plugin `9.1.1`、Material 3 `1.12.0-alpha03`。这是生成模板的版本基线；先运行模板构建核实仓库的 Java/Android SDK 和依赖解析，再锁定并保持兼容，不在页面迁移中途升级。`compose/local.properties` 仅包含本机 Android SDK 路径，不纳入版本控制。

`shared/src/commonMain` 按职责全局分层，包根顺序固定为 `app/`、`core/`、`data/`、`domain/` 和 `presentation/`；每层可以在自身根目录下按业务区域分子包，但不得改成 feature-first 的顶层结构。`:shared` 继续作为一个 KMP module，不拆成大量 Gradle 子模块。

- `app/` 是组合根：创建平台依赖、Repository 实现、UseCase 和 ViewModel，并把依赖显式传入 presentation；不加 DI 框架。
- `core/` 放跨功能的导航/路由、主题、平台接口、网络基础设施、错误类型、精确十进制与格式化、诊断日志接口等共享能力。
- `domain/` 放纯 Kotlin 领域模型、Repository 接口、UseCase 和业务规则；不得依赖 Compose、Ktor、serialization/JSON 或平台 UI API。
- `data/` 放 Ktor API 数据源、可序列化请求/响应 DTO、Repository 实现和 DTO↔Domain mapper。Transport DTO 不再被 Composable 或 ViewModel 直接使用。
- `presentation/` 放 Compose shell、按业务区拆开的页面/组件、ViewModel、`StateFlow` UI state 与用户意图；Composable 只收集状态、渲染并派发意图，不直接调用 API client。

ViewModel 调用 UseCase，UseCase 依赖 `domain` Repository 接口，`data` 提供实现，`app` 手动组装完整依赖图。生命周期由已引入的 Lifecycle ViewModel 支持；状态暴露为只读 `StateFlow`，UI collect 并把用户事件交回 ViewModel。这样各页面可以独立拆文件，同时保持层边界和跨 target 复用。

文件选择使用 FileKit `0.16.0` 的 `PlatformFile` 与 Compose picker，在 Android、iOS、JS 和 Wasm 共享一套选择与业务状态；iOS picker launcher 必须保留在稳定的根组合位置。登录令牌存储、浏览器地址栏/历史、剪贴板、平台返回动作及系统外观读取由 `expect/actual` 或小接口隔离。API transport 使用 Ktor 多平台客户端：锁定 Ktor `3.6.0`，JSON 使用 Kotlin serialization `1.10.0`；`ktor-client-engine-defaults` 为各 target 选择引擎，通用认证头、超时、JSON、错误映射和重试策略留在共享层。Ktor 3.6 官方支持 WasmJs 与 multiplatform 默认引擎，见 [Ktor client engines](https://ktor.io/docs/client-engines.html)。

**考虑过的替代方案：**保留 TypeScript packages 并从 Kotlin 调 JavaScript 会继续产生两套运行时和 DTO；为 React Native、React Web 分别创建 Material 3 页面则不能满足“一份 Compose UI”的目标，所以都不采用。

### 2. Compose Web 使用 Kotlin/Wasm，并自建路径历史适配

Web 目标使用 `wasmJs` 和 Compose Multiplatform。公共 `AppRoute` 与现有 path/query 双向映射：启动时解析 `window.location.pathname` 与查询参数；导航时写入 `history.pushState`；监听 `popstate` 恢复页面；workspace 子路由始终携带当前 workspace 前缀。不得调用会把 canonical path 转成 `#fragment` 的默认绑定。

Native 将当前 `AppRoute` 与页面返回栈作为一个可保存的 `AppNavigationState` 一起恢复，避免 Activity/视图重建后当前页面与 Android 系统 Back 或 iOS 页面返回栈分离。启动参数只用于没有已保存状态时的初始路由；Web 仍以浏览器 path 与 history 为事实源，不使用 Native 页面返回栈。

Render 与任何线上托管配置保持不变。Compose Web demo 由本地 Gradle 命令生成 `wasmJs` production distribution，再启动仓库内本地预览服务器；未设置 `FT_API_ORIGIN` 时，`/config.js` 将客户端 API origin 指向预览同源地址，服务器再把 `/api/*` 按原 HTTP 方法、路径、请求体和必要请求头代理到 `FT_API_PROXY_ORIGIN` 指定的 FastAPI origin。显式设置 `FT_API_ORIGIN` 时，客户端直接使用该 API origin。代理响应状态与正文回传给 Compose；不能在到达 API 分支前拒绝 `POST`，不得记录请求/响应内容。静态请求返回 JS/Wasm/font/resource 文件，未知客户端路径回退至本地 `index.html`。本地 Chrome QA 验证注册 POST 已到达 FastAPI、`/w/<id>/...`、账本页面、`?invite=<token>` 直达/刷新及 `.wasm` MIME。`render.yaml` 和旧 React/Expo 源码在三端 Compose 验收前保留，验收后按任务中的清单移除旧客户端源码，不进行线上入口切换。

**考虑过的替代方案：**继续用哈希路由不能保留既有 URL；把 `#` 映射到 path 也会令分享链接、刷新和服务端回退不完整。自行管理 path 与 history 的代码很小，而且能明确覆盖当前契约。

### 3. 以 `WindowSizeClass` 驱动同一套 shell 与页面排布

在共享代码按可用逻辑宽度纯函数计算 `compact <600`、`regular 600–1023`、`wide ≥1024`，不读取 iPhone 型号、Android `smallestWidth` 或平台名称。登录与邀请使用单项表单最大宽度 720 px；数据表可占据可用宽度；窄屏单列。外壳从 compact 菜单、regular rail 过渡到 wide permanent navigation drawer；所有 target 采用同一规则。Regular rail 显式固定为 Material 3 的 80 dp 宽，并为页面内容保留剩余宽度；rail 与内容必须同时可见，尺寸变化后不能只切换导航而隐藏页面。

新增、查看和编辑收支流水继续是 F-04 同一任务：小于 600 px 使用全高详情 Sheet，达到 regular/wide 后使用侧边详情面板；窗口变化时保留打开详情与表单内容。账单导入仍独立为多步页面，筛选、详情和危险确认仅按宽度改变承载方式。Material 3 Navigation、Sheet、Dialog、表单和列表只表达现有字段、操作和状态，不另做 Web 与 Native 两套页面。

### 4. 视觉系统映射现有 Cobalt token，不移动业务信息

将当前 Web `design-tokens` 与 Native token 映射到 Material 3 `ColorScheme`、Typography、Shapes 和组件状态：主色以现有 `#0A63B8`/Cobalt token 为种子；浅/深主题保留当前语义色方向；金额仍使用等宽数字字体；间距、最大表单宽度、触控目标和响应式阈值沿用现有 token。主题选择订阅浏览器或系统外观，不重建页面状态。

Web 中 Material 3 `DropdownMenu` 点击后未能打开，且关闭 Material 3 对话框后 Compose Web 会丢失辅助功能语义代理，已在 Chrome 生产预览复现；因此浏览器选择器使用页面内展开的 Material 3 单选卡片，避免依赖 Web 弹层。Android/iOS 保留下拉菜单。两种表面都在选中时立即应用值；关闭 Web 卡片或收起 Native 菜单而不选择时值不变。该差异仅改变选择控件的承载方式，不改变字段、标签、选项顺序、筛选/请求结果或确认时机。

Chrome 生产截图发现中文字符显示为缺字方框。根因是 Compose Web 1.12.1 对缺失字符自动从 `fonts.gstatic.com` 下载 Noto 字体，而当前 Web 运行环境的跨域字体请求失败；这属于字体显示缺陷，不能按无害日志处理。Web 以同源、仅 `wasmJsMain` 可见的 Noto Sans SC WOFF2 资源预载并注册：首屏字集 2,028,440 字节，加上按需加载的 101 个同源回退分片 2,410,448 字节，总计 4,438,888 字节；产物许可、来源和重建脚本见 `compose/shared/assets/fonts/`。Android/iOS 继续使用各自系统的中文字体，原生包不包含这些资源。截图复核又发现 Noto Sans SC 不提供关系标签中的 `↔` 字形；F-05 共享标签改用等义文字“与”，并由 Chrome E2E 锁定显示。Cross-platform Impact Check：Web 字体加载/绘制与共享 F-05 关系标签受影响；Android/iOS 使用相同共享文案，但仍使用系统字体；页面操作、状态、API、FastAPI 和数据库不变。

当前模板的 Material 3 artifact 为 `1.12.0-alpha03`。页面只使用项目需要的标准 Material 3 组件和颜色角色，不使用 Alpha/Expressive 独有 API；先完成三端模板编译和一个真实组件切片，后续依赖升级单独评估兼容性。参见 [Compose Multiplatform compatibility](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html) 和 [Compose 1.12.1 dependencies](https://kotlinlang.org/docs/multiplatform/whats-new-compose-112.html)。

### 5. API 和财务数值模型只迁移客户端实现

以当前 TypeScript `contracts`、`api-client`、`core`、`design-tokens`、`presentation` 及 Web 行为为逐项迁移基线。`data` 层用独立的 JSON DTO 表达 FastAPI 请求/响应，并在 mapper 中转换为 `domain` 纯 Kotlin 模型；Repository 将 API 失败转换为领域错误，Composable 和 ViewModel 不接触 transport DTO、Ktor response 或序列化类型。所有金额、投资数量、单价和汇率继续以十进制字符串穿过 API 边界，领域计算不得先转 `Double` 再比较或序列化。

Native 登录令牌由 Android Keystore 加密值与 iOS Keychain 存储，Web 继续使用既有 `localStorage` 合同；令牌与错误日志隔离，安全存储失败时停止受保护请求。数据层对已知输入校验、认证失败、HTTP 状态和网络不可用做封闭分类；ViewModel 将可恢复错误映射为本地化资源键，未分类异常统一映射为未知错误。原始异常消息、调用栈、请求/响应正文不得传入 UI 或诊断日志。

Android 应用显式声明 `android.permission.INTERNET`。正式变体通过构建参数注入 HTTPS `FT_API_ORIGIN`；Debug 变体单独携带网络安全配置，只允许 `localhost` 的明文连接，以便连到本机虚构 API fixture。iOS Debug 使用单独的 Info.plist 声明 `NSAllowsLocalNetworking`，支持本机 fixture 的回环地址；Release 继续使用默认 ATS 策略。不得把本地网络例外合并到 Release，也不得放宽远程主机的明文策略。

账单文件由 FileKit picker 返回 `PlatformFile`；取消选择不改变已选文件或触发扫描。共享层先按文件名后缀与大小校验，按内容计算 SHA-1 去重，限制最多 20 份、每份 100 MB，并以扫描返回的文件索引保存逐文件密码。FastAPI 与 API 合同保持不变：批量扫描继续向 `/api/v1/cash-import/scan` 发送 JSON/base64 文件数组；预览和确认继续使用 `import_token`、映射修订、预览摘要、关系决策和幂等键。客户端逐个读取文件以计算摘要，但 JSON 请求体必须持有整批 base64 内容；在每份文件达到 100 MB 且一次选择 20 份的极端情况下，内存峰值仍可能很高。当前不能在不改变 API 的前提下流式传送该数组，需在功能完成后的性能阶段实测峰值并记录限制；如需降低上界，需要另行批准 API 协议变更。

### 6. 页面信息架构与冗余处理

信息架构沿用已确认的 10 项页面级功能，不合并页面：

1. 公开入口：认证、邀请预览/接受。
2. 工作区入口：选择、创建和失败恢复。
3. 收支：收支账本；其新建/查看/编辑/删除和凭证在账本上下文中进入自适应详情 surface；账单导入和收支分类仍各自独立页面。
4. 投资：持仓和事件为两个独立页面；事件证据在对应上下文查看。
5. 管理：工作区管理独立页面。

Material 3 重排时不删除用户业务字段、来源证据、筛选项、状态、确认或操作。明确移除的冗余仅有：Expo 中「分类/投资/工作区管理暂不可用」占位（用真实页面替代）；重复的装饰性卡片套层；已经由控件状态表达、且违反 UI 规则的常驻解释文本。所有有行为含义的文案、数据与字段仍保留；若实现中发现需删减业务信息，Flow-Back 更新需求并先取得用户决定。

**原型与设计审查：**用户明确免除了 HTML 原型，因为它无法展示 Compose 渲染。本变更不创建静态 HTML demo；以真实 Compose 纵向切片表达设计，并在最终 UI 上运行 Hallmark `audit`。截图宽度按 UI 规则覆盖 320、375、390、414、768、1440 px，其中 390 和 1440 px 必须存图审查。

### 7. 页面状态与错误按 ViewModel 边界流动

每项有状态的页面由 Lifecycle ViewModel 持有不可变 UI state，并通过只读 `StateFlow` 暴露；ViewModel 只调用 UseCase 和诊断日志接口。Composable 用 lifecycle-aware collection 观察状态，把点击、输入、提交、取消等用户意图发送给 ViewModel，不创建 `FinanceApiClient`、Repository 或 service locator。独立状态测试验证 loading、empty、success、recoverable failure、unknown failure 及重试时表单数据保留。App composition root 显式创建依赖并注入 ViewModel 工厂，避免全局单例和 DI 框架。

API 层定义的 `RemoteFailure` 先映射成无异常文本的领域失败类别，presentation 再映射为 `UiMessage` 中的 Compose resource key。已知无效登录仍使用不暴露账户存在性的通用提示；注册校验、网络不可用、服务端拒绝和暂时性服务故障分别用可操作文案；无法分类的错误统一给出未知错误。异常的 message、stack trace、用户输入和响应正文不得进入 `UiMessage`。

### 8. 使用 Compose Resources 和系统语言，不加语言设置

Compose 产品文案统一进入 `compose/shared/src/commonMain/composeResources/` 的字符串资源。简体中文作为默认资源，英语放入 `values-en`，并提供 `zh` 与 `zh-Hans` 目录以覆盖浏览器/系统返回通用中文或脚本中文的情况；Web、Android 和 iOS 共用同一组资源键。应用从 Compose resource environment 读取系统/浏览器语言；平台报告英语时选英语，中文时选简体中文，其他语言没有资源匹配时由默认中文目录回退。不添加语言菜单或本机偏好设置。

所有固定产品文案，包括按钮、菜单、输入标签、辅助技术名称、加载/空/成功/错误提示、验证规则和确认问题，都必须经 Compose Resources 查找；不得在业务源码中内联显示文案。来自服务端或用户输入的工作区名称、分类名、账单说明等仍按业务数据原值呈现，不伪装为翻译资源。资源键在三端共享，Web Chrome、Android 和 iOS 各选中文/英语执行一条核心 journey，检查无原始 key、遗漏或语言混杂；代码审查用范围化搜索找出剩余用户可见字面量。

### 9. 脱敏日志双写并按时间和容量清理

共享 `core` 暴露固定字段的 `DiagnosticEvent` 与 logger API：ISO-8601 时间、固定 feature/action 标识、可空错误码或 HTTP status、异常类型。不得附加异常 message、stack trace、请求/响应 headers 或 body、URL query、邮箱、密码、登录令牌、金额、账单内容或文件名；`Throwable` 进入 logger 前只提取异常类型。错误边界、Repository failure 和 ViewModel 捕获点都走同一 logger，因此一次错误只形成一条不重复的事件。

每个平台使用一对 sink：Web 同时写 Chrome Console 和浏览器 `localStorage`；Android 同时写 Logcat 和应用私有文件；iOS 同时写系统日志和应用私有文件。所有 sink 使用同一已脱敏事件，不传原始 exception。追加记录前按 UTF-8 序列化后的字节数和时间戳清理：先删除超过 30 天的条目，再在总量超过 1 MiB 时从最旧记录开始移除，直到保留限制内的最新条目。存储访问由串行 writer 保护，文件更新以临时写入/原子替换避免中途损坏；浏览器可用配额不足或存储被用户清理时，仍写开发者日志且不影响业务请求，也不递归记录 logger 自身失败。

本地日志仅供开发诊断，没有 app 内查看、搜索、导出或上传功能；不写远程服务。单测构造包含真实邮箱、密码、令牌、金额和账单正文的异常上下文，断言序列化事件完全不含这些字段和值，并验证 TTL、1 MiB oldest-first 清理和存储失败降级。

## Risks / Trade-offs

- **Compose Web 仍为 Beta 且不是浏览器 DOM 布局 →** 先实现可运行的 Compose 路由/文件选择/输入/语义切片，在 Chrome 中核验键盘、辅助技术名称、文本输入、裁切、滚动、文件上传和深路径历史；用户要求 Web QA 使用 Chrome，不运行 Safari。未覆盖的浏览器兼容性明确保留为发布前风险；任何关键 Chrome 任务不能完整完成时暂停该平台的全量迁移并记录阻断项。
- **Material 3、Kotlin、Compose 与 Ktor 版本演进快 →** 版本集中锁定，以本地三端构建、共享测试和 Chrome E2E 验证，不在功能迁移中途升级依赖；CI 不属于本次 demo 范围。
- **路径路由与 workspace 上下文容易分离 →** `AppRoute` 统一负责规范化，分别对 root、workspace root、子路由、邀请 query、权限拒绝、刷新、Back/Forward 编写单测和 Playwright E2E。
- **账单批量 JSON/base64 请求可能放大大文件内存占用 →** 不改变现有 API；通过 FileKit 文件句柄共享选择与读取，单份上限 100 MB；在性能阶段覆盖多文件峰值，并记录当前批量协议的内存上界与处置条件。正文不写入日志。
- **三个 target 共用组件仍可能出现语义或无障碍差异 →** 稳定语义 ID、屏幕阅读器/键盘走查和跨端 parity journey；允许系统状态栏与平台控件外观不同，不允许页面区域和业务行为不同。
- **旧新客户端并行期间容易只更新一端 →** Compose 验收完成前保留 React/Expo/TypeScript 回退；每个功能同步 Cross-platform Impact Check 与 parity test。三端全部验收后仅按已盘点的精确文件清单退场，不切换生产入口或演练 Render 回滚。
- **日志元数据可能从异常正文或平台回调意外夹带敏感值 →** 诊断事件只接受固定字段和枚举 action，禁止接收原始异常消息；在写入前白名单序列化并用包含敏感测试值的用例检查开发者 sink、本地存储字节和回收逻辑。
- **日志写入失败或 localStorage 配额不足 →** 日志是尽力诊断通道，不阻断原操作；平台开发者 sink 独立写入，持久化 sink 失败不触发递归 logger 调用。
- **资源目录有遗漏或 API 返回文案造成中英文混杂 →** 未知 API 异常不透传原始文本；ViewModel 使用封闭 `UiMessage` resource key；每个页面按 zh/en 各跑核心状态并静态扫描硬编码用户文案。
- **Native 邀请链接打开需要应用链接登记与签名 →** 路由解析同时接受现有邀请 token 和 `finance-tracker` scheme；Android App Links、iOS Universal Links 必须用真实 bundle/package ID 和签名证书在验收设备核验，外部域名关联配置缺失时不得宣称 Native 邀请深链验收通过。

## Migration Plan

1. 把 Kotlin Wizard 生成的 Gradle 根落在 `compose/`，先验证 Android、iOS、Wasm 和 JS 模板构建，再实现真实 Compose 纵向切片；锁定模板插件/库版本。
2. 先按 global layer-first 建好 app/core/data/domain/presentation 边界、手动依赖装配、DTO↔Domain mapper、ViewModel/StateFlow、错误类型、Compose Resources 资源目录与跨平台日志 sink；为边界、数据映射、资源回退、隐私字段和日志上限添加共享回归测试。
3. 按功能复用上述层完成认证/工作区、收支账本/流水、导入、分类、邀请、投资持仓/事件和工作区管理的全部功能；所有固定文案改用资源，所有错误经过分类与脱敏日志；修复本地 preview 的 API POST/写请求代理。
4. 在功能冻结后按 **Web（Chrome）→ Android → iOS** 分别进行构建、共享/平台测试和真浏览器/设备 QA，检查 10 项功能、三种 WindowSizeClass、中英资源、错误/空/成功状态、日志脱敏和导航；不使用 Safari。完成后再运行跨端 parity matrix、性能/安全检查与 Hallmark 最终 UI audit。
5. 更新 `docs/feature-map.md`、本地运行说明和 OpenSpec 验收证据；记录每个平台命令、设备/视口、日志与性能结果。Web 生产预览使用 Chrome，不配置 Render 或云端发布。
6. Web、Android 和 iOS 全部验收通过后，按 `tasks.md` 列出的精确文件清单移除 React Web、Expo Native 与 TypeScript 共享客户端；再构建/验证 Compose 本地入口，确认 FastAPI、API、数据库、Render 配置和生产入口未被改动。

## Open Questions

本地 demo 使用已验证的 `finance-tracker://invite/<token>` Native scheme。真实 HTTPS App Links/Universal Links 关联域名和正式签名材料不包含在本次范围内；后续只有需要正式分发时再补充。

### 10. 以 pen.dev 作为可视化设计工作区

`ui-spec/` 是语义设计合同，`design/` 是可视化设计工作区，Compose `commonMain` 是运行实现。三者通过页面 ID、组件语义 ID、状态名和响应式不变量关联，不要求 DOM、Compose 节点和 `.pen` 节点逐一相同。

- `ui-spec/tokens/*.json` 按 DTCG 风格保存颜色、字号、间距、圆角、触控目标和主题别名；该目录是唯一 token 事实源。
- `ui-spec/components/`、`ui-spec/patterns/` 和 `ui-spec/screens/` 只记录语义、字段、操作、状态、权限和响应式约束，不记录只能在画布中表达的绝对坐标。
- `design/finance-design-system.lib.pen` 是可复用组件和变量库；`design/login.pen` 是首个页面设计，覆盖登录/注册切换、正常、输入校验错误、服务端错误、提交中/禁用及 compact/regular/wide、light/dark 变体。
- pen.dev Desktop/IDE 通过 MCP 读取和修改这些文件；CLI 的 `interactive` 模式用于无头确定性编辑，Agent 模式只在存在用户登录态或 CI secrets 时使用。
- 不直接用 `sed`、JSON patch 或自定义脚本修改 `.pen` 内部节点 ID；token 生成器只产出 Kotlin token 和供 CLI/MCP 消费的变量清单，变量写入由官方工具完成。

**替代方案：**把 `.pen` 当作唯一规格会让 API 状态、权限和跨端不变量无法审查；把 pen.dev 当作截图导出器又失去 Git/MCP 协作价值，因此采用语义合同、视觉文件和 Compose 实现三层分工。

### 11. Compose 唯一主动工作流与旧端退场边界

根 `package.json` 移除 `web`、`mobile` npm workspace、Expo/React 默认依赖和旧启动命令；`.github/workflows/mobile-ci.yml` 改为 Compose 主线工作流，执行 Gradle/Compose Web、Android、iOS 相关检查，并可选执行 pen CLI 校验。`.devcontainer` 不再暴露 Expo 端口或设置 Expo 环境变量。`web/`、`mobile/` 和 TypeScript 共享包源码及 manifest 保留，但不在根安装、默认构建或功能地图的主动实现统计中。

该切换只改变本地开发和 CI 入口，不删除源码、不切换 Render 或其他线上入口；需要删除旧端时另行创建明确的迁移决策和回滚清单。
