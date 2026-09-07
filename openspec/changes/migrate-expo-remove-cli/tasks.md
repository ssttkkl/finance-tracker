## 1. 思考：基线与边界

- [x] 1.1 完成 `/grilling` 需求澄清，记录平台范围、CLI 删除顺序、首个移动端链路、online-first、认证存储和后端保留边界。
- [x] 1.2 记录当前 `HEAD`、目标分支、工作树中的既有未跟踪文件，以及 active OpenSpec 变更不与本次混合。
- [x] 1.3 盘点 `src/ft/cli.py` 的全部命令、CLI 专属模块、CLI 测试、活跃文档引用和现有 Web/API 替代面。
- [x] 1.4 阅读项目上下文、领域统一术语、现有主规格、UI 设计规则和 Expo 官方当前 SDK/monorepo 文档。

## 2. 计划：OpenSpec 与 UI 原型

- [x] 2.1 创建 `proposal.md`，记录价值、范围、非目标、迁移顺序、回滚和影响面。
- [x] 2.2 创建 `specs/expo-client/spec.md` 和 `specs/cli-free-runtime/spec.md`，覆盖正常、权限、错误、空状态、幂等和显式数据库配置场景。
- [x] 2.3 创建 `prototype/index.html`，表达进入工作区、看收支、记一笔、导入账单四步及正常/空/加载/错误/成功/禁用/删除确认状态。
- [x] 2.4 按 UI 规则检查原型 320 px、375 px、414 px、768 px、390 px 和 1440 px 的布局目标，并把结果写入本任务文件。
- [x] 2.5 创建 `design.md`，锁定 npm workspace、共享边界、Expo Router、平台适配、CLI 能力替代、事务与回滚方案。
- [x] 2.6 运行 `openspec validate migrate-expo-remove-cli --type change --strict`，修正所有 artifact 格式、依赖和 requirement 场景问题。

阶段 2 验证证据：

- 原型地址：`http://127.0.0.1:18765/`，独立静态 HTTP 预览，无生产路由、后端、凭据或网络依赖。
- 浏览器：Playwright + Chromium；视口 `320`、`375`、`390`、`414`、`768`、`1440` px。各宽度均无横向溢出；可点击控件均达到至少 `44 × 44` px；阶段导航四步均可点击并更新 `aria-current` 与活动内容；可见控件键盘聚焦通过，禁用的“保存中…”按钮按预期不可聚焦。
- 原型状态覆盖：正常列表、空账本、读取中、会话/密码错误、保存成功、导入成功、禁用提交、删除确认；截图：[qa-390.png](prototype/qa-390.png)、[qa-1440.png](prototype/qa-1440.png)。
- OpenSpec：`openspec validate migrate-expo-remove-cli --type change --strict` → `Change 'migrate-expo-remove-cli' is valid`。

## 3. 共享客户端基础

- [x] 3.1 先为跨平台 API 错误、TokenStore、文件来源、金额字符串和工作区会话建立失败测试/类型测试。
- [x] 3.2 创建根级 npm workspace，纳入 `web`、`mobile` 和 `packages/*`，统一 Node 入口和根级 lockfile，确认不改动既有 `web/.lan-vite.config.mjs`。
- [x] 3.3 创建 `packages/contracts`，迁移 Web API DTO、角色、错误代码和导入/账本类型；让现有 Web 通过包导入并删除重复定义。
- [x] 3.4 创建无浏览器依赖的 `packages/api-client`，注入 API 地址、`fetch` 和 TokenStore，统一 Bearer、HTTP 错误、版本冲突、导入密码和 `Idempotency-Key` 处理。
- [x] 3.5 创建 `packages/core`，提取认证/工作区、收支账本、手工记账和导入会话的纯状态机、选择器与 payload 构造器；禁止依赖 DOM、Native 模块和浮点账务计算。
- [x] 3.6 创建 `packages/design-tokens`，以语义 token 同时服务 Web CSS 和 Native StyleSheet，保留现有 Cobalt、Noto Sans SC、IBM Plex Mono 和 4 pt 间距语汇。
- [x] 3.7 让 Web 完成共享包迁移，运行 Web TypeScript、Vitest 和生产构建，确保现有 URL 路由、DOM 焦点和文件导入行为不变。

## 4. Native Expo 首个纵向切片

