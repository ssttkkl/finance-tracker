# 实施任务

## 1. 思考

- [x] 1.1 阅读 `openspec/specs/shared/runtime-database`、`shared/kotlin-backend-parity`、`authentication/user-workspace-access`、`shared/deployment-diagnostics` 与导入相关规格，确认本地部署不改变账务、工作区隔离和幂等语义。
- [x] 1.2 阅读根 `Dockerfile`、`render.yaml`、`compose/backend` 启动配置、`compose/build-wasm-web.py`、`compose/webApp` 生产分发和本地预览服务器，记录可复用的健康检查、origin 与静态响应头合同。
- [x] 1.3 记录分级：安全边界变化 + 新部署面 + 跨后端验证，按 A 类完整变更执行。
- [x] 1.4 记录用户决策：Q1=A 本机 PostgreSQL 新库、Q2=B 局域网访问、Q8=B 入库并推送 `main`、Q9=B 私有 IPv4 无条件允许、Q10=A 固定 IP `192.168.1.111`、Q11=A 全新空库、Q12=C 不做自动备份、Q13=A 局域网 HTTPS（Caddy 内部 CA，固定 `https://192.168.1.111:8443`）。

## 2. 计划

- [x] 2.1 完成 `proposal.md`、delta spec、`design.md` 与 `tasks.md`，覆盖目标、非目标、验收、失败模式、安全风险、回滚和部署步骤。
- [x] 2.2 运行 `openspec validate local-docker-postgres --strict`，修复 artifact 一致性问题。
- [x] 2.3 核对 `design.md` 的 Open Questions 为空；固定 IP、无自动备份、Native 不改动均已由用户确认。

## 3. 任务拆分与一致性

- [x] 3.1 将每项 delta requirement 映射到失败测试、实现、文档和验证任务。
- [x] 3.2 确认不改动 `shared/runtime-database` 的 schema 版本措辞；在部署文档中按代码常量记录实际基线。
- [x] 3.3 确认 Web/Android/iOS 跨端影响：仅 Web 与后端部署边界变化，Native 不进本次范围，功能地图无需新增页面级能力。

## 4. 构建

- [x] 4.1 先写失败测试：`WebOriginTest` 增加私有 IPv4 HTTP 接受、公网 HTTP 拒绝、非法形状拒绝。
- [x] 4.2 先写失败测试：`compose/tests/test_build_wasm_web.py` 增加私有 IPv4 HTTP API origin 接受与公网 HTTP 拒绝。
- [x] 4.3 实现 `WebOrigin.kt` 的 RFC1918 私有 IPv4 判断，保留本机 HTTP、HTTPS 和非法形状拒绝。
- [x] 4.4 实现 `build-wasm-web.py` 的私有 IPv4 HTTP API origin 校验，保留 HTTPS、端口和路径约束。
- [x] 4.5 新增 Web 生产镜像 Dockerfile，使用镜像内 JDK 21、`python3` 与 `libatomic1` 构建 Wasm 分发，并由 nginx 运行。
- [x] 4.6 新增 nginx 配置：同源 `/api` 代理、COOP/COEP、Wasm MIME、SPA 回退、上传大小与超时、静态缓存策略。
- [x] 4.7 新增根 `compose.yaml`：PostgreSQL 17、后端、Web、Caddy、健康检查、依赖顺序、命名卷、仅发布 Caddy TLS 端口。
- [x] 4.8 新增 `.env.example` 与 `.dockerignore` 排除规则；生成仓库外真实 `.env`。
- [x] 4.9 更新 `README.md` 与 `compose/README.md` 的本地部署入口，新增部署说明和手工备份/回滚命令。
- [x] 4.10 更新 `openspec/specs/README.md` 的共享能力导航，加入 `shared/local-docker-deployment`（archive 同步主规格后完成）。
- [x] 4.11 新增 Caddy 内部 CA TLS 入口与根证书导出流程，固定 `https://192.168.1.111:8443`。
- [x] 4.12 更新 Web 构建脚本复用镜像内 Java 21，并补单元测试。

