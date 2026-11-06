This is a Kotlin Multiplatform project targeting Android, iOS, Web.

## Render Compose Web 部署

在 Render 创建 Static Site 时，将 **Root Directory** 设为 `compose`，**Build Command** 设为 `./build-wasm-web.sh`，**Publish Directory** 设为 `webApp/build/dist/wasmJs/productionExecutable`。构建脚本会下载并校验 Azul Zulu JDK 21 GA 版本，再执行 Compose Web 的 Wasm 浏览器分发任务；下载的 JDK 缓存到 Render 提供的 `$XDG_CACHE_HOME`。

在该 Static Site 的 **Environment** 中设置 `FT_API_ORIGIN`，值为后端公开的 HTTPS origin，例如 `https://finance-api.onrender.com`。共享模块会通过 BuildKonfig 在构建时把它编译进 Android、iOS 和 Web 客户端；它是公开配置，不要放 API 密钥或其他凭据。值只填写 origin，不带 `/api`、路径或查询参数。Compose Web 部署脚本在变量未设置或留空时会保留现有默认值 `http://127.0.0.1:18240`。部署到 Render 时，如果不设置该变量，站点访问者的浏览器会请求访问者自己设备上的 `127.0.0.1:18240`，而不是 Render 后端；线上使用时应设置后端的公开 HTTPS origin。后端服务还需设置 `FT_WEB_ORIGIN` 为 Compose 站点 origin（例如 `https://finance-tracker.onrender.com`），以允许该站点通过 CORS 访问 API。修改环境变量后选择重新构建并部署。

## 本地 Compose demo

在仓库根目录运行：

```sh
node compose/qa/web/scripts/start-compose-demo.mjs
```

命令会构建 Wasm production distribution，并启动本地 SPA 预览，默认地址为 <http://127.0.0.1:5186/>；按 `Ctrl+C` 停止。预览服务器把同源 `/api/*` 请求转发到 Kotlin 后端，默认目标为 `http://127.0.0.1:18240`，因此需要 API 流程时请另行启动后端。可用 `FT_API_PROXY_ORIGIN=http://127.0.0.1:<端口>` 指向本机其他 API 端口，或用 `FT_COMPOSE_PREVIEW_PORT=<端口>` 更改 demo 端口。构建依赖需要镜像时，可通过 `FT_GRADLE_INIT_SCRIPT=/本机路径/gradle-mirror.init.gradle` 提供仓库外 Gradle init script。

需要无需 API 服务器的 Compose 演示时，在仓库根目录运行 `node compose/qa/web/scripts/start-compose-offline-demo.mjs`。它通过构建环境变量 `FT_COMPOSE_DEMO_MODE=true` 和空的 `FT_API_ORIGIN` 生成共享模块的 `ComposeBuildConfig`，并启动不提供 API 代理的静态服务，默认地址为 <http://127.0.0.1:5186/>；正式 API 预览仍使用上面的 `node compose/qa/web/scripts/start-compose-demo.mjs` 命令。演示初始进入固定合成用户和工作区，收支、分类、投资、邀请和工作区数据均由进程内仓库提供，改动在进程结束后重置；导入流程提供内置合成账单。演示模式不会读取真实登录凭据或请求 API 服务器；字体等静态资源仍可从配置的 CDN 加载。Compose Web Playwright 与预览服务位于 `compose/qa/web/`，可在该目录运行 `npm ci`，再执行 `npx playwright install chromium` 安装浏览器，随后运行 `npm run test:node` 和 `npm run test:e2e`。Node 工具集合包含真实 DOM 的平台语义生命周期回归，也需要 Chromium；PR 检查执行该完整集合。已有 Chrome 时也可设置 `PLAYWRIGHT_CHROME_PATH` 指向浏览器可执行文件。完整端到端套件中的实时注册用例需要本地 Kotlin 后端通过 `FT_API_PROXY_ORIGIN` 可访问。

