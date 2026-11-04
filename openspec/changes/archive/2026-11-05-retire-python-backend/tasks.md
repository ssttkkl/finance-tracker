## 1. 思考与基线准备

- [x] 1.1 阅读 `shared/runtime-database`、`shared/kotlin-backend-parity`、`compose/backend/README.md`、CI 工作流和保留脚本，确认删除边界；记录到本文件第 5 阶段。
- [x] 1.2 在删除前创建 `pre-python-backend-removal` 标签；用户确认本轮没有现有业务库，现有库版本核对保留为首次发布前条件，不以测试库替代。
- [x] 1.3 用临时空 SQLite 与 PostgreSQL 执行 `alembic upgrade head`，导出 `sqlite-baseline.sql` 与 `postgresql-baseline.sql`；剔除 SQLite `sqlite_sequence`/`account_lifecycle_events__new`，写入 `alembic_version = 20261030_53`。
- [x] 1.4 对比两种方言的表集合与必需表清单，确认 33 个 public 表覆盖 Kotlin `requiredTables`，记录导出命令与哈希。

## 2. 计划与任务拆分

- [x] 2.1 在 `design.md` 记录基线生成、启动引导、Render 配置、删除边界和回滚方案（已完成，核对实现不偏离）。
- [x] 2.2 列出将删除的脚本与保留脚本，确认保留脚本不 `import ft`；记录清单和理由。

## 3. 构建：Kotlin schema 与生产入口

- [x] 3.1 先写失败测试：空 SQLite 建库后必需表与版本齐全；二次启动不重复建表；残缺库/旧版本失败关闭且无写入。
- [x] 3.2 先写失败测试：显式 PostgreSQL 空库建库与版本校验；未配置 `FT_TEST_POSTGRES_URL` 时按未完成记录，不自动探测。
- [x] 3.3 实现 `SchemaBootstrap`/`Database.bootstrapSchema()`，按方言执行基线 SQL，PostgreSQL 使用事务级 advisory lock，SQLite 使用 `mode=rwc` 与 `BEGIN IMMEDIATE`。
- [x] 3.4 更新 `Main.kt`：移除预览门禁，支持 `FT_DATABASE_URL`/`DATABASE_URL`、`postgresql://`、`PORT`/`FT_KOTLIN_PORT`、`FT_HOST`，启动时执行建库/校验。
- [x] 3.5 在路由加入无业务数据的 `/health`，验证未认证可访问。
- [x] 3.6 更新 jOOQ 生成器的输入从 `migrations/` 改为 PostgreSQL 基线文件，保留显式 `_test` 数据库约束。

## 4. 构建：删除 Python 后端与绑定脚本

- [x] 4.1 删除 `src/`、`tests/`、`migrations/`、`alembic.ini`、`compose/test-migration.json` 和 Python 后端缓存。
- [x] 4.2 删除所有 `import ft` 的脚本（含 177 个 `export_*`、迁移台账/检查、`run_backend_tests.py`、`migrate_*`、`review_*`、依赖 `ft` 的分析脚本）并复核无残留引用。
- [x] 4.3 保留并验证非后端 Python 工具：`generate_ui_tokens.py`、`compose/build-wasm-web.py`、`compose/tests/test_build_wasm_web.py`、`verify_kotlin_http.py` 及不依赖 `ft` 的分析工具。
- [x] 4.4 裁剪 `pyproject.toml`/`uv.lock` 为工具依赖，移除 FastAPI/SQLAlchemy/Alembic/数据库驱动和后端测试配置；验证保留工具可运行。
- [x] 4.5 更新 CI：删除 Python 后端测试、性能、迁移 job；保留 UI token、Web 构建器测试与 Compose/Node 门禁；确认干净检出不需要 Python 后端。

## 5. 构建：Docker 与 Render

- [x] 5.1 新增根目录多阶段 `Dockerfile`，JDK 21 构建 `:backend:installDist`，JRE 21 运行，非 root 用户，暴露平台端口。
- [x] 5.2 新增 `render.yaml`，只声明 Docker Web Service，`FT_HOST=0.0.0.0`、`/health`、`FT_DATABASE_URL`/`FT_WEB_ORIGIN` 由环境变量注入。
- [x] 5.3 本地构建镜像并以 SQLite 启动，验证 `/health`、注册/登录与一个真实业务读请求；停止并清理容器。
- [x] 5.4 记录 Render 环境变量、部署命令、首次建库日志和回滚命令；实际发布等待用户授权。

## 6. 审查

