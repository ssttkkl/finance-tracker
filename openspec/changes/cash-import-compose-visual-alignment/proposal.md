## Why

The running Compose import screen does not match the design the user already approved. Its desktop header places the close control before the title, the stepper uses four text buttons instead of numbered circles, file selection lacks the designed drop area, and transaction relations render as stacked form fields rather than a source-to-target row. The difference is visible even though the flow itself works.

## What Changes

- Align the shared Compose import screen and reusable secondary-page header with the approved Pen design and HTML prototype.
- Match the prototype's light Finance palette on the import surface, including when the system appearance is dark.
- Keep the four import stages, temporary-session cleanup, status semantics, relation kind/candidate decisions, reject/restore actions, and confirmation boundary unchanged.
- Add browser assertions for prototype-critical layout, desktop/compact sizing, and preserved relation actions.

Non-goals: changing the import workflow, copy intent beyond using the already-approved title, APIs, storage, relation rules, other pages, or the approved prototype's visual direction.

## Scope and Acceptance

- At regular/wide sizes, the title is leading and the close control is trailing; compact keeps back leading and title beside it.
- The stepper uses equal-width stages with a numbered/completed circle and a label beneath it.
- File selection has a distinct upload surface; mapping and review content are centered and capped at 720 dp; relation review uses the full modal width.
- Preview and relation filters read as compact selectable chips; preview records and relation decisions use bordered rows/cards with the order and density shown in the approved prototype.
- A pending relation exposes one candidate selector and retains the relation-kind and reject/restore actions without changing their semantics.
- The modal remains 90% of the regular/wide viewport, compact remains full screen, and the page has no horizontal overflow at required widths.
- Import surface colors match the approved light prototype in both light and dark system appearance.
- Browser visual and functional QA demonstrates the existing four-stage import contract still works.

## Impact

Shared Compose presentation affects Compose Web, Android, and iOS. No API, database, or data migration is involved. The approved design baseline is the Pen canvas `design/cash-import-secondary-surface.pen` and the HTML prototype copied into this change from the previously approved import secondary-page change.