PR 的 `Compose Web E2E` check 运行完整浏览器套件，使用临时 SQLite 数据库和本机 Kotlin 后端，包含同源代理注册流程；沿用单 worker 和零自动重试。浏览器统一使用 `zh-CN` 和 `Asia/Shanghai`，使现有 `+08:00` 夹具及日历断言不依赖宿主时区；此套件不代表多时区或夏令时端到端验收。CI 先构建后端，并用 `node compose/qa/web/scripts/start-compose-demo.mjs --build-only` 构建、校验 Web 产物并记录源码指纹，不启动预览服务；随后由 Playwright 再次校验指纹并管理自己的预览服务。本地也可使用 `--build-only` 预先完成冷构建。报告、失败 trace/截图及构建、后端和浏览器日志作为 `compose-web-e2e` artifact 保留 7 天，临时数据库不上传。完整 E2E 的首次 CI 耗时以实际运行结果为准。

本地 demo 启动器会对 Compose 源码、构建配置、相关环境值和实际服务产物计算指纹；输入或产物与上次构建不一致时会强制重新执行 Gradle 任务，并在启动服务前确认构建期间输入未变化。Playwright 每次通过独立端口启动预览服务，不复用已运行的 demo；设置 `FT_COMPOSE_PREVIEW_PORT` 时若端口被占用会显式失败，避免测试静默连到旧包。

Android、iOS 和 Web 都从共享模块的 BuildKonfig 读取 `FT_COMPOSE_DEMO_MODE` 与 `FT_API_ORIGIN`。演示模式默认关闭；API 地址默认为空。Android 可在仓库根目录运行 `cd compose && FT_COMPOSE_DEMO_MODE=true ./gradlew :androidApp:installDebug` 安装演示包。iOS Debug 构建也使用同一环境变量，Swift 入口仅在 `#if DEBUG` 下读取演示模式开关。可通过 `cd compose && FT_COMPOSE_DEMO_MODE=true xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -destination 'generic/platform=iOS Simulator' build` 构建 iOS 演示包。Android、iOS Release 和仓库生产 Web 构建都会强制使用正式数据图。构建需连接 API 的原生包时，通过构建环境变量设置地址，例如 `FT_API_ORIGIN=http://localhost:18240 ./gradlew :androidApp:installDebug` 或 `FT_API_ORIGIN=http://localhost:18240 xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -destination 'generic/platform=iOS Simulator' build`。Compose Web 本地预览在变量为空时使用预览站点 origin，再由本地代理转发 `/api/*` 请求。

UI 页面结构和样式以共享 Compose 实现为准；业务行为与验收位于 `openspec/specs/`，token 位于 `openspec/specs/shared/design-system/tokens/`。Compose Web 及其 QA 工具位于 `compose/webApp` 和 `compose/qa/web/`。

iOS 的 SQLCipher 4.20.0 由 Kotlin SwiftPM 导入及 Xcode 的本地 `KotlinMultiplatformLinkedPackage` 链接；首次构建需下载官方 XCFramework。版本固定在 Gradle catalog、生成包和 `compose/.swiftpm-locks/`，不需要 CocoaPods。修改 Swift 包依赖后，在 `compose/` 执行 `XCODEPROJ_PATH="$PWD/iosApp/iosApp.xcodeproj" ./gradlew :shared:integrateLinkagePackage`，核对生成包、Xcode 工程及锁文件一并提交。当前 Kotlin 2.4.20 的 SwiftPM 集成仍为 Alpha，升级时须重跑真实驱动测试和最终 Xcode 构建。该依赖用于内部加密驱动，Apple 缓存工厂已完成组件组合验证，应用会话、原生索引和页面尚未接线，详见[离线账本任务](../openspec/changes/offline-first-ledger-import/tasks.md)。

* [/iosApp](./iosApp/iosApp) contains an iOS application. Even if you’re sharing your UI with Compose Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.

* [/shared](./shared/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./shared/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./shared/src/iosMain/kotlin) folder would be the right place for such calls.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and options:

- Android app: `./gradlew :androidApp:assembleDebug`
- Web app:
  - Wasm target (faster, modern browsers): `./gradlew :webApp:wasmJsBrowserDevelopmentRun`
  - JS target (slower, supports older browsers): `./gradlew :webApp:jsBrowserDevelopmentRun`