- [x] 6.1 5a 范围/工程审查：删除边界、schema 等价、启动失败路径、密钥脱敏、回滚；按严重度记录 finding 与处置。
- [x] 6.2 5b `$codebase-degradation-review`：契约/UI 专项按范围调度 `$contract-design-review`、`$ui-reuse-review`，记录结论与未覆盖项。

## 7. 测试与 QA

- [x] 7.1 运行默认 Kotlin 后端全部自包含测试、共享层测试、business-contracts/domain 测试；修复失败。依赖外部夹具的历史证据另设显式 `migrationEvidenceTest`，逐类复核分类且不改原断言；未实际执行的历史证据保留未完成说明。
- [x] 7.2 在 SQLite 与显式 `FT_TEST_POSTGRES_URL` 的真实 PostgreSQL `_test` 库运行本次 schema 合同矩阵，覆盖建库、幂等、版本、缺表、失败回滚；历史业务证据不计入本次执行通过。
- [x] 7.3 运行 Web 构建、Compose Web E2E 冒烟与 live 用例（Kotlin 后端 + 同源代理），记录浏览器、视口、步骤和错误。
- [x] 7.4 运行 `openspec validate --all --strict`、`git diff --check`、脚本引用扫描和镜像启动检查。
- [x] 7.5 复核 README、后端说明、功能地图、词表、技能与 `openspec/project-context.md`，移除 Python 后端运行表述并保留非后端 Python 工具说明。

## 8. 发布与归档

- [ ] 8.1 按授权提交推送当前功能分支，并创建目标为 `main` 的 PR；删除前标签与删除提交保留在同一仓库。
- [x] 8.2 归档 `retire-python-backend`，同步 `shared/runtime-database` 与 `shared/kotlin-backend-parity` 主规格，确认 `No active changes found`。
- [x] 8.3 记录交付证据、HEAD、验证命令、未执行项（真实 Render 发布、外部历史业务双后端证据）与回滚入口。

## 当前审查与验证证据

核对时间：2026-11-05（Asia/Shanghai；验证跨 11 月 4–5 日执行）。HEAD：`ef3cb8d83e9e6620a11c7041a3b92516b4d95d01`；实施比较基线：`14c23762`。本节覆盖该基线到当前工作树，未提交改动的证据不得当作其他 HEAD 的通过结果。运行证据统一保存在仓库外 `/Users/huangwenlong/Library/Caches/finance-tracker/retire-python-backend-qa`（下称 QA 目录）。

### 阶段 1–4：范围与实现

已删除 Python 后端 `src/`、`tests/`、`migrations/`、`alembic.ini` 及其绑定脚本。删除脚本共 185 个，其中 177 个 `export_*`，另有旧迁移、审查及后端测试入口。当前保留 UI token、HTTP 验证、独立去重分析工具及 Compose Web 构建器，均不依赖 `ft`；`pyproject.toml`/`uv.lock` 只保留工具依赖。回退标签 `pre-python-backend-removal` 本地及远端均指向 `14c23762b30667387164114ef74badf67a976659`，见 `rollback-tag.log`。用户确认本轮没有现有业务库，1.2 的现有库核对不适用；首次发布前若接入非空库，必须确认版本为 `20261030_53`，本轮匿名测试库不替代该发布条件。

完整表门禁补入 `cash_entries`、`cash_entry_members`、`cash_relation_migration_audit`；SQLite 以 `BEGIN IMMEDIATE` 建库。端口与 origin 在数据库初始化前验证。启动保留原 SQL 异常及回滚异常，输出只含安全错误码、SQLState、vendor code 和回滚异常数量，不输出 URL、凭据或数据库异常消息。

用户确认将依赖外部夹具的历史迁移证据拆为显式 `:backend:migrationEvidenceTest`；默认 `:backend:test` 和 CI 执行全部自包含测试。200 个相关测试类逐类核对，25 个混合类保留 40 个自包含方法在默认任务执行。默认 820 个执行案例与历史 1,219 个案例无重叠、无遗漏；2,027 个方法因参数化产生 2,039 个案例。历史任务缺夹具会明确失败，dry-run 只验证发现集合。Polymarket 匿名资源从回退标签原样恢复为 classpath fixture；旧平台来源测试明确区分无编号拒绝与补编号后的当前合同，未改生产财务语义。

跨端影响：无 UI、共享业务或 Native 实现改动；Web live 用例仅更新后端名称。客户端仍调用相同 API，执行受影响的 Web 注册冒烟；原生设备 QA 不适用。

### 阶段 5：独立审查

独立上下文完成 5a 产品/范围、工程、安全、最终 diff 审查及 5b `$codebase-degradation-review` → `$contract-design-review`。无 UI 实现，UI 专项不适用。范围覆盖删除边界、DDL、数据库启动、配置、Docker、Render、CI、历史测试分类及文档。

