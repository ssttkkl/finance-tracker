# workspace-entry Specification

## Purpose

确保认证完成后的工作区入口与账户实际可访问的工作区状态一致，让已有工作区的使用者直接进入账本，让首次使用者在没有可返回目标时完成首次创建；同时支持通过工作区前缀 URL 直接打开已有工作区及其子页面。

## Requirements

### Requirement: 已有工作区的认证会话必须进入可用工作区

登录或恢复会话返回至少一个工作区时，Web 界面 MUST 使用会话提供的活动工作区；如果活动工作区为空，界面 MUST 选择返回列表中的第一个可访问工作区后进入账本，不得把该账户当作无工作区用户展示创建页。

#### Scenario: 登录已有工作区的账户

- **WHEN** 登录响应包含至少一个工作区且 `active_workspace_id` 为空
- **THEN** 界面 MUST 选择列表中的第一个工作区并显示该工作区的账本页面

#### Scenario: 恢复已有工作区的会话

- **WHEN** 页面刷新后会话包含至少一个工作区且 `active_workspace_id` 为空
- **THEN** 界面 MUST 选择列表中的第一个工作区并显示账本页面

#### Scenario: 自动选择工作区失败

- **WHEN** 会话包含至少一个工作区且 `active_workspace_id` 为空，但选择第一个工作区的请求失败
- **THEN** 界面 MUST 显示可重试的工作区访问错误，且不得显示创建工作区表单

### Requirement: 无工作区时创建页不得提供无效返回操作

账户没有任何工作区时，Web 界面 MUST 进入创建工作区页面；该首次创建页面 MUST 不显示返回按钮。已有工作区的使用者从工作区切换器主动进入创建流程时，创建页 MUST 保留返回到账本的操作。

#### Scenario: 首次创建工作区

- **WHEN** 已认证会话的 `workspaces` 为空且 `active_workspace_id` 为空
- **THEN** 界面 MUST 显示创建工作区表单且不得渲染返回按钮

#### Scenario: 已有工作区后创建另一个工作区

- **WHEN** 使用者从已有工作区的工作区切换器选择创建工作区
- **THEN** 界面 MUST 显示创建工作区表单和可返回当前账本的返回按钮

### Requirement: Static hosting serves client routes

The deployed static web site MUST rewrite unknown frontend paths, including `/w/<workspace-id>/` and workspace child paths, to the application entry document so that client-side routing can run.

#### Scenario: Direct workspace root request

- **WHEN** a browser requests `/w/workspace-1/` from the deployed web site
- **THEN** the web site returns the application entry document instead of a `Not Found` response

#### Scenario: Direct workspace child request

- **WHEN** a browser requests `/w/workspace-1/cash-categories` from the deployed web site
- **THEN** the web site returns the application entry document instead of a `Not Found` response

### Requirement: Client restores the requested workspace

The web application MUST parse the workspace ID from a workspace-prefixed URL, select that workspace through the existing access boundary when necessary, and render the requested child page.

#### Scenario: Accessible workspace root

- **GIVEN** the signed-in user can access `workspace-1`
- **WHEN** the application starts at `/w/workspace-1/`
- **THEN** it renders the ledger for `workspace-1` and keeps the browser URL workspace-prefixed

#### Scenario: Accessible workspace child page

- **GIVEN** the signed-in user can access `workspace-1`
- **WHEN** the application starts at `/w/workspace-1/cash-categories`
- **THEN** it selects `workspace-1` if needed and renders cash category management

#### Scenario: Inaccessible workspace

- **GIVEN** selecting the requested workspace is rejected by the access boundary
- **WHEN** the application starts at a URL for that workspace
- **THEN** it does not bypass access control, shows an actionable error, and normalizes to the current valid workspace URL

### Requirement: Workspace navigation preserves URL scope

When a workspace is active, client-side navigation MUST keep the active workspace prefix while changing the child route.

#### Scenario: Navigate from workspace ledger to a child page

- **GIVEN** the active URL is `/w/workspace-1/`
- **WHEN** the user opens cash category management
- **THEN** the URL becomes `/w/workspace-1/cash-categories` and the page changes without a full document request

### Requirement: 会话恢复期间不展示登录表单

Web MUST 根据本机是否存在登录令牌区分直接登录和会话恢复：有令牌时，在认证会话确认完成前 MUST 展示中性的「加载中...」恢复加载态；没有令牌时 MUST 直接展示登录表单。

#### Scenario: 刷新时等待已保存会话

- **WHEN** 使用者刷新页面且浏览器存在登录令牌，但会话请求尚未完成
- **THEN** Web MUST 展示「加载中...」，不得展示邮箱、密码、登录或注册控件

#### Scenario: 没有登录令牌时启动

- **WHEN** 使用者打开页面且浏览器没有登录令牌
- **THEN** Web MUST 直接展示登录表单，且不得为恢复会话发起请求

#### Scenario: 登录令牌无效

- **WHEN** 会话请求返回 `authentication_required` 或 HTTP `401`
- **THEN** Web MUST 停止恢复并展示登录表单

#### Scenario: 暂时性恢复失败后成功

- **WHEN** 前两次会话请求因网络或服务端暂时失败，第三次请求成功
- **THEN** Web MUST 进入认证会话对应的工作区页面，且整个恢复过程最多发起 3 次请求

#### Scenario: 三次恢复均失败

- **WHEN** 3 次会话请求均因网络或服务端暂时失败
- **THEN** Web MUST 停止恢复并展示登录表单，且不得展示工作区内容

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
