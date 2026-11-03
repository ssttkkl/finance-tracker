## Context

See [proposal.md](proposal.md) for motivation and [cash-ledger-browser delta](specs/cash-ledger/cash-ledger-browser/spec.md) for the user contract. The cash projection endpoint already applies one normalized filter set to the page, month summaries, pending count, total count, and cursor. Its amount column is nullable for drafts. The first UI demo moved the pending chip into a horizontally scrolling single row in Compact; user feedback changes that row to wrapping options with a fixed right-side detail-filter action. Amount criteria live in the detailed filter group.

## Goals / Non-Goals

**Goals:**

- Keep zero-amount filtering consistent across the server, Compose client, and offline demo data.
- Keep the shared Web/Native presentation contract: quick filters wrap within the available left-side width, while the detail-filter action remains fixed at the right and vertically centered against the full wrapped region.
- Reuse existing Material 3 components, project tokens, and query/cache/cursor mechanisms.

**Non-Goals:**

- Do not change projection construction, refund relationships, the amount of a refund, accounting eligibility, or source records.
- Do not persist this preference, add a schema migration, or change filters on other pages.
- Do not add a breakpoint or responsive behavior beyond the existing `COMPACT` / `REGULAR` / `WIDE` contract.

## Decisions

1. **Filter the server projection by final displayed amount.** Add a boolean `hide_zero_amount` query field defaulting to `true`. When enabled, retain rows whose `net_amount` is unknown and otherwise require exact decimal comparison with zero to be nonzero. Apply this predicate to the shared query conditions before page selection, monthly summaries, pending/total counts, and month positions. Include the boolean in normalized filter data so cursors and summary cache keys distinguish the two settings. SQLite uses the registered exact-decimal comparison function; PostgreSQL compares its exact decimal column to decimal zero. No schema or publication change is needed.

2. **Keep the choice in current screen state.** Add a Boolean default of `true` to `CashFilters`; constructing the initial filters or clearing filters restores that default. Send both on and off values to the endpoint. Do not write it to preferences or saved state. The demo repository evaluates `CashProjection.amount` after assembling the displayed result, preserving a null amount.

3. **Place the toggle in the amount group.** Follow the supplied [`SwitchItem`](https://github.com/ssttkkl/mahjong-utils-app/blob/main/base-components/src/commonMain/kotlin/io/ssttkkl/mahjongutils/app/base/components/SwitchItem.kt) structure: a full-width Material 3 `ListItem`, the “隐藏0元流水” label at the start and a `Switch` at the end. Put it on its own line after the currency and amount-range controls. Use the same horizontal content inset as the amount controls above at every size; do not add an extra horizontal inset or shift the label into a particular amount column. Keep 8 dp vertical breathing space around the row. Checked means hidden; the switch updates the current filter immediately and keeps its default-on/reset behavior. Make the full row one toggle target with `Role.Switch`; keep the trailing `Switch` display-only with `onCheckedChange = null`, so the row and control do not create separate selection actions. Add no supporting text. The amount group's other controls remain intact.

4. **Wrap quick filters around a fixed detail-filter action.** Move the current pending chip into the quick-filter area before “全部”; place a Material 3 `VerticalDivider` between them. Keep the pending chip, divider, and “全部” chip in one indivisible group so wrapping never separates the divider from the two filters. Preserve the current pending visibility rule (shown when pending items exist or when pending is selected) and its count label. Let the remaining type-filter chips flow into additional rows within the space to the left. Keep the “筛选” action anchored at the right edge and vertically centered against the complete wrapped chip area. With enough width, the chips and action naturally fit on one row. Do not scroll any row horizontally; the page itself must not scroll horizontally. Remove the redundant standalone pending-chip row below the filter card; retain the detailed confirmation-status picker.

5. **UI hierarchy and design review.** Information order is search, wrapping quick-filter chips with a fixed right-side detail-filter action, then the result list; detailed amount criteria remain grouped under “金额”, with the zero-amount switch on a separate row after the amount controls and using their shared leading content inset. The moved pending chip is the only removed UI element because its duplicate row is no longer needed. Do not add instructional text or new colors. The action remains visible while filters wrap, and the page must not scroll horizontally. Hallmark's audit executable is not available in the current tool inventory; use the skill's manual audit checklist after implementation and record this limitation. The official Material 3 Switch overview/specs and Lists overview/specs, plus Chips overview/specs, Divider overview/specs, Expressive usability and adaptive layout pages, were read in a real Chrome browser. Adopted guidance: use a switch for an immediate standalone setting and make its state visible; keep a list item's selection accessible as one interaction with a minimum 48 dp touch target; use filter chips for selectable filters; preserve selected/focus/pressed states; use a light divider to group related controls only when spacing does not make the grouping clear; adapt the layout to available space. `SwitchItem.kt` uses `ListItem` and a trailing switch; this row uses the correct `Role.Switch` semantics for its binary setting. Current Compose Material 3 version is 1.12.0-alpha03; `Switch`, `ListItem` and `Modifier.toggleable` are available to `commonMain`, with Android/iOS/Web compilation and browser behavior to verify.

## Risks / Trade-offs

- **A default API filter changes which rows appear in ordinary cash-ledger reads.** Keep the UI and endpoint defaults aligned, add contract coverage for on/off and unknown amounts, and retain the opt-out path.
- **Null and exact zero can be confused in backend-specific SQL.** Use an explicit null-preserving predicate and exact decimal comparison on both dialects; cover known zero and unknown draft cases.
- **Wrapping increases the quick-filter region's height.** Keep the detail-filter action visible at the right and vertically centered against the full chip area, preserve chip order and focus order, keep the pending-divider-all group intact, and smoke the wrapped layout at a narrow viewport.
- **Small endpoint/service callers may rely on omitted filter arguments.** The server-side default remains `true`; include this behavior in API contract tests.

## Migration Plan

No data migration or rollout job is required. Deploy the server query default and client field together. Rollback by reverting the client and query-filter changes; source facts and published projections remain unchanged.

## Open Questions

None. Product semantics and the fixed-action wrapping layout were settled during grilling.
