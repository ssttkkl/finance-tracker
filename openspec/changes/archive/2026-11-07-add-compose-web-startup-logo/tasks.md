## 1. 思考

- [x] 1.1 阅读受影响规格、代码、词表与产品/UI 规则，完成项目 grilling，确认启动动画与兼容提示范围。

B 类局部增强：仅 Web 启动层、平台启动脚本与浏览器兼容提示，无存储、API、财务或依赖版本变化。当前隔离 worktree 分支为 `feat/compose-web-wasm-loading-animation`，验证时 HEAD、diff 基线和最新 `origin/main` 均为 `d63ecdae9a239bfc0a8434526542e1ad026e4e77`；验证对象为当前未提交工作树。保留用户未跟踪的 `.commandcode/`。

Cross-platform Impact Check：Wasm/JS Web 入口、初始 HTML/CSS 及平台启动脚本受影响；Native 不经过这些代码，Android/iOS 编译和设备 QA 不适用。共享 `compose/shared` 的字体、主题、认证和业务加载未改；JS 开发目标不新增 GC 门槛。无持久化、权限或后端变化，不触发双后端、数据迁移、性能及安全专项。

## 2. 计划

- [x] 2.1 通过 openspec-propose/update-change 维护 proposal、design 与 shared/cross-platform-presentation delta，核对一致性。

用户已确认仅居中 Logo 呼吸，不做进度条；通过 6 秒专用预览确认动画。新增兼容提示列 Chrome、Edge、Firefox、Safari 最低版本，并单独说明 iPhone/iPad 系统要求。正常加载只有 Logo，不兼容时为升级标题、版本列表、系统说明与官方链接，无冗余信息。版本与发布日期的官方来源、实际编译要求及替代方案见 design。

已应用 compose-material3、domain-glossary 和 Hallmark；没有新财务领域概念，词表不需修改。已用真实 Chromium 阅读 M3 Motion physics system、Applying M3 Expressive 与 Assistive technology 正文。非重大 UI，不需业务 HTML 原型；现行 UI 规范明确 Pen 只保留作历史参考。

## 3. 任务拆分

- [x] 3.1 对齐正常启动、首帧、减少动态效果、缺失运行特性、旧 CSS、滚动/键盘、分目标资源及文档验收，先运行失败回归再实现。

## 4. 构建

- [x] 4.1 先补并运行失败回归，再实现独立渲染容器、Logo 呼吸及首绘交接。
- [x] 4.2 先补缺少 Wasm/GC/legacy EH 的失败回归，再实现 ES5 能力检测与升级提示；保留 JS 目标能力边界。
- [x] 4.3 相称编译后提供 Logo 和新增提示的真实 Web 演示。
- [x] 4.4 记录新增兼容提示的用户视觉确认。

失败证据：初始 Logo 回归因元素不存在失败；兼容回归因 `#browser-unsupported` 不存在失败；模拟旧引擎忽略 `inset`/CSS 变量时，提示位于 `y=320` 而非 `y=0`。修复后均转绿。旧引擎使用显式四边定位及 Git 浅色 token fallback；现代浏览器消费命名主题变量。

正常演示 `http://127.0.0.1:5186/` 使用最新离线分发。仓库外 `5188` 代理仅把应用脚本延迟 6 秒，用户已确认 Logo；`5190` 代理仅在本机预置能力检测失败，供直接查看新提示。两者不新增产品路由、参数或最短加载时间。新增提示已提供用户预览，用户于 2026-11-07 确认文案和布局符合预期，并要求按原授权继续归档和 PR。Logo 录制 `/tmp/finance-tracker-startup-animation.gif`，提示截图 `/tmp/finance-tracker-browser-support-preview.png` 和 `-dark.png`。

## 5. 审查

- [x] 5.1 独立工程、范围及最终 diff 复核，按产品编码规范 §7 检查。
- [x] 5.2 独立 codebase-degradation-review 调度适用 UI 复用审查，并完成 Hallmark audit 与独立设计复核。

独立 `startup_engineering_review` 覆盖全部启动逻辑、JS/Wasm 桥、容器、脚本顺序、能力探针与错误边界。唯一 major finding 为旧浏览器不支持 `inset`，已由失败回归复现并修复，增量重审确认清零。探针在实际引擎可验证并实例化，GC 为 finalized struct 编码，EH 确含 legacy try/throw/catch。检测与当前 Kotlin 产物的 instantiateStreaming、Tag 使用一致，不硬要求 JSTag/原生 js-string builtins；网络错误不转兼容提示。ES5 gate、动态 script async=false 顺序、JS 无 GC 要求均通过复核。

首帧时序已独立核对 Maven sources：Compose 1.12.1 只清空指定容器，Skiko 0.150.1 同步 drawFrame→flushAndSubmit→flush 后才到下一 rAF；根闭包只通知一次，绘制异常不伪装成功。递归 shadow DOM、字体、焦点、路由和认证边界未发现静态回归。

独立 `startup_design_review` 仅调度 UI reuse；没有模型、DTO、存储或接口变化，契约专项不适用。复用既有 favicon；运行时不可用时的局部 HTML、系统字体、命名 Git token 消费及旧引擎浅色 fallback 归属合理，没有新增主题来源或过度抽象。文案没有暴露 Wasm/GC/EH 术语。Hallmark 最终结论 `0 critical · 0 major · 0 minor`，不将营销页结构套用于启动反馈。

