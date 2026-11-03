## Why

当前 UI 流程同时要求 Pen 设计稿和独立 HTML 交互原型，而两者仍需在 Compose 中重新实现。Compose 本地预览还依赖 FastAPI，无法在没有 API 后端的情况下用全 mock 数据直接评审真实页面。改为直接实现共享 Compose 页面，并用无 API 后端演示模式检查交互，可以减少重复产物并让评审覆盖最终实现。

## What Changes

- **BREAKING（工作流）**：移除未来 UI 变更对 `.pen` 设计稿、`prototype/index.html`、Pen 到 Compose 节点映射和实现前双产物确认的要求；保留信息架构、验收标准和重大 UI 变更的 Hallmark 设计方向。Compose 实现成为页面结构与样式的事实源，演示截图仅作为 QA 证据；重大 UI 变更在演示模式中评审，并在用户确认后完成。
- **BREAKING（Compose UI API）**：共享 Compose 页面直接使用 Material 3 控件；逐步移除按钮和输入框的 Finance 包装 API、`LabeledInput` 等间接包装及全部 Compose Unstyled 依赖。保留 `FinanceTheme`、颜色、字体、token 与非控件业务展示辅助函数。
- **BREAKING（QA 工程布局）**：将 Compose Web 启动器、预览服务、Playwright 配置和浏览器用例迁入 `compose/qa/web/`，建立独立 npm 项目。旧 React UI 的 `web/src` 与其自身工具保留为迁移参考，不纳入当前 Compose 流程。
- 删除仅服务于旧设计规范的专用设计文件、样式副本和 Figma 合并插件；维护中的文档、token 注册表与 OpenSpec 主规格改为以 Material 3 和 Compose 为当前设计合同。保留 OpenSpec 归档及旧 React UI。
- 新增仅供 Android、iOS 和 Web 开发/QA 构建使用的 Compose 演示模式，覆盖全部 Compose 页面和主要流程。统一通过 `FT_COMPOSE_DEMO_MODE` 构建环境变量和共享模块的 BuildKonfig 生成配置；演示模式使用固定合成账户、工作区、账本数据和内置合成账单，常用变更操作仅修改进程内数据，重启后恢复种子数据。
- Compose 的 `FT_API_ORIGIN` 也由共享模块的 BuildKonfig 从构建环境变量生成，供 Android、iOS 和 Web 共用；各构建入口保留现有空值回退行为。
- 演示模式不创建生产 Ktor 客户端，不读取真实凭据，不请求 API 服务器，也不对正式构建开放；字体等静态资源可以从配置的 CDN 加载。
- 更新 Compose 本地演示命令与设计系统、跨端展示和仓库工作流文档；在功能地图中将旧 React UI 标记为历史参考并维护 Compose Web 工具的新路径。
- 移除 Pen CLI、Pen 变量副本生成和 Pen 驱动组件注册表输入；保留 Git token 到 Compose token 的生成与校验。

## Capabilities

### New Capabilities

- `shared/compose-demo-mode`: Compose 的跨端、无 API 后端、合成数据演示模式及其开发/QA 边界。

### Modified Capabilities

- `shared/cross-platform-presentation`: 以 Compose 实现为视觉事实源，以演示模式和截图证据替代 Pen/HTML 原型与 Pen 映射；保留跨端行为、状态和响应式合同。
- `shared/design-system`: 将 Git token、FinanceTheme 和 Material 3 直接控件作为持续维护依据，移除旧设计资源、Finance 按钮/输入框包装层及 Unstyled 组件依赖。

## Impact

- 文档与规格：`AGENTS.md`、`docs/shared/ui-design-rules.md`、`docs/feature-map.md`、`compose/README.md`、设计工作区说明、术语表及对应 OpenSpec 主规格。
- Compose：共享应用数据图、认证和财务仓库装配、内存演示数据和导入流程；Android、iOS、Compose Web 的开发/QA 入口和直接 Material 3 控件调用点。
- QA 工具：从旧 `web/` 目录迁出的 Compose Web 启动、预览与 Playwright 资产，以及独立 `compose/qa/web` npm 清单。
- 验证：共享层演示仓库/应用合同、Android/iOS/Web 构建入口、无后端 Compose Web QA 和三类窗口尺寸的演示截图。
- 不新增生产 API、数据库 schema、凭据或第三方依赖；保留 `FinanceTheme` 和 Git token，不改旧 `web/src`，不改 OpenSpec 归档。

## 验收标准

- Android 和 iOS 只在 Debug 构建进入演示数据图；Web 只有显式生成的 offline demo 构建进入该图，常规 Web 构建保持正式数据图。
- 三个平台的演示启动均使用固定用户和普通种子数据；新增、编辑、分类、工作区和导入结果只在当前进程内变化，重启后恢复种子数据。
- 演示数据图不创建 Ktor/API 客户端、不读真实凭据、不访问系统文件、不请求 API 服务器；允许字体等静态资源请求 CDN。
- 账单导入在既有 Compose 流程中使用内置演示账单，完成识别、映射、预览、关联确认和提交。
- 所有 Compose 主目的地与现有导航共用同一套 Compose 页面；Compose 实现维护视觉结果，截图只作 QA 证据。
- Compose 按钮与输入控件在页面调用点直接使用 Material 3；Finance 按钮/输入框包装和 Compose Unstyled 依赖已删除，颜色、字体及语义 token 仍来自 `FinanceTheme` 与 Git token。
- Native 紧凑全屏二级页面尊重系统安全区；Compose Web 主导航可用键盘访问，并显示键盘焦点状态。
- Compose Web 启动器、预览服务、Playwright 配置、测试和 Node 检查均位于 `compose/qa/web/`；旧 `web/src` 保持不变并标记为历史参考。
- 维护中的文档与设计注册表不再包含旧 UI 规范名称；专用旧设计文件和 Figma 插件已删除，归档记录保留。
- 后续 UI 变更保留信息架构、验收与重大变更的 Hallmark 方向，移除 Pen、HTML 原型及实现前原型审批；重大 UI 在演示评审后由用户确认再标记完成。
