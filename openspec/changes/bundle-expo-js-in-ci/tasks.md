## 1. 思考与范围锁定

- [x] 1.1 读取 `openspec/project-context.md`、现有 Mobile CI workflow、Mobile README、Native API origin 规则和相关 OpenSpec 事实源，确认当前 quality export 与原生 job 隔离且使用 Debug 配置。
- [x] 1.2 完成 `grill-me`/`grilling` 澄清：Android 与 iOS 一起改；使用 Release-like 测试包，Android 使用仓库内非生产测试 keystore、iOS 保持未签名；不设置 `EXPO_PUBLIC_FT_API_ORIGIN`，由登录时输入 API origin；测试包允许带显式端口的 HTTP；CI 只生成 artifact。
- [x] 1.3 完成 Cross-platform Impact Check：Native 登录/注册地址控件、Native origin 校验和 CI 构建契约受影响；Web 页面、后端、账本和数据库不受影响；本次没有 Web UI 变更，不需要浏览器 QA。

## 2. 计划、设计与规格

- [x] 2.1 创建 `proposal.md`，记录目标、非目标、影响、兼容和回滚边界。
- [x] 2.2 创建 `specs/native-ci-packaging/spec.md`，覆盖 JS 内置、API origin fail-closed、Android 测试签名、iOS 未签名 artifact 和可验证场景。
- [x] 2.3 创建 `design.md`，锁定 Release-like 构建、登录时地址选择、空构建地址、HTTP 测试地址、原生 job 自行 bundling、Android 测试 keystore、artifact 命名和风险缓解方案。
- [x] 2.4 运行 OpenSpec 变更校验，确认 proposal、spec、design、tasks 依赖关系完整后再修改 workflow。

## 3. 失败测试与任务拆分

- [x] 3.1 新增 Mobile CI 静态契约回归测试，先断言 Release-like 配置、登录地址覆盖开关、无构建地址变量、真实 artifact 名称和 README 配置说明；在 workflow 尚未修改时确认该测试失败。
- [x] 3.2 复核测试断言只检查用户确认的 CI 产物合同：Android 使用仓库测试 keystore、iOS 保持未签名，不把具体 Expo/Gradle/Xcode 内部实现写成不必要的公共契约。
- [x] 3.3 为缺失构建地址、空值登录阻断和 Release-like HTTP origin 覆盖补充失败回归测试。

## 4. 构建与文档实施

- [x] 4.1 在 `.github/workflows/mobile-ci.yml` 统一设置 `EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED=1`，移除 `EXPO_PUBLIC_FT_API_ORIGIN` 和构建地址校验步骤；保留 Release-like bundling 与 artifact 检查。
- [x] 4.2 生成 `mobile/ci/finance-tracker-test.keystore`，通过 Android config plugin 将 Android Release-like 构建指向该非生产测试 keystore；workflow 在上传前要求 `apksigner` 校验证书并检查 APK 内置 JS bundle，复制为明确的测试 release artifact 并上传。
- [x] 4.3 将 iOS job 改为 `Release` Simulator 构建，要求 App 包内包含 JS bundle，保持未签名并生成明确的 release artifact。
- [x] 4.4 更新 Mobile README、根 README 和领域词表，说明空构建地址、登录时地址选择、HTTP 测试地址、Metro 独立运行、Android 测试签名、iOS 未签名和新的 artifact 名称。
- [x] 4.5 调整 Native API origin 配置：构建地址可为空；启用地址覆盖时允许带显式端口的 HTTP origin；用户输入仍拒绝空值、路径、查询、片段和凭据。
- [x] 4.6 将 `mobile-login-api-origin` delta 与当前主规格同步到空构建地址、HTTP 测试地址和空值提交阻断语义。

## 5. 审查与一致性

- [x] 5.1 复核 workflow 的触发器、权限、缓存、失败顺序、地址覆盖开关、artifact 路径和签名边界；确认不读取后端地址变量、账号或生产签名 Secret。
- [x] 5.2 复核 proposal、spec、design、tasks、README、Native 配置和 workflow 一致，记录采纳的登录时地址选择及未采用的 Repository Variable 方案。
- [x] 5.3 完成最终 diff 复核；确认 Android 使用测试密钥、iOS 保持未签名、artifact 内置 JavaScript、登录地址控件开启且空地址不会发出网络请求。

## 6. 测试与验证

- [x] 6.1 运行 Mobile CI 静态契约测试以及 Native origin 配置测试并确认通过。
- [x] 6.2 验证未设置 `EXPO_PUBLIC_FT_API_ORIGIN` 时 Native 构建地址为空，空地址提交不发起请求，覆盖开关启用后带显式端口的 HTTP origin 可通过。
- [x] 6.3 在未设置 `EXPO_PUBLIC_FT_API_ORIGIN` 且启用覆盖开关的环境中运行 `npm run export:android --workspace finance-tracker-mobile` 与 `npm run export:ios --workspace finance-tracker-mobile`。
- [ ] 6.4 在可用本机工具链上复现 Android Release 与 iOS Release Simulator 构建；检查 Android APK 存在内置 JS bundle 且使用仓库测试证书，iOS App 包存在内置 JS bundle 并保持未签名；在无 Metro 时完成可用的 Native 入口验证。
- [x] 6.5 运行 `openspec validate --all --strict`、`openspec doctor`、`git diff --check`，并记录命令、当前 `HEAD`、比较基线、时间和未解决风险。
- [ ] 6.6 按可用环境完成 Native 登录地址输入、空值错误、HTTP 测试后端和地址持久化/重置验证；若设备或依赖环境不可用，准确记录补跑条件。

