## 1. 思考

- [x] 1.1 确认复现路径：Compact 可见轨道留在 40 dp 触控区中线，滑块贴近右缘；滚动后月份摘要又遮挡轨道。目标为 `origin/main` 的 `compose/` 实现。
- [x] 1.2 确认缺陷边界：三个窗口等级的轨道与滑块均贴齐列表右缘；保留摘要和定位语义，不涉及 Legacy React Web / Expo Native。

## 2. 计划与规格

- [x] 2.1 新增吸顶摘要不得遮挡滚动条的行为要求；无结构或视觉改版，不需要原型。
- [x] 2.2 记录共享 Compose 的 Native / Web 影响与实现策略。

## 3. 回归测试先行

- [x] 3.1 扩展 Compose Playwright 账本滚动场景，以预览截图像素验证吸顶摘要上方的可见轨道；修改轨道位置前该像素对比在 390 px 失败（差异为 0）。

## 4. 实施

- [x] 4.1 将三个窗口等级的轨道绘制偏移与滑块一致，并提高滚动条绘制层级；保持现有 40 dp 命中区域。
- [x] 4.2 更新 `docs/feature-map.md` 中收支账本的 Compose 实现证据，登记共享页面覆盖 Web、Android、iOS。

## 5. 审查

- [x] 5.1 范围化复核确认仅改动共享滚动条绘制偏移与层级、对应 Playwright 回归、功能地图和本变更记录；没有新增用户可见文案。Web Compose、Android、iOS 和共享层受影响，Legacy React Web / Expo Native 不受影响。
- [x] 5.2 Hallmark `audit` 覆盖最终 Compose 账本及 390、768、1440 px 浏览器截图与 Android/iOS 滚动截图；修改区域有 0 critical、0 major、0 minor finding。

## 6. 验证与 QA

- [x] 6.1 Playwright 账本滚动、轨道边缘、点击、拖动、错误重试、空态和 Escape 用例覆盖 390、768、1440 px；Android/iOS 共享模块测试及 Android/iOS 预览构建通过。
- [x] 6.2 记录浏览器、URL、视口、操作步骤、截图、控制台/网络结果、构建与 OpenSpec 校验、当前 `HEAD`、基线和残余风险，详见下方验证记录。
- [x] 6.3 `git diff --check` 通过；最终差异复核未发现超出本次滚动条缺陷范围的代码修改。

## 7. 发布准备

- [x] 7.1 用户明确授权向 `main` 提 PR；源分支为 `codex/compose-ledger-scrollbar`，不直接修改或合并 `main`，不部署。
- [x] 7.2 已将新增行为要求同步至主规格并核对 delta 场景；变更已归档至 `openspec/changes/archive/2026-10-22-compose-ledger-scrollbar-layering/`。

## 8. 反思

- [x] 8.1 回归新增了真实截图像素对比，避免只依赖 Compose Web 语义镜像判断轨道是否可见；Android 与 iOS 的滚动截图也确认共享层级在原生渲染中生效。

## 验证记录

执行日期：2026-10-22（Asia/Shanghai）

