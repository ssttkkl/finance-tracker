## 1. 思考与基线准备

- [ ] 1.1 阅读 `shared/runtime-database`、`shared/kotlin-backend-parity`、`compose/backend/README.md`、CI 工作流和保留脚本，确认删除边界；记录到本文件第 5 阶段。
- [ ] 1.2 在删除前创建 `pre-python-backend-removal` 标签，并确认目标数据库均已在 `20261030_53`。
- [x] 1.3 用临时空 SQLite 与 PostgreSQL 执行 `alembic upgrade head`，导出 `sqlite-baseline.sql` 与 `postgresql-baseline.sql`；剔除 SQLite `sqlite_sequence`/`account_lifecycle_events__new`，写入 `alembic_version = 20261030_53`。
- [x] 1.4 对比两种方言的表集合与必需表清单，确认 33 个 public 表覆盖 Kotlin `requiredTables`，记录导出命令与哈希。

## 2. 计划与任务拆分

- [x] 2.1 在 `design.md` 记录基线生成、启动引导、Render 配置、删除边界和回滚方案（已完成，核对实现不偏离）。
- [ ] 2.2 列出将删除的脚本与保留脚本，确认保留脚本不 `import ft`；记录清单和理由。

## 3. 构建：Kotlin schema 与生产入口

- [x] 3.1 先写失败测试：空 SQLite 建库后必需表与版本齐全；二次启动不重复建表；残缺库/旧版本失败关闭且无写入。
- [x] 3.2 先写失败测试：显式 PostgreSQL 空库建库与版本校验；未配置 `FT_TEST_POSTGRES_URL` 时按未完成记录，不自动探测。
- [x] 3.3 实现 `SchemaBootstrap`/`Database.bootstrapSchema()`，按方言执行基线 SQL，PostgreSQL 使用事务级 advisory lock，SQLite 使用 `mode=rwc` 与 `BEGIN IMMEDIATE`。
- [x] 3.4 更新 `Main.kt`：移除预览门禁，支持 `FT_DATABASE_URL`/`DATABASE_URL`、`postgresql://`、`PORT`/`FT_KOTLIN_PORT`、`FT_HOST`，启动时执行建库/校验。
- [x] 3.5 在路由加入无业务数据的 `/health`，验证未认证可访问。
- [x] 3.6 更新 jOOQ 生成器的输入从 `migrations/` 改为 PostgreSQL 基线文件，保留显式 `_test` 数据库约束。

## 4. 构建：删除 Python 后端与绑定脚本

- [ ] 4.1 删除 `src/`、`tests/`、`migrations/`、`alembic.ini`、`compose/test-migration.json` 和 Python 后端缓存。
- [ ] 4.2 删除所有 `import ft` 的脚本（含 177 个 `export_*`、迁移台账/检查、`run_backend_tests.py`、`migrate_*`、`review_*`、依赖 `ft` 的分析脚本）并复核无残留引用。
- [ ] 4.3 保留并验证非后端 Python 工具：`generate_ui_tokens.py`、`compose/build-wasm-web.py`、`compose/tests/test_build_wasm_web.py`、`verify_kotlin_http.py` 及不依赖 `ft` 的分析工具。
- [ ] 4.4 裁剪 `pyproject.toml`/`uv.lock` 为工具依赖，移除 FastAPI/SQLAlchemy/Alembic/数据库驱动和后端测试配置；验证保留工具可运行。
- [ ] 4.5 更新 CI：删除 Python 后端测试、性能、迁移 job；保留 UI token、Web 构建器测试与 Compose/Node 门禁；确认干净检出不需要 Python 后端。

## 5. 构建：Docker 与 Render

- [x] 5.1 新增根目录多阶段 `Dockerfile`，JDK 21 构建 `:backend:installDist`，JRE 21 运行，非 root 用户，暴露平台端口。
- [x] 5.2 新增 `render.yaml`，只声明 Docker Web Service，`FT_HOST=0.0.0.0`、`/health`、`FT_DATABASE_URL`/`FT_WEB_ORIGIN` 由环境变量注入。
- [ ] 5.3 本地构建镜像并以 SQLite 启动，验证 `/health`、注册/登录与一个真实业务读请求；停止并清理容器。
- [ ] 5.4 记录 Render 环境变量、部署命令、首次建库日志和回滚命令；实际发布等待用户授权。

## 6. 审查

- [ ] 6.1 5a 范围/工程审查：删除边界、schema 等价、启动失败路径、密钥脱敏、回滚；按严重度记录 finding 与处置。
- [ ] 6.2 5b `$codebase-degradation-review`：契约/UI 专项按范围调度 `$contract-design-review`、`$ui-reuse-review`，记录结论与未覆盖项。

## 7. 测试与 QA

- [ ] 7.1 运行 Kotlin 后端全量测试、共享层测试、business-contracts/domain 测试；修复失败。
- [ ] 7.2 在显式 `FT_TEST_POSTGRES_URL` 的 `_test` 库运行 schema 与双后端契约矩阵；未配置则记录未完成，不宣称通过。
- [ ] 7.3 运行 Web 构建、Compose Web E2E 冒烟与 live 用例（Kotlin 后端 + 同源代理），记录浏览器、视口、步骤和错误。
- [ ] 7.4 运行 `openspec validate --all --strict`、`git diff --check`、脚本引用扫描和镜像启动检查。
- [ ] 7.5 复核 README、后端说明、功能地图、词表、技能与 `openspec/project-context.md`，移除 Python 后端运行表述并保留非后端 Python 工具说明。

## 8. 发布与归档

- [ ] 8.1 按可验证小步提交推送；删除前标签与删除提交均推送到同一目标分支。
- [ ] 8.2 归档 `retire-python-backend`，同步 `shared/runtime-database` 与 `shared/kotlin-backend-parity` 主规格，确认 `No active changes found`。
- [ ] 8.3 记录交付证据、HEAD、验证命令、未执行项（真实 Render 发布、未配置 PostgreSQL 矩阵）与回滚入口。