- [x] 4.1 先为 Native 路由守卫、会话恢复、工作区选择、空账本、请求失败和只读角色建立组件/状态测试。
- [x] 4.2 创建 `mobile/` Expo SDK 57 应用，配置 Expo Router、TypeScript、开发脚本、Android/iOS 标识和根 workspace 依赖。
- [x] 4.3 实现 Native TokenStore（`expo-secure-store`）和文件来源（`expo-document-picker` + `expo-file-system`），覆盖取消选择、读取失败和敏感值不落日志。
- [x] 4.4 实现登录、注册、会话恢复、工作区列表和工作区切换页面，使用共享 API client 与服务端权限结果。
- [x] 4.5 实现单列收支账本浏览、加载/空/错误状态、账户筛选、记录详情入口和安全的移动触控目标。
- [x] 4.6 实现手工现金流水表单、提交中/成功/失败状态和服务端刷新；超时不得自动重试或伪装为已入账。
- [x] 4.7 实现账单选择、密码提示、导入预览、账户映射、配对建议、关系审查和带 `Idempotency-Key` 的确认导入。
- [x] 4.8 用 Native bundler/typecheck/export 验证 Android/iOS 路由、共享包解析、平台文件和 TokenStore 实现；记录当前无法运行真机时的准确条件。
- [x] 4.9 增加 GitHub Actions Native CI：PR、`refactor/web` 推送和手动触发执行质量检查、Android Debug APK 构建与 iOS Simulator `.app` 构建，并上传未签名 artifact；不接入商店签名。

Native 阶段验证证据：

- `npm install` → 根级 workspace 安装完成；未改动 `web/.lan-vite.config.mjs`。
- `npm run test --workspace finance-tracker-mobile` → 2 个测试文件、5 个状态/权限/存储键测试通过。
- `npm run typecheck --workspace finance-tracker-mobile` → 通过。
- `npm run export:android --workspace finance-tracker-mobile` → Android bundle 成功，输出 `mobile/dist`。
- `npm run export:ios --workspace finance-tracker-mobile` → iOS bundle 成功，输出 `mobile/dist`。
- `npm ci --no-audit --no-fund` → 在同步后的根级 lockfile 上干净安装成功，771 个包安装完成；未使用 `--legacy-peer-deps`。
- iOS 模拟器已补跑：`iPhone 17 Pro`（402 × 874 logical points，iOS 26.2，Xcode 26.3），应用 `com.finance.tracker`，Metro 使用 `http://192.168.1.3:8081`，临时 API 使用 `http://192.168.1.3:8766` 和隔离 SQLite 数据库。登录 `200`、创建工作区 `200`、账户/投影/记账选项读取 `200`；工作区、空账本、记账表单、导入入口和系统文件选择器取消均通过。截图：[qa-ios-login.png](qa-ios-login.png)、[qa-ios-import-cancel.png](qa-ios-import-cancel.png)。
- Native 最终复现发现并修复 SecureStore 键名问题：Web 继续使用 `finance-tracker:session-token`，Native 使用合法键名 `finance-tracker-session-token`；最终登录后进入工作区，无客户端异常。系统“保存密码？”提示按测试流程选择“以后”。
- Android 模拟器已补跑：`Medium_Phone_API_36` / `emulator-5554`（Android API 36，1080 × 2400），应用 `com.finance.tracker`，Metro 使用 `http://192.168.1.3:8081`，隔离 SQLite API 在宿主机 `127.0.0.1:18000`、模拟器通过 `http://10.0.2.2:18000` 访问。登录、工作区创建、空账本、记账表单、系统文件选择器取消和账单扫描/映射/预览/关系复核/确认导入已实际走通；手工记账和会话内退出问题已修复并完成复验。
- 针对 Android QA 暴露的两个问题已补充回归测试：`npm run test --workspace @finance-tracker/core`（4 passed）和 `npm run test --workspace finance-tracker-mobile`（5 passed）；`buildCashRecordPayload` 已与现有 `account_name` API 合同对齐，退出事件改为显式 `signed_out` 状态，并已完成设备重跑确认。
- GitHub Actions 工作流已写入 `.github/workflows/mobile-ci.yml`：权限仅为 `contents: read`，触发器覆盖 Pull Request、`refactor/web` 推送和 `workflow_dispatch`；质量 job 使用 Node 24/npm cache，Android 使用 Java 17、API 36、build-tools 36.0.0 和 NDK 30.0.14904198，iOS 使用 `macos-26` 并显式选择 Xcode 26.3，两端均明确关闭商店签名并上传短期测试 artifact。