## 7. 发布准备与回滚

- [x] 7.1 记录不需要 GitHub Repository Variable、测试者登录时输入 API origin、测试 keystore 的 test-only 边界和新的 artifact 下载名称；不执行商店发布或第三方资源写入。
- [x] 7.2 记录回滚步骤：移除 Android 测试 signing config、恢复 Debug 构建和旧 artifact 名称即可回到原 CI；不需要应用数据迁移或 API 回滚。
- [x] 7.3 用户已明确授权将测试 keystore 和相关 CI 改动提交到当前仓库；不执行 push、PR、合并或部署。

## 8. 反思与归档

- [x] 8.1 记录可复用的 Expo Native CI bundling、登录时 origin 选择、空构建地址、HTTP 测试地址和 Android 测试签名/iOS 未签名 artifact 约定。
- [ ] 8.2 完成所有适用验证后同步 delta 到主规格并运行归档前校验；若 CI runner 或 GitHub Variable 条件无法在本机验证，必须保留准确的未完成条件。

## 审查记录

- **产品/范围复核**：已完成；范围限定为 CI 产物独立运行能力、登录时 API origin 选择和 Android 非生产测试签名，不改变 Web、后端、数据库或现金导入业务。
- **工程/安全复核**：已完成；确认 workflow 不读取 `EXPO_PUBLIC_FT_API_ORIGIN`、账号或生产签名 Secret；空 origin 在登录提交前失败；HTTP 只在显式开启地址覆盖且带端口时允许；Android 只使用仓库测试 keystore，iOS 保持未签名。
- **设计复核**：已完成。范围为现有 Native 登录表单的空地址、错误、禁用和加载状态；Hallmark `audit` 技能动作在当前 Codex 工具面不可调用，因此按 `references/verbs/audit.md` 完成人工等价审查。Finding：critical 0、major 0、minor 0，无需修复；未改变布局、视觉 token 或 Web UI。
- **Web QA**：产品级 Web QA 不适用，本次没有修改 Web 页面、路由或交互；单独运行 `npm run test --workspace finance-tracker-web` 仍有 7 个既有账本/投资/无障碍测试失败，未将其归因于本变更；`npm run build:web` 通过。
- **Native QA**：Native 单元测试与无构建地址的 Android/iOS Expo export 已通过；真实 Android Release APK、iOS Simulator Release App、无 Metro 启动和设备登录流程仍由 6.4/6.6 保留为未完成项，不宣称通过。

## 验证证据

- **基线与时间**：当前修改前 `HEAD` 为 `dbe4579aa00186bd5288370dd9ed5c38a4114bdc`（`ci: bundle and sign native test artifacts`），比较基线为 `origin/sincere-fly` 的该提交；记录时间为 2026-09-24 17:12 +0800。
- **通过**：`PYTHONPATH=src uv run --no-sync pytest -q tests/test_mobile_ci_packaging.py`（4 passed）；`npm run test --workspace finance-tracker-mobile`（7 files / 28 tests passed）；`npm run typecheck --workspace finance-tracker-mobile`；`npm run test:shared`；`npm run typecheck:shared`；`env -u EXPO_PUBLIC_FT_API_ORIGIN EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED=1 NODE_ENV=production npm run export:android --workspace finance-tracker-mobile`；同条件的 iOS export；`npm run build:web`；`node --check`；Ruby YAML 解析；`git diff --check`；`openspec validate --all --strict`（35 passed）；`openspec doctor`。
- **配置证据**：`EXPO_PUBLIC_FT_API_ORIGIN` 未设置时 `nativeBuildApiOrigin()` 与 `nativeApiOrigin()` 均为空；覆盖开关启用且 `NODE_ENV=production` 时，带显式端口的 HTTP origin 可规范化，空值、缺端口和非根路径被拒绝。登录页面在空地址提交前调用同一校验，不会发起认证请求。
- **既有构建证据**：PR 原有 `CI=1 npx expo prebuild --platform android --no-install` 曾成功，生成的 `android/app/build.gradle` 中 `release` 使用 `signingConfigs.ciTest`，其 keystore 路径为 `mobile/ci/finance-tracker-test.keystore`；`keytool` 可用 alias `finance-tracker-test` 读取该 keystore，SHA-256 为 `65:22:DA:A7:C9:79:48:AB:B6:FB:2F:74:C6:27:8D:D2:87:D3:74:77:20:E1:50:AC:5F:73:6C:C1:72:D9:E0:51`。
- **未完成**：本次未重新运行 Android `:app:assembleRelease`、iOS `pod install`/Xcode Release、无 Metro 启动或真实设备登录。PR 原有记录显示本机 Maven/React Native 依赖下载曾阻断这部分验证；需在依赖可用的 runner 或本机缓存完成后补跑 6.4/6.6，不能据此宣称 APK 签名、App 内置 JS、无 Metro 启动或登录流程已通过。
- **补跑条件**：在能稳定访问 Maven/React Native 依赖的 runner 或本机缓存完成后，重跑 `6.4` 的 Android Release 与 iOS Simulator Release 构建、bundle 检查、签名指纹检查和无 Metro 启动验证；该条件不影响 workflow 的实现和测试 keystore 提交，但阻止本变更归档为全部验证完成。
