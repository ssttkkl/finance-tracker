## MODIFIED Requirements

### Requirement: Native API origin override is build-gated

Native MUST only expose the API origin override control when `EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED` is `1` or `true` at build time. When the flag is absent or has any other value, the login and registration surface MUST hide the control and use the build address from `EXPO_PUBLIC_FT_API_ORIGIN`; the build address MAY be empty, in which case a request requiring an origin MUST fail before network transmission. When the flag is enabled, the input MUST initially contain the current address, which MAY be empty when no build address or saved override exists.

#### Scenario: Default build hides the override

- **WHEN** a Native build does not enable `EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED`
- **THEN** the authentication surface MUST NOT show an API origin input or reset control
- **AND** authentication requests MUST use the build address, or fail before network transmission when that address is empty

#### Scenario: Test build shows an initially empty override

- **WHEN** a Native build sets `EXPO_PUBLIC_FT_API_ORIGIN_OVERRIDE_ENABLED` to `1` or `true` and does not provide `EXPO_PUBLIC_FT_API_ORIGIN`
- **THEN** the login and registration modes MUST show one shared API origin input
- **AND** the input MUST initially be empty until the user selects an origin or a saved valid override is restored

### Requirement: Native authentication can use a validated origin override

When the debug control is visible, Native MUST validate the entered value as an origin before login or registration. The value MUST use an `https` origin, or a `http` origin with an explicit port when the address override is enabled; paths other than `/`, query strings, fragments, credentials, and empty values MUST be rejected. A rejected value MUST prevent the authentication request and show an actionable validation error.

#### Scenario: Valid address routes authentication

- **WHEN** the user enters a valid HTTPS origin or an HTTP origin with an explicit port and submits login or registration while the override is enabled
- **THEN** the authentication request MUST be sent to that origin
- **AND** the origin MUST be normalized without a trailing slash

#### Scenario: Invalid or empty address blocks submission

- **WHEN** the user submits login or registration with an empty or invalid origin while the override control is visible
- **THEN** Native MUST show an API origin validation error
- **AND** Native MUST NOT send a login or registration request

#### Scenario: Switching authentication mode preserves the address

- **WHEN** the user changes between login and registration while the debug control is visible
- **THEN** the same entered API origin MUST remain selected
- **AND** the selected origin MUST apply to the next authentication submission

### Requirement: Reset and logout preserve the selected configuration semantics

When debug mode is enabled, Native MUST provide a “恢复默认” operation that clears the local address override and returns to the build address, which MAY be empty. If the returned build address is empty, the next authentication submission MUST require a new valid origin. Logout MUST clear the session token and protected session state but MUST NOT clear the selected debug address. When debug mode is disabled, any stored override MUST be ignored.

#### Scenario: Reset returns to an empty build address

- **WHEN** the user activates “恢复默认” and the build address is empty
- **THEN** the input MUST become empty
- **AND** the local address override MUST be cleared

#### Scenario: Logout retains the debug address

- **WHEN** the user logs out after using a debug address
- **THEN** the session token and authenticated session state MUST be cleared
- **AND** a later authentication surface in the same enabled build MUST still use the selected debug address

#### Scenario: Disabled builds ignore historical overrides

- **WHEN** a Native build has debug mode disabled but local storage contains an address override
- **THEN** Native MUST ignore the stored override
- **AND** all requests MUST use the build address without showing the debug control
