## ADDED Requirements

### Requirement: Compose cash import follows the confirmed secondary-page design

The Compose cash-import surface MUST follow the information hierarchy and responsive composition of the confirmed Pen and standalone prototype. Regular and wide layouts MUST show the page title before the trailing close control; compact MUST show the leading back control before the title. The secondary header MUST use 72 dp / 78 dp / 84 dp heights for Compact / Regular / Wide, respectively. Regular and wide modals MUST use 22 dp / 24 dp corner radii, respectively, without an additional shadow. The close/back control MUST show the prototype's blue focus treatment when the secondary page opens. The four-stage indicator MUST use equal-width numbered/completed circles with labels below and span the reusable page body's width independently from the capped stage content, using 36 dp side insets at Regular/Wide and 12 dp at Compact. File selection, account mapping, and review content MUST be centered within the established 720 dp form width; relationship review MUST use the available container width and render each relation as a source-to-target card/row. The layout MUST preserve the existing scan, mapping, review, allocation, relation-kind, candidate, reject/restore, error, and confirmation semantics.

The complete cash-import route MUST use the light Finance palette shown in the confirmed prototype regardless of system appearance, including the navigation shell, top bar, route backdrop, and import surface. The regular/wide modal scrim MUST use the existing dark overlay color token at 38% opacity, matching the prototype's `rgb(24 24 27 / 38%)` overlay. The modal MUST block outside interaction, retain modal keyboard focus, and close with Escape/back. Colors MUST be resolved from the active Compose theme scope so all shared components use the same palette. When the user leaves cash import, the application MUST restore the configured system appearance.

Relationship filters MUST show all, pending, and matched counts. Relation cards MUST follow the prototype's compact source/target hierarchy and MUST NOT show persistent account-name rows or a duplicate field label. A pending target MUST use one candidate dropdown that displays “选择流水”, without extra explanatory copy or a separate visible selection action. Compact relation cards MUST keep the source and target side by side, with status below; Regular/Wide cards MUST place status at the trailing edge. Less frequent relation-kind and reject/restore controls MAY be tucked behind an accessible, keyboard-operable action control styled as part of the status chip.

The secondary-page footer MUST show a divider on Compact and omit it on Regular/Wide. Compact footer copy MUST appear above its action row, with 8 dp top and 16 dp bottom padding; Regular/Wide footer content MUST use 16 dp top and 24 dp bottom padding. On the Compact file-selection stage, the discard note MUST appear above the next action. The visible file-format copy MUST match the prototype's `CSV · XLSX · PDF` list while the existing XLS import support remains intact.

#### Scenario: View import at wide size

- **WHEN** a user opens cash import at a wide logical window size
- **THEN** the page MUST show a leading title, trailing close control, and four vertically labelled stage indicators
- **AND** the navigation shell, top bar, and import surface MUST retain the prototype's light palette when the system appearance is dark
- **AND** the modal backdrop MUST use the prototype's 38% scrim
- **AND** file/mapping/review form content MUST be centered and capped at 720 dp while relation review uses the available modal width
- **AND** pending relations MUST present one candidate selector within the source-to-target row, while existing relation-kind and reject/restore actions remain available
- **AND** the matched filter MUST include automatic and user-confirmed relations, while rejected relations remain visible in the all filter

#### Scenario: View import at compact size

- **WHEN** a user opens cash import at a compact logical window size
- **THEN** the page MUST use a full-screen surface with a leading back control and title
- **AND** the file-stage discard note MUST appear above the next-step action
- **AND** stage content and relation cards MUST remain single-column/reflowed with no page-level horizontal overflow
- **AND** the same import decisions and results MUST remain available as at regular/wide sizes
- **AND** compact relation cards MUST use a horizontal source-to-target row without horizontal overflow

#### Scenario: Restore system appearance after import

- **WHEN** a user leaves cash import for another application route
- **THEN** the destination route MUST use the configured system appearance
