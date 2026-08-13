## Purpose

为个人和团队账本提供邮箱密码身份、受保护会话及工作区成员角色，使所有工作区内财务数据只能由获授权用户访问与操作。

## ADDED Requirements

### Requirement: 用户使用邮箱和密码建立受保护会话

系统 MUST 支持使用规范化邮箱地址和密码注册、登录与登出。密码 MUST 以强单向哈希保存；浏览器会话 MUST 使用 `HttpOnly`、`Secure`、`SameSite` Cookie，服务端仅保存会话摘要。错误响应不得泄露账户是否存在或任何密码内容。

#### Scenario: 注册并登录

- **WHEN** 使用者提交此前未注册的有效邮箱和符合密码规则的密码
- **THEN** 系统 MUST 创建用户和受保护会话，并返回该用户可访问的工作区信息

#### Scenario: 无效登录

- **WHEN** 使用者提交未知邮箱或错误密码
- **THEN** 系统 MUST 返回相同的未授权错误，且不得创建会话

### Requirement: 工作区成员角色限制数据访问

工作区 MUST 可有多个 `admin`、`editor` 和 `viewer` 成员。`admin` 可以管理成员与邀请，`editor` 可以读取和修改该工作区账本，`viewer` 只能读取。所有 Web 账本请求 MUST 从当前会话选定的成员工作区解析数据，不得信任客户端传入的工作区标识。

#### Scenario: 成员访问自己的工作区

- **WHEN** 已登录 `editor` 选择其成员工作区并请求新增现金流水
- **THEN** 系统 MUST 只在该工作区写入，并沿用既有账本审计和投影规则

#### Scenario: 非成员访问工作区

- **WHEN** 已登录用户选择或请求不是其成员的工作区
- **THEN** 系统 MUST 拒绝请求且不得泄露该工作区账本数据

#### Scenario: viewer 尝试写入

- **WHEN** `viewer` 调用账本写入、导入或关系修改端点
- **THEN** 系统 MUST 返回权限不足错误，且不得改变账本、关系或投影

### Requirement: admin 使用一次性限时邀请链接添加成员

工作区 `admin` MUST 能创建指定为 `editor` 或 `viewer` 的随机邀请链接。邀请 MUST 在接受后失效，并且到期后不能接受；接受者必须先登录。重复接受、过期邀请或非 admin 创建邀请不得授予成员资格。

#### Scenario: 接受有效邀请

- **WHEN** 已登录用户接受由成员工作区 admin 创建、未过期且未使用的 `editor` 邀请
- **THEN** 系统 MUST 将该用户以 `editor` 身份加入该工作区，并使邀请失效

### Requirement: 既有 default 工作区安全归属

迁移后，当邮箱 `admin@ssttkkl.fun` 的用户首次注册时，系统 MUST 将该用户作为既有 `default` 工作区的 `admin`。其他首次注册用户不得仅因注册而获得该既有工作区访问权限。

#### Scenario: 指定管理员注册

- **WHEN** `admin@ssttkkl.fun` 首次完成注册，且 `default` 工作区存在
- **THEN** 系统 MUST 原子地创建或保留其 `default` 工作区的 `admin` 成员关系
