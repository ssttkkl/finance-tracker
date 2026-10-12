## Context

See `proposal.md` for motivation and `specs/shared/cross-platform-presentation/spec.md` for the behavior contract. The six destinations already exist in `appDestinations`; `AppShell.kt` currently uses a compact modal drawer, a regular modal drawer, and a 220 dp dark permanent drawer. `DestinationScaffold` owns the shared page title and currently places workspace selection and sign-out in the top bar. Authentication, workspace membership, route selection, and sign-out are already handled by existing callbacks.

The affected Compose `commonMain` shell is shared by Web, Android, and iOS. `WindowSizeClass` uses the existing 600 dp and 1024 dp breakpoints. The finance palette and spacing remain governed by `design/finance-ui.lib.pen` and `openspec/specs/shared/design-system/tokens/`.

## Goals / Non-Goals

**Goals:**

- Keep navigation state and the top-level page title owned by one authenticated shell across all three window classes.
- Keep all six existing `appDestinations` flat and in their existing order; use their current localized title keys and semantic IDs.
- Give `REGULAR`/`WIDE` one persistent side rail whose width changes between a 64 dp icon rail and a 240 dp labeled rail. Initialize it collapsed; retain its in-memory state while navigating.
- On `COMPACT`, show the same six destinations in a modal drawer opened from the top bar. Put the identity control at the bottom of the drawer.
- Put an initial-based avatar at the bottom of the persistent rail. Open the same account/workspace actions from it: signed-in email, accessible workspaces and roles, workspace management, and sign-out.
- Preserve existing navigation, workspace selection, invitation, session clearing, and error-handling callbacks.

**Non-Goals:**

- No route, domain, authorization, API, persistence, or account-profile behavior changes.
- No profile editor, avatar upload, invented user data, or destination regrouping.
- No changes to the legacy React/Expo clients.

## Decisions

### One flat destination model, adapted by the window class

Use `appDestinations` as the sole ordered source for both the compact drawer and persistent rail. The icon rail and expanded rail are presentations of the same list, not separate navigation models. Preserve each destination's title key and semantic ID; add shell-level semantic IDs only for the expand/collapse, account, and workspace actions. For REGULAR/WIDE, the rail starts at 64 dp and expands to 240 dp; on COMPACT, keep the existing modal-drawer interaction and expose labels for every item.

Alternative considered: nested groups for cash and investments. Rejected because the user explicitly asked for six same-level destinations.

### Keep one `Scaffold` title; move identity actions to the rail footer

Keep `DestinationScaffold` as the page frame. Its top bar contains the current page title and only the route's existing back action. Remove workspace selection and sign-out from top-bar actions. Compose the persistent rail beside the scaffold for REGULAR/WIDE, and keep a top-bar drawer trigger for COMPACT. The expanded rail shows destination labels; the collapsed rail gives each icon an accessible label and hover/focus tooltip.

Alternative considered: keep workspace controls in both the top bar and avatar menu. Rejected because the user wants the GitHub-style avatar panel to own identity and workspace operations.

### Separate the rail toggle from branding and navigation

Remove the circular `F` mark and product wordmark from the persistent rail. The rail header contains only the 44 × 44 px expand/collapse control, with a divider and 12 px of separation before the six destination links. The panel arrow points right while collapsed and left while expanded; its accessible name and tooltip change with the state. The compact drawer keeps a plain text title and close button, without the `F` mark. This gives the toggle one clear place, meets the 44 px navigation-control target, and keeps branding out of the icon navigation.

### Use transient hover expansion on desktop

On hover-capable pointers, entering any part of the persistent rail temporarily expands it to 240 px; leaving the rail and its account panel collapses it. The isolated toggle remains available: clicking it pins the expanded width until clicked again. This gives mouse users a direct peek interaction while preserving an intentional way to keep labels visible. On touch devices, the same control expands and collapses the rail manually. Keyboard focus within a navigation item expands the rail for its duration; the control remains keyboard-operable. `COMPACT` continues to use explicit drawer and sheet activation.

Hovering the bottom avatar opens the account popover without moving keyboard focus. The popover and rail form one temporary hover region so the cursor can cross from the avatar to the panel without closing it or shifting its anchor. Leaving both the avatar/rail and popover closes the hover-opened menu and retracts the rail. Click-opened menus retain their existing outside-click and Escape behavior.

### Use a responsive account panel with the existing workspace actions

