# compose-demo-mode Specification

## Purpose

为 Compose 页面开发和跨端 QA 提供可在 Android、iOS 与 Web 启动的真实应用演示环境；它以合成数据运行共享页面，不依赖 API 后端或真实账户凭据。

## Requirements

### Requirement: Compose demo mode is isolated to development and QA builds

Compose MUST provide an explicitly selected demo mode in Android, iOS, and Web development/QA builds. Demo mode MUST use an in-memory session and finance data graph, MUST NOT instantiate the production API client, read the platform token store, access user-selected files, or send requests to an API server. Requests for static resources such as fonts MAY use their configured CDN. Release builds MUST NOT expose a route or control that enters demo mode.

The `FT_COMPOSE_DEMO_MODE` build environment variable MUST populate one shared BuildKonfig value consumed by the Compose targets. When the variable is unset or false, demo mode MUST be disabled. Android and iOS Release entry points and the production Web build MUST force demo mode off, even when the variable is true.

#### Scenario: Start a demo build without an API server

- **WHEN** a developer or QA operator explicitly starts a supported target in demo mode without an API server
- **THEN** the app MUST render the shared Compose application without reading real credentials or sending a request to an API server
- **AND** any static-resource requests MUST remain separate from API-server traffic

#### Scenario: Select demo mode with the build environment

- **WHEN** `FT_COMPOSE_DEMO_MODE=true` is present while building an Android, iOS, or Web development/QA target
- **THEN** that target MUST receive the shared demo-mode value through BuildKonfig
- **AND** Android and iOS Release entry points and the production Web build MUST keep demo mode disabled

#### Scenario: Start a release build

- **WHEN** a user starts a production Android, iOS, or Web build
- **THEN** the app MUST use its normal production entry point
- **AND** no demo-mode launcher, flag, URL, or in-app control MUST make mock data accessible

### Requirement: Demo mode reuses every Compose page and primary flow

Demo mode MUST render the same shared Compose screens used by the production graph, including authentication and workspace access, invitation, cash ledger, cash categories, cash import, investment holdings, investment events, and workspace management. It MUST provide a fixed synthetic signed-in user and writable demo workspace so the main flows can be explored without a scenario picker.

#### Scenario: Explore the main destinations

- **WHEN** a developer or QA operator opens each Compose destination in demo mode
- **THEN** the app MUST render the production Compose screen for that destination with deterministic synthetic data
- **AND** navigation and actions MUST retain the same labels, semantics, and response layout as the production screen

#### Scenario: Exercise authentication and workspace access

- **WHEN** the demo user signs out, submits the authentication form, selects or creates a workspace, or opens a synthetic invitation
- **THEN** the session repository MUST complete the UI flow locally with the fixed demo identity
- **AND** it MUST NOT store credentials or contact an authentication service

### Requirement: Demo mutations remain in memory and reset on restart

Demo repositories MUST reflect supported create, update, classify, delete, workspace, and import actions in the active app session. They MUST initialize from deterministic synthetic seed data, apply the existing ledger filters with exact decimal comparisons, and discard all mutations when the process restarts; demo data MUST NOT be written to production storage.

#### Scenario: Change demo data and restart

- **WHEN** a developer creates, edits, classifies, imports, or deletes demo data, then restarts the app
- **THEN** the current session MUST show the result of the action
- **AND** the next process MUST restore the original synthetic seed data

#### Scenario: Filter seeded ledger records by exact signed amounts

- **WHEN** the developer applies a valid minimum and maximum amount to the demo ledger
- **THEN** the demo repository MUST return only records within the signed range using exact decimal comparison
- **AND** a malformed amount filter MUST use the production ledger's recoverable filter error state

#### Scenario: View an empty or failed UI state

- **WHEN** automated or targeted QA injects an empty result, a loading delay, or a repository failure
- **THEN** the same production Compose loading, empty, disabled, error, and recovery UI MUST be available for validation
- **AND** demo mode MUST NOT add an in-app scenario picker

### Requirement: Demo import uses a built-in synthetic statement

The demo import flow MUST provide a built-in synthetic statement and complete file recognition, scanning, mapping, preview, and commit through the in-memory repositories. It MUST NOT open the system file picker, read a user file, or contact an import service.

#### Scenario: Complete a synthetic statement import

- **WHEN** a developer or QA operator starts import in demo mode and selects the built-in synthetic statement
- **THEN** the shared import screen MUST progress through recognition, scan, account mapping, preview, relation review, and commit
- **AND** the committed synthetic records MUST appear in the current in-memory demo session

#### Scenario: Cancel or retry synthetic import

- **WHEN** the operator cancels or retries the demo import flow
- **THEN** the import screen MUST keep its production cancellation, retry, loading, error, and success semantics
- **AND** it MUST not read or upload an external file
