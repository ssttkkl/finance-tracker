## 1. 思考

- [x] 1.1 Read `docs/ui-design-rules.md`, the current Compose import screen/container, existing import presentation specs, approved Pen, and the prior HTML prototype. Confirm the mismatch with same-stage screenshots: title/close order, stepper hierarchy, content width, file drop area, filter control, and relation row structure.
- [x] 1.2 Reuse the user's already-approved prototype and preserve existing import/API/data semantics. No unresolved product choice remains; no new workflow or relation behavior is introduced.
- [x] 1.3 Cross-platform Impact Check: Compose Web and Native are affected through `commonMain`; the legacy React Web and Expo implementations are outside the edit; the shared import state and business behavior remain unchanged.

## 2. 计划与原型

- [x] 2.1 Record scope, non-goals, acceptance, risks, and the reuse of the already-confirmed Pen/HTML design in `proposal.md` and `design.md`.
- [x] 2.2 Copy the previously approved standalone HTML prototype into this change without visual edits; it remains runnable without production routes, credentials, or network dependencies.
- [x] 2.3 Verify the unchanged approved prototype in Chromium at 320/375/414/768 px and compare same-stage 390/1440 px screenshots. All widths had no horizontal overflow; evidence is in `prototype/screenshots/prototype-import-relations-{390,1440}.png`.

## 3. 任务拆分与一致性

- [x] 3.1 Add browser assertions for the header order, numbered circle stepper, capped stage width, prototype-critical labels, and Compact content/footer bounds.
- [x] 3.2 Preserve assertions for navigation, responsive modal sizing, candidate choice, relation kind, reject/restore, error/empty states, and successful confirmation.
- [x] 3.3 Check proposal, design, delta spec, tasks, approved prototype, feature map, and implementation scope for agreement.

## 4. 构建

- [x] 4.1 Correct reusable secondary-page header ordering without changing modal or compact back behavior.
- [x] 4.2 Rebuild the cash import stepper and file/mapping/review/relation stage surfaces to match the approved prototype while retaining workflow state and decisions.
- [x] 4.3 Fix width constraint ordering and add compact filter-chip and row/card treatments using existing Finance tokens.
- [x] 4.4 Update browser assertions while keeping stable semantic IDs.
- [x] 4.5 Compare Pen and prototype bounds again; remove the duplicate body inset, apply Compact/Regular/Wide content and footer padding, and align title and action sizing.
- [x] 4.6 After the user's visual review, compare the real dark-system app screenshot with the fixed-light prototype. Apply the prototype's light Finance palette to the import surface and resolve shared component colors from the active Compose theme scope.

## 5. 审查

- [x] 5.1 Product/scope review: only Compose import presentation/theme handling and its browser assertions changed; scan, mapping, review, allocation, relation choices, reject/restore, submit, and discard behavior remain covered.
- [x] 5.2 Engineering review: verified 16/32 dp content insets, 720 dp form cap, 90% modal bounds, modifier ordering, active-theme color roles, and iOS shared-source compilation. Residual limitation: the Compose WASM semantics node for the relation-actions control exposes `tabIndex=-1`; pointer activation and Escape dismissal pass, but tab-to-action focus was not proven and remains a keyboard accessibility follow-up.
- [x] 5.3 Hallmark audit action was unavailable in this desktop runtime. Manually audited the final light/dark-system browser result against `prototype/index.html`, the Pen, Finance tokens, compact/wide layout, and normal/pending relation states. Initial finding was the dark modal palette under a dark system theme; fixed by theme-scoped light palette. No remaining critical or major visual finding.
- [x] 5.4 Final diff review confirms the active `cash-import-compose-visual-alignment` files only; the unrelated `hero-ui-compose-finance-ui` directory and other active changes were left untouched.

## 6. 测试与 QA

