# 运行时数据库

## Purpose

定义 Finance Tracker 在 PostgreSQL 与 SQLite 上运行时必须保持的数据库选择、账务一致性、事务、安全和错误合同。

## Requirements

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

### Requirement: 双后端保持账务和审计等价
相同工作区和相同有效输入在 PostgreSQL 与 SQLite 上 MUST 产生等价的账户、现金流水、投资事件、交易关系、投影和查询结果。金额、数量、汇率和成本 MUST 使用精确十进制语义，数据库选择不得改变币种、精度、幂等、来源追踪或逻辑删除结果。

#### Scenario: 比较确定性业务结果
- **WHEN** 在两个后端执行同一组确定性账户、导入、关系和查询操作
- **THEN** 除数据库内部代理键外，规范化业务结果 MUST 完全一致

#### Scenario: 拒绝非法十进制值
- **WHEN** 输入包含非有限值、超过支持范围或超过 18 位小数的账务数值
- **THEN** 两个后端 MUST 在提交前拒绝该操作，且不得发布部分结果

### Requirement: 写入和派生发布保持事务原子性
所有写入、批量导入、关系决定和投影发布 MUST 在明确的事务边界内完成。异常、业务拒绝、锁等待超时或来源校验失败 MUST NOT 发布部分账本记录、部分关系或不可配套的投影版本。

#### Scenario: 导入中途失败
- **WHEN** 一个导入批次中的任一业务行在正式提交前验证失败
- **THEN** 数据库 MUST 不包含该批次产生的任何新账本记录

#### Scenario: 投影发布竞争
- **WHEN** 投影构建期间源账本或已确认关系发生变化
- **THEN** 系统 MUST 拒绝发布过期结果或重新构建，不得把混合版本暴露给使用者

### Requirement: 工作区和数据库错误保持隔离
系统 MUST 在两个后端实施相同的工作区必填、存在性和隔离合同。连接配置、错误和日志 MUST 使用脱敏摘要；SQLite 新建文件及辅助文件 MUST 使用仅所有者可访问的权限，并启用外键约束和有界锁等待。

#### Scenario: 跨工作区访问
- **WHEN** 请求尝试读取或修改不属于当前工作区的账户或账本记录
- **THEN** 两个后端 MUST 拒绝访问，且不得泄露目标对象是否存在

#### Scenario: SQLite 锁竞争
- **WHEN** SQLite 写锁在约定的有界等待时间内无法获得
- **THEN** 系统 MUST 返回稳定的可重试错误，且不得静默丢失写入

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
