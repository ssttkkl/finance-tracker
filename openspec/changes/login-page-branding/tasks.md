# 任务

## 1. 思考
- [x] 明确仅修改 Compose 登录/注册页的品牌与展示文案，注册校验、系统应用名和旧 React/Expo 实现不变。
- [x] 阅读认证与跨平台展示规格、项目上下文、UI 规则及相关实现；风险为共享页面影响 Web、Android、iOS 三端。

## 2. 计划
- [x] 记录验收：登录页显示 Finance Tracker、不显示“工作区访问”及密码长度提示；注册页保留密码长度提示和现有校验。
- [x] 更新 `design/login.pen`，覆盖 320、375、390、414、768、1440 px 登录画布和注册状态；设计审查待实施后复核。
- [x] Pen 初稿复核发现 40 dp 输入框与 36 dp 按钮未表达项目 44 dp 最小触控目标；经用户确认后通过 Pen CLI 为输入、主按钮、注册入口增加透明 44 dp 命中容器，不改变可见样式。
- [x] 用户确认增加独立注册画板；通过 Pen CLI 为 320、375、390、414、768、1440 px 增加注册页面，复用 Finance Tracker 登录视觉、现有注册文案与 44 dp 透明命中容器。
- [x] Cross-platform Impact Check：Compose `commonMain` 同时影响 Web/Android/iOS；旧 React/Expo 与共享业务校验不受影响。

## 3. 任务拆分与一致性
- [x] 添加 Playwright 回归用例，覆盖 390/1440 px、品牌、移除标签、登录/注册密码提示差异及截图。
- [x] 核对 proposal、delta spec、design 与本任务验收一致。

## 4. 构建
- [x] 先在旧实现运行新增 Playwright 用例并确认失败：品牌文案未找到，符合预期。
- [x] 在共享 Compose 登录/注册界面实现独立品牌资源、移除访问标签、仅注册状态展示密码提示。
- [x] 依据确认后的 Pen 命中容器，在 Compose 为两个 `UnstyledTextField` 增加 44 dp 外层并保留 40 dp 可见输入框；主按钮与模式切换继续使用 44 dp `UnstyledButton` 外层。
- [x] 更新功能地图和所需资源文案，不改变 `app_name` 或认证校验。

## 5. 审查
- [x] 复核最终 diff、范围、四语言资源覆盖、注册提示与校验行为；保留系统 `app_name` 不变。
- [x] 独立设计复核：对照更新后的 Pen 与最终 390/1440 px 登录截图、390/1440 px 注册截图；确认结构、布局、触控目标与卡片视觉一致，无裁切/溢出或阻断 finding。

## 6. 测试与 QA
- [x] 从 `web/` 运行 `npm run test:e2e:compose -- tests/compose-access.e2e.ts`：认证用例 9/9 通过；覆盖 44 dp 命中区与 40 dp 可见输入框、登录/注册、键盘焦点、错误反馈；注册模式覆盖 320/375/390/414/768/1440 px 无横向溢出及卡片宽度。
- [x] 生产构建预览 `http://127.0.0.1:5186/` 的 Playwright Chromium QA 完成；保存 390×900、1440×900 登录与注册截图，并对照同宽 Pen 画板检查。
- [x] `npm run compose:build:web` 成功（Webpack 有既存包体积/critical dependency 警告）；`npm run compose:test` 成功；`openspec validate login-page-branding --type change --strict`（OpenSpec 1.7.0）与 `git diff --check` 通过。Android 构建因缺少 SDK 未运行；iOS 原生设备 QA 未运行。
- [x] 可见文案必要性自检及认证文案范围化搜索完成；移除登录提示与冗余标签，注册仅保留一处密码提示。

## 7. 发布
- [x] 记录交付证据和补跑条件；配置 Android SDK 后补跑 `npm run compose:build:android`。本任务不部署。

## 8. 反思
- [x] 保留登录/注册差异及 Pen/Compose 几何回归断言，防止密码提示条件或 44 dp 命中区回退。

