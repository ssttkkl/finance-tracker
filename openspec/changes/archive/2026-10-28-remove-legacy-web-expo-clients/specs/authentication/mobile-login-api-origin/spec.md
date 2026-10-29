## REMOVED Requirements

### Requirement: Native API origin override is build-gated

**Reason**: This requirement describes an Expo login control and its `EXPO_PUBLIC_*` configuration, which are removed with the Expo client.
**Migration**: Compose Native continues to use the build-time `FT_API_ORIGIN`; it does not provide a login-time API origin override.

### Requirement: Native authentication can use a validated origin override

**Reason**: Origin validation for the removed Expo login control is no longer an available user flow.
**Migration**: Configure Compose Native's API origin in the build environment before building the application.

### Requirement: Successful debug authentication persists the selected address locally

**Reason**: The selected debug address and its Expo local persistence are removed with the Expo client.
**Migration**: Compose Native reads its API origin from the build configuration and does not restore an Expo address override.

### Requirement: Reset and logout preserve the selected configuration semantics

**Reason**: Reset and logout behavior for the removed Expo address override no longer applies.
**Migration**: Compose Native uses the configured build-time API origin; no run-time reset or address selection is available.
