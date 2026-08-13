## Context

现有账本所有财务表已以 `workspace_id` 隔离，但 Web 运行时通过 `FT_WORKSPACE_ID` 固定绑定一个工作区。此变更仅改变 Web 请求的身份和授权边界；CLI 保持显式工作区模式。

## Goals / Non-Goals

**Goals:**
- 为多用户工作区隔离提供可审计、失败关闭的身份与成员授权。
- 把既有 `default` 数据仅归属到明确指定的首次管理员。

**Non-Goals:**
- 不接第三方身份提供方、邮件发送、密码找回或工作区删除。

## Decisions

- 使用数据库持久化的随机会话 token，Cookie 中只携带原始 token，数据库保存 SHA-256 摘要。会话撤销立即生效，泄露数据库不暴露可用 Cookie。
- 密码使用 `argon2-cffi` 哈希；登录统一返回失败，避免用户枚举。
- 当前工作区保存在会话的 `active_workspace_id`，切换时必须验证该用户成员关系。既有 application services 每次请求按该值重新装配，保证 repository 继续绑定单一工作区。
- 角色内部值采用 `admin`、`editor`、`viewer`，面向使用者分别展示为「管理员」、「可编辑」、「仅可查看」。可有多个 `admin`；第一版不允许通过 API 降级或移除最后一个 admin，防止工作区失管。
- 邀请表仅保存 SHA-256 token 摘要，链接 token 使用 URL-safe 随机值；有效期 7 天、只可接受一次。邀请角色仅限 `editor` 或 `viewer`，admin 只能由其他 admin 升级成员角色。
- 新增 React 的认证壳与工作区选择；账本 UI 复用既有视觉 token。用户已确认登录、接受邀请、创建工作区和成员管理四个独立原型页面；原型入口为 `prototype/index.html`，实现不将它们堆叠为一个页面。邀请页只展示创建时冻结的角色，不提供角色选择。
- 性能门禁通过 FastAPI HTTP 边界运行固定、去标识化样本。注册和登录包含 Argon2 哈希/校验，单独采用较宽 p95 预算；已登录的会话、工作区、邀请和成员接口采用较紧的 p95 预算。每项操作有预热和多次样本，输出 backend、样本和 p95，SQLite 默认执行，只有显式 `FT_TEST_POSTGRES_URL` 才运行 PostgreSQL。

## Risks / Trade-offs

- [不提供邮箱验证或找回密码] → 第一版限于可信测试用户；在生产扩大用户前必须补齐。
- [跨服务 Cookie] → API 与前端使用 HTTPS，CORS 精确白名单并携带 credentials；会话 Cookie 使用 `SameSite=Lax`。
- [SQLite/PostgreSQL 迁移差异] → 迁移和授权矩阵在两后端运行；未配置专用 PostgreSQL 测试库时记录为未完成。

## Migration Plan

1. 运行 Alembic 增量迁移，不修改任何现有财务行或 `workspace_id`。
2. 部署应用；指定邮箱首次注册时在单一事务内取得 `default` 的 admin 关系。
3. 设置 HTTPS 前端来源和安全 Cookie 配置后开放 Web 服务。
4. 回滚必须同时停止 Web 入口；不能将旧匿名 API 重新公开。
