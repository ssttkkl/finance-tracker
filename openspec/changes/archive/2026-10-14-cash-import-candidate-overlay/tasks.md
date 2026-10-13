## 1. 思考

- [x] 1.1 Read project context, terminology, the current presentation and token requirements, the approved import prototype, current implementation, and existing F-05 browser tests.
- [x] 1.2 Reuse the user's prior confirmation: one candidate control, overlay display without row growth, selection updates counts, Escape/outside click dismiss without changing selection. Compare the running Compose screen with the approved prototype; no open product decision remains.
- [x] 1.3 Reproduce the current mismatch before implementation: at 390 px the relation card grew from 146 px to 256 px when the candidate list opened.

## 2. 计划与原型

- [x] 2.1 Record scope, alternatives, prototype differences, risk, rollback, and shared Native/Web behavior in the proposal and design.
- [x] 2.2 Copy the previously approved standalone HTML prototype, retaining its native select overlay. The control already opens outside document layout and captures the intended no-reflow behavior; no visual restyling is needed for this interaction correction.
- [x] 2.3 Verify prototype behavior and no overflow at 320/375/414/768 px; capture same-stage 390/768/1440 px screenshots.
- [x] 2.4 Pen MCP access is unavailable because no editor document is open (`get_app_state` and `read_skill` return that limitation). This is an interaction implementation matching the already approved prototype, not a new visual direction; preserve and document the existing Pen mapping.

## 3. 任务拆分与一致性

- [x] 3.1 Copy the complete existing `Compose cash import follows the confirmed secondary-page design` requirement into the delta and add anchored overlay, no-reflow, selection, Escape, and outside-click outcomes.
- [x] 3.2 Record Cross-platform Impact Check: Web uses the page-owned candidate overlay; Native keeps the existing dropdown surface. Shared Compose content keeps identical labels, values, selection state, count updates, dismissal semantics, Compact footer actions at opposite edges, outlined “上一步”, right-aligned Regular/Wide actions, and warning colors; business/import APIs are unaffected.
- [x] 3.3 Add regression checks before implementation for unchanged relation-card bounds while open and state-neutral Escape/outside dismissal.
- [x] 3.4 Check proposal, both delta specs, design, tasks, prototype, feature map, and code scope for consistency.

## 4. 构建

- [x] 4.1 Implement the candidate overlay layer inside the existing secondary-page root for Compact, Regular, and Wide.
- [x] 4.2 Opt the import relation picker into the Web overlay while preserving other Web selectors and the Native selector.
- [x] 4.3 Update the F-05 feature-map evidence to identify the candidate overlay behavior and this change's QA record.
- [x] 4.4 Align Regular/Wide footer actions, pending-state colors, and local relation date format with the approved prototype; add generated semantic warning tokens.
- [x] 4.5 Correct the geometry test to scope its trigger to the pending relation card, and cover Regular/Wide footer action alignment.
- [x] 4.6 On prototype comparison, found Compact footer actions clustered on the left and the transparent tertiary “上一步” had no visible outline. Spread Compact footer actions across both edges and use the outlined secondary button matching the prototype.

## 5. 审查

- [x] 5.1 Product/scope review confirmed the four steps, candidate values, mapping/preview/import semantics, and copy remain in scope; only prototype mismatches were corrected.
- [x] 5.2 Engineering review confirmed same-root anchoring, viewport clamping, row-size invariance, keyboard focus/movement, Escape/outside dismissal, and state updates use the existing draft callback.
- [x] 5.3 Manually compare final Compact/Regular/Wide screens, open-menu state, footer alignment, relation date, and pending color with the approved prototype. Hallmark audit action was unavailable as a callable runtime tool; documented manual audit found 0 critical, 0 major, 0 minor findings.
- [x] 5.4 Review final diff and keep unrelated `cash-import-compose-visual-alignment` and `hero-ui-compose-finance-ui` worktree files out of the change.
- [x] 5.5 Re-review the final Compact, Regular, and Wide footer against the approved prototype after the user's visual-alignment feedback; the Compact button anchors and outlined previous action now match, with no remaining major or minor visual findings.

## 6. 测试与 QA

- [x] 6.1 Added the geometry regression before implementation; the baseline showed the Compact relation card grow from 146 px to 256 px. Then ran all F-05 browser tests against the production Wasm preview: 6 passed.
- [x] 6.2 Verified pointer selection and updated target/counts, Enter/arrow-key selection, Escape and outside-click dismissal without changing selection, unchanged Compact/Regular/Wide card bounds, and right-aligned Regular/Wide footer actions.
- [x] 6.3 In Chromium at `http://127.0.0.1:5192/w/workspace-1/cash-import`, verified Compose at 390×844, 768×1024, and 1440×900. The standalone prototype had no horizontal overflow at 320/375/414/768 px; both prototype and Compose screenshots are in `prototype/screenshots/`. Covered import success, validation/error, empty/filter states, keyboard operation, and browser diagnostics with no console/network/static HTTP/Wasm MIME failures.
- [x] 6.4 `ANDROID_HOME=/Users/huangwenlong/Library/Android/sdk ./gradlew :shared:wasmJsBrowserTest :webApp:wasmJsBrowserDistribution :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64 --no-configuration-cache` — passed. `npm run test` — 15 files / 152 tests passed. `npm run build` — passed. `openspec validate cash-import-candidate-overlay --strict`, `openspec doctor`, and `git diff --check` — passed.
- [x] 6.5 Storage and API matrices are not applicable: no data, endpoint, persistence, financial calculation, or import business contract changes.
- [x] 6.6 `FT_COMPOSE_PREVIEW_PORT=5192 npm run test:e2e:compose -- --grep 'F-05|批准的独立原型'` — 6 passed in Chromium against the production Wasm preview, including Compact 390×844 and Regular/Wide 768×1024 and 1440×900. Verified Compact actions at opposite edges and outlined previous button, Regular/Wide right-aligned outlined/primary buttons, relation selection and no-reflow overlay, Escape/outside dismissal, keyboard selection, validation/empty/success flows, and browser console/network/static HTTP/Wasm MIME diagnostics. Updated Compose and prototype screenshots are stored in `prototype/screenshots/`.

## 7. 发布准备

- [x] 7.1 Verification ran at HEAD `57092527b96b85ed7ade3aa31174418c1917ccf7`, compared with `origin/refactor/kmp` at the same commit. No financial, API, or persistence risk was introduced; rollback is limited to the candidate overlay, date formatter, footer/status presentation, and warning tokens.
- [x] 7.2 Archive the completed OpenSpec change, commit only the scoped implementation/spec/token/feature-map/browser artifacts, and push to the already-authorized `origin/refactor/kmp` branch.

## 8. 反思

- [x] 8.1 Record the prevention note: browser overlay tests must scope their trigger to the owning relation card so the overlay's “选择流水” option is not mistaken for the trigger; compare the open state and bounds at each representative window.
