# Design

## Status

Pen prototype and standalone preview are prepared. **The user approved the design by asking me to complete the change after reviewing the prototype.**

## User task and information architecture

The first-use task is to name a workspace and create it. The page has two regions:

1. **Account header:** 「财务账本」, the signed-in email, and 「退出登录」. The logout action remains available without introducing a back action.
2. **Single form card:** one 「创建工作区」 heading, one 「工作区名称」 field, and one primary 「创建工作区」 action. Validation and request errors sit directly below the field. After server confirmation, the existing session flow opens the ledger; the form does not become a success page.

The first-use state is the only production layout in this change. The existing workspace list and the form used when workspaces already exist stay as they are.

### Removed from the current first-use layout

- The page-level 「创建工作区」 title outside the form.
- The redundant 「新工作区」 title inside the form surface; the card now has one clear heading.
- Any additional instructional copy or success-stay screen. Neither is needed to complete the task.

## Pen source and node mapping

The approved visual source will be [`design/workspace-entry.pen`](../../../../design/workspace-entry.pen). `member@example.com` is explicit placeholder data. Frames are exported to `screenshots/` for review.

| Pen frame / node | Compose destination | Properties to preserve |
|---|---|---|
| `KV5AK` / `eIYRc` | `WorkspaceAccessScreen` account header | Compact 390 px viewport; 16 px horizontal/top inset; 72 px row; brand 18 px/700 and email 13 px regular grouped left; 14 px/500 logout in a 90 × 44 px target; 1 px bottom divider; space-between alignment. |
| `KV5AK` / `G6wFD` | First-use `WorkspaceAccessScreen` form container | x=16, y=136, width=358 px; surface token; `finance.radius`; vertical layout, 16 px gaps, 20 px padding. |
| `KV5AK` / `hN6hL`, `r46RB`, `u03Kkv` | Heading, `AuthTextField`, primary `Button` | Heading 24 px/700; field 318 × 56 px, 8 px outline radius; CTA 318 × 44 px, `finance.accent` fill, 18 px/700 white label. |
| `HetEf` / `uWZWG` | Account header in `REGULAR` | x=24, y=28, width=720 px, 72 px row; same account hierarchy and actions. |
| `HetEf` / `nUyNp` | Centered first-use form card in `REGULAR` | x=164, y=392, width=440 px, 32 px padding, 16 px gaps; centered in 768 × 1024 px viewport. |
| `HetEf` / `P6XHor`, `UqY0e`, `fl8jk` | Heading, `AuthTextField`, primary `Button` | Same field/CTA styles as compact; inner width 376 px; card max width 440 px. |
| `N1D59` / `ArHkS` | Account header in `WIDE` | x=160, y=28, width=1120 px, 72 px row; same account hierarchy and actions. |
| `N1D59` / `Kbx0T` | Centered first-use form card in `WIDE` | x=500, y=348, width=440 px, 32 px padding, 16 px gaps; centered in 1440 × 900 px viewport. |
| `N1D59` / `Kfrfl`, `kC7B8`, `ynQNU` | Heading, `AuthTextField`, primary `Button` | Same title, field, button properties as regular; inner width 376 px. |
| `Uqok8` | State reference only | Default/empty, focused, name error, request error, disabled/creating, and the success route to the ledger. This state board is not a production page. |

### Compose implementation mapping (after approval)

- Replace the first-use layout in `compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/AccessScreens.kt::WorkspaceAccessScreen` with the account header and card structure above. Keep the workspace-list branch intact.
- Match the login/register page's Material 3 `OutlinedTextField` and primary `Button` styling directly in the first-use card. Keep the 56 px field, 8 px outline radius, 44 px CTA target, and the specified colors and typography. The auth form's local controls are private, so the workspace uses its own Material 3 instances while preserving existing workspace semantic IDs and the accessible field label.
- Use the existing responsive window class: `COMPACT` is a full-width card with 16 px page inset and 20 px card padding; `REGULAR` and `WIDE` use a 440 px card with 32 px padding. The account header uses 16 px compact inset and the 720/1120 px max-width desktop containers shown in Pen. Final Native measurements use an 8 px upward offset in `REGULAR` and a 10 px downward offset in `WIDE`, placing the card at y=392 and y=348 respectively in the approved frames.
- Keep `nameError`, `requestError`, `busy`, field content, logout, retry, and server-session navigation semantics. Empty names continue to be submitted locally for validation; no invalid name is sent to the server. During `busy`, disable the field and action and show 「正在创建…」.
- Preserve `FinanceTheme` light/dark adaptation at runtime. Pen and the standalone visual preview show the light theme from the supplied reference.

## Visual decisions and state coverage

- Use the existing login/register colors, Noto Sans SC, outlined field, primary button, and 8 px auth-control radius. The page background is `finance.background`; the form surface is `finance.surface`.
- Keep the account header above the form. Compact uses the 390 px representative canvas; regular and wide use a centered 440 px form card. The three frames are `COMPACT (390)`, `REGULAR (768)`, and `WIDE (1440)`.
- Keep the 18 px bold CTA label and blue/white token pair for a 3:1 large-text contrast threshold. The focused field uses an accent outline/cursor and a foreground floating label so its 12 px label remains legible. Validation and request feedback use the danger token.
- The status board covers a blank default, field focus, invalid name, retryable request failure, disabled/creating, and the post-confirmation route to 「收支账本」. There is no invented local-success state.
- The standalone HTML preview is `prototype/index.html`; it is for visual and responsive review only and is not production Web UI.
- Native screenshot evidence was captured from the Compose device test host at `390 × 844`, `768 × 1024`, and `1440 × 900` logical pixels. The account header/card bounds, field and button widths, and 56/44 px control heights were compared against the exported Pen frames; the final images are `screenshots/native-390.png`, `screenshots/native-768.png`, and `screenshots/native-1440.png`.

## Risk and rollback

This is a first-use layout change only. It does not change workspace creation contracts, permissions, persistence, APIs, Web presentation, or Expo. The main regression risk is accidentally applying the first-use card layout when `session.workspaces` is non-empty; preserve that existing branch and its semantic IDs. Rollback restores the prior `WorkspaceAccessScreen` layout and leaves session/domain behavior unchanged.

## Design review

- Hallmark precheck: restrained task-first layout; no hero, decorative illustration, nested cards, repeated title, or instructional copy. The prototype uses the project's token pairing; CTA labels are large bold text to meet the 3:1 large-text threshold, and focus/error states are explicit.
- Hallmark audit: the prototype and Pen screenshots, then the final Native Compose UI and its `390 × 844`, `768 × 1024`, and `1440 × 900` captures, have 0 critical, 0 major, and 0 minor findings. The final audit reviewed hierarchy, responsive composition, typography, state coverage, control contrast, and the approved Pen mapping. Final Native screenshots are retained under `screenshots/native-*.png`.
- User approval: confirmed in the user's request to complete the named change after the Pen prototype was presented.