- iOS app: open the [/iosApp](./iosApp) directory in Xcode and run it from there.

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Android tests: `./gradlew :shared:testAndroidHostTest`
- Web tests:
  - Wasm target: `./gradlew :shared:wasmJsTest`
  - JS target: `./gradlew :shared:jsTest`
- iOS tests: `./gradlew :shared:iosSimulatorArm64Test`

真实 Keychain 集成测试使用单独模式。先用 `xcrun simctl list devices available` 选定测试模拟器，并手动启动；在 `compose/` 运行 `./gradlew -PledgerKeychainTests=true :shared:iosSimulatorArm64Test --device <UDID> --rerun`。该模式只运行依赖系统 Keychain 的仓储和缓存工厂用例，关闭 standalone 并使用仅测试二进制内的授权声明；不改变应用授权。普通模式保留纯 ID 校验，排除需要系统服务的 Keychain 类，因此普通单测通过不能写成 Keychain 验证通过。Kotlin 2.4.20 的 SwiftPM 仅为默认测试任务配置动态库路径，两个模式复用同一测试二进制；升级工具链须复跑两种模式。

Android 密钥仓储使用系统 Keystore 的不可导出 AES 密钥包装独立数据库密钥，包装记录留在 `noBackupFilesDir`。真实系统回归位于 `AndroidLedgerKeyVaultTest`：手动启动指定 QA 模拟器后，在 `compose/` 运行 `ANDROID_SERIAL=<serial> ./gradlew -Pandroid.testInstrumentationRunnerArguments.class=com.finance.tracker.AndroidLedgerKeyVaultTest :shared:connectedAndroidDeviceTest`。测试只使用合成范围；`testAndroidHostTest` 中的密钥交付测试不能代替 Keystore 验证。`AndroidLedgerStorageLeaseTest` 验证独立范围的文件租约，可用同一 class 参数选择；其中 app_process 独立进程探针需 API 26 以上，其余系统回归不因此整类排除。范围摘要复用共享实现，关闭结果不确定时继续阻止同范围打开。

Android 内部加密驱动固定官方 SQLCipher Android 4.20.0、SQLDelight Android driver 2.4.1 和 AndroidX SQLite 2.7.1。`AndroidEncryptedSqlDriverTest` 用实际引擎验证精确文本、加密回滚日志、错钥保旧、初始化清理、关闭确认失败和 24 MiB 文本读回，可按上述方式选择该类。官方 AAR 的四种 ABI 原生库及完整第三方声明随 APK 提供；静态 16 KiB 对齐检查不代表已执行相应页大小的设备验证。

Android 内部缓存工厂已组合范围租约、Keystore、SQLCipher 和完整快照存储，默认目录为应用的 `noBackupFilesDir/ledger-cache`。`AndroidLedgerFactCacheTest` 验证精确快照、范围隔离、现存库缺钥／错钥保旧、孤立边文件与路径拒绝、实际删除失败后重试、关闭不确定时的租约保留，以及打开后取消的清理。原生索引及应用登录／撤权、页面尚未接线，内部工厂不代表 Android 离线功能已可用。

`AndroidLedgerSnapshotSyncTest` 再将真实工厂与版本同步、本地分页组合，验证不变版本不下载、换版、失败保旧及 401／403 清除实际存储。HTTP 使用合成 MockEngine，不能替代后端集成、应用登录或断网重启的身份恢复验证。

同一 `FinanceApiClient` 对登录令牌的读取、写入及条件删除串行化；迟到的 401 或退出响应只清理仍匹配原请求的令牌。退出取消也完成条件清理，存储与远端双故障保留双方诊断。会话重试及 `KtorLedgerSnapshotSource` 在捕获后绑定原令牌与地址；恢复协调器可把同一快照用于会话、版本和整包读取，单参数 source 只在首次读取时捕获，实例不可跨登录复用。实际 Graph 地址为实例常量，不承诺任意可变 provider 的原子采集。该边界不协调采集期间的状态变化、多个客户端、浏览器标签页或页面状态；`FinanceApiCredentialLifecycleTest`、`LedgerCredentialSnapshotTest` 覆盖相应请求边界。

