## 1. 思考

- [x] 1.1 阅读 `openspec/project-context.md`、领域词表、相关主规格、功能地图、Web/Native 页面与客户端 packages；确认 FastAPI、API 和数据库不在范围内。
- [x] 1.2 完成 `$grill-me` / `$grilling` 需求访谈；用户确认三端 10 项功能、原 URL/邀请链接、Material 3 + Cobalt、系统深色模式、`WindowSizeClass` 阈值、global layer-first + ViewModel/StateFlow、严格 DTO/Domain 映射、中英 Compose Resources 自动选语言、可恢复错误提示与脱敏本地日志、Compose 唯一主动工作流、pen.dev 双层接入、`ui-spec` token 事实源和登录页首个设计样板。
- [x] 1.3 记录 Compose Web 技术基线：Kotlin/Wasm 仍为 Beta；Chrome/Edge 119+、Safari 18.2+；默认浏览器导航使用 hash，因此须以真实 Compose 切片验证自定义 path/history。

## 2. 计划

- [x] 2.1 建立 A 类 OpenSpec 变更 `compose-multiplatform-client`，记录目标、非目标、回滚与验收范围。
- [x] 2.2 在 `design.md` 固定 10 项页面的信息架构；业务字段、文案、操作、状态、确认和证据均保留，只替换视觉层级与承载方式。
- [x] 2.3 记录冗余处理边界：用真实功能替代 Expo 的「暂不可用」入口；删去装饰性卡片套层和违反 UI 规则的重复常驻解释，不删除业务信息。
- [x] 2.4 用户明确免除 HTML 原型；本次以真实 Compose 纵向切片和最终 UI 浏览器/设备审查替代静态 HTML demo 与原型审批。
- [x] 2.5 按 Hallmark 设计准则和 `docs/ui-design-rules.md` 人工复核 Material 3/Cobalt token、10 项导航和 WindowSizeClass 排布；此阶段未形成最终 UI，最终审查保留在 5.3。
- [x] 2.6 更新范围设计：共享 `commonMain` 按 app/core/data/domain/presentation 全局分层、Composable 只接收状态与发送意图、DTO 映射纯领域模型；覆盖 Compose Resources 双语、可恢复错误分类、三端本地诊断日志和本地预览 POST 转发。

## 3. 任务拆分与一致性

- [x] 3.1 建立三端 10 项功能矩阵，覆盖页面入口、操作、加载/空/错误/禁用/成功状态、权限及结果；测试按功能地图 ID F-01 至 F-10 追踪。
- [x] 3.2 Cross-platform Impact Check：Web 是目标及事实源；Android、iOS 需要全部页面；共享层覆盖 API、财务值、路由、语义 ID、页面状态、主题和窗口分类；FastAPI、API、数据库不受影响。
- [x] 3.3 将现有 Web 单测约 143 项、共享 packages 单测 24 项、Expo 单测约 28 项及 Playwright E2E 约 64 项纳入迁移盘点；按现有场景移植或提供 Compose 等价证据，不把静态页面算成功能覆盖。
- [x] 3.4 运行 `openspec validate compose-multiplatform-client --strict`；变更通过，proposal、delta specs、design、tasks 无阻断不一致。
- [x] 3.5 Cross-platform Impact Check：本次层边界、ViewModel、Compose Resources、错误态和共享日志影响 Web、Android、iOS 的全部页面；本地 Node API 代理只影响 Web demo；FastAPI、API、数据库、React/Expo 在 Compose 验收前保持不变。

## 4. 构建

### 4.1 Compose 工程与真实 Web 能力切片

- [x] 4.1.0 从 Kotlin Multiplatform Wizard 生成三端 Compose 项目并核对 ZIP 结构：`androidApp`、共享 `shared`、`webApp` JS/Wasm 入口、`iosApp` Xcode 宿主及默认测试源集；项目名 `FinanceTracker`，包名 `com.finance.tracker`。源文件保存在 `/tmp/finance-tracker-kmp-wizard.zip` 作为本地临时下载，不记录在仓库。
- [x] 4.1.1 将 Wizard 模板安全解压到独立 Gradle 根 `compose/`，保留 Android/iOS/Web target 与版本目录；排除本机 `local.properties`，核验 wrapper、文件结构、Kotlin/Compose/AGP/Material 3 版本及 JDK/SDK 基线。Gradle 9.5.1 wrapper、JDK 21、Android SDK 37；Kotlin 2.4.20、Compose Multiplatform 1.12.1、AGP 9.1.1、Material 3 1.12.0-alpha03。Android Debug APK、Kotlin/JS、Kotlin/Wasm 编译通过；Wasm 与 JS production Webpack 在补跑 `kotlinWasmToolingSetup` 后通过。首次 Webpack 尝试因 Kotlin NPM 工具缓存缺 `webpack` 失败，已通过官方工具安装任务修复；模板初始产物 Wasm 10.32 MiB、JS 3.48 MiB，记录为后续性能优化风险。
- [x] 4.1.2 先为 `WindowSizeClass` 边界、既有 URL 路径解析/格式化和邀请 query 写失败单测，再实现纯共享路由/响应式分类。6 项测试在 Android host、iOS Simulator、Kotlin/JS 和 Kotlin/Wasm 均通过；真实浏览器 Back/Forward 交互留在 4.1.4 的生产预览 E2E 验证。
- [x] 4.1.3 实现共享 Compose Material 3 应用入口与 shell，包括 Cobalt 浅/深色、语义 ID、路径路由和 compact/regular/wide 导航；最终 Web/Android/iOS 运行验收分别安排在 6.2、6.3、6.4。
- [x] 4.1.4 建立生产 Wasm 预览服务器和 Compose Playwright 用例；当前 23 项 Chrome 用例覆盖 F-01 至 F-10、登录键盘操作、账本错误/空态、viewer 写权限、旧工作区深链接拒绝后的可访问工作区选择及浏览器前进/后退。浏览器验收记录见 6.2。
- [x] 4.1.5 保留 Wizard `androidApp`、`iosApp` 宿主及本地构建入口；三端完整页面构建、模拟器启动与窗口变化验收分别留在 6.3、6.4。
- [x] 4.1.6 修复 Chrome 生产截图中的中文缺字：Noto Sans SC 首屏子集 2,028,440 字节，另有 101 个同源按需回退分片、共 2,410,448 字节；总计 4,438,888 字节。来源、OFL 许可和重建脚本已登记；F-03/F-08 字体修复后的 390 px 与 1440 px 浅/深截图已复核，F-05 在修复 `↔` 字形后重新生成浅/深截图；中文及关系标签无缺字，字体不进入 Android/iOS 包。

### 4.2 共享客户端层与认证/工作区

- [x] 4.2.1 迁移并以共享测试覆盖 DTO、JSON 错误合同、路由 URL、精确十进制、输入校验、语义 ID 和 API mock fixtures。
- [x] 4.2.2 实现 Ktor 多平台 API client 与 Android Keystore、iOS Keychain、Web `localStorage` TokenStore；共享测试覆盖会话恢复、过期、401、暂时错误和登出。敏感字段不进入日志；target 验收见 6.2 至 6.4。
- [x] 4.2.3 实现 F-01 认证、F-02 工作区选择/创建/恢复与权限 gate；共享代码和浏览器用例覆盖成功、错误、重试及 viewer 禁用行为。
- [x] 4.2.4 实现 F-06 邀请预览/接受/失效流程及 Android/iOS `finance-tracker://invite/<token>` 接入；共享路由测试覆盖返回页面与 token 处理，设备验收及 HTTPS App Links/Universal Links 条件见 6.3、6.4、7.3。

### 4.3 收支与分类

- [x] 4.3.1 实现 F-03 收支账本、筛选、分页、摘要和错误/空态，并补充共享逻辑单测；浏览器与设备验收见 6.2 至 6.4。
- [x] 4.3.2 实现 F-04 流水新建/查看/编辑/删除、证据与交易关系、精确金额和权限；紧凑窗口用全屏 Dialog，较宽窗口使用并排布局；完整失败路径验收见 6.2 至 6.4。
- [x] 4.3.3 实现 F-07 分类目录、Web 同语义的上级路径搜索、层级编辑、排序、删除影响确认与真实 API 流程，并补共享合同测试；设备验收见 6.2 至 6.4。
- [x] 4.3.4 在 Chrome 153 验证 F-03/F-04/F-07 于 320、375、390、414、768、1440 px 下无横向溢出；窗口变化期间 F-04 表单与 F-07 分类表单保持可见。最新截图按 390/1440 px、浅/深色复核。较早的 Edge/WebKit 结果保留为历史检查点，不计入本次 Chrome 验收。

### 4.4 导入

- [x] 4.4.1 迁移并测试 SHA-1、20 份/100 MB 限制、账户映射、精确分配、关系候选和幂等提交的边界合同。
- [x] 4.4.2 实现 F-05 文件选择、逐文件密码、扫描、账户映射、预览、关系审查、确认和结果摘要；沿用既有 FastAPI API 与流程顺序。
- [x] 4.4.3 通过 FileKit `PlatformFile` 实现三端文件选择；Compose 浏览器用例已覆盖文件选择至提交的主流程。取消、重复内容、大小限制、密码/请求错误及原生选择器行为在 6.2 至 6.4 验收；批量 JSON/base64 内存风险在 6.7 实测。
- [ ] 4.4.4 在三端验收多文件跨渠道聚合、关系候选排序/过滤/分页、幂等确认及导入错误/空/成功状态。

### 4.5 投资与工作区管理

- [x] 4.5.1 实现 F-08 持仓筛选、估值、币种折算、表现范围和详情；金额、数量与受限行情状态使用共享精确值逻辑。
- [x] 4.5.2 实现 F-09 投资事件筛选、分页、证据详情和错误恢复，并补充共享逻辑测试。
- [x] 4.5.3 实现 F-10 工作区名称、成员、角色、邀请创建、权限限制、删除确认和删除后入口恢复。
- [ ] 4.5.4 检查跨端导航、工作区上下文、邀请 URL、浏览器历史和 Android/iOS 返回栈在全部 10 项功能间一致。

### 4.6 本地 demo 构建与启动

