## Context

`NavigationDestinationItem` currently draws its focus border whenever Compose reports the item focused. A pointer click also leaves focus on the item, so the blue keyboard affordance remains after the pointer interaction.

## Goals and Non-Goals

**Goals:**

- Keep the focus outline visible for keyboard navigation.
- Hide the outline for pointer-generated focus in both expanded and collapsed persistent rails.

**Non-Goals:**

- Change navigation layout, selection colors, routes, destinations, compact drawer behavior, or account controls.
- Change business behavior, persistence, or shared API contracts.

## Decision

Pass the persistent shell's existing keyboard-navigation modality state to destination items and draw their outline only when the item is focused while keyboard navigation is active. Keep the compact drawer's existing focus behavior through the parameter default; selecting a destination closes that drawer as before.

This reuses the shell's established Tab-versus-pointer distinction and adds no new state or dependency.

## Pen State Mapping

The existing [`design/app-shell-main.pen`](../../../design/app-shell-main.pen) file was updated through the official pen.dev CLI. In the `REGULAR — 展开侧栏：键盘焦点` frame (`S12JH4`), destination `V58TBO` uses the existing `$shell-accent` token for a 2 px stroke and an 8 px radius. This maps to `NavigationDestinationItem`'s 2 dp `colors.focus` border when keyboard focus is active. The `REGULAR — 768 px — 鼠标选中后收起侧栏` frame (`ZTXPa`) shows the selected icon and marker without an outline, matching pointer activation. No page layout, destination order, or account panel geometry changed.

Pen preview exports reviewed: `/tmp/app-shell-pen-focus/S12JH4.png` (keyboard focus) and `/tmp/app-shell-pen-focus/ZTXPa.png` (pointer-selected collapsed rail).

## Cross-Platform Impact

The implementation is in Compose `commonMain`, so focus indication is shared by Compose Web, Android, and iOS. Web hover and screenshot QA are browser-specific. Legacy React Web, Expo, API, persistence, and database behavior are unaffected.

## Risks and Rollback

- A modality propagation error could hide keyboard focus indication. Browser coverage checks that keyboard focus retains the outline before pointer activation.
- Roll back by reverting the focused-style condition and its browser regression test.

## UI Review

This is a localized focus-state correction with no layout or visual hierarchy change, so the existing Pen file was updated in place and no standalone prototype was needed. The Pen focus and pointer states were checked against the production screenshots. Record the Hallmark audit result or the documented manual fallback in `tasks.md`.