## 5. 审查

- [x] 5.1 独立复核产品/范围：验收覆盖登录、建工作区、写入、重启持久化、局域网访问和无自动备份的边界。
- [x] 5.2 独立复核工程/安全：私网范围、origin 形状、CORS、凭据、镜像上下文、端口暴露、请求体上限和回滚。
- [x] 5.3 复核最终 diff：确认无真实密码、数据库连接串、账本数据或范围外改动；运行 `git diff --check`。
- [x] 5.4 记录审查 finding、处置和回写位置。

### 审查记录

- **独立复核（2026-11-24）**：复核者只读审查最终 diff 与运行栈，发现 2 个 critical（Worker `.mjs` MIME、`.env.example` 被忽略）、5 个 major（文档 origin 变量不一致、curl 说明、构建 origin 边缘不一致、`.dockerignore` 嵌套数据库、QA 证据）和若干 minor。
- **处置**：C1 在 nginx 增加 `default_type application/javascript` 并验证 Worker 可加载；C2 增加 `!/.env.example` 并在提交时 `git add`；M1/M2 修正文档与 design；M3 对齐本机 HTTP、端口 0、前导零和畸形 IPv6；M4 改为递归 `**/*.db*`/`**/*.sqlite*`；M5/M6 重跑浏览器 QA、删除临时脚本并回写证据；minor 同步修正。
- **重新复核**：复核者确认 C1、M1、M2、M3、M5、M6 关闭；C2 待提交时纳入 `.env.example`，M4 嵌套规则已补。`codebase-degradation-review` 技能在当前运行时不可用，已用独立复核上下文完成等效审查。
- **测试门禁**：曾因测试结果/worker 缓存出现 `java.io.EOFException`；`cleanTest` 后全量测试恢复通过，最终回归见 6.12。

## 6. 测试与 QA

- [x] 6.1 运行 `python3 compose/tests/test_build_wasm_web.py`。
- [x] 6.2 运行定向后端测试 `./gradlew :backend:test --tests '*WebOriginTest' --tests '*StartupBoundaryTest' --tests '*ProductionSettingsTest'`。
- [x] 6.3 运行完整后端回归 `./gradlew :backend:cleanTest :backend:test :backend:ktlintCheck :backend:detekt --no-configuration-cache`。
- [x] 6.4 准备名称以 `_test` 结尾的专用 PostgreSQL 库，设置 `FT_TEST_POSTGRES_URL`，运行 schema 双后端用例。
- [x] 6.5 构建 Compose Web 生产分发，确认产物包含入口、Wasm、Worker 与资源。
- [x] 6.6 `docker compose config` 校验编排变量和端口；`docker compose up -d --build` 启动完整栈。
- [x] 6.7 验证 `db`、`api`、`web`、`caddy` 健康，`/health` 成功，宿主机只能访问 Caddy TLS 端口。
- [x] 6.8 用真实 Chromium 从 `https://192.168.1.111:8443` 完成登录/注册、创建工作区、写入现金流水；确认 `isSecureContext`、`crossOriginIsolated` 为真且 `SharedArrayBuffer` 可用，并验证导入 Worker 资源 MIME；记录视口、截图、控制台和网络错误。
- [x] 6.9 重启完整栈，确认账号、工作区、账本数据和 Caddy 根证书仍在。
- [x] 6.10 验证后端拒绝公网 HTTP origin，接受私有 IPv4 HTTP、本机 HTTP 和 HTTPS；验证明文入口未发布。
- [x] 6.11 导出 Caddy 根证书并用 `curl --cacert` 验证信任链，执行一次 `pg_dump` 备份与恢复演练，确认文件在仓库外且可读。
- [x] 6.12 记录实际命令、结果、`HEAD`、基线、执行时间和未运行项/残余风险。

## 7. 发布

