## Context

See `proposal.md` for the observed failures. The shared Compose shell already expands the rail from pointer hover and keyboard focus, but currently treats all focus alike and makes the expanded-state control pin the rail open. The product surface, route model, and account-panel callbacks stay unchanged.

## Goals / Non-Goals

**Goals:**

- Keep hover expansion temporary when a mouse click leaves focus on a navigation item.
- Make the visible collapse control collapse an expanded rail immediately, including while the pointer remains over the rail.
- Preserve keyboard-driven focus expansion and click-driven control on devices without hover.

**Non-Goals:**

- Change the visual design, navigation destinations, account menu, compact drawer, routes, or business behavior.
- Change the existing accessibility names except where needed to describe the control's expand/collapse action accurately.

## Decisions

- Track keyboard navigation separately from focus presence. A key event enables keyboard-focus expansion; entering the rail by pointer switches back to pointer behavior. This preserves keyboard access without letting mouse-generated focus keep the rail open.
- Add a temporary-collapse override for the current hover/focus region. Activating the control while expanded clears pinned expansion and suppresses hover/focus expansion until the pointer and focus leave the rail/account panel; after exit, normal hover behavior resumes. Activating the control while collapsed expands it for no-hover devices and keyboard activation.
- Keep this state local to the shell. No persistence is needed because the rail's default state is collapsed on each entry, and no route or session state changes.
- Add Playwright regressions for clicking a destination then leaving, and for clicking the collapse control while hover-expanded. These distinguish pointer focus from keyboard focus and assert the re-entry behavior.

Alternatives considered:

- Removing focus-driven expansion would avoid sticky mouse focus, but would regress keyboard navigation and was rejected.
- Clearing focus only when a destination is clicked would fix one path but not the collapse control or other mouse-focused rail controls, so focus modality is tracked at the shell boundary.
- Pinning the rail open when clicking the visible left-chevron conflicts with the reported need to collapse; the expanded-state control now performs collapse.

## Risks / Trade-offs

- [Keyboard-focus expansion could be lost after pointer use] → Pointer entry deliberately switches to hover behavior; the next keyboard event re-enables focus expansion, which is covered in browser QA.
- [The collapse override could remain stuck] → Clear it only after all pointer and keyboard focus expansion sources leave; cover collapse, exit, and subsequent re-entry in Playwright.

## Migration Plan

No data or deployment migration. Roll back by reverting this focused code and test change; the prior behavior is not required for stored state compatibility.

## Open Questions

None. The expected pointer and toggle outcomes are explicit in the user reproduction.
