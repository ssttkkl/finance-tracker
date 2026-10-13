## Why

Clicking a destination in the persistent sidebar leaves a blue outline around the item. The outline is useful when focus comes from keyboard navigation, but it distracts after pointer clicks and remains visible in both expanded and collapsed rail states.

## What Changes

- Show the destination focus outline for keyboard navigation.
- Hide the outline when focus comes from mouse or touch activation, while retaining the selected-item styling.

## Scope

- Applies to the shared Compose persistent navigation rail in `REGULAR` and `WIDE` windows.
- The compact drawer, destinations, route behavior, and account actions are unchanged.
- Compose Web, Android, and iOS share this presentation behavior; legacy React and Expo implementations are unaffected.

## Acceptance Criteria

- Keyboard Tab focus visibly outlines a destination in expanded and collapsed rail states.
- Pointer activation does not leave a blue outline in expanded or collapsed rail states.
- Selected destination styling and navigation continue to work.
