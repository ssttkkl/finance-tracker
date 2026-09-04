# 工作区深链接入口

## ADDED Requirements

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