- 高：默认端口/origin 掩盖缺配置。已在建库前拒绝，配置回归与真实子进程验证通过，独立复核关闭。
- 高：CI 仅跑 schema 过滤。按用户确认拆分历史任务，恢复完整自包含门禁；当前与干净源码副本均 820/820 通过，分类审查关闭。
- 中：必需表漏三张。SQLite/PostgreSQL 逐表独立删除回归通过，独立复核关闭。
- 中：SQLite deferred 事务与约定不符。改为立即事务，实际 writer 持锁等待回归通过，独立复核关闭。
- 中：bootstrap 丢失 SQL 原因与回滚诊断。实际第三次 CREATE 后注入失败及回滚异常，两种数据库均核对原异常对象、suppressed 对象与安全日志，独立复核关闭。
- 中：Docker 非 root 无法写默认数据目录。预建 UID 10001 拥有的 `/data`，文档使用新命名卷；实际镜像的 UID 10001 成功创建并读写 SQLite，独立复核关闭。
- 低：SKILL 将 session 与工作区业务授权混淆。修正无工作区 session 说明，独立复核关闭。

容器构建只在镜像内删除 daemon 的 Azul vendor 限制、保留 Java 21 并使用 Temurin，新增 Gradle cache mount；独立复核无新增 finding。最终独立验收已读取镜像、API、浏览器、SQLite 快照与 Docker 清理证据，并通过 Docker API 确认专用容器和数据卷均不存在，无新增阻断 finding。真实 Render 发布未执行；本轮无现有业务库。

### 阶段 6：当前实际验证