- [x] 4.6.1 增加 `npm run compose:demo`，构建 `wasmJs` production distribution 并启动支持 SPA 深链回退的本地预览；未设置 `FT_API_ORIGIN` 时，客户端使用预览同源地址，由代理转发到 `FT_API_PROXY_ORIGIN` 指定的 FastAPI；代理 origin 默认 `127.0.0.1:8000`。Chrome 本地预览与真实注册 POST 实测见 6.10。Cross-platform Impact Check：本次只修复 Web 本地 API origin 与代理连接；Android/iOS app 与 FastAPI/API 合同无改动。
- [x] 4.6.2 在根 `README.md` 和 `compose/README.md` 记录本地 Compose Web demo 命令、Android emulator 与 iOS Simulator 的运行方法、API origin 和可选仓库外 Gradle init script；CI、Render 与云端部署不属于本次 demo 范围。
- [x] 4.6.3a 更新 `docs/feature-map.md`，将 Compose F-01 至 F-10 登记为主动实现证据；旧 React/Expo 仅标记为 legacy 参考，不再作为主动实现或验证入口，保留既有 ALIGN-N 历史说明。
- [x] 4.6.3b 保留旧 React/Expo 源码、manifest 和线上入口作为迁移参考，但立即移出根 workspace、默认依赖、CI、启动命令和功能地图主动统计；源码删除和线上入口切换另行决策。
- [x] 4.6.4 先新增 `node --test web/tests/compose-preview-proxy.node-test.mjs`，修复前得到 `405 !== 422` 且假 API 未收到请求；修复方法门禁后验证注册 POST 的 path/query/body/content-type 到达假 API，`422` 状态与 JSON body 原样返回。随后真实 Chrome 注册流程发现空 `FT_API_ORIGIN` 会令客户端在发送 POST 前拒绝请求；改为默认使用 preview 同源，并由该 Node 测试检查 `/config.js` 的默认值。Chrome 真实 FastAPI 注册请求和 Node 1/1 通过，记录于 6.10。

### 4.7 共享客户端分层与状态管理

- [x] 4.7.1 新增 `compose/tools/layer-boundaries.node-test.mjs`，检查全部 commonMain 文件使用 `app/`、`core/`、`data/`、`domain/`、`presentation/` layer-first 包名，Domain 不依赖 Compose/Ktor/serialization/上层包，Data 不依赖 App/Presentation。重排 commonMain 与平台 source set 包路径，并把令牌/文件抽象放入 Core；Node 边界测试 3/3、`:shared:compileTestKotlinWasmJs` 通过。`:shared` 仍是单 KMP module，无 DI 或 Gradle 子模块。
- [x] 4.7.2 建立纯 Kotlin 会话、账本、分类、导入、投资领域模型及 Repository 接口与认证/会话 UseCase；生成的 67 个财务 DTO 对应 Domain 模型保留金额/数量字符串，Domain 无 Compose、Ktor、serialization/JSON 导入。
- [x] 4.7.3 增加 67 组双向 DTO↔Domain 映射、纯 Kotlin 结构化 JSON 值适配、`KtorSessionRepository` 与 `KtorFinanceRepository`；共享 Wasm 测试验证会话角色、嵌套投影和精确金额往返、JSON 数值往返、API 错误码/status 映射与敏感 import token 丢弃。
- [x] 4.7.4 在 `app/FinanceTrackerGraph.kt` 作为手动组合根显式构造 Ktor API client、Session/Finance Repository；使用 Compose Lifecycle `viewModelFactory` 创建 ViewModel，并由应用级 `ViewModelStoreOwner` 管理生命周期；未新增 service locator、DI 框架或 Gradle 子模块。
- [x] 4.7.5 认证、会话恢复、登录/注册、工作区选择/创建与邀请预览/接受由 `AccessViewModel`、`InvitationViewModel` 及 `StateFlow` 驱动，Compose 通过 Lifecycle 收集状态并派发意图。修复登录失败仍保留 `AUTHENTICATED` 状态的回归；Wasm 单测 74 项全通过。未知错误 code 保持为通用提示路径；持久化诊断日志在 4.9 实施。
- [x] 4.7.6 迁移收支账本、收支流水和分类页面状态/操作到对应 ViewModel/UseCase/Repository；保持 WindowSizeClass、深链、viewer 权限和三端操作结果。
  - F-03/F-04 共用 `CashLedgerViewModel`、Domain Repository 与 `SaveCashRecordUseCase`；覆盖筛选、分页、viewer 写权限、流水创建/编辑/删除、凭证和关系操作。F-07 由 `CashCategoryViewModel`、`CashCategoryUseCases` 和 Domain Repository 管理目录、搜索、编辑、排序及删除影响确认；Composable 只收集 Lifecycle `StateFlow` 并派发操作。
  - Cross-platform Impact Check：三个功能都使用 Web、Android、iOS 共用的页面状态、筛选值、权限和操作结果；presentation 继续通过 `WindowSizeClass` 自适应。深链接由共享 `AppRouting` 保留；FastAPI/API 合同和旧 React/Expo 未改。实际平台流程验收仍按 6.10–6.12 顺序执行。
  - Domain 文件读取端口已从 `core` 移入 `domain`；Presentation 不导入 Data、Ktor 或传输 DTO。`CashLedgerViewModel` 的状态模型已拆到独立文件；分类新增/编辑/删除和流水状态由相应 VM 管理。
  - 验证：2026-09-26（Asia/Shanghai），`./gradlew -I /tmp/ft-gradle-mirror.init.gradle :shared:wasmJsTest --no-configuration-cache` 通过（105/105）；Node 边界、资源、POST 代理检查 7/7 通过；`git diff --check` 通过。最终三端用户流程验证仍见 6.10–6.12。
- [x] 4.7.7 迁移导入的文件/扫描/映射/预览/确认状态及投资持仓/事件、工作区管理到对应 ViewModel/UseCase/Repository；保留文件索引、精确数值、筛选分页和幂等流程。
  - F-05 使用 `CashImportViewModel` 与 `CashImportRepository` 管理多文件身份、扫描、映射、预览、关系审查、幂等 key 和确认结果；F-08/F-09 使用投资 ViewModel/Repository 保留精确数值与事件/持仓筛选分页；F-10 使用 `WorkspaceManagementViewModel` 和 Session Repository 承载成员权限、邀请与工作区操作。
  - Cross-platform Impact Check：三个页面使用同一套 commonMain ViewModel、Domain 值与 API 映射；原生文件选择器表面可不同，文件值、错误、确认时机及结果不变。三端端到端验收仍按 6.10–6.12 执行；后端与 API 合同未改。
  - 验证：Wasm 共享测试覆盖 105 项且全部通过，含 Import、Investment、Workspace ViewModel、DTO 映射与 API 流程回归；三端设备端到端证据仍见 6.10–6.12。
- [x] 4.7.8 拆分 `App.kt`、`FinanceApiClient.kt`、`FinanceDtos.kt`、`CashLedgerScreen.kt`、`CashImportScreen.kt`、`InvestmentScreens.kt` 等大文件，删除 Composable 对 Ktor、transport DTO 与 API client 的直接引用并检查层循环依赖。
  - API 客户端现按认证、账本投影、分类、流水关系、导入、投资 endpoint 拆分，HTTP 请求核心和编码工具独立；账本、导入、投资 UI state 声明已与 ViewModel 文件分离，流水详情和投资事件页面也独立拆文件。
  - `presentation` 无 Data/Ktor/DTO/API client 导入；Data 只依赖 Core/Domain，Domain 不依赖 Compose/Ktor/serialization/JSON，上层 Data 访问仅从 `app/FinanceTrackerGraph.kt` 手动组合根进入。扩展层边界 Node 测试检查包名、允许边及层依赖环。
  - 验证：2026-09-26（Asia/Shanghai），`node --test compose/tools/layer-boundaries.node-test.mjs` 通过 5/5；`./gradlew -I /tmp/ft-gradle-mirror.init.gradle :shared:wasmJsTest --no-configuration-cache` 通过（105/105）；`git diff --check` 通过。当前最大共享 Kotlin 文件约 763 行；仍待阶段 5 独立 diff/工程复核。

### 4.8 Compose Resources 中英文

- [x] 4.8.1 盘点 10 项页面中的固定 UI、表单校验、错误/空/加载/成功文案及辅助技术名称；确定共享资源 key，不把服务端数据或用户内容伪装成产品字符串。4 份目录各含 497 个资源 key；账户/账单/user data 仍原样展示。
- [x] 4.8.2 新建 Compose Resources 简体中文默认目录与英语目录，补通用 `zh`/`zh-Hans` 选择，接入系统/浏览器 locale 自动选择和中文默认回退；不新增语言设置 UI。资源位于 `values`、`values-zh`、`values-b+zh+Hans`、`values-en`，运行时语言选择尚待 6.15 三端验证。
- [x] 4.8.3 把所有 Compose 固定用户可见文案迁移为 `stringResource`/对应 resource API，包含表单、导航、对话框、Sheet、日期/文件选择器外壳与 accessibility 文本；保持服务端/user data 原样展示。追加复核补齐了应用名和邮箱示例占位符。
- [x] 4.8.4 为 zh/en 和未匹配语言回退补资源/状态测试；运行范围化静态搜索，确认产品固定文案没有遗留在 Kotlin 代码字面量中，且没有原始资源 key 或语言混用。资源 Node 测试验证四份目录 key/占位符完全一致、英语不含汉字、Compose `Text`/placeholder/accessibility 无硬编码；实际 locale 运行时回退仍待 6.15。

### 4.9 错误分类与本地诊断日志