## 验收证据
- 日期：2026-10-09（Asia/Shanghai）；历史基线：`a7961efdea1881615c9a155ab311e979b9f4dba5`；本轮验证 `HEAD`：`e34bdb307be6a8665b8532947db2788a35539af8`。
- 浏览器截图：`web/test-results/compose-access.e2e.ts-Compose-登录与注册显示对应的品牌和密码提示/login-390.png`、`web/test-results/compose-access.e2e.ts-Compose-登录与注册显示对应的品牌和密码提示/login-1440.png`、`web/test-results/compose-access.e2e.ts-Compose-登录与注册显示对应的品牌和密码提示/register-390.png`。
- Pen CLI：`pen --in design/login.pen --out design/login.pen ... --agent codex --export /tmp/login-accessible-final.png` 成功更新正式设计稿；第一次 CLI 导出可见画板，第二次导出异常为全黑图像，已以第一次 CLI 生成稿恢复正式文件。最终图片与回归证据待补核。
- 最终认证 E2E：`npm run test:e2e:compose -- tests/compose-access.e2e.ts` 从 `web/` 执行，9/9 通过；`npm run compose:build:web` 与 `npm run compose:test` 成功；严格 OpenSpec 校验与 `git diff --check` 通过。
- 本轮 Pen CLI 首次导出的预览 `/tmp/login-accessible-preview.png` 显示原有画板；同提示第二次导出异常为全黑 PNG，未保留该次输出，正式稿恢复为首次 Pen CLI 生成的有效设计文件。该导出异常未改变最终截图和已保存的 `.pen` 画板。
- 注册页扩展已获用户确认，Pen CLI 新增六个独立画板；生成预览 `/tmp/login-registration-v2-preview.png` 显示各注册画板与登录视觉一致。Pen 文件 320/375/390/414/768/1440 六个注册根画板均已核实；注册画板输入框、按钮、模式入口均为 44 dp 透明命中区。浏览器 320px 截图无横向溢出；390/1440px 登录和注册截图已完成设计复核，无阻断 finding。
- 未执行：Android 原生打包（缺少 SDK）；iOS 原生设备验收未运行。剩余风险仅为未覆盖原生设备字体/焦点栅格差异；共享 Compose Wasm/Web 验收已通过。

## 后续设计规则调整（2026-10-09）
- [x] 按用户要求，将 Pen 登录/注册画板精简为 Compose `WindowSizeClass` 三类：`COMPACT` 390 px、`REGULAR` 768 px、`WIDE` 1440 px；保留既有状态画板、布局和触控目标。
- [x] 更新 `AGENTS.md` 与 `docs/ui-design-rules.md`：三类各一张设计代表画板及对应浏览器代表视口；断点边界、最小支持宽度和缺陷宽度仅作补充 QA，不要求新增设计稿。
- [x] Pen CLI 导出预览 `/tmp/login-window-classes.png` 已目视确认包含六张登录/注册类别画板及既有状态画板，布局和视觉未重新设计；Pen 节点 ID 与类别映射已同步至 `design.md`。
- [x] 将认证 E2E 的登录及注册对照截图视口同步为三类代表值（390/768/1440 px）；注册溢出与卡片宽度仍保留额外窄屏/边界宽度回归检查。
- [x] 本次未更改 Compose 运行时代码；三类视口浏览器 QA 覆盖交互、横向溢出、卡片几何与 Pen 同视口截图。`npm run test:e2e:compose -- tests/compose-access.e2e.ts` 从 `web/` 执行，9/9 通过；`openspec validate login-page-branding --type change --strict` 与 `git diff --check` 通过。

## 10. 认证表单重设计与验证

