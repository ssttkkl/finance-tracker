## Purpose

让 Native 使用者在明确开启的调试构建中，于登录前选择要连接的后端 origin，便于在同一构建中联调不同后端实例，同时保持默认构建和正式环境的安全边界。

## ADDED Requirements

### Requirement: Native API origin override is build-gated

Native MUST only expose the API origin override control when `EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED` is `1` or `true` at build time. When the flag is absent or has any other value, the login and registration surface MUST hide the control and use the build address from `EXPO_PUBLIC_FT_API_ORIGIN`.

#### Scenario: Default build hides the override

- **WHEN** a Native build does not enable `EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED`
- **THEN** the authentication surface MUST NOT show an API origin input or reset control
- **AND** authentication requests MUST use the build address

#### Scenario: Debug build shows the override

- **WHEN** a Native build sets `EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED` to `1` or `true`
- **THEN** the login and registration modes MUST show one shared API origin input
- **AND** the input MUST initially contain the current address

### Requirement: Native authentication can use a validated origin override

When the debug control is visible, Native MUST validate the entered value as an origin before login or registration. The value MUST use the existing Native origin rules: `https` origins are allowed; `http` origins remain limited to non-production builds with an explicit port; paths other than `/`, query strings, fragments, credentials, and empty values MUST be rejected. A rejected value MUST prevent the authentication request and show an actionable validation error.

#### Scenario: Valid address routes authentication

- **WHEN** the user enters a valid origin and submits login or registration
- **THEN** the authentication request MUST be sent to that origin
- **AND** the origin MUST be normalized without a trailing slash

#### Scenario: Invalid address blocks submission

- **WHEN** the user enters an empty or invalid origin and submits login or registration
- **THEN** Native MUST show an API origin validation error
- **AND** Native MUST NOT send a login or registration request

#### Scenario: Switching authentication mode preserves the address

- **WHEN** the user changes between login and registration while the debug control is visible
- **THEN** the same entered API origin MUST remain selected
- **AND** the selected origin MUST apply to the next authentication submission

### Requirement: Successful debug authentication persists the selected address locally

When the debug control is enabled, Native MUST persist only the validated origin used by a successful login or registration to controlled local device storage. The origin MUST not be sent to the server as an additional field and MUST not be written to application logs.

#### Scenario: Successful authentication saves the origin

- **WHEN** login or registration succeeds using a validated debug address
- **THEN** the address MUST be available as the current Native API origin
- **AND** the address MUST be saved for the next launch

#### Scenario: Failed authentication does not replace the saved origin

- **WHEN** login or registration fails after the user enters a different valid debug address
- **THEN** Native MUST leave the existing saved address unchanged
- **AND** the entered address MAY remain selected for an immediate retry without being persisted

#### Scenario: Relaunch restores the saved origin

- **WHEN** a debug-enabled Native build starts with a previously saved valid address
- **THEN** Native MUST restore that address before its first session request
- **AND** the authentication surface MUST prefill the restored address

### Requirement: Reset and logout preserve the selected configuration semantics

When debug mode is enabled, Native MUST provide a “恢复默认” operation that clears the local address override and returns to the build address. Logout MUST clear the session token and protected session state but MUST NOT clear the selected debug address. When debug mode is disabled, any stored override MUST be ignored.

#### Scenario: Reset returns to the build address

- **WHEN** the user activates “恢复默认”
- **THEN** the input MUST show the build address
- **AND** the local address override MUST be cleared

#### Scenario: Logout retains the debug address

- **WHEN** the user logs out after using a debug address
- **THEN** the session token and authenticated session state MUST be cleared
- **AND** a later authentication surface in the same enabled build MUST still use the selected debug address

#### Scenario: Disabled builds ignore historical overrides

- **WHEN** a Native build has debug mode disabled but local storage contains an address override
- **THEN** Native MUST ignore the stored override
- **AND** all requests MUST use the build address without showing the debug control
