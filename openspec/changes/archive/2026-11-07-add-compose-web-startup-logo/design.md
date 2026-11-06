## Context

现有 `index.html` 只加载 `webApp.js`。Compose `1.12.1` 默认使用并清空 body，因此初始加载层需要与显式渲染容器分开。字体加载和认证恢复已有 Compose 加载态，本次交接到首个正常绘制的 Compose 帧即可，不等待业务数据。

## Goals / Non-Goals

参见 proposal。支持运行要求时，信息架构只有一个居中的现有 `favicon.svg`，没有标题、进度或可见状态文字；不兼容时替换为升级提示、四种主流浏览器的最低版本、iPhone/iPad 系统说明及官方兼容资料链接。没有需删除的既有信息。用户已确认 Logo 动效，并明确追加浏览器不兼容提示与浏览器范围。

## Decisions

- HTML 初始挂载启动层，CSS 直接运行动画，不依赖 Wasm、字体或额外动画库。Logo 使用现有品牌资产，绘制尺寸 56 px，约 1.8 秒一轮，在 0.94–1 倍和 0.5–1 透明度间平缓变化；减少动态效果时静止显示。
- 背景对应 Git token 的 `color.semantic.background`（浅色 `#F5F5F5`、深色 `#060607`），根据系统主题选择。HTML 仅消费这些既有颜色，不建立新主题来源。
- 两个 Web 入口共用 Web source set 的启动函数；显式 `compose-viewport` 容器与启动层互为 sibling。根绘制修饰符在 `drawContent()` 成功后仅通知一次，平台桥在下一 `requestAnimationFrame` 撤下启动层，避免把脚本下载、DOM 挂载或布局完成误作首绘。绘制失败不会通知完成，不捕获或掩盖原异常。
- 不添加人为最短展示、超时判失败、下载拦截或错误恢复框架。启动失败的诊断与刷新方式保持现有行为；Logo 的存在不表示成功，也不改变业务状态。
- 启动层不可聚焦、不抢焦点，以 `role=status` 和现有“加载中...”提供读屏名称；撤下后无遗留遮挡。
- 初始 HTML 由经典启动脚本决定是否加载应用；检测脚本使用 ES5 语法，在尚不支持现代 bundle 语法的浏览器上也能显示提示。Wasm/JS 目标各提供自己的同名 `startup.js`：Wasm 先做检测，JS 保持原有加载路径，不要求 GC。通过后以 `async=false` 按顺序加载既有 font-mirror、compose-focus 和 webApp 脚本，保持字体补丁在应用前安装；不兼容时不请求这些脚本或 Wasm。
- Wasm 检测验证小型、有效的 GC 与 legacy EH 模块，连同实际 loader 使用的 `instantiateStreaming`、`Tag` API 检查。以实际能力为准，版本号只作升级参考；不硬要求新 EH、`JSTag` 或原生 JS string builtins，当前 Kotlin wrapper 均有相应 fallback。不给普通下载失败、MIME 错误、文件损坏或内部程序错误套用兼容提示。
- 不兼容视图使用系统字体与既有背景、前景和链接 token 对应色，不依赖 Compose、Web 字体或外部 CDN。停止呼吸并隐藏加载状态，显示清晰标题和语义化版本列表；窄屏与低高度可滚动，官方资料链接可键盘聚焦。正常路径不闪现错误视图。
- 全屏层显式使用 `top/right/bottom/left`，避免旧浏览器不识别 `inset` 时提示落到首屏之外。对不支持 CSS 变量的旧引擎，保留既有浅色 Git token 的字面量作为可读 fallback；现代引擎仍消费对应主题变量，不增加新颜色事实源。

### 浏览器版本依据

[Kotlin 官方配置说明](https://kotlinlang.org/docs/wasm-configuration.html)确认 `wasmJs` 默认使用 GC 与 legacy EH；本仓库未启用新 EH，实际产物包含 `try/catch/catch_all/rethrow`，没有 `try_table/throw_ref`。界面使用以下最低版本，不把普通 Wasm MVP 的支持时间误作 Kotlin/Wasm 门槛。

| 浏览器 | 最低默认支持版本 | 首次稳定版发布日期与官方依据 |
| --- | --- | --- |
| Chrome | 119 | [2023-10-31](https://chromereleases.googleblog.com/2023/10/stable-channel-update-for-desktop_31.html) |
| Edge | 119 | [2023-11-02](https://learn.microsoft.com/en-us/deployedge/microsoft-edge-release-schedule) |
| Firefox | 120 | [2023-11-21，默认启用 WasmGC](https://www.firefox.com/en-US/firefox/120.0/releasenotes/) |
| Safari | 18.2 | [2024-12-09，新增 WasmGC](https://webkit.org/blog/16301/webkit-features-in-safari-18-2/) |

iPhone/iPad 上的 Safari 18.2 随 iOS/iPadOS 18.2 提供；仅更新浏览器 App 可能仍缺少支持。其他浏览器不建立品牌白名单；部分地区可使用替代引擎，因此不写“所有 iOS 浏览器必然相同”。界面不给发布日期增加阅读负担，只显示升级所需版本，日期与来源集中于此。

已通过 Chromium 阅读官方正文：Material [Motion physics system](https://m3.material.io/styles/motion/overview/how-it-works)（effects 的透明度不应过冲）、[Applying M3 Expressive](https://m3.material.io/foundations/usability/applying-m-3-expressive)（保持单一视觉焦点、平静布局）和 [Assistive technology](https://m3.material.io/foundations/overview/assistive-technology)（替代文字与键盘路径）。呼吸循环按用户明确需求使用 CSS，未引入 Compose 动画 API 或实验依赖。已调用 compose-material3 与 domain-glossary；本次没有新的财务领域概念，无需更新词表。现行 UI 规范 §6 明确 Pen 仅为历史参考，不生成 Pen。

## Risks / Trade-offs

- 根绘制通知依赖 Compose 绘制顺序 → 真实 Wasm 浏览器冒烟在延迟脚本及二进制时检查启动层，在实际首帧后检查移除与页面操作。
- 新容器可能影响布局、焦点或现有 shadow DOM 桥 → 容器保持满屏，复核现有 document/shadow root 搜索，执行受影响键盘及入口测试。
- 慢网仍需下载原有 Wasm → 本次仅改善等待反馈，不宣称改善加载时间。除明确浏览器不兼容外，其余启动失败提示与恢复流程不在本次范围。
- 兼容探针与编译要求可能偏离 → 用当前实际 Wasm 验证 legacy EH 和 fallback，并在浏览器回归分别模拟基础 Wasm、GC 和 EH 缺失；编译要求变化时同步核对。

## Migration Plan

无迁移；HTML、CSS、平台启动脚本与 Web bundle 作为同一分发更新。回滚本变更的入口和静态文件即可。用户已授权本分支提交、推送并向 `main` 创建 PR；先完成新增兼容提示的演示与适用门禁，再同步归档并创建 PR。未授权合并或部署。
