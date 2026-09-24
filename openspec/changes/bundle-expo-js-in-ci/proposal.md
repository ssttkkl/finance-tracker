## Why

当前 GitHub Actions 的 Mobile CI 只在独立的 quality job 中导出 Expo JavaScript bundle；Android 和 iOS 原生构建在隔离 job 中重新生成原生工程，并使用 Debug 配置打包，因此上传的应用产物不能保证脱离 Metro 直接启动。CI 需要产出包含内置 bundle 的独立测试包，并让测试者在登录时选择实际后端，避免把工作流变量当成测试入口。

## What Changes

- 让 Android 和 iOS CI 原生构建在 Release-like 配置中把 Expo JavaScript bundle 和资源写入应用包。
- 在 quality、Android 和 iOS job 中开启 `EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED=1`，不设置 `EXPO_PUBLIC_FT_API_ORIGIN`。
- 允许构建地址为空；Native 测试包在登录和注册页面展示后端地址控件，由测试者输入实际 API origin。
- 在启用地址覆盖的测试包中允许带显式端口的 HTTP origin；空值、路径、查询、片段和凭据仍不得提交认证请求。
- Android 测试产物使用提交到仓库的专用非生产测试 keystore 签名；iOS 真机 `.ipa` 面向 `iphoneos` 构建但继续不签名，不引入 Apple、App Store 或 Google Play 生产签名流程。
- 更新 Mobile CI 文档和验证任务，说明产物不依赖 Metro、登录时地址选择方式以及回滚路径。

## Capabilities

### New Capabilities

- `native-ci-packaging`: 定义 GitHub Actions 生成可脱离 Metro 运行的 Android/iOS Native 测试产物及其构建地址配置。

### Modified Capabilities

- `mobile-login-api-origin`：构建地址可以为空；启用地址覆盖的 Native 测试包允许使用带显式端口的 HTTP origin，并在登录前要求输入有效地址。

## Impact

- 受影响文件：`.github/workflows/mobile-ci.yml`、`mobile/src/platform/config.ts`、Mobile README、根 README、领域词表、Android CI 签名 config plugin、仓库内测试 keystore，以及对应 OpenSpec 规格、设计和任务记录。
- 受影响系统：GitHub Actions 的 Mobile CI、Expo bundler、Android Gradle 构建和 iOS Xcode 构建。
- 不改变 Web、后端、数据库、账本数据或导入业务；仅改变 Native CI 测试包的登录地址入口和构建配置边界。
- CI 不依赖 `EXPO_PUBLIC_FT_API_ORIGIN`，也不使用账号或真实后端执行登录；artifact 的使用者在登录时提供 API origin。
- 仓库内 keystore、alias 和密码只属于可公开获取的测试签名边界，任何生产、商店或正式发布流程都不得复用。
