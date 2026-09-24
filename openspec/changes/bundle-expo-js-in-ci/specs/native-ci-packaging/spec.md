## Purpose

定义 GitHub Actions 生成可脱离 Metro 运行的 Android/iOS Native 测试产物，以及通过登录时输入 API origin 连接测试后端的边界。

## ADDED Requirements

### Requirement: Native CI artifacts embed the Expo JavaScript bundle

GitHub Actions 的 Android 与 iOS Native 构建 MUST 使用会把 Expo JavaScript bundle 及其资源写入应用包的 Release-like 配置。上传的应用产物 MUST 能在没有 Metro 开发服务器的环境中启动并加载应用入口；CI 不得只上传独立的 JavaScript 导出目录来代替应用内置 bundle。

#### Scenario: Android artifact starts without Metro

- **WHEN** CI 生成 Android 使用仓库测试密钥签名的 Release-like APK，安装该 APK 时没有运行 Metro
- **THEN** 应用 MUST 从 APK 内加载 Expo JavaScript 与资源并进入应用入口，不得依赖开发服务器提供 bundle

#### Scenario: iOS simulator artifact starts without Metro

- **WHEN** CI 生成 iOS Simulator 未签名 Release-like App，启动该 App 时没有运行 Metro
- **THEN** 应用 MUST 从 App 包内加载 Expo JavaScript 与资源并进入应用入口，不得依赖开发服务器提供 bundle

### Requirement: Native CI artifacts expose login-time API origin selection

Native CI MUST 开启 `EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED=1`，且 MUST NOT 要求设置 `EXPO_PUBLIC_FT_API_ORIGIN` 才能生成测试 artifact。缺失或为空的构建地址 MUST 作为空值传入 Native 登录配置；测试 artifact MUST 在登录和注册页面展示 API origin 输入，使使用者可以在认证前指定测试后端。CI MUST NOT 使用账号或真实后端执行登录作为产物生成条件。

#### Scenario: Missing build origin still produces a test artifact

- **WHEN** GitHub Actions 未配置 `EXPO_PUBLIC_FT_API_ORIGIN` 且启用了 `EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED=1`
- **THEN** Android 与 iOS Native job MUST 继续执行构建，不得因空构建地址在上传前失败
- **AND** 生成的测试 artifact 登录和注册页面 MUST 展示 API origin 输入

#### Scenario: Empty origin blocks authentication until the user chooses an address

- **WHEN** 测试 artifact 的构建地址为空且使用者未在登录页面输入 API origin
- **THEN** Native MUST 在提交登录或注册前显示地址校验错误
- **AND** Native MUST NOT 发送认证请求

#### Scenario: Test artifact accepts an HTTP backend with an explicit port

- **WHEN** 测试 artifact 的使用者输入带显式端口的 `http://` API origin
- **THEN** Native MUST 按该 origin 发送认证请求并保留其规范化结果
- **AND** 没有显式端口、包含凭据、非根路径、查询参数或片段的值 MUST 被拒绝

### Requirement: Native test signing boundaries are explicit and truthfully identified

Native CI MUST use the committed `mobile/ci/finance-tracker-test.keystore` and its fixed non-production test credentials to sign the Android Release-like APK. The iOS Simulator app MUST remain unsigned. Neither platform may read production, App Store, TestFlight, or Google Play signing credentials. Artifact names and Mobile README MUST clearly state the actual signing boundary, Release-like configuration, embedded JavaScript, and development/test-only purpose.

#### Scenario: Android artifact uses the repository test key

- **WHEN** Android Release-like 构建成功
- **THEN** CI MUST upload an APK signed by the committed repository test keystore, verify that signature before upload, and use an artifact name that identifies it as a test release artifact

#### Scenario: iOS simulator artifact remains unsigned

- **WHEN** iOS Simulator Release-like 构建成功
- **THEN** CI MUST upload an unsigned simulator app archive and MUST NOT require or use a production signing credential
