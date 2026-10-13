## Design Baseline

The user clarified that the implementation must feel like the already-approved prototype. This work reuses that exact direction rather than inventing a new design. Compare the Compose production images from the archived `cash-import-secondary-surface` change with this change's `prototype/index.html` and `design/cash-import-secondary-surface.pen`.

Hallmark preflight and audit: the repository-provided Hallmark guidance was reviewed. The desktop runtime does not expose a callable Hallmark audit action in this session; the final UI will receive a manual audit against the same audit rubric and its findings will be recorded in `tasks.md`.

## Layout Corrections

- Reusable container: wide header reads left-to-right as title then close control; compact uses back control then title. Keep its 90% modal sizing, safe-area behavior, focus/Escape behavior, body scroll, and fixed footer.
- Stepper: four equal columns; completed/current stages use the brand circle, upcoming stages use a muted circle; every label sits under its circle. Keep the existing semantic IDs and stage navigation constraints.
- Stage width: file selection, account mapping, and preview are centered and capped at 720 dp. Relationship cards use the available modal width. Apply width constraints before fill modifiers so the intended cap is effective.
- File selection: stage heading followed by a pale, outlined upload area with upload icon, a bold file prompt, supported formats, a per-file size limit, and an outlined file-picker action. Selected files, remove actions, password prompts, and errors remain visible and operable. The footer keeps the discard note at leading edge and a single next-step action at trailing edge.
- Account mapping: show the selected-file summary, heading, then concise source-to-account rows. Keep source evidence, currency warnings, and new-account fields where they help make a mapping decision.
- Review: use clickable filter chips and bordered transaction rows; retain amount direction, review status, unresolved/error states, multi-component allocation inputs, and commit gating.
- Relations: use `全部` / `待确认` / `已匹配` count chips and source → target cards. Compact cards keep both records on one row. Existing paired targets are plain content; a pending target uses one candidate selector with the empty choice in that selector. Put the less-frequent relation-kind and reject/undo-reject actions behind a 44 dp accessible action button so they do not dominate the default row.
- Visual values come from existing Finance tokens; subtle tints derive from the accent role. Do not add a second card around the entire stage content.

## Compose Fidelity Follow-up

A direct same-stage comparison with the confirmed Pen and HTML prototype found measurable differences that were not obvious from the earlier structural review: `SecondaryPageBody` added 20 dp horizontal padding around `FeaturePage`'s existing 16 dp, leaving Compact content 36 dp from the edge while the Pen uses 16 dp. Wide content likewise inherited 36 dp instead of the Pen's 32 dp. The footer used a fixed 20 dp inset at every size and buttons could shrink to text width. The desktop title used the default 24 sp regular weight instead of the Pen's 28 sp semibold. A real-browser comparison then showed a larger visual mismatch: the prototype fixes the import surface to the light palette, while Compose inherited the system dark palette. The confirmed prototype is the visual source of truth for this import page, so the import surface uses the light Finance palette on every platform and window size. The surrounding application continues to use its configured system theme under the modal scrim.

The content slot now owns its horizontal inset: Compact uses 16 dp; Regular/Wide use 32 dp. `SecondaryPageBody` no longer adds a second inset. The footer uses 16 dp Compact / 32 dp Regular-Wide horizontal padding and 12 dp / 16 dp vertical padding. Page titles use 22 sp semibold Compact and 28 sp semibold Regular/Wide. Footer actions use 108 dp in Compact and 96 dp in Regular/Wide. This maps the same content edges and control proportions as `stage-content`, `surface-footer`, and `.button` in the approved HTML prototype. A local light Finance theme now also maps the surface colors to the prototype regardless of the system appearance.

## Cross-platform Impact Check

- Web: Compose Web is directly affected because it renders this shared screen. The legacy React Web implementation is not part of the edit and remains behaviorally unchanged.
- Native: Android/iOS render the same `commonMain` screen and reusable container, so the responsive hierarchy and controls are affected on phone, tablet, and large windows.
- Shared: `CashImportScreen` and `SecondaryPageContainer` are shared. The existing file-picker surface may remain platform-specific; all labels, state meanings, relation decisions, and submit outcomes stay shared.

## Risks and Rollback

The main risks are clipping at compact widths, a 720 dp constraint that still fails to constrain due to modifier ordering, and accidentally hiding relation decisions while rearranging cards. Cover widths 320/375/390/414/768/1440, verify actual element bounds, and exercise candidate selection plus reject/restore. Rollback only the presentation and browser assertion edits; no data rollback is needed.
