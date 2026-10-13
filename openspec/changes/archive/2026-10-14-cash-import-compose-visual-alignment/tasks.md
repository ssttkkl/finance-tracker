## 1. 思考

- [x] 1.1 Read `docs/ui-design-rules.md`, the current Compose import screen/container, existing import presentation specs, approved Pen, and the prior HTML prototype. Confirm the mismatch with same-stage screenshots: title/close order, stepper hierarchy, content width, file drop area, filter control, and relation row structure.
- [x] 1.2 Reuse the user's already-approved prototype and preserve existing import/API/data semantics. No unresolved product choice remains; no new workflow or relation behavior is introduced.
- [x] 1.3 Cross-platform Impact Check: Compose Web and Native are affected through `commonMain`; the legacy React Web and Expo implementations are outside the edit; the shared import state and business behavior remain unchanged.

## 2. 计划与原型

- [x] 2.1 Record scope, non-goals, acceptance, risks, and the reuse of the already-confirmed Pen/HTML design in `proposal.md` and `design.md`.
- [x] 2.2 Copy the previously approved standalone HTML prototype into this change without visual edits; it remains runnable without production routes, credentials, or network dependencies.
- [x] 2.3 Verify the unchanged approved prototype in Chromium at 320/375/414/768 px and compare same-stage 390/1440 px screenshots. All widths had no horizontal overflow; evidence is in `prototype/screenshots/prototype-import-relations-{390,1440}.png`.

## 3. 任务拆分与一致性

- [x] 3.1 Add browser assertions for the header order, numbered circle stepper width, capped stage width, prototype-critical labels, and Compact content/footer bounds.
- [x] 3.2 Preserve assertions for navigation, responsive modal sizing, candidate choice, relation kind, reject/restore, error/empty states, and successful confirmation.
- [x] 3.3 Check proposal, design, delta spec, tasks, approved prototype, feature map, and implementation scope for agreement.
- [x] 3.4 After the user's visual review, add regression assertions for the remaining screenshot differences: collapsed rail width, upload-area copy/density, and picker shape.
- [x] 3.5 Added relation-row parity assertions before changing the UI; the first browser run failed on the unexpected persistent `日常账户` row (Playwright `compose-pages.e2e.ts:739`, 2026-10-14), confirming the reported mismatch.

## 4. 构建

- [x] 4.1 Correct reusable secondary-page header ordering without changing modal or compact back behavior.
- [x] 4.2 Rebuild the cash import stepper and file/mapping/review/relation stage surfaces to match the approved prototype while retaining workflow state and decisions.
- [x] 4.3 Fix width constraint ordering and add compact filter-chip and row/card treatments using existing Finance tokens.
- [x] 4.4 Update browser assertions while keeping stable semantic IDs.
- [x] 4.5 Compare Pen and prototype bounds again; remove the duplicate body inset, apply Compact/Regular/Wide content and footer padding, and align title and action sizing.
- [x] 4.6 After the user's visual review, compare the real dark-system app screenshot with the fixed-light prototype. Apply the prototype's light Finance palette to the import surface and resolve shared component colors from the active Compose theme scope.
- [x] 4.7 Correct the remaining shell mismatch: apply the light palette to navigation and top bar for the cash-import route only, replace the stronger platform dialog scrim with the existing 38% overlay token on a focusable popup layer, block outside interaction, keep Escape/back dismissal, and restore system appearance when navigating away.
- [x] 4.8 Compare the same file-selection stage at 1440 px and move the stepper outside the 720 dp stage-content cap to match its prototype span and insets.
- [x] 4.9 Match the final file-selection screenshot: 72 dp collapsed rail, no permanent 100 MB helper line, prototype dropzone rhythm, and pill-shaped file-picker action; keep XLS selection and 100 MB validation behavior.
- [x] 4.10 Preserve the modal host during an open-page Regular/Wide-to-Compact resize, showing a full-screen surface with back behavior and no WASM disposed-root error.
- [x] 4.11 Align the remaining 4 dp stage offset and Compact page surface color to the prototype.
- [x] 4.12 After the user's latest side-by-side review, align the 78 dp Regular header, 22/24 dp modal radius, zero modal shadow, Compact-only footer divider, 8/16 dp Compact and 16/24 dp desktop footer padding, stacked Compact discard note/action, initial close/back focus appearance, and `CSV · XLSX · PDF` copy while keeping XLS support.
- [x] 4.13 Match the relation row to the prototype: remove visible account-name and duplicate field-label content, present one “选择流水” candidate control without extra close/action copy, and keep the status chip as the accessible entry for less-frequent relation actions. Replace Web candidate radio rows with simple text options; the native Compose popup could not dispatch selection in the nested page host, so its Web list remains in-flow and can increase the open row height (recorded as a visual limitation in `design.md`).

