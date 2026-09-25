## Why

当前 Web 和 Expo Native 分别维护客户端，同一产品只有 5 项页面级功能覆盖两端，另外 5 项只有 Web 入口。把客户端统一迁移到 Compose Multiplatform，可以保留 Web 的完整功能事实源，同时让 Web、Android 和 iOS 共享页面、状态和业务流程。

## What Changes

- 使用 Kotlin Multiplatform Wizard 生成独立 Gradle 根目录 `compose/`，以 Compose UI 覆盖 Web、Android 和 iOS。
- 按 Web 当前 10 项页面级功能补齐三端入口与流程：认证、工作区入口、收支账本、收支流水、账单导入、工作区邀请、收支分类、投资持仓、投资事件和工作区管理。
- Web 保留现有工作区路径、账本子页面路径、邀请查询参数和浏览器前进/后退语义。
- 三端使用 Material 3 与现有 Cobalt 品牌色，跟随系统浅色或深色主题，并按 `compact <600`、`regular 600–1023`、`wide ≥1024` 的逻辑窗口宽度自适应。
- 把文件选择、登录令牌存储和浏览器 URL 等平台 API 收敛到平台适配边界；选择结果、确认时机、错误与服务端结果保持一致。
- 本地 demo 开发期间保留 React Web 和 Expo 源码；本次不做线上入口切换或旧端退场，后续发布/清理需另行决定。
- 不改造 Python/FastAPI 后端或现有 API。本次只完成三端 demo 开发、构建和本地启动；不修改 Render/云端托管配置，不做 CI 发布、正式入口切换、提交、推送或部署。
- 用户明确要求不创建 HTML 原型；改用可运行的 Compose 纵向切片验证 Web、Android、iOS 的实际渲染与关键平台能力。

## Capabilities

### New Capabilities

- `compose-multiplatform-client`: 统一客户端的三端功能覆盖、Compose UI 行为、平台 API 边界和本地 demo 验收；旧端在本次变更中保留。

### Modified Capabilities

- `cross-platform-presentation`: 将现有 Web/Native 首阶段合同调整为 Web、Android、iOS 三端完整的页面、状态、语义标识与响应式合同。

## Impact

- 新增 Wizard 生成的 Kotlin/Gradle 项目：共享 API/领域/状态/UI 的 `shared` 模块，以及 Android、iOS 和 Kotlin/Wasm Web 应用入口；现有 npm 根工程与客户端保持并存。
- 提供可重复的本地 Compose Web 构建与深链接预览启动入口，并记录 Android/iOS 本地运行方法；现有 Python/FastAPI 接口和数据库均不变。
- 迁移现有 TypeScript 客户端合同与共享包中的请求、精确金额、校验、状态和语义标识；React Web、Expo 与 TypeScript 共享包先保留。
- Compose Multiplatform Web 基于 Kotlin/Wasm 且仍处于 Beta。兼容门槛以官方支持的 WasmGC 浏览器版本为准：Chrome/Edge 119+、Safari 18.2+；Web 可访问性、浏览器历史、文件选择和本地 production preview 作为验收项。本次 Web 浏览器 QA 按用户要求只使用 Chrome。