## 5. API 替代面与服务启动入口

- [x] 5.1 先为现有 CLI 用户侧能力建立 HTTP/API 替代矩阵测试：账户、现金流水、余额校准、转账、关系、导入、投资、查询、报表和事实删除。
- [x] 5.2 补齐账户管理、现金记账/校准/转账和关系审查所缺少的薄 API 路由，全部调用现有 Application Service，并覆盖 `admin`、`editor`、`viewer` 和工作区隔离。
- [x] 5.3 补齐投资事件写入、投资查询、报表和受控同步的 API 或显式后端脚本边界；凭据只在服务端受控位置加载，客户端不接触密钥。
- [x] 5.4 为新增 API 路由补 SQLite 集成失败测试和成功测试，确认 Decimal、来源、关系、幂等和事务语义沿用现有服务。
- [x] 5.5 保留 `create_runtime_app`，新增显式 Uvicorn/开发脚本入口，测试缺少或非法 `FT_DATABASE_URL` 时失败关闭，不自动回退或双写。

## 6. CLI 删除与文档切换

- [x] 6.1 先添加迁移门禁测试：确认无 `ft` script、无产品 CLI import、无未替代的活跃命令引用，同时允许历史 OpenSpec/legacy 审计记录保留。
- [x] 6.2 更新 `README.md`、开发文档、CSV/导入文档、运行时文档和测试命令，改用显式后端、Web 和 Expo workspace 入口。
- [x] 6.3 在替代 API/Web/Native 通过验证后，移除 `pyproject.toml` 的 `[project.scripts] ft` 注册和 `src/ft/cli.py`。
- [x] 6.4 移除仅服务 CLI 的 `src/ft/cli/import_cmd.py`、CLI 展示适配器和 CLI 专属测试；保留被 Application Service、导入器、连接器或 Web 使用的纯逻辑模块。
- [x] 6.5 删除 `web/package-lock.json` 并生成根级唯一 Node lockfile，验证干净安装不会重新注册 `ft` 或产生重复 React/React Native 原生版本。
- [x] 6.6 运行仓库扫描和干净环境安装测试，确认调用 `ft` 不会执行旧命令，文档明确指向 Uvicorn、Web 和 Expo 入口。

## 7. 审查、测试与 QA

- [ ] 7.1 完成 OpenSpec `validate --all --strict`、`openspec doctor`、`git diff --check` 和范围化 diff 复核。
- [x] 7.2 运行受影响 Python 单元/契约/SQLite 集成测试，覆盖正常、边界、错误、重复、超时、权限、工作区隔离和回滚路径。
- [ ] 7.3 配置名称以 `_test` 结尾的专用 PostgreSQL 数据库到 `FT_TEST_POSTGRES_URL`，补跑同一 Application Service/API 契约矩阵；未配置时记录为未完成，不计入通过。
- [x] 7.4 运行 Web Vitest、TypeScript 检查、生产构建、Playwright 主流程和生产预览；覆盖登录、工作区切换、收支账本、导入、错误/空状态、键盘焦点及 390 px/1440 px 截图。
- [x] 7.5 运行 Expo Native typecheck/bundler，并在可用 Android/iOS 模拟器或真机上验证登录、工作区、账本、记账、文件取消和导入确认；记录设备、URL、视口/屏幕、控制台和网络结果。
- [x] 7.6 对最终 Web/Native UI 运行 Hallmark `audit`，记录目标、finding、严重级别、采纳/延期理由和修复结果；critical/major finding 修复后重新审计。
- [x] 7.7 完成依赖、Token/账单密码日志、权限边界、文件上传、CORS/API 地址和敏感配置安全检查；确认没有把真实财务数据写入夹具或缓存。
- [x] 7.8 记录最终 `HEAD`、比较基线、所有实际命令、执行时间、未解决风险和独立产品/工程/设计/安全复核结论。
- [x] 7.9 校验 `.github/workflows/mobile-ci.yml` 的触发器、权限、Node/npm cache、原生依赖安装、无签名构建命令和 artifact 路径；本地执行与 CI 等价的 Expo prebuild/原生构建命令并记录结果。

## 8. 发布准备与反思

