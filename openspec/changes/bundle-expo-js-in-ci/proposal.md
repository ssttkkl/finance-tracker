## Why

当前 GitHub Actions 的 Mobile CI 只在独立的 quality job 中导出 Expo JavaScript bundle；Android 和 iOS 原生构建在隔离 job 中重新生成原生工程，并使用 Debug 配置打包，因此上传的应用产物不能保证脱离 Metro 直接启动。CI 需要产出可安装、可启动且连接明确 API 地址的独立测试包，避免把开发服务器误当成打包依赖。

## What Changes

- 让 Android 和 iOS CI 原生构建在 Release-like 配置中把 Expo JavaScript bundle 和资源写入应用包。
- 在两个原生构建 job 中统一注入构建时 `EXPO_PUBLIC_FT_API_ORIGIN`，从 GitHub Repository Variable 读取。
- 当 `EXPO_PUBLIC_FT_API_ORIGIN` 未配置或为空时，让 CI 在构建前失败，不上传无法启动的包。
- Android 测试产物使用提交到仓库的专用非生产测试 keystore 签名；iOS Simulator 测试产物继续不签名，不引入 App Store 或 Google Play 生产签名流程。
- 更新 Mobile CI 文档和验证任务，说明产物不依赖 Metro、API origin 的配置方式以及回滚路径。

## Capabilities

### New Capabilities

- `native-ci-packaging`: 定义 GitHub Actions 生成可脱离 Metro 运行的 Android/iOS Native 测试产物及其构建地址配置。

### Modified Capabilities

- 无。

## Impact

- 受影响文件：`.github/workflows/mobile-ci.yml`、`mobile/README.md`、`mobile/app.json`、Android CI 签名 config plugin、仓库内测试 keystore，以及新增的 OpenSpec 规格、设计和任务记录。
- 受影响系统：GitHub Actions 的 Mobile CI、Expo bundler、Android Gradle 构建和 iOS Xcode 构建。
- 不改变应用运行时 API、数据库、账本数据或用户可见业务流程；只改变 CI 产物的独立运行条件和构建配置入口。
- CI 配置需要维护一个非敏感的 GitHub Repository Variable：`EXPO_PUBLIC_FT_API_ORIGIN`，值必须是产品允许的 HTTPS API origin。
- 仓库内 keystore、alias 和密码只属于可公开获取的测试签名边界，任何生产、商店或正式发布流程都不得复用。
