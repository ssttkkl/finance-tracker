## 1. 思考与范围锁定

- [x] 1.1 读取 `openspec/project-context.md`、现有 Mobile CI workflow、Mobile README、Native API origin 规则和相关 OpenSpec 事实源，确认当前 quality export 与原生 job 隔离且使用 Debug 配置。
- [x] 1.2 完成 `grill-me`/`grilling` 澄清：Android 与 iOS 一起改；使用 Release-like 测试包，Android 使用仓库内非生产测试 keystore、iOS 保持未签名；从 GitHub Repository Variable `EXPO_PUBLIC_FT_API_ORIGIN` 注入；变量缺失或不合法时失败。
- [x] 1.3 完成 Cross-platform Impact Check：Android/iOS Native CI 构建契约受影响，Web、后端、账本和运行时 API 合同不受影响；不需要浏览器 QA 或 Native 页面交互 QA。

## 2. 计划、设计与规格

- [x] 2.1 创建 `proposal.md`，记录目标、非目标、影响、兼容和回滚边界。
- [x] 2.2 创建 `specs/native-ci-packaging/spec.md`，覆盖 JS 内置、API origin fail-closed、Android 测试签名、iOS 未签名 artifact 和可验证场景。
- [x] 2.3 创建 `design.md`，锁定 Release-like 构建、Repository Variable、原生 job 自行 bundling、Android 测试 keystore、artifact 命名和风险缓解方案。
- [x] 2.4 运行 OpenSpec 变更校验，确认 proposal、spec、design、tasks 依赖关系完整后再修改 workflow。

## 3. 失败测试与任务拆分

- [x] 3.1 新增 Mobile CI 静态契约回归测试，先断言 Release-like 配置、统一 API origin 校验、真实 artifact 名称和 README 配置说明；在 workflow 尚未修改时确认该测试失败：`PYTHONPATH=src uv run pytest -q tests/test_mobile_ci_packaging.py` → `2 failed`，失败点为缺少 `vars.EXPO_PUBLIC_FT_API_ORIGIN`、Release-like 构建和新 artifact 文档。
- [x] 3.2 复核测试断言只检查用户确认的 CI 产物合同：Android 使用仓库测试 keystore、iOS 保持未签名，不把具体 Expo/Gradle/Xcode 内部实现写成不必要的公共契约。

## 4. 构建与文档实施

- [x] 4.1 在 `.github/workflows/mobile-ci.yml` 统一注入 `vars.EXPO_PUBLIC_FT_API_ORIGIN`，并在 quality、Android、iOS job 中对非空 HTTPS origin 做不泄露完整值的 fail-closed 校验。
- [x] 4.2 生成 `mobile/ci/finance-tracker-test.keystore`，通过 Android config plugin 将 Android Release-like 构建指向该非生产测试 keystore；workflow 在上传前要求 `apksigner` 校验证书并检查 APK 内置 JS bundle，复制为明确的测试 release artifact 并上传。
- [x] 4.3 将 iOS job 改为 `Release` Simulator 构建，要求 App 包内包含 JS bundle，保持未签名并生成明确的 release artifact。
- [x] 4.4 更新 `mobile/README.md`，说明 Repository Variable 配置、Metro 独立运行、Android 测试签名、iOS 未签名和新的 artifact 名称。

## 5. 审查与一致性

- [x] 5.1 复核 workflow 的触发器、权限、缓存、失败顺序、变量日志脱敏、artifact 路径和签名边界；确认只使用仓库内非生产测试 keystore，不读取生产签名 Secret。
- [x] 5.2 复核 proposal、spec、design、tasks、README 和 workflow 一致，记录采纳的 Release-like 方案及未采用的 Debug 强制 bundling 方案。
- [x] 5.3 完成最终 diff 复核；已确认 Android 使用测试密钥、iOS 保持未签名、artifact 名称和变量校验一致；原生构建依赖下载阻断已回写到验证证据，未把它误记为代码通过。

## 6. 测试与验证

- [x] 6.1 运行新增的 Mobile CI 静态契约测试并确认通过。
- [x] 6.2 用合法和缺失/非法 `EXPO_PUBLIC_FT_API_ORIGIN` 分别执行校验路径，确认合法值继续构建、缺失或非法值在 artifact 上传前失败，且日志不打印完整地址。
- [x] 6.3 运行 `npm run export:android --workspace finance-tracker-mobile` 与 `npm run export:ios --workspace finance-tracker-mobile`，使用测试 HTTPS origin 验证 Expo bundler 可完成导出。
- [ ] 6.4 在可用本机工具链上复现 Android Release 与 iOS Release Simulator 构建；检查 Android APK 存在内置 JS bundle 且使用仓库测试证书，iOS App 包存在内置 JS bundle 并保持未签名；在无 Metro 时完成可用的 Native 入口验证。
- [x] 6.5 运行 `openspec validate --all --strict`、`openspec doctor`、`git diff --check`，并记录命令、当前 `HEAD`、比较基线、时间和未解决风险。

