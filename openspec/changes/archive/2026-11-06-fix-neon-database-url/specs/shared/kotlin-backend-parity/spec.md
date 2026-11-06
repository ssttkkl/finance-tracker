## MODIFIED Requirements

### Requirement: Kotlin 是唯一生产入口

系统 MUST 以 Kotlin 后端作为唯一生产运行时，并从仓库移除对 Python 后端、Alembic 和 Python 后端测试的启动、验证及部署依赖。后端 MUST 接受平台提供的端口与可配置绑定地址，使用显式数据库 URL 和 Web origin，并提供无需业务数据的健康检查；缺少必需配置时 MUST 失败关闭。

#### Scenario: Render 启动
- **WHEN** Render 通过环境变量提供端口、数据库 URL 和 Web origin
- **THEN** Kotlin 后端 MUST 绑定公开地址、完成 schema 校验并接受现有客户端请求

#### Scenario: 健康检查
- **WHEN** 平台请求健康检查路径
- **THEN** 后端 MUST 在不读取业务数据的情况下返回成功

#### Scenario: 缺少必需配置
- **WHEN** 数据库 URL、端口或 Web origin 缺失或非法
- **THEN** 后端 MUST 拒绝启动，不自动探测数据库或回退到其他事实源

#### Scenario: 仓库不再需要 Python
- **WHEN** 开发者在干净检出中构建、测试或部署后端
- **THEN** 流程 MUST 只使用 Kotlin、JVM、Node 与 Compose 工具链，不要求 Python、Alembic 或 Python 后端测试

#### Scenario: 默认后端门禁独立运行
- **WHEN** 开发者或 CI 在干净检出中执行默认 Kotlin 后端测试
- **THEN** 系统 MUST 执行全部自包含测试，并使用显式 `_test` PostgreSQL 配置完成 schema 双后端验证，不依赖已删除的 Python 导出器或仓库外历史迁移夹具

#### Scenario: 显式执行历史迁移证据
- **WHEN** 开发者选择执行依赖外部夹具的历史迁移证据测试
- **THEN** 系统 MUST 提供独立的显式任务并保留原断言；缺少必需夹具时 MUST 报告失败或未完成，不得冒称默认测试已执行这些历史证据

#### Scenario: PostgreSQL 连接安全参数
- **WHEN** 显式 PostgreSQL URL 携带 `sslmode` 或 `channel_binding`
- **THEN** 后端 MUST 保留合法参数的加密、证书校验和通道绑定语义；缺省时沿用驱动默认值，不静默移除、忽略或降低已指定要求

#### Scenario: 非法连接参数
- **WHEN** URL 包含未知、重复、空值或非法连接参数，或不可解析的编码
- **THEN** 后端 MUST 在连接前以安全的 `storage.config` 拒绝启动，不记录连接串、用户名、密码或原始参数正文；SQLite 不接受这些 PostgreSQL 参数