- [x] 7.1 按最终实现核对 README、部署说明、`.env.example`、Compose 变量和命令示例。
- [x] 7.2 运行 `openspec validate --all --strict` 与 `openspec doctor`。
- [x] 7.3 归档 change，同步 delta 到主规格，确认 `openspec/changes/archive/2026-11-24-local-docker-postgres/`。
- [ ] 7.4 本地提交本次范围文件，推送当前分支到 `origin/main`；记录提交 SHA 和推送结果。
- [ ] 7.5 记录发布状态、回滚命令、观察项（Caddy 内部 CA、固定 IP、无自动备份）和用户决策反思。

### 最终验证证据（2026-11-24 CST）

基线：`origin/main` `b9554eaea2607aa17ae9fc39f926e4961fe9ad26`；工作树为同一提交加本次未提交变更。浏览器：Google Chrome 158.0.8127.37；视口 1440×900 与 390×844。

- **构建**：`docker compose build api`、`docker compose build web` 成功；Web 构建复用镜像内 JDK 21，安装 `python3`、`libatomic1`；最终 `docker compose up -d --build` 后 `db/api/web/caddy` 均 healthy。
- **单元与契约**：`python3 compose/tests/test_build_wasm_web.py` 16 项通过；`./gradlew :backend:test --tests '*WebOriginTest' --tests '*StartupBoundaryTest' --tests '*ProductionSettingsTest'` 通过；`:backend:cleanTest :backend:test :backend:ktlintCheck :backend:detekt` 在重建后的 `FT_TEST_POSTGRES_URL=postgresql://postgres:test@127.0.0.1:55432/finance_tracker_test` 下通过（约 4 分 9 秒）；`:shared:wasmJsTest` 通过（约 1 分 17 秒）；`openspec validate --all --strict` 32/32 通过；`openspec doctor` 通过。
- **HTTPS 与 Worker**：Chrome 从 `https://192.168.1.111:8443` 打开；`isSecureContext=true`、`crossOriginIsolated=true`、`SharedArrayBuffer` 可用。`curl --cacert certs/finance-tracker-local-ca.crt https://192.168.1.111:8443/health` 返回 200。`/cash-import-worker/assets/<sha>/entry.mjs` 返回 `application/javascript` 且 `new Worker(..., { type: "module" })` 无错误；该 MIME 修复解决了复核时复现的登录 `DomainFailure`。
- **浏览器主流程**：注册新账号 → 创建工作区 → 创建账户 → 写入 `-12.50 CNY` 流水 → 账本显示“部署验证”；控制台仅有已知 `wasmExports` 弃用警告，无网络失败、无 4xx/5xx API 错误。截图：`outputs/local-docker-postgres/ft-deploy-desktop.png`、`ft-deploy-mobile.png`；最终发布前复验截图 `ft-release-desktop.png`、`ft-release-mobile.png`（2026-11-24 23:02）。
- **重登与持久化**：`docker compose down`（保留卷）后 `up -d`，users/workspaces/accounts/cash_transactions 计数保持不变；用原账号登录后读回“部署验证”；截图：`ft-final-desktop.png`、`ft-final-mobile.png`；Caddy 根证书 SHA-256 前后一致。
- **备份恢复**：`pg_dump -Fc` 生成 130 KB 备份，恢复到 `finance_tracker_restore_test` 后计数与版本 `20261104_54` 一致，随后删除测试库。
- **负向验证**：`FT_WEB_ORIGIN=http://8.8.8.8:8080` 的 API 容器以 `invalid Web origin` 拒绝启动；私网 HTTP origin 通过 origin 校验；宿主机 `8080/5432` 关闭，只有 `8443` 开放；`/api` 与 `/health` 经 Caddy → nginx → API 正常。
- **残余风险**：未在第二台真实手机/电脑上完成根证书安装与局域网访问；自动化浏览器使用 `ignoreHTTPSErrors`，证书信任链另由 `curl --cacert` 和 Node `NODE_EXTRA_CA_CERTS` 验证，未安装宿主机系统级根证书；未覆盖 Safari/iOS、接近 256 MiB 的上传和长时间 SSE；固定 IP 需路由器 DHCP 保留；使用非 80/443 端口（默认端口会导致浏览器 Origin 省略端口）。
