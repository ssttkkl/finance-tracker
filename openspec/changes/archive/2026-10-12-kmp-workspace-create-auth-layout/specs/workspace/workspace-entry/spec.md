## MODIFIED Requirements

### Requirement: Native workspace entry follows the Web access presentation

Android、iOS 和 KMP desktop 的工作区入口 MUST 保持 Web 已验证的标题、字段标签、工作区访问与创建结果、错误处理和成功导航语义。页面可以使用 Compose 原生控件；KMP 首次创建状态 MUST 采用与登录/注册页一致的 Material 3 表单层级，并按 `COMPACT`、`REGULAR`、`WIDE` 逻辑窗口适配。已认证使用者没有工作区时，首次创建页 MUST 显示财务账本、当前邮箱、退出登录、一个「创建工作区」标题、工作区名称字段和主创建操作，不得显示工作区选择列表或无效返回操作。KMP 此处的布局差异登记为 Native presentation 差异；Web 与 Expo 维持现有布局，所有平台继续共享文案、校验、错误、禁用和成功导航语义。KMP 页面 MUST 按当前 `FinanceTheme` 适配明暗主题。工作区访问失败 MUST 不得误显示为无工作区，未完成创建 MUST 不得标记为成功。

#### Scenario: Restore an existing workspace on Native

- **WHEN** Native 恢复会话并收到至少一个可访问工作区
- **THEN** 客户端 MUST 使用活动工作区或按 Web 规则选择第一个工作区后进入收支账本
- **AND** 选择失败时 MUST 显示可重试的工作区访问错误

#### Scenario: Create a workspace on a narrow or wide device

- **WHEN** 已认证使用者在 KMP `COMPACT`、`REGULAR` 或 `WIDE` 窗口首次创建工作区
- **THEN** 页面 MUST 保留财务账本、邮箱与退出登录，并在单列表单卡片中呈现一个「创建工作区」标题、工作区名称字段和主创建操作
- **AND** 首次创建页面 MUST 隐藏工作区选择列表和返回操作；布局 MUST 适配逻辑窗口宽度，不得出现横向滚动
- **AND** 名称字段、创建结果、校验/请求错误及成功进入账本的语义 MUST 与 Web 合同一致

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

#### Scenario: Preserve the existing-workspace creation path

- **WHEN** 已认证使用者已有至少一个可访问工作区并进入工作区选择或创建另一个工作区
- **THEN** Native MUST 保留现有工作区列表、选择操作和创建另一个工作区的表单入口
- **AND** 首次创建专用卡片 MUST 不替换已有工作区使用者的现有页面结构
