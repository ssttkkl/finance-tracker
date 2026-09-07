## Why

当前项目的业务能力主要由 Python 后端和 React/Vite Web 提供，但用户仍需要通过宽泛的 `ft` CLI 启动服务、记账、导入和处理关系。CLI 同时承担产品入口、运维入口和兼容层，阻碍 Android/iOS 客户端复用稳定的应用边界，也让同一业务能力出现多套输入、权限和错误处理路径。

现在采用 Expo 构建 Android/iOS 客户端，可以先保留成熟的 Web 端，再把 API 契约、业务状态和纯逻辑沉淀为共享 TypeScript 包。完成替代入口后删除 CLI，应用边界将收敛到显式的 HTTP API、Web 客户端和 Expo 客户端。

## What Changes

- 新增 Expo Android/iOS 客户端，首个纵向切片覆盖登录、工作区、收支账本浏览、快速记账、凭证入口、导入和关系审查。
- 保留现有 React/Vite Web；共享 API 契约、请求客户端、纯业务状态机、格式化函数和设计 token，Web 与 Native 使用各自的 UI、路由和平台适配器。
- 建立 `mobile/` Expo 应用和 `packages/` 共享包边界；Native 使用安全的 TokenStore 和系统文件选择器，Web 保留浏览器适配。
- 将客户端写操作定义为 online-first，后端继续作为金额、余额、权限、来源和审计的唯一事实源；本次不引入离线写入或冲突合并。
- 用显式的后端启动命令和项目开发脚本替代 `ft web`，保留 FastAPI 应用工厂。
- **BREAKING**：删除 `ft` 项目脚本、CLI 分发模块、CLI 专属测试和 CLI 文档入口，不保留兼容别名或隐藏命令。
- 将 CLI 原有的用户侧能力收敛到现有或新增 API、Web 和 Expo 流程；投影重建等运维动作改为显式后端脚本或 API，不作为客户端功能。
- 更新开发、测试和迁移文档，明确 Web、Expo 和后端的启动方式、环境变量、API 地址和能力边界。
- 增加 GitHub Actions Native CI：在 Pull Request、`refactor/web` 推送和手动触发时执行共享层/客户端质量检查，并上传未签名的 Android Debug APK 与 iOS Simulator `.app` 压缩包；商店签名和发布流水线留待后续变更。

## Capabilities

### New Capabilities

- `expo-client`: 定义 Android/iOS Expo 客户端、共享客户端层、平台适配、首个移动端纵向切片和 online-first 写入边界。
- `cli-free-runtime`: 定义 CLI 删除后的显式服务启动入口、用户侧能力替代要求和仓库中不得残留的 CLI 公共面。

### Modified Capabilities

- 无。现有账本、导入、关系、投资、工作区和数据库主规格的业务行为保持不变；本变更只为它们增加跨平台客户端承载和新的运行入口合同。

## Impact

- **客户端代码**：新增 `mobile/` Expo 应用和 `packages/` 共享包；重构 `web/src` 的 API、类型和纯逻辑依赖，保留现有 DOM/CSS 页面。
- **服务端代码**：保留 FastAPI、Application Service、Domain、持久化适配器和 `/api/v1` 契约；必要时补齐客户端已有但 CLI 替代流程缺失的 HTTP 边界。
- **Python 配置与工具**：移除 `pyproject.toml` 的 `ft` script 和 CLI 模块；增加显式 Uvicorn/开发脚本，清理 CLI 专属依赖、测试和文档引用。
- **认证与文件**：增加 Web/Native TokenStore、Native 安全存储和跨平台文件来源适配；账单密码只存在本次导入会话，不进入持久化存储。
- **数据与兼容**：不修改既有账本数据、金额精度、关系语义、数据库 schema 或 PostgreSQL/SQLite 运行契约；所有写入继续经同一 Application Service，保持工作区隔离、幂等和来源溯源。
- **迁移与回滚**：迁移期间保留现有 Web 和后端 API作为稳定回退面；先完成替代启动入口及首个客户端链路并通过验证，再删除 CLI。若 Expo 客户端未达到发布条件，可单独停止 Native 发布而不回滚账本数据；CLI 删除后的本地服务通过显式 Uvicorn 命令回退。
- **CI 与发布边界**：新增工作流只构建可供测试下载的未签名 Native 产物，不读取签名证书、商店凭据或生产密钥；若工作流失败，Web/API 回退面和本地开发入口不受影响。
