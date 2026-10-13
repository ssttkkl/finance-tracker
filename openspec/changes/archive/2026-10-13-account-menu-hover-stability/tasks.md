## 1. Thinking

- [x] 1.1 复现头像停留时的菜单开关循环并确认根因。生产 Compose Web 预览 `http://127.0.0.1:5186/w/aba98eed-8f7b-4a53-b58b-469190501fae/cash-import`、1045×1000 下，打开帧与 75 ms 后面板帧不同；修复前 Playwright 截图回归失败。临时 Compose 日志显示 Popup 出现后 `triggerHovered` 变为 false，宽限期后 `hoverOpen` 与 rail 状态关闭，随后再次命中头像并循环。无障碍树会保留隐藏菜单节点，因此用可见区域截图判断开关状态。
- [x] 1.2 Cross-platform Impact Check：Web Compose 是受影响目标；`AppShell.kt` 位于共享 `commonMain`，Native Compose 共用头像键盘激活和菜单语义，Android 测试编译通过。指针悬停只在支持悬停的设备生效；旧 React Web、Expo、API、路由、持久化和数据库不受影响。未连接 Android 设备，Native 交互设备 QA 尚未执行。

## 2. Plan

- [x] 2.1 验收目标：指针停在头像时面板稳定打开，移入面板仍打开，离开后关闭；键盘聚焦头像后 Enter/Space 可打开，Escape 关闭并还焦；紧凑布局、工作区选择和错误恢复继续可用。现有主规格已覆盖本次交互合同，delta 补充面板稳定与键盘验收。
- [x] 2.2 不调整布局、信息架构、文案和视觉 token；复用已确认的 `app-shell-icon-navigation` 原型。固定头像 hover sensor、同步菜单与侧栏的 hover-open 状态，悬停 Popup 不抢占鼠标 hover，键盘按键在 KeyDown 阶段消费以避免重复激活。

## 3. Task consistency and test-first

- [x] 3.1 先加入截图回归，再确认修复前在生产预览失败；回归从侧栏空白区移到头像，以 1045×1000 的账号面板裁剪连续采样 20 次（每次间隔 75 ms），验证可见面板不闪烁。另覆盖指针进入/离开面板和离开侧栏后收起。
- [x] 3.2 添加键盘回归，覆盖聚焦扩展、Escape 关闭、Space/Enter 打开及 click-open 状态下 Escape 关闭；修复前 Enter/Space 未触发头像操作，修复后浏览器验证通过。

## 4. Build

- [x] 4.1 `PersistentNavigationShell` 把 hover-open 状态作为临时展开原因；头像区域改用固定宽度 hover sensor；hover-open Popup 不接收焦点，click-open Popup 保持可聚焦；头像按钮明确处理 Enter 和 Space。
- [x] 4.2 更新 [`docs/feature-map.md`](../../../../docs/feature-map.md)，记录头像菜单悬停稳定、键盘激活与 Escape 焦点返回，并关联本变更证据。

## 5. Review

- [x] 5.1 人工复核最终差异：悬停与 click-open 状态独立；hover 传回 shell 后随菜单状态复位；固定 sensor 在 rail 动画期间保持命中区域；按键 KeyDown 被消费；紧凑抽屉仍使用点击打开。变更不含业务或持久化路径。当前工具面没有独立本地代码审查动作，已完成范围化人工复核。
- [x] 5.2 Hallmark 审计：本运行时没有可调用的 Hallmark audit 动作，按 `.agents/skills/hallmark/references/verbs/audit.md` 与 `anti-patterns.md` 对照 AppShell 变更和生产截图手工审查。范围：头像账号面板、侧栏悬停、compact 底部面板，截图见下方 1045、1440、390 px。审查结果：0 个 critical、0 个 major、0 个 minor；没有新增布局、文案、图形装饰或 token，沿用设计系统颜色。结论：无需要修复的设计问题。

## 6. Validation and QA

- [x] 6.1 浏览器：Playwright Chromium，`http://127.0.0.1:5186/`；手动复现路径对应 `/w/aba98eed-8f7b-4a53-b58b-469190501fae/cash-import`。生产 Compose Web 预览覆盖 320、375、390、414、600、768、1045×1000、1440 px；侧栏组 8/8 通过，包含停留 1.5 s 截图稳定采样、面板移入/移出、侧栏收起按钮、键盘 Escape/Space/Enter、紧凑抽屉、工作区切换和权限失败重试。控制台错误、失败请求、静态 HTTP 错误均为 0。截图：`/tmp/compose-app-shell-account-hover-stable-1045.png`、`/tmp/compose-app-shell-account-hover-1440.png`、`/tmp/compose-app-shell-compact-390.png`、`/tmp/compose-app-shell-account-escape-768.png`。
- [x] 6.2 环境与命令：Node.js v24.16.0；OpenSpec CLI 1.7.0。生产 Wasm 构建由 Playwright `webServer` 执行 `:webApp:wasmJsBrowserDistribution`，成功；受影响浏览器命令 `FT_COMPOSE_PREVIEW_PORT=5186 npm run test:e2e:compose -- tests/compose-app-shell.e2e.ts`，8/8 通过（55.6 s）。共享验证 `ANDROID_HOME=/Users/huangwenlong/Library/Android/sdk ./gradlew :shared:allTests :shared:compileAndroidDeviceTest --no-configuration-cache` 成功（1m32s）；Android 设备列表为空，故未执行设备交互 QA。归档后 `openspec validate --all --strict` 25/25 通过，`openspec doctor` 正常；`git diff --check` 通过。HEAD 与比较基线：`d2b1de008b6539ed2d802e59a35994ab2d3df087`（分支 `refactor/kmp`）。无数据库、接口和财务计算改动，不适用 SQLite/PostgreSQL 矩阵。
- [x] 6.3 可见文案必要性和范围化术语复核：无新增或修改产品文案；未引入数据库、领域模型或其他内部实现术语。

## 7. Release

- [x] 7.1 已记录回滚准备：回滚 `AppShell.kt` 交互修复、回归测试、主规格和功能地图条目；保留 OpenSpec 归档供审计。不涉及数据库回滚或部署。

## 8. Reflection

- [x] 8.1 防复发由生产预览截图采样覆盖；手动验证 1440 和 390 px 画面。Native 共用源码已编译，设备悬停行为仍需在有指针输入的 Native 设备上确认。
