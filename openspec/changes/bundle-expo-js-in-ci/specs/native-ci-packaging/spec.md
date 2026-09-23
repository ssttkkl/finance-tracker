## Purpose

为 GitHub Actions 的 Android 与 iOS Native 测试产物提供可独立启动的应用包，使安装者不需要运行 Metro 就能加载 Expo JavaScript 与资源，并明确构建时使用的 API origin。

## ADDED Requirements

### Requirement: Native CI artifacts embed the Expo JavaScript bundle

GitHub Actions 的 Android 与 iOS Native 构建 MUST 使用会把 Expo JavaScript bundle 及其资源写入应用包的 Release-like 配置。上传的应用产物 MUST 能在没有 Metro 开发服务器的环境中启动并加载应用入口；CI 不得只上传独立的 JavaScript 导出目录来代替应用内置 bundle。

#### Scenario: Android artifact starts without Metro

- **WHEN** CI 生成 Android 使用仓库测试密钥签名的 Release-like APK，安装该 APK 时没有运行 Metro
- **THEN** 应用 MUST 从 APK 内加载 Expo JavaScript 与资源并进入应用入口，不得依赖开发服务器提供 bundle

#### Scenario: iOS simulator artifact starts without Metro

- **WHEN** CI 生成 iOS Simulator 未签名 Release-like App，启动该 App 时没有运行 Metro
- **THEN** 应用 MUST 从 App 包内加载 Expo JavaScript 与资源并进入应用入口，不得依赖开发服务器提供 bundle

### Requirement: Native CI builds use an explicit and valid API origin

Native CI 构建 MUST 在 JavaScript bundle 生成时注入 GitHub Repository Variable `EXPO_PUBLIC_FT_API_ORIGIN`。该值 MUST 是非空的 HTTPS origin，不得包含用户凭据、路径、查询参数或片段；变量缺失或不合法时，CI MUST 在原生构建或 artifact 上传前失败，并且不得上传该构建产物。

#### Scenario: Valid repository variable is shared by both platforms

- **WHEN** GitHub Repository Variable `EXPO_PUBLIC_FT_API_ORIGIN` 配置为合法的 HTTPS origin
- **THEN** Android 与 iOS Native job MUST 使用同一个构建时 API origin 生成各自的 JavaScript bundle

#### Scenario: Missing API origin fails closed

- **WHEN** `EXPO_PUBLIC_FT_API_ORIGIN` 未配置、为空或不符合 HTTPS origin 约束
- **THEN** 受影响的 CI job MUST 失败，且 MUST NOT 上传 Android 或 iOS 应用 artifact

### Requirement: Native test signing boundaries are explicit and truthfully identified

Native CI MUST use the committed `mobile/ci/finance-tracker-test.keystore` and its fixed non-production test credentials to sign the Android Release-like APK. The iOS Simulator app MUST remain unsigned. Neither platform may read production, App Store, TestFlight, or Google Play signing credentials. Artifact names and Mobile README MUST clearly state the actual signing boundary, Release-like configuration, embedded JavaScript, and development/test-only purpose.

#### Scenario: Android artifact uses the repository test key

- **WHEN** API origin 校验通过且 Android Release-like 构建成功
- **THEN** CI MUST upload an APK signed by the committed repository test keystore, verify that signature before upload, and use an artifact name that identifies it as a test release artifact

#### Scenario: iOS simulator artifact remains unsigned

- **WHEN** API origin 校验通过且 iOS Simulator Release-like 构建成功
- **THEN** CI MUST upload an unsigned simulator app archive and MUST NOT require or use a production signing credential
