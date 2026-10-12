# Material 3 authentication screens

## Status and revision history

The first Pen revision was confirmed on 2026-10-11 and implemented. On 2026-10-12, the user asked for a more recognizable Material 3 appearance and selected the standard outlined-field pattern, supporting text, and Material 3 control sizes and shapes. This follow-up updates the Pen design and component library through the authenticated official Pen CLI. The user confirmed the revised Pen on 2026-10-12 by asking to continue; implementation proceeds against this revision.

The saved revision has 30 boards: login and registration default, focus, validation, loading, and request-error states across `COMPACT`, `REGULAR`, and `WIDE`. The review exports are `/tmp/auth-m3-pen-review-20261012/export.pdf` (light, 14 pages) and `/tmp/auth-m3-dark-review-20261012/export.pdf` (dark, 6 pages). No authentication code was changed during this design revision. The previous implementation and its build/browser evidence remain a historical checkpoint; they do not verify this new control appearance.

## Information architecture and removed redundancy

Keep the existing login and registration title, field order, validation copy, global request error, submit action, mode entry, registration return, and all authentication behavior. Replace standalone field headings with Material 3 outlined-field labels: an empty, unfocused field shows the label inside the outline, and a focused or populated field moves it above the outline. Place field helper and validation copy in the supporting-text position directly below the corresponding field. Do not add copy, fields, explanations, or confirmation steps.

The authentication form uses reusable components in `design/finance-ui.lib.pen`:

- `Finance/Auth/M3/OutlinedTextField` for outline, resting and floating labels, input value/cursor, and optional supporting text.
- `Finance/Auth/M3/PrimaryButton` for the submit action and its disabled state.
- `Finance/Auth/M3/TextButton` for login-to-registration while retaining a 44 dp hit region.

The components change only authentication's visual presentation. Keep FinanceTheme colors and typography, existing semantic IDs, accessibility names, keyboard flow, error live regions, password masking, progress behavior, and request payloads. Keep Compose Unstyled dependencies that serve other screens.

## Responsive shell and geometry

Keep the current common authentication shell and its outer placement contract:

- `COMPACT` (`390 × 900` representative): full-width FinanceTheme surface, 24 dp horizontal inset, 52 dp top inset, 24 dp bottom inset, top aligned, and vertically scrollable.
- `REGULAR` (`768 × 900`) and `WIDE` (`1440 × 900`): FinanceTheme background with a centered 440 dp surface card, 16 dp outer page inset, 32 dp card content inset, and vertical scrolling. Card corners use the authentication-local Material 3 medium shape default, 12 dp, with no shadow.

For empty default fields, the first outlined field starts at the same vertical position in both modes. The first field also starts at the same position in validation/focus states; supporting text increases the vertical rhythm only where it is present.

| Class and mode | Card or field positions relative to screen/card | Components |
|---|---|---|
| `COMPACT` login | Title y=64; email field x=24, y=118; password y=198; submit y=278; registration text button y=334 | Field width 342 dp, field height 56 dp, button height 40 dp, mode-entry hit area 44 dp |
| `COMPACT` registration | Title y=64; email y=118; password y=198 with support y=258; confirmation y=294; submit y=374; return target x=16, y=59.5 | Field width 342 dp; return target 44 × 44 dp |
| `REGULAR`/`WIDE` login | Card 440 × 378 dp; y=261 in a 900 dp viewport; email y=86; password y=166; submit y=246; registration text button y=302 | Card content x=32; field width 376 dp; same field/button heights as compact |
| `REGULAR`/`WIDE` registration | Card 440 × 414 dp; y=243 in a 900 dp viewport; email y=86; password y=166 with support y=226; confirmation y=262; submit y=342; return target x=32, y=27.5 | Card content x=32; field width 376 dp; return target 44 × 44 dp |

Supporting text begins 4 dp after the 56 dp outline and uses 12 sp. When present, the next field starts 20 dp after the supporting-text line. Without supporting text, the next field starts 24 dp after the outline. Request-error boards place the existing global error between the fields and submit action. Card height remains content-driven and vertically centered for regular and wide layouts.

