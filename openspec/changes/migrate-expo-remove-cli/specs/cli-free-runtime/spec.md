## Purpose

删除承担多重职责的 `ft` CLI 后，为本地开发、服务启动和用户侧财务能力提供清晰、可验证且不依赖
隐式兼容层的运行入口，确保 CLI 删除不会造成账本能力、数据安全或部署流程的静默退化。

## ADDED Requirements

### Requirement: The backend has an explicit non-CLI startup entry

项目 MUST 保留 FastAPI 应用工厂，并提供不依赖 `ft` 命令的显式后端启动方式；启动方式 MUST 明确要求调用方选择
`FT_DATABASE_URL`，不得自动探测、静默回退或双写 SQLite 与 PostgreSQL。

#### Scenario: A developer starts the API without the CLI

- **WHEN** 开发者按项目文档使用显式 Uvicorn 或项目开发脚本启动服务
- **THEN** 服务通过 FastAPI 应用工厂启动并提供 `/api/v1`，其数据库连接来自显式配置的 `FT_DATABASE_URL`

#### Scenario: Database selection is missing

- **WHEN** 开发者未提供 `FT_DATABASE_URL` 启动后端
- **THEN** 启动失败并给出配置错误，不自动创建或切换到另一个数据库后端

### Requirement: The ft CLI public surface is removed

发布的 Python 项目 MUST 不再注册 `ft` 可执行脚本，不再提供 CLI 分发模块、CLI 专属依赖或通过 CLI 暴露的隐藏兼容别名；
删除范围 MUST 包括启动、记账、导入、关系、投资、同步、报表和投影运维等现有命令入口。

#### Scenario: The legacy executable is invoked after migration

- **WHEN** 使用者在完成迁移的环境中调用 `ft`
- **THEN** 项目不提供该可执行文件，并指向显式后端启动方式或 Web/Expo 客户端，而不是执行旧命令或静默转发

#### Scenario: A package is installed for a clean environment

- **WHEN** 在没有历史安装残留的干净环境安装项目 Python 包
- **THEN** 安装结果不包含 `ft` script，且 Python 包中不存在可作为产品入口的 CLI 模块

### Requirement: User-facing CLI capabilities have a replacement surface

CLI 删除前，用户侧的账户、现金记账、转账、投资操作、账单导入、关系审查、查询、报表、事实删除和同步能力 MUST
能够通过现有或新增的 Web/API/Expo 流程完成；投影重建等运维动作 MUST 使用显式后端脚本或受控 API，不能要求用户恢复 CLI。

#### Scenario: A user completes a former CLI workflow

- **WHEN** 用户需要执行原 CLI 支持的账户、现金流水、导入、关系或投资操作
- **THEN** 用户可以在 Web 或 Expo 流程中完成同一业务操作，且使用相同的工作区隔离、权限、精度、来源溯源和幂等规则

#### Scenario: An operator rebuilds a projection

- **WHEN** 运维人员需要执行投影重建或查看重建状态
- **THEN** 运维人员使用文档列出的显式后端脚本或受控 API，并获得成功、失败和数据库配置错误，而不是依赖用户侧客户端或已删除的 CLI

### Requirement: Documentation and verification no longer depend on the CLI

项目开发文档、自动化测试、发布脚本和示例 MUST 使用新的后端、Web 和 Expo 入口；仓库不得保留会让开发者误以为
`ft` 仍受支持的活跃文档或测试引用。

#### Scenario: A contributor follows the quick start

- **WHEN** 新贡献者按快速开始文档启动后端、Web 和 Expo 客户端
- **THEN** 每个启动命令都不调用 `ft`，并能获得对应的健康检查或开发页面

#### Scenario: The migration gate scans the repository

- **WHEN** 迁移验收运行 CLI 注册、导入和活跃文档引用检查
- **THEN** 检查确认没有 `ft` script、产品 CLI 导入或未迁移的活跃入口；历史变更记录可保留为审计证据
