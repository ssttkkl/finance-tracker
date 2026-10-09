# 任务

## 1. 思考
- [x] 明确仅修改 Compose 登录/注册页的品牌与展示文案，注册校验、系统应用名和旧 React/Expo 实现不变。
- [x] 阅读认证与跨平台展示规格、项目上下文、UI 规则及相关实现；风险为共享页面影响 Web、Android、iOS 三端。

## 2. 计划
- [x] 记录验收：登录页显示 Finance Tracker、不显示“工作区访问”及密码长度提示；注册页保留密码长度提示和现有校验。
- [x] 更新 `design/login.pen`，覆盖 320、375、390、414、768、1440 px 登录画布和注册状态；设计审查待实施后复核。
- [x] Cross-platform Impact Check：Compose `commonMain` 同时影响 Web/Android/iOS；旧 React/Expo 与共享业务校验不受影响。

## 3. 任务拆分与一致性
- [x] 添加 Playwright 回归用例，覆盖 390/1440 px、品牌、移除标签、登录/注册密码提示差异及截图。
- [x] 核对 proposal、delta spec、design 与本任务验收一致。

## 4. 构建
- [x] 先在旧实现运行新增 Playwright 用例并确认失败：品牌文案未找到，符合预期。
- [x] 在共享 Compose 登录/注册界面实现独立品牌资源、移除访问标签、仅注册状态展示密码提示。
- [x] 更新功能地图和所需资源文案，不改变 `app_name` 或认证校验。

## 5. 审查
- [x] 复核最终 diff、范围、四语言资源覆盖、注册提示与校验行为；保留系统 `app_name` 不变。
- [x] 独立设计复核：对照 Pen 与 390/1440 px 登录截图、390 px 注册截图；未发现裁切、溢出或层级问题，无阻断 finding。

## 6. 测试与 QA
- [x] Compose Web Playwright 认证用例 9/9 通过，覆盖主表单、注册切换、错误反馈、键盘焦点、辅助技术标签与视口溢出。
- [x] 生产预览 Chrome QA：`http://127.0.0.1:5186/`；390×900 与 1440×900 登录截图、390×900 注册截图；另检查 320/375/414/768 px 无横向溢出。Playwright 诊断未发现控制台错误、失败请求或静态资源错误。
- [x] `:shared:wasmJsTest` 通过；`openspec validate login-page-branding --type change --strict` 与 `git diff --check` 通过。Android `assembleDebug` 因未配置 `ANDROID_HOME` 或 `compose/local.properties` 中的 `sdk.dir` 无法运行；iOS 原生设备 QA 未运行，相关残余风险已记录。
- [x] 可见文案必要性自检及认证文案范围化搜索完成；移除登录提示与冗余标签，注册仅保留一处密码提示。

## 7. 发布
- [x] 记录交付证据和补跑条件；配置 Android SDK 后补跑 `npm run compose:build:android`。本任务不部署。

## 8. 反思
- [x] 保留登录/注册差异的 E2E 回归断言及 390/1440 px 截图，防止注册密码提示被误删或重新显示在登录页。

## 验收证据
- 日期：2026-10-09（Asia/Shanghai）；基线与最终代码提交前 `HEAD`：`a7961efdea1881615c9a155ab311e979b9f4dba5`。
- 浏览器截图：`web/test-results/compose-access.e2e.ts-Compose-登录与注册显示对应的品牌和密码提示/login-390.png`、`web/test-results/compose-access.e2e.ts-Compose-登录与注册显示对应的品牌和密码提示/login-1440.png`、`web/test-results/compose-access.e2e.ts-Compose-登录与注册显示对应的品牌和密码提示/register-390.png`。
- 生产构建 `npm run compose:build:web` 成功；登录与注册专用 E2E 通过，认证文件完整回归 9/9 通过；后续仅调整截图稳定性断言，定向用例再次通过。
- 未执行：Android 原生打包（缺少 SDK）；iOS 原生设备验收未运行。Compose `commonMain` 共享实现已通过 Wasm 测试和 Web 浏览器验证。
