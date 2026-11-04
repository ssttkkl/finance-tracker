## ADDED Requirements

### Requirement: Kotlin 管理 schema 初始化和版本门禁

系统 MUST 由 Kotlin 后端负责数据库 schema 初始化与版本校验，不依赖 Python、Alembic 或外部迁移命令。启动时若数据库没有应用表，后端 MUST 在单一事务中创建 `20261030_53` 基线 schema 并写入该版本；非空数据库 MUST 校验完整表集合和版本等于 `20261030_53`，不匹配时 MUST 失败关闭，且不得自动升级、降级、重建或改写现有表。

#### Scenario: 空数据库首次启动
- **WHEN** Kotlin 后端连接到一个没有应用表的 PostgreSQL 或 SQLite 数据库
- **THEN** 它 MUST 创建当前基线 schema、写入 `20261030_53`，并开始提供现有 HTTP 合同

#### Scenario: 已处于当前版本
- **WHEN** 数据库已包含完整应用表且版本为 `20261030_53`
- **THEN** 后端 MUST 只校验并启动，不得执行建表或迁移写入

#### Scenario: 旧版本或未知版本
- **WHEN** 数据库版本不是 `20261030_53`、版本行缺失或表集合不完整
- **THEN** 后端 MUST 失败关闭并返回脱敏的存储错误，不得尝试从旧 revision 升级或修改现有数据

#### Scenario: 建库中途失败
- **WHEN** 基线建库在提交前发生任何错误
- **THEN** 数据库 MUST 不保留部分应用表或部分版本记录，后续启动仍按空库处理

## MODIFIED Requirements

### Requirement: 显式选择唯一运行时数据库

系统 MUST 只通过显式数据库 URL 选择 PostgreSQL 或文件型 SQLite，且一个进程只能使用一个运行时数据库。系统 MUST NOT 自动探测、静默回退、双写或把 CSV、JSON、YAML、Git 文件当作运行时账本。生产入口 MUST 由 Kotlin 后端提供，不再要求 Python 运行时或 Alembic。

#### Scenario: 使用 PostgreSQL
- **WHEN** 数据库 URL 指向可用且已达到当前 schema 版本的 PostgreSQL 数据库
- **THEN** Web 和 Application Service MUST 只在该数据库中读写当前工作区

#### Scenario: 使用 SQLite
- **WHEN** 数据库 URL 指向可用的 SQLite 文件
- **THEN** 同一组用户操作 MUST 使用该文件完成，且不得尝试连接 PostgreSQL

#### Scenario: 缺少或非法配置
- **WHEN** 数据库 URL 缺失、格式非法、数据库不可用或 schema 版本不受支持
- **THEN** 系统 MUST 失败关闭并返回可操作的脱敏错误，且不得创建其他事实源作为回退
