# Kotlin 后端

Kotlin/Ktor 后端是 Finance Tracker 的唯一生产入口，提供 `/api/v1` 业务接口、`/openapi.json` 和 `/health`。金额、余额、权限、来源、关系、投影和审计都以该后端与所选数据库为准。

## 数据契约

- 只通过 `FT_DATABASE_URL`（或平台注入的 `DATABASE_URL`）选择一个 PostgreSQL 或文件型 SQLite 数据库；不自动探测、不静默回退、不双写。
- 空数据库在启动时创建 `20261104_54` 基线 schema 并写入 `alembic_version`；已有数据库只校验完整表集合和版本。旧版本、未知版本或残缺 schema 直接失败关闭，不会自动升级或改写。
- 基线 SQL 位于 `src/main/resources/schema/{sqlite,postgresql}-baseline.sql`，在已退役迁移的 53 基线之上加入本分支的 ledger_seq 和 54 版本。历史 Alembic revision 已随 Python 后端退役，仓库不提供从旧 revision 升级的路径。
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

`GET /health` 不读取业务数据，可用于平台健康检查；`GET /openapi.json` 无需认证或数据库连接，当前元数据资源列出 90 项操作，运行时按实际装配筛选。

## Render 部署

仓库根目录的 `Dockerfile` 使用 JDK 21 构建 `:backend:installDist`，运行镜像只携带 JRE 与安装产物；`render.yaml` 声明 Docker Web Service 和 `/health` 检查。数据库不随蓝图声明，需要在 Render 环境变量中配置：

| 变量 | 说明 |
|---|---|
| `FT_DATABASE_URL` | PostgreSQL 连接串；支持 `postgresql://`、`postgresql+psycopg://` 和 `postgres://`，连接安全参数见下文 |
| `FT_WEB_ORIGIN` | Compose Web 站点的 HTTPS origin |
| `FT_IMPORT_STAGING_BACKEND` | 必须显式设为 `r2` 或 `memory` |
| `FT_R2_ENDPOINT` / `FT_R2_BUCKET` / `FT_R2_ACCESS_KEY_ID` / `FT_R2_SECRET_ACCESS_KEY` | `r2` 暂存所需；凭据只放在 Render 环境变量 |
| `FT_R2_REGION` / `FT_R2_PREFIX` | 可选，默认 `auto` / `cash-import` |

Neon 等 PostgreSQL 服务的连接串可以保留 `sslmode` 和 `channel_binding`，例如：

```text
postgresql://USER:PASSWORD@HOST/DATABASE?sslmode=require&channel_binding=require
```

| 参数 | 允许值与行为 |
|---|---|
| `sslmode` | `disable`、`allow`、`prefer`、`require`、`verify-ca`、`verify-full`；大小写不敏感 |
| `channel_binding` | 小写 `disable`、`prefer`、`require`；映射为 JDBC 的 `channelBinding` |

未设置时沿用驱动默认值，不额外覆盖。未知、重复、空值、非法枚举或错误编码在连接前返回安全的 `storage.config`；SQLite 不接受这些参数。用户名和密码仍独立传给驱动，不写入 JDBC URL 或诊断日志。连接串的用户信息按 URL 编码填写，不能通过删除安全选项绕过配置失败。

