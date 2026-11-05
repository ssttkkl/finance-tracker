## Why

Kotlin 后端已完成接口与业务规则迁移并通过双后端契约验证，但生产入口、数据库迁移和运维工具仍由 Python 承担。保留两套后端会持续产生契约漂移、重复测试和部署歧义；现在需要把 Kotlin 变为唯一生产后端，并从仓库移除 Python 运行时与其历史 Alembic 迁移链。

## What Changes

- **BREAKING** Kotlin 后端成为唯一生产入口：移除 `FT_KOTLIN_PREVIEW` 门禁，支持 Render 的 `PORT`/`FT_HOST`、`FT_DATABASE_URL`（含 `postgresql://`）与 `/health`，并提供后端 `Dockerfile` 和只声明 Web Service 的 `render.yaml`。
- **BREAKING** Kotlin 接管 schema 生命周期：删除 Alembic 历史迁移，Kotlin 在空数据库启动时执行 `20261030_53` 基线 DDL；非空数据库只校验当前版本并失败关闭。已有数据库不提供从旧 revision 升级的路径。
- **BREAKING** 删除 Python 后端运行时、后端测试、Alembic 迁移与依赖：`src/`、`tests/`、`migrations/`、`alembic.ini`、`.python-version` 及依赖它们的脚本。
- 删除依赖已退役 Python 后端的 177 个 `export_*` 迁移黄金导出器、迁移台账/历史分析脚本和人工数据质量脚本；保留不依赖 `ft` 的 Python 工具（UI token 生成、Web 构建器及其测试、HTTP 验证脚本）。回退点是 `pre-python-backend-removal` Git 标签。
- 脚本不做 Kotlin 迁移，Python 仅保留为与后端无关的构建/工具语言；其依赖配置裁剪为工具所需。
- 更新 CI、README、后端说明、功能地图、词表与当前主规格，移除 Python 运行命令与 `src/ft` 链接；归档变更保持历史原样。
- 迁移台账与双后端证据保留在 Git 历史与归档变更中，不再作为当前运行入口。依赖外部夹具的历史 Kotlin 迁移证据测试保留为显式任务；默认 `backend:test` 与 CI 执行全部自包含测试，并以显式 PostgreSQL 测试库完成本次 schema 双后端验证。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `shared/runtime-database`: 数据库选择仍由显式 URL 决定，但 schema 初始化改为 Kotlin 基线建库与当前版本校验；不再依赖 Alembic 或 Python 运行时。
- `shared/kotlin-backend-parity`: 生产入口、部署配置、测试与工具链以 Kotlin 为唯一实现；删除 Python 对照、Alembic 历史测试和旧运行时命令，同时保留现有用户可见合同。

## Impact

- 代码：删除 `src/ft`、`tests`、`migrations`、Alembic 配置及依赖 `ft` 的脚本；保留非后端 Python 工具与最小依赖配置；新增 Kotlin schema 基线与启动引导、Docker/Render 配置。
- 数据：数据库 schema 不变；空库由 Kotlin 建到 `20261030_53`，已在 head 的库直接使用。无数据迁移、无双写、无自动旧版本升级。
- 构建与 CI：Kotlin 后端、共享层与 Web 构建成为必需门禁；移除 Python 后端测试、性能与迁移 job，保留 Node/Compose 与工具脚本 job。
- 运维：Render Web Service 使用环境变量注入数据库与 Web origin；实际发布需单独授权。
- 回滚：删除前打 `pre-python-backend-removal` 标签；回滚通过恢复该标签并重新部署 Python 入口，数据库 schema 保持兼容。
- 非目标：不改变财务语义、API 路径、DTO、错误码或 UI；不重写归档 OpenSpec 历史；本变更不执行真实 Render 发布。
