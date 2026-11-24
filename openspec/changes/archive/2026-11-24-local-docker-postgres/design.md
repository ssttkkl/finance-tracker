## Context

当前仓库只有根 `Dockerfile` 构建 Kotlin 后端 `installDist`，以及 Render 的 API + Static Site 蓝图；没有把后端、Web 与本地 PostgreSQL 组合起来的编排。Compose Web 的生产分发由 `compose/webApp` 的 Wasm 构建产出，需要跨源隔离响应头；API 地址通过构建期 `FT_API_ORIGIN` 编译进 Web。后端 `WebOrigin.kt` 只接受本机 HTTP 或 HTTPS，`compose/build-wasm-web.py` 也只接受 HTTPS API origin。真实 Chrome 探测确认局域网明文 HTTP 不是安全上下文，`SharedArrayBuffer` 不可用，应用启动检查会拒绝运行；因此本部署必须提供 HTTPS。

仓库实际 schema 基线常量是 `20261104_54`，而 `README.md` 与 `shared/runtime-database` 仍写着 `20261030_53`。本变更按代码常量与后端 README 实现，不修改既有数据库规格的版本措辞；该不一致作为已知文档风险记录。

## Goals / Non-Goals

**Goals：**

- 一条命令启动 PostgreSQL、Kotlin 后端、Compose Web 与 Caddy TLS 入口，只有 Caddy 暴露宿主机端口。
- PostgreSQL 与 Caddy CA 使用命名卷持久化，空库由后端建立基线，重启不丢数据。
- 固定局域网 origin `https://192.168.1.111:8443` 提供安全上下文，公网明文 HTTP 仍拒绝。
- 部署凭据、数据库卷和备份全部留在仓库外。
- 提供可执行的启停、重建、日志、手工备份和回滚命令。

**Non-Goals：**

- 公网暴露、正式 CA、域名、证书自动化与反向代理鉴权。
- 自动备份、定时任务和恢复演练。
- 旧 Python/Alembic 或 SQLite 数据迁移。
- 修改 Render/Neon 线上蓝图或触发线上部署。
- Android/iOS 局域网 HTTP；本次只改 Web 与后端。
- 多副本、外部对象存储和容器编排平台。

## Decisions

### D1：四服务单入口拓扑

编排包含 `db`、`api`、`web`、`caddy` 四个服务。`db`、`api`、`web` 不发布宿主机端口，`caddy` 只发布 `8443:8443`。浏览器访问 `https://192.168.1.111:8443`，Caddy 终止 TLS 后转发到 `web:80`，`/api` 再由 nginx 同源转发到 `api:8080`。

选择单入口而不是直接暴露 `api:8080`，因为同源请求不依赖 CORS，Web 构建的 `FT_API_ORIGIN` 与页面 origin 一致，且减少局域网攻击面。替代方案是双端口 + CORS；它需要浏览器直连后端，并让 origin 配置更容易漂移，故不采用。选择 Caddy 而不是给 nginx 手工管理证书，因为 Caddy 内部 CA 会自动为固定 IP 签发和续期证书，减少证书文件管理。

### D2：Web 镜像复用镜像内 JDK，生产分发包由 nginx 提供

Web 构建阶段使用与后端相同的 `eclipse-temurin:21-jdk`，安装 `python3` 与 `libatomic1` 后调用仓库正式构建脚本 `compose/build-wasm-web.sh`；脚本复用镜像自带 JDK 21，不再联网下载第二套 JDK。构建参数 `FT_API_ORIGIN=https://192.168.1.111:8443`，脚本强制 `FT_COMPOSE_DEMO_MODE=false` 并校验 origin。运行阶段使用 `nginx:1.27-alpine`，复制 Wasm 分发包与处理后的资源。

nginx 提供：

- `Cross-Origin-Opener-Policy: same-origin` 与 `Cross-Origin-Embedder-Policy: require-corp`，覆盖静态与代理响应。
- `.wasm` 为 `application/wasm`，`.mjs` 为 JavaScript MIME。
- 未知前端路径回退 `index.html`。
- `/api/` 反代到 `api:8080`，`client_max_body_size 256m`，关闭代理缓冲以兼容长请求，读写超时 300 秒。
- `cash-import-worker/assets/<sha>/` 长缓存，`client.mjs` 与入口不缓存。

