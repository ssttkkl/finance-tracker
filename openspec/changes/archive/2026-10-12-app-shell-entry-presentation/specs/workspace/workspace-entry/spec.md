## MODIFIED Requirements

### Requirement: Native workspace entry follows the Web access presentation

Compose Web、Android、iOS 和 KMP desktop 的工作区入口 MUST 保持既有标题、字段标签、工作区访问与创建结果、错误处理和成功导航语义。上述 Compose targets MUST 使用与登录/注册和未登录邀请页一致的响应式入口容器与居中单列表面；`COMPACT` MUST 保持可纵向滚动并显示宽度不超过 `440` dp 的居中表单卡片，`REGULAR` 和 `WIDE` MUST 将表单卡片居中；已有工作区选择和首次创建 MUST 在同一视觉壳中保留各自内容，不得互相替代。入口 MUST 按 `COMPACT`、`REGULAR`、`WIDE` 逻辑窗口适配，沿用 `FinanceTheme` 的明暗主题。旧 React Web 与 Expo 的布局维持不变；所有平台继续共享文案、校验、错误、禁用和成功导航语义。工作区访问失败 MUST 不得误显示为无工作区，未完成创建 MUST 不得标记为成功。

#### Scenario: Restore an existing workspace on Compose

- **WHEN** Compose 客户端恢复会话并收到至少一个可访问工作区
- **THEN** 客户端 MUST 使用活动工作区或按现有规则选择第一个工作区后进入收支账本
- **AND** 选择失败时 MUST 显示可重试的工作区访问错误

#### Scenario: Create a workspace on a narrow or wide window

- **WHEN** 使用者在 Compose `COMPACT`、`REGULAR` 或 `WIDE` 窗口首次创建工作区
- **THEN** 页面 MUST 保留财务账本、邮箱与退出登录，并在与登录页一致的入口布局中呈现单列表单卡片（`COMPACT`、`REGULAR`、`WIDE` 均居中，`COMPACT` 可纵向滚动）
- **AND** 卡片 MUST 包含一个「新工作区」标题、工作区名称字段和「创建工作区」主操作
- **AND** 首次创建页面 MUST 隐藏工作区选择列表和无效返回操作，且不得出现横向滚动
- **AND** 名称字段、创建结果、校验/请求错误及成功进入账本的语义 MUST 保持不变

#### Scenario: Submit an empty workspace name

- **WHEN** 名称为空或仅包含空白字符时，使用者提交首次创建表单
- **THEN** 创建 MUST 不向服务端提交无效名称，并在字段附近显示名称校验错误
- **AND** 表单 MUST 保留可编辑和再次提交能力

#### Scenario: Create a workspace while the request is pending

- **WHEN** 使用者提交有效名称且创建请求尚未完成
- **THEN** 名称字段和创建操作 MUST 显示禁用状态，主操作 MUST 显示创建中状态
- **AND** 页面 MUST 不得提前显示成功或离开工作区创建流程

#### Scenario: Retry after a workspace creation failure

- **WHEN** 服务端拒绝创建或请求失败
- **THEN** 页面 MUST 保留使用者输入并显示可读的请求错误
- **AND** 使用者 MUST 能修正名称或再次提交

#### Scenario: Enter the ledger after a workspace is created

- **WHEN** 服务端确认创建并返回包含活动工作区的会话
- **THEN** 客户端 MUST 使用该会话进入对应工作区的收支账本
- **AND** 页面 MUST 不停留在创建表单并伪造本地成功结果

#### Scenario: Select or create a workspace when memberships already exist

- **WHEN** 使用者已有至少一个可访问工作区并打开工作区入口
- **THEN** 页面 MUST 在共享入口容器内保留可访问工作区列表、选择操作和创建另一个工作区的入口
- **AND** 页面 MUST 保留当前工作区恢复错误及重试操作
- **AND** 首次创建表单 MUST NOT 替代该使用者的工作区列表和选择流程
