## Context

See `proposal.md` for scope and `specs/` for the behavior contracts. 当前客户端由 Vite/React Web、Expo/React Native 和 5 个 TypeScript workspace packages 组成，没有 Kotlin 或 Gradle 工程。Web 是 10 项页面级功能的完整实现；Native 已有 5 项入口，其中分类、投资和工作区管理仍是不可用占位。API 由 Python/FastAPI 提供，本变更不修改服务端、API 或数据库。

Web 使用路径路由，当前 React 站点由 Render 托管。本变更不调整线上托管；Compose demo 通过本地 production distribution 和 SPA fallback 预览服务器启动。现有响应式阈值是 `600` 和 `1024` 逻辑像素；设计 token 已有 Cobalt 蓝、收入/支出语义色、Noto Sans SC、IBM Plex Mono、44 px 触控目标、空间与内容宽度。金额与数量沿 API 以十进制字符串传输。

Compose Multiplatform Web 使用 Kotlin/Wasm，官方平台稳定级别仍为 Beta；官方支持表列出 Chrome/Chromium Edge 119+ 和 Safari 18.2+。Compose Web 默认把 UI 绘制到 HTML canvas，Compose 浏览器导航默认使用 `#` URL 片段，因此需要实际验证可访问性与自定义路径历史适配。参见 [Kotlin/Wasm 浏览器版本](https://kotlinlang.org/docs/wasm-configuration.html)、[Compose Web 路由](https://kotlinlang.org/docs/multiplatform/compose-navigation-routing.html)、[Compose Web 视口](https://kotlinlang.org/docs/multiplatform/compose-css-styles.html) 和 [Kotlin 平台稳定级别](https://kotlinlang.org/docs/multiplatform/supported-platforms.html)。

## Goals / Non-Goals

**Goals:**

- 用同一份 Compose 页面和状态实现 Web、Android、iOS 的 10 项页面级功能。
- 保留既有 Web 路径、邀请链接和浏览器历史语义。
- 用 Material 3 重做视觉层级，保留 Cobalt 品牌色、系统深色模式和按窗口宽度适配。
- 继续使用现有 API、权限边界、精确金额语义与持久化事实源。
- 提供本地 Compose Web 一条命令构建并启动 demo；三端都保留本地构建和运行入口。
- 在 demo 开发期间保留旧 React Web 与 Expo 源码，不切换线上入口。

**Non-Goals:**

- Kotlin/Spring 后端、API 形状、数据模型、数据库迁移或导入解析器重写。
- 桌面原生应用；`wide` 只覆盖浏览器大窗口、Android 平板和 iPad。
- 在本次变更中修改 Render/云端托管配置、添加 CI 发布流程、切换线上入口，删除 React、Expo 或 TypeScript 共享包，或执行外部提交、推送和部署。
- 保留 Native-only 的可编辑 API origin 调试 UI；各 target 改用显式构建配置提供 API origin。

## Decisions

### 1. 以官方 Kotlin Wizard 工程作为独立 Gradle 根

使用 [Kotlin Multiplatform Wizard](https://kmp.jetbrains.com/) 生成的 Compose Multiplatform 三端工程作为迁移起点，放在仓库根下的 `compose/`。保持 Wizard 当前生成的入口结构：`:shared` 是共享 KMP 库，包含 `commonMain`、Android、iOS、Kotlin/JS 与 Kotlin/Wasm 源集；`:androidApp` 是 Android 应用壳；`:webApp` 是 JS/Wasm 浏览器入口；`iosApp/` 是 Xcode 宿主。Android、iOS 和浏览器入口共用 `shared/src/commonMain` 中的 Compose UI。Gradle 根放在 `compose/` 可以和现有 npm workspaces、`web/` 与 `mobile/` 并存，迁移验收前保持旧端可回退。

Wizard 下载时使用项目名 `FinanceTracker`、包名 `com.finance.tracker`、Gradle 构建、Android + Compose UI、iOS + Compose UI、Web + Compose UI 和默认测试源集。初始版本集中在 `compose/gradle/libs.versions.toml`：Kotlin `2.4.20`、Compose Multiplatform `1.12.1`、Android Gradle Plugin `9.1.1`、Material 3 `1.12.0-alpha03`。这是生成模板的版本基线；先运行模板构建核实仓库的 Java/Android SDK 和依赖解析，再锁定并保持兼容，不在页面迁移中途升级。`compose/local.properties` 仅包含本机 Android SDK 路径，不纳入版本控制。

`shared/src/commonMain` 持有 API DTO/序列化、请求合同、精确十进制字符串计算、业务状态、页面状态、路由模型、语义 ID、Material 3 主题和页面组合。文件选择使用 FileKit `0.16.0` 的 `PlatformFile` 与 Compose picker，在 Android、iOS、JS 和 Wasm 共享一套选择与业务状态；iOS picker launcher 必须保留在稳定的根组合位置。登录令牌存储、浏览器地址栏/历史、剪贴板、平台返回动作及系统外观读取由 `expect/actual` 或小接口隔离。API transport 使用 Ktor 多平台客户端：锁定 Ktor `3.6.0`，JSON 使用 Kotlin serialization `1.10.0`；`ktor-client-engine-defaults` 为各 target 选择引擎，通用认证头、超时、JSON、错误映射和重试策略留在共享层。Ktor 3.6 官方支持 WasmJs 与 multiplatform 默认引擎，见 [Ktor client engines](https://ktor.io/docs/client-engines.html)。

**考虑过的替代方案：**保留 TypeScript packages 并从 Kotlin 调 JavaScript 会继续产生两套运行时和 DTO；为 React Native、React Web 分别创建 Material 3 页面则不能满足“一份 Compose UI”的目标，所以都不采用。

### 2. Compose Web 使用 Kotlin/Wasm，并自建路径历史适配

Web 目标使用 `wasmJs` 和 Compose Multiplatform。公共 `AppRoute` 与现有 path/query 双向映射：启动时解析 `window.location.pathname` 与查询参数；导航时写入 `history.pushState`；监听 `popstate` 恢复页面；workspace 子路由始终携带当前 workspace 前缀。不得调用会把 canonical path 转成 `#fragment` 的默认绑定。

Native 将当前 `AppRoute` 与页面返回栈作为一个可保存的 `AppNavigationState` 一起恢复，避免 Activity/视图重建后当前页面与 Android 系统 Back 或 iOS 页面返回栈分离。启动参数只用于没有已保存状态时的初始路由；Web 仍以浏览器 path 与 history 为事实源，不使用 Native 页面返回栈。

Render 与任何线上托管配置保持不变。Compose Web demo 由本地 Gradle 命令生成 `wasmJs` production distribution，再启动仓库内本地预览服务器；服务器先返回 JS/Wasm/font/resource 文件，再将未知客户端路径回退至本地 `index.html`。本地 Chrome QA 验证 `/w/<id>/...`、账本页面、`?invite=<token>` 直达/刷新及 `.wasm` MIME。旧 `render.yaml` 与 React `web/` 保持原样并留作现状回退，不进行线上入口切换。

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

以当前 TypeScript `contracts`、`api-client`、`core`、`design-tokens`、`presentation` 及 Web 行为为逐项迁移基线。DTO 使用明确的 JSON 序列化；所有金额、投资数量、单价和汇率继续由十进制字符串承担。共享计算不得先转 `Double` 再序列化。Native 令牌由 Android Keystore 加密值与 iOS Keychain 存储，Web 继续使用既有 `localStorage` 合同；安全存储失败时停止受保护请求。

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

## Risks / Trade-offs

- **Compose Web 仍为 Beta 且不是浏览器 DOM 布局 →** 先实现可运行的 Compose 路由/文件选择/输入/语义切片，在 Chrome 中核验键盘、辅助技术名称、文本输入、裁切、滚动、文件上传和深路径历史；用户要求 Web QA 使用 Chrome，不运行 Safari。未覆盖的浏览器兼容性明确保留为发布前风险；任何关键 Chrome 任务不能完整完成时暂停该平台的全量迁移并记录阻断项。
- **Material 3、Kotlin、Compose 与 Ktor 版本演进快 →** 版本集中锁定，以本地三端构建、共享测试和 Chrome E2E 验证，不在功能迁移中途升级依赖；CI 不属于本次 demo 范围。
- **路径路由与 workspace 上下文容易分离 →** `AppRoute` 统一负责规范化，分别对 root、workspace root、子路由、邀请 query、权限拒绝、刷新、Back/Forward 编写单测和 Playwright E2E。
- **账单批量 JSON/base64 请求可能放大大文件内存占用 →** 不改变现有 API；通过 FileKit 文件句柄共享选择与读取，单份上限 100 MB；在性能阶段覆盖多文件峰值，并记录当前批量协议的内存上界与处置条件。正文不写入日志。
- **三个 target 共用组件仍可能出现语义或无障碍差异 →** 稳定语义 ID、屏幕阅读器/键盘走查和跨端 parity journey；允许系统状态栏与平台控件外观不同，不允许页面区域和业务行为不同。
- **旧新客户端并行期间容易只更新一端 →** React/Expo 在本地 demo 验收前后都保留；每个 Compose 功能开发同步 Cross-platform Impact Check 与 parity test。本次不切换生产入口，也不演练 Render 回滚。
- **Native 邀请链接打开需要应用链接登记与签名 →** 路由解析同时接受现有邀请 token 和 `finance-tracker` scheme；Android App Links、iOS Universal Links 必须用真实 bundle/package ID 和签名证书在验收设备核验，外部域名关联配置缺失时不得宣称 Native 邀请深链验收通过。

## Migration Plan

1. 把 Kotlin Wizard 生成的 Gradle 根落在 `compose/`，先验证 Android、iOS、Wasm 和 JS 模板构建，再实现真实 Compose 纵向切片；锁定模板插件/库版本。
2. 迁移共享合同、API 传输、精确十进制、主题、响应式路由及状态；完成认证/工作区、收支账本/流水、导入、分类、邀请、投资持仓/事件和工作区管理的全部功能实现。页面、API 与交互尽量复用 `commonMain`，按 WindowSizeClass 自适应。
3. 所有 10 项功能实现后冻结功能范围，再按 Web、Android、iOS 的顺序分别构建、运行单测和完成平台 QA；最后做跨端 parity matrix。开发期保留测试先行的共享合同测试，但不把某个平台的阶段性烟测当作平台验收。
4. 更新功能地图与本地运行说明，提供 Compose Web production distribution 构建与本地 SPA fallback 预览启动命令；用 Chrome 完成本地浏览器 QA，不配置 Render 或云端发布。
5. 完成 Web、Android、iOS demo 的本地构建与运行验证；旧 React/Expo 源码和线上入口保持原状，不触发正式 cutover 或删除。
6. 本次不清理旧客户端；如之后另行决定正式迁移和退场，再生成精确文件清单并单独确认。

## Open Questions

本地 demo 使用已验证的 `finance-tracker://invite/<token>` Native scheme。真实 HTTPS App Links/Universal Links 关联域名和正式签名材料不包含在本次范围内；后续只有需要正式分发时再补充。
