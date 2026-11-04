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
FT_HOST=127.0.0.1 FT_KOTLIN_PORT=18240 \
./backend/build/install/backend/bin/backend
```

服务默认监听 `127.0.0.1:18240`。`PORT` 优先于 `FT_KOTLIN_PORT`；`FT_HOST` 控制绑定地址，Render 使用 `0.0.0.0`。`FT_WEB_ORIGIN` 指定允许的 Compose Web origin；默认 `http://127.0.0.1:5173`，生产必须使用显式 HTTPS origin。

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
docker run --rm -p 8080:8080 \
  -e FT_DATABASE_URL='sqlite+pysqlite:////data/finance.db' \
  -e FT_IMPORT_STAGING_BACKEND=memory -e FT_IMPORT_STAGING_ALLOW_MEMORY=1 \
  -e FT_WEB_ORIGIN='http://127.0.0.1:5186' \
  -v "$PWD/.data:/data" finance-tracker-api
```

首次启动会按规定建立基线；`alembic_version` 应等于 `20261030_53`。

## 测试

```bash
cd compose
./gradlew :backend:test --tests '*SchemaBootstrap*' :backend:codegenTest \
  :backend:ktlintCheck :backend:detekt :backend:installDist --no-configuration-cache
```

PostgreSQL 基线与双后端验证需要显式、名称以 `_test` 结尾的数据库：

```bash
export FT_TEST_POSTGRES_URL='postgresql://localhost/finance_tracker_test'
./gradlew :backend:test --tests '*SchemaBootstrap*' --no-configuration-cache
```

测试不会自动启动、探测或切换数据库。`FT_TEST_POSTGRES_URL` 未配置时 PostgreSQL 用例标记为跳过，不能据此宣称双后端通过。

部分迁移期的原断言测试仍读取 `FT_KOTLIN_*_CONTRACT_DIR` 等仓库外夹具目录。这些目录的 Python 导出器已随后端退役删除，仓库不再能重新生成它们；默认 CI 只运行 schema、静态分析和安装检查。保留这些测试是为了历史证据可追溯，不把它们描述为可在干净检出中直接复现。

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
- [`retire-python-backend`](../../openspec/changes/retire-python-backend/tasks.md)
- `pre-python-backend-removal` 标签

当前行为以 `openspec/specs/` 为准，不以归档任务或旧 Python 实现为准。
