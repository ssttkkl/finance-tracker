## 1. Thinking and scope

- [x] 1.1 Read the project context, UI design rules, shared presentation spec, feature map, terminology guidance, and current Compose shell.
- [x] 1.2 Complete requirement clarification; record the flat six-item navigation, default-collapsed rail, compact drawer, and bottom-left account entry in the proposal and delta spec.
- [x] 1.3 Record cross-platform impact: Compose Web, Android, and iOS are affected through `commonMain`; legacy React/Expo are out of scope.

## 2. Design and prototype

- [x] 2.1 Create the official Pen design with COMPACT (390 px), REGULAR (768 px), and WIDE (1440 px) frames plus navigation/account states; use existing Finance UI library tokens.
- [x] 2.2 Create `prototype/index.html` as a standalone companion covering hover-expanded rail/account menu, manual pin, compact drawer, selected workspace, focus, error, and empty content states.
- [x] 2.3 Review the prototype against the reference and responsive constraints; record Hallmark self-critique and audit findings in this file.
- [ ] 2.4 Present the concrete Pen design and interactive prototype for user confirmation; do not begin Compose implementation before confirmation.
- [ ] 2.5 After confirmation, record Pen frame/node IDs and map each approved size, spacing, alignment, token, font, border/radius, and interaction state to Compose components here.

## 3. Task consistency and implementation plan

- [ ] 3.1 Validate proposal, modified requirement, design, tasks, route preservation, interaction states, and cross-platform impact as one consistent contract.
- [ ] 3.2 Arrange the implementation around one shared destination list, responsive shell, persistent rail, account panel, and existing action callbacks; keep all six links at one level.

## 4. Build

- [ ] 4.1 Implement the approved persistent icon rail and expansion behavior for REGULAR/WIDE, preserving its state across destination changes.
- [ ] 4.2 Implement the compact drawer with all six destinations and a bottom avatar entry.
- [ ] 4.3 Implement the responsive account/workspace panel and wire existing select-workspace, management-navigation, and sign-out callbacks.
- [ ] 4.4 Keep the shared Scaffold title/back action and remove the top-bar workspace menu/logout; update `docs/feature-map.md`.

## 5. Review

- [ ] 5.1 Review product scope, shell boundaries, callback/data flow, accessibility, failure handling, rollback, and final diff; record findings and disposition here.
- [ ] 5.2 Run Hallmark `audit` on the final UI and resolve all critical/major findings; record scope and result here.
- [ ] 5.3 Perform final diff and artifact review; verify no API, route, session, workspace-role, or persistence behavior changed.

## 6. Validation and QA

- [ ] 6.1 Validate OpenSpec and run the applicable Compose/Web behavior checks after implementation.
- [ ] 6.2 Inspect the production Compose Web preview at 390 px, 768 px, and 1440 px; exercise navigation, account open/close, workspace switch, management, logout, focus, Escape, empty, loading, error, and disabled states; record URL, screenshot paths, browser, console/network output, and results.
- [ ] 6.3 Check Native compact and regular/wide presentation against the same labels, selected/current workspace, roles, focus, and action outcomes; record supported-device evidence and any unresolved platform gap.
- [ ] 6.4 Record actual commands, result, current `HEAD`, comparison base, date/time, unrun items, and remaining risks; run `git diff --check`.

## 7. Release

- [ ] 7.1 Record delivery and rollback readiness; no external release or deployment is part of this change.

## 8. Reflection

- [ ] 8.1 Record reusable navigation/accessibility lessons and any follow-up cross-platform alignment item.

## Design review evidence (2026-10-12)