For Compose Web, the Material 3 labeled `OutlinedTextField` component includes 8 dp of label clearance above its 56 dp outline; its outer layout/semantics bounds are therefore 8 dp above the Pen outline position. The form offsets compensate for that internal M3 layout so the visible outline, supporting text, and following control align with the Pen positions. Keep this distinction when reviewing DOM bounds.

The field label rests at 16 sp inside an empty field and uses the existing Noto Sans SC font family. A focused or populated field uses a 12 sp floating label; input values remain 16 sp. The primary button keeps its 40 dp height and uses the existing FinanceTheme label typography. These font families and color values are from FinanceTheme; the label placement and Material 3 component roles are mapped explicitly in the Pen.

## Shapes, colors, and states

Use Material 3 small shape default, 8 dp, for outlined fields and primary buttons; use Material 3 medium shape default, 12 dp, for desktop cards. These values are scoped to the authentication UI and do not change the FinanceTheme shape scheme or token JSON. Keep the 44 dp registration return target and 44 dp mode-entry target.

Use existing FinanceTheme roles and token values in both themes:

| Pen role | FinanceTheme/token source | Light | Dark |
|---|---|---|---|
| `background` | `colorScheme.background` | `#F5F5F5` | `#060607` |
| `surface` | `colorScheme.surface` | `#FFFFFF` | `#18181B` |
| `foreground` | `colorScheme.onSurface` | `#18181B` | `#FCFCFC` |
| `accent` | `colorScheme.primary` | `#0485F7` | `#0485F7` |
| `accentForeground` | `colorScheme.onPrimary` | `#FCFCFC` | `#FCFCFC` |
| `outline` | `color.content.muted` / `colorScheme.outline` | `#71717A` | `#A1A1AA` |
| `danger` | `colorScheme.error` | `#D92D20` | `#F04438` |
| `disabled` | `color.semantic.disabled` | `#E4E4E7` | `#27272A` |

The `finance.authM3.outline` and `finance.authM3.disabled` Pen variables consume those existing token values. The Pen-only `finance.authM3.smallRadius` value represents Material 3's local 8 dp small shape default; 12 dp medium is applied to the shell card. Neither creates or changes a source design token.

The boards retain empty, focused, populated, validation-success, validation-error, busy/disabled, login request-error, and registration request-error states. Focus and field-error states use a 2 dp outline and the existing accent/error roles; resting states use a 1 dp outline. Busy fields keep their existing disabled presentation and progress indicator. Password inputs remain masked. Dark-mode review confirms that imported library variables resolve to the existing FinanceTheme dark values.

## Pen design and code mapping

The user selected and confirmed the standard Material 3 direction. Implement directly from this mapping; do not substitute a visual approximation.

### Reusable component mapping

| Pen component/node | Compose mapping | Required properties |
|---|---|---|
| `design/finance-ui.lib.pen` `FWJjz` `Finance/Auth/M3/OutlinedTextField` | `AccessScreens.kt` shared authentication field | `OutlinedTextField` label, input value, focus/error state, optional `supportingText`, 56 dp outline height, 8 dp auth-local shape, FinanceTheme colors and typography; account for the M3 8 dp label clearance above its outline |
| `FWJjz` child `AnYoF` Outline | `OutlinedTextField` container | 1 dp resting outline; 2 dp focused/error outline; auth-local 8 dp shape passed from `MaterialTheme.shapes.small`; surface fill and FinanceTheme outline/accent/error roles |
| `FWJjz` children `Sr8Cx` Resting label and `C0GmHd` Floating label | Material 3 `label` slot | 16 sp resting label; 12 sp floating label; float when focused or populated |
| `FWJjz` child `nBJ3j` Supporting text | Material 3 `supportingText` slot | Existing helper/validation content and status color below the field; preserve accessibility/live-region behavior |
| `design/finance-ui.lib.pen` `O8M8q` `Finance/Auth/M3/PrimaryButton` | `AccessScreens.kt` submit button | Material 3 `Button`, 40 dp height, auth-local 8 dp shape passed from `MaterialTheme.shapes.small`, FinanceTheme primary roles, existing busy/disabled state |
| `design/finance-ui.lib.pen` `rL9ZT` `Finance/Auth/M3/TextButton` | `AccessScreens.kt` mode entry | Material 3 `TextButton`, existing copy and 44 dp minimum hit region |
| Login/register title nodes on root boards | `AuthHeader` title | FinanceTheme headline typography with explicit `colorScheme.onSurface` so light/dark themes retain the foreground role |
| Registration return nodes `R220D` / `XG1MS` and their compact/regular/wide equivalents | `AuthHeader` return action | Material 3 `IconButton`, 44 × 44 dp target, 24 dp `arrow_back` tinted with `colorScheme.onSurface`, existing accessible name |
| Regular login card `Uzs5Q`; regular registration card `j58oPc`; corresponding wide cards | `AuthResponsiveShell.kt` card surface | Centered 440 dp width, 12 dp authentication-local medium shape, FinanceTheme surface, no shadow |