## 7. 发布准备与回滚

- [x] 7.1 记录 GitHub Repository Variable 配置、测试 keystore 的 test-only 边界和新的 artifact 下载名称；不执行商店发布或第三方资源写入。
- [x] 7.2 记录回滚步骤：移除 Android 测试 signing config、恢复 Debug 构建和旧 artifact 名称即可回到原 CI；不需要应用数据迁移或 API 回滚。
- [x] 7.3 用户已明确授权将测试 keystore 和相关 CI 改动提交到当前仓库；不执行 push、PR、合并或部署。

## 8. 反思与归档

- [x] 8.1 记录可复用的 Expo Native CI bundling、构建时 origin 校验和 Android 测试签名/iOS 未签名 artifact 约定。
- [ ] 8.2 完成所有适用验证后同步 delta 到主规格并运行归档前校验；若 CI runner 或 GitHub Variable 条件无法在本机验证，必须保留准确的未完成条件。

## 审查记录

- **产品/范围复核**：已完成；范围限定为 CI 产物独立运行能力和 Android 非生产测试签名，不改变应用业务行为、Web、后端或数据库。
- **工程/安全复核**：已完成；确认变量 fail-closed、错误日志不回显完整 origin、workflow 不读取生产签名 Secret，Android 只使用仓库测试 keystore，iOS 保持未签名。未完成的原生依赖下载验证单独保留为 6.4 风险。
- **设计复核**：不适用。没有用户界面或页面交互变更；仅更新开发者文档中的 CI 使用说明。
- **Web QA**：不适用。Web 页面、路由和用户可见 Web 行为未修改。
- **Native QA**：已尝试 Android Release 和 iOS Release Simulator 构建，但本机 Maven/React Native 依赖下载未完成，因此没有把无 Metro 启动宣称为通过；Native 导入页面的既有门禁状态不因本变更重置。

## 验证证据

- **基线与时间**：比较基线和当前实现前 `HEAD` 均为 `c7b7a50b6308f26f27a5e51fe48153b81c80a20a`（`feat: support multi-file cash imports`）；记录时间为 2026-09-24 01:33 +0800。
- **通过**：`PYTHONPATH=src uv run pytest -q tests/test_mobile_ci_packaging.py`（4 passed）；`npm run test --workspace finance-tracker-mobile`（7 files / 26 tests passed）；`npm run typecheck --workspace finance-tracker-mobile`；Android/iOS `NODE_ENV=production` Expo export；合法、空值和非法 origin 校验；`node --check`；Ruby YAML 解析；`git diff --check`；`openspec validate --all --strict`（35 passed）；`openspec doctor`。
- **配置证据**：`CI=1 npx expo prebuild --platform android --no-install` 成功，生成的 `android/app/build.gradle` 中 `release` 使用 `signingConfigs.ciTest`，其 keystore 路径为 `mobile/ci/finance-tracker-test.keystore`；`keytool` 可用 alias `finance-tracker-test` 读取该 keystore，SHA-256 为 `65:22:DA:A7:C9:79:48:AB:B6:FB:2F:74:C6:27:8D:D2:87:D3:74:77:20:E1:50:AC:5F:73:6C:C1:72:D9:E0:51`。
- **未完成**：本机 Android `:app:assembleRelease` 在 Gradle 依赖的 Maven HTTPS 下载阶段长时间无进展后以退出码 130 中止，未生成 APK；本机 `pod install --no-repo-update` 在下载 React Native Core 依赖时收到 `curl (92) HTTP/2 stream 1 was not closed cleanly: PROTOCOL_ERROR`，未进入 iOS Xcode Release 构建。因此尚未声称 APK 的 `apksigner` 指纹、App 包内置 JS 和无 Metro 启动已通过。
- **补跑条件**：在能稳定访问 Maven/React Native 依赖的 runner 或本机缓存完成后，重跑 `6.4` 的 Android Release 与 iOS Simulator Release 构建、bundle 检查、签名指纹检查和无 Metro 启动验证；该条件不影响 workflow 的实现和测试 keystore 提交，但阻止本变更归档为全部验证完成。