- [x] 更新 proposal、跨端 delta、design，记录紧凑/常规/宽屏布局、三项实时校验、表单级错误及 24 张完整状态页面的状态矩阵。
- [x] Cross-platform Impact Check：共享 Compose `commonMain` 同步影响 Web、Android、iOS；认证 API 和持久化不变；深色主题保留现有颜色角色并沿用新布局。
- [x] 更新 `DOMAIN_GLOSSARY.md` 中的字段校验项术语，并在 UI 规则中明确必要的字段校验反馈不计入常驻帮助文案上限。
- [x] 通过 Pen CLI 更新 `design/login.pen`：为登录默认/登录中/登录错误与注册默认/校验通过/校验失败/注册中/注册错误，分别按 `COMPACT`、`REGULAR`、`WIDE` 建立完整页面画板，共 24 张；移除旧片段式状态板。
- [x] 对照状态矩阵与 24 张画板复核浅色视觉及深色 token 适配说明；发现 `REGULAR`/`WIDE` 卡片内容贴边后，重新通过 Pen CLI 为所有对应状态补齐内边距，并再次检查完整画板概览。节点映射记录于 `design.md`。
- [x] 按用户补充区分强/弱校验提示：密码长度强提示在空值时仍显示；邮箱格式与确认密码一致性弱提示在空值时隐藏，仅在输入后实时反馈。Pen CLI 更新 3 张注册默认画板及适用状态，预览 `/tmp/login-auth-hint-rules-preview.png`。
- [x] 再次独立复核 24 张完整状态画板及深色注释：发现默认态焦点色误用、空弱提示占位、确认密码失败文案错误、样例说明泄露到画面、认证模式切换位置不一致及输入框到按钮间距过大；通过 Pen CLI 修复。无错误时字段到主按钮约 24 px，有通用错误时保留错误提示与按钮间距；`REGULAR`/`WIDE` 两个模式入口统一收在卡片内，`COMPACT` 紧随按钮。
- [x] 验证设计导出：24 张状态页与 1 张深色模式注释；无纯黑填充残留；成功/失败/中性提示使用对应颜色角色；透明模式入口点击区域高 44 px；整页预览已目视审查。正式稿为 `design/login.pen`，完整预览临时导出为仓库根 `--preview-output`。
- [x] Cross-platform Impact Check：本轮仅完善共同认证设计契约，不新增平台差异；Web、Native、共享 presentation 均须按确认后的 24 状态实现，Compose Web 浏览器 QA 和 Native 适配门禁仍适用。
- [x] 按用户截图发现的按钮文字偏右问题，通过 Pen CLI 调整 24 张状态页主按钮标签的水平/垂直居中；登录中/注册中加载圆点与状态文字作为整体居中。节点数量与 ID 保持不变，纯黑填充为 0；全量导出预览 `/tmp/login-auth-centered-buttons-preview.png` 已目视核对。
- [x] 用户继续指出「已有账户？登录」仍偏右。复核发现模式切换文案与主按钮是独立定位节点；通过 Pen CLI 仅调整 24 张页面的入口文案 x 坐标（登录 15 张、注册 9 张），按卡片/视口中心对齐，未移动点击区域或其他节点。预览 `/tmp/login-auth-centered-all-content-preview.png`；画板总数 24，黑色填充残留 0。
- [x] 按用户确认将注册页底部登录入口替换为标题左侧的仅图标返回按钮：目标为登录页，图标为左箭头、无可见文字、无障碍名称为「返回登录」。Pen CLI 更新全部 15 张注册状态画板，删除旧文案及其点击区；`REGULAR`/`WIDE` 卡片依内容收紧后仍垂直居中，`COMPACT` 保持全屏顶部布局。
- [x] 按用户反馈修正返回图标与标题的垂直关系：依据 Inter 24 px Bold ascent，将 15 个箭头图标的 y 从 10 调整为 3.75，使图标下沿与标题 baseline 对齐；按钮热区、标题及其他节点不变。Pen CLI 全量预览 `/tmp/login-auth-back-baseline-preview.png` 已目视复核，结构化比较确认节点数量与 ID 不变且仅有 15 个图标 y 坐标变化。
- [x] 按用户要求将 9 张登录画板标题统一改为「登录」、15 张注册画板标题统一改为「创建账户」。Pen CLI 预览 `/tmp/auth-title-copy-preview-24-boards/` 已抽查；结构化比较确认 393 个节点及 ID 不变，仅 24 个标题文本内容变化，返回箭头 baseline 保持不变。
- [x] Cross-platform Impact Check：此认证导航变化影响共享 Compose、Web、Android、iOS；登录/注册结果和认证 API 不变；各端须以可访问标签提供图标按钮语义。
- [x] 更新 proposal、跨端 delta、design 和 UI 规则，明确仅图标返回按钮的无障碍名称与注册页无底部登录链接。
- [x] 展示本轮 Pen 预览并取得用户确认；用户确认后开始实现导航控件。
- [x] 展示 Pen 预览并取得用户明确确认；用户确认后开始实现 Compose。
- [x] 为邮箱实时状态、12 字符密码状态、确认密码一致性、表单级提交错误和确认密码不出请求/日志增加失败优先回归测试；另修复输入状态流初始快照误清空空表单提交错误的竞态。
- [x] 按确认稿实现共享 Compose 表单和可复用校验字段组件；更新四语言资源、语义 ID、键盘/读屏行为和功能地图。
- [x] 独立审查最终实现与 Pen 的 24 状态页面；初审 P1 指出 Web Tab 可能双重推进，P2 指出卡片左右内边距/baseline 证据不足。修复为 Web 唯一全局 Tab capture、非 Web 使用本地 Preview handler；确认密码实时错误用于验证 Tab 到达对应字段。REGULAR/WIDE 控件左右内边距均断言 32 dp（±2 dp）；返回按钮以 `alignBy` 和标题 `alignByBaseline` 对齐，画布下沿与自定义对齐线均为 27.75 dp。独立复审确认 P1/P2 已解除；建议补测 baseline 的浏览器几何回归，不作为阻断项。
- [ ] 完成 Compose 测试、Web 构建与 Playwright 三类视口/状态/键盘/错误/空值/dark theme QA，并记录截图和控制台/网络结果；浅色主题注册流程第二次 Tab 仍停留在密码框，用户明确要求本次先忽略该回归并继续提交推送，问题保留为已知遗留，不能标记浏览器 QA 完成。
- [x] 运行严格 OpenSpec 校验、`git diff --check` 和适用 Android 验证评估；Android SDK 缺失，Native 原生设备检查未运行并记录补跑条件。
- [x] 记录发布准备、回滚方式及防止确认密码流入 API/日志的回归保护；本轮不部署。

