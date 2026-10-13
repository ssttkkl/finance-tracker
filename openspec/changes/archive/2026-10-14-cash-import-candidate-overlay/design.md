## Context

See `proposal.md` and `specs/shared/cross-platform-presentation/spec.md`. The previous implementation rendered the Web candidate options as a `Column` below the trigger inside each relation row. This made the row expand while open. A nested Compose popup was previously explored and did not deliver candidate clicks through the focusable secondary-page popup.

The accepted standalone prototype already uses the platform-native HTML `select`, whose option list overlays page content and does not participate in relation-row layout. Browser comparison of the current Compose preview also found two implementation deviations from that approved prototype: desktop workflow actions were left-aligned, and pending relation status was gray instead of warning orange. Correct those existing presentation differences without changing the approved hierarchy or content. The Pen design files are encrypted and can only be accessed through the pen.dev MCP. The current runtime has no open Pen editor document; `get_app_state` and `read_skill` both report that a file must be open. Preserve the approved control surface and implement the same overlay behavior in Compose Web.

## Goals / Non-Goals

**Goals:** Keep the option surface anchored near the candidate field, clickable inside both the Compact page and the Regular/Wide modal, and outside the relation list's measure/layout pass. Preserve selected value, labels, count updates, and keyboard operation.

**Non-Goals:** Change candidate ordering, matching state, relation business rules, page geometry, Native selector appearance, or the shared secondary-page container's modal/full-screen behavior.

## Decisions

1. **Use a page-owned overlay layer instead of a nested `Popup`.** `SecondaryPageContainer` owns a composition-local menu host and renders one topmost overlay sibling in the same Compose root as the page. This avoids nested popup event/focus forwarding while keeping menu hit testing above the scrollable body. The existing secondary-page Popup remains the only popup boundary for Regular/Wide.
2. **Share candidate data and callbacks, retain platform presentation.** `ChoicePicker` uses the anchored page layer only when the caller opts into it on Web. Native keeps the existing Material `DropdownMenu`; both surfaces use the same option values, labels, callback, and state outcome. Other Web pickers keep their current behavior.
3. **Position from same-root coordinates and constrain to the page viewport.** Match the trigger width with a minimum menu width, clamp the horizontal position, prefer below the trigger when space allows and otherwise open above. Cap the menu at 420 dp and scroll longer option lists. A transparent full-page hit surface dismisses on outside pointer input; Escape dismisses and restores focus to the trigger. Enter or Space opens the selector, arrow keys move among options, and Enter selects the focused option.
4. **Match prototype presentation details.** Keep Regular/Wide footer actions right-aligned; on Compact, place the outlined “上一步” at the footer's left edge and the primary action at its right edge. Use the outlined secondary style for “上一步” at every size. Render pending relation status with explicit semantic warning foreground/surface tokens, and format relation dates as `MM 月 dd 日 · HH:mm` in local time. Colors are sourced from the structured token files and generated into Compose roles.
5. **Keep dismissal state-neutral.** Outside click and Escape only clear the menu request. Candidate selection clears the request and then calls the existing `onSelected` callback, which owns draft and count updates.

Alternatives considered: retain the in-flow Web list (rejected because it changes geometry); add a nested Compose popup (rejected because option clicks did not reach Compose state in the current modal host); move relation review outside its page popup (rejected because it would change the shared page layering contract).

## Risks / Trade-offs

- [Coordinate drift after scrolling or window resize] → Re-read live anchor/root coordinates during composition and clamp the menu inside the host bounds; verify Compact, Regular, and Wide in the browser.
- [Menu focus may be inconsistent in Compose WASM] → Keep option controls focusable, return focus to the trigger on close, and exercise Enter/Space, arrow-key selection, Escape, and pointer selection in Playwright.
- [Long candidate labels may wrap] → Keep the existing text styling and a capped, scrollable option surface; validate at 390 px and 768 px.

## Migration Plan

No data or API migration. Rollback by removing the overlay host and the Web opt-in parameter, restoring the previous row-local option surface. Native remains unchanged either way.

## Open Questions

None. The user explicitly authorized prototype adjustments as approved and confirmed the overlay behavior in prior turns.