- [x] 4.9.1 先添加认证、注册输入、网络不可用、服务端失败和未知异常的分类回归；修复 UI 将任意异常都显示为“邮箱或密码不正确”的问题，所有文案通过本地化资源。另将 API `temporarily_unavailable` 归类为可恢复导入错误并本地化；共享回归证明重试保留导入 token 与幂等 key。Wasm 测试及 Chrome F-05 多文件重试 E2E 均通过。
- [x] 4.9.2 建立白名单 `DiagnosticEvent`，仅含时间、固定 feature/action、可用错误码/HTTP 状态、异常类型；统一由共享错误边界双写到 developer sink 与 local sink，不序列化 message、stack、URL、headers、body 或业务数据。
- [x] 4.9.3 实现 Chrome Console + `localStorage`、Android Logcat + 应用私有文件、iOS 系统日志 + 应用私有文件；本地存储按 30 天和 1 MiB 双上限 oldest-first 清理，无查看/导出界面。实际三端 sink 检查仍见 6.14。
- [x] 4.9.4 新增脱敏、限时、容量、最旧优先删除和存储失败不阻断业务的测试；含邮箱、密码、令牌、金额和账单正文的虚构异常在白名单记录边界回归中均不出现在 sinks；平台适配器实测仍见 6.14。
- [x] 4.9.5 执行 Cross-platform Impact Check：共享错误提示与日志记录逻辑影响 Web/Android/iOS；`localStorage`、Logcat/私有文件、iOS 系统日志/私有存储由各 target adapter 提供；平台文件/日期选择器保持值、确认语义、错误和结果一致；FastAPI/API 不变。

### 4.10 pen.dev 设计工作区与 token 合同

- [x] 4.10.1 建立 `ui-spec/tokens/`、`ui-spec/components/`、`ui-spec/patterns/` 和 `ui-spec/screens/` 目录；用 `ui-spec/tokens/*.json` 固定跨平台 token 事实源，并为登录/注册页写出字段、操作、权限、正常/加载/空/错误/禁用/成功状态及 compact/regular/wide 约束。
- [x] 4.10.2 安装并固定 `@pen.dev/cli` 版本，提供 `pen status`、本地 `pen interactive`、Codex MCP bootstrap 和 CI `PEN_CLI_KEY` 配置说明；不提交 session、CLI key、模型 key 或 workspace 私密信息。已核验 `pen 0.3.9`、认证状态和 Personal workspace；凭据留在用户目录。
- [x] 4.10.3 通过官方 pen CLI/MCP 生成合法的 `design/finance-design-system.lib.pen` 与 `design/login.pen`，包含登录/注册切换、校验错误、服务错误、提交中/禁用及 light/dark、compact/regular/wide 视觉状态；记录生成命令和认证前置条件。
- [x] 4.10.4 实现确定性 token 生成器：从 `ui-spec/tokens/*.json` 生成 Compose Kotlin token 和 pen.dev 变量同步清单；生成器不得直接修改 `.pen` 内部节点 JSON，并添加稳定输出检查。
- [x] 4.10.5 更新 `docs/ui-design-rules.md` 引用的工作流说明、根 README、Compose README 和 `docs/feature-map.md`，明确 Spec → `.pen` → Compose 的关系、登录样板入口和旧端 legacy 边界。

### 4.11 Compose 主动工作流切换

- [x] 4.11.1 从根 `package.json` 和 lockfile 移除 `web`/`mobile` workspace、Expo/React 默认依赖及旧启动命令；保留旧端目录和 manifest，不让根安装流程解析其依赖。干净 `npm ci --no-audit --no-fund` 通过，lockfile 不含 legacy workspace 或 Expo/React 包。
- [x] 4.11.2 用 `.github/workflows/compose-ci.yml` 替换 `.github/workflows/mobile-ci.yml`，执行 Compose 共享测试、Web/Android/iOS 适用构建，并在 `PEN_CLI_KEY` 存在时运行 pen CLI 校验/导出；无 key 时非阻断跳过且输出明确原因。`pr-checks.yml` 的客户端 job 已同步切换。
- [x] 4.11.3 清理 `.devcontainer` 的 Expo 端口和环境变量，更新 `docs/feature-map.md` 将 Compose 标为主动实现、React/Expo 标为 legacy 参考，并更新根 README 的启动、能力表和安全存储描述。

## 5. 审查

- [x] 5.1 历史产品范围复核：初始迁移范围为 10 项 Web 功能、三端入口、Material 3/Cobalt、窗口分类与原 URL；本次执行合同另扩展到共享层重构、双语资源、错误分类/本地日志、demo POST 代理修复以及验收后退场。最终范围复核待 5.5。
- [x] 5.2 历史工程复核：最初共享实现和本地 demo 的依赖/网络边界已记录；本次架构重构后的纯 Domain 依赖边界、Repository 映射、日志隐私、存储回收及 POST 代理须在 5.6 重新独立复核。
- [x] 5.3 历史 Hallmark `audit`：认证语义名称、F-05 字形和 F-10 regular 邮箱宽度 finding 已修复，历史复审为 0 critical、0 major、0 minor；共享 UI 和本次国际化完成后的最终 UI audit 在 5.7 重新执行。
- [x] 5.4 历史差异复核仅适用于 2026-09-25 基线；本次 Compose 架构、资源、测试和本地 proxy 变化后的最终差异复核在 5.8 重新执行。
- [x] 5.5 产品范围复核：复核 `docs/feature-map.md`、10 项页面功能与执行合同。Web Chrome 和 Android 已覆盖 F-01–F-10；iOS 已走通 F-01–F-04、F-06–F-10，F-05 文件选择→扫描→映射→确认仍有明确 QA 缺口并保留在 6.12。语言回退与错误/空/加载状态有 Web/Android 证据；iOS locale 已做账本界面抽样。诊断日志没有用户查看/导出界面；React/Expo 仍保留；不改 FastAPI/API、不部署。Cross-platform Impact Check 见 3.5、4.9.5、6.10.1、6.10.2 和 6.12.1。范围检查完成，未把未验收的 iOS 导入记为已覆盖。
- [ ] 5.6 工程与安全复核：独立检查 `presentation→domain`、`data→domain`、`app` 手动装配依赖方向，Domain 无 Compose/Ktor/JSON，DTO mapper 完整；验证异常不携带到 UI/log message、所有日志 sink 白名单字段、30 天/1 MiB oldest-first 回收与 POST 代理隔离。
- [x] 5.7 最终 UI 复核：当前运行时未暴露可调用的 Hallmark `audit` 动作，未运行 CLI；按本地 Hallmark audit 规则及 `docs/ui-design-rules.md` 人工检查截图。范围：Web F-03/F-05/F-08 的 390/1440 px 浅/深截图；Android compact 登录、F-05 导入、F-07 分类、F-08 持仓深色、F-10 工作区；iPhone 17 邀请/错误态/英语与未支持语言回退、iPad 11 分类/事件、iPad 13 持仓深色/工作区宽屏。Finding：0 critical、0 major、1 minor。Minor：Web 1440 px 收支账本筛选区按 4 组纵向展示，首屏只能看到首条流水的一部分（`CashLedgerScreen.kt:273–289`）；接受当前布局，保留完整筛选项可见与跨端顺序一致，后续可再评估收起低频筛选。无 major，因此不需要修复后复审。该记录为人工审查，不声称运行了 Hallmark 工具动作。
- [ ] 5.8 由非实施上下文复核最终 diff 与 OpenSpec；检查旧客户端清单、依赖删除、构建脚本/CI、feature map、无新增后端合同、无错误地暴露异常内容以及所有未勾选 task 的原因。
- [x] 5.9 独立复核 pen.dev 接入：确认 `.pen` 由官方 CLI/MCP 生成、token 事实源没有重复定义、CLI secrets 不入 Git、旧端仅退出主动工作流且没有误删源码；finding：0 critical、0 major、0 minor。`.pen` 仅通过 interactive MCP 生成，token 生成器只写 Kotlin/变量清单；凭据和 workspace 信息未入仓库。

## 6. 测试与 QA

下方 6.1–6.9 中 2026-09-25 的通过结果是当前重构前历史基线，不代替本轮最终验证。完成全部功能与分层/资源/日志实现后，严格依次执行 6.10 Web Chrome、6.11 Android、6.12 iOS，再完成 6.13 parity 和专项检查。