同时更新 `compose/build-wasm-web.py` 的 origin 校验，使仓库支持的 Web 构建入口接受私有 IPv4 HTTP；脚本新增“复用已有 Java 21”路径，使容器构建复用镜像 JDK，避免重复下载。

### D3：后端接受 RFC1918 私有 IPv4 HTTP，公网仍 HTTPS-only

在 `WebOrigin.kt` 增加私有 IPv4 判断：仅当 scheme 为 `http`、host 是 `10.0.0.0/8`、`172.16.0.0/12` 或 `192.168.0.0/16` 的 IPv4 字面量、且端口显式存在时接受。保留本机 `127.0.0.1`/`localhost` HTTP 和任意 HTTPS；继续拒绝用户信息、路径、查询参数、片段、公网 HTTP 和非 IPv4 主机名。

选择“无条件接受私有 IPv4 HTTP”而不是增加环境开关，因为用户明确选择 Q9=B；安全性通过私网范围、固定 origin 和最小端口暴露约束。替代方案是显式开关；它更安全但多一层配置，未获用户选择。

CORS 继续以 `FT_WEB_ORIGIN` 精确匹配；本部署同源代理不触发跨源预检，但直接访问 API 时仍只允许该 origin。本部署使用 HTTPS，私有 IPv4 HTTP 仅作为后端兼容能力保留，不作为 Web 入口。

### D4：PostgreSQL 17 + 命名卷，后端负责基线

使用 `postgres:17-alpine`，`pg_isready` 健康检查，数据卷 `pgdata`。后端连接串 `postgresql://finance_tracker:<password>@db:5432/finance_tracker`。密码由 48 位十六进制随机值生成，避免 URL 编码问题。

`api` 用 `depends_on: db: condition: service_healthy`；`web` 用 `depends_on: api: condition: service_healthy`；`caddy` 用 `depends_on: web: condition: service_healthy`。不使用单独迁移容器：当前 Kotlin 后端会在空库启动时建立基线，并对非空库做版本门禁。

选择 PostgreSQL 17 而不是 16，因为当前官方镜像稳定支持 arm64，且与线上 Neon 的大版本接近；JDBC 驱动保持现有依赖，不新增包。

### D5：配置与凭据边界

仓库提交 `.env.example`，只含占位值：

- `FT_WEB_HOST=192.168.1.111`
- `FT_WEB_PORT=8443`
- `FT_POSTGRES_PASSWORD=change-me`
- `FT_POSTGRES_DB=finance_tracker`
- `FT_POSTGRES_USER=finance_tracker`

真实 `.env` 由部署命令生成并写入随机密码，已被 `.gitignore` 的 `.env` 规则忽略；`.env.example` 用 `!/.env.example` 保留在仓库中。编排从同一组 `FT_WEB_HOST`/`FT_WEB_PORT` 派生后端 `FT_WEB_ORIGIN` 与 Web 构建参数，避免两处漂移。

