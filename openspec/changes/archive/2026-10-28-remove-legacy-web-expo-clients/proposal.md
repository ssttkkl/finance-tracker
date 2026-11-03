## Why

Compose Multiplatform 已是当前 Web、Android 和 iOS 客户端。仓库仍保留不进入默认开发与 CI 的 React Web、Expo 客户端及只供它们使用的 TypeScript 包，相关规格和文档也仍把这些旧实现写成当前资源，容易让人误用或按错误入口维护。

## What Changes

- **BREAKING**：删除根目录下的 React Web、Expo 客户端及仅由它们使用的 TypeScript shared packages。
- 删除 Expo 专属的 Native 登录调试地址规格，移除现行规格中要求保留旧客户端行为的条款；保留 Compose 的产品行为合同。
- 删除只用于旧 React 静态站点的根目录 Render 配置；更新测试、功能地图、运行说明、词表、迁移索引及仓库约定，使其只描述当前 Compose 客户端；归档变更原样保留。
- 保留 Compose Web、Android、iOS 与 Python/FastAPI 后端，不改运行时 API、财务规则或持久化行为。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `shared/cross-platform-presentation`：以 Compose Web、Android 和 iOS 作为当前跨端实现，删除旧 React 源码保留要求及过时的 Expo 页面缺口描述。
- `workspace/workspace-entry`：移除已删除 React 与 Expo 客户端必须保持布局不变的要求，保留 Compose 工作区入口合同。
- `cash-ledger/cash-ledger-browser`：移除 Compose 详情面板不得影响旧 React 抽屉的例外说明，保留当前 Compose 面板合同。
- `authentication/mobile-login-api-origin`：退役 Expo 专属登录地址覆盖要求；Compose 继续使用既有构建配置。

## Impact

影响 `web/`、`mobile/`、`packages/`、根 `render.yaml`、`tests/test_ci_workflow_contracts.py`、当前运行与架构文档、功能地图及相关主规格。没有数据迁移或 API 兼容变化；回滚可通过 Git 恢复被删除文件和规格。旧客户端不再有仓库内启动、构建、测试、签名或静态站点配置入口。
