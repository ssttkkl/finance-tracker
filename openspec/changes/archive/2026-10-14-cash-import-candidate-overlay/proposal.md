## Why

The confirmed bill-import prototype shows pending relation candidates in a dropdown overlay. Compose Web currently expands the relation row when the list opens, changing the card height and moving nearby rows. A browser comparison also found that Regular/Wide footer actions were left-aligned and pending relation status used a neutral gray instead of the prototype's right-aligned actions and warning treatment. This follow-up brings the candidate interaction and those visible states back in line with the approved design.

## What Changes

- Show candidate choices in an anchored overlay that does not resize the relation card or reflow the list.
- Keep candidate selection, pending/matched counts, keyboard dismissal, and outside-click dismissal working in Compact, Regular, and Wide layouts.
- Keep the existing single “选择流水” field and omit repeated labels, account rows, radio controls, and a separate visible action.
- Align Regular/Wide workflow actions to the right and use semantic warning colors for pending relation status, matching the approved prototype.
- Present relation timestamps in the prototype's compact local month/day/time format.

Non-goals: changing relation matching rules, candidate ordering, cash-import APIs, persisted records, relation actions, other dropdowns, or the approved import hierarchy.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `shared/cross-platform-presentation`: require relation candidate options to open as an anchored overlay without changing surrounding row geometry, with consistent selection and dismissal outcomes.
- `shared/design-system`: add semantic warning colors so pending state styling uses the prototype's warning palette.

## Impact

- Affected code: shared Compose `ChoicePicker`, the reusable secondary-page overlay host, cash-import footer/status presentation, semantic color tokens, and Compose browser tests.
- Affected clients: Compose Web and Native share candidate values, labels, state, result, footer alignment, and status colors; each platform keeps its native rendering surface while satisfying the same behavior.
- Data and APIs: no changes. Selection remains a temporary import decision until the existing confirmation action.
- Rollback: restore the prior row-local option-list presentation and retain the single candidate selector.
