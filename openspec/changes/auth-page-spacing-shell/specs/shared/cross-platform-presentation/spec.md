## ADDED Requirements

### Requirement: Compose authentication screens use the scoped Material 3 appearance

Compose login and registration screens MUST use the corresponding Material 3 outlined text field, primary button, text button, and icon button controls under the existing FinanceTheme. They MUST retain FinanceTheme colors and typography. The standard Material 3 small shape MUST be scoped to authentication fields and primary buttons, and the standard medium shape MUST be scoped to desktop authentication cards; FinanceTheme's global shape scheme MUST NOT change. Authentication code MUST NOT use Compose Unstyled controls or duplicate Material 3 control colors, outlines, shapes, pressed styles, or disabled styles. This requirement applies to the shared Compose implementation across Web, Android, and iOS; it does not change legacy React Web or Expo presentation.

#### Scenario: Empty login and registration fields use Material 3 labels

- **WHEN** a user opens login or registration with empty fields
- **THEN** each outlined text field MUST show its label inside the field
- **AND** the first field's outlined container MUST start at the same vertical position in both modes within the same responsive class
- **AND** field height, button height, component shapes, colors, typography, field order, labels, semantic IDs, and accessibility meaning MUST match the confirmed Pen design

#### Scenario: A focused or populated field floats its label

- **WHEN** a user focuses a field or the field contains a value
- **THEN** the Material 3 label MUST float above the field outline
- **AND** the outline and label MUST reflect the current focus or validation state
- **AND** the input value MUST remain visible or masked according to the existing field type

#### Scenario: Field-level helper and validation feedback use supporting text

- **WHEN** a field has a helper, validation-success, or validation-error message
- **THEN** that feedback MUST use the Material 3 supporting-text position below its outlined field
- **AND** it MUST retain its current copy, live-region behavior, color meaning, and relationship to the corresponding field
- **AND** subsequent fields and the submit action MUST remain visible without overlap in compact, regular, and wide layouts

#### Scenario: Authentication is busy or returns a request error

- **WHEN** authentication is busy or a request fails
- **THEN** fields and actions MUST retain their current disabled, progress, and request-error behavior
- **AND** labels, copy, request data, and authentication results MUST remain unchanged

### Requirement: Compose authentication title and first-field positions are aligned

The login and registration first outlined fields MUST start at the same vertical position within a given responsive class. The registration return button MUST keep a 44 dp hit area and its existing accessible name.

#### Scenario: Switch between login and registration

- **WHEN** the user switches modes at the same window size class
- **THEN** the first outlined field starts at the same vertical position in both modes
- **AND** the registration return button remains available with its existing accessible name and 44 dp hit area

### Requirement: Compose authentication modes share a responsive shell

Login and registration MUST use one internal `commonMain` responsive shell for compact, regular, and wide layouts. The shell MUST preserve the existing responsive layout behavior and contain no authentication behavior or field-specific copy.

#### Scenario: Open authentication at each responsive size class

- **WHEN** login or registration is rendered in compact, regular, or wide window classes
- **THEN** both modes MUST use the same shell and retain their current outer padding, scrolling, and centered-card behavior for that class
