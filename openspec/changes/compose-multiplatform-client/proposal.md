## Why

当前 Web 和 Expo Native 分别维护客户端，同一产品只有 5 项页面级功能覆盖两端，另外 5 项只有 Web 入口。把客户端统一迁移到 Compose Multiplatform，可以保留 Web 的完整功能事实源，同时让 Web、Android 和 iOS 共享页面、状态和业务流程。

## What Changes

- 使用 Kotlin Multiplatform Wizard 生成独立 Gradle 根目录 `compose/`，以 Compose UI 覆盖 Web、Android 和 iOS。
- 按 Web 当前 10 项页面级功能补齐三端入口与流程：认证、工作区入口、收支账本、收支流水、账单导入、工作区邀请、收支分类、投资持仓、投资事件和工作区管理。
- Web 保留现有工作区路径、账本子页面路径、邀请查询参数和浏览器前进/后退语义。
- 三端使用 Material 3 与现有 Cobalt 品牌色，跟随系统浅色或深色主题，并按 `compact <600`、`regular 600–1023`、`wide ≥1024` 的逻辑窗口宽度自适应。
- 将约 7,700 行共享客户端按全局 layer-first 职责重构到 `app/`、`core/`、`data/`、`domain/` 和 `presentation/`；以手动装配连接依赖，以 Lifecycle ViewModel + `StateFlow` 管理页面状态，不增加 DI 框架。
- `domain` 使用不依赖 Compose、Ktor 或 JSON 的领域模型与用例；`data` 独立维护 API DTO、Repository 实现及 DTO/领域模型映射。
- 所有 Compose 用户可见文案改用 Compose Resources，首期支持简体中文和英语；自动跟随系统或浏览器语言，无法匹配时回退到简体中文。
- 认证及其他错误只向用户展示可恢复的友好提示，意外异常统一显示未知错误。错误详情同时写入开发者日志和本地持久化日志；日志仅包含时间、功能操作、错误码/状态和异常类型，不记录邮箱、密码、登录令牌、金额或账单内容，最多保存 30 天且总量不超过 1 MB，先达到者触发清理，不增加日志查看或导出界面。
- 修复本地 Compose demo 预览服务器对 API 写请求返回 405 的问题，使代理按原样转发认证与其他 API 请求；不改变 FastAPI 或既有 API 合同。
- 将 Compose 设为仓库唯一主动开发、构建、测试和设计实现主线；立即移除 Expo/React 的根 workspace、默认依赖、启动命令、CI 入口和功能地图主动实现标记。
- 保留 `web/`、`mobile/` 及 TypeScript 共享包源码和各自 manifest 作为迁移参考，但不再由根工作流安装、构建或验证；源码删除另行决策。
- 引入 pen.dev 可视化设计工作区：`ui-spec/` 保存语义合同与 token 事实源，`design/` 保存官方工具生成的 `.pen` 设计文件；首个样板为登录页。
- 通过 pen.dev Desktop/IDE MCP 支持本地设计协作，通过固定版本 CLI 在 CI 中做无头校验和导出；不把账号、CLI key 或模型 key 写入仓库。
- 不改造 Python/FastAPI 后端或现有 API，不修改 Render/云端托管配置，不进行线上发布、入口切换或部署；仅完成 demo 开发、跨端验证和本地启动。
- 用户明确要求不创建 HTML 原型；改用可运行的 Compose 纵向切片验证 Web、Android、iOS 的实际渲染与关键平台能力。

## Capabilities

### New Capabilities

- `compose-multiplatform-client`: 统一客户端的三端功能覆盖、Compose UI 行为、平台 API 边界和本地 demo 验收；旧端在本次变更中保留。

### Modified Capabilities

- `cross-platform-presentation`: 将现有 Web/Native 首阶段合同调整为 Web、Android、iOS 三端完整的页面、状态、语义标识与响应式合同。

## Impact

- 重构 Wizard 生成的 Kotlin/Gradle 项目：`shared` 中约 7,700 行 `commonMain` 代码按 app、core、data、domain、presentation 职责拆分，Android、iOS 和 Kotlin/Wasm Web 共用页面状态与业务流程。
- 提供可重复的本地 Compose Web 构建与深链接预览启动入口，并记录 Android/iOS 本地运行方法；现有 Python/FastAPI 接口和数据库均不变。
- 为 Compose 增加中英文 Compose Resources、跨平台错误分类和有界脱敏日志；本地 Node 预览服务需代理 API 写请求。
- 根 Node 工作流不再安装或执行 Expo/React；旧端源码仍保留在仓库的主动依赖图之外，便于迁移查阅。
- `ui-spec/tokens/*.json` 是跨平台 token 唯一事实源，由确定性生成器产出 Compose Kotlin token 和 pen.dev 变量同步输入；`.pen` 通过官方 CLI/MCP 操作，不直接编辑内部节点 JSON。
- 设计合同和 `.pen` 只约束视觉与交互表达，Compose `commonMain` 仍是实际运行实现；登录页设计覆盖登录/注册切换、正常、校验错误、服务错误、提交中/禁用及 compact/regular/wide、light/dark 状态。
- Compose Multiplatform Web 基于 Kotlin/Wasm 且仍处于 Beta。兼容门槛以官方支持的 WasmGC 浏览器版本为准：Chrome/Edge 119+、Safari 18.2+；Web 可访问性、浏览器历史、文件选择和本地 production preview 作为验收项。本次 Web 浏览器 QA 按用户要求只使用 Chrome。
- 完成功能后按 Web（Chrome）、Android、iOS 顺序验证；不使用 Safari。金额、数量、价格、汇率的精确值语义及现有服务端权限和持久化行为保持不变。