- [x] 6.1 运行共享单测，覆盖迁移的 contracts/API/presentation 行为、精确金额、路由、SizeClass、导入边界、分类搜索和投资展示逻辑；最近一次 `:shared:wasmJsTest` 通过。
- [x] 6.2 **Web 第一阶段：**按用户指示使用 Chrome 153，不运行 Safari。`FT_GRADLE_INIT_SCRIPT=/tmp/ft-gradle-mirror.init.gradle npm run demo:compose --workspace=finance-tracker-web` 本地构建并启动成功；Vitest 152/152、`npx tsc -p web/tsconfig.json --noEmit` 通过；生产预览 Chrome E2E 最新 24/24 通过，URL `http://127.0.0.1:5186/`。新增 F-10 在 834 px regular 下邮箱最小宽度回归，修复前失败、修复后通过。覆盖 F-01 至 F-10、viewer 旧工作区深链接被拒后选择可访问工作区、viewer 写入限制、工作区深路径和浏览器 Back/Forward；预期 API 403 已单独记录，非预期控制台/网络/静态 HTTP 错误为 0，Wasm MIME 为 `application/wasm`。Gradle 使用仓库外 init script 的阿里云/腾讯镜像；未修改项目仓库源。Web 阶段完成。
- [x] 6.3 **Android 第二阶段：**Web Chrome 阶段完成后执行 `./gradlew -I /tmp/ft-gradle-mirror.init.gradle :shared:testAndroidHostTest :shared:compileAndroidDeviceTest :androidApp:assembleDebug -PftApiOrigin=http://localhost:8181 --no-configuration-cache`，host 65/65、APK 与 device-test 编译通过；`./gradlew -I /tmp/ft-gradle-mirror.init.gradle :shared:connectedAndroidDeviceTest --no-configuration-cache` 在 `FTComposeQA_API36` / `emulator-5554` 67/67 通过。虚构 fixture UI 流程覆盖 F-01 至 F-10、系统 CSV 选择器、邀请 URI 生成/接受、viewer 写限制、成功与错误恢复。WindowSizeClass 实测 compact 411 dp、regular 960×640 dp、wide 1200×640 dp；regular/wide 同时显示导航和页面内容。新工作区创建后旧 `workspace-created` 路由的 viewer 回归得到预期 403，再选择 `workspace-1`（200）进入账本，viewer 写操作保持禁用；Android 系统 Back 从 F-08 返回 F-03。Debug 明文 origin 仅 `localhost`，Release 保持 HTTPS 策略。QA 和截图证据见本节执行记录；Android 阶段完成，随后进入 iOS。
- [ ] 6.4 **iOS 第三阶段（部分完成，端到端导入待补）：**Android 阶段完成后运行 `:shared:iosSimulatorArm64Test`、`linkDebugFrameworkIosSimulatorArm64` 和 iOS Simulator Debug app 构建，均通过；iPhone 17（402×874 compact）、iPad 11（834×1210 regular）、iPad 13（1032×1376 wide）已抽查原生页面。fixture 收到分类创建、持仓/事件详情与分页、工作区改名/成员权限/邀请及邀请 URI 预览/接受请求并返回预期结果；账本筛选、错误/重试、详情编辑/取消、iPad 分类无结果/创建和深色持仓均有设备证据。`finance-tracker://invite/invite-e2e-token` 在 iPhone 17 接受后回到账本。F-05 使用仓库内虚构 CSV 夹具并通过临时本地文件提供器放入 iPhone 17 Simulator；FileKit 选择器可打开、浏览「我的 iPhone」并显示和选中 371 字节文件。点击「打开」后 `agent-device` 的界面树报告已返回 Compose 页面，但 `simctl` 截图仍显示文件选择器；等待 `1/20` 5 秒超时，无法确认文件是否交回应用。选择阶段截图为 `screenshots/ios-iphone17-import-picker-selection-20260925.png`；此前空列表截图为 `screenshots/ios-iphone17-import-picker-empty-20260925.png`。因此 iOS 文件选择→扫描→映射→确认仍未验证，不能勾选本项；当前证据不能区分文件选择器回调缺陷与模拟器自动化窗口状态不一致。`Info.Debug.plist` 单独启用 `NSAllowsLocalNetworking`，Release 使用无 ATS 例外的 `Info.plist`；Debug origin 为 `http://localhost:8181`。`xcodebuild` Debug app build 命令见本节最新执行记录。
- [ ] 6.5 完成 parity matrix：10 项功能 × Web/Android/iOS × compact/regular/wide；记录登记的平台控件差异及相同值、标签、确认语义、流程和结果。
- [x] 6.6 Web 在 Chrome 153 的 320、375、390、414、768、1440 px 检查无水平溢出；F-03/F-04/F-07 页面宽度循环通过。浅/深色截图覆盖 390×1800、1440×1000，并包括 F-05 关系卡片与 F-08 持仓；Android 已在 compact 411 dp、regular 960×640 dp、wide 1200×640 dp 检查导航和内容并存；iOS 已在 iPhone 17 compact、iPad 11 regular、iPad 13 wide 检查账本/持仓/工作区管理代表页，并留存浅/深色截图。此尺寸样本检查不等同于 6.5 的全页面 parity matrix。
- [x] 6.6.1 Cross-platform Impact Check：Noto 同源回退只改变 Web/Wasm 字体加载和字形绘制；共享 F-05 将关系标签的缺字箭头改为等义文字“与”，Web、Android、iOS 文案一致；Native 仍使用系统字体。API、FastAPI、数据库和业务结果不受影响。Web QA 使用 Chrome，不运行 Safari；最新截图无缺字。
- [ ] 6.7 检查 Wasm/JS/APK/iOS 产物大小、首屏启动和大列表滚动；实测多文件 JSON/base64 内存峰值，并确认日志与测试产物不含令牌、密码或原始账单内容。
- [x] 6.8 记录数据库矩阵不适用：本次未修改 API、持久化、数据库语义或后端代码；若发现间接存储行为变化则升级 A 类并补 SQLite 与显式 PostgreSQL `_test` 矩阵。
- [x] 6.9 历史验证基线（2026-09-25）：当时 OpenSpec strict 36/36、doctor root ok、Web Vitest 152/152、Chrome E2E 24/24、Android host 65/65/device 67/67、iOS shared tests/framework/Xcode Debug build 通过。该结果不覆盖本次分层、资源、日志和 proxy 改动；iOS F-05、全矩阵和性能未完成，最终验证转由 6.10–6.17 记录。
- [x] 6.10 **Web 第一阶段，全部功能实现后：**2026-09-26（Asia/Shanghai）在隔离 FastAPI SQLite demo（`127.0.0.1:8000`）和 Compose production preview（`http://127.0.0.1:5187/`）上完成最终复验。命令：`FT_COMPOSE_PREVIEW_PORT=5187 FT_API_PROXY_ORIGIN=http://127.0.0.1:8000 FT_GRADLE_INIT_SCRIPT=/tmp/ft-gradle-mirror.init.gradle npm run demo:compose --workspace=finance-tracker-web`；`./gradlew -I /tmp/ft-gradle-mirror.init.gradle :shared:wasmJsBrowserTest --no-configuration-cache`（最新 111/111，0 failure）；`npm run test --workspace=finance-tracker-web`（152/152）；`npx tsc -p web/tsconfig.json --noEmit`；`node --test web/tests/compose-preview-proxy.node-test.mjs`（1/1）；`FT_COMPOSE_PREVIEW_PORT=5187 PLAYWRIGHT_CHROME_PATH='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' npm run test:e2e:compose --workspace=finance-tracker-web -- --browser=chromium`（Chrome 154.0.8037.57，28/28）。真实注册 POST 到达 FastAPI 并进入工作区；覆盖 F-01–F-10、登录错误/本地认证校验、工作区名称校验、恢复/导入失败重试、locale 自动选择与中文 fallback、诊断日志双写脱敏、深链接/history、键盘可达性及响应式宽度。F-03/F-05/F-08 浅深色截图各 3/3 更新并目视复核；视口 390×844、1440×1000，另检查 320/375/414/768 px 无水平溢出。截图为 `screenshots/web-final-{390,1440}{,-dark}.png`、`web-choice-card-{390,1440}{,-dark}.png`、`web-holdings-{390,1440}{,-dark}.png`。非预期 console/network/静态资源错误 0，Wasm MIME 为 `application/wasm`；日志 E2E 确认错误包含固定 feature/action/code/type 且不含邮箱、密码或 Token。Web 阶段完成，Native 最终验证见 6.11–6.12。
- [x] 6.10.1 Cross-platform Impact Check：账本共享 `CashLedgerViewModel` 的异步引用数据合并修复和 `temporarily_unavailable` 可恢复导入错误影响 Web/Android/iOS 的相同状态与用户文案，重试仍使用原导入 token/幂等 key；`delayedReferenceLoadCannotReplaceTheLoadedPageState` 与 transient commit retry 回归修复前失败、修复后通过。相关文案位于 Compose Resources。Chrome Preview 的 `<base href="/">`、默认同源 API origin、POST 代理只影响 Web 本地预览，不改变用户 URL、FastAPI/API 合同或数据库；Chrome 实际注册已到达隔离 FastAPI。旧 React/Expo 未改。Web 终验完成后按用户指定进入 Android，再 iOS。
- [x] 6.10.2 Cross-platform Impact Check：Android QA 显示认证必填字段、工作区名称、持仓币种、账本金额/日期时间及金额筛选错误此前仅在界面显示。修复后 ViewModel 通过固定 feature/action/code/type 记录，禁止记录输入值；Web/Android/iOS 共用相同校验与日志语义，平台只更换双写 sink；不改变页面流程、API/FastAPI、财务结果或文案。三个 ViewModel 回归在修复前因日志记录入口缺失而编译失败，修复后 Wasm 110/110 通过；Chrome 校验日志目标 E2E 2/2、全量 E2E 28/28、Vitest 152/152、TypeScript 与 Node proxy test 通过。已按要求 Flow-back 重跑 Web Chrome，后续继续 Android、iOS；原生文件日志验收见 6.11–6.14。
- [x] 6.11 **Android 第二阶段：**2026-09-26 在 `FTComposeQA_API36` / `emulator-5554` 完成 Android 最终验证。`./gradlew -I /tmp/ft-gradle-mirror.init.gradle :shared:testAndroidHostTest :shared:compileAndroidDeviceTest :androidApp:assembleDebug -PftApiOrigin=http://localhost:8000 --no-configuration-cache` 成功：Android host 111/111、device-test 编译和 Debug APK 构建通过；`./gradlew -I /tmp/ft-gradle-mirror.init.gradle :shared:connectedAndroidDeviceTest --no-configuration-cache` 为 113/113。此前设备 fixture UI 走通 F-01–F-10、Android 文件/日期选择器、邀请 URI、viewer 只读、错误恢复及账户/工作区操作；用例截图见本节 Android 执行记录。AVD compact 411 dp、regular 960×640 dp、wide 1200×640 dp 均检查导航和页面内容；系统 Back 从 F-08 返回 F-03。locale 已验证 `zh-CN` 中文、`en-US` 英文、`fr-FR` 回退简体中文。空邮箱校验提示可操作，Logcat 与 app-private 文件都只含时间、功能、操作、错误码和异常类型；未见邮箱、密码或令牌。镜像来自仓库外 init script；未改 Release 网络策略。Android 阶段完成，随后进入 iOS。
- [ ] 6.12 **iOS 第三阶段（部分完成，F-05 端到端仍未验收）：**2026-09-26 在 Xcode 26.3 / iOS 26.3 Simulator 继续验收。`./gradlew -I /tmp/ft-gradle-mirror.init.gradle :shared:iosSimulatorArm64Test :shared:linkDebugFrameworkIosSimulatorArm64 --no-configuration-cache` 对应结果 XML 为 112/112、0 failure，framework link 成功；本轮 `xcodebuild -quiet -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -destination 'platform=iOS Simulator,id=70A46354-A489-4A58-BDA8-D59EE8B4BF94' -derivedDataPath /private/tmp/ft-compose-ios-final FT_API_ORIGIN=http://localhost:8181 CODE_SIGN_ENTITLEMENTS=/private/tmp/ft-compose-ios.entitlements CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual CODE_SIGNING_ALLOWED=YES INFOPLIST_FILE=/private/tmp/ft-compose-ios-debug.plist build` 成功（仅有 Xcode destination 元数据 warning）；签名 entitlement、文件共享 plist 和 API 地址均为仓库外 QA 临时配置。iPhone 17（402×874 compact）、iPad Pro 11（834×1210 regular）、iPad Pro 13（1032×1376 wide）已有页面/截图证据；F-01–F-04、F-06–F-10 的本地 fixture 流程、邀请 deep link/返回、账本错误恢复、详情编辑、分类/持仓/事件与工作区操作此前已走通；iOS 本地日志写入 app-private `NSUserDefaults` 和系统日志，校验字段符合白名单。将 Simulator 系统语言依次切换为 `en-US`、`fr-FR` 再恢复 `zh-Hans-CN`，账本主界面验证英语及简体中文 fallback；截图为 `screenshots/ios-iphone17-en-20260926.png`、`screenshots/ios-iphone17-fallback-fr-20260926.png`。F-05 继续使用虚构 CSV `QA-Import-Fixture.csv`（371 字节）：选择器可打开、浏览 app 的 `FinanceTracker` 文件夹并显示和选中文件；点击系统「打开」后画面仍停在文件选择器，底层 Compose 页面仍显示 `0/20`。`agent-device` AX 快照把 picker 作为 `AXRemoteElement`，并在点击后报告底层页面；实际截图仍显示 picker，尚无法分辨模拟器跨进程自动化限制与 FileKit 回调缺陷。最新证据 `screenshots/ios-iphone17-import-picker-after-open-20260926.png` 显示所选文件与系统「打开」控件；日志未出现 picker 错误事件。文件选择→扫描→映射→确认尚未完成，因此本项保持未勾选；不得用 Safari 替代原生 iOS QA。
- [x] 6.12.1 Cross-platform Impact Check：为修复 Kotlin/Native 下 `NSDate` 时间戳编译失败，将 iOS 诊断日志毫秒时间改为 `Clock.System.now().toEpochMilliseconds()`，并新增 epoch 范围单测；只影响 iOS 日志适配器与共享测试，不改变 Web/Android 行为、页面、API、后端或功能地图。Flow-back 后 Web Wasm 111/111、Android host 111/111、Android device 113/113 通过；iOS shared 112/112、framework link 与 Xcode Debug app build 通过。
- [ ] 6.13 在 6.10–6.12 通过后完成 10 功能 × Web/Android/iOS × compact/regular/wide parity matrix；对每格记录真实入口、用户动作、空/错/成功状态、API 结果、文案/标签及有登记的平台控件差异。未运行项保持未完成并说明真实阻断条件。
- [x] 6.13.1 当前覆盖盘点：Web Chrome 与 Android fixture UI 都走过 F-01–F-10；iOS fixture UI 走过 F-01–F-04、F-06–F-10，F-05 已打开并选中样本文件但没有确认返回结果。compact/regular/wide 已覆盖三个平台的代表页面和响应式检查，但不是每个功能在每个窗口等级的完整 90 格 parity 矩阵；未覆盖格仍保留在 6.5/6.13，不推断为通过。
- [x] 6.14 日志专项验证：Chrome E2E 检查 Console 与 `localStorage`；Android 设备检查 Logcat 与 app-private 文件；iOS 设备检查系统日志与 app-private `NSUserDefaults`。三端样例仅含时间、feature/action/code/status/异常类型，不含邮箱、密码、Token 或账单内容。`DiagnosticLoggingTest` 6 项测试覆盖双 sink 白名单、未知码/异常类型净化、过期与损坏条目清理、1 MiB oldest-first、存储写入失败不阻断开发者日志；Chrome E2E 再检查本地记录不含敏感输入。iOS 使用私有偏好存储而非单独日志文件，符合 app-private 本地存储合同。
- [ ] 6.15 国际化专项验证：三端分别设置简体中文、英语和未支持 locale；对 F-01–F-10 的可见固定文案、a11y labels、验证和所有请求状态抽样核对一致翻译，无混语/资源 key/字形缺失；只使用系统/浏览器语言，不增加设置入口。
- [x] 6.15.1 国际化 locale 冒烟：Chrome 与 Android 已验证 `zh-CN`、`en-US` 和未支持 locale 回退；2026-09-26 在 iPhone 17 Simulator 全局语言切换为 `en-US` 与 `fr-FR`，分别看到英文账本过滤界面和简体中文回退，之后恢复原 `zh-Hans-CN` 系统语言。截图见 6.12。该抽样不覆盖 F-01–F-10 所有状态/无障碍标签，故 6.15 仍未完成。
- [ ] 6.16 性能与发布前安全：测 Wasm/JS/APK/iOS 产物、Chrome 首屏启动、大列表滚动和多文件 JSON/base64 峰值；扫描日志/fixtures/screenshots/build 产物无真实个人财务值、凭据或原始异常正文；记录命令、数据集和结果。
- [x] 6.17 2026-09-26（Asia/Shanghai）运行 `openspec --version`（1.7.0）、`openspec validate --all --strict`（36/36）、`openspec doctor`（Root ok）、`git diff --check`（通过）；受影响验证为 Wasm 111/111、Android host 111/111、Android device 113/113、iOS Simulator 112/112、iOS framework link/Xcode Debug build 成功；Web 结果见 6.10（Vitest 152/152、TypeScript、proxy test 1/1、Chrome 154 E2E 28/28）。最终 `HEAD=e716c01f945350daaf03590d648adeb9a7e12afb`，比较基线 `beda546f6fcf2e3a69634af38d9afe54d459260b`；本轮未提交、推送或部署。未运行/未完成项及补跑条件：iOS F-05 在「打开」后仍停留系统 picker，须先在可交互 Simulator 会话确认选择器回调，再走扫描/映射/确认；6.13 的 90 格 parity、6.15 全功能多语言与无障碍标签核查、6.16 产物/启动/滚动/多文件内存测量仍需专项执行；5.6/5.8 工程安全及独立最终 diff 复核仍待完成。FastAPI 未变，数据库矩阵不适用。
- [x] 6.18 pen.dev/规范验证：2026-09-28（Asia/Shanghai）运行 `pen version`（0.3.9）、`pen status`（Active，Personal workspace）、`npm run tokens:generate` 与生成物 `git diff --exit-code`；官方 interactive MCP 生成 `design/finance-design-system.lib.pen`、`design/login.pen`，JSON 结构均为 schema `2.19`，登录页 8 个顶层状态画布，最终画布由 `Export` 导出为 PNG。实测 CI 原 `pen --export` 会误入 Agent 模式，且传入 `document` 作为节点 ID 的导出失败；修订工作流改用各 `.pen` 文件的顶层 frame ID，通过 interactive shell 无模型导出。`npm run compose:test`、`openspec validate --all --strict` 36/36、干净 `npm ci --no-audit --no-fund`、`git diff --check` 通过。CI 在无 `PEN_CLI_KEY` 时准确记录非阻断跳过；本地未配置 CI key，不将该项当作 CI 远端执行证据。