- [x] 6.1 Run new assertions first and observe failure before implementation; earlier runs failed on missing prototype structure and the newly added focusability assertion.
- [x] 6.2 `./gradlew :shared:wasmJsBrowserTest :webApp:wasmJsBrowserDistribution --no-configuration-cache` passed (2026-10-13). Existing Gradle deprecation/configuration and Webpack bundle-size warnings remain. `:shared:compileKotlinIosSimulatorArm64` and `ANDROID_HOME=/Users/huangwenlong/Library/Android/sdk ./gradlew :shared:compileAndroidMain --no-configuration-cache` both passed. Native UI interaction QA was not run because the iOS simulator has no seeded test session/backend and no Android device is attached.
- [x] 6.3 Playwright Chromium real-browser QA used `FT_COMPOSE_PREVIEW_PORT=5188 FT_COMPOSE_COLOR_SCHEME=dark npx playwright test -c playwright.compose.config.ts --grep 'F-05' --reporter=line`; all 5 F-05 cases passed across 320/375/390/414/768/1440 px. The suite covers four-stage navigation, close/back/Escape and draft discard, import success/retry/error, empty and filtered relation states, candidate selection, relation kind, reject/restore, and modal/full-screen bounds. Browser diagnostics asserted zero unexpected console errors, failed requests, or static-resource HTTP errors. A follow-up screenshot-only hierarchy case also passed after resetting scroll and waiting for fonts.
- [x] 6.4 Reviewed 390×844 and 1440×900 Compose screenshots against the same prototype stage, with the browser context set to dark appearance to verify the intended light import surface. Prototype screenshots: `prototype/screenshots/prototype-import-relations-390.png` and `prototype/screenshots/prototype-import-relations-1440.png`; Compose screenshots: `prototype/screenshots/compose-import-file-390.png`, `compose-import-file-1440.png`, `compose-import-relations-390.png`, and `compose-import-relation-picker-1440.png`. No overflow or text clipping was visible in the reviewed captures.
- [x] 6.5 `git diff --check` passed; `openspec validate cash-import-compose-visual-alignment --strict` passed; `openspec doctor` passed. `openspec validate --all --strict` reports 27/28 valid because the unrelated, untouched `hero-ui-compose-finance-ui` change has no delta requirements and lacks `skip_specs: true`; its source was left unchanged.
- [x] 6.6 Backend/API, SQLite, and PostgreSQL matrix are not applicable: this change edits presentation only and does not change storage, API, import contracts, or financial calculations.

## 7. 发布准备

- [x] 7.1 Rollback scope is limited to the import presentation/theme, reusable theme-role lookup, tests, feature map, and this OpenSpec change; no data rollback is needed. Base/current `HEAD` is `4fedbf9` (`refactor/kmp`). Web/Wasm, Android shared-source, and iOS simulator shared-source builds passed. Native authenticated UI QA was not run because there is no seeded test session/backend for the simulator and no Android device is attached.

## 8. 反思

- [x] 8.1 Keep same-stage Pen/prototype screenshots next to Compose screenshots, test modifier order and actual bounds, and run at least one Web browser pass with dark system appearance so theme-context leaks cannot recreate the visual mismatch.

## Hallmark and Browser QA Evidence

- Hallmark: preflight and audit guidance reviewed from the repository skill. The callable audit action was not exposed in this runtime; manual final audit findings and disposition are recorded in 5.3.
- Prototype check: Chromium, standalone `prototype/index.html`, viewports 320/375/414/768/390/1440; `scrollWidth === innerWidth` at all six widths. Reference captures are listed in 6.4.
- Compose browser: Playwright Chromium, `http://127.0.0.1:5188/w/workspace-1/cash-import`, `colorScheme=dark`; 5 F-05 tests passed. Manual app tab `http://127.0.0.1:5186/w/aba98eed-8f7b-4a53-b58b-469190501fae/cash-import` was reloaded and visually checked at 1280×720 after the final build.
- Browser console/network: the shared diagnostics fixture asserted no unexpected console errors, failed requests, or static-resource HTTP errors across the passing F-05 cases.
- Current code baseline for this change: `refactor/kmp` at `4fedbf9`; `git diff --check` and targeted strict OpenSpec validation passed after these artifacts were updated. Full OpenSpec validation has the unrelated `hero-ui-compose-finance-ui` failure recorded above.