## 5. 审查

- [x] 5.1 Product/scope review: Compose route-level theme and the shared secondary-page modal host changed. File scanning, mapping, preview, allocation, relation choices, reject/restore, submit, and discard behavior remain unchanged and covered.
- [x] 5.2 Engineering review: verified 16/24/32 dp content insets, 36/12 dp full-width stepper insets, 720 dp form cap, 90% modal bounds, 72/78/84 dp header heights, 22/24 dp modal radii, no modal shadow, 8/16 dp Compact and 16/24 dp desktop footer padding, route-scoped theme restoration, active-theme color roles, and 38% token scrim. The full-screen popup blocks outside interaction and passes Escape/back flows. `FocusRequester` requests the close/back control after the first frame, and the captured focus ring matches the prototype. Limitation: Compose WASM places semantic controls in a ShadowRoot with `tabIndex=-1`; Playwright `toBeFocused()` still reports inactive and `ShadowRoot.activeElement` is empty, so DOM-level keyboard focus cannot be confirmed. Pointer activation and Escape dismissal pass.
- [x] 5.3 Hallmark audit action was unavailable in this desktop runtime. Manually audited the final dark-system browser result against `prototype/index.html`, the Pen, Finance tokens, and the Compact/Regular/Wide screens. The final same-stage captures show the 78 dp Regular header and matching title/dropzone positions, the shadow-free 90% modal, Compact-only footer divider, correctly stacked Compact note/action, matching desktop/Compact footer insets, and the prototype's initial blue close/back focus ring. The file-format line now matches while XLS selection remains supported. Earlier same-stage screenshots exposed a narrow Compose stepper and a too-dark platform dialog scrim; the full-width stepper and token-based 38% popup scrim now align the stage centers and unoccluded RGB (167, 167, 168) corner pixels with the prototype. Leaving the route restores system dark. The relation review now omits account names, repeated selector copy, and the ellipsis while preserving relation actions through the status chip. Web candidate selection works, but its in-flow option list increases the open row height; this remains a documented fidelity difference because popup selection did not reach Compose state. The DOM focus limitation is recorded in 5.2.
- [x] 5.4 Final diff review confirms only the cash-import route/theme, reusable secondary-page host, feature map, and active OpenSpec evidence changed; the unrelated `hero-ui-compose-finance-ui` directory and other changes were left untouched.

## 6. 测试与 QA