## 7. 发布准备

- [x] 7.1 记录交付状态：当前分支 `refactor/kmp`；用户授权本次提交并推送至同一分支。不配置 Render、不部署。Compose 是主动入口；旧 React/Expo 源码保留为参考，不按本变更自动删除或切换线上入口。
- [x] 7.2 不适用：用户要求只完成 demo 开发和本地启动，本次不配置 Render、云端托管或线上回滚。
- [x] 7.3 本地 demo 已验证 Native `finance-tracker://invite/<token>`；真实 HTTPS App Links/Universal Links 域名关联与正式签名材料属于线上分发条件，不纳入本次本地交付。
- [x] 7.4 仅执行本地开发和验证；不部署 Render/云端、不切换线上入口。提交/推送遵守现有分支授权边界。
- [x] 7.5 按用户决策取消本变更内的旧客户端源码删除清单；`mobile/`、`web/` 和 TypeScript shared packages 保留为迁移参考，只有根主动依赖图、CI、启动命令和功能地图主动统计退场。未来删除源码或切换线上入口必须另开明确变更。

## 8. 反思

- [ ] 8.1 记录 Wasm、Material 3、跨端测试中可复用的工具或规则，并把必要变更回写项目文档、主规格或工作流规则。
- [x] 8.2 历史代码盘点（2026-09-25）：Compose production Kotlin 共 8,349 行（`commonMain` 7,705 行；含 Android/iOS/JS/Wasm 与 app 壳；不含 Swift、测试和生成资源）。当时 shared commonTest 64 个 `@Test` 声明，Android host 65/65、Android device 67/67、Web Vitest 152/152、Chrome E2E 24/24；iOS Simulator shared-test task 通过。缺口仍有 iOS F-05 全路径、10×3×3 parity 和性能/大文件峰值；分层重构后的行数和测试数由 8.3 重新盘点。
- [ ] 8.3 重构里程碑反思：复盘 7,700 行共享层迁移后的实际 Kotlin 行数、文件最大行数、层间依赖检查、ViewModel/Repository/UseCase 覆盖、commonTest/Android/iOS/Web Chrome 用例数量、Compose Resources 覆盖率与日志隐私回归；总结可复用的构建/QA 做法，并在 6.17 与最终 diff 记录中回写未解决事项。
- [x] 8.4 记录 pen.dev 接入和 Compose 唯一工作流的可复用约定：`.pen` 版本管理、MCP/CLI 认证、token 生成、CI 跳过语义和旧端参考边界已写入 `design/README.md`、根 `README.md`、`proposal.md`、`design.md` 和本任务记录。

## 执行证据

### 历史切片记录（仅描述早期检查点，不代表当前功能覆盖）

- 用户调整执行顺序：先完成 F-01 至 F-10 的共享实现，再依次完成 Web、Android、iOS 平台验证。此前导航壳、登录表单及 iPhone 16 的截图/烟测作为开发期探索证据保留；不作为最终平台验收。最终三端完整 QA 移至所有功能页面完成之后。