``.dockerignore` 增加 `.env`、`.env.*`、`.worktrees/`、数据库文件、真实账单夹具和本地构建产物，避免密码、账本或宿主机缓存进入构建上下文。`.env.example` 不需要进入镜像。

### D6：导入暂存使用内存后端

本地单实例设置 `FT_IMPORT_STAGING_BACKEND=memory` 与 `FT_IMPORT_STAGING_ALLOW_MEMORY=1`。进程重启会丢失进行中的导入会话，但账本数据不受影响；当前导入主流程已转为客户端本地文件处理，服务端暂存仅用于维护路径。R2 凭据不进入本地部署。

### D7：备份与回滚

不增加自动备份服务（Q12=C）。部署文档提供 `pg_dump` 手工命令，输出到仓库外目录；命令通过 `set -a; . ./.env` 读取容器环境，密码不写入命令行或 shell 历史。`docker compose down` 保留卷；`down -v` 明确标注为不可恢复。代码回退只需停止栈并恢复旧提交，卷可保留。

### D8：验证策略

- 先补失败测试：`WebOriginTest` 覆盖私有 IPv4 接受、公网 HTTP 拒绝和非法端口；`compose/tests/test_build_wasm_web.py` 覆盖构建脚本 origin。
- 运行 `:backend:test`、`:backend:ktlintCheck`、`:backend:detekt`、`compose/tests/test_build_wasm_web.py`。
- 用真实 PostgreSQL `_test` 库运行 schema 双后端用例，库名以 `_test` 结尾。
- 构建并启动完整栈，检查四个健康状态、`/health`、登录页、注册/登录、创建工作区、写入现金流水、重启后读取。
- 使用真实 Chromium 从 `https://192.168.1.111:8443` 走登录主流程，确认 `isSecureContext`、`crossOriginIsolated` 为真且 `SharedArrayBuffer` 可用，记录视口、控制台、网络错误和截图路径。
- 用 `curl --cacert` 验证 Caddy 根证书链；验证未信任证书的客户端不会降级到 HTTP。
- 从宿主机验证公网 HTTP origin 被拒绝；局域网第二设备若不可用，记录为未验证项。

## Risks / Trade-offs

- **本地 CA 根证书需要安装到每台设备** → 导出根证书并在 macOS/iOS 明确说明信任步骤；未安装时显示证书错误，不降级到 HTTP。
- **固定 IP 变化导致 origin 与证书/构建不一致** → 文档要求路由器 DHCP 保留；IP 或端口变化时更新 `FT_WEB_HOST`/`FT_WEB_PORT` 并重建 `api`、`web`、`caddy`；Caddy 根证书不变，无需重装。
- **Caddy 内部证书短期轮换** → Caddy 自动续期；不要手工复制 leaf 证书，只分发根证书。
- **明文 HTTP 仍可作为后端兼容 origin** → 不作为 Web 入口发布；Compose 只暴露 Caddy 的 TLS 端口，文档说明其仅用于开发兼容。
- **没有自动备份** → 文档提供手工 `pg_dump`；建议每次升级或删除卷前执行；残余风险由用户接受。
- **Docker Desktop 端口转发或 macOS 防火墙阻止局域网访问** → 部署后用宿主机 LAN IP 和 `curl` 验证；必要时在系统设置允许 Docker 入站。
- **Gradle 首次构建下载依赖较慢或受网络影响** → 使用 Gradle 缓存挂载；失败时保留日志并重试，不改变业务代码。Web 构建镜像还需安装 `libatomic1`，否则 Gradle 下载的 Node 在 linux-arm64 上无法启动。
- **schema 版本文档不一致（53/54）** → 以代码常量 `20261104_54` 为准；本变更不擅自改既有规格，部署文档标注实际值。
- **Web 浏览器兼容性（Safari 18.2+ / 现代 Chromium）** → 用 Chromium 做冒烟，并在文档列出最低版本；旧浏览器由应用启动页拦截。
- **单实例内存暂存** → 重启会丢失进行中的导入会话；文档说明，账本事实不受影响。

## Migration Plan

1. 在仓库外生成 `.env`，填入随机 PostgreSQL 密码和固定 `FT_WEB_HOST=192.168.1.111`、`FT_WEB_PORT=8443`。
2. `docker compose up -d --build`，等待 `db`、`api`、`web`、`caddy` 健康。
3. 导出 Caddy 根证书并安装到访问设备；浏览器打开固定地址，确认安全上下文，注册首个账号并创建 workspace；完成一次现金流水写入。
4. 重启栈，确认数据、根证书和账本仍在；执行一次 `pg_dump` 演练。
5. 回滚：`docker compose down` 停止容器；恢复旧提交后重新构建；保留数据卷即可保留数据。只有确认不再需要数据时才执行 `down -v`。

## Open Questions

无。固定 IP 的 DHCP 保留由维护者在路由器侧完成，不改变本设计的规格或任务拆分。