- [x] 8.1 编写迁移发布顺序、显式 Uvicorn 回退方式、Web 回退面、Native 暂停发布条件和 CLI 删除后的用户提示。
- [x] 8.2 确认本变更没有数据库 migration；若 API 替代面引入 schema 或通用幂等存储，回退更新 proposal/spec/design/tasks 并重新执行双后端门禁。
- [x] 8.3 完成最终文件清单、未跟踪文件保护检查和用户可见文案/术语扫描；已获得用户对当前仓库 `refactor/web` 提交和推送的明确授权，具体动作在验证通过后执行。
- [x] 8.4 在阶段性失败或重复问题出现时，记录可复用的测试、脚本和架构经验，必要时更新项目统一术语或后续 OpenSpec 变更建议。

## 阶段 5-8 验证与复核记录（2026-09-06，Asia/Shanghai）

### 阶段 5：API 替代面

- PYTHONPATH=tests:.:src uv run pytest -q tests/contract/test_cli_replacement_api.py tests/contract/test_cli_free_runtime.py tests/test_runtime_docs.py tests/test_application_queries.py → 20 passed, 1 skipped, 1 warning；覆盖账户、现金记账、余额校准、转账、查询、报表、关系、投资事件、事实删除、同步/投影运维、Decimal 拒绝和 viewer 拒绝。
- API 实际 SQLite fixture 检查了成功写入、浮点金额拒绝、精确 Decimal 持久化和事务边界；PostgreSQL 参数化 fixture 因未配置 FT_TEST_POSTGRES_URL 准确跳过，见 7.3。
- uv run python -m compileall -q src → 通过。create_runtime_app 保留；运行时文档、显式 Uvicorn 工厂入口和非法/缺失数据库配置测试通过。

### 阶段 6：CLI 删除与文档切换

- tests/contract/test_cli_free_runtime.py → 3 passed；确认无 pyproject.toml 的 ft script、无 src/ft/cli.py / src/ft/cli/、无产品代码导入已删除模块，活跃文档无未替代的 ft 命令引用。
- 已删除 src/ft/acct.py、src/ft/report.py、src/ft/cli.py、src/ft/cli/import_cmd.py、src/ft/adapters/portfolio_cli.py 和 CLI 专属测试；保留纯 CSV 交换适配器、导入器、Application Service、连接器和持久化层。
- web/package-lock.json 已删除；根级 package-lock.json 由 npm workspace 统一管理。npm install 完成，npm ci --ignore-scripts --no-audit --no-fund --dry-run → 通过；未重新注册 ft。

### 阶段 7：审查、测试与 QA

