This is a Kotlin Multiplatform project targeting Android, iOS, Web.

## 本地 Compose demo

在仓库根目录运行：

```sh
npm run demo:compose --workspace=finance-tracker-web
```

命令会构建 Wasm production distribution，并启动本地 SPA 预览，默认地址为 <http://127.0.0.1:5186/>；按 `Ctrl+C` 停止。预览服务器把同源 `/api/*` 请求转发到现有 FastAPI，默认目标为 `http://127.0.0.1:8000`，因此需要 API 流程时请另行启动仓库现有后端。可用 `FT_API_PROXY_ORIGIN=http://127.0.0.1:<端口>` 指向本机其他 API 端口，或用 `FT_COMPOSE_PREVIEW_PORT=<端口>` 更改 demo 端口。构建依赖需要镜像时，可通过 `FT_GRADLE_INIT_SCRIPT=/本机路径/gradle-mirror.init.gradle` 提供仓库外 Gradle init script。

Android 可在 Android Studio 打开本目录并运行 `androidApp`；本机后端通过模拟器访问时，先执行 `adb reverse tcp:8000 tcp:8000`，再用构建参数 `ftApiOrigin=http://localhost:8000`。iOS 可在 Xcode 打开 `iosApp/iosApp.xcodeproj`，选择 `iosApp` scheme 和本地 Simulator 运行；需要 API 流程时，在 Debug Build Settings 将 `FT_API_ORIGIN` 设为 `http://localhost:8000`。这些步骤只运行本地 demo，不会启动或修改云端服务。

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
