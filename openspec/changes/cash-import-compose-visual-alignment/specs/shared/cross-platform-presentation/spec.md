## ADDED Requirements

### Requirement: Compose cash import follows the confirmed secondary-page design

The Compose cash-import surface MUST follow the information hierarchy and responsive composition of the confirmed Pen and standalone prototype. Regular and wide layouts MUST show the page title before the trailing close control; compact MUST show the leading back control before the title. The four-stage indicator MUST use equal-width numbered/completed circles with labels below. File selection, account mapping, and review content MUST be centered within the established 720 dp form width; relationship review MUST use the available container width and render each relation as a source-to-target card/row. The layout MUST preserve the existing scan, mapping, review, allocation, relation-kind, candidate, reject/restore, error, and confirmation semantics.

The import surface MUST use the light Finance palette shown in the confirmed prototype regardless of system appearance. Its colors MUST be resolved from the active Compose theme scope so all shared controls use the same palette.

Relationship filters MUST show all, pending, and matched counts. Compact relation cards MUST keep the source and target side by side, with status below. The default relation row MUST prioritize the two records, candidate selector, and status; less frequent relation-kind and reject/restore controls MAY be tucked behind an accessible, keyboard-operable action control.

#### Scenario: View import at wide size

- **WHEN** a user opens cash import at a wide logical window size
- **THEN** the page MUST show a leading title, trailing close control, and four vertically labelled stage indicators
- **AND** the import surface MUST retain the prototype's light palette when the system appearance is dark
- **AND** file/mapping/review form content MUST be centered and capped at 720 dp while relation review uses the available modal width
- **AND** pending relations MUST present one candidate selector within the source-to-target row, while existing relation-kind and reject/restore actions remain available
- **AND** the matched filter MUST include automatic and user-confirmed relations, while rejected relations remain visible in the all filter

#### Scenario: View import at compact size

- **WHEN** a user opens cash import at a compact logical window size
- **THEN** the page MUST use a full-screen surface with a leading back control and title
- **AND** stage content and relation cards MUST remain single-column/reflowed with no page-level horizontal overflow
- **AND** the same import decisions and results MUST remain available as at regular/wide sizes
- **AND** compact relation cards MUST use a horizontal source-to-target row without horizontal overflow