- 变更专属 `openspec validate migrate-expo-remove-cli --type change --strict` → 通过；`openspec doctor` → 通过；`git diff --check` → 通过。全仓 `openspec validate --all --strict` 为 37 passed、1 failed，唯一失败是既有的 `change/cloudflare-access-web-deployment`，不属于本变更，因此 7.1 保持未勾选。
- rebase 到最新 `origin/refactor/web` 后，`PYTHONPATH=tests:.:src uv run pytest -q` → 1526 passed、183 skipped、1 warning（372.16 s）；受影响 API、关系导入、运行时和 CLI 替代目标均通过。
- PostgreSQL 门禁未完成：FT_TEST_POSTGRES_URL 未配置，也未创建名称以 _test 结尾的专用库；不能把跳过项计入通过，7.3 保持未勾选。
- Web：npm run test:web → 14 个文件、143 passed；npm run build:web → 通过；npm run test:preview --workspace finance-tracker-web → 11 passed，Playwright + Chromium 生产预览地址 http://127.0.0.1:5173，覆盖登录/工作区/收支/导入/分类、错误/空状态以及 390 px 和 1440 px。视觉快照 14 passed, 1 failed；唯一失败为既有 1024x768 快照 4312 像素（1%）基线漂移，未更新基线。
- Native：`npm run test --workspace finance-tracker-mobile` → 2 个文件、5 个测试通过（含 SecureStore 合法键名和 `signed_out` 状态回归）；`npm run typecheck --workspace finance-tracker-mobile` → 通过；`npm run export:android --workspace finance-tracker-mobile` → Android bundle 成功；本次修复后的 `npx expo run:android --device Medium_Phone_API_36` → Gradle 构建和 APK 安装成功。
- iOS 真实 QA 使用 iPhone 17 Pro 模拟器（402 × 874 logical points，iOS 26.2，Xcode 26.3）、`com.finance.tracker`、Metro `http://192.168.1.3:8081` 和隔离 SQLite API `http://192.168.1.3:8766`。登录、退出回登录页、创建工作区、空账本、记账表单、导入入口、系统文件选择器及取消通过；API 网络日志中登录/创建工作区/账户/投影/选项读取均为 200。截图：[qa-ios-login.png](qa-ios-login.png)、[qa-ios-import-cancel.png](qa-ios-import-cancel.png)。
- Android QA（2026-09-06 11:09–11:21 CST）：`Medium_Phone_API_36` / `emulator-5554`，Android API 36，屏幕 1080 × 2400，Metro `http://192.168.1.3:8081`，API `http://10.0.2.2:18000`。注册/登录、创建工作区、空账本、记账表单打开、系统 DocumentsUI 文件选择器与取消通过。将仓库 `tests/fixtures/cash_import_browser_refund.csv` 复制为测试文件后完成扫描、创建映射账户、预览、退款关系复核和确认导入；服务端 `/cash-import/scan`、`/preview`、`/commit` 均为 200，页面显示 `2 条新增 · 0 条更新`，回到账本可见净额流水。截图：[qa-android-login-after-relaunch.png](qa-android-login-after-relaunch.png)、[qa-android-file-picker.png](qa-android-file-picker.png)、[qa-android-import-preview.png](qa-android-import-preview.png)、[qa-android-import-success.png](qa-android-import-success.png)、[qa-android-ledger-imported.png](qa-android-ledger-imported.png)。
- Android 修复后 QA（2026-09-06 11:35–11:42 CST）：同一 `Medium_Phone_API_36` / `emulator-5554`、Android API 36、1080 × 2400、Metro `http://192.168.1.3:8081`、API `http://10.0.2.2:18000`。在已有工作区中填写 `12.34 CNY`、交易对方 `Android QA manual` 和备注后保存，服务端 `POST /api/v1/cash-records` 返回 `201 Created`，账本显示 `12.34 CNY` 手工流水；修复后的客户端 payload 使用现有 `account_name` 合同。截图：[qa-android-record-save-fixed.png](qa-android-record-save-fixed.png)。切换到工作区页面退出，服务端 `POST /api/v1/auth/logout` 返回 `200 OK`，会话内立即显示登录表单；冷启动后登录表单仍可见，后续 `/api/v1/auth/session` 返回 `401 Unauthorized`。截图：[qa-android-logout-fixed.png](qa-android-logout-fixed.png)。首次复验中出现一次可恢复的 accessibility capture stalled，重试后 `snapshot -i --force-full` 正常；未见应用崩溃或新增控制台异常。
- Native CI 等价验证（2026-09-07 11:00–11:12 CST）：`ruby -e 'require "yaml"; YAML.load_file(".github/workflows/mobile-ci.yml")'`、`openspec validate migrate-expo-remove-cli --type change --strict` 和 `git diff --check` 通过；在 `mobile/` 执行 `CI=1 npx expo prebuild --platform android --no-install` 后运行 `ORG_GRADLE_PROJECT_ndkVersion=30.0.14904198 ./gradlew :app:assembleDebug --no-daemon --stacktrace`，构建成功并生成 `mobile/android/app/build/outputs/apk/debug/app-debug.apk`（约 200 MB）；在 `mobile/` 执行 iOS prebuild、`pod install --no-repo-update` 和 Generic iOS Simulator Debug build，`FinanceTracker.xcworkspace` 构建成功，生成 `mobile/dist/finance-tracker-ios-simulator.zip`（约 47 MB）。两项本地产物均为未签名开发/模拟器产物。
- GitHub Actions 远程验证 run `34081347940`（commit `d9c7f769b7f2b9fa99f8114b61864e15cfcae3c2`，2026-09-07 03:56–04:14 UTC）：`Shared and JavaScript checks`、`Android Debug APK`（11m47s）和 `iOS Simulator app`（18m01s）三个 job 均成功；已上传 `finance-tracker-android-debug` 和 `finance-tracker-ios-simulator` 两个 artifact，保留 7 天。该 run 使用 `macos-26` + Xcode 26.3、Android API 36/Java 17，产物仍明确为未签名 Debug/Simulator 测试包。
- CI 迭代审计：run `34079470525` 暴露初始 lockfile 与 workspace manifest 不同步，`c89058c8498de7ee60ace62fc979de3e3b00178f` 通过根级 `react-dom@19.2.3` 和 lockfile 修复；run `34080069080` 的质量 job 成功，但 Android 的重复许可证管道触发 `pipefail`、iOS 的 `macos-15` 默认 Xcode 16.4/Swift 6.1 不满足 ExpoModulesJSI 的 Swift 6.2 要求；`81f09f12131b6dca7dbb424ae2fb8312b6a4e432` 删除重复许可证命令，`d9c7f769b7f2b9fa99f8114b61864e15cfcae3c2` 切换到 `macos-26` 并固定 Xcode 26.3，最终 run 已闭合。
- Hallmark 最终 UI 审查按 docs/ui-design-rules.md 和 Hallmark audit 规则执行；当前运行时没有可执行的 Hallmark CLI，因此记录人工等价审查，未声称运行不存在的命令。范围为变更原型、Web 生产预览、Native 登录/工作区/空账本/记账/导入页面及上述截图：critical 0、major 0；Native 使用共享 token、单列布局、至少 48 px 触控目标，加载/禁用/错误/成功/焦点状态均有覆盖，无渐变或伪浏览器外壳。一个 minor finding 是既有 Web CSS 仍有少量非 token 像素字面量，未由本变更引入，延期到独立 UI 清理变更；无阻断项。审查日志已写入 .hallmark/log.json。
- 安全检查：npm audit --omit=dev --audit-level=high 发现 14 moderate severity vulnerabilities，主要来自 Expo 传递依赖 decode-uri-component 和 uuid；npm audit fix --force 会降级/破坏 Expo Router/Expo 版本，未执行。静态扫描未发现项目源码中的真实 Token、账单密码日志或私钥；账单密码仅通过当前请求头传递，连接器凭据仍由服务端加载，测试夹具中的凭据均为明确占位字符串。CORS、API origin、workspace 角色和文件取消边界已检查。
- 最终基线：rebase 后比较基线与合并基点均为 `1619d3c0399909b60cac39d82c9d50cded22c6e0`；代码/CI 验证提交 `d9c7f769b7f2b9fa99f8114b61864e15cfcae3c2`，远程 run `34081347940` 启动时 `origin/refactor/web` 指向同一提交，记录时间 2026-09-07 12:15 CST。工作树保留用户既有的 `.codex/worktrees/`、`.vite/`、`docs/superpowers/plans/2026-08-01-icbc-refund-pairing.md`、根 `prototype/` 和 `web/.lan-vite.config.mjs`，本变更已提交并推送，未创建 PR 或部署。产品复核结论为首个 Native 纵向切片可继续开发；工程复核结论为 API/共享包/CLI 删除边界闭合，Android 记账与退出回归已闭合，Native CI 三个 job 已通过；设计复核无 critical/major；安全复核无新高危问题。