内部 `LedgerStorage` 在同一加密库保存离线访问恢复记录：复用 `SessionDto`，绑定原令牌的单向摘要及完整范围，要求该范围已有已提交快照。恢复资料与事实共用关闭围栏和文件／密钥清理；原令牌不写入账本。`close()` 确认真实关库后释放租约，保留事实、恢复记录与密钥；`clear(scope)` 则擦除材料。同一实例首次选择保留或擦除后只能重试该动作，旧实例不能删除重开后的缓存。三平台工厂提供 `existingOnly = true` 恢复打开：缺库或零字节主库在读取密钥及打开驱动前拒绝，不创建替代库／钥；正常在线首次缓存沿默认创建路径。`LedgerOfflineSessionStorageTest` 验证 SQL 原子更新、范围／令牌拒绝、损坏记录和关闭围栏，Android 系统回归及 Web 两目标验证正式工厂关闭后重开。内部 `LedgerSessionDirectory` 保存范围引用、当前引用及发布代次，不存显示身份或令牌；旧代次不能登记或发布，条件清理旧范围不移除其他当前范围。原生定位存储限定当前单应用进程，Web 使用同源短锁比较替换。三端工厂可传入 `LedgerSessionRegistration`，在真实租约内先登记再创建材料，擦除成功后条件移除引用再释放；定位清理失败保留围栏及租约供原实例重试。Web Worker 已提供分步租约协议：仅取锁不创建事实或密钥，打开失败后仍允许同所有者擦除，关库和擦除后直到最终关闭才释放。旧 JavaScript 包装器仍完成整个关闭流程。Web 工厂等待实际登记／移除 Promise 完成才释放租约；取消与最终关闭确认失败后的旧重试不会重新擦除新所有者。Android 另提供固定范围的 `LedgerCacheErasure`：先验证引用，明确清理时才取得租约，直接删除坏文件、指定密钥和定位，不读密钥或打开数据库；失败须保留同一对象重试，完成旧对象不再触碰新材料。Web／Apple 独立清理及应用启动恢复／联网核验尚未接入，当前页面仍须联网恢复登录。

### Local ledger calendar dependencies

The local ledger date-range helper uses `java.time` on Android, `kotlinx-datetime` 0.8.0-0.6.x-compat on iOS (matching the existing Ktor dependency; the new bridge uses `kotlin.time.Instant`) and `@js-temporal/polyfill` 0.5.1 on JS/Wasm. Android API desugaring is enabled in `androidApp` for minSdk 24. The accepted named-zone catalog is generated by `scripts/GenerateLedgerTimeZoneIds.java` using its pinned Temurin runtime; it contains names only. Platform timezone data still determines transitions. This helper is not yet wired to the ledger page.

Dependency notices are packaged as `files/third-party/calendar-third-party-notices.txt` in Compose resources. Before distributing Android binaries, provide the corresponding source and notices required for the exact desugared-library artifact and its build; the linked upstream source reference alone does not establish that release obligation. Application publication is outside the current development validation.

### Static analysis

The Kotlin backend and the pure business modules (`business-contracts`, `business-domain`) run ktlint and detekt. Backend and JVM/Android targets use detekt with type resolution; common and platform source sets are checked per configured target.

- ktlint: `./gradlew :backend:ktlintCheck :business-contracts:ktlintCheck :business-domain:ktlintCheck`
- detekt: `./gradlew :backend:detektMain :backend:detektTest`; business targets such as `:business-domain:detektMetadataCommonMain`, `:business-domain:detektJvmMain`, `:business-domain:detektAndroidHostTest`, `:business-domain:detektJsMain`.

`shared` is intentionally outside this gate because it contains Compose UI; Compose rules and Android Lint are not enabled. ktlint formatting rules are limited to unused imports, trailing spaces and final newline until the migrated code is reformatted.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html),
[Compose Multiplatform](https://kotlinlang.org/compose-multiplatform/),
[Kotlin/Wasm](https://kotl.in/wasm/)…

We would appreciate your feedback on Compose/Web and Kotlin/Wasm in the public Slack channel [#compose-web](https://slack-chats.kotlinlang.org/c/compose-web).
If you face any issues, please report them on [YouTrack](https://youtrack.jetbrains.com/newIssue?project=CMP).