- Official design source: `design/app-shell-main.pen`; frames `exkWh` (WIDE 1440×900), `ZTXPa` (REGULAR 768×900), `kma6S` (COMPACT 390×844), `S12JH4` (expanded rail), `qFbi0` (desktop account panel), `MgNLU` (compact account sheet), and `ug51c` (loading/error/success states). The HTML companion is `openspec/changes/app-shell-icon-navigation/prototype/index.html`.
- Pen CLI `0.3.11` was used for the official `.pen` source and exports. Latest frame previews after the user's feedback to remove the top brand mark, clarify expansion, and add hover behavior: `/tmp/app-shell-hover-export/{exkWh,ZTXPa,S12JH4}.png`; the WIDE frame now shows the expanded rail with the open account panel.
- Hallmark self-critique: Philosophy 5, Hierarchy 5, Execution 5, Specificity 5, Restraint 5, Variety 4. Audit scope: final Pen frames and standalone HTML. Result after the latest header update: 0 critical, 0 major, 0 minor findings. The audit initially caught small-text/white-button contrast on the bright accent; action labels and filled actions were changed to the existing primary token, then rechecked (6.02:1 on white). Focus/selection accents remain 3.68:1, meeting the icon/focus threshold; muted text is 4.83:1 and danger text on the error surface is 5.65:1. The final desktop rail has no circular `F` mark or wordmark; its only header item is a 44 × 44 px toggle in a 60 px row, followed by a divider and 12 px separation. Its arrow changes direction with expanded state, and its accessible name/tooltip describes the action. The compact drawer keeps a text-only “财务账本” heading.
- Real-browser QA: Chrome through CDP, prototype URL `http://127.0.0.1:5191/prototype/index.html`. Checked CSS viewport widths 320×800, 375×812, 390×844, 414×844, 768×900, and 1440×900; screenshot set `/tmp/finance-proto-final-{320,375,414,390-drawer,390-sheet,390-error,768-collapsed,768-expanded,1440-account,1440-loading,1440-error,1440-success}.png`. `scrollWidth` equaled `clientWidth` at every tested size; 768 and 1440 have a 15 px vertical scrollbar, with no horizontal overflow.
- Interactive flow: all six flat destinations exist; rail defaults collapsed, expands and retains state through route selection; top title updates; desktop account panel opens, Escape closes and restores trigger focus; workspace selection disables both options during loading, updates exactly one current marker on success, and displays confirmation; error exposes retry and retry succeeds. At 390 px, drawer opens and focuses navigation, avatar opens account sheet, Tab wraps, Escape closes to the visible drawer trigger, and selecting a route updates title and closes the drawer. Empty content is visible. Browser console and network reported no errors.
- Hover follow-up QA: Chrome in the Codex in-app browser, URL `http://127.0.0.1:5191/prototype/index.html`. Pointer hover was exercised at 980×959, 768×900, and 1440×900. Hovering the avatar opened the account panel and expanded the rail to 240 px; moving into another part of the rail kept the menu open. Moving to main content closed the hover-opened panel and returned the rail to 64 px. Clicking the rail control pinned the expansion, and clicking again released it. Keyboard focus expanded while inside the rail and collapsed after focus moved to main content; Escape closed the account panel and restored focus. At 390×844, the drawer and account sheet remained click-driven and Escape closed the sheet. Browser console error log was empty; network-panel details were not captured.
- Responsive follow-up: viewport sizes 320×800, 375×812, 390×844, 414×844, 768×900, and 1440×900 all had `scrollWidth === clientWidth`; narrow and desktop sizes include a 15 px vertical scrollbar. At 768×900 and 1440×900, hovering the avatar opened the account panel and expanded the rail to 240 px. At 390×844, the compact drawer and account sheet remained click-driven. Console error log was empty. The standalone prototype does not call the backend; network-panel details were not captured. Browser states were reviewed in the CUA session; design-preview screenshots are `/tmp/app-shell-hover-export/exkWh.png`, `/tmp/app-shell-hover-export/ZTXPa.png`, and `/tmp/app-shell-hover-export/S12JH4.png`.
- Artifacts: final screenshots are in `/tmp/finance-proto-final-*.png`; this is a design preview only, so no Compose, API, or workspace data behavior was exercised. OpenSpec validation passed; Compose implementation, tests/build, and production browser QA remain pending the user's design approval.
- Artifact verification: `openspec --version` → `1.7.0`; `openspec validate app-shell-icon-navigation --strict` → valid; `git diff --check` → clean; `rg -n '[\t ]+$' openspec/changes/app-shell-icon-navigation/ design` → no trailing whitespace. Current branch and comparison base: `refactor/kmp` at `c3685c43080b6489cdd20c6d56676796837a589d`; the design/change artifacts are untracked. Recorded 2026-10-12 18:00 CST. No test/build command was run because implementation is intentionally waiting for design approval.

## Hover interaction follow-up (2026-10-12)

- Scope: updated `proposal.md`, the shared presentation delta, `design.md`, `tasks.md`, `prototype/index.html`, and the official Pen source. No Compose implementation, APIs, routes, or feature map changed.
- Design choice: hover on a capable pointer temporarily expands the whole rail; the account trigger and popover form a continuous hover region. Leaving both closes a hover-opened menu and collapses the rail. Clicking the toggle pins/unpins it for the current shell session; touch remains click-driven and keyboard focus expands the rail while the focused item is inside it.
- Hallmark audit of the revised prototype and corresponding Pen states: 0 critical, 0 major, 0 minor findings. The page still matches the `modern-minimal` / `Persistent Utility Rail` stamp, uses the finance tokens, and has no added explanatory copy in the product surface.
- OpenSpec strict validation and final artifact checks passed after this follow-up. Compose implementation, production QA, and cross-platform runtime evidence remain pending user approval of the revised prototype.
