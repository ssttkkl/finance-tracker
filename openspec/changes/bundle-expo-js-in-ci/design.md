## Context

当前 `.github/workflows/mobile-ci.yml` 的 `quality` job 会单独执行 `expo export`，但 Android 与 iOS job 在隔离环境中重新生成原生工程并使用 Debug 配置构建。隔离 job 不会共享 `dist`，而 Debug 原生构建面向 Metro 开发流程，不能作为脱离 Metro 的应用内置 bundle 证据。

Native API origin 由 `EXPO_PUBLIC_FT_API_ORIGIN` 提供构建地址。该值可以缺失：此时 `nativeBuildApiOrigin()` 返回空字符串，API client 仍会在真正发起请求前拒绝空地址。开启 `EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED` 的测试包在登录页提供地址输入，输入有效地址后才会产生认证请求。

## Goals / Non-Goals

**Goals:**

- 让 Android APK 和 iOS Simulator App 都在 Release-like 配置中内置 Expo JavaScript 与静态资源；Android 使用仓库内非生产测试 keystore，iOS 保持未签名。
- 让 quality、Android 和 iOS job 开启登录时的 API origin 输入，不依赖 `EXPO_PUBLIC_FT_API_ORIGIN` 或真实后端账号。
- 允许构建地址为空；启用地址覆盖时允许使用带显式端口的 HTTP origin，供测试后端使用。
- 让 Expo export 和两个 Native release job 明确使用 `NODE_ENV=production`，避免 bundler 依赖 runner 默认环境。
- 用 artifact 名称和 README 说明实际产物类型、登录时地址选择方式及 Metro 独立运行边界。

**Non-Goals:**

- 不在 GitHub Actions 中执行真实登录、导入或其他需要账号的端到端流程。
- 不配置 App Store、TestFlight、Google Play 或其他商店签名。
- 不引入 EAS Build、新的 JavaScript bundler 或新的运行时配置系统。
- 不改变 Web、后端、数据库、账本数据或现金导入业务。
- 不允许未开启地址覆盖的正式构建通过用户输入切换后端；该构建仍使用明确的构建地址并保持 HTTPS 规则。

## Decisions

### 1. 使用 Release-like 原生构建触发内置 bundle

Android 改用 `assembleRelease`，并由 Android config plugin 将生成的 Android `release` build type 指向 `mobile/ci/finance-tracker-test.keystore`；iOS 改用 Xcode `Release` configuration 并显式关闭 code signing。Expo/React Native 的原生构建流程会在非 Debug 配置中执行 bundle embed，产物可直接验证；相比在 Debug 构建上额外覆盖 bundling 开关，这条路径更接近真实分发行为，且减少对 Gradle/Xcode 内部脚本的定制。

备选方案是保留 Debug 配置并强制开启 bundle。该方案会继续携带开发构建语义，容易让 artifact 名称、调试菜单和实际运行依赖产生误导，因此不采用。

### 2. 用登录时地址选择替代 CI 构建地址变量

workflow 顶层只设置 `EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED=1`，不设置 `EXPO_PUBLIC_FT_API_ORIGIN`，也不运行构建地址校验脚本。`nativeBuildApiOrigin()` 在变量缺失或为空时返回空字符串；登录页的地址输入因此初始为空，测试者必须输入有效 origin 才能提交登录或注册。API client 仍以空地址为非法请求目标，未选地址不会发出网络请求。

这样可以让同一个 artifact 连接不同测试后端，且不会把后端地址、账号或凭据写入 workflow。CI 只负责生成可安装 artifact，不承担真实认证验证。

### 3. 地址覆盖模式允许显式端口的 HTTP

当 `EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED` 开启时，Native origin 校验接受 `https://host` 以及带显式端口的 `http://host:port`；仍拒绝空值、凭据、非根路径、查询参数和片段。该规则在 `NODE_ENV=production` 的 Release-like 测试包中也适用，满足测试后端只提供 HTTP 的场景。

未开启地址覆盖时，HTTP 仍仅在非生产构建中按既有规则可用；正式构建不因本次 CI 测试入口而放宽 HTTPS 边界。