- 当前 `HEAD` 与比较基线均为 `beda546f6fcf2e3a69634af38d9afe54d459260b`；尚无提交、推送或部署。
- Kotlin Wizard 官方项目生成后，安全提取到 `compose/`；版本为 Gradle 9.5.1、JDK 21、Android SDK 37、Kotlin 2.4.20、Compose Multiplatform 1.12.1、AGP 9.1.1、Material 3 1.12.0-alpha03。未提取 IDE 状态与本机 `local.properties`。
- `./gradlew :androidApp:assembleDebug :webApp:wasmJsBrowserDistribution :webApp:jsBrowserProductionWebpack`：Android APK、Kotlin/JS 和 Kotlin/Wasm 编译通过；首轮 Webpack 因 Kotlin NPM 工具缓存内缺失 `webpack` 失败。随后 `./gradlew kotlinWasmToolingSetup --rerun-tasks --no-configuration-cache --info` 成功，重跑 Webpack 后 `./gradlew :webApp:wasmJsBrowserProductionWebpack :webApp:jsBrowserProductionWebpack --no-configuration-cache` 通过。
- `./gradlew :shared:compileTestKotlinWasmJs --no-configuration-cache`：在路由与断点实现前按测试先行预期失败，提示相关共享符号未定义；实现后同一源集编译并通过。
- `./gradlew :shared:wasmJsTest --no-configuration-cache`：通过；截至本次共享层切片，`./gradlew :shared:allTests --no-configuration-cache` 覆盖 Android host、iOS Simulator arm64、Kotlin/JS 和 Kotlin/Wasm 共 52 项测试，0 失败、0 跳过：每 target 含 8 项路由/尺寸/URL 格式测试、3 项导航目录测试及 2 项模板测试。
- 响应式应用壳已加入 `FinanceTheme`（Cobalt 浅/深色）、compact 模态抽屉、regular 导航栏、wide 常驻抽屉及六个具名页面入口。Web 适配器读取 `pathname/search`、`pushState` 并监听 `popstate`；路径和邀请链接双向格式化单测通过。Android、iOS 的浏览器历史适配为 no-op，Native 深链待 4.2.4/4.5.4。
- Chrome 153 headless（SwiftShader，启用 WebGL；禁用 GPU 会令 Skia 无上下文并出现空白页）中打开 `http://localhost:8080/`：在 390×844 轻色检查 compact 抽屉，在 1440×1000 深色检查 wide 常驻导航；点击“账单导入”后路径更新为 `/cash-import`，Back/Forward 分别恢复 `/` 和 `/cash-import`。Accessibility Tree 暴露“打开导航”及六个具名按钮；Tab 焦点仍需结合真实表单补测。控制台错误、资源失败与 HTTP 错误均为 0。截图：`screenshots/compose-web-390.png`、`screenshots/compose-web-1440.png`。
- 生产静态预览使用 Chrome 153 headless/SwiftShader，URL `http://127.0.0.1:8081/`，静态目录为 `webApp/build/dist/wasmJs/productionExecutable`，临时 Node fallback server 按文件扩展名返回 Wasm/静态资源并将无扩展客户端路径回退到 `index.html`。直达并刷新 `/w/team%20one/investment-events` 显示“投资事件”；从紧凑抽屉选择导入后变为 `/w/team%20one/cash-import`；Back/Forward 正确恢复两页；`/?invite=demo%20token` 显示邀请页；未知路径显示“页面不存在”。所有 JS/CSS/SVG/Wasm 请求无 HTTP 错误，两个 Wasm 响应均为 `application/wasm`，控制台错误为 0。
- 深链首轮发现入口资源相对路径会将 `/w/<id>/...` 请求错误地解析到 `/w/<id>/webApp.js`、`styles.css`；已将静态引用改为站点根路径并重新构建，随后上述生产深链/刷新流程通过。Kotlin Web 开发服务器仍不提供未知路径 SPA fallback（直接返回 `Cannot GET`），部署必须配置同等静态 path rewrite。Edge、Safari、文件选择与 Compose 可访问文本输入尚未测试，因此 4.1.4 不勾选。
- 该早期检查点仅有登录/注册与工作区入口；当时账本、导入、投资、分类及工作区管理业务页尚未实现。此前导航壳浏览器结果只证明 shell 切片；不能作为当前十项页面的验收证据。
- 此切片 `:shared:allTests`、`:androidApp:assembleDebug`、`:webApp:wasmJsBrowserProductionWebpack`、`:webApp:jsBrowserProductionWebpack` 均通过。产物约为 JS 4.14 MiB、Wasm 2.75 MiB、Skiko Wasm 8.24 MiB；Webpack 体积警告仍在。浏览器运行依赖项目已构建的 Skia/Compose runtime。
- Wasm 生产 bundle：入口 JS 约 513 KiB、主 Wasm 约 2.75 MiB、Skiko Wasm 约 8.24 MiB（两项 Wasm 合计约 10.99 MiB）；Webpack 体积建议警告，最终性能检查仍待全功能实现后重做。早期模板的 JS/Wasm 体积仅作基线，不代表当前代码。
- Hallmark `audit` 最终 UI 审查待执行；本次访问页需先完成焦点激活回归。
- 共享 API 基础合同切片先以 `ApiContractsTest` 验证失败，再加入 session/auth/invitation/member JSON DTO、服务端错误码安全映射、登录/工作区输入校验、精确十进制分配与语义 ID；在该历史检查点，`./gradlew :shared:allTests --no-configuration-cache` 共 76 项测试通过。随后增加 Ktor、平台 TokenStore 与焦点顺序测试；全量账本/导入/投资 DTO 仍随页面切片迁移，故 4.2.1 暂不勾选。
- 共享 HTTP 基线锁定 Ktor `3.6.0`、Kotlin serialization `1.10.0`，采用平台默认引擎与 `ktor-client-mock`；Android Keystore、iOS Keychain、Web localStorage TokenStore 已实现。登录/注册/会话恢复与工作区选择/创建 API mock 测试已存在；实际后端和生产 API origin 未配置，因此访问流程尚未与 FastAPI 端到端验收。
- 浏览器回归先复现发现：Chrome 153 生产 Wasm 预览中 Tab 可从邮箱到密码；密码框 Enter 可提交，Tab 到提交按钮后 Enter 不激活。鼠标提交、通用认证错误态和注册切换通过。已将此 finding 回写变更记录；下续先增加独立 Compose Playwright E2E，再实现 Web 键盘按钮激活。
- Compose Playwright 回归文件已建立但依照新的阶段顺序暂不运行；`npm ci --workspace=finance-tracker-web --include-workspace-root=false --no-audit --no-fund` 在安装依赖期间以 `ENOSPC` 失败，未改动 lockfile。完整 Web E2E 依赖安装与运行在全部功能实现后重试。
- 上传方案修正：design 原 multipart 描述与“FastAPI/API 不变”及 `packages/api-client` 现状冲突。依据 `packages/api-client/src/index.ts` 的 `importBatchRequest`，批量扫描上传 `files[{filename, content_base64}]` JSON；预览/确认提交既有 `import_token` JSON。依赖选择为 FileKit `0.16.0`，版本与三端/Wasm 支持核对自 FileKit 官方文档（https://filekit.mintlify.app/dialogs/file-picker、https://filekit.mintlify.app/dialogs/setup、https://filekit.mintlify.app/quickstart）。其整批 JSON/base64 合同仍有高峰内存风险，不能声称采用了流式上传。

### 当前实现进度（2026-09-25）
- 本轮功能对照修正 F-07 搜索：Web 的 `categoryPath()` 只连接上级路径，所以 Compose 不搜索当前分类名称；新增 `categorySearchMatchesTheAncestorPathLikeTheWebPage`，先运行 `./gradlew :shared:compileTestKotlinWasmJs --no-configuration-cache` 观察到预期的未定义 helper 编译失败，再实现 `filterCashCategoriesByAncestorPath` 并在 `CashCategoryScreen` 复用。`./gradlew :shared:wasmJsTest --no-configuration-cache` 通过（42 actionable tasks，`BUILD SUCCESSFUL`）。
- Compose Playwright 共 17 项：覆盖 F-01 至 F-10、登录焦点/失败与无障碍名称、工作区创建、账本筛选/重试/空态、流水新建/编辑、导入主流程、邀请接受、分类搜索/新增、持仓详情、事件分页/证据、工作区管理和 viewer 写权限。API 由 Playwright fixture 提供，不替代 FastAPI 后端契约验收。
- 精确用例清点：`compose/shared/src/commonTest` 有 59 项 `@Test`；`web/tests/compose-access.e2e.ts` 与 `web/tests/compose-pages.e2e.ts` 合计 17 项 `test()`。Web Vitest 有 152 项。
- 本轮 Cross-platform Impact Check：F-07 的共享搜索逻辑和本次认证、表单辅助技术标签均位于 `commonMain`，影响 Web Compose、Android、iOS 三个 target；Web 原 React/Expo 页面只作为事实源不修改。`CashLedgerScreen`、`CashImportScreen`、投资和工作区 UI 也由三个 target 共用；本轮原生输入无障碍表现仍须在 Android、iOS 验收中复核。

### Web 阶段首轮验证结果（历史记录，2026-09-25）

以下记录保留首次阶段性验收和缺陷发现。用户之后指定 Web QA 使用 Chrome、不使用 Safari；最近共享层改动后的正式证据以紧随其后的「Web 阶段最新复验」为准。早期 Noto 跨域回退和 F-05 箭头缺字已在最新复验前修复。