- 当前 `HEAD`：`65c60a22ea6264c724c68df971a133c50b9fbbb4`；比较基线：`origin/main`，同一提交。
- Compose Web 预览：在 `compose/qa/web/` 运行 `FT_COMPOSE_PREVIEW_PORT=5198 node scripts/start-compose-demo.mjs`，生产预览构建成功（49 项 Gradle task）。构建有既存 API 弃用与 Webpack 体积/依赖警告，无编译错误。
- Playwright Chromium：访问 `http://127.0.0.1:5198/w/workspace-1/`，视口为 390 × 844、768 × 1024、1440 × 1000。操作包含滚动到账本月份吸顶摘要、检查轨道与滑块右缘、滚动条点击和拖动到未加载流水、错误重试、空态及按 Escape 关闭筛选菜单。主回归命令 `FT_COMPOSE_PREVIEW_PORT=5198 FT_CAPTURE_COMPOSE_SCREENSHOTS=1 npm run test:e2e -- --grep 'F-03 FAB 覆盖列表|F-03 窄滚动条轨道|F-03 滚动条交互热区|F-03 滚动条拖动后定位|F-03 账本请求失败后可重试|F-03 账本没有记录时显示空态|F-03 Escape 先关闭筛选选项菜单'` 7 项通过；补充命令 `FT_CAPTURE_COMPOSE_SCREENSHOTS=1 npm run test:e2e -- --grep 'F-03 滚动条拖动后定位到未加载流水'` 1 项通过。浏览器诊断断言通过：无意外控制台错误、失败请求或静态资源 HTTP 错误；错误态用例中的模拟 API 错误为预期行为。
- 浏览器截图：[`ledger-sticky-compact-390.png`](screenshots/ledger-sticky-compact-390.png)、[`ledger-sticky-regular-768.png`](screenshots/ledger-sticky-regular-768.png)、[`ledger-sticky-wide-1440.png`](screenshots/ledger-sticky-wide-1440.png)、[`ledger-scrollbar-drag-active-compact-390.png`](screenshots/ledger-scrollbar-drag-active-compact-390.png)。
- Android：`FT_COMPOSE_DEMO_MODE=true ./gradlew :androidApp:installDebug` 成功；Android 16 / API 36 AVD 上执行 `ANDROID_SERIAL=emulator-5556 FT_COMPOSE_DEMO_MODE=true ./gradlew :shared:connectedAndroidDeviceTest -Pandroid.testInstrumentationRunnerArguments.class=com.finance.tracker.CashRecordDetailDeviceTest`，3 项通过。另一次命令 `ANDROID_SERIAL=emulator-5556 FT_COMPOSE_DEMO_MODE=true ./gradlew :shared:connectedAndroidDeviceTest` 跑 157 项，155 项通过、2 项失败、0 项跳过；失败仅在未修改的 `ResponsiveAppShellDeviceTest.desktopRailExpandsOnActivationAndAccountMenuKeepsWorkspaceActions` 与 `compactDrawerKeepsSixFlatDestinationsAndBottomAccountEntry`，均为“收支账本”导航文案未显示断言，与滚动条文件无关。
- Android 原生截图：390 dp 宽 × 600 dp 高的 Compact 短视口用于让合成账本记录可滚动；[`ledger-android-compact-390.png`](screenshots/ledger-android-compact-390.png) 为 390 × 900 dp 初始态，[`ledger-android-sticky-compact-390x600-active.png`](screenshots/ledger-android-sticky-compact-390x600-active.png) 为滚动中吸顶态，轨道贴卡片右缘且显示在月份摘要上方。验证后恢复模拟器尺寸设置。
- iOS：`./gradlew :shared:testAndroidHostTest :shared:iosSimulatorArm64Test` 成功；`FT_COMPOSE_DEMO_MODE=true xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -destination 'id=211AB001-23FF-425B-8812-FF897999C70B' -derivedDataPath /tmp/compose-ledger-scrollbar-ios-derived build` 成功。iPhone 16 / iOS 18.3.1（393 × 852 pt）上以 `agent-device` 打开 demo 并滚动到账本吸顶摘要；[`ledger-ios-sticky-compact-active.png`](screenshots/ledger-ios-sticky-compact-active.png) 显示轨道、滑块位于摘要表面上方并贴近账本右缘。
- OpenSpec：`openspec validate --all --strict` 和 `openspec doctor` 均通过；最终 `git diff --check` 通过。
- 归档后复核：2026-10-22 15:51:53 CST；`openspec validate --all --strict` 为 31 项通过、0 项失败，`openspec doctor` 与 `git diff --check` 均通过；此时 `HEAD` 与 `origin/main` 均为 `65c60a22ea6264c724c68df971a133c50b9fbbb4`。
- UI 审计：按 Hallmark `audit` 参考检查滚动区域、吸顶叠层、右缘对齐、触控状态与代表视口截图；结果为 0 critical、0 major、0 minor finding。本次未改变页面结构或文案。
- 残余风险：全量 Android 设备矩阵中的上述 2 项导航测试失败尚未在基线分支复测；本次未修改对应导航实现。iOS/Android 宽屏布局由 Compose Web 三尺寸浏览器回归、共享模块目标测试和原生 Compact 截图覆盖，未额外生成原生平板截图。