备选方案是把 CI 继续绑定到一个 HTTPS Repository Variable，或为 HTTP 测试另建一套 Debug workflow。前者违背测试者登录时选择后端的目标，后者重复构建和维护入口，因此不采用。

### 4. 原生 job 自己完成 bundling，不传递 quality job 的 export 目录

保留 quality job 的 Android/iOS `expo export` 作为 JavaScript 导出回归检查，但不依赖其工作区或 `dist` 目录。Android 和 iOS job 各自在自己的原生工程中运行正式 bundling，确保上传的 APK/App 自己携带 bundle，避免新增跨 job artifact 传递和路径耦合。

### 5. Android 使用可提交的非生产测试签名

测试 keystore 固定放在 `mobile/ci/finance-tracker-test.keystore`，alias 为 `finance-tracker-test`，store/key password 使用同一组明确标注为测试用途的值。`withAndroidCiTestSigning` config plugin 只给生成的 Android `release` build type 添加这个 signing config；workflow 将测试凭据作为非敏感构建参数提供给 Gradle。这样 CI APK 可以直接安装到测试设备并能被 `apksigner` 校验，同时不会读取任何 GitHub Secret 或生产签名资产。

该 keystore 不能用于发布、升级正式包或保护真实用户数据；若未来需要正式分发，必须另行引入受保护的生产签名凭据和独立流程。

### 6. 用真实构建类型命名 artifact

Android artifact 改名为 `finance-tracker-android-release`；iOS artifact 改名为 `finance-tracker-ios-simulator-release`。文件名也使用 `finance-tracker-android-release.apk` 与 `finance-tracker-ios-simulator-release.zip`，README 同步说明 Android 为测试签名、iOS 未签名、已内置 JavaScript、登录时需要填写后端地址，不能用于商店发布。

## Cross-platform Impact Check

- **Web**：不受影响。Web 仍使用自己的 API origin 与登录流程，不新增登录地址控件，也不改变 Web 页面、路由或响应式行为。
- **Native**：受影响。Native 登录/注册页面在 CI artifact 中通过现有地址控件接收 API origin；空构建地址、HTTP 测试地址和地址重置语义需要自动化覆盖。
- **共享层**：`@finance-tracker/api-client` 的公共请求合同不变，仍拒绝空 origin；`packages/presentation` 文案和语义 ID 不变。变化只位于 Native 配置读取和 CI 注入。

## Risks / Trade-offs

- **测试者未填写地址** → 登录页保持空值并在提交前显示地址校验错误；API client 不会向空 URL 发请求。
- **HTTP 测试流量未加密** → 只在显式开启地址覆盖的测试 artifact 中允许带端口 HTTP；README 标明该包仅用于测试，不改变普通生产构建的 HTTPS 约束。
- **Release-like artifact 暴露地址输入控件** → 该控件由 workflow 明确写入的构建开关开启，artifact 名称和文档均标记为测试用途；没有该开关的构建保持隐藏。
- **测试 keystore 被误用于生产** → 路径、alias、workflow job 和 README 均明确标注 test-only；不读取 Secret、不配置商店发布，签名验证只作为 CI 测试包门禁。
- **Expo/React Native 后续改变 bundling 脚本** → CI 保留独立 export 检查，并在原生构建后检查 APK/App 产物存在内置 bundle；后续若发现离线启动回归，优先在对应平台 job 增加 bundle 内容验证。

## Migration Plan

1. 合并 workflow 和 Native 配置变更；不需要设置 `EXPO_PUBLIC_FT_API_ORIGIN` Repository Variable。
2. 后续 PR、`refactor/web` push 或手动运行会生成新的 release artifact；安装者在登录页输入 HTTPS 或带端口的 HTTP API origin。
3. 先验证 APK/App 在没有 Metro 的模拟器中启动，再按测试后端执行登录和多文件导入验证。
4. 回滚时移除地址覆盖开关、恢复构建地址校验脚本和旧 artifact 名称；Native 业务数据与 API 不需要迁移。

## Open Questions

无。平台范围、空构建地址、HTTP 测试地址、CI 只打包和本地提交边界均已由用户确认。