TLS 按 [PGJDBC 语义](https://jdbc.postgresql.org/documentation/ssl/) 执行：`require` 要求加密但不验证 CA 或主机名，`verify-ca` 验证 CA，`verify-full` 还验证主机名。选择证书验证模式时，运行用户须能读取驱动要求的可信 CA；证书不可用或主机名不符时连接失败，不会自动退回 `require`。此接口不接收额外证书路径参数，也不模拟 libpq 根据本机证书文件隐式改变模式的行为。

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

首次启动会按规定建立基线；`alembic_version` 应等于 `20261104_54`。

发布前确认已有数据库的版本为 `20261104_54`，并完成当前提交的门禁；已有 53 库缺少 ledger_seq 时直接拒绝；须显式升级字段、约束及版本，不能只修改版本号或交给 Kotlin 自动修复。本分支后续破坏性 schema 调整的升级方案仍待完成。Render 从仓库根目录的 Dockerfile 构建并运行 `/app/bin/backend`，不配置 Python 或 Alembic 构建、启动命令。

获得具体服务和提交的发布授权后，可用该服务 Settings 中的秘密 Deploy Hook URL 触发指定提交。URL 只保存在受控环境变量中，不写进仓库或日志：

```bash
: "${RENDER_DEPLOY_HOOK_URL:?set the authorized service deploy hook}"
: "${FT_RELEASE_COMMIT:?set the reviewed full commit SHA}"
curl --fail --silent --show-error --get \
  --data-urlencode "ref=$FT_RELEASE_COMMIT" "$RENDER_DEPLOY_HOOK_URL"
```

该请求只表示触发部署，仍需检查部署状态和 `/health`，再验证登录及一个业务读取。指定 `ref` 会关闭该服务的自动部署；恢复自动部署应另行确认目标分支。操作定义见 [Render Deploy Hooks](https://render.com/docs/deploy-hooks) 和 [指定提交部署](https://render.com/docs/deploys#deploying-a-specific-commit)。本 change 未执行真实 Render 发布。

首次空库启动的实际本机记录位于 change 的 `tasks.md`：安装产物输出日志框架缺少 provider 的警告，没有 schema 创建成功日志。不能以没有报错作为建库证明；需同时核对 `SELECT version_num FROM alembic_version` 返回 `20261104_54`、33 张 public 表及 HTTP 主流程。日志中不输出连接串、凭据或业务数据。

本分支使用 54 schema，不能直接沿用 main 的 53／旧 Python 回退步骤。pre-python-backend-removal 标签只接受 53，直接连接本分支数据库会失败；不得仅修改版本标记规避校验。代码合并可恢复合并前的 65be32d9（备份分支 backup/offline-first-before-main-20261106），该代码同样要求 54；它不会撤回已执行的数据写入。后续破坏性 schema 改动须提供对应的显式升级及相称恢复方案，部署与真实数据恢复仍需具体授权。

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

## 离线账本改造（尚未完成）

`workspaces.ledger_seq` 由 Kotlin 基线 SQL 定义，托管事务按工作区合并一次推进并与事实一同回滚。账户、分类、别名、账单账户映射，以及现金流水、来源证据、条目、关系和资金对应的写入口已接入并完成独立写面审计。幂等收据重放及没有事实变化的操作不推进。

永久收据的摘要与按 ID／幂等键读取支持可选 `ledger_seq`，使用规范非负 64 位十进制字符串，保持超出 JS 安全整数的版本。旧收据缺省时不补写当前版本；损坏持久值停止读取，不静默省略。OpenAPI 资源复用 Kotlin additions 中现有 `LedgerVersion` 的字段定义。完整写集 service 及提交事务填写真实版本尚未接入。

`GET /api/v1/ledger/version` 返回选定工作区的 `ledger_seq` 字符串；`GET /api/v1/ledger/snapshot` 在同一读取事务返回 `workspace_id`、`ledger_seq`、共享规则 `rules_version` 和 `facts`。快照包含全历史现金事实、组成项、来源证据、条目、关系、问题、目录与只读资金关联；保留草稿和删除标记，排除完整投资事件、账户私有配置、余额与投影。金额、标识和修订使用字符串，来源规划及操作中的修订保持 64 位整数，开放 JSON 保留原结构。当前快照为单响应，尚无分页；PostgreSQL 使用可重复读，SQLite 使用同连接 WAL 快照。返回前重新确认会话及选定工作区。共享 `buildLocalCashLedgerEntries` 已将快照派生分组与持久化收支条目组合，公开显示字段取条目，来源流水保持原值；该入口不分配新身份。客户端缓存与离线导入尚未接入。

已接受且生效的只读资金关系另带 `investment_event`，只复制关联资金事件的账户、币种与 from_amount／to_amount，保留精确金额及 null；其余关系该字段为 null，不返回投资正文或完整投资账本。`LedgerFundingSnapshotContractTest` 验证确认／回滚、账户改名、现金删除及版本联动、状态过滤和其他工作区隔离。删除先解除全部物化数据集的资金关系外键，再清目标关系，失败同事务回滚；原投资事件保持。

```sh
curl "$API_ORIGIN/api/v1/ledger/version" -H "Authorization: Bearer $SESSION_TOKEN"
curl "$API_ORIGIN/api/v1/ledger/snapshot" -H "Authorization: Bearer $SESSION_TOKEN"
```

启动及暂存配置使用本文前述唯一 Kotlin 运行时入口；完整写集服务和端侧页面接线尚未完成。

## 只解析 PDF

`POST /api/v1/cash-import/pdf/parse` 要求登录且在当前工作区具有写权限。multipart 包含一份 `metadata` JSON 文本和一份 `file:<client_file_ref>` 文件；`metadata.files` 只能有一项，复用 `client_file_ref`、`source_hint`、`password` 和 `last_modified_at_ms` 字段。密码只放请求体。文件上限 100 MiB，总请求上限 101 MiB，归一响应上限 25 MiB。

入口复用工行信用卡／借记卡的受限 PDF 子进程，返回 `rules_version` 和完整归一文档；文档保留逐行位置、来源说明、问题、错误及页证明，金额使用文本。它不需要 R2／导入暂存配置、不建立会话或计划、不读取账本、不写业务数据，解析后重新核对原用户及工作区权限。文件超限返回 413，文件／来源／密码问题返回 422，解析进程忙或超时返回可重试的 503；未知解析程序故障返回安全的 `internal_error`／500，响应与日志不包含原异常消息或账单内容。取消按取消语义传播。

共享 `parseCashPdf` 传输和 `localNormalizedCashDocument` 还原由单文件 `parseLocalCashStatement` 消费，按原字节／来源优先级分派且只读一次原文件；批次 owner 与页面／完整本地导入尚未接入。旧服务端导入路径仍保留到本次改造完成，不能据此声明离线导入已可用。

## 只解码旧 XLS

`POST /api/v1/cash-import/xls/parse` 只支持当前建行借记卡 OLE／BIFF 账单，沿用上述单文件 multipart、输入／响应预算和前后鉴权。`source_hint` 可省略，或使用 `ccb_debit`／`ccb-debit`；`password` 只能省略或为 null，不提供 XLS 密码能力。原文件通过受限子进程解析，不读取账本、不建立会话或暂存、不写业务数据。

返回 `rules_version` 和原始 `CashStatementDocument`，保留原始单元格字符串、位置、计数与覆盖证明；它与 PDF 的归一文档是不同返回类型。共享 `parseCashXls` 已提供，结果交给 `normalizeLocalCashStatement` 完成端侧归一，再继续查重与配对。金额保留解码文本，`declaredCount` 保留完整 Long。该格式需要联网，单文件 `parseLocalCashStatement` 已组合远端解码与本地归一；批次 owner、稳定输入保存和页面尚未接入，不能据此声明端侧 XLS 解码或完整离线导入已可用。

已识别的文件错误返回原 422，超限为 413 或 `statement_rows_too_large`，busy／timeout 为 503。新 XLS worker 的不明退出、坏内部回包及未知程序故障返回安全的 500；受限 worker 的可捕获程序故障只传递类型与有界调用帧，不传异常消息。旧银行读取器内部的既有异常分类保持，不能将外边界回归解释为已识别其内部任意程序异常。