### 阶段 8：发布准备与反思

- 发布顺序：先执行显式 FT_DATABASE_URL + uv run alembic upgrade head，再启动 uv run uvicorn ft.web.app:create_runtime_app --factory，随后发布 Web 生产构建，最后才把满足设备/导入确认条件的 Native build 交给测试。Native 未达条件时只暂停 Native 发布，Web/API 继续作为回退面。
- 服务回退使用同一数据库和显式 Uvicorn 工厂；Web 回退到上一份静态构建/API 组合；CLI 删除后的旧 ft 命令显示为不存在，不恢复兼容别名。Native 的 Xcode 26.3 兼容补丁已纳入 Expo config plugin，若上游修复则先移除补丁并重跑 prebuild/export。
- 本变更没有新增 Alembic migration、数据库表或通用幂等存储；客户端写入继续调用同一 Application Service。回退不需要数据迁移，导入/手工写入仍遵守现有事务和幂等语义。
- 阶段性经验：SecureStore 键名不能复用带冒号的 Web localStorage 键，平台存储键应隔离；Expo 原生依赖修复必须通过可重复、幂等、匹配失败即停止的 config plugin 完成；退出操作必须在网络失败时仍清理本地会话并由 route group 守卫回到登录页。Android 真机式 QA 暴露的 Native 手工记账 `account_id`/`account_name` 契约不一致和会话内登出后的 `idle` spinner 死锁已通过回归测试、Android 构建和设备复验修复。后续独立工作项为 PostgreSQL `_test` 矩阵、Native 真实账单复验、1024 px Web 基线和既有财富性能基线。