Anchor a menu/popover above the persistent rail avatar on REGULAR/WIDE, with the signed-in email, available workspaces and their roles, current workspace marker, workspace-management action, and sign-out. On COMPACT, open the equivalent content in a bottom sheet from the drawer footer. Selecting a workspace continues to call `accessViewModel::selectWorkspace`; selecting management navigates to the existing first-level destination; signing out continues through the existing `signOut()` path and navigation reset.

Alternative considered: route users to a new profile page. Rejected because no profile capability exists and the requested interaction is an operations panel.

### Keep the finance design system and restrained shell styling

The prototype uses the existing finance light palette and typography, with a white/light side rail, neutral separators, and the existing blue accent for active and focus states. The reference contributes the narrow persistent icon rail, active-item cue, and bottom identity placement; it does not supply product data or copied branding. Avoid a new theme, decorative cards, and financial metrics in the shell.

### Information architecture and removed redundancy

```text
Authenticated shell
├─ Persistent side rail (REGULAR/WIDE; collapsed by default)
│  ├─ Expand/collapse control
│  ├─ 收支账本
│  ├─ 账单导入
│  ├─ 收支分类
│  ├─ 当前持仓
│  ├─ 投资事件
│  ├─ 工作区管理
│  └─ Avatar → account/workspace panel
├─ Modal drawer (COMPACT; same six flat items, avatar at bottom)
└─ Destination Scaffold
   ├─ Top app bar: current page title and applicable back action
   └─ Existing destination content
```

Remove the top-bar workspace switcher and sign-out control, the fixed dark wide-only drawer treatment, and any grouping affordance that implies nested destinations. Keep the six route entries and their content.

### Pen source and Compose mapping

Compose Web, Android, and iOS are all affected because they share `commonMain`; the compact drawer is the registered phone presentation and REGULAR/WIDE share the persistent rail contract. Legacy React and Expo are out of scope. The official source is [`design/app-shell-main.pen`](../../../design/app-shell-main.pen); the HTML prototype is its clickable interaction companion.

| Pen frame/node | Approved geometry and appearance | Compose owner and mapping |
|---|---|---|
| `exkWh` WIDE artboard; `uSVpK` hover-expanded rail; `hkZUl` scaffold; `m4tCg4` top bar; `to8mO` destination body | 1440 × 900 hover state; rail 240 px while the pointer is over it or the account panel; it collapses to 64 px when the pointer leaves both. The 60 px toggle header contains a 44 × 44 px target, 1 px divider, and 12 px gap before navigation; clicking the toggle pins/unpins the expanded width. Top bar is 64 px high with 20 px horizontal inset; body uses 24 px inset and 20 px section gap. | `WideAppShell` places the shared rail beside `DestinationScaffold`; `DestinationScaffold` retains the single page title and route-specific back action. Use Material 3 `Scaffold`/`TopAppBar` for the title frame, with the rail as its persistent navigation region. The top rail row contains no brand glyph or wordmark. Pointer hover expansion is transient; explicit activation is available to pin it. |
| `ZTXPa` REGULAR artboard; `sBFRl` collapsed rail; `S12JH4` expanded rail state; `Ti2sF` scaffold | Representative 768 × 900 screen. Collapsed rail is 64 px; expanded rail is 240 px; both show the isolated 44 × 44 px control and divider. Destination rows are 44 px high with 20 px icons and 4 px row spacing. Pointer hover temporarily expands, while click pins/unpins; no-hover touch uses click to expand/collapse. | `RegularAppShell` uses the same flat destination list as `WideAppShell`; a transient hover/focus state expands the rail, while explicit activation can pin the expanded width during this shell session. Each collapsed icon retains its destination semantic label and focus/hover indication. |
| `kma6S` COMPACT artboard; `n6MqGO` top bar; `CwaRP` drawer; `XHsDL` drawer account trigger; `MgNLU` account sheet | 390 × 844; top bar 64 px; drawer 304 px; nav rows 44 px; avatar 36 px; account trigger 48 px. Bottom sheet content is 390 px wide with 16 px top radius and safe-area bottom padding. | `CompactAppShell` keeps the top-bar drawer trigger and modal drawer. The drawer contains the same six first-level destinations and a bottom account trigger. `ModalBottomSheet` displays the shared account content and dismisses with Escape/backdrop/back, returning focus to its trigger. |
| `bKMmj` WIDE account panel instance; `qFbi0` shared desktop account panel | Panel width 320 px, 16 px inset, 12 px vertical rhythm, 8 px corners, 1 px border, subtle 0/8/24 px shadow. Identity avatar is 36 px; workspace and account action rows are 48 px. The active row uses a 2 px accent focus outline; roles use muted text. | `WorkspaceAccountMenu` is anchored above the persistent rail avatar using a Compose popup/menu surface. It reads the authenticated email and accessible workspaces, shows the active workspace and role, then routes management and sign-out to existing callbacks. |
| `ug51c` interaction-state sheet | 960 × 440 reference sheet, with mutually exclusive loading/disabled, error/retry, and successful workspace-selection examples. | One shared menu state model drives `WorkspaceAccountMenu`; reuse `selectWorkspace`, error recovery, and `signOut` behavior rather than introducing a parallel session path. The active route remains outside the menu state. |

