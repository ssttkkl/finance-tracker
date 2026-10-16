This is a Kotlin Multiplatform project targeting Android, iOS, Web.

## Render Compose Web 部署

在 Render 创建 Static Site 时，将 **Root Directory** 设为 `compose`，**Build Command** 设为 `./build-wasm-web.sh`，**Publish Directory** 设为 `webApp/build/dist/wasmJs/productionExecutable`。构建脚本会下载并校验 Azul Zulu JDK 21 GA 版本，再执行 Compose Web 的 Wasm 浏览器分发任务；下载的 JDK 缓存到 Render 提供的 `$XDG_CACHE_HOME`。

在该 Static Site 的 **Environment** 中设置 `FT_API_ORIGIN`，值为后端公开的 HTTPS origin，例如 `https://finance-api.onrender.com`。共享模块会通过 BuildKonfig 在构建时把它编译进 Android、iOS 和 Web 客户端；它是公开配置，不要放 API 密钥或其他凭据。值只填写 origin，不带 `/api`、路径或查询参数。Compose Web 部署脚本在变量未设置或留空时会保留现有默认值 `http://127.0.0.1:8000`。部署到 Render 时，如果不设置该变量，站点访问者的浏览器会请求访问者自己设备上的 `127.0.0.1:8000`，而不是 Render 后端；线上使用时应设置后端的公开 HTTPS origin。后端服务还需设置 `FT_WEB_ORIGIN` 为 Compose 站点 origin（例如 `https://finance-tracker.onrender.com`），以允许该站点通过 CORS 访问 API。修改环境变量后选择重新构建并部署。

仓库根目录的 `render.yaml` 仍用于 `web/` 下的服务配置，不用于此 Compose Web Static Site。

## 本地 Compose demo

在仓库根目录运行：

```sh
node compose/qa/web/scripts/start-compose-demo.mjs
```

命令会构建 Wasm production distribution，并启动本地 SPA 预览，默认地址为 <http://127.0.0.1:5186/>；按 `Ctrl+C` 停止。预览服务器把同源 `/api/*` 请求转发到现有 FastAPI，默认目标为 `http://127.0.0.1:8000`，因此需要 API 流程时请另行启动仓库现有后端。可用 `FT_API_PROXY_ORIGIN=http://127.0.0.1:<端口>` 指向本机其他 API 端口，或用 `FT_COMPOSE_PREVIEW_PORT=<端口>` 更改 demo 端口。构建依赖需要镜像时，可通过 `FT_GRADLE_INIT_SCRIPT=/本机路径/gradle-mirror.init.gradle` 提供仓库外 Gradle init script。

需要无需 API 服务器的 Compose 演示时，在仓库根目录运行 `node compose/qa/web/scripts/start-compose-offline-demo.mjs`。它通过构建环境变量 `FT_COMPOSE_DEMO_MODE=true` 和空的 `FT_API_ORIGIN` 生成共享模块的 `ComposeBuildConfig`，并启动不提供 API 代理的静态服务，默认地址为 <http://127.0.0.1:5186/>；正式 API 预览仍使用上面的 `node compose/qa/web/scripts/start-compose-demo.mjs` 命令。演示初始进入固定合成用户和工作区，收支、分类、投资、邀请和工作区数据均由进程内仓库提供，改动在进程结束后重置；导入流程提供内置合成账单。演示模式不会读取真实登录凭据或请求 API 服务器；字体等静态资源仍可从配置的 CDN 加载。Compose Web Playwright 与预览服务位于 `compose/qa/web/`，可在该目录运行 `npm ci`、`npm run test:node` 和 `npm run test:e2e`。首次运行浏览器用例前，执行 `npx playwright install chromium`；已有 Chrome 时也可设置 `PLAYWRIGHT_CHROME_PATH` 指向浏览器可执行文件。完整端到端套件中的实时注册用例需要本地 FastAPI 通过 `FT_API_PROXY_ORIGIN` 可访问。

本地 demo 启动器会对 Compose 源码、构建配置、相关环境值和实际服务产物计算指纹；输入或产物与上次构建不一致时会强制重新执行 Gradle 任务，并在启动服务前确认构建期间输入未变化。Playwright 每次通过独立端口启动预览服务，不复用已运行的 demo；设置 `FT_COMPOSE_PREVIEW_PORT` 时若端口被占用会显式失败，避免测试静默连到旧包。

Android、iOS 和 Web 都从共享模块的 BuildKonfig 读取 `FT_COMPOSE_DEMO_MODE` 与 `FT_API_ORIGIN`。演示模式默认关闭；API 地址默认为空。Android 可在仓库根目录运行 `cd compose && FT_COMPOSE_DEMO_MODE=true ./gradlew :androidApp:installDebug` 安装演示包。iOS Debug 构建也使用同一环境变量，Swift 入口仅在 `#if DEBUG` 下读取演示模式开关。可通过 `cd compose && FT_COMPOSE_DEMO_MODE=true xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -destination 'generic/platform=iOS Simulator' build` 构建 iOS 演示包。Android、iOS Release 和仓库生产 Web 构建都会强制使用正式数据图。构建需连接 API 的原生包时，通过构建环境变量设置地址，例如 `FT_API_ORIGIN=http://localhost:8000 ./gradlew :androidApp:installDebug` 或 `FT_API_ORIGIN=http://localhost:8000 xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -destination 'generic/platform=iOS Simulator' build`。Compose Web 本地预览在变量为空时使用预览站点 origin，再由本地代理转发 `/api/*` 请求。

UI 页面结构和样式以共享 Compose 实现为准；业务行为与验收位于 `openspec/specs/`，token 位于 `openspec/specs/shared/design-system/tokens/`。旧 React UI 位于 `web/src`，仅作迁移参考；Compose Web 及其 QA 工具位于 `compose/webApp` 和 `compose/qa/web/`。

* [/iosApp](./iosApp/iosApp) contains an iOS application. Even if you’re sharing your UI with Compose Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.

* [/shared](./shared/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./shared/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./shared/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./shared/src/jvmMain/kotlin)
    folder is the appropriate location.

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

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html),
[Compose Multiplatform](https://kotlinlang.org/compose-multiplatform/),
[Kotlin/Wasm](https://kotl.in/wasm/)…

We would appreciate your feedback on Compose/Web and Kotlin/Wasm in the public Slack channel [#compose-web](https://slack-chats.kotlinlang.org/c/compose-web).
If you face any issues, please report them on [YouTrack](https://youtrack.jetbrains.com/newIssue?project=CMP).
