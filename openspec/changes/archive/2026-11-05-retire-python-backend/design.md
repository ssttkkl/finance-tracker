## Context

Kotlin 后端已覆盖 HTTP、业务规则、SQLite/PostgreSQL 存储和客户端 E2E；原 Python 运行时仍承担生产入口、Alembic 迁移和部分历史工具。Kotlin `Main.kt` 目前要求 `FT_KOTLIN_PREVIEW=true` 且只监听 `127.0.0.1`，`Database.validateSchema()` 只校验 `alembic_version = 20261030_53`，仓库没有任何建表 DDL。删除 Python 前必须补上 Kotlin 自己的 schema 生命周期和生产启动配置。

## Goals / Non-Goals

**Goals:**

- Kotlin 在空 PostgreSQL/SQLite 上创建与 `20261030_53` 等价的应用 schema，并继续拒绝旧版本或残缺 schema。
- Kotlin 成为 Render 上的唯一后端入口，支持平台端口、可配置绑定地址、显式数据库 URL、Web origin 和健康检查。
- 删除 Python 运行时、后端测试、Alembic 历史迁移和依赖 `ft` 的脚本，同时保持 API、DTO、错误码、财务语义和 UI 不变。
- 保留与后端无关的 Python 工具（UI token 生成、Web 静态构建器及其测试、HTTP 验证），不把它们迁成 Kotlin。

**Non-Goals:**

- 不提供从旧 Alembic revision 升级的路径；已有库必须在删除前已处于 `20261030_53`。
- 不改变数据库表结构、金额精度、工作区隔离、幂等或审计语义。
- 不移植 177 个一次性黄金导出器，也不重写历史 OpenSpec 归档。
- 不在本变更中执行真实 Render 发布或切换 DNS；发布需要单独授权。

## Decisions

**基线 DDL 从当前 Alembic head 生成，而不是手写或依赖 SQLAlchemy `create_all`。**
在临时空 SQLite 与 PostgreSQL 16 上执行 `alembic upgrade head`，分别导出 `.schema` 与 `pg_dump --schema-only --no-owner --no-privileges`，提交为后端资源。这样保留迁移阶段创建的部分索引、外键和约束。SQLite 导出剔除 `sqlite_sequence` 和空的 `account_lifecycle_events__new` 迁移残留；PostgreSQL 导出保留 33 个 public 表。两者都只写入一条 `alembic_version = 20261030_53`。
替代方案：直接用 ORM metadata 建表会漏掉只在迁移中出现的对象；全量移植 64 个 revision 收益低且无法验证。

**空库自动建基线，非空库只校验。**
`Database` 新增 `bootstrapSchema()`：PostgreSQL 在事务内取 advisory lock 后检查 `public` 表；SQLite 以 `mode=rwc` 打开并检查 `sqlite_master`。没有任何应用表时按方言执行基线 SQL 并写版本；存在 `alembic_version` 或任何应用表时进入现有 `validateSchema()`。残缺库、旧版本、未知版本全部 `storage.schema` 失败关闭，不尝试修复。
替代方案：独立迁移命令更可控，但 Render 首次启动需要人工步骤；自动建库更适合当前单实例部署。

**生产入口由环境变量驱动。**
`Main.kt` 移除 `FT_KOTLIN_PREVIEW`，数据库 URL 优先 `FT_DATABASE_URL`、回退 `DATABASE_URL`，并接受 `postgresql://` 与 `postgresql+psycopg://`；端口优先 `PORT`、缺省时读取 `FT_KOTLIN_PORT`；两者均缺失或优先值非法时拒绝启动，不使用默认端口。`FT_WEB_ORIGIN` 必填；端口、绑定地址和 origin 在建库前校验。绑定地址由 `FT_HOST` 提供（Render 设为 `0.0.0.0`）。新增 `/health`，不访问业务数据。
替代方案：保留预览门禁会让 Render 无法启动；硬编码 `0.0.0.0` 会扩大本机暴露面。