### Representative responsive boards

| Class | Login root board | Registration root board | Responsive implementation |
|---|---|---|---|
| `COMPACT` 390 × 900 | `SV9TY` | `bjLzq` | Full-width top-aligned shell, 24 dp content inset, scrollable |
| `REGULAR` 768 × 900 | `X30S0` | `M1ThxH` | Centered 440 dp card |
| `WIDE` 1440 × 900 | `spUwA` | `dxhA2` | Centered 440 dp card |

The same component mapping applies to each state board:

| Screen and state | `COMPACT` / `REGULAR` / `WIDE` board IDs |
|---|---|
| Login default | `SV9TY` / `X30S0` / `spUwA` |
| Login loading | `TItrE` / `G3UAD` / `CpEvl` |
| Login request error | `c4wBU` / `h0wrSu` / `lEs9S` |
| Login email focus | `EFaok` / `ztB6D` / `IfWd0` |
| Registration default | `bjLzq` / `M1ThxH` / `dxhA2` |
| Registration validation success | `j1oqoI` / `QWp0p` / `zDTNx` |
| Registration validation error | `l7piJ` / `DKm5V` / `n4FX0m` |
| Registration loading | `rkCKT` / `diPL3` / `XHEQv` |
| Registration request error | `DvN5k` / `uGbiD` / `N7OSd` |
| Registration email focus | `VCefP` / `kHblL` / `b5DSA` |

## Cross-platform impact

- Shared Compose layer: affected. `commonMain` owns the shared authentication form, Material 3 controls, and responsive shell.
- Compose Web: affected because it renders the shared Compose authentication UI; production Wasm build and real-browser QA must be rerun after implementation.
- Android and iOS Compose targets: affected because they render the same `commonMain` UI; target compilation and device presentation remain separate checks.
- Legacy React Web and Expo: unchanged and out of scope. Authentication APIs and domain contracts are unchanged.

## Risks and verification plan

- The supporting-text slot changes form height in helper, validation, and request-error states. Implement with Material 3's actual `supportingText` slot and verify that subsequent fields and actions match the Pen geometry within 2 dp.
- FinanceTheme currently defines smaller global shapes than Material 3. Apply the 8 dp and 12 dp values only through an authentication-scoped shape override; do not edit FinanceTheme or generated token files.
- Keep Compose Unstyled dependencies used by other screens; remove only authentication usage and duplicated authentication styles.
- The Pen file is authored through pen.dev's official CLI. The revision was visually reviewed at compact, regular, and wide sizes and in light/dark themes; no clipped nodes were reported. The user confirmed the revised boards on 2026-10-12.
- Rebuild the affected Compose Web target and run browser QA at 390, 768, and 1440 px across login, registration, focus, validation/error, busy/disabled, keyboard, and request-error flows. Recheck Android/iOS compilation and document any unavailable device QA. Compare the final page against the confirmed Pen boards and record screenshots, measurements, findings, and remaining risks in `tasks.md`.
