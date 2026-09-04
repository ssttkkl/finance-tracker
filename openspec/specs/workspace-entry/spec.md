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