**Render 使用 Docker + 环境变量数据库。**
根目录 `Dockerfile` 多阶段构建：JDK 21 构建 `:backend:installDist`，JRE 21 运行安装产物；`render.yaml` 只声明 Web Service，`FT_DATABASE_URL`/`FT_WEB_ORIGIN` 通过 Render 环境变量注入，不声明数据库资源。Web 静态站继续使用现有 `compose/build-wasm-web.sh`，因为它是与后端无关的 Python 构建工具。
容器构建仅在镜像内复制的 daemon 配置中移除 Azul vendor 限制，保留 Java 21 要求并使用基础镜像自带的 Temurin JDK；仓库的本机 daemon 配置不变。构建步骤挂载独立 Gradle 缓存，避免重试重复下载。Gradle 的 [daemon JVM criteria](https://docs.gradle.org/current/userguide/gradle_daemon.html#sec:daemon_jvm_criteria) 允许省略 vendor；原限制会触发第二套 JDK 的网络下载。
替代方案：Render native runtime 依赖平台 JDK 与构建命令；Docker 可固定 JVM、PDF 和字体依赖。

**删除边界按依赖而不是按语言。**
删除 `src/ft`、`tests`、`migrations`、`alembic.ini` 以及所有 `import ft` 的脚本（177 个 `export_*`、迁移台账、质量审查）；删除 `run_backend_tests.py`。保留 `generate_ui_tokens.py`、`compose/build-wasm-web.py`、`compose/tests/test_build_wasm_web.py`、`verify_kotlin_http.py` 及不依赖 `ft` 的分析工具。`pyproject.toml`/`uv.lock` 裁剪为工具依赖，CI 只保留工具相关 Python job。

**默认回归与历史迁移证据显式分开。**
用户确认保留依赖仓库外夹具的历史测试，将其放入独立的 `migrationEvidenceTest`；默认 `test` 和 CI 执行全部自包含测试，不要求旧 Python 导出器。两类测试都继续编译，不改断言或把缺夹具改成通过。使用明确的 JUnit category 登记历史类或混合类中的历史方法，默认任务排除该 category，显式任务只包含该 category；分类以实际外部夹具依赖及共享 helper 调用链为依据，逐类复核，不能将普通业务测试按名称宽泛排除。schema 的真实 PostgreSQL 回归仍属于默认门禁，CI 显式提供 `_test` 数据库。原历史证据只有在配置其必要夹具并实际执行后才可声明通过；不重建已删除的 Python 导出器。随旧测试目录删除的匿名 Polymarket JSON 原样迁入 Kotlin classpath 资源，相关自包含测试继续默认运行。旧来源标签夹具缺少现行必需的平台交易编号时，先保留原输入的拒绝验证，再显式补充合成编号验证来源及资金合同；不放宽生产编号规则。

## Risks / Trade-offs

- [基线 SQL 与生产库漂移] → 基线由 head 实库导出；Kotlin 测试在 SQLite 与显式 PostgreSQL 上建空库并对比必需表/版本；jOOQ 生成代码不重新生成。
- [两实例同时初始化空库] → PostgreSQL 使用事务级 advisory lock，SQLite 使用 `BEGIN IMMEDIATE`；冲突后二次校验版本。
- [Render 首次启动配置缺失] → 缺少 URL、端口或 Web origin 时失败关闭；初始化保留内部 SQL 原因链及回滚 suppressed 诊断，启动入口只输出存储错误码、受限 SQLState、数值错误码和回滚失败数，不打印原异常消息或连接串。`/health` 不输出业务数据。
- [删除 Python 后失去历史迁移证据] → 删除前打 `pre-python-backend-removal` 标签；迁移台账与归档变更保留在 Git 历史。
- [历史脚本仍依赖 `ft`] → 删除所有 `import ft` 的脚本并在 CI 中禁止重新引入；非后端工具不导入后端。
- [Docker 镜像增大/构建变慢] → 多阶段构建、Gradle 缓存层、仅复制运行产物；不把源码和测试带入运行镜像。

本轮用户确认没有现有业务库；若首次发布接入非空库，发布前仍须核对 `20261030_53`，不能以匿名测试库验证替代。

## Migration Plan

1. 在删除前创建 `pre-python-backend-removal` 标签，确认目标数据库均已在 `20261030_53`。
2. 提交基线 SQL、`bootstrapSchema()`、SQLite/PostgreSQL 测试和 `/health`。
3. 提交生产配置：`Main.kt`、`Dockerfile`、`render.yaml`、工具与 CI 调整。
4. 删除 Python 后端、Alembic、后端测试与依赖 `ft` 的脚本；裁剪工具依赖；更新 README/后端说明/功能地图/主规格。
5. 运行 Kotlin 后端全量测试、双后端 schema 测试、共享层测试与 Web E2E；`openspec validate --all --strict`。
6. 回滚：恢复标签并重新部署 Python 入口；数据库 schema 未改变，无需逆向迁移。实际 Render 发布与回滚操作需用户授权后执行。
