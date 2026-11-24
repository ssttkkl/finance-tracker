## Why

仓库已有 Kotlin 后端镜像与 Render 蓝图，但没有把后端、Compose Web 与 PostgreSQL 组合起来的本地 Docker 运行方式；Web 运行还依赖安全上下文（`SharedArrayBuffer`、OPFS），局域网明文 HTTP 无法启动账本。需要交付一个可重复启动、数据持久化、绑定真实 PostgreSQL、并通过局域网 HTTPS 访问的本地部署。

## What Changes

- 新增本地 Docker Compose 栈：PostgreSQL、Kotlin 后端、Compose Web 内部静态站、Caddy TLS 入口与同源 `/api` 反向代理。
- PostgreSQL 使用命名卷持久化；后端启动时按空库建立当前 Kotlin 基线 schema，非空库只校验版本。
- Web 使用 Compose Wasm 生产分发，由 nginx 提供静态文件、跨源隔离响应头、SPA 回退与 API 代理；后端与 Web 端口不发布到宿主机，只有 Caddy 暴露局域网端口。
- Caddy 使用内部 CA 为固定局域网 IP 签发 HTTPS 证书，地址为 `https://192.168.1.111:8443`；浏览器获得安全上下文与跨源隔离能力。
- 后端 `FT_WEB_ORIGIN` 在本机 HTTP 之外，继续接受 RFC1918 私有 IPv4 的 HTTP origin 作为兼容能力；本部署实际使用 HTTPS，公网 HTTP 仍拒绝。
- Web 构建脚本 `FT_API_ORIGIN` 同步接受私有 IPv4 HTTP origin，但本地部署编译 HTTPS origin。
- 增加 `.env.example`、根证书导出/安装说明、启停与手工备份命令；真实 `.env`、数据库卷、根证书和备份留在仓库外。
- 本次不引入公网暴露、正式 CA/域名、自动备份、SQLite 数据迁移或 Native 局域网 HTTP。

## Capabilities

### New Capabilities

- `shared/local-docker-deployment`: 本地 Docker Compose 部署拓扑、PostgreSQL 持久化、Web 与 API 的同源 HTTPS 访问、私有局域网 origin 边界、健康检查和启停/回滚合同。

### Modified Capabilities

<!-- 无既有能力的行为要求变化；origin 允许范围作为本地部署能力的一部分定义。 -->

## Impact

- 后端：`compose/backend/src/main/kotlin/com/finance/tracker/backend/WebOrigin.kt` 与对应单元测试。
- Web 构建：`compose/build-wasm-web.py` 与 `compose/tests/test_build_wasm_web.py`。
- 部署资产：根 `compose.yaml`、Web 镜像 Dockerfile、nginx 配置、Caddy 配置、`.env.example`、`.dockerignore`。
- 文档：`README.md`、`compose/README.md` 或新增本地部署说明，以及 OpenSpec 能力导航。
- 数据：新建本地 PostgreSQL 数据库；不迁移现有数据，不创建自动备份。
- 安全：局域网入口使用 HTTPS，避免会话令牌与财务数据明文传输；内部 CA 根证书只用于本机/可信设备。后端保留的私网 HTTP 兼容能力不作为默认部署入口。

## Acceptance

- `docker compose up -d --build` 后数据库、后端、Web 与 TLS 入口均健康，Web 可从 `https://192.168.1.111:8443` 打开。
- 浏览器 `isSecureContext`、`crossOriginIsolated` 为真且 `SharedArrayBuffer` 可用；首次访问可注册账号、创建工作区并完成一次现金流水写入；重启栈后数据仍在。
- 公网 HTTP origin 与私网非 IPv4 形式仍被后端拒绝；本机 HTTP 与 HTTPS 行为保持。
- 后端、Web 构建脚本相关测试通过，Compose Web 生产构建成功，真实浏览器完成登录与写入冒烟。
- 仓库不包含真实密码、数据库连接串或账本数据；部署说明包含停止、重建、回滚和手工 `pg_dump` 命令。

## Non-goals

- 公网访问、正式 CA、域名和证书自动化。
- 自动备份、备份保留策略和灾难恢复演练。
- 从旧 Python/Alembic 数据库或本地 SQLite 迁移数据。
- 修改 Render/Neon 线上部署。
- Android/iOS 局域网 HTTP 访问。
- 多副本、负载均衡和容器编排平台适配。

## Rollback

- 停止并删除容器但保留 PostgreSQL 命名卷，即可回退本次部署而不丢数据。
- 需要完全回退时删除本次容器与专用卷；删除前先导出备份。
- 代码回退到引入本变更前的提交；后端恢复为本机 HTTP 或 HTTPS origin 的原有范围，Web 构建恢复原 HTTPS-only 校验。Caddy 根证书与数据卷可单独删除。
