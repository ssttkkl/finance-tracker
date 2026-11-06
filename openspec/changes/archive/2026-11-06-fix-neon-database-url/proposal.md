## Why

Render 部署已完成 Docker 构建，启动因现有 Neon URL 的 `sslmode`、`channel_binding` 被 Kotlin 全部拒绝而返回 `storage.config`。修复连接配置迁移遗漏，使明确指定的连接安全要求传递到现有 PostgreSQL 驱动。

## What Changes

- 接受 PostgreSQL URL 中已确认的 TLS 与通道绑定参数，保留各自取值和缺省行为。
- 严格拒绝未知、重复、空值或非法参数，凭据不进入 JDBC URL 或错误日志。
- 保留 URL 别名、环境变量优先级、SQLite、schema、权限和事务合同。
- 补启动解析、驱动实收参数、真实本地 PostgreSQL TLS 与 SQLite、完整后端回归；更新部署参考。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `shared/kotlin-backend-parity`：明确生产数据库 URL 中连接安全参数的支持及拒绝边界。

## Impact

按澄清后的实际影响评为 C 类：根因是启动配置拒绝并丢弃已确认的连接安全参数，修复局限于连接串解析与驱动参数映射，可由失败回归及实际连接验证证明。使用隔离分支 `fix-neon-database-url`，基线 `2950f7908ac944f649db3ad69ef43a1b751edbee`。不新增依赖，不改变数据模型、schema、财务或 UI，不接触 Neon 业务库，不修改 Render 配置或触发发布。连接安全风险仍执行真实 TLS／通道绑定及双后端验证，并保留完整后端回归。回退本次代码即可；本次没有数据迁移。
