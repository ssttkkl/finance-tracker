## Why

账本已按工作区保存数据，但运行时仅以环境变量选择单一工作区，也没有用户身份和成员授权，无法安全地让团队共同使用。需要把身份、会话和工作区成员关系置于所有账本 API 的请求边界。

## What Changes

- 新增邮箱密码注册、登录、登出和受保护会话。
- 新增用户—工作区成员关系；一个工作区支持多个 `admin`、`editor` 和 `viewer`。
- 用户可创建工作区；`admin` 可生成指定角色的一次性限时邀请链接，已登录用户可接受邀请。
- **BREAKING**：账本 Web API 改为从已登录用户选择的工作区解析数据，未登录、非成员或角色不足的请求不得读取或写入账本。
- 迁移已有工作区数据：`admin@ssttkkl.fun` 注册后成为已有 `default` 工作区的 `admin`。
- 新增登录、注册、工作区选择和成员管理的 Web 界面。
- 为认证、工作区、邀请和成员 HTTP 接口新增固定负载的 p95 性能门禁，并在 SQLite 与显式配置的 PostgreSQL 测试库运行相同矩阵。

非目标：OAuth、邮箱验证、找回密码、邮件发送、组织/企业 SSO、工作区删除，以及细粒度账本级权限。

## Capabilities

### New Capabilities

- `user-workspace-access`: 邮箱密码身份、会话、成员角色和一次性邀请链接。

### Modified Capabilities

- `cash-ledger-browser`: 收支账本浏览和管理 API 按登录用户的工作区成员权限授权。

## Impact

- 数据库新增用户、成员、会话、邀请表及迁移；现有账本事实和 `workspace_id` 不移动。
- 修改 FastAPI 应用装配、路由和服务构造，新增认证授权边界；修改 React 入口和 API 客户端。
- 生产环境需要安全的会话配置和公开 API/前端 HTTPS origin；已有 CLI 保持显式 `FT_WORKSPACE_ID` 工作方式。
- 回滚前必须停止公开 Web 服务或恢复到访问控制版本，不能将已有财务 API 回退为匿名公开状态。
