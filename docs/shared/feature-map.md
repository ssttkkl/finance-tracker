# 共享规则与工程证据

[返回产品总览](../feature-map.md) · [跨端覆盖与待办](../feature-map.md#跨端覆盖与待办)

本模块当前 **没有独立页面**。账本记录、时间、跨端展示、设计系统和演示模式由已有页面共同使用，见 [共享规则规格](../../openspec/specs/shared/README.md)。

## 工程与导航入口

Compose Web、Android、iOS 共用 `compose/shared/src/commonMain`，工程入口分别为：

- [Web 工程](../../compose/webApp) 与 [构建配置](../../compose/webApp/build.gradle.kts)
- [Android](../../compose/androidApp/src/main/kotlin/com/finance/tracker/MainActivity.kt)
- [iOS](../../compose/iosApp/iosApp/ContentView.swift)

页面路由和主要导航由 [路由解析](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/core/AppRouting.kt)、[主导航](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/app/AppNavigation.kt) 与 [页面分派](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/app/AppShell.kt) 决定。页面代码入口见各模块的实现证据，Web QA 位于 [浏览器测试目录](../../compose/qa/web/tests)。

## 跨页面呈现

F-01／F-02／未登录 F-06 共用登录式响应布局与居中卡片；已登录 F-06 保留 Scaffold 标题。F-03 至 F-10 的应用内任务由共享 `DestinationScaffold` 承载，F-05 使用全屏对话框与独立标题／关闭入口，其余任务使用应用顶栏标题。入口一致性见 [入口呈现](../../openspec/changes/archive/2026-10-12-app-shell-entry-presentation)。

桌面侧栏默认显示图标，鼠标进入展开、离开收回；鼠标点击导航产生的焦点不阻止收回，展开时可立即点击收起。键盘焦点可展开侧栏。头像悬停打开账号面板且停留时保持稳定，Enter／Space 可打开，Escape 关闭并把焦点返回头像。工作区切换、管理和退出位于侧栏底部，证据见 [侧栏折叠](../../openspec/changes/archive/2026-10-13-navigation-rail-collapse/tasks.md) 和 [账号面板](../../openspec/changes/archive/2026-10-13-account-menu-hover-stability/tasks.md)。

全局 `FinanceTheme` 将现有 token 映射到 `surfaceContainer*`，影响各 Compose 平台的 Material 3 默认容器色。按钮和文本输入直接使用 Material 3，保留 `FinanceDataTable`／`Amount` 展示辅助函数，见 [组件 Registry](design-system/registry.yaml) 与 [UI 设计规范](ui-design-rules.md)。具体视觉尺寸与实现细节集中于设计规范和各模块链接的实现记录。

## 跨端合同与验收边界

共享 HTTP 客户端已为凭据读写及条件删除提供同实例互斥，迟到的 401／退出不能清除后续登录；退出清理覆盖取消及双异常。会话重试及范围 source 支持捕获后固定原令牌／地址，认证、版本和整包可共用同一快照。该保护尚未与采集期代次、应用缓存生命周期、多个客户端或标签页组合。

内部[离线访问恢复记录](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/data/LedgerSessionStorage.kt)与事实共用加密库和清理所有者，绑定原令牌摘要及对应身份／工作区，并要求存在完整快照。内部存储、同步及本地浏览均区分保留材料的关闭与显式擦除；确认关库后才释放范围租约，旧实例不得在释放后转为擦除。三平台恢复工厂提供已有库模式，缺库和零字节主库不创建替代库／钥。Android 与 Web JS／Wasm 已验证正式工厂关闭后重新读取加密文件，iOS 已编译该接口；内部[范围定位目录](../../compose/shared/src/commonMain/kotlin/com/finance/tracker/data/LedgerSessionDirectory.kt)只保存非显示引用，封闭旧操作发布并按固定范围清理引用；原生限定单应用进程，Web 使用同源短锁。三端工厂可绑定目录，在真实租约内登记／清理，定位清理失败不释放租约。登录／注册内部结果固定同次响应身份及原凭据；内部身份发布组合已连接原凭据、完整事实资格与目录 current，发布后再核存储、令牌及代次；不发起认证请求，也不代替恢复 owner。Web Worker 分步协议保持关库／擦除期间的租约，最终关闭才释放；三端已提供不打开数据库的固定范围清理；Apple 已用真实 Keychain 和加密文件验证坏材料与重试隔离。固定范围缓存 owner 已组合原凭据、已有库恢复、完整刷新与分页，开库交付取消不丢租约，每页核绑定身份。严格会话恢复策略只对已识别传输 I/O／超时读缓存，已知 HTTP 拒绝不回退；Web 默认引擎已按本客户端原生 fetch Promise 拒绝及直接 SDK 异常桥识别传输故障，不按错误文案或任意原因链猜测；HTTP／存储错误及取消不授予离线资格。内部单次恢复默认委托三端正式存储工厂，强制传入目录登记，独立清理复用原平台对象；已组合严格认证、范围定位、完整刷新／已有库恢复与分页；认证等待期间关闭或后续凭据／代次变更会封闭迟到结果，失败仍保留同一 owner 供关闭重试。应用断网恢复、联网核验和跨范围清理尚未接通，页面仍依赖在线会话恢复。

Web 的[自动解锁密钥仓储](../../compose/webApp/src/webMain/resources/ledger-key-vault.mjs)按服务地址、使用者和工作区隔离，使用浏览器持久密钥包装随机数据库密钥，不增加缓存密码。真实 Chromium 已验证密钥重启、两标签页并发、损坏拒绝和范围删除；[加密 OPFS Worker](../../compose/webApp/src/webMain/resources/ledger-storage/worker.js)已组合实际数据库与范围密钥，验证精确文本／索引、DELETE 日志、缺钥／错钥不改文件、排他租约、事务中断恢复及清理失败围栏。固定官方 WASM 与校验和、完整第三方许可随资源分发。[Web 工厂](../../compose/shared/src/webMain/kotlin/com/finance/tracker/data/WebLedgerFactCache.kt)已组合真实 SQLDelight 驱动、完整快照与可重试清理围栏；JS／Wasm 真实浏览器验证精确值、打开时取消及线程故障后的活动／后续请求失败。Web 工厂与同步／本地浏览已完成内部组合回归，401／403 后实际目录和密钥清除已验证；HTTP 使用合成响应；Android 工厂与同步组合见下文，Apple 同步组合、应用登录生命周期及页面尚未接通，不代表已支持离线浏览或完整登出清理。实现与剩余边界见[离线账本任务](../../openspec/changes/offline-first-ledger-import/tasks.md)。

Apple 的[底层加密驱动](../../compose/shared/src/iosMain/kotlin/com/finance/tracker/data/AppleEncryptedSqlDriver.kt)使用固定版本官方 SQLCipher 与 SQLDelight；macOS／iOS 原生回归已验证精确文本、活动回滚日志、错钥拒绝及失败打开的句柄清理。关闭异常保持不确定状态，不能用 SDK 标志推进删库。[Keychain 仓储](../../compose/shared/src/iosMain/kotlin/com/finance/tracker/data/AppleLedgerKeyVault.kt)保存独立随机密钥，真实 iOS 系统测试已验证只读缺项、重复创建保留原钥、设备本地解锁资格、损坏拒绝及指定记录删除；[范围文件租约](../../compose/shared/src/iosMain/kotlin/com/finance/tracker/data/AppleLedgerStorageLease.kt)提供完整服务地址／使用者／工作区的无歧义摘要及独占文件锁，事实目录删除不解除租约；关闭错误锁存后不重复关闭描述符。[Apple 缓存工厂](../../compose/shared/src/iosMain/kotlin/com/finance/tracker/data/AppleLedgerFactCache.kt)已组合范围标识、Keychain、实际数据库及清理，验证精确快照、缺钥／错钥保旧、实际文件删除失败和不确定关闭的租约围栏。应用生命周期、原生索引和页面尚未接线，不代表 iOS 离线功能可用；具体测试入口及未验证边界见[任务记录](../../openspec/changes/offline-first-ledger-import/tasks.md)。

Android 的[密钥仓储](../../compose/shared/src/androidMain/kotlin/com/finance/tracker/data/AndroidLedgerKeyVault.kt)使用系统 Keystore 的不可导出 AES 包装密钥，将独立随机数据库密钥加密保存在不备份目录；读取缺项不建钥，缺失包装别名或损坏密文直接拒绝。实际 Android 系统回归已验证重复／并发首次创建、跨范围密文拒绝、文件删除拒绝后重试、符号链接和指定范围清理；host 回归验证锁释放失败时擦除未交付密钥。[范围文件租约](../../compose/shared/src/androidMain/kotlin/com/finance/tracker/data/AndroidLedgerStorageLease.kt)复用原生完整范围摘要，实际系统回归验证互斥、事实目录删除期间继续持锁、路径／权限拒绝和关闭确认失败后的持续占用；独立 ART 进程探针验证重复打开被拒后内核仍持锁及释放后可取得锁。[底层加密驱动](../../compose/shared/src/androidMain/kotlin/com/finance/tracker/data/AndroidEncryptedSqlDriver.kt)使用官方 SQLCipher 与 SQLDelight，真实系统测试验证精确文本、回滚日志、错钥保旧、初始化清理、关闭确认围栏及 24 MiB 文本读回。[Android 缓存工厂](../../compose/shared/src/androidMain/kotlin/com/finance/tracker/data/AndroidLedgerFactCache.kt)已内部组合上述组件和完整快照，实际系统验证默认不备份目录、精确事实、范围隔离、缺钥／错钥保旧、删除失败重试、打开后取消及关闭不确定的租约保留。真实 Android 缓存与版本同步／本地分页的内部组合已验证失败保旧及 401／403 清除存储，HTTP 为合成 MockEngine，非应用登录或断网重启验证。原生索引和应用登出尚未接通，未验证硬件保护、锁屏、并发密钥创建的跨进程交错或进程崩溃恢复。

Web、Android 和 iOS 共用行为、文案、状态、语义和业务结果。导航、文件选择器及窗口呈现可以不同，差异和共同不变量以 [跨端展示规格](../../openspec/specs/shared/cross-platform-presentation/spec.md) 与各业务主规格为准。共享实现覆盖要求真实 Compose 入口及对应 API 流程；仅有后端接口、文案、原型、规格或不可达导航均不算实现。共享代码不代表每个 target 的设备 QA 已覆盖。

## Web 已知缺陷与历史验证

当前 Compose Web `1.12.1` 关闭详情弹层后无障碍树不恢复，关联 [CMP-10623](https://youtrack.jetbrains.com/issue/CMP-10623)。草稿保存按钮、公共备注空值决策的单选项与确认按钮，通过有限平台适配导出实际禁用和选中状态；适配复用 Material 控件状态，不改变原生控件或业务提交。其余控件未做全面语义修复，Web 返回与整体无障碍 QA 仍保留未通过状态，Native 设备体验另行验证。账本搜索框移到筛选控件时，Web 焦点桥在临时输入框销毁后保留 Canvas 接收后续键盘事件；不改变 Compose 当前逻辑焦点。当前修复与验证范围见 [浏览器回归任务](../../openspec/changes/archive/2026-11-05-fix-web-regression-gates/tasks.md)。

浏览器用例位于 [`compose-access.e2e.ts`](../../compose/qa/web/tests/compose-access.e2e.ts)、[`compose-pages.e2e.ts`](../../compose/qa/web/tests/compose-pages.e2e.ts)、[`compose-live-demo.e2e.ts`](../../compose/qa/web/tests/compose-live-demo.e2e.ts) 和 [`compose-locale.e2e.ts`](../../compose/qa/web/tests/compose-locale.e2e.ts)。2026-10-17 Compose Web 生产预览完整运行 67 项 Playwright 用例并通过，包含账本三尺寸、分页和导入浅色／夜间流程；诊断无浏览器 console error、failed request 或静态资源 HTTP 错误，Wasm MIME 为 `application/wasm`。详细截图、视口、命令与限制见 [`compose-cash-import-account-mapping-redesign/tasks.md`](../../openspec/changes/archive/2026-10-17-compose-cash-import-account-mapping-redesign/tasks.md)。Web 字体使用同源 Noto Sans SC 资源；Compose Playwright fixture 检查 UI／API 请求，本地 registration E2E 另通过 Kotlin 后端实测，两者都不替代 Kotlin 后端契约测试。

历史浏览器结果不能替代各模块当前记录的待验收范围，也不能据此认定代码已部署。

Kotlin 后端迁移的逐项实现与验证状态见 [Kotlin 后端迁移证据](kotlin-backend-migration.md)。生产入口支持 PostgreSQL／Neon URL 的显式 TLS 与通道绑定参数，未知或非法参数拒绝启动；配置、可信 CA 与运行边界见[后端部署参考](../../compose/backend/README.md#render-部署)。该能力不新增页面或改变 Web／Native 功能覆盖。