独立 Chromium 复核 5190：390×320 浅色面板 `(0,0,390,320)`，内容高 424，可滚动；Tab 自动滚至 104，使官方链接完整可见且有 2 px 焦点轮廓，PageUp/PageDown 可滚动，Enter 打开正确官方页面。1440×900 深色亦通过。两者无横向溢出、动画、app/Wasm 请求或页面异常。5186 正常路径只显示 Logo，错误视图及兼容链接不可访问，首帧后层移除。已独立查看手机/桌面及低高度截图。旧 CSS 通过模拟核对，未冒充真实旧 Safari/Chrome 验收。

## 6. 测试与 QA

- [x] 6.1 运行受影响 Node/浏览器测试、Wasm 分发构建、JS 编译与资源处理，并核对分发资源。
- [x] 6.2 真实浏览器验证正常首帧、缺失特性、网络不误报、键盘及滚动，记录截图与诊断。
- [x] 6.3 执行 OpenSpec 严格校验、doctor、范围化术语、链接及 diff 检查。

构建与首轮实现验证于 2026-11-06、归档后受影响回归及规格和 diff 复核于 2026-11-07（Asia/Shanghai）完成，验证 HEAD/基线见第 1 阶段；代码验证对应下列指纹，不以旧构建冒充当前结果。

| 命令／检查 | 当前结果 |
| --- | --- |
| `cd compose/qa/web && npm run test:node` | 8/8 通过；含 Logo、Wasm/GC/EH/Tag/streaming 缺失、旧 CSS、未知 UA、无 JSTag、JS 无 GC、脚本顺序和既有 DOM/焦点回归 |
| `node compose/qa/web/scripts/start-compose-offline-demo.mjs` | Wasm production distribution 构建通过，61 tasks，2m32s；日志 `/tmp/finance-tracker-startup-compat-demo.log` |
| `cd compose && FT_COMPOSE_DEMO_MODE=true FT_API_ORIGIN= ./gradlew :webApp:compileKotlinJs :webApp:jsProcessResources --no-configuration-cache` | 通过，27 tasks，2s；日志 `/tmp/finance-tracker-startup-compat-js-build.log` |
| `cd compose/qa/web && npx playwright test --config /tmp/finance-tracker-startup-playwright.config.mjs` | 6/6 通过，7.2s；临时 config 只选择仓库 compose-startup.e2e.ts 与当前离线预览，无第二个构建 |
| 官方 freshness 检查 | `stale:false`；stamp `2026-11-06T15:50:09.280Z`，输入 `32c920fa7443dc8ad5ddede83f189f91d5db9433a2a44b5bb8e1c367fb191e1d`，产物 `cf5ef74bf6436f66ef2a8cc650bd481d726d29d86e315299eb3f629a9717df6a` |
| 分发与源逐字节比较 | HTML/CSS/favicon/Wasm startup.js 均一致；JS processed startup.js 与 JS 源一致，不依赖预览源文件兜底 |
| `openspec validate --all --strict`；`openspec doctor` | 归档前 27/27、归档后全部现行规格 26/26 通过；doctor 退出 0 |
| 本地文档链接、用户字符串/术语搜索、`git diff --check` | 通过；7 份相关文档无缺失本地链接；新文案为浏览器升级动作和版本，没有财务术语变化 |

Playwright Chromium，URL 5186；正常路径 1440×900 浅色和 390×844 深色/减少动态效果，依次延迟脚本与全部 Wasm，确认 Logo 保留，放行后层移除且账本可操作，实际打开筛选。缺失 Wasm 用 390×844 浅色，缺失 GC 用 1440×900 深色，缺失 legacy EH 用 390×320，确认不请求 app/Wasm、停止动画、版本可读、Tab 聚焦链接并自动滚动；网络失败独立注入，未显示兼容提示。成功与不兼容路径 console/page error、failed request、静态 HTTP 错误均为空，实际 Wasm MIME 为 application/wasm；网络故障用例保留预期请求失败。

截图 `/tmp/finance-tracker-startup-{desktop,phone-reduced-motion}.png`、`/tmp/finance-tracker-startup-ready-{desktop,phone-reduced-motion}.png`、`/tmp/finance-tracker-browser-unsupported-{wasm,gc,legacy-eh}.png`。截图只作 QA 证据，不提交。

未执行完整业务 E2E、真实旧浏览器、多浏览器、真实读屏或 Native 设备 QA；本次未改业务和 Native 代码。实际运行限 Chromium/Wasm，JS 目标做编译、资源与 bootstrap 回归。浏览器版本来自官方资料，不宣称实测这些最低版本。普通启动网络错误仍按原有边界处理，未新增通用错误恢复；不以兼容提示掩盖网络、MIME 或程序错误。

## 7. 发布

- [x] 7.1 核对 README、功能地图、主规格、词表、参考及配置/命令，记录用户决策反思。
- [x] 7.2 核对归档与 PR 准备、全部适用门禁、用户授权及回滚边界。

已更新 Compose README、共享和总览功能地图；主规格已同步两条新增需求，保留原有 39 条需求及场景；`openspec archive add-compose-web-startup-logo --yes` 已归档到 `openspec/changes/archive/2026-11-07-add-compose-web-startup-logo/`。核对根 README、字体/预览参考、词表及接口/命令；无新领域概念、API、配置、迁移或命令，其余无需修改。没有项目技能规则变化，不修改历史归档或技能。

反思：用户选择仅 Logo 呼吸、通过延迟预览确认，再要求明确的兼容提示和四浏览器/iPhone范围；这是本 Web 启动能力的产品决定，保留在对应规格，无需新增通用规范。回滚同一批 Web 分发即可，无数据迁移。用户已授权同仓库同分支 commit/push 及面向 main 的 PR；尚未授权合并或部署。新增提示视觉确认与全部适用技术门禁已完成，按授权同步主规格、归档后提交/推送当前分支并向 main 创建 PR。