- Cross-platform Impact Check：早期 Web E2E、截图和浏览器 QA 验证 Compose Web；认证与通用表单辅助技术标签在 `commonMain`，Web、Android、iOS 均受影响，Android/iOS 原生呈现将在后续阶段复核。FastAPI、API 和数据库均未改动。
- `npm run test --workspace=finance-tracker-web`：15 个 Vitest 文件、152 项通过（3.90 秒）。
- `./gradlew :shared:wasmJsTest :webApp:wasmJsBrowserProductionWebpack :webApp:jsBrowserProductionWebpack --no-configuration-cache`（`compose/`）：`BUILD SUCCESSFUL`，90 actionable tasks，20 executed、70 up-to-date。Production Webpack 报告 `webApp.js` 8.18 MiB、Wasm 8.24 MiB，超过默认 244 KiB 性能建议阈值；需在 6.7 继续评估启动和资源体积。
- Chrome 153：`PLAYWRIGHT_CHROME_PATH='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' npm run test:e2e:compose --workspace=finance-tracker-web -- --browser=chromium`，17/17 通过。Edge 154 使用 `/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge` 作为 Playwright Chromium 项目的 executable，17/17 通过。`npm run test:e2e:compose --workspace=finance-tracker-web -- --browser=webkit`：Playwright WebKit 26.5，17/17 通过。回归共 51 次浏览器用例运行；另以 Chrome 重跑截图场景 2 次。API fixture 提供 401 登录失败、503 重试失败等有意错误场景并验证其恢复。
- 浏览器检查覆盖登录 Tab 焦点与 Enter 提交、十项功能的主要页面/API 请求、viewer 禁用写入、导入文件选择/扫描/映射/预览/确认、邀请 query 与接受、账本空/错/成功、F-09 直接路由刷新及 Back/Forward。生产 Wasm 预览 URL 为 `http://127.0.0.1:5186/`；场景访问 `/w/workspace-1/`、`/w/workspace-1/cash-import`、`/w/workspace-1/cash-categories`、`/w/workspace-1/investment-holdings`、`/w/workspace-1/investment-events`、`/w/workspace-1/workspace-management` 及 `/?invite=invite-e2e-token`。Wasm 响应 `application/wasm`；Chrome/Edge 无非预期 console、资源或静态 HTTP 错误。测试有意触发的 API 401/503 单独记录。
- F-03/F-04/F-07 的响应式循环检查 320、375、390、414、768、1440 px，无水平溢出；F-04 编辑表单、F-07 分类表单在改宽后仍可见。浅/深系统主题都在 Chrome 153 下以 390×1800、1440×1000 截图审查：`screenshots/web-final-390.png`、`screenshots/web-final-1440.png`、`screenshots/web-final-390-dark.png`、`screenshots/web-final-1440-dark.png`。主题层级、主要操作、字段和记录可读；无 Material 3/Cobalt 样式偏移。
- Hallmark UI audit（范围：`AccessScreens.kt`、`CashLedgerScreen.kt`、`CashImportScreen.kt`、`CashCategoryScreen.kt`、`InvestmentScreens.kt`、`WorkspaceScreens.kt`、`FeatureComponents.kt`、`AppNavigation.kt`；Web 390/1440 px 浅/深截图、iOS Safari 402 px 截图及 Playwright 路由/状态）：首轮 0 critical、1 major、0 minor。Safari iOS 无障碍树确认认证输入框缺少名称；在共享输入框语义增加字段标签后，新增 E2E 从失败转为 Chrome、Edge、WebKit 全通过；iOS Safari 无障碍树显示“邮箱”“密码”。复审结果：0 critical、0 major、0 minor。
- Playwright WebKit 26.5 捕获到 Skia 探测 `WEBGL_debug_renderer_info` 的 `INVALID_ENUM` 消息，以及自动 Noto 字体 fallback 对 `fonts.gstatic.com` 请求的跨域失败；页面中文字仍可辨认。诊断夹具把这两类已知引擎消息和有意触发的 401/503 归档，不屏蔽其他控制台错误、资源失败、HTTP 错误或 Wasm MIME 检查。
- 实际 Safari 浏览器运行于 iPhone 17 Simulator（iOS 26.3），视口 402×874，访问 `/w/workspace-1/` 后显示预期登录界面；无障碍快照有邮箱、密码名称，截图见 `screenshots/web-safari-402.png`。macOS Safari 窗口未手动操作；原生 Compose iOS 应用 QA 仍属于 6.4，不能由 Safari 浏览器烟测代替。
- `openspec validate --all --strict`：36/36 通过；OpenSpec CLI 1.7.0、Node 26.8.1；`git diff --check` 通过。`openspec doctor`、完整三端构建和最终跨端复核留待最终 QA 阶段。

### Web 阶段最新复验（2026-09-25）

- Cross-platform Impact Check：Noto 字体预载、同源回退和生产资源只影响 Compose Web；F-05 关系分隔文案位于 `commonMain`，Web、Android、iOS 展示同一语义文字，Native 仍用系统字体。Android/iOS 页面和操作不变，FastAPI、API、数据库不变。按 Web → Android → iOS 顺序复验；当前 Web 已完成，接着重跑 Android。
- `npm run test --workspace=finance-tracker-web`：15 个 Vitest 文件、152 项通过（4.07 秒）。
- `PLAYWRIGHT_CHROME_PATH='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' npm run test:e2e:compose --workspace=finance-tracker-web -- --browser=chromium`：Chrome 153 生产预览 22/22 通过（54.7 秒）。URL 为 `http://127.0.0.1:5186/`；覆盖 F-01 至 F-10、登录键盘和无障碍名称、导入文件选择/多渠道关系筛选/失败重试/幂等确认、viewer 禁用、工作区路径切换，以及真实 `history.back()` / `history.forward()` 结果和页面断言。有意触发的 API 401/503 由 fixture 捕获并验证恢复；非预期控制台错误、资源失败、静态 HTTP 错误为 0，Wasm MIME 为 `application/wasm`。Compose 语义代理节点无可用屏幕坐标，主导航用 Playwright `dispatchEvent('click')` 触发其 Compose click handler，随后在真实 Chrome History 上验证地址和页面；不把该步骤描述成画布像素点击。
- `./gradlew :shared:wasmJsTest :webApp:wasmJsBrowserDistribution --no-configuration-cache`（`compose/`）：`BUILD SUCCESSFUL`，8 秒、63 actionable tasks（12 executed、51 up-to-date）。Chrome 预览 production bundle 为 `webApp.js` 532 KiB、应用 Wasm 5.72 MiB、Skiko Wasm 8.24 MiB；Webpack 仍给出 bundle 建议，首屏加载和总体体积留在 6.7 评估。
- 在修复前，Chrome E2E 对 F-05 关系文字断言失败，截图显示 `↔` 为缺字方框。把共享标签替换为等义文字“与”后，目标用例通过（1/1）；F-05 关系筛选卡片浅/深色截图回归通过（各 1/1）。F-03 账本、F-05 关系卡片和 F-08 持仓页面的浅/深色截图均已检查 390×1800 与 1440×1000；中文字形完整、主题层级和主要信息可读。截图路径为 `screenshots/web-final-390.png`、`web-final-1440.png`、对应 `-dark` 文件、`web-choice-card-390.png` / `1440.png` 及深色对应文件，以及 `web-holdings-390.png` / `1440.png` 及深色对应文件。
- 字体文件实测：首屏 WOFF2 2,028,440 字节，101 个同源回退分片合计 2,410,448 字节，总计 4,438,888 字节；字体请求测试确认无 `fonts.gstatic.com` 请求。Chrome 截图无缺字。OFL 许可、上游来源与子集生成脚本已随 Web-only 资源登记。
- Hallmark `audit` 复核范围：`CashImportScreen.kt` 的 F-05 关系审查状态、筛选选项、两个关系状态、390 px 浅/深截图和 1440 px 浅/深截图；首轮视觉检查记录一项功能性字形缺失，已修复并复拍。最终结果 0 critical、0 major、0 minor；没有额外 UI 风格或层级 finding。

### Android 阶段执行记录（2026-09-25；已完成）

- Cross-platform Impact Check：本轮 `App.kt` / `ApiContracts.kt` 的会话工作区选择影响 Compose Web、Android、iOS 的登录恢复、深链接恢复、手动选择和创建；Web Chrome 23/23、Android host 与 instrumented 及设备回归完成，iOS 仍待验证。URL 路由语义保持；FastAPI、API、数据库和财务状态不受影响。
- `./gradlew -I /tmp/ft-gradle-mirror.init.gradle :shared:testAndroidHostTest :shared:compileAndroidDeviceTest :androidApp:assembleDebug -PftApiOrigin=http://localhost:8181 --no-configuration-cache`：`BUILD SUCCESSFUL`；host XML 报告 65 项，0 failures、0 errors，device-test 编译与 Debug APK 构建通过。`./gradlew -I /tmp/ft-gradle-mirror.init.gradle :shared:connectedAndroidDeviceTest --no-configuration-cache`：`FTComposeQA_API36`（`emulator-5554`）67/67 通过。镜像只通过仓库外 init script 使用。
- 首轮网络失败由构建 origin 使用 `127.0.0.1`、而 Android Debug 网络安全策略只允许 `localhost` 导致；现有 Debug manifest 已声明 `INTERNET` 并引用仅放行 `localhost` 的 `network_security_config.xml`，Release 未放宽 HTTPS 策略。改用 `-PftApiOrigin=http://localhost:8181` 重建后，虚构 API fixture 收到登录请求，原生登录与后续 API 请求通过。
- 既有 `Medium_Phone_API_36` 已安装签名不匹配的 `com.finance.tracker`。未卸载或覆盖其数据；另建干净 `FTComposeQA_API36` AVD 并成功安装 QA APK。
- `agent-device` 0.21.0 全局 npm 包附带同版本 Android snapshot/IME helper APK，但没有 `scripts/` 或完整 pnpm workspace。`pnpm build:android` 与 `pnpm clean:daemon` 均因缺少 `@agent-device/ad-replay@workspace:*` 失败；直接清理脚本也不存在。UI 操作用该已安装 CLI 成功打开 QA app；工具 freshness gate 未能按标准命令完成，作为 Android QA 限制继续记录。
- Android 虚构 API fixture 的设备 UI QA 覆盖 F-01 至 F-10：登录和工作区创建/恢复、账本筛选/错误恢复、流水新建编辑、系统 CSV 文件选择器到扫描/映射/预览/确认、分类搜索和创建、投资持仓/事件详情与分页、邀请 URI 解析/接受、工作区编辑/邀请/删除及 viewer 权限。证据见 `screenshots/android-final-import-success.png`、`android-final-categories.png`、`android-final-holdings.png`、`android-final-events.png`、`android-final-invite-link.png`、`android-final-workspace.png`。
- Native regular 布局曾因 `NavigationRail` 未受宽度约束而占满行宽，导致内容区域不可见；已限制 rail 宽度并显式保留内容剩余宽度。Web 768/1440 px 和 Android regular/wide 页面内容复验通过，截图包括 `screenshots/android-regular-after-fix.png`、`android-wide-after-fix.png`。
- Android viewer 验收暴露共享工作区选择问题：登录账号可访问 `workspace-1`、旧路由仍包含不可访问的 `workspace-created`；用户手动选择 `workspace-1` 后，客户端再次按旧路由选择工作区并收到 403，停留在选择页。
- Cross-platform Impact Check：会话接收与工作区路由选择位于共享 `App.kt` / `ApiContracts.kt`，修复影响 Web、Android、iOS 的认证恢复、路由恢复及手动工作区选择；URL 指定工作区的启动语义必须保留，Web 浏览器 history 与 Native 返回栈不变；FastAPI、API、数据库和财务状态不受影响。按 Web → Android → iOS 重跑。
- [x] 为显式手动选择不恢复旧 URL 工作区添加共享回归测试；修复前 `:shared:compileTestKotlinWasmJs` 按预期因参数缺失失败。实现按意图区分路由恢复与手动选择，用户选中工作区及创建工作区均保留 API 返回的活动工作区。
- [x] Web Chrome 回归确认深链接仍选择 URL 工作区；新增 viewer 场景覆盖不可访问旧 URL 返回 403、选择可访问 workspace、进入正确账本、账本写入被拦截及导入只读提示。预期 403 单独记录。`npx tsc -p web/tsconfig.json --noEmit` 检查 E2E fixture 类型通过。
- [x] Android 设备回归以虚构账号建立旧 `workspace-created` 路由后登出，以 viewer 登录。fixture 拒绝旧工作区选择（403），页面展示 viewer 可访问工作区；用户选择 `workspace-1` 后返回 200 并进入收支账本，写操作保持禁用；没有再次请求旧工作区。截图：`screenshots/android-viewer-stale-route-recovered.png`。
- Android 初次切到 regular 的复现：仅登录 QA AVD `emulator-5556`，执行 `adb shell wm size 1920x1280` 与 `adb shell wm density 320`，可用窗口为 960×640 dp；导航项显示在窗口中央，账本内容区域不可见。fixture 同时收到现金页与持仓页请求，说明路由和数据加载已触发。失败截图：`screenshots/android-regular-viewer.png`、`screenshots/android-regular-viewer-holdings.png`。原始 accessibility tree 显示导航 rail 内部滚动区占据整行宽度，页面内容语义节点缺失，初步判断 rail 未受宽度约束并使加权内容区为零宽；修复后以同一 AVD 步骤回归，并补测 wide。
- 同一窗口布局修复在 `FTComposeQA_API36`（`emulator-5554`）复验：紧凑屏 411 dp，regular 960×640 dp，wide 1200×640 dp；F-08 导航与持仓内容同屏可见。深色截图为 `screenshots/android-holdings-compact-dark.png`、`android-holdings-regular-dark.png`、`android-holdings-wide-dark.png`。从 regular F-08 按 Android 系统 Back 返回 F-03 收支账本，不退出到启动器。