- `FT_TEST_POSTGRES_URL=postgresql://huangwenlong@127.0.0.1:55435/ft_retire_backend_test ./compose/gradlew -p compose :backend:test :backend:codegenTest :business-contracts:jvmTest :business-domain:jvmTest :backend:ktlintCheck :backend:detekt :backend:installDist --no-configuration-cache`：通过，默认后端 820 项、0 失败、0 跳过。`default-ci.log` 与 `default-test-results/` 保存当前实现证据。
- 同一源码复制到仓库外 `clean-source/`，上述门禁增加 `--no-build-cache --rerun-tasks`：29 个任务实际执行，通过，后端 820 项、0 失败、0 跳过；`clean-ci.log`。实施验证时 711 个 backend/business 文件 SHA-256 与冷副本完全一致，见 `test-source-proof.json`；归档仅更新其中 README 的链接，运行时、测试和构建输入未变，见 `post-archive-source-proof.json`。此前一次 SDK 生命周期断言短暂失败，后续当前实现与冷执行均未重现；未据此修改生产行为。
- 历史证据：`migrationEvidenceTest --test-dry-run` 发现 1,219 项；`partition.json` 验证分类无重叠遗漏。显式运行 `WorkspaceContractTest` 在缺外部夹具时按预期失败，见 `historical-missing-fixture.log`。不宣称 1,219 项实际通过，完整历史业务双后端证据未重跑。
- schema 测试先失败再转绿：`schema-diagnostic-red.log` 的 9 项有 2 项暴露原因丢失；修复后 schema 9 项及生产配置 2 项通过，见 `schema-diagnostic-green.log`。真实 PostgreSQL 18.6 由操作者显式准备，端口 55435，库名 `_test`；测试只在显式 URL 下创建/清理独立 `_test` 子库，不探测或启动数据库。SQLite/PostgreSQL 共用版本拒绝合同；实际第三次建表后失败均完整回滚并可重试，各缺表分支从完整独立库开始。
- 删除前标签在仓库外 `reference-source/` 重放 `uv run --no-dev alembic upgrade head`，分别生成 SQLite 和真实 PostgreSQL 参考库。与当前 Kotlin 实际空库启动结果比较：SQLite 97 个对象及有序列合同一致；PostgreSQL 279 条 DDL 一致。仅规范化约束排序、NOT NULL 自动名称及等价 varchar 数组 text cast，未移除业务约束；`compare-schema.py`、`schema-comparison.json` 及两个 SQL dump 可复核。版本均 `20261030_53`、33 张表。
- 基线 SHA-256：SQLite `ecf472fc9382a3a062c15e2c2f26b6f8766cea200be95252696a270d629608fb`；PostgreSQL `8dcfded333295de63a3535678b78cedfabb2db4f97dd0b7538a5d7119583ea32`。
- `:shared:wasmJsTest`、business-contracts/domain 测试已通过；`python3 compose/tests/test_build_wasm_web.py` 6 项与 `node --test compose/qa/web/tests/*.node-test.mjs` 3 项通过。UI token 生成、8 个保留 Python 文件 AST、工具 `--help` 与 `/health` HTTP 验证通过；不宣称未改动的分析脚本已复算真实账单。
- 实际安装产物 `/health`、注册、登录、session、创建工作区及 accounts 读取均成功；`startup-safety.json` 验证 3 种非法配置在建库前退出及连接失败仅输出安全诊断。用户授权重启 Docker Desktop 后恢复 Docker API。`docker build --tag finance-tracker-retire-qa:20261104 .` 成功，11 个任务实际执行，见 `docker-build.log`；Linux arm64 镜像 ID `sha256:a544a531955d710b177d099bf073ba1e3f3863e4d65f1ba537eb4dabe48b9d6e`。专用容器 `ft-retire-qa-f1f76e1b14` 使用新命名卷，UID 10001 创建 `/data/finance.db`（权限 600），`/health`、注册、登录、session、工作区创建及 accounts 均 200；停机后核对 33 张表和 `20261030_53`，已删除本次容器与数据卷，保留匿名快照、启动日志及 `container-*.json`，未触碰其他容器。本次安装产物 QA 服务（19245/19246）及专用 PostgreSQL 已停止，测试数据与证据保留在仓库外，见 `qa-service-cleanup.json`。
- Web：Chromium、390×844、实际新建 Compose 预览和同源代理，`FT_API_PROXY_ORIGIN=http://127.0.0.1:19245 npm run test:e2e -- compose-live-demo.e2e.ts` 1/1 通过，真实 Kotlin 注册进入工作区，控制台/网络/静态资源错误为空，Wasm MIME 正确；`web-live.log`。对实际容器端口补跑 `FT_API_PROXY_ORIGIN=http://127.0.0.1:19248 npm run test:e2e -- compose-live-demo.e2e.ts`：1/1 通过（15.9 秒），浏览器入口 `http://127.0.0.1:5186`，同样无控制台/请求/静态错误且 Wasm MIME 正确；`web-container-live.log`。
- 完整 Web 回归 183 项：157 通过、26 失败，不记全绿。独立审查 26 份 trace：180 个 API 请求全部 Route.fulfill，未到代理/后端；涉及 UI、共享实现及 fixture 相对比较基线未改动，属于与本次后端变更无关的 UI 测试失败；未逐项完成 UI、测试预期或环境的根因分析。保留 `web-full-results/` 的 trace、截图及错误上下文，受影响真实后端冒烟另行通过。
- `openspec validate --all --strict` 27/27、`openspec doctor`、change strict 校验及 `git diff --check` 通过。CLI 1.7.0、Node v26.8.1。8 份已改文档的 69 个本地链接与 17 个 bash 示例检查无问题，见 `document-check.json`；最终收尾后重查受影响项目。

### 阶段 7：交付条件与反思

实现、独立审查、适用验证及文档核对完成；按用户授权同步主规格、归档并提交 PR。Render 配置/部署与回滚示例已准备，实际部署不在本次执行授权内。标签没有新的 Kotlin Dockerfile；线上回退必须恢复 Python 服务配置，不能直接让当前 Docker 服务选旧标签。

用户选择显式分离历史证据与默认自包含门禁，已写入本 change 的 proposal/design/delta 与使用说明。该选择适用于本次移除外部导出器，现有工程原则已要求证据真实，不新增通用规则。用户已授权归档后创建目标为 `main` 的 PR；据此提交并推送 `origin/continue-kotlin-backend-refactor`，不执行合并或 Render 发布。

收尾核对覆盖 README、后端 README、SKILL、迁移说明、功能地图、主规格、词表、项目上下文及 OpenSpec 技能与配置。无新领域概念，词表无需新增；项目上下文已准确保留 Kotlin 运行时与独立 Python 工具，未新增通用规则。同步两份 delta 并更新归档后的当前文档链接，随后复核受影响链接与严格校验。OpenSpec 配置的旧 Python 运行时描述已改为当前 Kotlin/JVM 21，未改动规则。

两份主规格已按 delta 合并并逐项核验新增、修改、删除及保留场景，再执行 `openspec archive retire-python-backend --yes --skip-specs --json`；该标志仅避免重复合并已经同步的规格。归档路径为 `openspec/changes/archive/2026-11-05-retire-python-backend`。PR 的比较目标为 `main`，范围包含此前 Kotlin 整体迁移；两个账户导入 change 的实现在 main 已存在，本分支只归档其记录，不作为本次新增功能。
