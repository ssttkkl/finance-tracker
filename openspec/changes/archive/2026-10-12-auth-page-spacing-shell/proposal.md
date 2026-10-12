# Material 3 authentication screens

## Why

Compose authentication currently uses hand-built control styling and Compose Unstyled wrappers. The controls do not read as Material 3, and changing their appearance requires maintaining duplicate styling in the authentication code. Login and registration also need one responsive shell and a consistent title-to-first-field position.

## Scope

- Use Material 3 `OutlinedTextField`, `Button`, `TextButton`, and `IconButton` components on Compose login and registration screens only.
- Adopt the standard Material 3 outlined-field presentation: labels rest inside empty fields and float above the outline when focused or populated; field-level feedback uses the `supportingText` slot.
- Use Material 3 small and medium shape defaults locally for authentication controls and desktop cards. Keep FinanceTheme colors and typography, and do not change its global shape scheme or other screens.
- Keep the login and registration first-field positions aligned within each responsive class and retain the registration back target of at least 44 dp.
- Keep the internal `commonMain` responsive authentication shell shared by both modes.
- Preserve authentication behavior, fields, labels, copy, validation, semantics, keyboard flow, and request payloads.

## Non-goals

- Do not migrate Compose Unstyled from other product screens or remove its shared dependency while those screens still use it.
- Do not change authentication APIs, validation rules, session behavior, route behavior, field order, or visible copy.
- Do not change FinanceTheme globally or restyle other screens.
- Do not modify the legacy React Web or Expo authentication implementations in this change.

## Acceptance criteria

- Login and registration use Material 3 controls under FinanceTheme. Authentication code does not supply duplicate control colors, outlines, shapes, pressed styles, or disabled styles.
- Empty fields show their labels inside the outlined control; labels float for focused or populated fields. Field-level validation and helper text use the Material 3 supporting-text position.
- Authentication-local outlined fields and primary buttons use the Material 3 small shape default, and desktop cards use the Material 3 medium shape default. FinanceTheme's global shape scheme remains unchanged.
- Login and registration first fields start at the same vertical position within each responsive class. The registration back target remains 44 dp.
- Both modes use one internal responsive shell in `commonMain` and preserve compact, regular, and wide layout behavior.
- Existing fields, visible text, validation and error behavior, busy/disabled behavior, keyboard semantics, accessibility labels, and semantic test IDs remain intact.

## Risks

- Material 3 floating labels and supporting text change the vertical rhythm of populated and validation states. The revised Pen boards define these positions before implementation.
- The shared Compose Unstyled dependency remains necessary for other screens, so only authentication-specific wrappers and styling are removed.
- A Compose Material 3 local shape override must stay scoped to authentication so FinanceTheme defaults on other pages do not change.