### 跨端 QA 修正（2026-09-25）

- iPhone 17 原生走查在 F-08「当前持仓」页发现，盈亏率把缺失币种显示成 `null`（例如 `+10 null%`）；期望显示为 `+10%`。根因定位到金额格式化把可空币种直接放入字符串列表，Kotlin 将空值转成文本 `null`。
- Cross-platform Impact Check：金额/百分比格式化位于共享层，修复影响 Compose Web、Android、iOS 的投资持仓展示；API、FastAPI、数据库、React Web 和 Expo 不受影响。按 Web → Android → iOS 顺序重新验证。
- [x] 新增格式化回归单测并先观察旧逻辑失败；共享格式化修复后 Android host 65 项和 iOS Simulator shared tests 通过，Web Chrome F-08 展示回归与 Android/iOS 持仓界面复核通过。
- Android QA 另发现 Native 导航问题：在 `FTComposeQA_API36` 登录并打开 F-08 后，把窗口从 compact 调为 960×640 dp regular，当前页重置为收支账本；在 regular 的 F-08 按系统 Back 会退出到启动器，而不会返回前一页面。
- Cross-platform Impact Check：`AppNavigationState` 与保存/恢复逻辑位于共享层，影响 Web、Android、iOS 的当前路由；Native 当前路由与返回栈同存，供 Android 系统 Back、iOS 页面返回和配置变化恢复使用；Web 仍由既有 URL 与浏览器 history 管理，Back/Forward 合同保持不变。API、FastAPI、数据库不受影响。
- [x] 为共享路由保存/恢复及 Native 返回栈加回归测试，实现可保存的 `AppNavigationState` 与 Android `StateRestorationTester` 设备用例；Web Chrome history、Android 系统 Back 和 iOS 邀请接受后的返回入口已有运行证据。iOS 配置变化恢复与全部页面间返回栈仍未覆盖，保留在 4.5.4/6.5 范围中。
- [x] 记录 iOS 原生 Compose QA 与文件选择器限制，见 6.4 和下方 iOS 阶段记录。
- [x] F-10 UI 审查 finding（major）：iPad 11 regular（834×1210）工作区管理页的非本人邮箱逐字竖排，修复前证据为 `screenshots/ios-ipad11-workspace-management-light-20260925.png`。根因是成员行中的 `ChoicePicker` 在 `Row` 内未设宽度上限，挤占加权邮箱区域；已为宽屏 picker 加入 `widthIn(max = 220.dp)`，修复后邮箱单行显示。
- Cross-platform Impact Check：成员行位于共享 `WorkspaceScreens.kt`，影响 Web、Android、iOS 的 `regular`/`wide` F-10 页面；`compact` 列式布局、API、FastAPI、数据库和工作区角色语义不变。
- [x] 新增 Chrome 834 px F-10 邮箱宽度回归 E2E，修复前按预期失败；为非紧凑成员权限选择器设置宽度上限。修复后该用例通过，Chrome E2E 24/24、Android regular/wide 截图与 iPhone 17 compact/iPad 11 regular/iPad 13 wide 截图复核通过。

### iOS F-05 样本选择补充验证（2026-09-25）

- 在仓库外构建临时 `QAFilesProvider` 并安装到 iPhone 17 Simulator，将虚构夹具 `tests/fixtures/cash_import_browser_refund.csv` 放入其 `Documents`，只用于让系统 Files 提供器出现可选文件；没有写入真实账单或应用代码。
- `agent-device` 打开 Compose iOS app 和 FileKit 选择器后，可浏览「我的 iPhone」并显示、选中 371 字节 CSV。点击系统「打开」后，`agent-device` 的界面树报告已返回 Compose 页面，但 `xcrun simctl io 70A46354-A489-4A58-BDA8-D59EE8B4BF94 screenshot ...` 仍捕获文件选择器；`agent-device wait text '1/20' 5000 --session compose-ios-final` 超时。选择后界面截图：`screenshots/ios-iphone17-import-picker-selection-20260925.png`。随后重启 Compose app，恢复账本页面。
- 当前证据无法区分 FileKit 选择器回调问题与模拟器自动化的前台窗口/截图状态不一致；没有完成选择→扫描→映射→确认，不将 iOS F-05 记为通过。需要在可交互的 iOS Simulator 会话中复现该选择结果并观察回调后再关闭 6.4。
- Cross-platform Impact Check：本次仅补做 iOS F-05 本地选择器验证并更新 QA 证据；Web、Android、共享业务实现、FastAPI、API 和数据库均未修改。
- 本次记录完成后 `openspec validate --all --strict` 为 36/36，`openspec doctor` 为 Root ok，`git diff --check` 通过；本地 Compose Web `/health` 与 `/` 分别返回 200。`HEAD` 与比较基线仍为 `beda546f6fcf2e3a69634af38d9afe54d459260b`，工作树未提交；未配置 Render、云端发布或部署。

### iOS 与 F-10 最终复核（2026-09-25）

- iOS shared/framework 验证：`./gradlew -I /tmp/ft-gradle-mirror.init.gradle :shared:iosSimulatorArm64Test :shared:linkDebugFrameworkIosSimulatorArm64 --no-configuration-cache` 成功。Xcode Debug app 使用 `FT_API_ORIGIN=http://localhost:8181`、本机 ad-hoc signing 与临时 Keychain entitlement 构建成功；安装到 iPhone 17、iPad 11、iPad 13 后运行成功。签名 entitlement 仅用于本地模拟器 QA，没有写入仓库。
- iOS 功能证据：iPad 11 分类无结果搜索/创建，持仓估值详情，投资事件详情与 Load More；工作区名更新、成员角色选择和邀请链接创建；iPhone 17 邀请 URI 预览/接受后回到账本、F-03 筛选与错误/重试、F-04 详情/编辑/取消。虚构 fixture 记录 `POST /api/v1/auth/invitations`、`PUT /api/v1/auth/members/member-id`、`PUT /api/v1/auth/workspace`、邀请 preview/accept 均为预期成功状态。设备截图位于 `screenshots/ios-iphone17-*`、`ios-ipad11-*`、`ios-ipad13-*`。
- iOS F-05：已在 iPhone 17 打开 FileKit 系统文件选择器，系统截图显示「最近项目」为空；关闭 picker 回到选择步骤后点击应用内「取消」，返回工作区管理页。截图为 `screenshots/ios-iphone17-import-picker-empty-20260925.png` 与 `ios-iphone17-import-select-step-20260925.png`。缺少模拟器可选文件，F-05 完整文件内容/扫描/导入结果未验证；这是 6.4 未完成的阻断项。
- 修复后 F-10 Hallmark `audit` 复审目标：共享 `WorkspaceScreens.kt` 与 Chrome 834 px 邮箱宽度 E2E；iPad 11 834×1210 regular、iPad 13 1032×1376 wide、iPhone 17 402×874 compact 截图。首轮 1 major（邮箱宽度被权限选择器挤压）已通过限宽修复并重拍；复审 0 critical、0 major、0 minor。成员邮箱保持一行，操作顺序、标签和角色选择可读；compact 堆叠布局保留。证据包括 `screenshots/android-f10-regular-after-fix.png`、`android-f10-wide-after-fix.png` 及以上 iOS F-10 截图。
- Cross-platform Impact Check（F-10 修复）：共享成员行影响 Web、Android、iOS regular/wide；Chrome、Android AVD、iOS Simulator 均复测。compact、API、权限语义、FastAPI 和数据库不变。