- [x] 6.1 Run new assertions first and observe failure before implementation: the stepper-width assertion initially failed because it inherited the 720 dp cap; the prototype format-copy assertion exposed the extra XLS label; the Compact footer/layout assertions were added before implementation. The real-browser focus assertion failed because Compose WASM's ShadowRoot semantic node did not become `document.activeElement`; this limitation is retained instead of claiming DOM focus passed.
- [x] 6.2 `cd compose && ANDROID_HOME=/Users/huangwenlong/Library/Android/sdk ./gradlew :shared:wasmJsBrowserTest :webApp:wasmJsBrowserDistribution :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64 --no-configuration-cache` passed after the final shared source edits (2026-10-14). Existing Gradle deprecation/configuration and Webpack bundle-size warnings remain. Native UI interaction QA was not run because the iOS simulator has no seeded test session/backend and no Android device is attached.
- [x] 6.3 `FT_COMPOSE_PREVIEW_PORT=5192 FT_COMPOSE_COLOR_SCHEME=dark npx playwright test -c playwright.compose.config.ts --grep 'F-05' --reporter=line` passed all 5 Chromium cases after the footer/focus changes (2026-10-14). The suite covers four-stage navigation, close/back/Escape and draft discard, import success/retry/error, relation filters/states, candidate selection, relation kind, reject/restore, and modal/full-screen bounds. The hierarchy test checks no horizontal overflow at Compact widths 320/375/390/414 px and at Regular/Wide 768/1440 px. Browser diagnostics passed for console errors, failed requests, static-resource HTTP errors, and Wasm content types.
- [x] 6.4 Captured and manually compared the same file-selection stage at 1440×900, 768×1024, and 390×844 using Chromium: `prototype/screenshots/prototype-import-file-{1440,768,390}.png` beside `prototype/screenshots/compose-import-file-{1440,768,390}.png`. Confirmed the title/dropzone start, modal shape/elevation, footer separator and action placement, Compact note stacking, and close/back focus appearance against the approved prototype. The 1440 unoccluded backdrop corner pixels match at RGB (167, 167, 168). The route was opened with dark system appearance and leaving it was checked against the dark ledger shell.
- [x] 6.5 `git diff --check` passed; `openspec validate cash-import-compose-visual-alignment --strict` passed; `openspec doctor` passed. `openspec validate --all --strict` reports 27/28 valid because the unrelated, untouched `hero-ui-compose-finance-ui` change has no delta requirements and lacks `skip_specs: true`; its source was left unchanged.
- [x] 6.6 Backend/API, SQLite, and PostgreSQL matrix are not applicable: this change edits presentation only and does not change storage, API, import contracts, or financial calculations.
- [x] 6.7 Ran the relation-parity assertions first and confirmed they failed on the unwanted account-name row; after implementation, all five F-05 Chromium cases passed. Captured same-stage Compose relation screens at 390×844 and 1440×900, plus the open candidate list at both widths. Candidate selection updates the matched/pending counts and selected target. The browser diagnostic fixture reported no unexpected console errors, failed requests, or static-resource HTTP errors. The open Web option list's row-height difference remains in the manual audit above.

## 7. 发布准备

- [x] 7.1 Rollback scope is limited to the import presentation/theme, reusable theme-role lookup, tests, feature map, and this OpenSpec change; no data rollback is needed. Final validation ran on `refactor/kmp` at `987539a` (equal to `origin/refactor/kmp` before this follow-up); the previous implementation commit `4ed5492` is pushed. Native authenticated UI QA was not run because there is no seeded test session/backend for the simulator and no Android device is attached. The Web open-menu height difference is recorded as the remaining visual risk.
- [x] 7.2 Archived the completed change to `openspec/changes/archive/2026-10-14-cash-import-compose-visual-alignment`; the main presentation spec already contains the delta behavior.

## 8. 反思

- [x] 8.1 Keep same-stage Pen/prototype screenshots next to Compose screenshots, test modifier order and actual bounds, and run at least one Web browser pass with dark system appearance so theme-context leaks cannot recreate the visual mismatch.

## Hallmark and Browser QA Evidence

- Hallmark: preflight and audit guidance reviewed from the repository skill. The callable audit action was not exposed in this runtime; manual final audit findings and disposition are recorded in 5.3.
- Prototype check: Chromium, standalone `prototype/index.html`, viewports 320/375/414/768/390/1440; `scrollWidth === innerWidth` at all six widths. Reference captures are listed in 6.4.
- Compose browser: Playwright Chromium, `http://127.0.0.1:5192/w/workspace-1/cash-import`, `colorScheme=dark`; all 5 F-05 cases passed at 390×844 and 1440×900 relation viewports, with route hierarchy and bounds also covered at 768×1024. Captures are `prototype/screenshots/compose-import-relations-{390,1440}.png` and `prototype/screenshots/compose-import-relation-picker-{390,1440}.png`. Manual app tab `http://127.0.0.1:5186/w/aba98eed-8f7b-4a53-b58b-469190501fae/cash-import` was checked at 1280×720 in the preceding UI pass.
- Browser console/network: the shared diagnostics fixture asserted no unexpected console errors, failed requests, or static-resource HTTP errors across the passing F-05 cases.
- Validation baseline: `refactor/kmp` at `987539a`, equal to `origin/refactor/kmp` before the current follow-up. `git diff --check`, targeted strict OpenSpec validation, `openspec doctor`, the Android/iOS/Wasm build matrix, and all 5 F-05 browser cases passed. Full OpenSpec validation has the unrelated `hero-ui-compose-finance-ui` failure recorded above. Previous implementation commit `4ed5492` is pushed to `origin/refactor/kmp`.
