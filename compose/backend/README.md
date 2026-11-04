# Kotlin 后端

Kotlin/Ktor 后端是 Finance Tracker 的唯一生产入口，提供 `/api/v1` 业务接口、`/openapi.json` 和 `/health`。金额、余额、权限、来源、关系、投影和审计都以该后端与所选数据库为准。

## 数据契约

- 只通过 `FT_DATABASE_URL`（或平台注入的 `DATABASE_URL`）选择一个 PostgreSQL 或文件型 SQLite 数据库；不自动探测、不静默回退、不双写。
- 空数据库在启动时创建 `20261030_53` 基线 schema 并写入 `alembic_version`；已有数据库只校验完整表集合和版本。旧版本、未知版本或残缺 schema 直接失败关闭，不会自动升级或改写。
- 基线 SQL 位于 `src/main/resources/schema/{sqlite,postgresql}-baseline.sql`，由迁移完成时的 `20261030_53` 实际 head 导出。历史 Alembic revision 已随 Python 后端退役，仓库不提供从旧 revision 升级的路径。
- 金额、数量、汇率和成本使用精确十进制语义；SQLite 与 PostgreSQL 共享同一组 Application Service 和用户合同。

## 启动

需要 JDK 21。

```bash
cd compose
./gradlew :backend:installDist --no-configuration-cache
FT_DATABASE_URL='sqlite+pysqlite:////absolute/path/finance-tracker.db' \
FT_IMPORT_STAGING_BACKEND=memory FT_IMPORT_STAGING_ALLOW_MEMORY=1 \
FT_HOST=127.0.0.1 FT_KOTLIN_PORT=18240 FT_WEB_ORIGIN=http://127.0.0.1:5173 \
./backend/build/install/backend/bin/backend
```

端口必须通过 `PORT` 或 `FT_KOTLIN_PORT` 显式提供，前者优先；缺失或非法时拒绝启动。`FT_HOST` 默认 `127.0.0.1`，Render 使用 `0.0.0.0`。`FT_WEB_ORIGIN` 必须显式指定允许的 Compose Web origin，生产使用 HTTPS origin。端口、绑定地址和 origin 在初始化数据库前校验。

`GET /health` 不读取业务数据，可用于平台健康检查；`GET /openapi.json` 无需认证或数据库连接，当前完整装配运行时列出 86 项操作。

## Render 部署

仓库根目录的 `Dockerfile` 使用 JDK 21 构建 `:backend:installDist`，运行镜像只携带 JRE 与安装产物；`render.yaml` 声明 Docker Web Service 和 `/health` 检查。数据库不随蓝图声明，需要在 Render 环境变量中配置：

| 变量 | 说明 |
|---|---|
| `FT_DATABASE_URL` | Render PostgreSQL 连接串；支持 `postgresql://`、`postgresql+psycopg://` 和 `postgres://` |
| `FT_WEB_ORIGIN` | Compose Web 站点的 HTTPS origin |
| `FT_IMPORT_STAGING_BACKEND` | 必须显式设为 `r2` 或 `memory` |
| `FT_R2_ENDPOINT` / `FT_R2_BUCKET` / `FT_R2_ACCESS_KEY_ID` / `FT_R2_SECRET_ACCESS_KEY` | `r2` 暂存所需；凭据只放在 Render 环境变量 |
| `FT_R2_REGION` / `FT_R2_PREFIX` | 可选，默认 `auto` / `cash-import` |

`memory` 暂存仅适合本机或单实例演示：进程重启会丢失导入会话；生产应使用 `r2`。`FT_IMPORT_STAGING_TTL_SECONDS`（1–1800）、`FT_IMPORT_STAGING_SWEEP_SECONDS`（1–60）和 `FT_IMPORT_STAGING_SWEEP_PAGE_SIZE`（1–200）控制会话 TTL 与清扫。

本地构建镜像：

```bash
docker build -t finance-tracker-api .
docker volume create finance-tracker-data
docker run --rm -p 8080:8080 \
  -e FT_DATABASE_URL='sqlite+pysqlite:////data/finance.db' \
  -e FT_IMPORT_STAGING_BACKEND=memory -e FT_IMPORT_STAGING_ALLOW_MEMORY=1 \
  -e FT_WEB_ORIGIN='http://127.0.0.1:5186' \
  -v finance-tracker-data:/data finance-tracker-api
```

镜像预建由 UID `10001` 拥有的 `/data`；新命名卷沿用该目录权限。已有卷或绑定目录须由操作者核对该 UID 的写权限，不能用放宽全部权限解决。数据库与运行数据位于仓库外的 Docker 卷中。

首次启动会按规定建立基线；`alembic_version` 应等于 `20261030_53`。

发布前确认已有数据库的版本为 `20261030_53`，并完成当前提交的门禁；旧版本先使用删除前代码升级，不能交给 Kotlin 自动修复。Render 从仓库根目录的 Dockerfile 构建并运行 `/app/bin/backend`，不配置 Python 或 Alembic 构建、启动命令。