**Token, type, and state binding:** map `$shell-bg` to background `#F5F5F5`, `$shell-surface` to surface `#FFFFFF`, `$shell-text` to foreground `#18181B`, `$shell-muted` to muted foreground `#71717A`, `$shell-border` to border `#E4E4E7`, `$shell-accent` to focus/active markers and icons `#0485F7`, and `$shell-primary` to text actions, filled actions, and avatar `#0A63B8`; use the existing `Noto Sans SC` font token. The page title is 18 px/600, navigation labels are 13 px, account identity 14 px/600, and role metadata is 12 px. Use 6 px control corners, 8 px account-panel corners, 16 px sheet top corners, 44 px nav hit areas, 48 px account rows, and the dimensions above. Keep the selected destination marker 3 × 28 px; selected, focus, hover, disabled, loading, error, success, and empty states use the Pen samples and existing tokens. The account panel and sheet use a 1 px border and the specified low-contrast shadow; no financial amount or personal-profile editor is added.

### Hallmark audit and design review

Audited the Pen frames `exkWh`, `ZTXPa`, `kma6S`, `qFbi0`, `MgNLU`, `ug51c`, and `S12JH4` plus the rendered HTML prototype. Final audit after the user's visual feedback: 0 critical, 0 major, 0 minor findings. The review covered the icon-first shell and account panel, current finance tokens, real interactive states, keyboard focus, readable one-line affordances, and the required responsive widths. The review first caught a text/button contrast issue, fixed by switching filled actions and small action links to `$shell-primary` (6.02:1 on white); `$shell-accent` remains on icon/focus affordances (3.68:1 on white). The follow-up visual pass removed the top `F` mark, made the toggle the only item in a separated rail header, reversed the arrow between open/closed states, and raised its hit target to 44 × 44 px per the UI rules. Error copy uses the danger token (5.65:1 on error surface). The source CSS includes the Hallmark score and audit stamps.

This mapping applies identically to Compose Web, Android, and iOS `commonMain`. Web `compact` and Native Phone share the drawer/sheet ordering and outcomes; Native `regular`/`wide` share the same six-level list and collapsed-first rail. Legacy React and Expo remain out of scope. No user preference is persisted for expansion; process-local shell state resets to collapsed on a new session.

## Risks / Trade-offs

- [64 dp icon-only items can be hard to identify] → Expand the rail on pointer hover or keyboard focus; give every item an accessible name and use the active marker and selected state consistently.
- [A 240 dp expanded rail reduces space at the 600 dp REGULAR boundary] → Default to the 64 dp rail at every REGULAR/WIDE width; retain existing `WindowSizeClass` content adaptations and check the 768 dp representative.
- [An avatar-anchored menu can be clipped, flicker, or shift while the pointer crosses to it] → Place it above the bottom anchor on REGULAR/WIDE and treat the rail plus popover as one hover region with a short exit grace period; use a modal bottom sheet on COMPACT; preserve Escape dismissal and focus return where the platform supports them.
- [Workspace switching may fail or change route state] → Reuse the existing `onWorkspaceSelected` callback and verify the existing error and navigation behavior without adding a new session pathway.
- [The Pen MCP app connection is currently unavailable] → Use the installed official `pen` CLI to create and export the `.pen` file; do not edit encrypted `.pen` data directly.

## Migration Plan

No data migration or API rollout is needed. After the user confirms the prototype, update the shared Compose shell in place, retain existing routes/callbacks, update the feature map, and validate the OpenSpec change. Rollback by reverting the shell, feature-map, and spec changes together; no persisted user preference or server state needs cleanup.

## Open Questions

None that change the specified behavior. The account panel's desktop anchoring and compact bottom-sheet treatment are shown in the prototype for user review before implementation.
