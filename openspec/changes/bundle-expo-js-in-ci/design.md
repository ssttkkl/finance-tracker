## Context

当前 `.github/workflows/mobile-ci.yml` 的 `quality` job 会单独执行 `expo export`，但 Android 与 iOS job 在隔离环境中重新生成原生工程并使用 Debug 配置构建。隔离 job 不会共享 `dist`，而 Debug 原生构建面向 Metro 开发流程，不能作为脱离 Metro 的应用内置 bundle 证据。

Native API origin 由 `EXPO_PUBLIC_FT_API_ORIGIN` 在 JavaScript 构建时读取；运行时会拒绝空值和非法 origin。CI 需要在 bundle 前显式验证 GitHub Repository Variable，避免生成安装后才失败的应用。

## Goals / Non-Goals

**Goals:**

- 让 Android APK 和 iOS Simulator App 都在 Release-like 配置中内置 Expo JavaScript 与静态资源；Android 使用仓库内非生产测试 keystore，iOS 保持未签名。
- 让 quality、Android 和 iOS job 使用同一个构建时 API origin，并在变量无效时尽早失败。
- 让 Expo export 和两个 Native release job 明确使用 `NODE_ENV=production`，避免 bundler 依赖 runner 默认环境。
- 保留 Native 依赖预构建和已有构建缓存策略；Android 测试签名只服务于 CI 安装验证，不代表商店或生产签名。
- 用 artifact 名称和 README 说明实际产物类型及 Metro 独立运行边界。

**Non-Goals:**

- 不配置 App Store、TestFlight、Google Play 或其他商店签名。
- 不引入 EAS Build、新的 JavaScript bundler 或新的运行时配置系统。
- 不改变 Native 应用 API origin override 的产品规则；CI 只提供默认构建地址。
- 不把 JS bundle 作为独立发布物上传，也不改变 Web 构建流程。

## Decisions

### 1. 使用 Release-like 原生构建触发内置 bundle

Android 改用 `assembleRelease`，并由 Android config plugin 将 Expo 预构建出的 release build type 指向 `mobile/ci/finance-tracker-test.keystore`；iOS 改用 Xcode `Release` configuration 并显式关闭 code signing。Expo/React Native 的原生构建流程会在非 Debug 配置中执行 bundle embed，产物可直接验证；相比在 Debug 构建上额外覆盖 bundling 开关，这条路径更接近真实分发行为，且减少对 Gradle/Xcode 内部脚本的定制。

备选方案是保留 Debug 配置并强制开启 bundle。该方案会继续携带开发构建语义，容易让 artifact 名称、调试菜单和实际运行依赖产生误导，因此不采用。

### 2. 使用 GitHub Repository Variable 注入 API origin

在 workflow 顶层设置 `EXPO_PUBLIC_FT_API_ORIGIN: ${{ vars.EXPO_PUBLIC_FT_API_ORIGIN }}`，让 quality、Android 和 iOS job 继承同一值。每个会执行 Expo bundling 或原生打包的 job 在依赖安装后运行相同的 Node 校验：要求非空、`https:`、无凭据、无非根路径、无 query/hash。校验不输出完整地址，失败后不会进入打包和上传步骤。

备选方案是把地址写入 workflow 或使用 Secret。硬编码会使不同环境无法复用；该 origin 不是凭据，使用 Repository Variable 比 Secret 更容易发现和维护，同时仍由 GitHub Actions 权限保护配置变更。

### 3. 原生 job 自己完成 bundling，不传递 quality job 的 export 目录

保留 quality job 的 Android/iOS `expo export` 作为 JavaScript 导出回归检查，但不依赖其工作区或 dist 目录。Android 和 iOS job 各自在自己的原生工程中运行正式 bundling，确保上传的 APK/App 自己携带 bundle，避免新增跨 job artifact 传递和路径耦合。

### 4. Android 使用可提交的非生产测试签名

测试 keystore 固定放在 `mobile/ci/finance-tracker-test.keystore`，alias 为 `finance-tracker-test`，store/key password 使用同一组明确标注为测试用途的值。`withAndroidCiTestSigning` config plugin 只给生成的 Android `release` build type 添加这个测试 signing config；workflow 将测试凭据作为非敏感构建参数提供给 Gradle。这样 CI APK 可以直接安装到测试设备并能被 `apksigner` 校验，同时不会读取任何 GitHub Secret 或生产签名资产。

该 keystore 故意属于仓库的一部分，不能用于发布、升级正式包或保护真实用户数据；若未来需要正式分发，必须另行引入受保护的生产签名凭据和独立流程。

### 5. 用真实构建类型命名 artifact

Android artifact 改名为 `finance-tracker-android-release`；iOS artifact 改名为 `finance-tracker-ios-simulator-release`。文件名也使用 `finance-tracker-android-release.apk` 与 `finance-tracker-ios-simulator-release.zip`，README 同步说明 Android 为测试签名、iOS 未签名、已内置 JS、不能用于商店发布。

## Risks / Trade-offs

- **Release-like 构建耗时或体积增加** → 这是内置 bundle 的必要成本；保留 npm/Gradle 缓存，并以构建产物启动验证作为证据。
- **Repository Variable 未在新仓库或 fork 环境配置** → job 在构建前明确失败并打印配置键名，不上传无效 artifact；README 记录配置位置。
- **API origin 包含尾部斜杠或路径导致配置误判** → CI 校验与 Native 运行时规则保持一致，只允许根 origin；失败信息不泄露完整变量值。
- **测试 keystore 被误用于生产** → 路径、alias、workflow job 和 README 均明确标注 test-only；不读取 Secret、不配置商店发布，且签名验证只作为 CI 测试包门禁。
- **Expo/React Native 后续改变 bundling 脚本** → CI 保留独立 export 检查，并在原生构建后检查 APK/App 产物存在；后续若发现离线启动回归，优先在对应平台 job 增加 bundle 内容验证。

## Migration Plan

1. 在 GitHub Repository Variables 中配置 `EXPO_PUBLIC_FT_API_ORIGIN` 为产品 HTTPS API origin。
2. 合并 workflow 和 README 变更；后续 PR、`refactor/web` push 或手动运行会生成新的 artifact 名称。
3. 先验证 APK/App 在没有 Metro 的模拟器中启动，再将旧 artifact 名称从下载脚本或人工流程中移除。
4. 回滚时移除 Android 测试 signing config 与 keystore 引用，恢复旧 artifact 名称和 README；应用源码与 API 不需要迁移。测试 keystore 是否保留在历史提交中不影响正式签名，但不得被生产流程引用。

## Open Questions

无。平台范围、构建类型、变量来源和签名边界已由用户确认。