获得具体服务和提交的发布授权后，可用该服务 Settings 中的秘密 Deploy Hook URL 触发指定提交。URL 只保存在受控环境变量中，不写进仓库或日志：

```bash
: "${RENDER_DEPLOY_HOOK_URL:?set the authorized service deploy hook}"
: "${FT_RELEASE_COMMIT:?set the reviewed full commit SHA}"
curl --fail --silent --show-error --get \
  --data-urlencode "ref=$FT_RELEASE_COMMIT" "$RENDER_DEPLOY_HOOK_URL"
```

该请求只表示触发部署，仍需检查部署状态和 `/health`，再验证登录及一个业务读取。指定 `ref` 会关闭该服务的自动部署；恢复自动部署应另行确认目标分支。操作定义见 [Render Deploy Hooks](https://render.com/docs/deploy-hooks) 和 [指定提交部署](https://render.com/docs/deploys#deploying-a-specific-commit)。本 change 未执行真实 Render 发布。

首次空库启动的实际本机记录位于 change 的 `tasks.md`：安装产物输出日志框架缺少 provider 的警告，没有 schema 创建成功日志。不能以没有报错作为建库证明；需同时核对 `SELECT version_num FROM alembic_version` 返回 `20261030_53`、33 张 public 表及 HTTP 主流程。日志中不输出连接串、凭据或业务数据。

回退到旧 Python 入口时，在仓库外恢复删除前标签，保持同一个已在 head 的数据库，不执行反向迁移：

```bash
git worktree add --detach /absolute/outside/repository/finance-tracker-python pre-python-backend-removal
cd /absolute/outside/repository/finance-tracker-python
uv sync --frozen
# 沿用受控环境中的 FT_DATABASE_URL、FT_WEB_ORIGIN 和导入暂存配置。
uv run uvicorn ft.web.app:create_runtime_app --factory --host 0.0.0.0 --port "$PORT"
```

该标签没有新的 Kotlin Dockerfile。线上回退须恢复旧 Python 服务的构建与启动配置并验证健康、登录及业务读取，不能只让当前 Docker 服务选择旧标签。实际回退、服务配置变更和流量切换均需具体授权。


## 测试

```bash
cd compose
./gradlew :backend:test :backend:codegenTest \
  :backend:ktlintCheck :backend:detekt :backend:installDist --no-configuration-cache
```

PostgreSQL 基线与双后端验证需要显式、名称以 `_test` 结尾的数据库：

```bash
export FT_TEST_POSTGRES_URL='postgresql://localhost/finance_tracker_test'
./gradlew :backend:test --tests '*SchemaBootstrap*' --no-configuration-cache
```

测试不会自动启动、探测或切换数据库。`FT_TEST_POSTGRES_URL` 未配置时 PostgreSQL 用例标记为跳过，不能据此宣称双后端通过。

默认 `:backend:test` 和 CI 执行全部自包含单元、业务与 HTTP 测试，CI 另显式提供 PostgreSQL `_test` 库执行 schema 验证。`HistoricalMigrationEvidence` category 标记仍依赖仓库外历史夹具的类或方法；混合类的自包含方法保留在默认任务中。两类测试始终编译，默认任务不把历史用例伪装为通过。

历史迁移证据须显式执行：

```bash
# 先在当前终端设置所选测试实际需要的 FT_KOTLIN_* 夹具目录和测试数据库凭据。
./gradlew :backend:migrationEvidenceTest --tests '*WorkspaceContractTest' --no-configuration-cache
```

`migrationEvidenceTest` 只选择该 category，默认 `test` 排除它；不要用默认任务的 `--tests` 选择历史类。缺少必需夹具时显式任务失败。Python 导出器已随后端退役删除，仓库不再重新生成这些历史夹具；保留原断言是为了使用已保存的迁移证据，未实际配置并运行的历史测试不属于当前通过结论。

## jOOQ 代码生成

普通构建直接使用已提交的生成代码，无需连接数据库。schema 事实源是 `src/main/resources/schema/postgresql-baseline.sql`，不再读取 Alembic 目录。

```bash
FT_JOOQ_CODEGEN_URL=jdbc:postgresql://127.0.0.1:55432/finance_jooq_test \
FT_JOOQ_CODEGEN_USER=postgres \
./gradlew :backend:generateJooq
```

生成器只接受显式 PostgreSQL `_test` 数据库；`codegenTest` 不连接数据库，验证非法目标不会输出代码或泄露连接串。

## 历史证据

Python 后端、Alembic 历史迁移和契约导出器的完成证据保留在归档变更与 Git 历史中：

- [`kotlin-backend-shared-core`](../../openspec/changes/archive/2026-11-04-kotlin-backend-shared-core/tasks.md)
- [`retire-python-backend`](../../openspec/changes/archive/2026-11-05-retire-python-backend/tasks.md)
- `pre-python-backend-removal` 标签

当前行为以 `openspec/specs/` 为准，不以归档任务或旧 Python 实现为准。