### 本轮实现与复验（2026-10-10）
- 生产构建：`npm run compose:build:web` 成功；仅有已知 Webpack 大资源包及 dynamic dependency 警告。预览地址 `http://127.0.0.1:5186/`。
- 共享测试：`npm run compose:test` 成功（46 actionable tasks）。
- 浏览器 QA：Playwright Chromium 生产预览，深色主题全量 9/9 通过；浅色主题全量 8/9，认证布局测试失败：注册流程第一次 Tab 正确到密码框，但第二次 Tab 后焦点仍留在密码框，确认密码实时校验未触发。用户明确要求本次忽略此项并继续交付；QA 仍记为未完成，后续修复焦点导航后重跑浅色与深色完整套件。排除该布局/校验场景的补充套件曾因超时和页面未挂载报 6/8，登录 Tab 顺序与认证必填诊断用例随后各自单测通过，符合间歇性浏览器启动/负载波动；其余已覆盖的视口、错误/空值、字体、主流程测试通过。仅有 Node `NO_COLOR`/`FORCE_COLOR` 环境警告。
- 代表画板截图：`web/test-results/compose-access.e2e.ts-Compose-认证页面匹配确认稿的布局、标题与校验/` 下的 `login-390.png`、`login-768.png`、`login-1440.png`、`register-default-390.png`、`register-default-768.png`、`register-default-1440.png`、`register-validation-error-390.png`、`register-validation-error-768.png`、`register-validation-error-1440.png`。目视复核注册 `REGULAR` 卡片为 440×458 px、居中且保留 32 px 内边距；三类布局与状态符合已确认 Pen。
- `openspec validate login-page-branding --type change --strict` 与 `git diff --check` 通过，当前 `HEAD` 为 `e34bdb3`。Android 原生构建因环境缺少 SDK 未执行；iOS 设备 QA 未执行。按用户明确要求，在保留上述焦点缺陷记录的前提下继续提交推送；具备 Android SDK 后运行 `npm run compose:build:android`，并在 Android/iOS 原生设备复核字体与焦点行为。
- 回滚：按需回退本次认证界面、资源、语义 ID、规格与测试变更；认证 API、持久化及服务端数据结构未改动。本轮未提交、推送或部署。
